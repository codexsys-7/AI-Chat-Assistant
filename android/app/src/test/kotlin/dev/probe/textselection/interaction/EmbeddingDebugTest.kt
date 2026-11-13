package dev.probe.textselection.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EmbeddingDebugTest {
    private val instant = Instant.parse("2026-11-12T00:00:00Z")

    @Test
    fun missingMemoryAndNullStatusAreUnavailable() {
        val missing = embeddingDebug(null)
        assertEquals(EMBEDDING_DEBUG_UNAVAILABLE, missing.status)
        assertNull(missing.preview)
        assertNull(missing.dimensions)

        val mock = embeddingDebug(memory(status = null, embedding = null))
        assertEquals(EMBEDDING_DEBUG_UNAVAILABLE, mock.status)
        assertNull(mock.preview)
        assertNull(mock.dimensions)
    }

    @Test
    fun pendingAndFailedShowStatusWithoutAPreviewOrDimensions() {
        val pending = embeddingDebug(memory(status = EmbeddingStatus.PENDING, embedding = null))
        assertEquals("PENDING", pending.status)
        assertNull(pending.preview)
        assertNull(pending.dimensions)

        val failed = embeddingDebug(memory(status = EmbeddingStatus.FAILED, embedding = null))
        assertEquals("FAILED", failed.status)
        assertNull(failed.preview)
        assertNull(failed.dimensions)
    }

    @Test
    fun completedPreviewShowsOnlyTheFirstEightNumbers() {
        val vector = List(1536) { index -> (index + 1).toDouble() }
        val debug = embeddingDebug(memory(status = EmbeddingStatus.COMPLETED, embedding = vector))
        val preview = debug.preview

        assertEquals("COMPLETED", debug.status)
        assertEquals("1536", debug.dimensions)
        assertEquals(
            "First 8 of 1536 values (preview, not the full vector): 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0",
            preview,
        )
        assertTrue(preview!!.contains("preview, not the full vector"))
        assertFalse(preview.contains("9.0"))
        assertFalse(preview.contains("1536.0"))
        assertEquals(8, preview.substringAfter(": ").split(", ").size)
    }

    @Test
    fun dimensionsAppearOnlyWhenAVectorIsPresent() {
        val withVector = embeddingDebug(
            memory(status = EmbeddingStatus.FAILED, embedding = listOf(1.0, 2.0)),
        )
        assertEquals("FAILED", withVector.status)
        assertNull(withVector.preview)
        assertEquals("2", withVector.dimensions)

        val empty = embeddingDebug(
            memory(status = EmbeddingStatus.COMPLETED, embedding = emptyList()),
        )
        assertEquals("COMPLETED", empty.status)
        assertNull(empty.preview)
        assertNull(empty.dimensions)
    }

    @Test
    fun shortVectorPreviewDoesNotInventExtraNumbers() {
        val preview = embeddingPreview(listOf(4.5, 5.5))
        assertEquals(
            "First 2 of 2 values (preview, not the full vector): 4.5, 5.5",
            preview,
        )
    }

    @Test
    fun lookupStaysInsideTheRequestedSession() {
        val store = InteractionMemoryStore()
        store.save(memory(sessionId = "session-a", requestId = "request-a", status = EmbeddingStatus.COMPLETED, embedding = listOf(1.0)))
        store.save(
            memory(
                memoryId = "memory-b",
                sessionId = "session-b",
                requestId = "request-b",
                status = EmbeddingStatus.COMPLETED,
                embedding = List(9) { 20.0 + it },
            ),
        )

        val own = embeddingDebug(memoryForRequest(store, "session-a", "request-a"))
        assertEquals("COMPLETED", own.status)
        assertEquals("1", own.dimensions)
        assertEquals(
            "First 1 of 1 values (preview, not the full vector): 1.0",
            own.preview,
        )

        val other = embeddingDebug(memoryForRequest(store, "session-a", "request-b"))
        assertEquals(EMBEDDING_DEBUG_UNAVAILABLE, other.status)
        assertNull(other.preview)
        assertNull(other.dimensions)
        assertNull(memoryForRequest(store, "session-a", "request-b"))
        assertEquals("request-b", memoryForRequest(store, "session-b", "request-b")?.requestId)
        assertNull(memoryForRequest(store, null, "request-a"))
        assertNull(memoryForRequest(store, "session-a", " "))

        store.clear("session-a")
        assertNull(memoryForRequest(store, "session-a", "request-a"))
        assertEquals("request-b", memoryForRequest(store, "session-b", "request-b")?.requestId)
    }

    private fun memory(
        memoryId: String = "memory-1",
        sessionId: String = "session-a",
        requestId: String = "request-1",
        status: EmbeddingStatus?,
        embedding: List<Double>?,
    ) = InteractionMemory(
        memoryId = memoryId,
        sessionId = sessionId,
        requestId = requestId,
        responseId = "response-1",
        userAction = UserAction.EXPLAIN,
        selectedText = "Cells store fuel.",
        precedingContext = "Before.",
        followingContext = "After.",
        generatedResponse = "A mitochondrion makes ATP.",
        sourcePackage = "dev.probe.samplechat",
        createdAt = instant,
        embedding = embedding,
        embeddingStatus = status,
    )
}
