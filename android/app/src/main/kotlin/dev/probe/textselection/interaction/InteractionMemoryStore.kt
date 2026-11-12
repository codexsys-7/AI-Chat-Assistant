package dev.probe.textselection.interaction

/**
 * Process-local successes, grouped by session id.
 * Listing one session cannot see another. Nothing here is written to disk.
 */
class InteractionMemoryStore(
    private val maxPerSession: Int = DEFAULT_MAX_PER_SESSION,
) {
    init {
        require(maxPerSession >= 1) { "maxPerSession must be at least 1." }
    }

    private val lock = Any()
    private val bySession = HashMap<String, ArrayDeque<InteractionMemory>>()
    private val knownIds = HashSet<String>()

    /**
     * Keeps [memory] in its own session.
     * A repeated [InteractionMemory.memoryId] is rejected and leaves the store unchanged.
     * Past [maxPerSession], the oldest memory in that session is dropped.
     */
    fun save(memory: InteractionMemory) {
        if (memory.memoryId.isBlank()) {
            throw IllegalArgumentException("Memory id is blank.")
        }
        if (memory.sessionId.isBlank()) {
            throw IllegalArgumentException("Session id is blank.")
        }
        synchronized(lock) {
            if (memory.memoryId in knownIds) {
                throw DuplicateInteractionMemoryIdException(memory.memoryId)
            }
            knownIds.add(memory.memoryId)
            val session = bySession.getOrPut(memory.sessionId) { ArrayDeque() }
            session.insertChronologically(memory)
            while (session.size > maxPerSession) {
                knownIds.remove(session.removeFirst().memoryId)
            }
        }
    }

    fun list(sessionId: String): List<InteractionMemory> {
        if (sessionId.isBlank()) return emptyList()
        synchronized(lock) {
            return bySession[sessionId]?.toList().orEmpty()
        }
    }

    fun clear(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            val removed = bySession.remove(sessionId) ?: return
            removed.forEach { knownIds.remove(it.memoryId) }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            bySession.clear()
            knownIds.clear()
        }
    }

    private fun ArrayDeque<InteractionMemory>.insertChronologically(memory: InteractionMemory) {
        val index = indexOfFirst { it.createdAt > memory.createdAt }
        if (index == -1) addLast(memory) else add(index, memory)
    }

    companion object {
        const val DEFAULT_MAX_PER_SESSION = 30
    }
}

class DuplicateInteractionMemoryIdException(val memoryId: String) :
    IllegalArgumentException("Duplicate memory id: $memoryId")
