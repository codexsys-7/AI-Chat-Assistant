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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RequestScreen(fields: RequestFields) {
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
                RequestField(label = "Selected text", value = fields.selectedText)
                RequestField(label = "Preceding text", value = fields.precedingText)
                RequestField(label = "Following text", value = fields.followingText)
                RequestField(label = "Source app", value = fields.sourceApp)
                RequestField(label = "Capture method", value = fields.captureMethod)
                RequestField(label = "Context quality", value = fields.contextQuality)
                RequestField(label = "Request id", value = fields.requestId)
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
