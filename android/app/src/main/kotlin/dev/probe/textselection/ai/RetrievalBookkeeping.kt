package dev.probe.textselection.ai

import dev.probe.textselection.interaction.Prompt
import dev.probe.textselection.retrieval.RetrievalDiagnostics
import dev.probe.textselection.retrieval.formatRetrievalDebug

/**
 * Response id of the in-app response the user selected inside.
 * External PROCESS_TEXT does not record one. The id is metadata for retrieval.
 * It is not a field of [dev.probe.textselection.interaction.AIRequest].
 */
internal object OriginatingResponses {
    private const val MAX = 32
    private val lock = Any()
    private val byRequest = LinkedHashMap<String, String>()

    fun remember(requestId: String, responseId: String) {
        if (requestId.isBlank() || responseId.isBlank()) return
        synchronized(lock) {
            byRequest.remove(requestId)
            byRequest[requestId] = responseId
            while (byRequest.size > MAX) {
                val oldest = byRequest.keys.first()
                byRequest.remove(oldest)
            }
        }
    }

    fun read(requestId: String): String? = synchronized(lock) { byRequest[requestId] }

    fun clear() {
        synchronized(lock) { byRequest.clear() }
    }
}

internal fun rememberInAppOrigin(requestId: String, originatingResponseId: String) {
    OriginatingResponses.remember(requestId, originatingResponseId)
}

internal data class StoredRetrievalDebug(
    val diagnostics: RetrievalDiagnostics,
    val systemInstruction: String,
    val userContent: String,
    val section: String,
)

/**
 * The retrieval record and the prompt built from it, keyed by request id.
 * [section] is formatted once here. The request screen copies that string.
 */
internal object RetrievalDebugLog {
    private const val MAX = 32
    private val lock = Any()
    private val byRequest = LinkedHashMap<String, StoredRetrievalDebug>()

    fun save(requestId: String, diagnostics: RetrievalDiagnostics, prompt: Prompt) {
        if (requestId.isBlank()) return
        val stored = StoredRetrievalDebug(
            diagnostics = diagnostics,
            systemInstruction = prompt.systemInstruction,
            userContent = prompt.userContent,
            section = formatRetrievalDebug(diagnostics),
        )
        synchronized(lock) {
            byRequest.remove(requestId)
            byRequest[requestId] = stored
            while (byRequest.size > MAX) {
                val oldest = byRequest.keys.first()
                byRequest.remove(oldest)
            }
        }
    }

    fun find(requestId: String): StoredRetrievalDebug? = synchronized(lock) { byRequest[requestId] }

    fun clear() {
        synchronized(lock) { byRequest.clear() }
    }
}

internal fun clearRetrievalBookkeeping() {
    OriginatingResponses.clear()
    RetrievalDebugLog.clear()
}
