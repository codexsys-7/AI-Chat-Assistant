package dev.probe.textselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.probe.textselection.engine.ContextPackage

private const val EMPTY_HINT =
    "Select text in another app and choose Text Selection Probe from the text selection toolbar, or share plain text into this app."

private const val CONTEXT_UNAVAILABLE =
    "Selected text captured, surrounding context unavailable."

@Composable
fun TextProbeScreen(received: ReceivedText, contextPackage: ContextPackage) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Text Selection Probe",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Field(label = "Path", value = pathLabel(received.path))
                Field(label = "Action", value = received.action ?: "—")
                Field(label = "MIME type", value = received.mimeType ?: "—")
                Text(text = "Text", style = MaterialTheme.typography.titleMedium)
                if (received.path == TextPath.None) {
                    Text(text = EMPTY_HINT, style = MaterialTheme.typography.bodyLarge)
                } else {
                    SelectionContainer {
                        Text(
                            text = received.text?.ifEmpty { "(empty)" } ?: "(none)",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Field(label = "SELECTED TEXT", value = display(contextPackage.selectedText))
                Field(label = "PRECEDING CONTEXT", value = display(contextPackage.precedingContext))
                Field(label = "FOLLOWING CONTEXT", value = display(contextPackage.followingContext))
                Field(label = "SOURCE APP", value = display(contextPackage.sourcePackage))
                Field(label = "CAPTURE METHOD", value = contextPackage.captureMethod.name)
                Field(label = "CONTEXT QUALITY", value = contextPackage.contextQuality.name)
                Field(label = "CAPTURE STATUS", value = contextPackage.captureStatus.name)
                if (surroundingContextMissing(received, contextPackage)) {
                    Text(
                        text = CONTEXT_UNAVAILABLE,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun pathLabel(path: TextPath): String = when (path) {
    TextPath.Selected -> "Selected text"
    TextPath.Shared -> "Shared text"
    TextPath.None -> "None"
}

private fun display(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "—"

private fun surroundingContextMissing(received: ReceivedText, contextPackage: ContextPackage): Boolean {
    return received.path == TextPath.Selected &&
        !contextPackage.selectedText.isNullOrBlank() &&
        contextPackage.precedingContext.isNullOrBlank() &&
        contextPackage.followingContext.isNullOrBlank()
}
