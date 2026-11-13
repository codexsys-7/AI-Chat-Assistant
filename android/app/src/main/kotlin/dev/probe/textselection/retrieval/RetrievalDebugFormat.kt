package dev.probe.textselection.retrieval

/**
 * Text form of a [RetrievalDiagnostics] record.
 * This reads the record. It does not embed, score, or read the memory store.
 */
fun formatRetrievalDebug(diagnostics: RetrievalDiagnostics): String {
    val lines = ArrayList<String>()
    lines += "Semantic Memory Retrieval"
    lines += "Attempted: ${yesNo(diagnostics.attempted)}"
    lines += "Status: ${diagnostics.status.name}"
    lines += "Query embedding status: ${diagnostics.queryEmbeddingStatus}"
    lines += "Session id: ${diagnostics.sessionId}"
    lines += "Request id: ${diagnostics.requestId}"
    lines += "Originating response id: ${diagnostics.originatingResponseId ?: "none"}"
    lines += "Stored count: ${diagnostics.storedCount}"
    lines += "Evaluated: ${diagnostics.evaluatedCount}"
    lines += "Excluded: ${diagnostics.excludedCount}"
    lines += "Returned: ${diagnostics.returnedCount}"
    lines += "Threshold: ${diagnostics.threshold}"
    lines += "Top K: ${diagnostics.topK}"
    lines += "Dimensions: ${diagnostics.dimensions}"
    if (diagnostics.durationMillis != null) {
        lines += "Duration ms: ${diagnostics.durationMillis}"
    }
    lines += "Query text: ${diagnostics.queryText ?: "none"}"
    lines += "Query embedding status: ${diagnostics.queryEmbeddingStatus}"
    lines += "Dimensions: ${diagnostics.dimensions}"
    lines += "Preceding context present: ${yesNo(diagnostics.precedingContextPresent)}"
    lines += "Following context present: ${yesNo(diagnostics.followingContextPresent)}"
    lines += "Evaluated candidates:"
    if (diagnostics.candidates.isEmpty()) {
        lines += "none"
    } else {
        diagnostics.candidates.forEachIndexed { index, candidate ->
            lines += "Candidate ${index + 1}"
            lines += "Memory id: ${candidate.memoryId}"
            lines += "Session id: ${candidate.sessionId}"
            lines += "Request id: ${candidate.requestId}"
            lines += "Response id: ${candidate.responseId}"
            lines += "Score: ${candidate.score?.toString() ?: "none"}"
            lines += "Threshold: ${candidate.threshold}"
            if (candidate.rank != null) {
                lines += "Rank: ${candidate.rank}"
            }
            lines += "Passed threshold: ${yesNoOrNa(candidate.passedThreshold)}"
            lines += "Selected for top K: ${yesNo(candidate.selectedForTopK)}"
            if (candidate.exclusion != null) {
                lines += "Exclusion reason: ${candidate.exclusion.name}"
            }
        }
    }
    lines += "Retrieved Memories — Top K"
    if (diagnostics.topHits.isEmpty()) {
        lines += "No relevant memories retrieved."
    } else {
        diagnostics.topHits.forEach { hit ->
            lines += "Rank: ${hit.rank}"
            lines += "Memory id: ${hit.memoryId}"
            lines += "Session id: ${hit.sessionId}"
            lines += "Request id: ${hit.requestId}"
            lines += "Response id: ${hit.responseId}"
            lines += "Score: ${hit.score}"
            lines += "Selected text: ${hit.selectedText}"
            lines += "Previous answer: ${hit.previousAnswerPreview}"
        }
    }
    return lines.joinToString("\n")
}

private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"

private fun yesNoOrNa(value: Boolean?): String = when (value) {
    true -> "YES"
    false -> "NO"
    null -> "n/a"
}
