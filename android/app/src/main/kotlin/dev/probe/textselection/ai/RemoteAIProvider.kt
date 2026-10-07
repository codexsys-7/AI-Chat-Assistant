package dev.probe.textselection.ai

import dev.probe.textselection.interaction.AIProvider
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.Prompt
import dev.probe.textselection.interaction.ResponseStatus
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Sends the already-built [Prompt] to our backend and maps the reply onto [AIResponse].
 * It does not know which vendor the backend called, and it does not rebuild prompts.
 */
class RemoteAIProvider internal constructor(
    private val client: AIBackendClient,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AIProvider {
    override val providerName: String = PROVIDER_NAME
    override val modelName: String = MODEL_NAME

    override suspend fun generateResponse(request: AIRequest, prompt: Prompt): AIResponse {
        val payload = BackendRequestPayload(
            requestId = request.requestId,
            action = request.action.name,
            selectedText = request.selectedText,
            precedingText = request.precedingContext,
            followingText = request.followingContext,
            contextQuality = request.contextQuality.name,
            systemInstruction = prompt.systemInstruction,
            userContent = prompt.userContent,
        )
        return try {
            when (val exchange = client.exchange(payload)) {
                BackendExchange.TimedOut -> failure(request, DEBUG_TIMED_OUT)
                BackendExchange.Unreachable -> failure(request, DEBUG_UNREACHABLE)
                BackendExchange.NoNetwork -> failure(request, DEBUG_NO_NETWORK)
                is BackendExchange.Response -> fromHttp(request, exchange.statusCode, exchange.body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(request, DEBUG_UNEXPECTED)
        }
    }

    private fun fromHttp(request: AIRequest, statusCode: Int, body: String): AIResponse {
        val httpDebug = httpDebug(statusCode)
        if (body.isBlank()) {
            val debug = if (statusCode in 200..299) DEBUG_EMPTY_RESPONSE else httpDebug
            return failure(request, debug)
        }
        return when (val parsed = parseBackendResponse(body)) {
            ResponseParse.Empty -> failure(
                request,
                if (statusCode in 200..299) DEBUG_EMPTY_RESPONSE else httpDebug,
            )
            ResponseParse.Malformed -> failure(
                request,
                if (statusCode in 200..299) DEBUG_MALFORMED else httpDebug,
            )
            is ResponseParse.Ok -> {
                if (statusCode !in 200..299 && parsed.payload.status != ResponseStatus.ERROR) {
                    failure(request, httpDebug)
                } else {
                    validated(request, parsed.payload, statusCode)
                }
            }
        }
    }

    private fun validated(
        request: AIRequest,
        payload: BackendResponsePayload,
        statusCode: Int,
    ): AIResponse {
        if (payload.requestId != request.requestId) {
            val debug = if (statusCode in 200..299) DEBUG_MALFORMED else httpDebug(statusCode)
            return failure(request, debug)
        }
        if (payload.status == ResponseStatus.SUCCESS) {
            if (payload.content.isBlank()) {
                return failure(
                    request,
                    DEBUG_EMPTY_CONTENT,
                    responseId = payload.responseId,
                    provider = payload.provider,
                    model = payload.model,
                )
            }
            return AIResponse(
                responseId = payload.responseId,
                requestId = request.requestId,
                action = request.action,
                content = payload.content,
                provider = payload.provider,
                model = payload.model,
                status = ResponseStatus.SUCCESS,
            )
        }
        val content = if (payload.content.isBlank() || containsSecretMarker(payload.content)) {
            remoteErrorContent(DEBUG_EMPTY_CONTENT)
        } else {
            payload.content
        }
        return AIResponse(
            responseId = payload.responseId,
            requestId = request.requestId,
            action = request.action,
            content = content,
            provider = payload.provider,
            model = payload.model,
            status = ResponseStatus.ERROR,
        )
    }

    private fun failure(
        request: AIRequest,
        debug: String,
        responseId: String = newId(),
        provider: String = providerName,
        model: String = modelName,
    ): AIResponse {
        return AIResponse(
            responseId = responseId,
            requestId = request.requestId,
            action = request.action,
            content = remoteErrorContent(debug),
            provider = provider,
            model = model,
            status = ResponseStatus.ERROR,
        )
    }

    companion object {
        const val PROVIDER_NAME = "RemoteAIProvider"
        const val MODEL_NAME = "unavailable"

        const val DEBUG_TIMED_OUT = "timed out"
        const val DEBUG_UNREACHABLE = "backend unreachable"
        const val DEBUG_NO_NETWORK = "no network"
        const val DEBUG_EMPTY_RESPONSE = "empty response"
        const val DEBUG_MALFORMED = "malformed response"
        const val DEBUG_EMPTY_CONTENT = "empty content"
        const val DEBUG_UNEXPECTED = "unexpected error"
    }
}

internal fun httpDebug(statusCode: Int): String {
    return if (statusCode in 100..599) "HTTP $statusCode" else "HTTP error"
}

internal fun remoteErrorContent(debug: String): String {
    return AIService.ERROR_MESSAGE + "\nDebug: " + debug
}

internal fun containsSecretMarker(content: String): Boolean {
    val lowered = content.lowercase()
    return lowered.contains("sk-") ||
        lowered.contains("bearer ") ||
        lowered.contains("api_key") ||
        lowered.contains("authorization:")
}
