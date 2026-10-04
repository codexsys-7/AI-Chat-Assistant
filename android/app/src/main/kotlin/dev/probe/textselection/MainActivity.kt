package dev.probe.textselection

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.probe.textselection.context.ContextCapture
import dev.probe.textselection.context.ContextCaptureRepository
import dev.probe.textselection.engine.ContextEngine

class MainActivity : ComponentActivity() {
    private var received by mutableStateOf(ReceivedText.none())
    private var contextPackage by mutableStateOf(ContextEngine.process(ContextCapture.idle()))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accept(intent)
        setContent {
            TextProbeScreen(received, contextPackage)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent?) {
        IntentDebugLogger.log(intent)
        val incoming = IncomingText.read(intent)
        received = incoming
        contextPackage = ContextEngine.process(ContextCaptureRepository.capture(this, incoming))
    }
}
