package dev.probe.textselection

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.probe.textselection.ai.PendingRequests
import dev.probe.textselection.context.ContextCapture
import dev.probe.textselection.context.ContextCaptureRepository
import dev.probe.textselection.engine.ContextEngine
import dev.probe.textselection.interaction.AIRequestAttempt
import dev.probe.textselection.interaction.AIRequestFactory
import dev.probe.textselection.interaction.UserAction

class MainActivity : ComponentActivity() {
    private var contextPackage by mutableStateOf(ContextEngine.process(ContextCapture.idle()))
    private var rejection by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        accept(intent)
        setContent {
            ActionPopup(
                contextPackage = contextPackage,
                rejection = rejection,
                onAction = ::onAction,
            )
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
        contextPackage = ContextEngine.process(ContextCaptureRepository.capture(this, incoming))
        rejection = null
    }

    private fun onAction(action: UserAction) {
        when (val attempt = AIRequestFactory.create(contextPackage, action)) {
            is AIRequestAttempt.Rejected -> rejection = attempt.reason
            is AIRequestAttempt.Ready -> {
                rejection = null
                PendingRequests.hold(attempt.request)
                startActivity(ResponseActivity.intent(this, attempt.request.requestId))
            }
        }
    }
}
