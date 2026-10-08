package dev.probe.textselection

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.probe.textselection.interaction.ConversationSessions
import dev.probe.textselection.interaction.sessionDebug

class RequestActivity : ComponentActivity() {
    private var debug by mutableStateOf(sessionDebug(ConversationSessions.manager.current()))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fields = RequestFields.from(intent)
        debug = sessionDebug(ConversationSessions.manager.current())
        setContent {
            RequestScreen(
                fields = fields,
                sessionId = debug.sessionId,
                interactionCount = debug.interactionCount,
                onResetSession = if (BuildConfig.DEBUG) {
                    {
                        ConversationSessions.manager.reset()
                        debug = sessionDebug(ConversationSessions.manager.current())
                    }
                } else {
                    null
                },
            )
        }
    }
}
