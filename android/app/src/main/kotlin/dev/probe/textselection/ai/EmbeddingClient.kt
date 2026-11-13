package dev.probe.textselection.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * One call to `POST /v1/embeddings` on our backend.
 * Tests supply a fake. The HTTP implementation sends no provider API key.
 * [embed] is used once for the retrieval query before the prompt, and once
 * for a stored memory passage after a successful remote response.
 */
interface EmbeddingClient {
    suspend fun embed(text: String): EmbeddingResult
}

const val EMBEDDING_DIMENSIONS = 1536

sealed class EmbeddingResult {
    data class Success(
        val embedding: List<Double>,
        val model: String,
        val dimensions: Int,
    ) : EmbeddingResult()

    data object Invalid : EmbeddingResult()
    data object EmptyVector : EmbeddingResult()
    data object WrongDimension : EmbeddingResult()
    data object TimedOut : EmbeddingResult()
    data object NetworkFailure : EmbeddingResult()
    data object BackendError : EmbeddingResult()
}

internal class HttpEmbeddingClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : EmbeddingClient {
    override suspend fun embed(text: String): EmbeddingResult {
        return withContext(Dispatchers.IO) {
            execute(text)
        }
    }

    private fun execute(text: String): EmbeddingResult {
        val connection = try {
            val url = URL(embeddingEndpoint(baseUrl))
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return embeddingCallForTransport(error)
        }
        return try {
            val bytes = embeddingRequestJson(text).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { stream -> stream.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }.orEmpty()
            interpretEmbeddingHttp(code, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            embeddingCallForTransport(error)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun embeddingEndpoint(baseUrl: String): String {
    return baseUrl.trim().trimEnd('/') + "/v1/embeddings"
}

internal fun embeddingRequestJson(text: String): String {
    return "{\"text\":${jsonString(text)}}"
}

internal fun interpretEmbeddingHttp(statusCode: Int, body: String): EmbeddingResult {
    if (statusCode == 504) return EmbeddingResult.TimedOut
    if (statusCode !in 200..299) return EmbeddingResult.BackendError
    return interpretEmbeddingBody(body)
}

internal fun interpretEmbeddingBody(body: String): EmbeddingResult {
    if (body.isBlank()) return EmbeddingResult.Invalid
    return try {
        val json = parseJsonObject(body)
        val raw = json["embedding"] ?: return EmbeddingResult.Invalid
        if (raw !is List<*>) return EmbeddingResult.Invalid
        if (raw.isEmpty()) return EmbeddingResult.EmptyVector
        val vector = ArrayList<Double>(raw.size)
        for (value in raw) {
            if (value !is Double || !value.isFinite()) return EmbeddingResult.Invalid
            vector.add(value)
        }
        if (vector.size != EMBEDDING_DIMENSIONS) return EmbeddingResult.WrongDimension
        val declared = json["dimensions"]
        if (declared !is Double) return EmbeddingResult.Invalid
        val declaredInt = declared.toInt()
        if (declaredInt.toDouble() != declared || declaredInt != EMBEDDING_DIMENSIONS) {
            return EmbeddingResult.WrongDimension
        }
        val model = json["model"]
        if (model !is String || model.isBlank()) return EmbeddingResult.Invalid
        EmbeddingResult.Success(
            embedding = vector,
            model = model,
            dimensions = declaredInt,
        )
    } catch (_: JsonParseException) {
        EmbeddingResult.Invalid
    }
}

internal fun embeddingCallForTransport(error: Exception): EmbeddingResult {
    return when (classifyTransportFailure(error)) {
        BackendExchange.TimedOut -> EmbeddingResult.TimedOut
        BackendExchange.NoNetwork -> EmbeddingResult.NetworkFailure
        BackendExchange.Unreachable -> EmbeddingResult.NetworkFailure
        is BackendExchange.Response -> EmbeddingResult.NetworkFailure
    }
}
