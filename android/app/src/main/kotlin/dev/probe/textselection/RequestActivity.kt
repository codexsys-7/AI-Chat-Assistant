package dev.probe.textselection

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.probe.textselection.interaction.ConversationSessions
import dev.probe.textselection.interaction.EMBEDDING_DEBUG_UNAVAILABLE
import dev.probe.textselection.interaction.EmbeddingDebug
import dev.probe.textselection.interaction.EmbeddingStatus
import dev.probe.textselection.interaction.embeddingDebug
import dev.probe.textselection.interaction.memoryForRequest
import dev.probe.textselection.interaction.sessionDebug
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class RequestActivity : ComponentActivity() {
    private var debug by mutableStateOf(sessionDebug(ConversationSessions.manager.current()))
    private var embedding by mutableStateOf(EmbeddingDebug(EMBEDDING_DEBUG_UNAVAILABLE, null, null))
    private var refreshToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fields = RequestFields.from(intent)
        val requestId = fields.requestId
        debug = sessionDebug(ConversationSessions.manager.current())
        embedding = currentEmbedding(requestId)
        setContent {
            val token = refreshToken
            LaunchedEffect(requestId, token) {
                while (isActive) {
                    embedding = currentEmbedding(requestId)
                    if (embedding.status != EmbeddingStatus.PENDING.name) break
                    delay(EMBEDDING_DEBUG_POLL_MS)
                }
            }
            RequestScreen(
                fields = fields,
                sessionId = debug.sessionId,
                interactionCount = debug.interactionCount,
                embeddingStatus = embedding.status,
                embeddingPreview = embedding.preview,
                embeddingDimensions = embedding.dimensions,
                onResetSession = if (BuildConfig.DEBUG) {
                    {
                        ConversationSessions.manager.reset()
                        debug = sessionDebug(ConversationSessions.manager.current())
                        embedding = currentEmbedding(requestId)
                        refreshToken += 1
                    }
                } else {
                    null
                },
            )
        }
    }

    private fun currentEmbedding(requestId: String): EmbeddingDebug {
        val sessionId = ConversationSessions.manager.current()?.sessionId
        return embeddingDebug(memoryForRequest(ConversationSessions.memories, sessionId, requestId))
    }

    private companion object {
        const val EMBEDDING_DEBUG_POLL_MS = 300L
    }
}
