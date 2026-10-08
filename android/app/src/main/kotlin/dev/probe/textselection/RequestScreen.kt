package dev.probe.textselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RequestScreen(
    fields: RequestFields,
    sessionId: String,
    interactionCount: String,
    onResetSession: (() -> Unit)?,
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
                RequestField(label = "Action", value = fields.action)
                RequestField(label = "Normalized selected text", value = fields.selectedText)
                RequestField(label = "Normalized preceding text", value = fields.precedingText)
                RequestField(label = "Normalized following text", value = fields.followingText)
                RequestField(label = "Source app", value = fields.sourceApp)
                RequestField(label = "Session id", value = sessionId)
                RequestField(label = "Interaction count", value = interactionCount)
                RequestField(label = "Capture method", value = fields.captureMethod)
                RequestField(label = "Context quality", value = fields.contextQuality)
                RequestField(label = "Relevance", value = fields.relevance)
                RequestField(label = "Segments", value = fields.segments)
                RequestField(label = "Request id", value = fields.requestId)
                RequestField(label = "Prompt system instruction", value = fields.systemInstruction)
                RequestField(label = "Prompt user content", value = fields.userContent)
                if (onResetSession != null) {
                    TextButton(onClick = onResetSession) {
                        Text("Reset session")
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Text(text = value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}
