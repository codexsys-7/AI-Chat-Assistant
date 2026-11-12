package dev.probe.textselection.ai

import dev.probe.textselection.interaction.AIProvider
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.EmbeddingStatus
import dev.probe.textselection.interaction.InteractionMemory
import dev.probe.textselection.interaction.InteractionMemoryStore
import dev.probe.textselection.interaction.ResponseStatus
import dev.probe.textselection.interaction.memoryPassage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Builds a prompt, then asks whichever [AIProvider] it was given.
 * It does not choose a vendor and it does not retry.
 * After a successful response it copies the interaction into [memoryStore].
 * That copy does not change the [AIResponse] returned to the caller.
 *
 * A successful [RemoteAIProvider] interaction also starts one embedding of the
 * stored passage. The response is returned before that call finishes.
 */
class AIService(
    private val provider: AIProvider,
    private val promptEngine: PromptEngine = PromptEngine,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val memoryStore: InteractionMemoryStore = InteractionMemoryStore(),
    private val now: () -> Instant = { Instant.now() },
    private val embeddings: EmbeddingClient? = null,
    private val embeddingScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val embeddingLogger: EmbeddingLogger = embeddingCategoryLogger,
) {
    suspend fun generate(request: AIRequest): AIResponse {
        val response = try {
            val prompt = promptEngine.build(request)
            provider.generateResponse(request, prompt)
        } catch (_: Exception) {
            AIResponse(
                responseId = newId(),
                requestId = request.requestId,
                sessionId = request.sessionId,
                action = request.action,
                content = ERROR_MESSAGE,
                provider = provider.providerName,
                model = provider.modelName,
                status = ResponseStatus.ERROR,
            )
        }
        remember(request, response)
        return response
    }

    private fun remember(request: AIRequest, response: AIResponse) {
        if (!shouldRemember(request, response)) return
        val embedRemotely = embeddings != null && provider is RemoteAIProvider
        val memory = InteractionMemory(
            memoryId = newId(),
            sessionId = request.sessionId,
            requestId = request.requestId,
            responseId = response.responseId,
            userAction = request.action,
            selectedText = request.selectedText,
            precedingContext = request.precedingContext,
            followingContext = request.followingContext,
            generatedResponse = response.content,
            sourcePackage = request.sourcePackage,
            createdAt = now(),
            embedding = null,
            embeddingStatus = if (embedRemotely) EmbeddingStatus.PENDING else null,
        )
        val saved = try {
            memoryStore.save(memory)
            true
        } catch (_: Exception) {
            false
        }
        if (saved && embedRemotely) {
            scheduleEmbedding(memory)
        }
    }

    /**
     * Schedules one embedding attempt. It does not retry.
     * A later controlled retry can call [embedStoredMemory] again.
     */
    private fun scheduleEmbedding(memory: InteractionMemory) {
        val client = embeddings ?: return
        embeddingScope.launch {
            embedStoredMemory(client, memory)
        }
    }

    /**
     * Embeds [memory] once and updates that same id.
     * Does not insert another memory and does not loop.
     */
    internal suspend fun embedStoredMemory(client: EmbeddingClient, memory: InteractionMemory) {
        val result = try {
            client.embed(memoryPassage(memory))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            embeddingLogger.log(EmbeddingLog.UNEXPECTED)
            memoryStore.update(memory.copy(embedding = null, embeddingStatus = EmbeddingStatus.FAILED))
            return
        }
        memoryStore.update(appliedEmbedding(memory, result))
    }

    private fun appliedEmbedding(memory: InteractionMemory, result: EmbeddingResult): InteractionMemory {
        return when (result) {
            is EmbeddingResult.Success -> {
                when {
                    result.embedding.isEmpty() -> {
                        embeddingLogger.log(EmbeddingLog.EMPTY_VECTOR)
                        failed(memory)
                    }
                    result.embedding.size != EMBEDDING_DIMENSIONS || result.dimensions != EMBEDDING_DIMENSIONS -> {
                        embeddingLogger.log(EmbeddingLog.WRONG_DIMENSION)
                        failed(memory)
                    }
                    else -> memory.copy(
                        embedding = result.embedding,
                        embeddingStatus = EmbeddingStatus.COMPLETED,
                    )
                }
            }
            EmbeddingResult.EmptyVector -> {
                embeddingLogger.log(EmbeddingLog.EMPTY_VECTOR)
                failed(memory)
            }
            EmbeddingResult.WrongDimension -> {
                embeddingLogger.log(EmbeddingLog.WRONG_DIMENSION)
                failed(memory)
            }
            EmbeddingResult.Invalid -> {
                embeddingLogger.log(EmbeddingLog.INVALID)
                failed(memory)
            }
            EmbeddingResult.TimedOut -> {
                embeddingLogger.log(EmbeddingLog.TIMEOUT)
                failed(memory)
            }
            EmbeddingResult.NetworkFailure -> {
                embeddingLogger.log(EmbeddingLog.NETWORK)
                failed(memory)
            }
            EmbeddingResult.BackendError -> {
                embeddingLogger.log(EmbeddingLog.BACKEND)
                failed(memory)
            }
        }
    }

    private fun failed(memory: InteractionMemory): InteractionMemory {
        return memory.copy(embedding = null, embeddingStatus = EmbeddingStatus.FAILED)
    }

    private fun shouldRemember(request: AIRequest, response: AIResponse): Boolean {
        if (response.status != ResponseStatus.SUCCESS) return false
        if (response.content.isBlank()) return false
        if (request.selectedText.isBlank()) return false
        if (request.sessionId.isBlank()) return false
        if (response.sessionId.isBlank()) return false
        return true
    }

    companion object {
        const val ERROR_MESSAGE = "Could not prepare a response."
    }
}

fun interface EmbeddingLogger {
    fun log(category: String)
}

internal object EmbeddingLog {
    const val TIMEOUT = "timeout"
    const val NETWORK = "network"
    const val BACKEND = "backend"
    const val INVALID = "invalid"
    const val EMPTY_VECTOR = "empty-vector"
    const val WRONG_DIMENSION = "wrong-dimension"
    const val UNEXPECTED = "unexpected"
}

val embeddingCategoryLogger = EmbeddingLogger { category ->
    try {
        android.util.Log.i(EMBEDDING_LOG_TAG, category)
    } catch (_: RuntimeException) {
        // JVM unit tests do not implement android.util.Log.
    }
}

internal const val EMBEDDING_LOG_TAG = "Embedding"
