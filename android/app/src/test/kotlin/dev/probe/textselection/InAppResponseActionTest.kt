package dev.probe.textselection

import dev.probe.textselection.ai.AIService
import dev.probe.textselection.ai.MockAIProvider
import dev.probe.textselection.ai.PromptEngine
import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.ContextSnapshotStore
import dev.probe.textselection.context.VisibleWindowSnapshot
import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIRequestAttempt
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.ConversationSessions
import dev.probe.textselection.interaction.ResponseStatus
import dev.probe.textselection.interaction.UserAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppResponseActionTest {
    private val before =
        "Retrieval augmented generation combines language models with external knowledge sources."
    private val middle =
        "The system first retrieves relevant documents and then provides those documents to the model as context."
    private val after =
        "This can help the model answer questions using information that was not contained in its original training data."
    private val laterParagraph =
        "A later paragraph stays outside following context for this response."

    private val providerToken = "ProviderTokenZZ"
    private val modelToken = "ModelTokenZZ"
    private val sourceRequestId = "request-id-token-zz"
    private val sourceResponseId = "response-id-token-zz"
    private val sourceSessionId = "session-id-token-zz"

    @After
    fun clearSession() {
        ConversationSessions.manager.reset()
    }

    @Test
    fun middleSelectionReachesPromptAndProvider() {
        val request = ready(UserAction.EXPLAIN, middle)

        assertEquals(middle, request.selectedText)
        assertEquals(before, request.precedingContext)
        assertEquals(after, request.followingContext)
        assertEquals("dev.probe.textselection", request.sourcePackage)
        assertEquals(CaptureMethod.SelectedText, request.captureMethod)
        assertEquals(ContextQuality.HIGH, request.contextQuality)
        assertFalse(contextText(request).contains(laterParagraph))

        val prompt = PromptEngine.build(request)
        assertTrue(prompt.systemInstruction.startsWith("Explain the passage"))
        assertTrue(prompt.userContent.contains("Selected text: $middle"))
        assertTrue(prompt.userContent.contains("Preceding context: $before"))
        assertTrue(prompt.userContent.contains("Following context: $after"))
        assertFalse(prompt.userContent.contains(laterParagraph))
        assertFalse(prompt.systemInstruction.contains(request.sessionId))
        assertFalse(prompt.userContent.contains(request.sessionId))

        val generated = runBlocking {
            AIService(MockAIProvider { "generated-response" }).generate(request)
        }
        assertEquals("generated-response", generated.responseId)
        assertEquals(request.requestId, generated.requestId)
        assertEquals(request.sessionId, generated.sessionId)
        assertEquals(UserAction.EXPLAIN, generated.action)
        assertTrue(generated.content.contains("Selected passage: \"$middle\""))
        assertTrue(generated.content.contains("Context quality: HIGH"))
        assertEquals(ResponseStatus.SUCCESS, generated.status)
    }

    @Test
    fun firstSentenceHasNullPreceding() {
        val request = ready(UserAction.EXAMPLE, before)

        assertEquals(before, request.selectedText)
        assertNull(request.precedingContext)
        assertEquals("$middle $after", request.followingContext)
        assertEquals(ContextQuality.MEDIUM, request.contextQuality)
        assertFalse(contextText(request).contains(laterParagraph))
        assertTrue(PromptEngine.build(request).systemInstruction.startsWith("Give a concrete example"))
    }

    @Test
    fun lastSentenceHasNullFollowing() {
        val request = ready(UserAction.FOLLOW_UP, after)

        assertEquals(after, request.selectedText)
        assertEquals("$before $middle", request.precedingContext)
        assertNull(request.followingContext)
        assertEquals(ContextQuality.MEDIUM, request.contextQuality)
        assertFalse(contextText(request).contains(laterParagraph))
        assertTrue(PromptEngine.build(request).systemInstruction.startsWith("Ask the user one follow-up"))
    }

    @Test
    fun partialSentenceStaysExact() {
        val partial = "retrieves relevant documents"
        val request = ready(UserAction.EXPLAIN, partial)

        assertEquals(partial, request.selectedText)
        assertFalse(request.selectedText == middle)
        assertEquals(before, request.precedingContext)
        assertEquals(after, request.followingContext)
        assertFalse(request.precedingContext!!.contains(partial))
        assertFalse(request.followingContext!!.contains(partial))
        assertEquals(ContextQuality.LOW, request.contextQuality)
        val prompt = PromptEngine.build(request)
        assertTrue(prompt.userContent.contains("Selected text: $partial"))
        assertFalse(prompt.userContent.contains("Selected text: $middle"))
    }

    @Test
    fun metadataStaysOutOfTheHandoff() {
        val request = ready(UserAction.EXPLAIN, middle)
        val prompt = PromptEngine.build(request)
        val carried = contextText(request) + "\n" + prompt.systemInstruction + "\n" + prompt.userContent

        assertFalse(carried.contains("MOCK"))
        assertFalse(carried.contains("Request debug"))
        assertFalse(carried.contains(providerToken))
        assertFalse(carried.contains(modelToken))
        assertFalse(carried.contains(sourceRequestId))
        assertFalse(carried.contains(sourceResponseId))
        assertFalse(carried.contains(sourceSessionId))
        assertFalse(carried.contains(ResponseStatus.SUCCESS.name))
        assertEquals(middle, request.selectedText)
    }

    @Test
    fun externalSnapshotIsNotUsed() {
        val snapshotBefore = "External snapshot preceding sentence about glaciers."
        val snapshotAfter = "External snapshot following sentence about volcanoes."
        val snapshot = VisibleWindowSnapshot(
            sourcePackage = "com.example.other",
            visibleText = "$snapshotBefore $middle $snapshotAfter",
            nodeCount = 3,
        )
        ContextSnapshotStore.record(snapshot)

        val request = ready(UserAction.EXPLAIN, middle)

        assertEquals(before, request.precedingContext)
        assertEquals(after, request.followingContext)
        assertEquals("dev.probe.textselection", request.sourcePackage)
        assertEquals(CaptureMethod.SelectedText, request.captureMethod)
        val carried = contextText(request) + "\n" + PromptEngine.build(request).userContent
        assertFalse(carried.contains("glaciers"))
        assertFalse(carried.contains("volcanoes"))
        assertFalse(carried.contains("com.example.other"))
        val stored = ContextSnapshotStore.latest()
        assertEquals(snapshot.sourcePackage, stored?.sourcePackage)
        assertEquals(snapshot.visibleText, stored?.visibleText)
        assertEquals(snapshot.nodeCount, stored?.nodeCount)
    }

    @Test
    fun duplicateSubstringDoesNotGuessAnOccurrence() {
        val alphaBefore = "Alpha occurrence is preceded by glaciers and fjords."
        val alphaAfter = "Alpha occurrence is followed by volcanoes and basalt."
        val betaBefore = "Beta occurrence is preceded by orchards and rainfall."
        val betaAfter = "Beta occurrence is followed by canyons and sandstone."
        val content = "$alphaBefore $middle $alphaAfter $betaBefore $middle $betaAfter"
        val request = ready(UserAction.EXPLAIN, middle, content)

        assertEquals(middle, request.selectedText)
        assertNull(request.precedingContext)
        assertNull(request.followingContext)
        assertEquals(ContextQuality.UNAVAILABLE, request.contextQuality)
        val prompt = PromptEngine.build(request).userContent
        assertTrue(prompt.contains("Preceding context is unavailable."))
        assertTrue(prompt.contains("Following context is unavailable."))
        assertFalse(prompt.contains("glaciers"))
        assertFalse(prompt.contains("orchards"))
        assertFalse(prompt.contains("Alpha"))
        assertFalse(prompt.contains("Beta"))
    }

    @Test
    fun twoActionsShareSessionAndResetStartsAnother() {
        ConversationSessions.manager.reset()
        val explain = ready(UserAction.EXPLAIN, middle)
        val example = ready(UserAction.EXAMPLE, middle)

        assertEquals(explain.sessionId, example.sessionId)
        assertNotEquals(explain.requestId, example.requestId)
        assertEquals(explain.selectedText, example.selectedText)
        assertEquals(2, ConversationSessions.manager.current()?.interactionCount)
        assertFalse(PromptEngine.build(example).userContent.contains(example.sessionId))

        ConversationSessions.manager.reset()
        val followUp = ready(UserAction.FOLLOW_UP, middle)

        assertNotEquals(explain.sessionId, followUp.sessionId)
        assertEquals(1, ConversationSessions.manager.current()?.interactionCount)
        assertEquals(UserAction.FOLLOW_UP, followUp.action)
    }

    @Test
    fun blankSelectionDoesNotOpenASession() {
        ConversationSessions.manager.reset()
        val attempt = InAppResponseAction.attempt(sourceResponse(threeSentences()), "   ", UserAction.EXPLAIN)

        assertTrue(attempt is AIRequestAttempt.Rejected)
        assertEquals("Selected text is empty.", (attempt as AIRequestAttempt.Rejected).reason)
        assertFalse(ConversationSessions.manager.exists())
    }

    private fun ready(
        action: UserAction,
        selectedText: String,
        content: String = threeSentences(),
    ): AIRequest {
        val attempt = InAppResponseAction.attempt(sourceResponse(content), selectedText, action)
        assertTrue(attempt is AIRequestAttempt.Ready)
        return (attempt as AIRequestAttempt.Ready).request
    }

    private fun threeSentences(): String = "$before $middle $after\n$laterParagraph"

    private fun sourceResponse(content: String) = AIResponse(
        responseId = sourceResponseId,
        requestId = sourceRequestId,
        sessionId = sourceSessionId,
        action = UserAction.EXPLAIN,
        content = content,
        provider = providerToken,
        model = modelToken,
        status = ResponseStatus.SUCCESS,
    )

    private fun contextText(request: AIRequest): String {
        return listOfNotNull(
            request.selectedText,
            request.precedingContext,
            request.followingContext,
            request.sourcePackage,
        ).joinToString("\n")
    }
}
