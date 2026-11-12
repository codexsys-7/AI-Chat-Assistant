package dev.probe.backend

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

internal const val EMBEDDINGS_PATH = "/v1/embeddings"
internal const val EMBEDDING_MODEL = "text-embedding-3-small"
internal const val EMBEDDING_DIMENSIONS = 1536

internal sealed class EmbeddingModelResult {
    data class Success(val vector: List<Double>) : EmbeddingModelResult()
    data class Failure(val debug: String) : EmbeddingModelResult()
    data object TimedOut : EmbeddingModelResult()
    data object UnexpectedVector : EmbeddingModelResult()
}

internal fun interface EmbeddingModel {
    fun embed(text: String): EmbeddingModelResult
}

internal sealed class EmbeddingRequestParse {
    data class Text(val text: String) : EmbeddingRequestParse()
    data object Invalid : EmbeddingRequestParse()
}

/**
 * `POST /v1/embeddings`. The body is JSON `{"text":"..."}`.
 * The chat completion contract is not used here.
 */
internal class EmbeddingHandler(
    private val model: EmbeddingModel,
    private val modelName: String = EMBEDDING_MODEL,
    private val dimensions: Int = EMBEDDING_DIMENSIONS,
    private val log: (String) -> Unit = {},
) {
    fun handle(method: String, path: String, body: String): Reply {
        if (path != EMBEDDINGS_PATH) {
            return embeddingError(404, "not found")
        }
        if (!method.equals("POST", ignoreCase = true)) {
            event("method")
            return embeddingError(405, "method not allowed")
        }
        if (body.length > MAX_BODY_CHARS) {
            event("too-large")
            return embeddingError(413, "request too large")
        }
        return when (val parsed = parseEmbeddingRequest(body)) {
            EmbeddingRequestParse.Invalid -> {
                event("invalid-request")
                embeddingError(400, "invalid request")
            }
            is EmbeddingRequestParse.Text -> {
                if (parsed.text.isBlank()) {
                    event("empty-text")
                    embeddingError(400, "empty text")
                } else if (parsed.text.length > MAX_BODY_CHARS) {
                    event("too-large")
                    embeddingError(413, "request too large")
                } else {
                    when (val result = model.embed(parsed.text)) {
                        is EmbeddingModelResult.Success -> accept(result.vector)
                        EmbeddingModelResult.TimedOut -> {
                            event("timeout")
                            embeddingError(504, "timed out")
                        }
                        EmbeddingModelResult.UnexpectedVector -> {
                            event("unexpected-vector")
                            embeddingError(502, "unexpected vector")
                        }
                        is EmbeddingModelResult.Failure -> {
                            event("provider-error")
                            embeddingError(502, safeEmbeddingDebug(result.debug))
                        }
                    }
                }
            }
        }
    }

    fun unexpected(): Reply {
        event("unexpected")
        return embeddingError(500, "unexpected error")
    }

    private fun accept(vector: List<Double>): Reply {
        if (vector.size != dimensions || vector.any { !it.isFinite() }) {
            event("unexpected-vector")
            return embeddingError(502, "unexpected vector")
        }
        return Reply(
            status = 200,
            body = embeddingResponseJson(vector, modelName, dimensions),
        )
    }

    private fun event(category: String) {
        log(category)
    }
}

internal fun parseEmbeddingRequest(body: String): EmbeddingRequestParse {
    return try {
        val json = JSONObject(body)
        if (!json.has("text") || json.isNull("text")) return EmbeddingRequestParse.Invalid
        val value = json.get("text")
        if (value !is String) return EmbeddingRequestParse.Invalid
        EmbeddingRequestParse.Text(value)
    } catch (_: Exception) {
        EmbeddingRequestParse.Invalid
    }
}

internal fun embeddingResponseJson(vector: List<Double>, model: String, dimensions: Int): String {
    val embedding = JSONArray()
    vector.forEach { value -> embedding.put(value) }
    return JSONObject()
        .put("embedding", embedding)
        .put("model", model)
        .put("dimensions", dimensions)
        .toString()
}

internal fun embeddingError(status: Int, reason: String): Reply {
    return Reply(
        status = status,
        body = JSONObject().put("error", safeEmbeddingDebug(reason)).toString(),
    )
}

internal fun safeEmbeddingDebug(reason: String): String {
    return when (reason) {
        "timed out",
        "unexpected vector",
        "embedding model is not configured",
        "provider failure",
        "empty text",
        "invalid request",
        "request too large",
        "method not allowed",
        "unexpected error",
        "not found",
        -> reason
        else -> if (reason.matches(Regex("provider HTTP \\d{3}"))) reason else "provider failure"
    }
}

/**
 * Server-side OpenAI embeddings call. The API key stays in this process.
 * Upstream bodies are not returned or logged.
 */
internal class OpenAiEmbeddingModel(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String = EMBEDDING_MODEL,
    private val dimensions: Int = EMBEDDING_DIMENSIONS,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : EmbeddingModel {
    override fun embed(text: String): EmbeddingModelResult {
        if (apiKey.isBlank()) {
            return EmbeddingModelResult.Failure("embedding model is not configured")
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + "/embeddings"))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(openAiEmbeddingBody(model, text, dimensions)))
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            interpretOpenAiEmbedding(response.statusCode(), response.body(), dimensions)
        } catch (_: HttpTimeoutException) {
            EmbeddingModelResult.TimedOut
        } catch (_: Exception) {
            EmbeddingModelResult.Failure("provider failure")
        }
    }
}

internal fun openAiEmbeddingBody(model: String, input: String, dimensions: Int): String {
    return JSONObject()
        .put("model", model)
        .put("input", input)
        .put("dimensions", dimensions)
        .toString()
}

internal fun interpretOpenAiEmbedding(
    statusCode: Int,
    body: String,
    dimensions: Int,
): EmbeddingModelResult {
    if (statusCode !in 200..299) {
        val debug = if (statusCode in 100..599) "provider HTTP $statusCode" else "provider failure"
        return EmbeddingModelResult.Failure(debug)
    }
    return try {
        val data = JSONObject(body).getJSONArray("data")
        if (data.length() != 1) return EmbeddingModelResult.UnexpectedVector
        val embedding = data.getJSONObject(0).getJSONArray("embedding")
        readEmbeddingVector(embedding, dimensions)
    } catch (_: Exception) {
        EmbeddingModelResult.Failure("provider failure")
    }
}

private fun readEmbeddingVector(embedding: JSONArray, dimensions: Int): EmbeddingModelResult {
    if (embedding.length() != dimensions) return EmbeddingModelResult.UnexpectedVector
    val vector = ArrayList<Double>(embedding.length())
    for (index in 0 until embedding.length()) {
        if (embedding.isNull(index)) return EmbeddingModelResult.UnexpectedVector
        val number = embedding.get(index)
        if (number !is Number) return EmbeddingModelResult.UnexpectedVector
        val value = number.toDouble()
        if (!value.isFinite()) return EmbeddingModelResult.UnexpectedVector
        vector.add(value)
    }
    return EmbeddingModelResult.Success(vector)
}
