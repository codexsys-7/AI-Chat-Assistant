package dev.probe.textselection.ai

import dev.probe.textselection.engine.ContextIntelligence
import dev.probe.textselection.engine.ContextSegment
import dev.probe.textselection.engine.NormalizedContext
import dev.probe.textselection.interaction.AIRequest

/**
 * Development-only view of a request that was already built.
 * It does not log, store, or send anything.
 */
data class RequestInspection(
    val normalizedSelectedText: String,
    val normalizedPrecedingText: String,
    val normalizedFollowingText: String,
    val contextQuality: String,
    val relevance: String,
    val segments: String,
    val systemInstruction: String,
    val userContent: String,
)

object RequestInspectionBuilder {
    fun from(request: AIRequest): RequestInspection {
        val analysis = ContextIntelligence.analyze(
            NormalizedContext(
                selectedText = request.selectedText,
                precedingContext = request.precedingContext,
                followingContext = request.followingContext,
            ),
        )
        val prompt = PromptEngine.build(request)
        return RequestInspection(
            normalizedSelectedText = request.selectedText,
            normalizedPrecedingText = display(request.precedingContext),
            normalizedFollowingText = display(request.followingContext),
            contextQuality = request.contextQuality.name,
            relevance = analysis.relevance,
            segments = formatSegments(analysis.segments),
            systemInstruction = prompt.systemInstruction,
            userContent = prompt.userContent,
        )
    }

    private fun display(text: String?): String = text?.takeIf { it.isNotBlank() } ?: "unavailable"

    private fun formatSegments(segments: List<ContextSegment>): String {
        return segments.joinToString("\n") { segment ->
            val value = segment.text ?: "unavailable"
            "${segment.kind.name}: $value"
        }
    }
}
