package dev.probe.textselection.ai

import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIRequestAttempt
import dev.probe.textselection.interaction.AIRequestFactory
import dev.probe.textselection.interaction.UserAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PromptEngineTest {
    private val selected = "Cells store fuel before they can spend it."
    private val preceding = "A cell still has to turn stored fuel into usable energy."
    private val following = "That next step is what the mitochondria sentence describes."
    private val invented = "Retrieval augmented generation combines language models with external knowledge sources."

    @Test
    fun explainExampleAndFollowUpKeepSeparateInstructions() {
        val explain = PromptEngine.build(request(UserAction.EXPLAIN, ContextQuality.HIGH))
        val example = PromptEngine.build(request(UserAction.EXAMPLE, ContextQuality.HIGH))
        val followUp = PromptEngine.build(request(UserAction.FOLLOW_UP, ContextQuality.HIGH))

        assertTrue(explain.systemInstruction.contains("ASD-STE100"))
        assertTrue(explain.systemInstruction.contains("no more than 3 sentences"))
        assertTrue(explain.systemInstruction.contains("Do not hallucinate."))
        assertTrue(explain.userContent.contains("Response instructions:"))
        assertTrue(explain.userContent.contains("Do not repeat the whole context"))

        assertTrue(example.systemInstruction.contains("one simple example"))
        assertTrue(example.systemInstruction.contains("knows nothing about the topic"))
        assertTrue(example.systemInstruction.contains("Do not drift"))
        assertTrue(example.userContent.contains("one simple example"))

        assertTrue(followUp.systemInstruction.contains("one follow-up question"))
        assertTrue(followUp.systemInstruction.contains("experienced professor"))
        assertTrue(followUp.systemInstruction.contains("Do not pretend that missing conversation exists"))
        assertTrue(followUp.systemInstruction.contains("generic essay"))
        assertFalse(followUp.systemInstruction.contains("implied follow-up"))

        listOf(explain, example, followUp).forEach { prompt ->
            assertTrue(prompt.userContent.contains("Action:"))
            assertTrue(prompt.userContent.contains("Selected text: $selected"))
            assertTrue(prompt.userContent.contains("Primary subject: the selected text"))
            assertTrue(prompt.userContent.contains("Preceding context: $preceding"))
            assertTrue(prompt.userContent.contains("Following context: $following"))
            assertTrue(prompt.userContent.contains("Context quality: HIGH"))
            assertFalse(prompt.userContent.contains(invented))
            assertFalse(prompt.systemInstruction.contains(invented))
        }
    }

    @Test
    fun contextOnIncludesOnlySuppliedSides() {
        val prompt = PromptEngine.build(request(UserAction.EXPLAIN, ContextQuality.HIGH))

        assertTrue(prompt.userContent.contains(preceding))
        assertTrue(prompt.userContent.contains(following))
        assertFalse(prompt.userContent.contains(invented))
        assertFalse(prompt.userContent.contains("dev.probe.samplechat"))
    }

    @Test
    fun contextOffSaysBothSidesAreUnavailable() {
        val prompt = PromptEngine.build(
            request(UserAction.EXAMPLE, ContextQuality.UNAVAILABLE, preceding = null, following = null),
        )

        assertTrue(prompt.systemInstruction.contains("Preceding context is unavailable."))
        assertTrue(prompt.systemInstruction.contains("Following context is unavailable."))
        assertTrue(prompt.systemInstruction.contains("Answer from the selected text alone."))
        assertTrue(prompt.userContent.contains("Only the selected text is available."))
        assertTrue(prompt.userContent.contains("Answer from the selected text alone."))
        assertTrue(prompt.userContent.contains("Context quality: UNAVAILABLE"))
        assertTrue(prompt.userContent.contains("Selected text: $selected"))
        assertFalse(prompt.userContent.contains("Preceding context:"))
        assertFalse(prompt.userContent.contains("Following context:"))
        assertFalse(prompt.userContent.contains(preceding))
        assertFalse(prompt.userContent.contains(following))
        assertFalse(prompt.userContent.contains(invented))
    }

    @Test
    fun unavailableQualityDoesNotRepeatStraySurroundingText() {
        val prompt = PromptEngine.build(
            request(UserAction.FOLLOW_UP, ContextQuality.UNAVAILABLE, preceding = preceding, following = following),
        )

        assertFalse(prompt.userContent.contains(preceding))
        assertFalse(prompt.userContent.contains(following))
        assertTrue(prompt.userContent.contains("Answer from the selected text alone."))
        assertTrue(prompt.systemInstruction.contains("one follow-up question"))
    }

    @Test
    fun aMissingSideIsLabeledUnavailableWithoutInventingText() {
        val prompt = PromptEngine.build(
            request(UserAction.EXPLAIN, ContextQuality.MEDIUM, following = null),
        )

        assertTrue(prompt.userContent.contains("Preceding context: $preceding"))
        assertTrue(prompt.userContent.contains("Following context: unavailable"))
        assertFalse(prompt.userContent.contains(invented))
        assertFalse(prompt.userContent.contains(following))
    }

    @Test
    fun inspectionShowsNormalizedSidesQualityRelevanceSegmentsAndPrompt() {
        val topic = "The system first retrieves relevant documents and then provides those documents to the model as context."
        val before = "Retrieval augmented generation combines language models with external knowledge sources."
        val after = "This can help the model answer questions using information that was not contained in its original training data."
        val source = request(
            UserAction.EXPLAIN,
            ContextQuality.HIGH,
            preceding = before,
            following = after,
            selectedText = topic,
        )
        val inspection = RequestInspectionBuilder.from(source)

        assertEquals(topic, inspection.normalizedSelectedText)
        assertEquals(before, inspection.normalizedPrecedingText)
        assertEquals(after, inspection.normalizedFollowingText)
        assertEquals("HIGH", inspection.contextQuality)
        assertTrue(inspection.relevance.contains("Meaningful usable"))
        assertTrue(inspection.segments.contains("SELECTED: $topic"))
        assertTrue(inspection.segments.contains("PRECEDING: $before"))
        assertTrue(inspection.segments.contains("FOLLOWING: $after"))
        assertEquals(PromptEngine.build(source).systemInstruction, inspection.systemInstruction)
        assertEquals(PromptEngine.build(source).userContent, inspection.userContent)
        assertFalse(inspection.userContent.contains("dev.probe.samplechat"))
    }

    @Test
    fun inspectionMarksMissingSidesUnavailable() {
        val inspection = RequestInspectionBuilder.from(
            request(UserAction.FOLLOW_UP, ContextQuality.UNAVAILABLE, preceding = null, following = null),
        )

        assertEquals("unavailable", inspection.normalizedPrecedingText)
        assertEquals("unavailable", inspection.normalizedFollowingText)
        assertEquals("UNAVAILABLE", inspection.contextQuality)
        assertTrue(inspection.segments.contains("PRECEDING: unavailable"))
        assertTrue(inspection.segments.contains("FOLLOWING: unavailable"))
        assertTrue(inspection.segments.contains("SELECTED: $selected"))
        assertFalse(inspection.segments.contains(invented))
        assertTrue(inspection.userContent.contains("Answer from the selected text alone."))
    }

    private fun request(
        action: UserAction,
        quality: ContextQuality,
        preceding: String? = this.preceding,
        following: String? = this.following,
        selectedText: String = selected,
    ): AIRequest {
        val attempt = AIRequestFactory.create(
            ContextPackage(
                selectedText = selectedText,
                precedingContext = preceding,
                followingContext = following,
                sourcePackage = "dev.probe.samplechat",
                captureMethod = CaptureMethod.Accessibility,
                captureStatus = CaptureStatus.SUCCESS,
                contextQuality = quality,
            ),
            action,
            newId = { "request-1" },
            now = { Instant.parse("2026-10-08T00:00:00Z") },
        )
        return (attempt as AIRequestAttempt.Ready).request
    }
}
