package dev.probe.textselection

import dev.probe.textselection.interaction.AIResponse

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
