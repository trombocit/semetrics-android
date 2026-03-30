package ru.semetrics.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal class TransportException(message: String) : Exception(message)

internal class NetworkTransport(
    private val endpoint: String,
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val batchUrl = endpoint.trimEnd('/') + "/ingest/batch"
    private val contentType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendBatch(events: List<EventPayload>): Unit = withContext(Dispatchers.IO) {
        val payload = BatchPayload(events)
        val body = json.encodeToString(payload).toRequestBody(contentType)

        val request = Request.Builder()
            .url(batchUrl)
            .post(body)
            .addHeader("X-API-Key", apiKey)
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw TransportException("HTTP ${resp.code}: ${resp.message}")
            }
            val responseBody = resp.body?.string() ?: return@use
            val serverResponse = json.decodeFromString<ServerResponse>(responseBody)
            if (serverResponse.status.code.isNotEmpty()) {
                throw TransportException("Ошибка сервера: ${serverResponse.status.message ?: serverResponse.status.code}")
            }
        }
    }
}
