package dev.probe.textselection.interaction

import dev.probe.textselection.RequestFields
import dev.probe.textselection.ai.AIBackendClient
import dev.probe.textselection.ai.AIService
import dev.probe.textselection.ai.BackendExchange
import dev.probe.textselection.ai.BackendRequestPayload
import dev.probe.textselection.ai.MockAIProvider
import dev.probe.textselection.ai.PromptEngine
import dev.probe.textselection.ai.RemoteAIProvider
import dev.probe.textselection.ai.parseJsonObject
import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.engine.ContextQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConversationSessionTest {
    private val oldSelection = "OLD-SELECTION-SHOULD-NOT-LEAK"
    private val newSelection = "NEW-SELECTION-ONLY"
    private val sessionToken = "session-ZZZ-not-context"
    private var instant = Instant.parse("2026-10-08T12:00:00Z")
    private var requestIds = 0
    private var sessionIds = 0

    @Test
    fun createCurrentUniqueIdAndActiveState() {
        val manager = manager(ids = listOf("session-a", "session-b"))

        assertFalse(manager.exists())
        assertNull(manager.current())

        val first = manager.create()
        assertTrue(manager.exists())
        assertEquals(first, manager.current())
        assertEquals("session-a", first.sessionId)
        assertEquals(SessionState.ACTIVE, first.state)
        assertEquals(0, first.interactionCount)
        assertEquals(instant, first.createdAt)
        assertEquals(instant, first.updatedAt)

        val second = manager.create()
        assertEquals("session-b", second.sessionId)
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(second, manager.current())
        assertEquals(SessionState.ACTIVE, manager.current()?.state)
    }

    @Test
    fun touchUpdatesTimestampWithoutChangingIdentity() {
        val manager = manager(ids = listOf("session-a"))
        val created = manager.create()
        instant = instant.plusSeconds(30)
        val touched = manager.touch()

        assertEquals(created.sessionId, touched?.sessionId)
        assertEquals(created.createdAt, touched?.createdAt)
        assertEquals(instant, touched?.updatedAt)
        assertEquals(SessionState.ACTIVE, touched?.state)
        assertEquals(0, touched?.interactionCount)
    }

    @Test
    fun incrementInteractionCountUpdatesTimestamp() {
        val manager = manager(ids = listOf("session-a"))
        manager.create()
        instant = instant.plusSeconds(5)
        val once = manager.incrementInteractionCount()
        instant = instant.plusSeconds(5)
        val twice = manager.incrementInteractionCount()

        assertEquals(1, once?.interactionCount)
        assertEquals(2, twice?.interactionCount)
        assertEquals("session-a", twice?.sessionId)
        assertEquals(instant, twice?.updatedAt)
        assertEquals(2, manager.current()?.interactionCount)
    }

    @Test
    fun endMarksEndedAndResetDropsTheSession() {
        val manager = manager(ids = listOf("session-a", "session-b"))
        manager.create()
        instant = instant.plusSeconds(9)
        val ended = manager.end()

        assertEquals(SessionState.ENDED, ended?.state)
        assertEquals(instant, ended?.updatedAt)
        assertTrue(manager.exists())
        assertEquals(SessionState.ENDED, manager.current()?.state)
        assertEquals(SESSION_UNAVAILABLE, sessionDebug(manager.current()).sessionId)

        manager.reset()
        assertFalse(manager.exists())
        assertNull(manager.current())
        assertNull(manager.touch())
        assertNull(manager.incrementInteractionCount())
        assertNull(manager.end())
        assertEquals(SESSION_UNAVAILABLE, sessionDebug(manager.current()).sessionId)
        assertEquals(SESSION_UNAVAILABLE, sessionDebug(manager.current()).interactionCount)

        val restarted = manager.create()
        assertEquals("session-b", restarted.sessionId)
        assertEquals(SessionState.ACTIVE, restarted.state)
        assertEquals("session-b", sessionDebug(manager.current()).sessionId)
        assertEquals("0", sessionDebug(manager.current()).interactionCount)
    }

    @Test
    fun rejectedInteractionDoesNotCreateASession() {
        val manager = manager(ids = listOf("session-should-not-exist"))
        var supplied = 0
        val empty = AIRequestFactory.create(
            packageWith(selectedText = "  ", quality = ContextQuality.HIGH),
            UserAction.EXPLAIN,
            sessionId = {
                supplied += 1
                manager.idForValidInteraction()
            },
        )
        val missingAction = AIRequestFactory.create(
            packageWith(selectedText = newSelection, quality = ContextQuality.HIGH),
            null,
            sessionId = {
                supplied += 1
                manager.idForValidInteraction()
            },
        )

        assertTrue(empty is AIRequestAttempt.Rejected)
        assertTrue(missingAction is AIRequestAttempt.Rejected)
        assertEquals(0, supplied)
        assertFalse(manager.exists())
    }

    @Test
    fun requestGetsSessionIdAndResponseKeepsIndependentIds() {
        val manager = manager(ids = listOf(sessionToken))
        val request = ready(manager, selectedText = newSelection, quality = ContextQuality.HIGH)
        val response = runBlocking {
            MockAIProvider(newId = { "response-fixed" }).generateResponse(request, PromptEngine.build(request))
        }

        assertEquals(sessionToken, request.sessionId)
        assertEquals(sessionToken, response.sessionId)
        assertEquals(request.requestId, response.requestId)
        assertEquals("response-fixed", response.responseId)
        assertNotEquals(request.sessionId, request.requestId)
        assertNotEquals(request.sessionId, response.responseId)
        assertNotEquals(request.requestId, response.responseId)
        assertEquals(1, manager.current()?.interactionCount)
        assertTrue(response.content.contains(newSelection))
        assertFalse(response.content.contains(sessionToken))
    }

    @Test
    fun twoRequestsShareASessionAndResetStartsAnother() {
        val manager = manager(ids = listOf("session-one", "session-two"))
        val first = ready(
            manager,
            selectedText = oldSelection,
            quality = ContextQuality.HIGH,
            preceding = "before-old",
            following = "after-old",
        )
        val second = ready(
            manager,
            selectedText = newSelection,
            quality = ContextQuality.MEDIUM,
            preceding = "before-new",
            following = null,
        )

        assertEquals(first.sessionId, second.sessionId)
        assertEquals("session-one", second.sessionId)
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(newSelection, second.selectedText)
        assertEquals("before-new", second.precedingContext)
        assertNull(second.followingContext)
        assertEquals(2, manager.current()?.interactionCount)
        assertFalse(second.selectedText.contains(oldSelection))

        val secondPrompt = PromptEngine.build(second)
        assertTrue(secondPrompt.userContent.contains(newSelection))
        assertFalse(secondPrompt.userContent.contains(oldSelection))
        assertFalse(secondPrompt.userContent.contains("before-old"))
        assertFalse(secondPrompt.userContent.contains("after-old"))
        assertFalse(secondPrompt.userContent.contains(first.sessionId))
        assertFalse(secondPrompt.systemInstruction.contains(first.sessionId))

        manager.reset()
        assertNull(manager.current())
        val third = ready(manager, selectedText = "after-reset", quality = ContextQuality.LOW, preceding = "p", following = "f")
        assertEquals("session-two", third.sessionId)
        assertNotEquals(first.sessionId, third.sessionId)
        assertEquals("after-reset", third.selectedText)
        assertFalse(PromptEngine.build(third).userContent.contains(oldSelection))
        assertFalse(PromptEngine.build(third).userContent.contains(newSelection))
        assertFalse(PromptEngine.build(third).userContent.contains(first.sessionId))
        assertEquals(1, manager.current()?.interactionCount)
        assertEquals("session-two", manager.current()?.sessionId)
    }

    @Test
    fun endedSessionIsNotReused() {
        val manager = manager(ids = listOf("session-one", "session-two"))
        val first = ready(manager, selectedText = oldSelection, quality = ContextQuality.HIGH)
        manager.end()
        val second = ready(manager, selectedText = newSelection, quality = ContextQuality.HIGH)

        assertEquals(SessionState.ACTIVE, manager.current()?.state)
        assertEquals("session-two", second.sessionId)
        assertNotEquals(first.sessionId, second.sessionId)
        assertFalse(PromptEngine.build(second).userContent.contains(oldSelection))
    }

    @Test
    fun contextOnAndOffKeepTheSessionOutOfThePrompt() {
        val manager = manager(ids = listOf(sessionToken))
        val on = ready(
            manager,
            selectedText = "Cells store fuel before they can spend it.",
            quality = ContextQuality.HIGH,
            preceding = "A cell still has to turn stored fuel into usable energy.",
            following = "That next step is what the mitochondria sentence describes.",
        )
        val off = ready(
            manager,
            selectedText = "Cells store fuel before they can spend it.",
            quality = ContextQuality.UNAVAILABLE,
            preceding = null,
            following = null,
        )
        val onPrompt = PromptEngine.build(on)
        val offPrompt = PromptEngine.build(off)

        assertEquals(on.sessionId, off.sessionId)
        assertEquals(sessionToken, on.sessionId)
        assertTrue(onPrompt.userContent.contains("A cell still has to turn stored fuel into usable energy."))
        assertTrue(onPrompt.userContent.contains("That next step is what the mitochondria sentence describes."))
        assertTrue(onPrompt.userContent.contains("Context quality: HIGH"))
        assertFalse(onPrompt.userContent.contains(sessionToken))
        assertFalse(onPrompt.systemInstruction.contains(sessionToken))

        assertTrue(offPrompt.userContent.contains("Only the selected text is available."))
        assertTrue(offPrompt.userContent.contains("Answer from the selected text alone."))
        assertTrue(offPrompt.systemInstruction.contains("Preceding context is unavailable."))
        assertTrue(offPrompt.systemInstruction.contains("Answer from the selected text alone."))
        assertFalse(offPrompt.userContent.contains("A cell still has to turn stored fuel into usable energy."))
        assertFalse(offPrompt.userContent.contains(sessionToken))
        assertFalse(offPrompt.systemInstruction.contains(sessionToken))
        assertFalse(off.selectedText.contains(oldSelection))
    }

    @Test
    fun mockAndRemoteProvidersKeepTheSession() {
        val manager = manager(ids = listOf(sessionToken))
        val first = ready(manager, selectedText = oldSelection, quality = ContextQuality.HIGH, preceding = "old-before", following = "old-after")
        val second = ready(
            manager,
            selectedText = newSelection,
            quality = ContextQuality.UNAVAILABLE,
            preceding = null,
            following = null,
        )
        val mock = MockAIProvider(newId = { "mock-response" })
        val mockResponse = runBlocking { AIService(mock).generate(second) }
        assertEquals(second.sessionId, mockResponse.sessionId)
        assertEquals(second.requestId, mockResponse.requestId)
        assertEquals("mock-response", mockResponse.responseId)
        assertNotEquals(mockResponse.sessionId, mockResponse.responseId)
        assertTrue(mockResponse.content.contains(newSelection))
        assertTrue(mockResponse.content.contains("Only the selected text is available."))
        assertFalse(mockResponse.content.contains(oldSelection))
        assertFalse(mockResponse.content.contains(sessionToken))
        assertFalse(mockResponse.content.contains("old-before"))

        val failing = object : AIProvider {
            override val providerName = "RecordingProvider"
            override val modelName = "recording-model"
            override suspend fun generateResponse(request: AIRequest, prompt: Prompt): AIResponse {
                throw IllegalStateException("boom $oldSelection $sessionToken")
            }
        }
        val error = runBlocking { AIService(failing, newId = { "error-id" }).generate(second) }
        assertEquals(second.sessionId, error.sessionId)
        assertEquals("error-id", error.responseId)
        assertNotEquals(error.sessionId, error.responseId)
        assertEquals(ResponseStatus.ERROR, error.status)
        assertFalse(error.content.contains(oldSelection))
        assertFalse(error.content.contains(sessionToken))

        val fake = RecordingBackend()
        val remote = RemoteAIProvider(fake, newId = { "remote-local" })
        val remoteResponse = runBlocking { remote.generateResponse(second, PromptEngine.build(second)) }
        val payload = fake.payloads.single()
        val json = parseJsonObject(payload.toJson())
        @Suppress("UNCHECKED_CAST")
        val promptJson = json["prompt"] as Map<String, Any?>

        assertEquals(sessionToken, payload.sessionId)
        assertEquals(sessionToken, json["sessionId"])
        assertEquals(second.requestId, json["requestId"])
        assertEquals(newSelection, payload.selectedText)
        assertEquals(PromptEngine.build(second).userContent, promptJson["userContent"])
        assertEquals(PromptEngine.build(second).systemInstruction, promptJson["systemInstruction"])
        assertFalse((promptJson["userContent"] as String).contains(sessionToken))
        assertFalse((promptJson["userContent"] as String).contains(oldSelection))
        assertFalse((promptJson["systemInstruction"] as String).contains(sessionToken))
        assertFalse(payload.toJson().contains("old-before"))
        assertEquals(setOf("systemInstruction", "userContent"), promptJson.keys)
        assertEquals(second.sessionId, remoteResponse.sessionId)
        assertEquals(second.requestId, remoteResponse.requestId)
        assertEquals("response-9", remoteResponse.responseId)
        assertNotEquals(remoteResponse.sessionId, remoteResponse.requestId)
        assertNotEquals(remoteResponse.responseId, remoteResponse.requestId)
        assertFalse(remoteResponse.content.contains(sessionToken))

        val replaced = RecordingBackend(bodySessionId = "server-replaced")
        val kept = runBlocking {
            RemoteAIProvider(replaced, newId = { "unused" }).generateResponse(first, PromptEngine.build(first))
        }
        assertEquals(first.sessionId, kept.sessionId)
        assertNotEquals("server-replaced", kept.sessionId)
    }

    @Test
    fun missingSourceAppSaysUnavailable() {
        val manager = manager(ids = listOf("session-a"))
        val missing = ready(manager, selectedText = newSelection, quality = ContextQuality.HIGH, sourcePackage = null)
        val present = ready(manager, selectedText = newSelection, quality = ContextQuality.HIGH, sourcePackage = "dev.probe.samplechat")

        assertEquals("unavailable", RequestFields.from(missing).sourceApp)
        assertEquals("dev.probe.samplechat", RequestFields.from(present).sourceApp)
        assertNull(missing.sourcePackage)
        assertFalse(PromptEngine.build(missing).userContent.contains("dev.probe.samplechat"))
    }

    private fun manager(ids: List<String>): ConversationSessionManager {
        sessionIds = 0
        return ConversationSessionManager(
            newId = {
                val id = ids[sessionIds]
                sessionIds += 1
                id
            },
            now = { instant },
        )
    }

    private fun ready(
        manager: ConversationSessionManager,
        selectedText: String,
        quality: ContextQuality,
        preceding: String? = "preceding",
        following: String? = "following",
        sourcePackage: String? = "dev.probe.samplechat",
    ): AIRequest {
        val attempt = AIRequestFactory.create(
            packageWith(selectedText, quality, preceding, following, sourcePackage),
            UserAction.EXPLAIN,
            sessionId = { manager.idForValidInteraction() },
            newId = {
                requestIds += 1
                "request-$requestIds"
            },
            now = { instant },
        )
        return (attempt as AIRequestAttempt.Ready).request
    }

    private fun packageWith(
        selectedText: String?,
        quality: ContextQuality,
        preceding: String? = null,
        following: String? = null,
        sourcePackage: String? = "dev.probe.samplechat",
    ) = ContextPackage(
        selectedText = selectedText,
        precedingContext = preceding,
        followingContext = following,
        sourcePackage = sourcePackage,
        captureMethod = if (quality == ContextQuality.UNAVAILABLE) CaptureMethod.SelectedText else CaptureMethod.Accessibility,
        captureStatus = if (quality == ContextQuality.UNAVAILABLE) CaptureStatus.FAILED else CaptureStatus.SUCCESS,
        contextQuality = quality,
    )

    private class RecordingBackend(
        private val bodySessionId: String? = null,
    ) : AIBackendClient {
        val payloads = mutableListOf<BackendRequestPayload>()

        override suspend fun exchange(payload: BackendRequestPayload): BackendExchange {
            payloads += payload
            val extra = if (bodySessionId == null) "" else ""","sessionId":"$bodySessionId""""
            val body = """
                {
                  "requestId": "${payload.requestId}",
                  "responseId": "response-9",
                  "content": "Fuel is spent in the mitochondria.",
                  "provider": "OpenAI",
                  "model": "gpt-4o-mini",
                  "status": "SUCCESS"$extra
                }
            """.trimIndent()
            return BackendExchange.Response(statusCode = 200, body = body)
        }
    }
}
