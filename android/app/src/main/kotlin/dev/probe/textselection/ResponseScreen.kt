package dev.probe.textselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.probe.textselection.interaction.AIResponse
import dev.probe.textselection.interaction.UserAction

sealed class ResponsePhase {
    data object Preparing : ResponsePhase()
    data class Ready(val response: AIResponse) : ResponsePhase()
    data class Failed(val message: String) : ResponsePhase()
}

@Composable
fun ResponseScreen(
    phase: ResponsePhase,
    onOpenRequestDebug: (() -> Unit)?,
    onInAppAction: (AIResponse, String, UserAction) -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "MOCK", style = MaterialTheme.typography.labelLarge)
                when (phase) {
                    ResponsePhase.Preparing -> {
                        Text(
                            text = "Preparing response...",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    is ResponsePhase.Failed -> {
                        Text(text = phase.message, style = MaterialTheme.typography.bodyLarge)
                    }
                    is ResponsePhase.Ready -> {
                        ReadyResponse(
                            response = phase.response,
                            onOpenRequestDebug = onOpenRequestDebug,
                            onInAppAction = onInAppAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyResponse(
    response: AIResponse,
    onOpenRequestDebug: (() -> Unit)?,
    onInAppAction: (AIResponse, String, UserAction) -> Unit,
) {
    val selectionKey = responseBodySelectionKey(response)
    // A new request or response id disposes this container, including the registrar
    // SelectionContainer saves internally. Offsets stay inside that registrar.
    // The public state only keeps the selected substring.
    key(selectionKey) {
        val selectionState = rememberSelectionState()
        ResponseField(label = "Action", value = response.action.label)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "Response", style = MaterialTheme.typography.titleMedium)
            val body = response.content.ifBlank { "—" }
            SelectionContainer(
                state = selectionState,
                modifier = responseSelectionToolbar(
                    response = response,
                    selectedText = {
                        selectedResponseSubstring(selectionState.selectedTexts.map { it.text })
                    },
                    onInAppAction = onInAppAction,
                ),
            ) {
                Text(text = body, style = MaterialTheme.typography.bodyLarge)
            }
        }
        ResponseField(label = "Provider", value = response.provider)
        ResponseField(label = "Model", value = response.model)
        ResponseField(label = "Request id", value = response.requestId)
        ResponseField(label = "Response id", value = response.responseId)
        ResponseField(label = "Status", value = response.status.name)
        if (onOpenRequestDebug != null) {
            TextButton(onClick = onOpenRequestDebug) {
                Text("Request debug")
            }
        }
    }
}

/**
 * Adds Explain, Give Example, and Ask Follow-up to the platform selection toolbar.
 *
 * Foundation 1.12.1 puts [appendTextContextMenuComponents] items on the floating
 * action-mode toolbar beside Copy. PROCESS_TEXT items are removed: they only carry
 * the substring and would resolve neighbors from the other-app snapshot.
 */
private fun responseSelectionToolbar(
    response: AIResponse,
    selectedText: () -> String?,
    onInAppAction: (AIResponse, String, UserAction) -> Unit,
): Modifier {
    return Modifier
        .appendTextContextMenuComponents {
            val selected = selectedText() ?: return@appendTextContextMenuComponents
            responseSelectionToolbarActions.forEach { action ->
                item(key = action, label = action.label) {
                    onInAppAction(response, selected, action)
                    close()
                }
            }
        }
        .filterTextContextMenuComponents { component ->
            component.key !is ProcessTextKey
        }
}

@Composable
private fun ResponseField(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Text(text = value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}
