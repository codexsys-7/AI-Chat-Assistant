package dev.probe.textselection.retrieval

import dev.probe.textselection.interaction.InteractionMemory

/**
 * Ranked memories for one retrieval call.
 * Lists are aligned by index. A caller may pass this object to [dev.probe.textselection.ai.PromptEngine].
 * [EMPTY] is the result when nothing qualifies.
 */
data class RetrievedMemoryContext(
    val memories: List<InteractionMemory>,
    val scores: List<Double>,
    val sourceMemoryIds: List<String>,
    val requestIds: List<String>,
    val responseIds: List<String>,
) {
    init {
        val size = memories.size
        require(scores.size == size) { "scores must align with memories." }
        require(sourceMemoryIds.size == size) { "source memory ids must align with memories." }
        require(requestIds.size == size) { "request ids must align with memories." }
        require(responseIds.size == size) { "response ids must align with memories." }
        memories.forEachIndexed { index, memory ->
            require(scores[index].isFinite()) { "score must be finite." }
            require(sourceMemoryIds[index] == memory.memoryId) { "source memory id must match the memory." }
            require(requestIds[index] == memory.requestId) { "request id must match the memory." }
            require(responseIds[index] == memory.responseId) { "response id must match the memory." }
        }
    }

    companion object {
        val EMPTY: RetrievedMemoryContext = RetrievedMemoryContext(
            memories = emptyList(),
            scores = emptyList(),
            sourceMemoryIds = emptyList(),
            requestIds = emptyList(),
            responseIds = emptyList(),
        )

        internal fun from(hits: List<ScoredMemory>): RetrievedMemoryContext {
            if (hits.isEmpty()) return EMPTY
            return RetrievedMemoryContext(
                memories = java.util.List.copyOf(hits.map { it.memory }),
                scores = java.util.List.copyOf(hits.map { it.score }),
                sourceMemoryIds = java.util.List.copyOf(hits.map { it.memory.memoryId }),
                requestIds = java.util.List.copyOf(hits.map { it.memory.requestId }),
                responseIds = java.util.List.copyOf(hits.map { it.memory.responseId }),
            )
        }
    }
}

internal data class ScoredMemory(
    val memory: InteractionMemory,
    val score: Double,
)
