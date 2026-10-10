package dev.probe.textselection.interaction

import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.engine.ContextQuality
import java.time.Instant

/**
 * Immutable request a future provider could accept.
 * Values are copied in. This type does not read the screen or the window.
 */
data class AIRequest(
    val requestId: String,
    val sessionId: String,
    val action: UserAction,
    val selectedText: String,
    val precedingContext: String?,
    val followingContext: String?,
    val sourcePackage: String?,
    val captureMethod: CaptureMethod,
    val contextQuality: ContextQuality,
    val timestamp: Instant,
)
