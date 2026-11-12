package dev.probe.textselection.interaction

import java.time.Instant

/**
 * One successful interaction, copied from the request and the response.
 * Process-local only. No embedding, provider, model, or screen state.
 */
data class InteractionMemory(
    val memoryId: String,
    val sessionId: String,
    val requestId: String,
    val responseId: String,
    val userAction: UserAction,
    val selectedText: String,
    val precedingContext: String?,
    val followingContext: String?,
    val generatedResponse: String,
    val sourcePackage: String?,
    val createdAt: Instant,
)
