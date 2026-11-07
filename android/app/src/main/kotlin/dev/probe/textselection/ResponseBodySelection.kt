package dev.probe.textselection

import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.UserAction

/**
 * Identity of the response whose body may be selected.
 *
 * Foundation 1.12.1 (Compose BOM 2026.09.00) keeps character offsets on an internal
 * selection type. The public selection state exposes the selected substring and
 * clear(), not a start or end index into [AIResponse.content].
 *
 * The response screen remembers its selection container with this key. A new
 * request id or response id disposes that container and drops the selection.
 * This is not a text-selection engine.
 */
data class ResponseBodySelectionKey(
    val requestId: String,
    val responseId: String,
)

fun responseBodySelectionKey(response: AIResponse): ResponseBodySelectionKey {
    return ResponseBodySelectionKey(
        requestId = response.requestId,
        responseId = response.responseId,
    )
}

/**
 * The selected substring from [androidx.compose.foundation.text.selection.SelectionState.selectedTexts].
 *
 * One response body is one text node, so the list is that node's selected slice.
 * Several slices are concatenated in list order and nothing is inserted between them.
 * This does not search [AIResponse.content], so a repeated sentence is not assigned an occurrence.
 * A blank or missing selection returns null and does not open an action.
 */
/**
 * Actions placed on the selection toolbar beside Copy and Select all.
 * Order matches [UserAction]: Explain, Give Example, Ask Follow-up.
 */
val responseSelectionToolbarActions: List<UserAction> = UserAction.entries

fun selectedResponseSubstring(parts: List<String>): String? {
    val selected = when (parts.size) {
        0 -> return null
        1 -> parts[0]
        else -> parts.joinToString(separator = "")
    }
    if (selected.isBlank()) return null
    return selected
}
