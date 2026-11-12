package dev.probe.textselection.interaction

import dev.probe.textselection.ai.AIBackendClient
import dev.probe.textselection.ai.AIService
import dev.probe.textselection.ai.BackendExchange
import dev.probe.textselection.ai.BackendRequestPayload
import dev.probe.textselection.ai.MockAIProvider
import dev.probe.textselection.ai.PromptEngine
import dev.probe.textselection.ai.RemoteAIProvider
import dev.probe.textselection.ai.jsonString
import dev.probe.textselection.ai.parseJsonObject
import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.engine.ContextQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class InteractionMemoryTest {
    private val instant = Instant.parse("2026-11-12T00:00:00Z")
    private val selected = "Cells store fuel before they can spend it."
    private val preceding = "A cell still has to turn stored fuel into usable energy."
    private val following = "That next step is what the mitochondria sentence describes."
    private val exactSelected = "  Cells store fuel.\nStill  two spaces."
    private val exactPreceding = "pre\texact  "
    private val exactFollowing = " follow \"end\" "
    private val sourcePackage = "dev.probe.samplechat"

    @Test
    fun memoryRecordsOnlyTheInteractionFields() {
        val names = InteractionMemory::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        assertEquals(
            setOf(
                "memoryId",
                "sessionId",
                "requestId",
                "responseId",
                "userAction",
                "selectedText",
                "precedingContext",
                "followingContext",
                "generatedResponse",
                "sourcePackage",
                "createdAt",
            ),
            names,
        )
    }

    @Test
    fun successfulMockInteractionIsStored() {
        val store = InteractionMemoryStore()
        val service = service(store, provider = MockAIProvider(newId = { "response-1" }), memoryId = "memory-1")
        val request = request(sessionId = "session-a", requestId = "request-1", action = UserAction.EXPLAIN)
        val response = runBlocking { service.generate(request) }

        assertEquals(ResponseStatus.SUCCESS, response.status)
        assertEquals("response-1", response.responseId)
        assertEquals(request.requestId, response.requestId)
        val memory = store.list("session-a").single()
        assertEquals("memory-1", memory.memoryId)
        assertEquals("session-a", memory.sessionId)
        assertEquals(UserAction.EXPLAIN, memory.userAction)
        assertEquals(response.content, memory.generatedResponse)
        assertEquals(instant, memory.createdAt)
        assertEquals(sourcePackage, memory.sourcePackage)
        assertFalse(memory.generatedResponse.isBlank())
    }

    @Test
    fun listReturnsOnlyTheRequestedSession() {
        val store = InteractionMemoryStore()
        val service = sequencingService(store)
        runBlocking {
            service.generate(request(sessionId = "session-a", requestId = "request-a", selectedText = "alpha selection"))
            service.generate(request(sessionId = "session-b", requestId = "request-b", selectedText = "beta selection"))
        }

        val listed = store.list("session-a")
        assertEquals(1, listed.size)
        assertEquals("session-a", listed.single().sessionId)
        assertEquals("alpha selection", listed.single().selectedText)
        assertEquals("request-a", listed.single().requestId)
    }

    @Test
    fun otherSessionIsInvisible() {
        val store = InteractionMemoryStore()
        val service = sequencingService(store)
        runBlocking {
            service.generate(request(sessionId = "session-a", requestId = "request-a", selectedText = "alpha selection"))
            service.generate(request(sessionId = "session-b", requestId = "request-b", selectedText = "beta selection"))
        }

        val other = store.list("session-b")
        assertEquals("beta selection", other.single().selectedText)
        assertTrue(store.list("session-a").none { it.sessionId == "session-b" || it.selectedText == "beta selection" })
        assertTrue(store.list("session-b").none { it.sessionId == "session-a" || it.selectedText == "alpha selection" })
        assertTrue(store.list("session-c").isEmpty())
        assertTrue(store.list("").isEmpty())
        assertTrue(store.list(" session-a").isEmpty())

        store.clear("session-a")
        assertTrue(store.list("session-a").isEmpty())
        assertEquals("beta selection", store.list("session-b").single().selectedText)
        store.clearAll()
        assertTrue(store.list("session-b").isEmpty())
    }

    @Test
    fun resetClearsThatSessionsMemories() {
        val store = InteractionMemoryStore()
        val manager = manager(store, "session-old")
        val service = service(store, provider = MockAIProvider(newId = { "response-old" }), memoryId = "memory-old")
        val request = ready(manager, selectedText = "OLD-SELECTION")
        runBlocking { service.generate(request) }
        store.save(
            stored(
                memoryId = "other-memory",
                sessionId = "session-other",
                selectedText = "OTHER-SELECTION",
            ),
        )

        assertEquals("session-old", manager.current()?.sessionId)
        manager.reset()

        assertTrue(store.list("session-old").isEmpty())
        assertEquals("OTHER-SELECTION", store.list("session-other").single().selectedText)
        assertNull(manager.current())
        assertFalse(manager.exists())
    }

    @Test
    fun newSessionDoesNotSeeOldMemories() {
        val store = InteractionMemoryStore()
        val manager = manager(store, "session-old", "session-new")
        val first = service(store, provider = MockAIProvider(newId = { "response-old" }), memoryId = "memory-old")
        val oldRequest = ready(manager, selectedText = "OLD-SELECTION")
        val oldResponse = runBlocking { first.generate(oldRequest) }
        store.save(
            stored(
                memoryId = "other-memory",
                sessionId = "session-other",
                selectedText = "OTHER-SELECTION",
                generatedResponse = "OTHER-RESPONSE",
            ),
        )

        manager.reset()
        assertTrue(store.list("session-old").isEmpty())

        val second = service(store, provider = MockAIProvider(newId = { "response-new" }), memoryId = "memory-new")
        val newRequest = ready(manager, selectedText = "NEW-SELECTION")
        assertEquals("session-new", newRequest.sessionId)
        assertTrue(store.list("session-new").isEmpty())
        val newResponse = runBlocking { second.generate(newRequest) }
        val prompt = PromptEngine.build(newRequest)

        val remembered = store.list("session-new").single()
        assertEquals("NEW-SELECTION", remembered.selectedText)
        assertEquals("memory-new", remembered.memoryId)
        assertEquals(newResponse.responseId, remembered.responseId)
        assertFalse(remembered.generatedResponse.contains("OLD-SELECTION"))
        assertFalse(prompt.userContent.contains("OLD-SELECTION"))
        assertFalse(prompt.userContent.contains(oldResponse.content))
        assertFalse(prompt.systemInstruction.contains("OTHER-RESPONSE"))
        assertTrue(store.list("session-old").isEmpty())
        assertEquals("OTHER-SELECTION", store.list("session-other").single().selectedText)
        assertEquals(1, manager.current()?.interactionCount)
    }

    @Test
    fun failedResponsesAreNotStored() {
        val store = InteractionMemoryStore()
        val request = request(sessionId = "session-a", requestId = "request-fail")
        val exploding = AIService(
            provider = object : AIProvider {
                override val providerName = "RecordingProvider"
                override val modelName = "recording-model"
                override suspend fun generateResponse(request: AIRequest, prompt: Prompt): AIResponse {
                    throw IllegalStateException("boom")
                }
            },
            memoryStore = store,
            newId = { "error-id" },
        )
        val thrown = runBlocking { exploding.generate(request) }
        assertEquals(ResponseStatus.ERROR, thrown.status)
        assertEquals("error-id", thrown.responseId)
        assertEquals(AIService.ERROR_MESSAGE, thrown.content)

        val errored = runBlocking {
            service(
                store,
                provider = stub { source ->
                    response(source, content = "nope", status = ResponseStatus.ERROR, responseId = "response-error")
                },
                memoryId = "should-not-be-used",
            ).generate(request)
        }
        assertEquals(ResponseStatus.ERROR, errored.status)
        assertEquals("response-error", errored.responseId)

        val timedOut = FakeBackend { BackendExchange.TimedOut }
        val timeoutResponse = runBlocking {
            AIService(RemoteAIProvider(timedOut, newId = { "remote-error" }), memoryStore = store).generate(request)
        }
        assertEquals(ResponseStatus.ERROR, timeoutResponse.status)
        assertEquals(1, timedOut.payloads.size)

        val remoteError = FakeBackend { payload ->
            BackendExchange.Response(
                statusCode = 200,
                body = successBody(payload.requestId, content = "backend failed", status = "ERROR"),
            )
        }
        val remoteErrorResponse = runBlocking {
            AIService(RemoteAIProvider(remoteError), memoryStore = store).generate(request)
        }
        assertEquals(ResponseStatus.ERROR, remoteErrorResponse.status)
        assertEquals(1, remoteError.payloads.size)
        assertTrue(store.list("session-a").isEmpty())
        assertTrue(store.list(request.sessionId).isEmpty())
    }

    @Test
    fun blankResponsesAreNotStored() {
        val store = InteractionMemoryStore()
        val request = request(sessionId = "session-a")
        listOf("", "   ", "\n").forEach { blank ->
            val response = runBlocking {
                service(
                    store,
                    provider = stub { source ->
                        response(source, content = blank, status = ResponseStatus.SUCCESS, responseId = "blank-response")
                    },
                ).generate(request)
            }
            assertEquals(ResponseStatus.SUCCESS, response.status)
            assertEquals(blank, response.content)
        }

        val emptyRemote = FakeBackend { payload ->
            BackendExchange.Response(
                statusCode = 200,
                body = successBody(payload.requestId, content = "   ", responseId = "empty-remote"),
            )
        }
        val remoteResponse = runBlocking {
            AIService(RemoteAIProvider(emptyRemote, newId = { "local-empty" }), memoryStore = store).generate(request)
        }
        assertEquals(ResponseStatus.ERROR, remoteResponse.status)
        assertEquals(1, emptyRemote.payloads.size)
        assertTrue(store.list("session-a").isEmpty())
    }

    @Test
    fun missingOrBlankSessionIdIsNotStored() {
        val store = InteractionMemoryStore()
        listOf("", "   ", "\n").forEach { sessionId ->
            val request = request(sessionId = sessionId, requestId = "request-blank-session")
            val echoed = runBlocking {
                service(store, provider = MockAIProvider(newId = { "response-blank-session" })).generate(request)
            }
            assertEquals(sessionId, echoed.sessionId)
            assertEquals(ResponseStatus.SUCCESS, echoed.status)

            val responseSessionBlank = runBlocking {
                service(
                    store,
                    provider = stub { source ->
                        response(source, content = "kept", responseId = "response-kept").copy(sessionId = " ")
                    },
                ).generate(request(sessionId = "session-real", requestId = "request-real"))
            }
            assertEquals(ResponseStatus.SUCCESS, responseSessionBlank.status)
            assertEquals(" ", responseSessionBlank.sessionId)
        }
        assertTrue(store.list("session-real").isEmpty())
        assertTrue(store.list(" ").isEmpty())
        assertTrue(store.list("").isEmpty())

        val rejected = AIRequestFactory.create(
            packageWith(selectedText = "  ", quality = ContextQuality.HIGH),
            UserAction.EXPLAIN,
            sessionId = { "session-should-not-open" },
        )
        assertTrue(rejected is AIRequestAttempt.Rejected)
        val blankSelection = request(sessionId = "session-a", selectedText = selected).copy(selectedText = " \n ")
        val ignored = runBlocking {
            service(
                store,
                provider = stub { source -> response(source, content = "should-not-stick", responseId = "r") },
            ).generate(blankSelection)
        }
        assertEquals("should-not-stick", ignored.content)
        assertTrue(store.list("session-a").isEmpty())
    }

    @Test
    fun duplicateMemoryIdsAreRejected() {
        val store = InteractionMemoryStore()
        val first = stored(memoryId = "same-id", sessionId = "session-a", selectedText = "first")
        store.save(first)
        try {
            store.save(stored(memoryId = "same-id", sessionId = "session-b", selectedText = "second"))
            fail("duplicate memory id should be rejected")
        } catch (error: DuplicateInteractionMemoryIdException) {
            assertEquals("same-id", error.memoryId)
        }
        assertEquals(listOf(first), store.list("session-a"))
        assertTrue(store.list("session-b").isEmpty())

        val service = service(
            store,
            provider = MockAIProvider(newId = { "response-new" }),
            memoryId = "same-id",
        )
        val response = runBlocking {
            service.generate(request(sessionId = "session-a", requestId = "request-new", selectedText = "fresh selection"))
        }
        assertEquals(ResponseStatus.SUCCESS, response.status)
        assertEquals("response-new", response.responseId)
        assertEquals("request-new", response.requestId)
        assertTrue(response.content.contains("fresh selection"))
        assertEquals(listOf(first), store.list("session-a"))
    }

    @Test
    fun defaultCapIsThirtyRecords() {
        val store = InteractionMemoryStore()
        assertEquals(30, InteractionMemoryStore.DEFAULT_MAX_PER_SESSION)
        repeat(30) { index ->
            store.save(stored(memoryId = "m-$index", sessionId = "session-a", createdAt = instant.plusSeconds(index.toLong())))
        }
        store.save(stored(memoryId = "other", sessionId = "session-other", createdAt = instant))

        assertEquals(30, store.list("session-a").size)
        assertEquals("m-0", store.list("session-a").first().memoryId)
        assertEquals("m-29", store.list("session-a").last().memoryId)
        assertEquals(1, store.list("session-other").size)
    }

    @Test
    fun exceedingTheCapDropsOnlyTheOldestInThatSession() {
        val store = InteractionMemoryStore()
        store.save(stored(memoryId = "keep-other", sessionId = "session-other", selectedText = "keep-other-text"))
        repeat(30) { index ->
            store.save(
                stored(
                    memoryId = "m-$index",
                    sessionId = "session-a",
                    createdAt = instant.plusSeconds(index.toLong()),
                    selectedText = "text-$index",
                ),
            )
        }
        store.save(
            stored(
                memoryId = "m-30",
                sessionId = "session-a",
                createdAt = instant.plusSeconds(30),
                selectedText = "text-30",
            ),
        )

        val kept = store.list("session-a")
        assertEquals(30, kept.size)
        assertEquals("m-1", kept.first().memoryId)
        assertEquals("text-1", kept.first().selectedText)
        assertEquals("m-30", kept.last().memoryId)
        assertTrue(kept.none { it.memoryId == "m-0" })
        assertEquals("keep-other-text", store.list("session-other").single().selectedText)

        val ordered = InteractionMemoryStore(maxPerSession = 2)
        ordered.save(stored(memoryId = "newer", sessionId = "session-a", createdAt = instant.plusSeconds(2)))
        ordered.save(stored(memoryId = "oldest", sessionId = "session-a", createdAt = instant))
        ordered.save(stored(memoryId = "middle", sessionId = "session-a", createdAt = instant.plusSeconds(1)))
        assertEquals(listOf("middle", "newer"), ordered.list("session-a").map { it.memoryId })
    }

    @Test
    fun selectedTextAndSurroundingContextArePreserved() {
        val store = InteractionMemoryStore()
        val service = service(store, provider = MockAIProvider(newId = { "response-exact" }), memoryId = "memory-exact")
        val request = request(
            sessionId = "session-a",
            requestId = "request-exact",
            selectedText = exactSelected,
            preceding = exactPreceding,
            following = exactFollowing,
            action = UserAction.EXAMPLE,
        )
        val response = runBlocking { service.generate(request) }
        val memory = store.list("session-a").single()

        assertEquals(exactSelected, memory.selectedText)
        assertEquals(exactPreceding, memory.precedingContext)
        assertEquals(exactFollowing, memory.followingContext)
        assertEquals(sourcePackage, memory.sourcePackage)
        assertEquals(UserAction.EXAMPLE, memory.userAction)
        assertEquals(response.content, memory.generatedResponse)
        assertEquals(exactSelected, request.selectedText)

        val unavailable = request(
            sessionId = "session-b",
            requestId = "request-unavailable",
            selectedText = exactSelected,
            preceding = null,
            following = null,
            sourcePackage = null,
            quality = ContextQuality.UNAVAILABLE,
        )
        runBlocking {
            service(
                store,
                provider = MockAIProvider(newId = { "response-unavailable" }),
                memoryId = "memory-unavailable",
            ).generate(unavailable)
        }
        val missing = store.list("session-b").single()
        assertEquals(exactSelected, missing.selectedText)
        assertNull(missing.precedingContext)
        assertNull(missing.followingContext)
        assertNull(missing.sourcePackage)
        assertEquals(exactPreceding, store.list("session-a").single().precedingContext)
    }

    @Test
    fun requestIdAndResponseIdArePreserved() {
        val store = InteractionMemoryStore()
        val mockRequest = request(sessionId = "session-mock", requestId = "request-mock")
        val mockResponse = runBlocking {
            service(
                store,
                provider = MockAIProvider(newId = { "response-mock" }),
                memoryId = "memory-mock",
            ).generate(mockRequest)
        }
        val mockMemory = store.list("session-mock").single()
        assertEquals("request-mock", mockMemory.requestId)
        assertEquals(mockRequest.requestId, mockMemory.requestId)
        assertEquals("response-mock", mockMemory.responseId)
        assertEquals(mockResponse.responseId, mockMemory.responseId)
        assertEquals(mockResponse.requestId, mockMemory.requestId)

        val remoteRequest = request(sessionId = "session-remote", requestId = "request-remote", selectedText = "Remote selection stays put.")
        val fake = FakeBackend { payload ->
            BackendExchange.Response(
                statusCode = 200,
                body = successBody(payload.requestId, content = "Fuel is spent in the mitochondria.", responseId = "response-remote"),
            )
        }
        val remoteResponse = runBlocking {
            AIService(
                provider = RemoteAIProvider(fake, newId = { "local-should-not-be-used" }),
                memoryStore = store,
                newId = { "memory-remote" },
                now = { instant },
            ).generate(remoteRequest)
        }
        val remoteMemory = store.list("session-remote").single()
        assertEquals("request-remote", remoteMemory.requestId)
        assertEquals(remoteResponse.requestId, remoteMemory.requestId)
        assertEquals("response-remote", remoteMemory.responseId)
        assertEquals(remoteResponse.responseId, remoteMemory.responseId)
        assertEquals("Fuel is spent in the mitochondria.", remoteMemory.generatedResponse)
        assertEquals(1, fake.payloads.size)
        assertFalse(remoteMemory.generatedResponse.contains("OpenAI"))
        assertFalse(remoteMemory.generatedResponse.contains("gpt-4o-mini"))
        assertFalse(remoteMemory.generatedResponse.contains("[MOCK"))
    }

    @Test
    fun remoteSuccessStaysOnTheExistingContractAndOutOfThePrompt() {
        val store = InteractionMemoryStore()
        store.save(
            stored(
                memoryId = "already",
                sessionId = "session-a",
                selectedText = "OLD-SELECTED-MEMORY",
                generatedResponse = "OLD-MEMORY-SHOULD-NOT-BE-IN-PROMPT",
            ),
        )
        val request = request(
            sessionId = "session-a",
            requestId = "request-remote",
            selectedText = "Remote selection stays put.",
            preceding = exactPreceding,
            following = exactFollowing,
        )
        val fake = FakeBackend { payload ->
            BackendExchange.Response(
                statusCode = 200,
                body = successBody(payload.requestId, content = "A mitochondrion makes ATP.", responseId = "response-9"),
            )
        }
        val response = runBlocking {
            AIService(
                provider = RemoteAIProvider(fake),
                memoryStore = store,
                newId = { "memory-remote" },
                now = { instant },
            ).generate(request)
        }

        assertEquals(ResponseStatus.SUCCESS, response.status)
        assertEquals("A mitochondrion makes ATP.", response.content)
        assertEquals(1, fake.payloads.size)
        val payload = fake.payloads.single()
        val json = parseJsonObject(payload.toJson())
        assertEquals(
            setOf(
                "requestId",
                "action",
                "selectedText",
                "precedingText",
                "followingText",
                "contextQuality",
                "sessionId",
                "prompt",
            ),
            json.keys,
        )
        assertEquals(request.requestId, payload.requestId)
        assertEquals(exactPreceding, payload.precedingText)
        assertEquals(exactFollowing, payload.followingText)
        assertEquals(PromptEngine.build(request).userContent, payload.userContent)
        assertEquals(PromptEngine.build(request).systemInstruction, payload.systemInstruction)
        assertFalse(payload.userContent.contains("OLD-MEMORY-SHOULD-NOT-BE-IN-PROMPT"))
        assertFalse(payload.userContent.contains("OLD-SELECTED-MEMORY"))
        assertFalse(payload.toJson().contains("memory-remote"))
        assertFalse(payload.toJson().contains("A mitochondrion makes ATP."))
        val memory = store.list("session-a").last()
        assertEquals("memory-remote", memory.memoryId)
        assertEquals("A mitochondrion makes ATP.", memory.generatedResponse)
        assertEquals("Remote selection stays put.", memory.selectedText)
    }

    private fun service(
        store: InteractionMemoryStore,
        provider: AIProvider = MockAIProvider(newId = { "response-fixed" }),
        memoryId: String = "memory-fixed",
    ): AIService {
        return AIService(
            provider = provider,
            memoryStore = store,
            newId = { memoryId },
            now = { instant },
        )
    }

    private fun sequencingService(store: InteractionMemoryStore): AIService {
        var next = 0
        return AIService(
            provider = MockAIProvider(newId = {
                next += 1
                "response-$next"
            }),
            memoryStore = store,
            newId = {
                next += 1
                "memory-$next"
            },
            now = { instant },
        )
    }

    private fun manager(store: InteractionMemoryStore, vararg ids: String): ConversationSessionManager {
        var index = 0
        return ConversationSessionManager(
            newId = {
                val id = ids[index]
                index += 1
                id
            },
            now = { instant },
            memories = store,
        )
    }

    private fun ready(
        manager: ConversationSessionManager,
        selectedText: String,
    ): AIRequest {
        val attempt = AIRequestFactory.create(
            packageWith(selectedText, ContextQuality.HIGH, preceding, following, sourcePackage),
            UserAction.FOLLOW_UP,
            sessionId = { manager.idForValidInteraction() },
            newId = { "request-$selectedText" },
            now = { instant },
        )
        return (attempt as AIRequestAttempt.Ready).request
    }

    private fun request(
        sessionId: String,
        requestId: String = "request-1",
        selectedText: String = this.selected,
        preceding: String? = this.preceding,
        following: String? = this.following,
        sourcePackage: String? = this.sourcePackage,
        action: UserAction = UserAction.EXPLAIN,
        quality: ContextQuality = ContextQuality.HIGH,
    ): AIRequest {
        val attempt = AIRequestFactory.create(
            packageWith(selectedText, quality, preceding, following, sourcePackage),
            action,
            sessionId = { sessionId },
            newId = { requestId },
            now = { instant },
        )
        return (attempt as AIRequestAttempt.Ready).request
    }

    private fun packageWith(
        selectedText: String?,
        quality: ContextQuality,
        preceding: String? = null,
        following: String? = null,
        sourcePackage: String? = this.sourcePackage,
    ) = ContextPackage(
        selectedText = selectedText,
        precedingContext = preceding,
        followingContext = following,
        sourcePackage = sourcePackage,
        captureMethod = if (quality == ContextQuality.UNAVAILABLE) CaptureMethod.SelectedText else CaptureMethod.Accessibility,
        captureStatus = if (quality == ContextQuality.UNAVAILABLE) CaptureStatus.FAILED else CaptureStatus.SUCCESS,
        contextQuality = quality,
    )

    private fun stored(
        memoryId: String,
        sessionId: String,
        createdAt: Instant = instant,
        selectedText: String = "selected-$memoryId",
        precedingContext: String? = "pre-$memoryId",
        followingContext: String? = "post-$memoryId",
        generatedResponse: String = "generated-$memoryId",
        sourcePackage: String? = this.sourcePackage,
        userAction: UserAction = UserAction.EXPLAIN,
        requestId: String = "request-$memoryId",
        responseId: String = "response-$memoryId",
    ) = InteractionMemory(
        memoryId = memoryId,
        sessionId = sessionId,
        requestId = requestId,
        responseId = responseId,
        userAction = userAction,
        selectedText = selectedText,
        precedingContext = precedingContext,
        followingContext = followingContext,
        generatedResponse = generatedResponse,
        sourcePackage = sourcePackage,
        createdAt = createdAt,
    )

    private fun response(
        request: AIRequest,
        content: String,
        status: ResponseStatus = ResponseStatus.SUCCESS,
        responseId: String = "response-1",
    ) = AIResponse(
        responseId = responseId,
        requestId = request.requestId,
        sessionId = request.sessionId,
        action = request.action,
        content = content,
        provider = "StubProvider",
        model = "stub-model",
        status = status,
    )

    private fun stub(
        respond: (AIRequest) -> AIResponse,
    ): AIProvider {
        return object : AIProvider {
            override val providerName = "StubProvider"
            override val modelName = "stub-model"
            override suspend fun generateResponse(request: AIRequest, prompt: Prompt): AIResponse = respond(request)
        }
    }

    private fun successBody(
        requestId: String,
        content: String,
        responseId: String = "response-9",
        status: String = "SUCCESS",
    ): String {
        return buildString {
            append('{')
            append("\"requestId\":").append(jsonString(requestId))
            append(",\"responseId\":").append(jsonString(responseId))
            append(",\"content\":").append(jsonString(content))
            append(",\"provider\":").append(jsonString("OpenAI"))
            append(",\"model\":").append(jsonString("gpt-4o-mini"))
            append(",\"status\":").append(jsonString(status))
            append('}')
        }
    }

    private class FakeBackend(
        private val handler: (BackendRequestPayload) -> BackendExchange,
    ) : AIBackendClient {
        val payloads = mutableListOf<BackendRequestPayload>()

        override suspend fun exchange(payload: BackendRequestPayload): BackendExchange {
            payloads += payload
            return handler(payload)
        }
    }
}
