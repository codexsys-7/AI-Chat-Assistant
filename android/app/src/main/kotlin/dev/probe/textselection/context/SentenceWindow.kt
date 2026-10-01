package dev.probe.textselection.context

/**
 * Pulls a few sentences before and after a selection from text that is already in memory.
 * It does not invent sentences that were not in that text.
 */
internal object SentenceWindow {
    const val SENTENCE_LIMIT = 3

    fun around(corpus: String, selectedText: String): SurroundingSentences? {
        val selected = normalize(selectedText)
        if (selected.isEmpty() || corpus.isBlank()) return null
        val sentences = splitSentences(normalize(corpus))
        if (sentences.isEmpty()) return null
        val range = matchingRange(sentences, selected) ?: return null
        val before = sentences.subList((range.first - SENTENCE_LIMIT).coerceAtLeast(0), range.first)
        val afterStart = range.last + 1
        val after = sentences.subList(afterStart, (afterStart + SENTENCE_LIMIT).coerceAtMost(sentences.size))
        return SurroundingSentences(
            preceding = before.joinToString(" ").ifBlank { null },
            following = after.joinToString(" ").ifBlank { null },
        )
    }

    fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    fun splitSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun matchingRange(sentences: List<String>, selected: String): IntRange? {
        val normalized = sentences.map { normalize(it) }
        for (start in normalized.indices) {
            val joined = StringBuilder()
            for (end in start until normalized.size) {
                if (joined.isNotEmpty()) joined.append(' ')
                joined.append(normalized[end])
                val value = joined.toString()
                if (value == selected) return start..end
                if (value.length > selected.length) break
            }
        }
        val containing = normalized.indices.filter { index ->
            val value = normalized[index]
            value.contains(selected) || (selected.contains(value) && value.length >= 40)
        }
        if (containing.isEmpty()) return null
        val best = containing.minBy { normalized[it].length }
        return best..best
    }
}

internal data class SurroundingSentences(
    val preceding: String?,
    val following: String?,
)
