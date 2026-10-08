package dev.probe.textselection.context

/**
 * Pulls a few sentences before and after a selection from text that is already in memory.
 * Speaker turns are split on line breaks before whitespace inside a line is collapsed.
 * Only the speaker turn that contains the selection is used. Other speakers, the window
 * title, and later paragraphs in that turn are left out. It does not invent sentences.
 */
internal object SentenceWindow {
    const val SENTENCE_LIMIT = 3

    fun around(corpus: String, selectedText: String): SurroundingSentences? {
        val selected = normalize(selectedText)
        if (selected.isEmpty() || corpus.isBlank()) return null
        val turn = turnContaining(corpus, selected) ?: return null
        val lineIndex = turn.indexOfFirst { normalize(it).contains(selected) }
        if (lineIndex < 0) return null
        val onLine = splitSentences(normalize(turn[lineIndex]))
        val range = matchingRange(onLine, selected)
        val beforeOnLine = if (range == null) emptyList() else onLine.subList(0, range.first)
        val afterOnLine = if (range == null) emptyList() else onLine.subList(range.last + 1, onLine.size)
        val earlier = turn.subList(0, lineIndex).flatMap { splitSentences(normalize(it)) }
        val preceding = (earlier + beforeOnLine).takeLast(SENTENCE_LIMIT)
        val following = afterOnLine.take(SENTENCE_LIMIT)
        return SurroundingSentences(
            preceding = preceding.joinToString(" ").ifBlank { null },
            following = following.joinToString(" ").ifBlank { null },
        )
    }

    /**
     * Lines stay separate until a speaker label starts a new turn.
     * "You" / "Assistant" count as labels on their own line, or when the next
     * word is capitalized. A sentence that merely starts with "You should" does not.
     */
    private fun turnContaining(corpus: String, selected: String): List<String>? {
        val turns = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        fun nextTurn() {
            if (current.isNotEmpty()) turns.add(current)
            current = mutableListOf()
        }
        for (raw in corpus.split(Regex("\\r\\n|\\n|\\r"))) {
            val stripped = stripChrome(raw.trim())
            if (stripped.isEmpty() || isChrome(stripped)) continue
            val label = leadingSpeaker(stripped)
            if (label != null) {
                nextTurn()
                val rest = stripped.substring(label.length).trim()
                if (rest.isNotEmpty() && !isChrome(rest)) current += rest
                continue
            }
            current += stripped
        }
        if (current.isNotEmpty()) turns.add(current)
        return turns.firstOrNull { turn -> turn.any { normalize(it).contains(selected) } }
    }

    private fun stripChrome(line: String): String {
        var rest = line
        while (true) {
            val match = CHROME_PREFIXES.firstOrNull { prefix ->
                rest.equals(prefix, ignoreCase = true) || rest.startsWith("$prefix ", ignoreCase = true)
            } ?: return rest
            if (rest.equals(match, ignoreCase = true)) return ""
            rest = rest.substring(match.length).trim()
        }
    }

    private fun isChrome(line: String): Boolean {
        val key = line.trim().lowercase().replace(Regex("[.!?]+$"), "").replace(Regex("\\s+"), " ")
        return key in CHROME_LINES
    }

    private fun leadingSpeaker(line: String): String? {
        for (label in listOf("Assistant", "You")) {
            if (line == label) return label
            val prefix = "$label "
            if (!line.startsWith(prefix)) continue
            val next = line.getOrNull(prefix.length) ?: return label
            if (next.isUpperCase()) return label
        }
        return null
    }

    private val CHROME_PREFIXES = listOf("Sample Chat")

    private val CHROME_LINES = setOf(
        "sample chat",
        "copy",
        "share",
        "select all",
        "read aloud",
        "paste",
        "cut",
        "text selection probe",
    )

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
