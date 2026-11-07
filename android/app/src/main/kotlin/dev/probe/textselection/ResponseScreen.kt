package dev.probe.textselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.probe.textselection.context.ResponseContextBuilder
import dev.probe.textselection.interaction.AIRequestAttempt
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
    onInAppAction: (AIResponse, String, UserAction) -> AIRequestAttempt,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (phase) {
                ResponsePhase.Preparing -> {
                    MessageColumn {
                        Text(
                            text = "Preparing response...",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is ResponsePhase.Failed -> {
                    MessageColumn {
                        Text(text = phase.message, style = MaterialTheme.typography.bodyLarge)
                    }
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

@Composable
private fun MessageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "MOCK", style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun ReadyResponse(
    response: AIResponse,
    onOpenRequestDebug: (() -> Unit)?,
    onInAppAction: (AIResponse, String, UserAction) -> AIRequestAttempt,
) {
    val selectionKey = responseBodySelectionKey(response)
    // A new request or response id disposes this container, including the registrar
    // SelectionContainer saves internally. Offsets stay inside that registrar.
    // The public state only keeps the selected substring.
    key(selectionKey) {
        val selectionState = rememberSelectionState()
        val selected = selectedResponseSubstring(selectionState.selectedTexts.map { it.text })
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "MOCK", style = MaterialTheme.typography.labelLarge)
                ResponseField(label = "Action", value = response.action.label)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "Response", style = MaterialTheme.typography.titleMedium)
                    val body = response.content.ifBlank { "—" }
                    // PROCESS_TEXT items only carry the substring and resolve neighbors from the
                    // other-app snapshot. They are omitted here. The panel below uses this response.
                    SelectionContainer(
                        state = selectionState,
                        modifier = Modifier.filterTextContextMenuComponents { component ->
                            component.key !is ProcessTextKey
                        },
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
            if (selected != null) {
                ResponseSelectionActions(
                    response = response,
                    selectedText = selected,
                    onInAppAction = onInAppAction,
                )
            }
        }
    }
}

@Composable
private fun ResponseSelectionActions(
    response: AIResponse,
    selectedText: String,
    onInAppAction: (AIResponse, String, UserAction) -> AIRequestAttempt,
) {
    val contextPackage = ResponseContextBuilder.build(response, selectedText)
    var rejection by remember(selectedText) { mutableStateOf<String?>(null) }
    ActionPopup(
        contextPackage = contextPackage,
        rejection = rejection,
        onAction = { action ->
            when (val attempt = onInAppAction(response, selectedText, action)) {
                is AIRequestAttempt.Rejected -> rejection = attempt.reason
                is AIRequestAttempt.Ready -> rejection = null
            }
        },
    )
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
