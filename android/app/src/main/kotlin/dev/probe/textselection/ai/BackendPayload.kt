package dev.probe.textselection.ai

import dev.probe.textselection.interaction.ResponseStatus

/**
 * Wire payload for `POST /v1/complete`.
 *
 * ```
 * {
 *   "requestId": "...",
 *   "action": "EXPLAIN",
 *   "selectedText": "...",
 *   "precedingText": "... or null",
 *   "followingText": "... or null",
 *   "contextQuality": "HIGH",
 *   "prompt": {
 *     "systemInstruction": "...",
 *     "userContent": "..."
 *   }
 * }
 * ```
 *
 * `prompt` is the [dev.probe.textselection.interaction.Prompt] already built by
 * [PromptEngine]. This type does not rebuild it. The source package, capture
 * method, timestamp, device id, and the rest of the conversation are not fields.
 */
internal data class BackendRequestPayload(
    val requestId: String,
    val action: String,
    val selectedText: String,
    val precedingText: String?,
    val followingText: String?,
    val contextQuality: String,
    val systemInstruction: String,
    val userContent: String,
) {
    fun toJson(): String = buildString {
        append('{')
        field("requestId", jsonString(requestId))
        field("action", jsonString(action))
        field("selectedText", jsonString(selectedText))
        field("precedingText", precedingText?.let(::jsonString) ?: "null")
        field("followingText", followingText?.let(::jsonString) ?: "null")
        field("contextQuality", jsonString(contextQuality))
        append(",\"prompt\":{")
        append("\"systemInstruction\":").append(jsonString(systemInstruction))
        append(",\"userContent\":").append(jsonString(userContent))
        append("}}")
    }

    private fun StringBuilder.field(name: String, encoded: String) {
        if (isNotEmpty() && last() != '{') append(',')
        append('"').append(name).append("\":").append(encoded)
    }
}

/**
 * Wire body returned by the backend. The UI keeps using [dev.probe.textselection.interaction.AIResponse].
 *
 * ```
 * {
 *   "requestId": "...",
 *   "responseId": "...",
 *   "content": "...",
 *   "provider": "...",
 *   "model": "...",
 *   "status": "SUCCESS"
 * }
 * ```
 */
internal data class BackendResponsePayload(
    val requestId: String,
    val responseId: String,
    val content: String,
    val provider: String,
    val model: String,
    val status: ResponseStatus,
)

internal sealed class ResponseParse {
    data class Ok(val payload: BackendResponsePayload) : ResponseParse()
    data object Empty : ResponseParse()
    data object Malformed : ResponseParse()
}

internal fun parseBackendResponse(body: String?): ResponseParse {
    if (body == null || body.isBlank()) return ResponseParse.Empty
    return try {
        val json = parseJsonObject(body)
        val status = when (json["status"]) {
            "SUCCESS" -> ResponseStatus.SUCCESS
            "ERROR" -> ResponseStatus.ERROR
            else -> return ResponseParse.Malformed
        }
        val content = json["content"]
        if (content !is String) return ResponseParse.Malformed
        val payload = BackendResponsePayload(
            requestId = requiredText(json, "requestId"),
            responseId = requiredText(json, "responseId"),
            content = content,
            provider = requiredText(json, "provider"),
            model = requiredText(json, "model"),
            status = status,
        )
        ResponseParse.Ok(payload)
    } catch (_: JsonParseException) {
        ResponseParse.Malformed
    }
}

private fun requiredText(json: Map<String, Any?>, key: String): String {
    val value = json[key]
    if (value !is String || value.isBlank()) throw JsonParseException()
    return value
}
