package dev.probe.textselection.ai

import dev.probe.textselection.BuildConfig
import dev.probe.textselection.interaction.AIProvider
import dev.probe.textselection.interaction.ConversationSessions
import dev.probe.textselection.interaction.InteractionMemoryStore

/**
 * The only place that chooses [MockAIProvider] or [RemoteAIProvider].
 * Screens talk to [AIService]. The mode is the build property `ai.provider`.
 * Remote mode also gets an [EmbeddingClient] for the same backend. Mock mode does not.
 */
object AppServices {
    val ai: AIService = createAiService(
        mode = BuildConfig.AI_PROVIDER,
        backendUrl = BuildConfig.AI_BACKEND_URL,
        memoryStore = ConversationSessions.memories,
    )
}

internal fun createAiService(
    mode: String,
    backendUrl: String,
    memoryStore: InteractionMemoryStore,
    remoteClient: AIBackendClient = HttpAIBackendClient(baseUrl = backendUrl),
    embeddingClient: EmbeddingClient? = defaultEmbeddingClient(mode, backendUrl),
): AIService {
    return AIService(
        provider = createProvider(mode, backendUrl, remoteClient),
        memoryStore = memoryStore,
        embeddings = embeddingClient,
    )
}

internal fun defaultEmbeddingClient(mode: String, backendUrl: String): EmbeddingClient? {
    return if (mode.trim().equals(REMOTE_MODE, ignoreCase = true)) {
        HttpEmbeddingClient(baseUrl = backendUrl)
    } else {
        null
    }
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
