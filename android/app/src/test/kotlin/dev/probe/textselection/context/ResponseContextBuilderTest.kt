package dev.probe.textselection.context

import dev.probe.textselection.engine.ContextQuality
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.ResponseStatus
import dev.probe.textselection.interaction.UserAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseContextBuilderTest {
    private val before =
        "Retrieval augmented generation combines language models with external knowledge sources."
    private val selected =
        "The system first retrieves relevant documents and then provides those documents to the model as context."
    private val after =
        "This can help the model answer questions using information that was not contained in its original training data."
    private val laterParagraph =
        "A later paragraph stays outside following context for this response."

    private val providerToken = "ProviderTokenZZ"
    private val modelToken = "ModelTokenZZ"
    private val requestId = "request-id-token-zz"
    private val responseId = "response-id-token-zz"
    private val sessionId = "session-id-token-zz"

    @Test
    fun middleSentenceKeepsSameLineNeighborsOnly() {
        val result = ResponseContextBuilder.build(response(threeSentences()), selected)

        assertEquals(selected, result.selectedText)
        assertEquals(before, result.precedingContext)
        assertEquals(after, result.followingContext)
        assertEquals(ResponseContextBuilder.SOURCE_PACKAGE, result.sourcePackage)
        assertEquals(CaptureMethod.SelectedText, result.captureMethod)
        assertEquals(CaptureStatus.SUCCESS, result.captureStatus)
        assertEquals(ContextQuality.HIGH, result.contextQuality)
        assertFalse(packageText(result).contains(laterParagraph))
    }

    @Test
    fun metadataLabelsNeverEnterThePackage() {
        val result = ResponseContextBuilder.build(response(threeSentences()), selected)
        val text = packageText(result)

        assertFalse(text.contains("MOCK"))
        assertFalse(text.contains("Request debug"))
        assertFalse(text.contains("Provider"))
        assertFalse(text.contains("Model"))
        assertFalse(text.contains("Request id"))
        assertFalse(text.contains("Response id"))
        assertFalse(text.contains("Status"))
        assertFalse(text.contains(providerToken))
        assertFalse(text.contains(modelToken))
        assertFalse(text.contains(requestId))
        assertFalse(text.contains(responseId))
        assertFalse(text.contains(sessionId))
        assertFalse(text.contains(UserAction.EXPLAIN.label))
        assertFalse(text.contains(ResponseStatus.SUCCESS.name))
        assertEquals(selected, result.selectedText)
    }

    @Test
    fun sameSentenceInSnapshotUsesResponseContentOnly() {
        val snapshotBefore = "External snapshot preceding sentence about glaciers."
        val snapshotAfter = "External snapshot following sentence about volcanoes."
        ContextSnapshotStore.record(
            VisibleWindowSnapshot(
                sourcePackage = "com.example.other",
                visibleText = "$snapshotBefore $selected $snapshotAfter",
                nodeCount = 3,
            ),
        )

        val result = ResponseContextBuilder.build(response(threeSentences()), selected)

        assertEquals(before, result.precedingContext)
        assertEquals(after, result.followingContext)
        assertEquals(ResponseContextBuilder.SOURCE_PACKAGE, result.sourcePackage)
        assertEquals(CaptureMethod.SelectedText, result.captureMethod)
        val text = packageText(result)
        assertFalse(text.contains("glaciers"))
        assertFalse(text.contains("volcanoes"))
        assertFalse(text.contains("com.example.other"))
        val stored = ContextSnapshotStore.latest()
        assertEquals("com.example.other", stored?.sourcePackage)
        assertEquals("$snapshotBefore $selected $snapshotAfter", stored?.visibleText)
    }

    @Test
    fun firstSentenceHasNullPreceding() {
        val result = ResponseContextBuilder.build(response(threeSentences()), before)

        assertEquals(before, result.selectedText)
        assertNull(result.precedingContext)
        assertEquals("$selected $after", result.followingContext)
        assertFalse(packageText(result).contains(laterParagraph))
        assertEquals(CaptureStatus.PARTIAL, result.captureStatus)
        assertEquals(ContextQuality.MEDIUM, result.contextQuality)
    }

    @Test
    fun lastSentenceHasNullFollowing() {
        val result = ResponseContextBuilder.build(response(threeSentences()), after)

        assertEquals(after, result.selectedText)
        assertEquals("$before $selected", result.precedingContext)
        assertNull(result.followingContext)
        assertFalse(packageText(result).contains(laterParagraph))
        assertEquals(CaptureStatus.PARTIAL, result.captureStatus)
        assertEquals(ContextQuality.MEDIUM, result.contextQuality)
    }

    @Test
    fun selectionMissingFromContentDoesNotFabricateContext() {
        val missing = "Completely missing sentence about zebras."
        ContextSnapshotStore.record(
            VisibleWindowSnapshot(
                sourcePackage = "com.example.other",
                visibleText = "Snapshot neighbor before zebras. $missing Snapshot neighbor after zebras.",
                nodeCount = 2,
            ),
        )

        val result = ResponseContextBuilder.build(response(threeSentences()), missing)

        assertEquals(missing, result.selectedText)
        assertNull(result.precedingContext)
        assertNull(result.followingContext)
        assertNull(result.sourcePackage)
        assertEquals(CaptureMethod.SelectedText, result.captureMethod)
        assertEquals(CaptureStatus.FAILED, result.captureStatus)
        assertEquals(ContextQuality.UNAVAILABLE, result.contextQuality)
        val text = packageText(result)
        assertFalse(text.contains(before))
        assertFalse(text.contains(selected))
        assertFalse(text.contains(after))
        assertFalse(text.contains("Snapshot neighbor"))
    }

    @Test
    fun partialSentenceIsPreservedExactly() {
        val partial = "retrieves relevant documents"
        val result = ResponseContextBuilder.build(response(threeSentences()), partial)

        assertEquals(partial, result.selectedText)
        assertFalse(result.selectedText == selected)
        assertEquals(before, result.precedingContext)
        assertEquals(after, result.followingContext)
        assertFalse(result.precedingContext!!.contains(partial))
        assertFalse(result.followingContext!!.contains(partial))
        assertEquals(ContextQuality.LOW, result.contextQuality)
    }

    @Test
    fun duplicateSubstringDoesNotTakeEitherOccurrencesNeighbors() {
        val shared = selected
        val alphaBefore = "Alpha occurrence is preceded by glaciers and fjords."
        val alphaAfter = "Alpha occurrence is followed by volcanoes and basalt."
        val betaBefore = "Beta occurrence is preceded by orchards and rainfall."
        val betaAfter = "Beta occurrence is followed by canyons and sandstone."
        val content = "$alphaBefore $shared $alphaAfter $betaBefore $shared $betaAfter"

        val result = ResponseContextBuilder.build(response(content), shared)

        assertEquals(shared, result.selectedText)
        assertNull(result.precedingContext)
        assertNull(result.followingContext)
        assertEquals(CaptureStatus.FAILED, result.captureStatus)
        assertEquals(ContextQuality.UNAVAILABLE, result.contextQuality)
        assertEquals(CaptureMethod.SelectedText, result.captureMethod)
        val text = packageText(result)
        assertFalse(text.contains("glaciers"))
        assertFalse(text.contains("volcanoes"))
        assertFalse(text.contains("orchards"))
        assertFalse(text.contains("canyons"))
        assertFalse(text.contains("Alpha"))
        assertFalse(text.contains("Beta"))
    }

    @Test
    fun emptyAndWhitespaceOnlySelectionsStayEmpty() {
        val content = threeSentences()
        for (selection in listOf("", "   ", "\n\t ")) {
            val result = ResponseContextBuilder.build(response(content), selection)
            assertNull(result.selectedText)
            assertNull(result.precedingContext)
            assertNull(result.followingContext)
            assertNull(result.sourcePackage)
            assertEquals(CaptureStatus.FAILED, result.captureStatus)
            assertEquals(ContextQuality.UNAVAILABLE, result.contextQuality)
            assertEquals(CaptureMethod.SelectedText, result.captureMethod)
            assertFalse(packageText(result).contains(before))
        }
    }

    private fun threeSentences(): String = "$before $selected $after\n$laterParagraph"

    private fun response(content: String) = AIResponse(
        responseId = responseId,
        requestId = requestId,
        sessionId = sessionId,
        action = UserAction.EXPLAIN,
        content = content,
        provider = providerToken,
        model = modelToken,
        status = ResponseStatus.SUCCESS,
    )

    private fun packageText(result: dev.probe.textselection.engine.ContextPackage): String {
        return listOfNotNull(
            result.selectedText,
            result.precedingContext,
            result.followingContext,
            result.sourcePackage,
        ).joinToString("\n")
    }
}
