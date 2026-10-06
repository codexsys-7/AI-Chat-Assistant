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
import dev.probe.textselection.interaction.AIResponse

sealed class ResponsePhase {
    data object Preparing : ResponsePhase()
    data class Ready(val response: AIResponse) : ResponsePhase()
    data class Failed(val message: String) : ResponsePhase()
}

@Composable
fun ResponseScreen(
    phase: ResponsePhase,
    onOpenRequestDebug: (() -> Unit)?,
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
                        val response = phase.response
                        ResponseField(label = "Action", value = response.action.label)
                        ResponseField(label = "Response", value = response.content)
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
            }
        }
    }
}

@Composable
private fun ResponseField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Text(text = value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}
