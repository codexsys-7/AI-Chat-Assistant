package dev.probe.textselection.engine

import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.context.ContextCapture
import dev.probe.textselection.interaction.AIRequestAttempt
import dev.probe.textselection.interaction.AIRequestFactory
import dev.probe.textselection.interaction.UserAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ContextIntelligenceTest {
    private val selected =
        "The system first retrieves relevant documents and then provides those documents to the model as context."
    private val preceding =
        "Retrieval augmented generation combines language models with external knowledge sources."
    private val following =
        "This can help the model answer questions using information that was not contained in its original training data."
    private val invented = "The mitochondria invented this missing sentence from nowhere."

    @Test
    fun normalizationTrimsAllThreeSidesAndDropsEmptySegments() {
        val normalized = ContextNormalizer.normalize(
            selectedText = "  The   cat   sat.  ",
            precedingContext = " \n\n  ",
            followingContext = "\tNext\t\tline.\n\n\n\n",
        )

        assertEquals("The cat sat.", normalized.selectedText)
        assertNull(normalized.precedingContext)
        assertEquals("Next line.", normalized.followingContext)
    }

    @Test
    fun whitespaceOnlySidesAreNull() {
        val normalized = ContextNormalizer.normalize(
            selectedText = " \n\t ",
            precedingContext = "",
            followingContext = "   \n  ",
        )

        assertNull(normalized.selectedText)
        assertNull(normalized.precedingContext)
        assertNull(normalized.followingContext)
    }

    @Test
    fun duplicatedUiNoiseIsDroppedAndSelectedMeaningStays() {
        val original = "Don't rewrite this: it's already clear."
        val normalized = ContextNormalizer.normalize(
            selectedText = "  $original  ",
            precedingContext = "Copy\n\nSelect all\n\nCopy\n\n$preceding\n\n$preceding",
            followingContext = "Read aloud. $following",
        )

        assertEquals(original, normalized.selectedText)
        assertEquals(preceding, normalized.precedingContext)
        assertEquals(following, normalized.followingContext)
        assertFalse(normalized.selectedText!!.contains("Copy"))
        assertFalse(normalized.precedingContext!!.contains("Copy"))
        assertFalse(normalized.followingContext!!.contains("Read aloud"))
    }

    @Test
    fun selectedTextThatLooksLikeAUserTurnIsNotRewritten() {
        val original = "You should keep this sentence intact."
        val result = ContextEngine.process(
            capture(selectedText = "  $original  ", precedingContext = preceding, followingContext = following),
        )

        assertEquals(original, result.selectedText)
        assertTrue(result.selectedText!!.startsWith("You"))
    }

    @Test
    fun bothRelevantSidesAreHigh() {
        val analysis = analyze(preceding, following)

        assertEquals(ContextQuality.HIGH, analysis.contextQuality)
        assertTrue(analysis.relevance.contains("Meaningful usable"))
        assertEquals(selected, analysis.segments.single { it.kind == ContextSegmentKind.SELECTED }.text)
        assertTrue(analysis.segments.single { it.kind == ContextSegmentKind.PRECEDING }.available)
        assertTrue(analysis.segments.single { it.kind == ContextSegmentKind.FOLLOWING }.available)
    }

    @Test
    fun missingPrecedingIsMediumAndExplicitlyUnavailable() {
        val analysis = analyze(preceding = null, following = following)
        val precedingSegment = analysis.segments.single { it.kind == ContextSegmentKind.PRECEDING }

        assertEquals(ContextQuality.MEDIUM, analysis.contextQuality)
        assertNull(analysis.precedingContext)
        assertEquals(following, analysis.followingContext)
        assertFalse(precedingSegment.available)
        assertNull(precedingSegment.text)
        assertFalse(segmentText(analysis).contains(invented))
    }

    @Test
    fun missingFollowingIsMediumAndExplicitlyUnavailable() {
        val analysis = analyze(preceding = preceding, following = null)
        val followingSegment = analysis.segments.single { it.kind == ContextSegmentKind.FOLLOWING }

        assertEquals(ContextQuality.MEDIUM, analysis.contextQuality)
        assertEquals(preceding, analysis.precedingContext)
        assertNull(analysis.followingContext)
        assertFalse(followingSegment.available)
        assertNull(followingSegment.text)
    }

    @Test
    fun missingBothSidesIsUnavailable() {
        val analysis = analyze(preceding = null, following = null)

        assertEquals(ContextQuality.UNAVAILABLE, analysis.contextQuality)
        assertTrue(analysis.relevance.contains("unavailable"))
        assertEquals(listOf(ContextSegmentKind.SELECTED, ContextSegmentKind.PRECEDING, ContextSegmentKind.FOLLOWING), analysis.segments.map { it.kind })
        assertTrue(analysis.segments.single { it.kind == ContextSegmentKind.SELECTED }.available)
        assertFalse(analysis.segments.single { it.kind == ContextSegmentKind.PRECEDING }.available)
        assertFalse(analysis.segments.single { it.kind == ContextSegmentKind.FOLLOWING }.available)
        assertNull(analysis.precedingContext)
        assertNull(analysis.followingContext)
    }

    @Test
    fun shortSurroundingContextIsLow() {
        val analysis = analyze(preceding = "Hi", following = "No")

        assertEquals("Hi", analysis.precedingContext)
        assertEquals("No", analysis.followingContext)
        assertEquals(ContextQuality.LOW, analysis.contextQuality)
        assertTrue(analysis.relevance.contains("weak or noisy"))
    }

    @Test
    fun limitedOverlapOnOneSideIsMedium() {
        val analysis = analyze(
            preceding = "The cells adapt.",
            following = null,
            selectedText = "Cells store fuel before they can spend it.",
        )

        assertEquals(ContextQuality.MEDIUM, analysis.contextQuality)
        assertEquals("The cells adapt.", analysis.precedingContext)
        assertNull(analysis.followingContext)
    }

    @Test
    fun unrelatedNonEmptyContextIsNotHigh() {
        val analysis = analyze(
            preceding = "The weather stays pleasant through the afternoon.",
            following = "Please remember to water those green plants.",
        )

        assertEquals("The weather stays pleasant through the afternoon.", analysis.precedingContext)
        assertEquals("Please remember to water those green plants.", analysis.followingContext)
        assertEquals(ContextQuality.LOW, analysis.contextQuality)
    }

    @Test
    fun segmentsStayInOrderAndNeverUsePlaceholderText() {
        val analysis = analyze(preceding = null, following = "   ")

        assertEquals(3, analysis.segments.size)
        assertEquals(ContextSegmentKind.SELECTED, analysis.segments[0].kind)
        assertEquals(ContextSegmentKind.PRECEDING, analysis.segments[1].kind)
        assertEquals(ContextSegmentKind.FOLLOWING, analysis.segments[2].kind)
        assertEquals(selected, analysis.segments[0].text)
        assertNull(analysis.segments[1].text)
        assertNull(analysis.segments[2].text)
        analysis.segments.forEach { segment ->
            assertFalse(segment.text.orEmpty().contains(invented))
        }
    }

    @Test
    fun contextOnKeepsRelevantSidesAndContextOffDoesNotInventThem() {
        val on = ContextEngine.process(
            capture(selectedText = selected, precedingContext = preceding, followingContext = following),
        )
        val off = ContextEngine.process(
            capture(
                selectedText = selected,
                precedingContext = null,
                followingContext = null,
                method = CaptureMethod.SelectedText,
                status = CaptureStatus.FAILED,
            ),
        )

        assertEquals(ContextQuality.HIGH, on.contextQuality)
        assertEquals(preceding, on.precedingContext)
        assertEquals(following, on.followingContext)
        assertEquals(ContextQuality.UNAVAILABLE, off.contextQuality)
        assertEquals(selected, off.selectedText)
        assertNull(off.precedingContext)
        assertNull(off.followingContext)

        val offRequest = AIRequestFactory.create(
            off,
            UserAction.EXPLAIN,
            newId = { "request-off" },
            now = { Instant.parse("2026-10-08T00:00:00Z") },
        )
        val request = (offRequest as AIRequestAttempt.Ready).request
        assertEquals(selected, request.selectedText)
        assertNull(request.precedingContext)
        assertNull(request.followingContext)
        assertEquals(ContextQuality.UNAVAILABLE, request.contextQuality)
        assertEquals("request-off", request.requestId)
        assertEquals(UserAction.EXPLAIN, request.action)
    }

    @Test
    fun repeatedAnalysisMatchesThePackageQuality() {
        val pack = ContextEngine.process(
            capture(selectedText = selected, precedingContext = preceding, followingContext = following),
        )
        val again = ContextIntelligence.analyze(
            NormalizedContext(pack.selectedText, pack.precedingContext, pack.followingContext),
        )

        assertEquals(pack.contextQuality, again.contextQuality)
        assertEquals(pack.selectedText, again.selectedText)
        assertEquals(pack.precedingContext, again.precedingContext)
        assertEquals(pack.followingContext, again.followingContext)
    }

    private fun analyze(
        preceding: String?,
        following: String?,
        selectedText: String = selected,
    ) = ContextIntelligence.analyze(
        ContextNormalizer.normalize(selectedText, preceding, following),
    )

    private fun segmentText(analysis: ContextAnalysis): String {
        return analysis.segments.joinToString("\n") { it.text.orEmpty() }
    }

    private fun capture(
        selectedText: String?,
        precedingContext: String?,
        followingContext: String?,
        method: CaptureMethod = CaptureMethod.Accessibility,
        status: CaptureStatus = CaptureStatus.SUCCESS,
    ) = ContextCapture(
        selectedText = selectedText,
        precedingContext = precedingContext,
        followingContext = followingContext,
        sourcePackage = "dev.probe.samplechat",
        captureMethod = method,
        success = status == CaptureStatus.SUCCESS,
        failureReason = null,
        status = status,
    )
}
