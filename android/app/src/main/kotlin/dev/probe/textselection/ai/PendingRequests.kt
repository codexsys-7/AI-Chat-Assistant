package dev.probe.textselection.ai

import dev.probe.textselection.interaction.AIRequest

/**
 * In-memory handoff from the action dialog to the response screen.
 * Nothing here is written to disk.
 */
internal object PendingRequests {
    private const val MAX = 8
    private val lock = Any()
    private val recent = ArrayDeque<AIRequest>()

    fun hold(request: AIRequest) {
        synchronized(lock) {
            recent.addLast(request)
            while (recent.size > MAX) {
                recent.removeFirst()
            }
        }
    }

    fun find(requestId: String): AIRequest? {
        synchronized(lock) {
            return recent.lastOrNull { it.requestId == requestId }
        }
    }
}
