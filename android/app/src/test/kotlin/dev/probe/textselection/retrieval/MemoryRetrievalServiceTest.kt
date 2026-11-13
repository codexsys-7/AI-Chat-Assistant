package dev.probe.textselection.retrieval

import dev.probe.textselection.ai.EMBEDDING_DIMENSIONS
import dev.probe.textselection.ai.EmbeddingClient
import dev.probe.textselection.ai.EmbeddingResult
import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.EmbeddingStatus
import dev.probe.textselection.interaction.InteractionMemory
import dev.probe.textselection.interaction.InteractionMemoryStore
import dev.probe.textselection.interaction.UserAction
import dev.probe.textselection.interaction.memoryPassage
import dev.probe.textselection.interaction.queryPassage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import kotlin.math.sqrt

class MemoryRetrievalServiceTest {
    private val instant = Instant.parse("2026-11-12T00:00:00Z")
    private val selected = "Cells store fuel before they can spend it."
    private val preceding = "A cell still has to turn stored fuel into usable energy."
    private val following = "That next step is what the mitochondria sentence describes."
    private val sourcePackage = "pkg-SHOULD-NOT-LEAK"

    @Test
    fun defaultsAreInjectable() {
        val defaults = RetrievalConfig()
        assertEquals(3, defaults.topK)
        assertEquals(3, RetrievalConfig.DEFAULT_TOP_K)
        assertEquals(0.75, defaults.threshold, 0.0)
        assertEquals(0.75, RetrievalConfig.DEFAULT_THRESHOLD, 0.0)
        assertEquals(1536, defaults.dimensions)
        assertEquals(EMBEDDING_DIMENSIONS, defaults.dimensions)
    }

    @Test
    fun queryPassageMatchesMemoryLabelsAndOmitsTheResponse() {
        val memory = memory(id = "memory-passage", response = "A mitochondrion makes ATP.")
        val query = queryPassage(memory.selectedText, memory.precedingContext, memory.followingContext)
        assertEquals(
            memoryPassage(memory).removeSuffix("\nGenerated response: ${memory.generatedResponse}"),
            query,
        )
        assertFalse(query.lineSequence().any { it.startsWith("Generated response:") })
        assertFalse(query.contains(memory.memoryId))
        assertFalse(query.contains(memory.sessionId))
        assertFalse(query.contains(memory.requestId))
        assertFalse(query.contains(memory.responseId))
        assertFalse(query.contains(sourcePackage))
        assertFalse(query.contains("EXPLAIN"))
        assertFalse(query.contains("text-embedding"))
        assertFalse(query.contains("debug"))

        val selectedOnly = queryPassage(selected, null, null)
        assertEquals("Selected text: $selected", selectedOnly)
        assertFalse(selectedOnly.contains("Preceding context:"))
        assertFalse(selectedOnly.contains("Following context:"))
        assertFalse(selectedOnly.contains("Generated response:"))
    }

    @Test
    fun relevantMemoryIsRetrieved() {
        val store = InteractionMemoryStore()
        val match = memory(id = "memory-relevant", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED)
        store.save(match)
        val client = clientReturning(vector(1.0))
        val result = retrieve(store, client)

        assertEquals(listOf("memory-relevant"), result.sourceMemoryIds)
        assertEquals(1.0, result.scores.single(), 0.0)
        assertSame(match, result.memories.single())
        assertEquals(queryPassage(selected, preceding, following), client.texts.single())
        assertFalse(client.texts.single().contains("Generated response:"))
        assertEquals(1, client.calls)
    }

