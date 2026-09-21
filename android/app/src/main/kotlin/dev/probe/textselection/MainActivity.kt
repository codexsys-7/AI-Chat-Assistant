package dev.probe.textselection

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var received by mutableStateOf(ReceivedText.none())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        received = accept(intent)
        setContent {
            TextProbeScreen(received)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        received = accept(intent)
    }

    private fun accept(intent: Intent?): ReceivedText {
        IntentDebugLogger.log(intent)
        return IncomingText.read(intent)
    }
}
