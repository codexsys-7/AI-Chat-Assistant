package dev.probe.textselection.interaction

import dev.probe.textselection.engine.ContextPackage
import java.time.Instant
import java.util.UUID

/**
 * Builds an [AIRequest] from a [ContextPackage] and a [UserAction].
 * The screen must not assemble requests itself.
 */
object AIRequestFactory {
    const val EMPTY_SELECTED_TEXT = "Selected text is empty."
    const val MISSING_ACTION = "User action is missing."

    fun create(
        contextPackage: ContextPackage,
        userAction: UserAction?,
        newId: () -> String = { UUID.randomUUID().toString() },
        now: () -> Instant = { Instant.now() },
    ): AIRequestAttempt {
        val selectedText = contextPackage.selectedText
        if (selectedText.isNullOrBlank()) {
            return AIRequestAttempt.Rejected(EMPTY_SELECTED_TEXT)
        }
        if (userAction == null) {
            return AIRequestAttempt.Rejected(MISSING_ACTION)
        }
        return AIRequestAttempt.Ready(
            AIRequest(
                requestId = newId(),
                action = userAction,
                selectedText = selectedText,
                precedingContext = contextPackage.precedingContext,
                followingContext = contextPackage.followingContext,
                sourcePackage = contextPackage.sourcePackage,
                captureMethod = contextPackage.captureMethod,
                contextQuality = contextPackage.contextQuality,
                timestamp = now(),
            ),
        )
    }
}
