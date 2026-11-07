package dev.probe.textselection

import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.ResponseStatus
import dev.probe.textselection.interaction.UserAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseBodySelectionTest {
    @Test
    fun keyUsesRequestIdAndResponseId() {
        val key = responseBodySelectionKey(response(requestId = "req-1", responseId = "res-1"))

        assertEquals("req-1", key.requestId)
        assertEquals("res-1", key.responseId)
    }

    @Test
    fun sameIdsKeepTheSelectionKey() {
        val first = responseBodySelectionKey(response(requestId = "req-1", responseId = "res-1"))
        val again = responseBodySelectionKey(
            response(
                requestId = "req-1",
                responseId = "res-1",
                content = "A different body under the same ids.",
            ),
        )

        assertEquals(first, again)
    }

    @Test
    fun newResponseIdClearsTheSelectionKey() {
        val shown = responseBodySelectionKey(response(requestId = "req-1", responseId = "res-1"))
        val next = responseBodySelectionKey(response(requestId = "req-1", responseId = "res-2"))

        assertNotEquals(shown, next)
    }

    @Test
    fun toolbarActionsAreExplainExampleAndFollowUp() {
        assertEquals(
            listOf("Explain", "Give Example", "Ask Follow-up"),
            responseSelectionToolbarActions.map { it.label },
        )
        assertEquals(UserAction.entries, responseSelectionToolbarActions)
    }

    @Test
    fun selectedSubstringIsTheSelectionStateSlice() {
        assertEquals("retrieves relevant", selectedResponseSubstring(listOf("retrieves relevant")))
    }

    @Test
    fun selectedSubstringKeepsTheExactCharacters() {
        assertEquals("  retrieves relevant", selectedResponseSubstring(listOf("  retrieves relevant")))
    }

    @Test
    fun selectedSubstringConcatenatesSlicesWithoutSearching() {
        assertEquals(
            "alpha beta",
            selectedResponseSubstring(listOf("alpha", " beta")),
        )
    }

    @Test
    fun blankSelectionDoesNotOpenAnAction() {
        assertNull(selectedResponseSubstring(emptyList()))
        assertNull(selectedResponseSubstring(listOf("")))
        assertNull(selectedResponseSubstring(listOf("   ")))
        assertNull(selectedResponseSubstring(listOf("\n\t ")))
    }

    @Test
    fun newRequestIdClearsTheSelectionKey() {
        val shown = responseBodySelectionKey(response(requestId = "req-1", responseId = "res-1"))
        val next = responseBodySelectionKey(response(requestId = "req-2", responseId = "res-1"))

        assertNotEquals(shown, next)
    }

    private fun response(
        requestId: String,
        responseId: String,
        content: String = "The response body.",
    ): AIResponse {
        return AIResponse(
            responseId = responseId,
            requestId = requestId,
            sessionId = "session-1",
            action = UserAction.EXPLAIN,
            content = content,
            provider = "mock",
            model = "mock-model",
            status = ResponseStatus.SUCCESS,
        )
    }
}
