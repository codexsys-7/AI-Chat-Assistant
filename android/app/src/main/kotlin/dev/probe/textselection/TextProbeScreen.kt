package dev.probe.textselection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.probe.textselection.engine.ContextPackage
import dev.probe.textselection.interaction.UserAction

private const val CONTEXT_UNAVAILABLE =
    "Selected text captured, surrounding context unavailable."

@Composable
fun ActionPopup(
    contextPackage: ContextPackage,
    rejection: String?,
    onAction: (UserAction) -> Unit,
) {
    MaterialTheme {
        Surface {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = contextPackage.selectedText?.takeIf { it.isNotBlank() } ?: "—",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (surroundingContextMissing(contextPackage)) {
                    Text(
                        text = CONTEXT_UNAVAILABLE,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                UserAction.entries.forEach { action ->
                    Button(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = action.label)
                    }
                }
                if (rejection != null) {
                    Text(text = rejection, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun surroundingContextMissing(contextPackage: ContextPackage): Boolean {
    return !contextPackage.selectedText.isNullOrBlank() &&
        contextPackage.precedingContext.isNullOrBlank() &&
        contextPackage.followingContext.isNullOrBlank()
}
