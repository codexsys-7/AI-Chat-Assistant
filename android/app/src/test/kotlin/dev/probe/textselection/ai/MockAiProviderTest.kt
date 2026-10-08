package dev.probe.textselection.ai

import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.AIProvider
import dev.probe.textselection.interaction.AIRequest
import dev.probe.textselection.interaction.AIRequestFactory
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.Prompt
import dev.probe.textselection.interaction.ResponseStatus
import dev.probe.textselection.interaction.UserAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MockAiProviderTest {
    private val selected = "Cells store fuel before they can spend it."
    private val preceding = "A cell still has to turn stored fuel into usable energy."
    private val following = "That next step is what the mitochondria sentence describes."
    private val invented = "Retrieval augmented generation combines language models with external knowledge sources."

    @Test
    fun explainExampleAndFollowUpPromptsStayDistinct() {
        val explain = PromptEngine.build(request(UserAction.EXPLAIN, ContextQuality.HIGH))
        val example = PromptEngine.build(request(UserAction.EXAMPLE, ContextQuality.HIGH))
        val followUp = PromptEngine.build(request(UserAction.FOLLOW_UP, ContextQuality.HIGH))

        assertTrue(explain.systemInstruction.startsWith("Explain the passage"))
        assertTrue(explain.systemInstruction.contains("ASD-STE100"))
        assertTrue(example.systemInstruction.startsWith("Give a concrete example"))
        assertTrue(followUp.systemInstruction.contains("one follow-up question"))
        assertTrue(followUp.systemInstruction.contains("professor"))
        assertNotEquals(explain.systemInstruction, example.systemInstruction)
        assertNotEquals(example.systemInstruction, followUp.systemInstruction)
        listOf(explain, example, followUp).forEach { prompt ->
            assertTrue(prompt.userContent.contains("Selected text: $selected"))
            assertTrue(prompt.userContent.contains(preceding))
            assertTrue(prompt.userContent.contains(following))
        }
    }

    @Test
    fun highContextIncludesOnlyTheSuppliedContext() {
        val prompt = PromptEngine.build(request(UserAction.EXPLAIN, ContextQuality.HIGH))

        assertTrue(prompt.userContent.contains("Context quality: HIGH"))
        assertTrue(prompt.userContent.contains("Preceding context: $preceding"))
        assertTrue(prompt.userContent.contains("Following context: $following"))
        assertFalse(prompt.userContent.contains(invented))
        assertFalse(prompt.systemInstruction.contains(invented))
    }

    @Test
    fun unavailableContextDoesNotFabricateSurroundingText() {
        val prompt = PromptEngine.build(
            request(UserAction.EXPLAIN, ContextQuality.UNAVAILABLE, preceding = null, following = null),
        )

        assertTrue(prompt.userContent.contains("Only the selected text is available."))
        assertTrue(prompt.userContent.contains("Selected text: $selected"))
        assertFalse(prompt.userContent.contains("Preceding context:"))
        assertFalse(prompt.userContent.contains("Following context:"))
        assertFalse(prompt.userContent.contains(invented))
        assertFalse(prompt.userContent.contains(preceding))
    }

    @Test
    fun mockResponsesForAllThreeActionsPreserveTheRequest() {
        val provider = MockAIProvider(newId = { "response-fixed" })
        UserAction.entries.forEach { action ->
            val source = request(action, ContextQuality.HIGH)
            val prompt = PromptEngine.build(source)
            val response = runBlocking { provider.generateResponse(source, prompt) }

            assertEquals(source.requestId, response.requestId)
            assertEquals("response-fixed", response.responseId)
            assertNotEquals(response.requestId, response.responseId)
            assertEquals(action, response.action)
            assertEquals(MockAIProvider.PROVIDER_NAME, response.provider)
            assertEquals(MockAIProvider.MODEL_NAME, response.model)
            assertEquals(ResponseStatus.SUCCESS, response.status)
            assertTrue(response.content.contains("[MOCK mock-v1]"))
            assertTrue(response.content.contains(prompt.systemInstruction))
            assertTrue(response.content.contains("Context quality: HIGH"))
            assertTrue(response.content.contains("Selected passage: \"$selected\""))
            assertFalse(response.content.contains(invented))
        }
    }

    @Test
    fun unavailableMockResponseDoesNotAddContext() {
        val source = request(UserAction.EXAMPLE, ContextQuality.UNAVAILABLE, preceding = null, following = null)
        val response = runBlocking {
            MockAIProvider().generateResponse(source, PromptEngine.build(source))
        }

        assertTrue(response.content.contains("Context quality: UNAVAILABLE"))
        assertTrue(response.content.contains("Only the selected text is available."))
        assertTrue(response.content.contains(selected))
        assertFalse(response.content.contains(preceding))
        assertFalse(response.content.contains(invented))
        assertTrue(response.responseId.isNotBlank())
        assertNotEquals(source.requestId, response.responseId)
    }

    @Test
    fun serviceWiresRequestToPromptToProviderToResponse() {
        val source = request(UserAction.FOLLOW_UP, ContextQuality.HIGH)
        val seen = mutableListOf<Prompt>()
        val provider = object : AIProvider {
            override val providerName = "RecordingProvider"
            override val modelName = "recording-model"
            override suspend fun generateResponse(request: AIRequest, prompt: Prompt): AIResponse {
                seen += prompt
                return AIResponse(
                    responseId = "resp-1",
                    requestId = request.requestId,
                    sessionId = request.sessionId,
                    action = request.action,
                    content = "recorded",
                    provider = providerName,
                    model = modelName,
                    status = ResponseStatus.SUCCESS,
                )
            }
        }
        val response = runBlocking { AIService(provider).generate(source) }

        assertEquals(listOf(PromptEngine.build(source)), seen)
        assertEquals(source.requestId, response.requestId)
        assertEquals("resp-1", response.responseId)
        assertEquals(ResponseStatus.SUCCESS, response.status)
        assertEquals(UserAction.FOLLOW_UP, response.action)
    }

    @Test
    fun serviceReturnsErrorWithoutCrashing() {
        val source = request(UserAction.EXPLAIN, ContextQuality.MEDIUM, following = null)
        val provider = object : AIProvider {
            override val providerName = "RecordingProvider"
            override val modelName = "recording-model"
            override suspend fun generateResponse(request: AIRequest, prompt: Prompt): AIResponse {
                throw IllegalStateException("boom")
            }
        }
        val response = runBlocking {
            AIService(provider, newId = { "error-id" }).generate(source)
        }

        assertEquals(ResponseStatus.ERROR, response.status)
        assertEquals(source.requestId, response.requestId)
        assertEquals("error-id", response.responseId)
        assertEquals(UserAction.EXPLAIN, response.action)
        assertEquals(AIService.ERROR_MESSAGE, response.content)
        assertEquals("RecordingProvider", response.provider)
        assertFalse(response.content.contains(source.selectedText))
    }

    private fun request(
        action: UserAction,
        quality: ContextQuality,
        preceding: String? = this.preceding,
        following: String? = this.following,
    ): AIRequest {
        val attempt = AIRequestFactory.create(
            ContextPackage(
                selectedText = selected,
                precedingContext = preceding,
                followingContext = following,
                sourcePackage = "dev.probe.samplechat",
                captureMethod = CaptureMethod.Accessibility,
                captureStatus = CaptureStatus.SUCCESS,
                contextQuality = quality,
            ),
            action,
            newId = { "request-1" },
            now = { Instant.parse("2026-10-05T00:00:00Z") },
        )
        return (attempt as dev.probe.textselection.interaction.AIRequestAttempt.Ready).request
    }
}
