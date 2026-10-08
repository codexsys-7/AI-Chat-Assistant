package dev.probe.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID

internal data class Reply(val status: Int, val body: String)

internal const val COMPLETE_PATH = "/v1/complete"
internal const val MAX_BODY_CHARS = 64 * 1024
internal const val PROVIDER_NAME = "OpenAI"

/**
 * Accepts the app contract, forwards the prompt unchanged, and returns the same contract.
 */
internal class CompletionHandler(
    private val model: LanguageModel,
    private val modelName: String,
    private val providerName: String = PROVIDER_NAME,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun handle(method: String, path: String, body: String): Reply {
        if (path != COMPLETE_PATH) {
            return errorReply(404, "not found", requestId = "")
        }
        if (!method.equals("POST", ignoreCase = true)) {
            return errorReply(405, "method not allowed", requestId = "")
        }
        if (body.length > MAX_BODY_CHARS) {
            return errorReply(413, "request too large", requestId = "")
        }
        val incoming = parseIncoming(body)
        if (incoming == null) {
            return errorReply(400, "malformed request", requestId = "")
        }
        val responseId = newId()
        return when (val result = model.complete(incoming.systemInstruction, incoming.userContent)) {
            is ModelResult.Success -> Reply(
                status = 200,
                body = responseJson(
                    requestId = incoming.requestId,
                    responseId = responseId,
                    content = result.content,
                    provider = providerName,
                    model = modelName,
                    status = "SUCCESS",
                    sessionId = incoming.sessionId,
                ),
            )
            ModelResult.Empty -> errorReply(502, "empty content", incoming.requestId, responseId, incoming.sessionId)
            ModelResult.TimedOut -> errorReply(504, "timed out", incoming.requestId, responseId, incoming.sessionId)
            is ModelResult.Failure -> errorReply(502, safeDebug(result.debug), incoming.requestId, responseId, incoming.sessionId)
        }
    }

    fun unexpected(): Reply = errorReply(500, "unexpected error", requestId = "")

    private fun errorReply(
        status: Int,
        debug: String,
        requestId: String,
        responseId: String = newId(),
        sessionId: String? = null,
    ): Reply {
        return Reply(
            status = status,
            body = responseJson(
                requestId = requestId.ifBlank { "unknown" },
                responseId = responseId,
                content = userFacingError(safeDebug(debug)),
                provider = providerName,
                model = modelName,
                status = "ERROR",
                sessionId = sessionId,
            ),
        )
    }
}

internal fun safeDebug(reason: String): String {
    return when (reason) {
        "timed out",
        "empty content",
        "provider failure",
        "language model is not configured",
        "not found",
        "method not allowed",
        "request too large",
        "malformed request",
        "unexpected error",
        -> reason
        else -> if (reason.matches(Regex("provider HTTP \\d{3}"))) reason else "provider failure"
    }
}

internal fun startCompletionServer(
    port: Int,
    handler: CompletionHandler,
    host: String = "0.0.0.0",
): HttpServer {
    val server = HttpServer.create(InetSocketAddress(host, port), 0)
    server.createContext("/") { exchange ->
        val reply = try {
            val path = exchange.requestURI.path ?: "/"
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
            handler.handle(exchange.requestMethod, path, body)
        } catch (_: Exception) {
            handler.unexpected()
        }
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
        exchange.responseBody.use { stream -> stream.write(bytes) }
    }
    server.start()
    return server
}
