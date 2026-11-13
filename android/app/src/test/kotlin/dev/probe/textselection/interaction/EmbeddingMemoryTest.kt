package dev.probe.textselection.interaction

import dev.probe.textselection.ai.AIBackendClient
import dev.probe.textselection.ai.AIService
import dev.probe.textselection.ai.BackendExchange
import dev.probe.textselection.ai.BackendRequestPayload
import dev.probe.textselection.ai.EMBEDDING_DIMENSIONS
import dev.probe.textselection.ai.EmbeddingClient
import dev.probe.textselection.ai.EmbeddingLog
import dev.probe.textselection.ai.EmbeddingLogger
import dev.probe.textselection.ai.EmbeddingResult
import dev.probe.textselection.ai.MockAIProvider
import dev.probe.textselection.ai.PromptEngine
import dev.probe.textselection.ai.RemoteAIProvider
import dev.probe.textselection.ai.jsonString
import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.engine.ContextQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EmbeddingMemoryTest {
    private val instant = Instant.parse("2026-11-12T00:00:00Z")
    private val selected = "Cells store fuel before they can spend it."
    private val preceding = "A cell still has to turn stored fuel into usable energy."
    private val following = "That next step is what the mitochondria sentence describes."
    private val sourcePackage = "dev.probe.samplechat"
    private val answer = "A mitochondrion makes ATP."

    @Test
    fun passageUsesEachStoredFieldOnceAndOmitsMetadata() {
        val memory = stored(
            memoryId = "memory-passage",
            sessionId = "session-passage",
            requestId = "request-passage",
            responseId = "response-passage",
            selectedText = selected,
            precedingContext = preceding,
            followingContext = following,
            generatedResponse = answer,
        )
        val passage = memoryPassage(memory)
        assertEquals(
            listOf(
                "Selected text: $selected",
                "Preceding context: $preceding",
                "Following context: $following",
                "Generated response: $answer",
            ).joinToString("\n"),
            passage,
        )
        assertEquals(1, passage.split(selected).size - 1)
        assertEquals(1, passage.split(preceding).size - 1)
        assertEquals(1, passage.split(following).size - 1)
        assertEquals(1, passage.split(answer).size - 1)
        assertFalse(passage.contains(memory.memoryId))
        assertFalse(passage.contains(memory.sessionId))
        assertFalse(passage.contains(memory.requestId))
        assertFalse(passage.contains(memory.responseId))
        assertFalse(passage.contains(sourcePackage))
        assertFalse(passage.contains("EXPLAIN"))
        assertFalse(passage.contains(instant.toString()))

        val withoutSides = memoryPassage(
            memory.copy(precedingContext = null, followingContext = null),
        )
        assertEquals(
            "Selected text: $selected\nGenerated response: $answer",
            withoutSides,
        )
        assertFalse(withoutSides.contains("Preceding context:"))
        assertFalse(withoutSides.contains("Following context:"))
    }

    @Test
    fun remoteSuccessAttachesTheVectorToTheSameMemory() {
        val store = InteractionMemoryStore()
        val logs = mutableListOf<String>()
        val vector = sampleVector(1.0)
        val embeddings = FakeEmbeddings {
            EmbeddingResult.Success(vector, "text-embedding-3-small", EMBEDDING_DIMENSIONS)
        }
        withScope { scope ->
            val service = service(store, scope, embeddings, logs, memoryId = "memory-remote")
            val request = request(sessionId = "session-a", requestId = "request-remote")
            val response = runBlocking { service.generate(request) }
            join(scope)

            assertEquals(ResponseStatus.SUCCESS, response.status)
            assertEquals(answer, response.content)
            assertEquals(2, embeddings.texts.size)
            val memory = store.list("session-a").single()
            assertEquals("memory-remote", memory.memoryId)
            assertEquals(request.requestId, memory.requestId)
            assertEquals(response.responseId, memory.responseId)
            assertEquals(selected, memory.selectedText)
            assertEquals(preceding, memory.precedingContext)
            assertEquals(following, memory.followingContext)
            assertEquals(answer, memory.generatedResponse)
            assertEquals(vector, memory.embedding)
            assertEquals(EmbeddingStatus.COMPLETED, memory.embeddingStatus)
            assertFalse(embeddings.texts[0].contains("Generated response:"))
            assertEquals(memoryPassage(memory), embeddings.texts[1])
            assertFalse(embeddings.texts[1].contains(memory.memoryId))
            assertFalse(embeddings.texts[1].contains(memory.sessionId))
            assertFalse(embeddings.texts[1].contains(memory.requestId))
            assertFalse(embeddings.texts[1].contains(memory.responseId))
            assertFalse(embeddings.texts[1].contains(sourcePackage))
            assertTrue(logs.isEmpty())
            val prompt = PromptEngine.build(request)
            assertFalse(prompt.userContent.contains("Generated response:"))
            assertFalse(prompt.userContent.contains(answer))
            assertFalse(prompt.systemInstruction.contains(answer))
            assertEquals(1, store.list("session-a").size)
        }
    }

    @Test
    fun responseReturnsWhileTheEmbeddingIsStillPending() {
        val store = InteractionMemoryStore()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val embeddings = object : EmbeddingClient {
            override suspend fun embed(text: String): EmbeddingResult {
                if (!text.contains("Generated response:")) {
                    return EmbeddingResult.Success(sampleVector(1.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS)
                }
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return EmbeddingResult.Success(sampleVector(1.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS)
            }
        }
        withScope { scope ->
            try {
                val service = service(store, scope, embeddings, mutableListOf(), memoryId = "memory-pending")
                val response = runBlocking {
                    service.generate(request(sessionId = "session-a", requestId = "request-pending"))
                }
                assertEquals(ResponseStatus.SUCCESS, response.status)
                assertEquals(answer, response.content)
                assertTrue(started.await(3, TimeUnit.SECONDS))
                val pending = store.list("session-a").single()
                assertEquals(EmbeddingStatus.PENDING, pending.embeddingStatus)
                assertNull(pending.embedding)
                assertEquals("memory-pending", pending.memoryId)
                assertEquals(answer, pending.generatedResponse)
                release.countDown()
                join(scope)
                val done = store.list("session-a").single()
                assertEquals("memory-pending", done.memoryId)
                assertEquals(EmbeddingStatus.COMPLETED, done.embeddingStatus)
                assertEquals(EMBEDDING_DIMENSIONS, done.embedding?.size)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun embeddingFailureKeepsTheOriginalMemory() {
        val store = InteractionMemoryStore()
        val logs = mutableListOf<String>()
        val embeddings = FakeEmbeddings { EmbeddingResult.BackendError }
        withScope { scope ->
            val service = service(store, scope, embeddings, logs, memoryId = "memory-failed")
            val request = request(sessionId = "session-a", requestId = "request-failed")
            val response = runBlocking { service.generate(request) }
            join(scope)

            assertEquals(ResponseStatus.SUCCESS, response.status)
            assertEquals(answer, response.content)
            val memory = store.list("session-a").single()
            assertEquals("memory-failed", memory.memoryId)
            assertEquals(selected, memory.selectedText)
            assertEquals(preceding, memory.precedingContext)
            assertEquals(following, memory.followingContext)
            assertEquals(answer, memory.generatedResponse)
            assertEquals(request.requestId, memory.requestId)
            assertEquals(response.responseId, memory.responseId)
            assertNull(memory.embedding)
            assertEquals(EmbeddingStatus.FAILED, memory.embeddingStatus)
            assertEquals(listOf(EmbeddingLog.BACKEND), logs)
            assertFalse(logs.any { it.contains(selected) || it.contains(answer) || it.contains("sk-") })
            assertEquals(2, embeddings.texts.size)
            assertFalse(embeddings.texts[0].contains("Generated response:"))
            assertTrue(embeddings.texts[1].contains("Generated response:"))
        }
    }

    @Test
    fun mockProviderMakesNoEmbeddingCall() {
        val store = InteractionMemoryStore()
        val embeddings = FakeEmbeddings {
            EmbeddingResult.Success(sampleVector(1.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS)
        }
        withScope { scope ->
            val service = AIService(
                provider = MockAIProvider(newId = { "response-mock" }),
                memoryStore = store,
                newId = { "memory-mock" },
                now = { instant },
                embeddings = embeddings,
                embeddingScope = scope,
                embeddingLogger = EmbeddingLogger { fail("mock logged an embedding category") },
            )
            val response = runBlocking {
                service.generate(request(sessionId = "session-a", requestId = "request-mock"))
            }
            join(scope)
            assertEquals(ResponseStatus.SUCCESS, response.status)
            assertEquals(MockAIProvider.PROVIDER_NAME, response.provider)
            assertTrue(embeddings.texts.isEmpty())
            val memory = store.list("session-a").single()
            assertNull(memory.embedding)
            assertNull(memory.embeddingStatus)
            assertEquals("memory-mock", memory.memoryId)
        }
    }

    @Test
    fun updateReplacesTheSameMemoryId() {
        val store = InteractionMemoryStore()
        val original = stored(
            memoryId = "memory-update",
            sessionId = "session-a",
            selectedText = selected,
            generatedResponse = answer,
        )
        store.save(original)
        val attached = original.copy(
            embedding = sampleVector(1.0),
            embeddingStatus = EmbeddingStatus.COMPLETED,
        )
        assertTrue(store.update(attached))
        val replaced = attached.copy(embedding = sampleVector(4.0), embeddingStatus = EmbeddingStatus.COMPLETED)
        assertTrue(store.update(replaced))
        val listed = store.list("session-a")
        assertEquals(1, listed.size)
        assertEquals("memory-update", listed.single().memoryId)
        assertEquals(sampleVector(4.0), listed.single().embedding)
        assertEquals(selected, listed.single().selectedText)
        try {
            store.save(listed.single())
            fail("duplicate id was inserted")
        } catch (error: DuplicateInteractionMemoryIdException) {
            assertEquals("memory-update", error.memoryId)
        }
        assertEquals(1, store.list("session-a").size)
        assertFalse(store.update(original.copy(memoryId = "missing-memory")))
        assertEquals(1, store.list("session-a").size)
    }

    @Test
    fun sessionsStayIsolatedWhenOneMemoryIsEmbedded() {
        val store = InteractionMemoryStore()
        val embeddings = FakeEmbeddings {
            EmbeddingResult.Success(sampleVector(1.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS)
        }
        withScope { scope ->
            val service = service(store, scope, embeddings, mutableListOf(), memoryId = "memory-a")
            runBlocking {
                service.generate(request(sessionId = "session-a", requestId = "request-a", selectedText = "alpha selection"))
            }
            val other = stored(
                memoryId = "memory-b",
                sessionId = "session-b",
                selectedText = "beta selection",
                generatedResponse = "beta response",
            )
            store.save(other)
            join(scope)

            val sessionA = store.list("session-a").single()
            val sessionB = store.list("session-b").single()
            assertEquals("alpha selection", sessionA.selectedText)
            assertEquals(EmbeddingStatus.COMPLETED, sessionA.embeddingStatus)
            assertEquals(sampleVector(1.0), sessionA.embedding)
            assertEquals("beta selection", sessionB.selectedText)
            assertNull(sessionB.embedding)
            assertNull(sessionB.embeddingStatus)
            assertTrue(store.list("session-a").none { it.sessionId == "session-b" || it.selectedText == "beta selection" })
            assertTrue(store.list("session-b").none { it.memoryId == "memory-a" })
            assertEquals(2, embeddings.texts.size)
            assertTrue(embeddings.texts.none { it.contains("beta selection") })
        }
    }

    @Test
    fun resetClearsEmbeddedMemoriesAndAnInFlightUpdateDoesNotRestoreThem() {
        val store = InteractionMemoryStore()
        val manager = manager(store, "session-old", "session-new")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val embeddings = object : EmbeddingClient {
            override suspend fun embed(text: String): EmbeddingResult {
                if (!text.contains("Generated response:")) {
                    return EmbeddingResult.Success(sampleVector(1.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS)
                }
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return EmbeddingResult.Success(sampleVector(1.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS)
            }
        }
        store.save(
            stored(
                memoryId = "other-memory",
                sessionId = "session-other",
                selectedText = "OTHER-SELECTION",
                embedding = sampleVector(4.0),
                embeddingStatus = EmbeddingStatus.COMPLETED,
            ),
        )
        withScope { scope ->
            try {
                val service = service(store, scope, embeddings, mutableListOf(), memoryId = "memory-old")
                val request = ready(manager, "OLD-SELECTION")
                val response = runBlocking { service.generate(request) }
                assertEquals(ResponseStatus.SUCCESS, response.status)
                assertTrue(started.await(3, TimeUnit.SECONDS))
                assertEquals(EmbeddingStatus.PENDING, store.list("session-old").single().embeddingStatus)
                manager.reset()
                assertTrue(store.list("session-old").isEmpty())
                assertEquals("OTHER-SELECTION", store.list("session-other").single().selectedText)
                release.countDown()
                join(scope)
                assertTrue(store.list("session-old").isEmpty())
                assertEquals(sampleVector(4.0), store.list("session-other").single().embedding)
                assertNull(manager.current())

                val next = service(
                    store,
                    scope,
                    FakeEmbeddings { EmbeddingResult.BackendError },
                    mutableListOf(),
                    memoryId = "memory-new",
                    provider = RemoteAIProvider(
                        FakeBackend { BackendExchange.Response(200, successBody("request-NEW-SELECTION", "fresh answer", "response-new")) },
                        newId = { "unused" },
                    ),
                )
                val fresh = ready(manager, "NEW-SELECTION")
                assertEquals("session-new", fresh.sessionId)
                runBlocking { next.generate(fresh) }
                join(scope)
                assertTrue(store.list("session-old").isEmpty())
                assertEquals("memory-new", store.list("session-new").single().memoryId)
                assertEquals("NEW-SELECTION", store.list("session-new").single().selectedText)
                assertEquals("OTHER-SELECTION", store.list("session-other").single().selectedText)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun failedRemoteResponseIsNotEmbedded() {
        val store = InteractionMemoryStore()
        val embeddings = FakeEmbeddings {
            EmbeddingResult.Success(sampleVector(1.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS)
        }
        withScope { scope ->
            val service = service(
                store,
                scope,
                embeddings,
                mutableListOf(),
                memoryId = "memory-error",
                provider = RemoteAIProvider(
                    FakeBackend { BackendExchange.TimedOut },
                    newId = { "response-error" },
                ),
            )
            val response = runBlocking {
                service.generate(request(sessionId = "session-a", requestId = "request-error"))
            }
            join(scope)
            assertEquals(ResponseStatus.ERROR, response.status)
            assertTrue(store.list("session-a").isEmpty())
            assertEquals(1, embeddings.texts.size)
            assertFalse(embeddings.texts.single().contains("Generated response:"))
        }
    }

    private fun service(
        store: InteractionMemoryStore,
        scope: CoroutineScope,
        embeddings: EmbeddingClient,
        logs: MutableList<String>,
        memoryId: String,
        provider: AIProvider = RemoteAIProvider(
            FakeBackend { payload ->
                BackendExchange.Response(200, successBody(payload.requestId, answer, "response-remote"))
            },
            newId = { "unused-local-id" },
        ),
    ): AIService {
        return AIService(
            provider = provider,
            memoryStore = store,
            newId = { memoryId },
            now = { instant },
            embeddings = embeddings,
            embeddingScope = scope,
            embeddingLogger = EmbeddingLogger { logs += it },
        )
    }

    private fun join(scope: CoroutineScope) {
        runBlocking {
            scope.coroutineContext.job.children.toList().forEach { it.join() }
        }
    }

    private fun <T> withScope(block: (CoroutineScope) -> T): T {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            return block(scope)
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun sampleVector(first: Double): List<Double> {
        return List(EMBEDDING_DIMENSIONS) { index -> if (index == 0) first else 2.0 }
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

    private fun ready(manager: ConversationSessionManager, selectedText: String): AIRequest {
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
        requestId: String,
        selectedText: String = this.selected,
        preceding: String? = this.preceding,
        following: String? = this.following,
    ): AIRequest {
        val attempt = AIRequestFactory.create(
            packageWith(selectedText, ContextQuality.HIGH, preceding, following, sourcePackage),
            UserAction.EXPLAIN,
            sessionId = { sessionId },
            newId = { requestId },
            now = { instant },
        )
        return (attempt as AIRequestAttempt.Ready).request
    }

    private fun packageWith(
        selectedText: String?,
        quality: ContextQuality,
        preceding: String?,
        following: String?,
        sourcePackage: String?,
    ) = ContextPackage(
        selectedText = selectedText,
        precedingContext = preceding,
        followingContext = following,
        sourcePackage = sourcePackage,
        captureMethod = CaptureMethod.Accessibility,
        captureStatus = CaptureStatus.SUCCESS,
        contextQuality = quality,
    )

    private fun stored(
        memoryId: String,
        sessionId: String,
        selectedText: String = "selected-$memoryId",
        precedingContext: String? = "pre-$memoryId",
        followingContext: String? = "post-$memoryId",
        generatedResponse: String = "generated-$memoryId",
        requestId: String = "request-$memoryId",
        responseId: String = "response-$memoryId",
        embedding: List<Double>? = null,
        embeddingStatus: EmbeddingStatus? = null,
    ) = InteractionMemory(
        memoryId = memoryId,
        sessionId = sessionId,
        requestId = requestId,
        responseId = responseId,
        userAction = UserAction.EXPLAIN,
        selectedText = selectedText,
        precedingContext = precedingContext,
        followingContext = followingContext,
        generatedResponse = generatedResponse,
        sourcePackage = sourcePackage,
        createdAt = instant,
        embedding = embedding,
        embeddingStatus = embeddingStatus,
    )

    private fun successBody(requestId: String, content: String, responseId: String): String {
        return buildString {
            append('{')
            append("\"requestId\":").append(jsonString(requestId))
            append(",\"responseId\":").append(jsonString(responseId))
            append(",\"content\":").append(jsonString(content))
            append(",\"provider\":").append(jsonString("OpenAI"))
            append(",\"model\":").append(jsonString("gpt-4o-mini"))
            append(",\"status\":").append(jsonString("SUCCESS"))
            append('}')
        }
    }

    private class FakeEmbeddings(
        private val handler: (String) -> EmbeddingResult,
    ) : EmbeddingClient {
        val texts = mutableListOf<String>()

        override suspend fun embed(text: String): EmbeddingResult {
            texts += text
            return handler(text)
        }
    }

    private class FakeBackend(
        private val handler: (BackendRequestPayload) -> BackendExchange,
    ) : AIBackendClient {
        override suspend fun exchange(payload: BackendRequestPayload): BackendExchange = handler(payload)
    }
}
