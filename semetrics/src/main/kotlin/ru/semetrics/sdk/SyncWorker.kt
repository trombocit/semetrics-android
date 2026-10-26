package ru.semetrics.sdk

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val TAG = "SemetricsSync"
private const val BATCH_SIZE = 200

/**
 * Периодический и разовый (flush) worker'ы могут запуститься одновременно —
 * без блокировки оба прочитают одни и те же строки и отправят их дважды.
 */
private val sendLock = Mutex()

internal enum class DrainResult { DONE, RETRY_LATER }

/**
 * Вычерпать очередь батчами. Строка удаляется из Room только после ответа сервера:
 * - успех — батч удаляется, берём следующий;
 * - постоянная ошибка (4xx, отказ сервера) — батч удаляется, чтобы не блокировать очередь навсегда;
 * - временная ошибка (сеть, DNS, 5xx) — батч остаётся первым в очереди, повтор позже.
 */
internal suspend fun drainQueue(
    dao: EventDao,
    batchSize: Int,
    send: suspend (List<EventPayload>) -> Unit,
): DrainResult {
    while (true) {
        val events = dao.getBatch(batchSize)
        if (events.isEmpty()) return DrainResult.DONE

        try {
            send(events.map { it.toPayload() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is TransportException && !e.retryable) {
                dao.deleteByIds(events.map { it.id })
                Log.e(TAG, "Батч из ${events.size} событий отклонён сервером и удалён: ${e.message}")
                continue
            }
            Log.w(TAG, "Ошибка отправки, события остаются в очереди: ${e.message}")
            return DrainResult.RETRY_LATER
        }

        dao.deleteByIds(events.map { it.id })
        Log.d(TAG, "Отправлено ${events.size} событий.")
    }
}

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

        val dao = EventDatabase.getInstance(applicationContext).eventDao()
        val transport = NetworkTransport(endpoint, apiKey)

        val result = try {
            sendLock.withLock { drainQueue(dao, BATCH_SIZE) { transport.sendBatch(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка чтения очереди: ${e.message}")
            DrainResult.RETRY_LATER
        }

        when (result) {
            DrainResult.DONE -> Result.success()
            // События остаются в Room: после 3 повторов с backoff их подберёт следующий периодический запуск
            DrainResult.RETRY_LATER -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

internal fun QueuedEvent.toPayload(): EventPayload {
    val props = propertiesJson
        ?.let { runCatching { parseSimpleJson(it) }.getOrNull() }
    return EventPayload(
        eventName = eventName,
        userId = userId,
        anonymousId = anonymousId,
        sessionId = sessionId,
        sourceId = sourceId,
        sourceVersion = sourceVersion,
        clientTs = clientTs,
        properties = props,
    )
}

private fun parseSimpleJson(json: String): Map<String, JsonElement>? {
    if (json.isBlank() || json == "{}") return null
    return runCatching {
        Json.decodeFromString<Map<String, JsonElement>>(json)
    }.getOrNull()
}
