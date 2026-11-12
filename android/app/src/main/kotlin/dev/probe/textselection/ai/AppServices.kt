package dev.probe.textselection.ai

import dev.probe.textselection.BuildConfig
import dev.probe.textselection.interaction.AIProvider
import dev.probe.textselection.interaction.ConversationSessions

/**
 * The only place that chooses [MockAIProvider] or [RemoteAIProvider].
 * Screens talk to [AIService]. The mode is the build property `ai.provider`.
 */
object AppServices {
    val ai: AIService = AIService(
        provider = createProvider(
            mode = BuildConfig.AI_PROVIDER,
            backendUrl = BuildConfig.AI_BACKEND_URL,
        ),
        memoryStore = ConversationSessions.memories,
    )
}

internal fun createProvider(
    mode: String,
    backendUrl: String,
    remoteClient: AIBackendClient = HttpAIBackendClient(baseUrl = backendUrl),
): AIProvider {
    return if (mode.trim().equals(REMOTE_MODE, ignoreCase = true)) {
        RemoteAIProvider(client = remoteClient)
    } else {
        MockAIProvider()
    }
}

internal const val REMOTE_MODE = "REMOTE"