    @Test
    fun unrelatedMemoryIsDropped() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-unrelated", embedding = vector(0.0, 1.0), status = EmbeddingStatus.COMPLETED))
        val result = retrieve(store, clientReturning(vector(1.0)))
        assertEquals(RetrievedMemoryContext.EMPTY, result)
    }

    @Test
    fun resultsSortByScoreDescending() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-low", text = "low", response = "low-answer", embedding = vector(2.0, 1.0), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-high", text = "high", response = "high-answer", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-mid", text = "mid", response = "mid-answer", embedding = vector(3.0, 1.0), status = EmbeddingStatus.COMPLETED))
        val result = retrieve(store, clientReturning(vector(1.0)))
        assertEquals(listOf("memory-high", "memory-mid", "memory-low"), result.sourceMemoryIds)
        assertTrue(result.scores[0] > result.scores[1])
        assertTrue(result.scores[1] > result.scores[2])
        assertEquals(1.0, result.scores[0], 1e-9)
        assertEquals(3.0 / sqrt(10.0), result.scores[1], 1e-6)
        assertEquals(2.0 / sqrt(5.0), result.scores[2], 1e-6)
    }

    @Test
    fun topKKeepsTheHighestScores() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-4", text = "four", response = "four-answer", embedding = vector(4.0, 3.0), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-1", text = "one", response = "one-answer", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-3", text = "three", response = "three-answer", embedding = vector(3.0, 1.0), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-2", text = "two", response = "two-answer", embedding = vector(2.0, 1.0), status = EmbeddingStatus.COMPLETED))
        val result = retrieve(store, clientReturning(vector(1.0)))
        assertEquals(3, RetrievalConfig().topK)
        assertEquals(listOf("memory-1", "memory-3", "memory-2"), result.sourceMemoryIds)
        assertFalse(result.sourceMemoryIds.contains("memory-4"))

        val onlyBest = retrieve(
            store,
            clientReturning(vector(1.0)),
            config = RetrievalConfig(topK = 1),
        )
        assertEquals(listOf("memory-1"), onlyBest.sourceMemoryIds)
    }

    @Test
    fun scoresBelowTheThresholdAreDropped() {
        val store = InteractionMemoryStore()
        val kept = memory(id = "memory-above", text = "above", response = "above-answer", embedding = vector(3.0, 1.0), status = EmbeddingStatus.COMPLETED)
        val dropped = memory(id = "memory-below", text = "below", response = "below-answer", embedding = vector(1.0, 1.0), status = EmbeddingStatus.COMPLETED)
        store.save(dropped)
        store.save(kept)
        val result = retrieve(store, clientReturning(vector(1.0)))
        assertEquals(listOf("memory-above"), result.sourceMemoryIds)
        assertTrue(result.scores.single() >= RetrievalConfig.DEFAULT_THRESHOLD)
        assertTrue(1.0 / sqrt(2.0) < RetrievalConfig.DEFAULT_THRESHOLD)

        val atScore = cosineSimilarity(floatArrayOf(1f, 1f), floatArrayOf(1f, 0f))!!
        val included = retrieve(
            store,
            clientReturning(vector(1.0)),
            config = RetrievalConfig(threshold = atScore),
        )
        assertEquals(listOf("memory-above", "memory-below"), included.sourceMemoryIds)
        val excluded = retrieve(
            store,
            clientReturning(vector(1.0)),
            config = RetrievalConfig(threshold = atScore + 1e-4),
        )
        assertEquals(listOf("memory-above"), excluded.sourceMemoryIds)
    }

    @Test
    fun memoriesWithoutAnEmbeddingAreDropped() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-none", embedding = null, status = null))
        store.save(memory(id = "memory-pending", embedding = null, status = EmbeddingStatus.PENDING))
        store.save(memory(id = "memory-failed", embedding = null, status = EmbeddingStatus.FAILED))
        store.save(memory(id = "memory-completed-empty", embedding = null, status = EmbeddingStatus.COMPLETED))
        val result = retrieve(store, clientReturning(vector(1.0)))
        assertEquals(RetrievedMemoryContext.EMPTY, result)
        assertEquals(4, store.list("session-a").size)
    }

    @Test
    fun invalidEmbeddingsAreDropped() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-short", embedding = listOf(1.0, 0.0), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-nan", embedding = vector(Double.NaN), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-infinite", embedding = vector(Double.POSITIVE_INFINITY), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-empty", embedding = emptyList(), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-zero", embedding = vector(0.0), status = EmbeddingStatus.COMPLETED))
        store.save(memory(id = "memory-pending-vector", embedding = vector(1.0), status = EmbeddingStatus.PENDING))
        store.save(memory(id = "memory-failed-vector", embedding = vector(1.0), status = EmbeddingStatus.FAILED))
        store.save(memory(id = "memory-valid", text = "valid", response = "valid-answer", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED))
        val result = retrieve(store, clientReturning(vector(1.0)))
        assertEquals(listOf("memory-valid"), result.sourceMemoryIds)
    }

    @Test
    fun emptyStoreReturnsEmpty() {
        val store = InteractionMemoryStore()
        val client = clientReturning(vector(1.0))
        val result = retrieve(store, client)
        assertEquals(RetrievedMemoryContext.EMPTY, result)
        assertEquals(1, client.calls)
        assertTrue(store.list("session-a").isEmpty())
    }

    @Test
    fun duplicateTextKeepsTheHigherScoreAndItsIds() {
        val store = InteractionMemoryStore()
        store.save(
            memory(
                id = "memory-weaker",
                requestId = "request-weaker",
                responseId = "response-weaker",
                text = "  Cells   store fuel. ",
                response = " ATP. ",
                embedding = vector(3.0, 1.0),
                status = EmbeddingStatus.COMPLETED,
            ),
        )
        store.save(
            memory(
                id = "memory-stronger",
                requestId = "request-stronger",
                responseId = "response-stronger",
                text = "Cells store fuel.",
                response = "ATP.",
                embedding = vector(1.0),
                status = EmbeddingStatus.COMPLETED,
            ),
        )
        store.save(
            memory(
                id = "memory-different",
                requestId = "request-different",
                responseId = "response-different",
                text = "A different selected sentence.",
                response = "A different answer.",
                embedding = vector(4.0, 1.0),
                status = EmbeddingStatus.COMPLETED,
            ),
        )
        val result = retrieve(store, clientReturning(vector(1.0)))
        assertEquals(listOf("memory-stronger", "memory-different"), result.sourceMemoryIds)
        assertEquals(listOf("request-stronger", "request-different"), result.requestIds)
        assertEquals(listOf("response-stronger", "response-different"), result.responseIds)
        assertFalse(result.sourceMemoryIds.contains("memory-weaker"))
        assertEquals("Cells store fuel.", result.memories[0].selectedText)
        assertEquals("ATP.", result.memories[0].generatedResponse)
    }

    @Test
    fun sourceIdsComeFromTheKeptMemory() {
        val store = InteractionMemoryStore()
        val kept = memory(
            id = "memory-source",
            requestId = "request-source",
            responseId = "response-source",
            embedding = vector(1.0),
            status = EmbeddingStatus.COMPLETED,
        )
        store.save(kept)
        val result = retrieve(store, clientReturning(vector(1.0)))
        assertEquals(listOf(kept.memoryId), result.sourceMemoryIds)
        assertEquals(listOf(kept.requestId), result.requestIds)
        assertEquals(listOf(kept.responseId), result.responseIds)
        assertEquals(kept.memoryId, result.memories.single().memoryId)
    }

    @Test
    fun otherSessionIsInvisible() {
        val store = InteractionMemoryStore()
        val own = memory(
            id = "memory-own",
            sessionId = "session-a",
            text = "own selection",
            response = "own answer",
            embedding = vector(3.0, 1.0),
            status = EmbeddingStatus.COMPLETED,
        )
        val other = memory(
            id = "memory-other",
            sessionId = "session-b",
            text = "other selection",
            response = "other answer",
            embedding = vector(1.0),
            status = EmbeddingStatus.COMPLETED,
        )
        store.save(own)
        store.save(other)
        val result = retrieve(store, clientReturning(vector(1.0)), sessionId = "session-a")
        assertEquals(listOf("memory-own"), result.sourceMemoryIds)
        assertFalse(result.sourceMemoryIds.contains("memory-other"))
        assertSame(other, store.list("session-b").single())
        val otherResult = retrieve(store, clientReturning(vector(1.0)), sessionId = "session-b")
        assertEquals(listOf("memory-other"), otherResult.sourceMemoryIds)
    }

    @Test
    fun excludedResponseIsDroppedAndAMissingExclusionRemovesNothing() {
        val store = InteractionMemoryStore()
        val current = memory(
            id = "memory-current",
            responseId = "response-current",
            text = "current selection",
            response = "current answer",
            embedding = vector(1.0),
            status = EmbeddingStatus.COMPLETED,
        )
        val earlier = memory(
            id = "memory-earlier",
            responseId = "response-earlier",
            text = "earlier selection",
            response = "earlier answer",
            embedding = vector(3.0, 1.0),
            status = EmbeddingStatus.COMPLETED,
        )
        store.save(earlier)
        store.save(current)
        val excluded = retrieve(store, clientReturning(vector(1.0)), excludedResponseId = "response-current")
        assertEquals(listOf("memory-earlier"), excluded.sourceMemoryIds)
        val included = retrieve(store, clientReturning(vector(1.0)), excludedResponseId = null)
        assertEquals(listOf("memory-current", "memory-earlier"), included.sourceMemoryIds)
    }

    @Test
    fun duplicateSurvivesWhenTheHigherCopyIsTheExcludedResponse() {
        val store = InteractionMemoryStore()
        store.save(
            memory(
                id = "memory-lower-copy",
                responseId = "response-lower",
                text = "Same selection",
                response = "Same answer",
                embedding = vector(3.0, 1.0),
                status = EmbeddingStatus.COMPLETED,
            ),
        )
        store.save(
            memory(
                id = "memory-higher-copy",
                responseId = "response-current",
                text = "Same selection",
                response = "Same answer",
                embedding = vector(1.0),
                status = EmbeddingStatus.COMPLETED,
            ),
        )
        val result = retrieve(store, clientReturning(vector(1.0)), excludedResponseId = "response-current")
        assertEquals(listOf("memory-lower-copy"), result.sourceMemoryIds)
        assertEquals(listOf("response-lower"), result.responseIds)
    }

    @Test
    fun missingPrecedingOmitsThatLine() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-match", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED))
        val client = clientReturning(vector(1.0))
        val context = context(selectedText = selected, preceding = null, following = following)
        val result = retrieve(store, client, context = context)
        assertEquals(queryPassage(selected, null, following), client.texts.single())
        assertFalse(client.texts.single().contains("Preceding context:"))
        assertTrue(client.texts.single().contains("Following context: $following"))
        assertEquals(listOf("memory-match"), result.sourceMemoryIds)
    }

    @Test
    fun missingFollowingOmitsThatLine() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-match", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED))
        val client = clientReturning(vector(1.0))
        val context = context(selectedText = selected, preceding = preceding, following = null)
        val result = retrieve(store, client, context = context)
        assertEquals(queryPassage(selected, preceding, null), client.texts.single())
        assertTrue(client.texts.single().contains("Preceding context: $preceding"))
        assertFalse(client.texts.single().contains("Following context:"))
        assertEquals(listOf("memory-match"), result.sourceMemoryIds)
    }

    @Test
    fun queryEmbeddingFailureReturnsEmptyWithoutRetry() {
        val store = InteractionMemoryStore()
        val saved = memory(id = "memory-kept", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED)
        store.save(saved)
        val failing = clientReturning(vector(1.0), EmbeddingResult.NetworkFailure)
        val failed = retrieve(store, failing)
        assertEquals(RetrievedMemoryContext.EMPTY, failed)
        assertEquals(1, failing.calls)

        val throwing = clientReturning(vector(1.0)) { throw IllegalStateException("backend down") }
        val thrown = retrieve(store, throwing)
        assertEquals(RetrievedMemoryContext.EMPTY, thrown)
        assertEquals(1, throwing.calls)
        assertSame(saved, store.list("session-a").single())
    }

    @Test
    fun invalidQueryVectorReturnsEmpty() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-kept", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED))
        val cases = listOf(
            EmbeddingResult.EmptyVector,
            EmbeddingResult.WrongDimension,
            EmbeddingResult.Invalid,
            EmbeddingResult.TimedOut,
            EmbeddingResult.BackendError,
            EmbeddingResult.Success(vector(1.0), "text-embedding-3-small", 3),
            EmbeddingResult.Success(vector(Double.NaN), "text-embedding-3-small", EMBEDDING_DIMENSIONS),
            EmbeddingResult.Success(vector(0.0), "text-embedding-3-small", EMBEDDING_DIMENSIONS),
            EmbeddingResult.Success(emptyList(), "text-embedding-3-small", EMBEDDING_DIMENSIONS),
        )
        for (result in cases) {
            val client = clientReturning(vector(1.0), result)
            assertEquals(RetrievedMemoryContext.EMPTY, retrieve(store, client))
            assertEquals(1, client.calls)
        }
    }

    @Test
    fun retrievalDoesNotChangeTheStore() {
        val store = InteractionMemoryStore()
        val current = memory(id = "memory-current", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED)
        val pending = memory(id = "memory-pending", embedding = null, status = EmbeddingStatus.PENDING)
        val other = memory(
            id = "memory-other",
            sessionId = "session-b",
            embedding = vector(1.0),
            status = EmbeddingStatus.COMPLETED,
        )
        store.save(current)
        store.save(pending)
        store.save(other)
        val beforeA = store.list("session-a")
        val beforeB = store.list("session-b")
        retrieve(store, clientReturning(vector(1.0)), excludedResponseId = "response-current")
        val afterA = store.list("session-a")
        val afterB = store.list("session-b")
        assertEquals(2, afterA.size)
        assertSame(beforeA[0], afterA[0])
        assertSame(beforeA[1], afterA[1])
        assertSame(beforeB.single(), afterB.single())
        assertEquals(vector(1.0), afterA[0].embedding)
        assertEquals(EmbeddingStatus.COMPLETED, afterA[0].embeddingStatus)
        assertNull(afterA[1].embedding)
        assertEquals(EmbeddingStatus.PENDING, afterA[1].embeddingStatus)
    }

    @Test
    fun badSessionReturnsEmpty() {
        val store = InteractionMemoryStore()
        val saved = memory(id = "memory-own", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED)
        store.save(saved)
        assertEquals(RetrievedMemoryContext.EMPTY, retrieve(store, clientReturning(vector(1.0)), sessionId = " "))
        assertEquals(RetrievedMemoryContext.EMPTY, retrieve(store, clientReturning(vector(1.0)), sessionId = "session-missing"))
        assertSame(saved, store.list("session-a").single())
    }

    @Test
    fun nullSelectionDoesNotEmbed() {
        val store = InteractionMemoryStore()
        store.save(memory(id = "memory-own", embedding = vector(1.0), status = EmbeddingStatus.COMPLETED))
        val client = clientReturning(vector(1.0))
        val result = retrieve(store, client, context = context(selectedText = null, preceding = preceding, following = following))
        assertEquals(RetrievedMemoryContext.EMPTY, result)
        assertEquals(0, client.calls)
    }

    @Test
    fun cancellationIsNotTreatedAsAnEmptyResult() {
        val store = InteractionMemoryStore()
        val client = object : EmbeddingClient {
            override suspend fun embed(text: String): EmbeddingResult {
                throw CancellationException("stopped")
            }
        }
        try {
            runBlocking {
                MemoryRetrievalService(store, client).retrieve(context(), "session-a")
            }
            fail("cancellation should propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("stopped", cancelled.message)
        }
        assertTrue(store.list("session-a").isEmpty())
    }

    @Test
    fun configuredDimensionsAreRequired() {
        val store = InteractionMemoryStore()
        store.save(
            memory(
                id = "memory-short",
                embedding = listOf(1.0, 0.0),
                status = EmbeddingStatus.COMPLETED,
            ),
        )
        store.save(
            memory(
                id = "memory-full",
                text = "full",
                response = "full-answer",
                embedding = vector(1.0),
                status = EmbeddingStatus.COMPLETED,
            ),
        )
        val result = retrieve(
            store,
            clientReturning(listOf(1.0, 0.0)),
            config = RetrievalConfig(dimensions = 2),
        )
        assertEquals(listOf("memory-short"), result.sourceMemoryIds)
    }

    private fun retrieve(
        store: InteractionMemoryStore,
        client: FakeEmbeddings,
        context: ContextPackage = context(),
        sessionId: String = "session-a",
        excludedResponseId: String? = null,
        config: RetrievalConfig = RetrievalConfig(),
    ): RetrievedMemoryContext {
        return runBlocking {
            MemoryRetrievalService(store, client, config).retrieve(context, sessionId, excludedResponseId)
        }
    }

    private fun clientReturning(
        vector: List<Double>,
        result: EmbeddingResult = EmbeddingResult.Success(vector, "text-embedding-3-small", vector.size),
        before: () -> Unit = {},
    ): FakeEmbeddings {
        return FakeEmbeddings {
            before()
            result
        }
    }

    private fun context(
        selectedText: String? = selected,
        preceding: String? = this.preceding,
        following: String? = this.following,
    ) = ContextPackage(
        selectedText = selectedText,
        precedingContext = preceding,
        followingContext = following,
        sourcePackage = sourcePackage,
        captureMethod = CaptureMethod.Accessibility,
        captureStatus = CaptureStatus.SUCCESS,
        contextQuality = ContextQuality.HIGH,
    )

    private fun memory(
        id: String,
        sessionId: String = "session-a",
        requestId: String = "request-$id",
        responseId: String = "response-$id",
        text: String = selected,
        precedingContext: String? = preceding,
        followingContext: String? = following,
        response: String = "A mitochondrion makes ATP.",
        embedding: List<Double>? = null,
        status: EmbeddingStatus? = null,
    ) = InteractionMemory(
        memoryId = id,
        sessionId = sessionId,
        requestId = requestId,
        responseId = responseId,
        userAction = UserAction.EXPLAIN,
        selectedText = text,
        precedingContext = precedingContext,
        followingContext = followingContext,
        generatedResponse = response,
        sourcePackage = sourcePackage,
        createdAt = instant,
        embedding = embedding,
        embeddingStatus = status,
    )

    private fun vector(first: Double, second: Double = 0.0, third: Double = 0.0): List<Double> {
        val values = ArrayList<Double>(EMBEDDING_DIMENSIONS)
        values += first
        values += second
        values += third
        while (values.size < EMBEDDING_DIMENSIONS) values += 0.0
        return values
    }

    private class FakeEmbeddings(
        private val handler: (String) -> EmbeddingResult,
    ) : EmbeddingClient {
        val texts = mutableListOf<String>()
        var calls = 0

        override suspend fun embed(text: String): EmbeddingResult {
            calls += 1
            texts += text
            return handler(text)
        }
    }
}
