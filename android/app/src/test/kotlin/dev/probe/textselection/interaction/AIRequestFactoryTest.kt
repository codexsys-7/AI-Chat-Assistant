package dev.probe.textselection.interaction

import dev.probe.textselection.context.CaptureMethod
import dev.probe.textselection.context.CaptureStatus
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.engine.ContextQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AIRequestFactoryTest {
    private val UUID_PATTERN =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val selected =
        "The system first retrieves relevant documents and then provides those documents to the model as context."
    private val preceding =
        "Retrieval augmented generation combines language models with external knowledge sources."
    private val following =
        "This can help the model answer questions using information that was not contained in its original training data."
    private val timestamp = Instant.parse("2026-10-04T00:00:00Z")

    @Test
    fun highContextCopiesBothSides() {
        val result = create(
            packageWith(ContextQuality.HIGH, preceding, following),
            UserAction.EXPLAIN,
        )

        val request = result.request()
        assertEquals(ContextQuality.HIGH, request.contextQuality)
        assertEquals(preceding, request.precedingContext)
        assertEquals(following, request.followingContext)
        assertEquals(selected, request.selectedText)
        assertEquals(CaptureMethod.Accessibility, request.captureMethod)
        assertEquals("dev.probe.samplechat", request.sourcePackage)
    }

    @Test
    fun mediumContextKeepsTheOneAvailableSide() {
        val result = create(
            packageWith(ContextQuality.MEDIUM, preceding, null),
            UserAction.EXAMPLE,
        )

        val request = result.request()
        assertEquals(ContextQuality.MEDIUM, request.contextQuality)
        assertEquals(preceding, request.precedingContext)
        assertNull(request.followingContext)
        assertEquals(UserAction.EXAMPLE, request.action)
    }

    @Test
    fun lowContextIsPreserved() {
        val result = create(
            packageWith(ContextQuality.LOW, "Hi", "No"),
            UserAction.FOLLOW_UP,
        )

        val request = result.request()
        assertEquals(ContextQuality.LOW, request.contextQuality)
        assertEquals("Hi", request.precedingContext)
        assertEquals("No", request.followingContext)
    }

    @Test
    fun unavailableContextIsNotFabricated() {
        val result = create(
            packageWith(
                quality = ContextQuality.UNAVAILABLE,
                preceding = null,
                following = null,
                method = CaptureMethod.SelectedText,
                status = CaptureStatus.FAILED,
            ),
            UserAction.EXPLAIN,
        )

        val request = result.request()
        assertEquals(ContextQuality.UNAVAILABLE, request.contextQuality)
        assertEquals(selected, request.selectedText)
        assertNull(request.precedingContext)
        assertNull(request.followingContext)
        assertEquals(CaptureMethod.SelectedText, request.captureMethod)
    }

    @Test
    fun emptySelectedTextIsRejected() {
        for (empty in listOf(null, "", "   ", "\n")) {
            val result = create(packageWith(ContextQuality.HIGH, preceding, following, selectedText = empty), UserAction.EXPLAIN)
            assertTrue(result is AIRequestAttempt.Rejected)
            assertEquals(AIRequestFactory.EMPTY_SELECTED_TEXT, (result as AIRequestAttempt.Rejected).reason)
        }
    }

    @Test
    fun missingActionIsRejected() {
        val result = AIRequestFactory.create(
            packageWith(ContextQuality.HIGH, preceding, following),
            null,
            newId = { "should-not-be-used" },
            now = { timestamp },
        )

        assertTrue(result is AIRequestAttempt.Rejected)
        assertEquals(AIRequestFactory.MISSING_ACTION, (result as AIRequestAttempt.Rejected).reason)
    }

    @Test
    fun eachActionKeepsItsIdentifierAndLabel() {
        assertEquals("Explain", ready(UserAction.EXPLAIN).action.label)
        assertEquals(UserAction.EXPLAIN, ready(UserAction.EXPLAIN).action)
        assertEquals("Give Example", ready(UserAction.EXAMPLE).action.label)
        assertEquals(UserAction.EXAMPLE, ready(UserAction.EXAMPLE).action)
        assertEquals("Ask Follow-up", ready(UserAction.FOLLOW_UP).action.label)
        assertEquals(UserAction.FOLLOW_UP, ready(UserAction.FOLLOW_UP).action)
    }

    @Test
    fun requestIdIsGeneratedAndTimestampIsRecorded() {
        val contextPackage = packageWith(ContextQuality.HIGH, preceding, following)
        val first = AIRequestFactory.create(contextPackage, UserAction.EXPLAIN, now = { timestamp }).request()
        val second = AIRequestFactory.create(contextPackage, UserAction.EXPLAIN, now = { timestamp }).request()

        assertTrue(first.requestId.matches(UUID_PATTERN))
        assertTrue(second.requestId.matches(UUID_PATTERN))
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(timestamp, first.timestamp)
        assertEquals(timestamp, second.timestamp)
    }

    @Test
    fun selectedTextAndSurroundingContextAreCopiedExactly() {
        val exactSelected = "Don't change this: it's already clear.  "
        val exactPreceding = "  keep the spaces and the word you inside.  "
        val exactFollowing = "Following stays, you included."
        val result = create(
            packageWith(
                ContextQuality.HIGH,
                exactPreceding,
                exactFollowing,
                selectedText = exactSelected,
            ),
            UserAction.EXPLAIN,
        )

        val request = result.request()
        assertEquals(exactSelected, request.selectedText)
        assertEquals(exactPreceding, request.precedingContext)
        assertEquals(exactFollowing, request.followingContext)
    }

    private fun ready(action: UserAction): AIRequest {
        return create(packageWith(ContextQuality.HIGH, preceding, following), action).request()
    }

    private fun create(contextPackage: ContextPackage, action: UserAction?): AIRequestAttempt {
        return AIRequestFactory.create(
            contextPackage,
            action,
            newId = { "id-${System.nanoTime()}" },
            now = { timestamp },
        )
    }

    private fun AIRequestAttempt.request(): AIRequest {
        return (this as AIRequestAttempt.Ready).request
    }

    private fun packageWith(
        quality: ContextQuality,
        preceding: String?,
        following: String?,
        selectedText: String? = selected,
        method: CaptureMethod = CaptureMethod.Accessibility,
        status: CaptureStatus = CaptureStatus.SUCCESS,
    ) = ContextPackage(
        selectedText = selectedText,
        precedingContext = preceding,
        followingContext = following,
        sourcePackage = "dev.probe.samplechat",
        captureMethod = method,
        captureStatus = status,
        contextQuality = quality,
    )
}
