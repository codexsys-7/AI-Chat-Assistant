package dev.probe.textselection.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Transport in front of the backend. [RemoteAIProvider] depends on this, not on a vendor SDK.
 * Tests supply a fake. The HTTP implementation sends no provider API key.
 */
internal interface AIBackendClient {
    suspend fun exchange(payload: BackendRequestPayload): BackendExchange
}

internal sealed class BackendExchange {
    data class Response(val statusCode: Int, val body: String) : BackendExchange()
    data object TimedOut : BackendExchange()
    data object Unreachable : BackendExchange()
    data object NoNetwork : BackendExchange()
}

internal class HttpAIBackendClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : AIBackendClient {
    override suspend fun exchange(payload: BackendRequestPayload): BackendExchange {
        return withContext(Dispatchers.IO) {
            execute(payload)
        }
    }

    private fun execute(payload: BackendRequestPayload): BackendExchange {
        val connection = try {
            val url = URL(backendEndpoint(baseUrl))
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
            return classifyTransportFailure(error)
        }
        return try {
            val bytes = payload.toJson().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { stream -> stream.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }.orEmpty()
            BackendExchange.Response(statusCode = code, body = body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            classifyTransportFailure(error)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun backendEndpoint(baseUrl: String): String {
    return baseUrl.trim().trimEnd('/') + "/v1/complete"
}

internal fun classifyTransportFailure(error: Exception): BackendExchange {
    return when (error) {
        is SocketTimeoutException -> BackendExchange.TimedOut
        is UnknownHostException -> BackendExchange.NoNetwork
        is ConnectException -> BackendExchange.Unreachable
        is MalformedURLException -> BackendExchange.Unreachable
        is IOException -> BackendExchange.Unreachable
        else -> BackendExchange.Unreachable
    }
}
