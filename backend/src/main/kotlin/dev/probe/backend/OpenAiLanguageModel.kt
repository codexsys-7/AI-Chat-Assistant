package dev.probe.backend

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

internal sealed class ModelResult {
    data class Success(val content: String) : ModelResult()
    data class Failure(val debug: String) : ModelResult()
    data object Empty : ModelResult()
    data object TimedOut : ModelResult()
}

internal fun interface LanguageModel {
    fun complete(systemInstruction: String, userContent: String): ModelResult
}

/**
 * Server-side OpenAI chat call. The API key stays in this process.
 * Upstream error bodies are not returned to the app.
 */
internal class OpenAiLanguageModel(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : LanguageModel {
    override fun complete(systemInstruction: String, userContent: String): ModelResult {
        if (apiKey.isBlank()) {
            return ModelResult.Failure("language model is not configured")
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + "/chat/completions"))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(openAiRequestBody(model, systemInstruction, userContent)))
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            interpretOpenAi(response.statusCode(), response.body())
        } catch (_: HttpTimeoutException) {
            ModelResult.TimedOut
        } catch (_: Exception) {
            ModelResult.Failure("provider failure")
        }
    }
}

internal fun openAiRequestBody(model: String, systemInstruction: String, userContent: String): String {
    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", systemInstruction))
        .put(JSONObject().put("role", "user").put("content", userContent))
    return JSONObject()
        .put("model", model)
        .put("messages", messages)
        .toString()
}

internal fun interpretOpenAi(statusCode: Int, body: String): ModelResult {
    if (statusCode !in 200..299) {
        val debug = if (statusCode in 100..599) "provider HTTP $statusCode" else "provider failure"
        return ModelResult.Failure(debug)
    }
    return try {
        val content = JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content", "")
        if (content.isBlank()) ModelResult.Empty else ModelResult.Success(content)
    } catch (_: Exception) {
        ModelResult.Failure("provider failure")
    }
}
