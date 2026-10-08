package dev.probe.textselection.context

import dev.probe.textselection.engine.ContextEngine
import dev.probe.textselection.engine.ContextQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceWindowTest {
    private val mitochondria =
        "The mitochondria converts nutrients into usable energy for the cell."
    private val cells =
        "Cells store fuel, but they still have to turn it into something they can spend."
    private val userQuestion = "What should I know about how a cell makes energy?"
    private val harnessSameBubble =
        "Everything after that sentence is only there so the target sits in the middle."
    private val harnessNextMessage =
        "Use the normal text selection gesture on that sentence, then choose Text Selection Probe from the toolbar."

    private val retrievalSelected =
        "The system first retrieves relevant documents and then provides those documents to the model as context."
    private val retrievalBefore =
        "Retrieval augmented generation combines language models with external knowledge sources."
    private val retrievalAfter =
        "This can help the model answer questions using information that was not contained in its original training data."

    @Test
    fun mitochondriaContextStaysInsideTheAssistantMessage() {
        val corpus = """
            Sample Chat
            You
            $userQuestion
            Assistant
            $cells
            $mitochondria
            $harnessSameBubble
            You
            I will select the mitochondria sentence and open the probe.
            Assistant
            $harnessNextMessage
        """.trimIndent()

        val surrounding = SentenceWindow.around(corpus, mitochondria)
        val result = ContextEngine.process(
            ContextCapture(
                selectedText = mitochondria,
                precedingContext = surrounding?.preceding,
                followingContext = surrounding?.following,
                sourcePackage = "dev.probe.samplechat",
                captureMethod = CaptureMethod.Accessibility,
                success = true,
                failureReason = null,
                status = CaptureStatus.PARTIAL,
            ),
        )

        assertEquals(mitochondria, result.selectedText)
        assertEquals(cells, result.precedingContext)
        assertNull(result.followingContext)
        assertEquals(ContextQuality.MEDIUM, result.contextQuality)
        val kept = listOfNotNull(result.precedingContext, result.followingContext).joinToString("\n")
        assertFalse(kept.contains("Sample Chat"))
        assertFalse(kept.contains("You"))
        assertFalse(kept.contains(userQuestion))
        assertFalse(kept.contains(harnessSameBubble))
        assertFalse(kept.contains(harnessNextMessage))
        assertFalse(kept.contains("I will select"))
    }

    @Test
    fun gluedTitleAndUserQuestionDoNotReplaceTheAssistantSentence() {
        val result = ContextEngine.process(
            ContextCapture(
                selectedText = "  $mitochondria  ",
                precedingContext = "Sample Chat You $userQuestion $cells",
                followingContext = null,
                sourcePackage = "dev.probe.samplechat",
                captureMethod = CaptureMethod.Accessibility,
                success = true,
                failureReason = null,
                status = CaptureStatus.SUCCESS,
            ),
        )

        assertEquals(mitochondria, result.selectedText)
        assertEquals(cells, result.precedingContext)
        assertNull(result.followingContext)
        assertTrue(result.selectedText!!.startsWith("The mitochondria"))
    }

    @Test
    fun retrievalNeighborsOnTheSameAssistantLineStayHigh() {
        val corpus = """
            You
            How does retrieval augmented generation work?
            Assistant
            $retrievalBefore $retrievalSelected $retrievalAfter The quality of the retrieved information therefore has a major effect on the final answer.
        """.trimIndent()

        val surrounding = SentenceWindow.around(corpus, retrievalSelected)
        val result = ContextEngine.process(
            ContextCapture(
                selectedText = retrievalSelected,
                precedingContext = surrounding?.preceding,
                followingContext = surrounding?.following,
                sourcePackage = "dev.probe.samplechat",
                captureMethod = CaptureMethod.Accessibility,
                success = true,
                failureReason = null,
                status = CaptureStatus.SUCCESS,
            ),
        )

        assertEquals(retrievalBefore, result.precedingContext)
        assertTrue(result.followingContext!!.startsWith(retrievalAfter))
        assertEquals(ContextQuality.HIGH, result.contextQuality)
        assertFalse(result.precedingContext!!.contains("How does retrieval"))
        assertFalse(result.followingContext!!.contains("You"))
    }
}
