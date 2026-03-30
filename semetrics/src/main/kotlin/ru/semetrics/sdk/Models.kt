package ru.semetrics.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Сериализуемое событие для отправки на сервер. */
@Serializable
internal data class EventPayload(
    @SerialName("event_name")   val eventName: String,
    @SerialName("user_id")      val userId: String?,
    @SerialName("anonymous_id") val anonymousId: String?,
    @SerialName("session_id")   val sessionId: String?,
    val platform: String = "android",
    @SerialName("sdk_version")  val sdkVersion: String = BuildConfig.SDK_VERSION,
    @SerialName("client_ts")    val clientTs: String,   // ISO-8601
    val properties: Map<String, String>? = null,        // JSON-строки значений
)

/** Батч для POST /ingest/batch */
@Serializable
internal data class BatchPayload(
    val events: List<EventPayload>,
)

/** Ответ сервера */
@Serializable
internal data class ServerResponse(
    val status: Status,
) {
    @Serializable
    data class Status(
        val code: String,
        val message: String? = null,
    )
}

/** Версия SDK, подставляется через buildConfigField в build.gradle.kts */
internal object BuildConfig {
    const val SDK_VERSION = "0.1.0"
}
