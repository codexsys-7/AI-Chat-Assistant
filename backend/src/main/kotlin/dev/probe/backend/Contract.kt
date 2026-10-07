package dev.probe.backend

import org.json.JSONException
import org.json.JSONObject

/**
 * `POST /v1/complete` request. `prompt` is used as the model input.
 * Preceding and following text are optional and are not invented here.
 */
internal data class IncomingRequest(
    val requestId: String,
    val action: String,
    val selectedText: String,
    val precedingText: String?,
    val followingText: String?,
    val contextQuality: String,
    val systemInstruction: String,
    val userContent: String,
)

internal fun parseIncoming(body: String): IncomingRequest? {
    return try {
        val json = JSONObject(body)
        val prompt = json.getJSONObject("prompt")
        IncomingRequest(
            requestId = requiredText(json, "requestId"),
            action = requiredText(json, "action"),
            selectedText = requiredText(json, "selectedText"),
            precedingText = optionalText(json, "precedingText"),
            followingText = optionalText(json, "followingText"),
            contextQuality = requiredText(json, "contextQuality"),
            systemInstruction = requiredText(prompt, "systemInstruction"),
            userContent = requiredText(prompt, "userContent"),
        )
    } catch (_: Exception) {
        null
    }
}

internal fun responseJson(
    requestId: String,
    responseId: String,
    content: String,
    provider: String,
    model: String,
    status: String,
): String {
    return JSONObject()
        .put("requestId", requestId)
        .put("responseId", responseId)
        .put("content", content)
        .put("provider", provider)
        .put("model", model)
        .put("status", status)
        .toString()
}

internal fun userFacingError(debug: String): String {
    return "Could not prepare a response.\nDebug: $debug"
}

private fun requiredText(json: JSONObject, key: String): String {
    if (!json.has(key) || json.isNull(key)) throw JSONException("missing $key")
    val value = json.get(key)
    if (value !is String || value.isBlank()) throw JSONException("bad $key")
    return value
}

private fun optionalText(json: JSONObject, key: String): String? {
    if (!json.has(key) || json.isNull(key)) return null
    val value = json.get(key)
    if (value !is String) throw JSONException("bad $key")
    return value
}
