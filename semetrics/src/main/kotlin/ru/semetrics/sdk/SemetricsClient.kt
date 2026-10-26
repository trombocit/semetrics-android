package ru.semetrics.sdk

import android.app.Application
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
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Semetrics"
private const val WORK_NAME = "semetrics_periodic_sync"
private const val FLUSH_WORK_NAME = "semetrics_flush"

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

    @Volatile private var config: Config? = null
    private val lifecycleRegistered = AtomicBoolean(false)
    private val overflowLogged = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    data class Config(
        val context: Context,
        val apiKey: String,
        val endpoint: String,
        val sourceId: String?,
        val sourceVersion: String?,
        val maxQueueSize: Int,
    )

    // MARK: - Публичный API

    /**
     * Инициализация SDK. Вызывать один раз в Application.onCreate().
     * Регистрирует WorkManager для периодической доставки и автоматический flush
     * при уходе приложения в фон и возврате из него.
     *
     * @param maxQueueSize максимум событий, ожидающих отправки; сверх лимита новые события отбрасываются.
     */
    fun configure(
        context: Context,
        apiKey: String,
        endpoint: String = "https://semetrics.ru/events",
        sourceId: String? = null,
        sourceVersion: String? = null,
        syncIntervalMinutes: Long = 15,
        maxQueueSize: Int = 10_000,
    ) {
        config = Config(context.applicationContext, apiKey, endpoint, sourceId, sourceVersion, maxQueueSize)
        schedulePeriodicSync(context, apiKey, endpoint, syncIntervalMinutes)
        registerLifecycleFlush(context)
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
            ?.let { JSONObject(it as Map<*, *>).toString() }

        scope.launch {
            val dao = EventDatabase.getInstance(cfg.context).eventDao()
            if (dao.count() >= cfg.maxQueueSize) {
                if (overflowLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "Очередь переполнена (${cfg.maxQueueSize}), новые события отбрасываются.")
                }
                return@launch
            }
            overflowLogged.set(false)
            dao.insert(
                QueuedEvent(
                    eventName = eventName,
                    userId = userId,
                    anonymousId = anonymousId,
                    sessionId = sessionId,
                    sourceId = cfg.sourceId,
                    sourceVersion = cfg.sourceVersion,
                    propertiesJson = propertiesJson,
                    clientTs = isoFormatter.format(Instant.now()),
                )
            )
        }
    }

    /**
     * Немедленно запустить синхронизацию (OneTimeWorkRequest).
     * Если flush уже ждёт сети или выполняется — повторный вызов ничего не добавляет.
     */
    fun flush() {
        val cfg = config ?: return
        val data = workData(cfg.apiKey, cfg.endpoint, cfg.sourceId, cfg.sourceVersion)
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(cfg.context).enqueueUniqueWork(
            FLUSH_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    // MARK: - Приватные методы

    private fun schedulePeriodicSync(
        context: Context,
        apiKey: String,
        endpoint: String,
        intervalMinutes: Long,
    ) {
        val data = workData(apiKey, endpoint, config?.sourceId, config?.sourceVersion)
        val work = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setInputData(data)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        // UPDATE, а не KEEP: иначе смена apiKey/endpoint/интервала не применится к уже созданной задаче
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }

    private fun registerLifecycleFlush(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(AppVisibilityObserver { flush() })
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun workData(apiKey: String, endpoint: String, sourceId: String?, sourceVersion: String? = null): Data =
        workDataOf("api_key" to apiKey, "endpoint" to endpoint, "source_id" to sourceId, "source_version" to sourceVersion)
}
