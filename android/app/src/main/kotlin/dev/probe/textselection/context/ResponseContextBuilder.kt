package dev.probe.textselection.context

import dev.probe.textselection.engine.ContextEngine
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.interaction.AIResponse

/**
 * Builds a [ContextPackage] from the stored response body and the user's substring.
 *
 * Preceding and following text come only from [AIResponse.content], via [SentenceWindow]
 * and [ContextEngine]. This path does not read accessibility snapshots or
 * [ContextSnapshotStore]. UI fields on [AIResponse] are not part of the corpus.
 *
 * The contract has no character offset. [SentenceWindow] would otherwise keep the
 * first match. When the normalized substring occurs more than once, this builder
 * does not choose an occurrence: both surrounding sides stay null. A repeated
 * sentence therefore gets no neighbors until a caller can pass the selection range.
 */
object ResponseContextBuilder {
    const val SOURCE_PACKAGE = "dev.probe.textselection"

    fun build(response: AIResponse, selectedText: String): ContextPackage {
        val content = response.content
        val occurrences = occurrenceCount(content, selectedText)
        val surrounding = if (occurrences == 1) SentenceWindow.around(content, selectedText) else null
        val preceding = surrounding?.preceding
        val following = surrounding?.following
        val hasPreceding = !preceding.isNullOrBlank()
        val hasFollowing = !following.isNullOrBlank()
        val status = when {
            hasPreceding && hasFollowing -> CaptureStatus.SUCCESS
            hasPreceding || hasFollowing -> CaptureStatus.PARTIAL
            else -> CaptureStatus.FAILED
        }
        return ContextEngine.process(
            ContextCapture(
                selectedText = selectedText,
                precedingContext = preceding,
                followingContext = following,
                sourcePackage = if (occurrences > 0) SOURCE_PACKAGE else null,
                captureMethod = CaptureMethod.SelectedText,
                success = status == CaptureStatus.SUCCESS,
                failureReason = failureReason(occurrences, status),
                status = status,
            ),
        )
    }

    /**
     * Counts non-overlapping hits of the normalized selection inside the
     * normalized response. Empty and whitespace-only selections count as zero.
     * This is a containment check, not a second sentence splitter.
     */
    private fun occurrenceCount(corpus: String, selectedText: String): Int {
        val haystack = SentenceWindow.normalize(corpus)
        val needle = SentenceWindow.normalize(selectedText)
        if (needle.isEmpty() || haystack.isEmpty()) return 0
        var count = 0
        var start = 0
        while (start < haystack.length) {
            val index = haystack.indexOf(needle, start)
            if (index < 0) return count
            count++
            start = index + needle.length
        }
        return count
    }

    private fun failureReason(occurrences: Int, status: CaptureStatus): String? = when {
        occurrences == 0 -> "Selected text was not contained in the response."
        occurrences > 1 -> "Selected text occurs more than once and no offset was provided."
        status == CaptureStatus.PARTIAL -> "Only one side of the surrounding text was available in the response."
        status == CaptureStatus.FAILED -> "Selected text was contained, but no surrounding sentences were available."
        else -> null
    }
}
