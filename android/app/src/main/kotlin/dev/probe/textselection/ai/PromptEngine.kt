package dev.probe.textselection.ai

import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.Prompt
import dev.probe.textselection.interaction.UserAction

/**
 * Turns an [AIRequest] into a [Prompt]. Local only: no network and no new facts.
 * Providers must forward this prompt and must not rebuild it.
 * Session id and earlier selections are not prompt content.
 */
object PromptEngine {
    fun build(request: AIRequest): Prompt {
        return Prompt(
            systemInstruction = instruction(request.action, request.contextQuality),
            userContent = userContent(request),
        )
    }

    private fun instruction(action: UserAction, quality: ContextQuality): String {
        val base = when (action) {
            UserAction.EXPLAIN -> EXPLAIN
            UserAction.EXAMPLE -> EXAMPLE
            UserAction.FOLLOW_UP -> FOLLOW_UP
        }
        if (quality != ContextQuality.UNAVAILABLE) return base
        return "$base $UNAVAILABLE_RULE"
    }

    private fun userContent(request: AIRequest): String {
        val lines = mutableListOf<String>()
        lines += "Action: ${request.action.name}"
        lines += "Selected text: ${request.selectedText}"
        lines += "Primary subject: the selected text"
        if (request.contextQuality == ContextQuality.UNAVAILABLE) {
            lines += "Context quality: ${request.contextQuality.name}"
            lines += "Preceding context is unavailable. Following context is unavailable."
            lines += "Only the selected text is available."
            lines += "Answer from the selected text alone."
        } else {
            lines += side("Preceding context", request.precedingContext)
            lines += side("Following context", request.followingContext)
            lines += "Context quality: ${request.contextQuality.name}"
        }
        lines += "Response instructions: ${responseInstructions(request)}"
        return lines.joinToString("\n")
    }

    private fun side(label: String, text: String?): String {
        val value = text?.takeIf { it.isNotBlank() } ?: "unavailable"
        return "$label: $value"
    }

    private fun responseInstructions(request: AIRequest): String {
        val base = when (request.action) {
            UserAction.EXPLAIN -> EXPLAIN_RESPONSE
            UserAction.EXAMPLE -> EXAMPLE_RESPONSE
            UserAction.FOLLOW_UP -> FOLLOW_UP_RESPONSE
        }
        if (request.contextQuality != ContextQuality.UNAVAILABLE) return base
        return "$base Answer from the selected text alone."
    }

    private const val EXPLAIN =
        "Explain the passage in ASD-STE100 simplified technical English. " +
            "Use no more than 3 sentences. Keep the wording minimal and clean. " +
            "Use surrounding context only when it clarifies the selected text. " +
            "Do not invent missing information. Do not repeat the whole context or the passage verbatim. " +
            "Do not hallucinate."

    private const val EXAMPLE =
        "Give a concrete example of the selected passage, as if the reader knows nothing about the topic. " +
            "Use one simple example. Use surrounding context only when it helps. " +
            "Do not drift from the selected text."

    private const val FOLLOW_UP =
        "Ask the user one follow-up question about the selected text, and briefly explain that question " +
            "the way an experienced professor would. Treat the selected text as the subject. " +
            "Do not pretend that missing conversation exists. Do not turn the response into a generic essay."

    private const val UNAVAILABLE_RULE =
        "Preceding context is unavailable. Following context is unavailable. " +
            "Answer from the selected text alone."

    private const val EXPLAIN_RESPONSE =
        "Follow ASD-STE100 simplified technical English. Use no more than 3 sentences. " +
            "Use surrounding context only when it clarifies the selected text. " +
            "Do not invent missing information. Do not repeat the whole context or the passage verbatim. " +
            "Do not hallucinate."

    private const val EXAMPLE_RESPONSE =
        "Give one simple example of the selected passage, as if the reader knows nothing about the topic. " +
            "Use surrounding context only when it helps. Do not drift from the selected text."

    private const val FOLLOW_UP_RESPONSE =
        "Ask the user one follow-up question about the selected text, and briefly explain that question " +
            "the way an experienced professor would. Do not pretend that missing conversation exists. " +
            "Do not turn the response into a generic essay."
}
