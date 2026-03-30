package ru.semetrics.sdk

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "Semetrics"
private const val WORK_NAME = "semetrics_periodic_sync"

/**
 * Главный клиент Semetrics SDK для Android.
 *
 * Пример использования:
 * ```kotlin
 * // Application.onCreate()
 * Semetrics.configure(
 *     context = applicationContext,
 *     apiKey = "sm_live_...",
 *     endpoint = "https://semetrics.ru/events",
 * )
 *
 * // В любом месте приложения
 * Semetrics.track("button_clicked", userId = "u1", properties = mapOf("button" to "checkout"))
 * ```
 */
object Semetrics {

    private var config: Config? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    data class Config(
        val context: Context,
        val apiKey: String,
        val endpoint: String,
    )

    // MARK: - Публичный API

    /**
     * Инициализация SDK. Вызывать один раз в Application.onCreate().
     * Регистрирует WorkManager для периодической доставки.
     */
    fun configure(
        context: Context,
        apiKey: String,
        endpoint: String = "https://semetrics.ru/events",
        syncIntervalMinutes: Long = 15,
    ) {
        config = Config(context.applicationContext, apiKey, endpoint)
        schedulePeriodicSync(context, apiKey, endpoint, syncIntervalMinutes)
        Log.d(TAG, "SDK инициализирован.")
    }

    /**
     * Добавить событие в очередь (не блокирует основной поток).
     */
    fun track(
        eventName: String,
        userId: String? = null,
        anonymousId: String? = null,
        sessionId: String? = null,
        properties: Map<String, Any>? = null,
    ) {
        val cfg = config ?: run {
            Log.e(TAG, "Вызовите Semetrics.configure() перед track()")
            return
        }

        val propertiesJson = properties
            ?.mapValues { it.value.toString() }
            ?.let { JSONObject(it).toString() }

        scope.launch {
            val db = EventDatabase.getInstance(cfg.context)
            db.eventDao().insert(
                QueuedEvent(
                    eventName = eventName,
                    userId = userId,
                    anonymousId = anonymousId,
                    sessionId = sessionId,
                    propertiesJson = propertiesJson,
                    clientTs = isoFormatter.format(Instant.now()),
                )
            )
        }
    }

    /**
     * Немедленно запустить синхронизацию (OneTimeWorkRequest).
     */
    fun flush() {
        val cfg = config ?: return
        val data = workData(cfg.apiKey, cfg.endpoint)
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(cfg.context).enqueue(work)
    }

    // MARK: - Приватные методы

    private fun schedulePeriodicSync(
        context: Context,
        apiKey: String,
        endpoint: String,
        intervalMinutes: Long,
    ) {
        val data = workData(apiKey, endpoint)
        val work = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setInputData(data)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun workData(apiKey: String, endpoint: String): Data =
        workDataOf("api_key" to apiKey, "endpoint" to endpoint)
}
