package ru.semetrics.sdk

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "SemetricsSync"
private const val BATCH_SIZE = 50

/**
 * WorkManager worker — отправляет батч событий из Room на сервер.
 * Запускается периодически и по требованию.
 * Endpoint и API-ключ передаются через WorkManager Data (inputData).
 */
internal class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val endpoint = inputData.getString("endpoint") ?: return@withContext Result.failure()
        val apiKey = inputData.getString("api_key") ?: return@withContext Result.failure()

        val db = EventDatabase.getInstance(applicationContext)
        val dao = db.eventDao()
        val transport = NetworkTransport(endpoint, apiKey)

        val events = dao.getBatch(BATCH_SIZE)
        if (events.isEmpty()) return@withContext Result.success()

        val payloads = events.map { it.toPayload() }

        return@withContext try {
            transport.sendBatch(payloads)
            dao.deleteByIds(events.map { it.id })
            Log.d(TAG, "Отправлено ${events.size} событий.")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка отправки (попытка $runAttemptCount): ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun QueuedEvent.toPayload(): EventPayload {
        val props = propertiesJson
            ?.let { runCatching { parseSimpleJson(it) }.getOrNull() }
        return EventPayload(
            eventName = eventName,
            userId = userId,
            anonymousId = anonymousId,
            sessionId = sessionId,
            clientTs = clientTs,
            properties = props,
        )
    }

    /** Парсит JSON-объект с строковыми значениями в Map<String, String>. */
    private fun parseSimpleJson(json: String): Map<String, String>? {
        if (json.isBlank() || json == "{}") return null
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }
}
