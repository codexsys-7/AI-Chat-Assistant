package dev.probe.textselection

import dev.probe.textselection.context.ResponseContextBuilder
import dev.probe.textselection.interaction.AIRequestAttempt
import dev.probe.textselection.interaction.AIRequestFactory
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.ConversationSessions
import dev.probe.textselection.interaction.UserAction
import java.time.Instant
import java.util.UUID

/**
 * Explain, Give Example, and Ask Follow-up for text selected inside an AI response.
 *
 * [ResponseContextBuilder] reads [AIResponse.content] and the exact selected substring.
 * [AIRequestFactory] then opens or continues the in-memory session. The caller shows
 * the resulting request with the existing response screen, which runs [dev.probe.textselection.ai.PromptEngine]
 * and the configured provider.
 *
 * This path does not send `ACTION_PROCESS_TEXT` and does not read the other-app snapshot.
 * It does not reset the session. Offsets are not available, so a repeated substring is
 * passed through and the builder leaves both surrounding sides null.
 */
object InAppResponseAction {
    fun attempt(
        response: AIResponse,
        selectedText: String,
        action: UserAction,
        sessionId: () -> String = { ConversationSessions.manager.idForValidInteraction() },
        newId: () -> String = { UUID.randomUUID().toString() },
        now: () -> Instant = { Instant.now() },
    ): AIRequestAttempt {
        val contextPackage = ResponseContextBuilder.build(response, selectedText)
        return AIRequestFactory.create(
            contextPackage = contextPackage,
            userAction = action,
            sessionId = sessionId,
            newId = newId,
            now = now,
        )
    }
}
