package dev.probe.textselection.engine

import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.context.ContextCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEngineTest {
    private val selected =
        "The system first retrieves relevant documents and then provides those documents to the model as context."
    private val preceding =
        "Retrieval augmented generation combines language models with external knowledge sources."
    private val following =
        "This can help the model answer questions using information that was not contained in its original training data."

    @Test
    fun selectedPrecedingAndFollowingAreHighQuality() {
        val result = ContextEngine.process(
            capture(selectedText = selected, precedingContext = preceding, followingContext = following),
        )

        assertEquals(selected, result.selectedText)
        assertEquals(preceding, result.precedingContext)
        assertEquals(following, result.followingContext)
        assertEquals("dev.probe.samplechat", result.sourcePackage)
        assertEquals(CaptureMethod.Accessibility, result.captureMethod)
        assertEquals(CaptureStatus.SUCCESS, result.captureStatus)
        assertEquals(ContextQuality.HIGH, result.contextQuality)
    }

    @Test
    fun noPrecedingIsMedium() {
        val result = ContextEngine.process(
            capture(selectedText = selected, precedingContext = null, followingContext = following),
        )

        assertEquals(selected, result.selectedText)
        assertNull(result.precedingContext)
        assertEquals(following, result.followingContext)
        assertEquals(ContextQuality.MEDIUM, result.contextQuality)
    }

    @Test
    fun noFollowingIsMedium() {
        val result = ContextEngine.process(
            capture(selectedText = selected, precedingContext = preceding, followingContext = null),
        )

        assertEquals(selected, result.selectedText)
        assertEquals(preceding, result.precedingContext)
        assertNull(result.followingContext)
        assertEquals(ContextQuality.MEDIUM, result.contextQuality)
    }

    @Test
    fun neitherSideIsUnavailable() {
        val result = ContextEngine.process(
            capture(selectedText = selected, precedingContext = null, followingContext = null),
        )

        assertEquals(selected, result.selectedText)
        assertNull(result.precedingContext)
        assertNull(result.followingContext)
        assertEquals(ContextQuality.UNAVAILABLE, result.contextQuality)
    }

    @Test
    fun selectedSentenceIsRemovedFromSurroundingContext() {
        val result = ContextEngine.process(
            capture(
                selectedText = selected,
                precedingContext = "$preceding $selected",
                followingContext = "$selected $following",
            ),
        )

        assertEquals(preceding, result.precedingContext)
        assertEquals(following, result.followingContext)
        assertFalse(result.precedingContext!!.contains(selected))
        assertFalse(result.followingContext!!.contains(selected))
        assertEquals(ContextQuality.HIGH, result.contextQuality)
    }

    @Test
    fun excessWhitespaceCollapsesWithoutDroppingParagraphBreaks() {
        val result = ContextEngine.process(
            capture(
                selectedText = "  The   cat   sat.  ",
                precedingContext = "Before    the   cat.\n\n\nAfter    break.",
                followingContext = "Next\t\tline.",
            ),
        )

        assertEquals("The cat sat.", result.selectedText)
        assertEquals("Before the cat.\n\nAfter break.", result.precedingContext)
        assertEquals("Next line.", result.followingContext)
    }

    @Test
    fun emptyOrNullSurroundingContextIsUnavailable() {
        val result = ContextEngine.process(
            capture(selectedText = selected, precedingContext = null, followingContext = "   \n  "),
        )

        assertEquals(selected, result.selectedText)
        assertNull(result.precedingContext)
        assertNull(result.followingContext)
        assertEquals(ContextQuality.UNAVAILABLE, result.contextQuality)
    }

    @Test
    fun veryShortContextIsKeptAndRatedLow() {
        val result = ContextEngine.process(
            capture(selectedText = selected, precedingContext = " Hi ", followingContext = "No"),
        )

        assertEquals("Hi", result.precedingContext)
        assertEquals("No", result.followingContext)
        assertEquals(ContextQuality.LOW, result.contextQuality)
    }

    @Test
    fun punctuationIsPreservedAndExtraSentencesAreNotAdded() {
        val result = ContextEngine.process(
            capture(
                selectedText = selected,
                precedingContext = "One. Two. Three. Four. Five?",
                followingContext = "Wait... Really? Yes! Later. Much later.",
            ),
        )

        assertEquals("Three. Four. Five?", result.precedingContext)
        assertEquals("Wait... Really? Yes!", result.followingContext)
        assertTrue(result.followingContext!!.contains("..."))
        assertTrue(result.followingContext!!.contains("?"))
        assertTrue(result.followingContext!!.contains("!"))
        assertFalse(result.followingContext!!.contains("Later."))
        assertEquals(ContextQuality.HIGH, result.contextQuality)
    }

    @Test
    fun normalizationDoesNotChangeSelectedTextMeaning() {
        val original = "Don't rewrite this: it's already clear."
        val result = ContextEngine.process(
            capture(
                selectedText = "  $original  ",
                precedingContext = preceding,
                followingContext = following,
            ),
        )

        assertEquals(original, result.selectedText)
        assertTrue(result.selectedText!!.contains("Don't"))
        assertTrue(result.selectedText!!.contains("it's"))
        assertTrue(result.selectedText!!.endsWith("."))
        assertFalse(result.selectedText!!.contains(preceding))
        assertFalse(result.selectedText!!.contains(following))
    }

    private fun capture(
        selectedText: String?,
        precedingContext: String?,
        followingContext: String?,
    ) = ContextCapture(
        selectedText = selectedText,
        precedingContext = precedingContext,
        followingContext = followingContext,
        sourcePackage = "dev.probe.samplechat",
        captureMethod = CaptureMethod.Accessibility,
        success = true,
        failureReason = null,
        status = CaptureStatus.SUCCESS,
    )
}
