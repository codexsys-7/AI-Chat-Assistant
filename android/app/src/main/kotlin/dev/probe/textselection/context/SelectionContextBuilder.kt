package dev.probe.textselection.context

/**
 * Turns one accessibility snapshot into the few sentences around the selection.
 * The raw window text is not retained.
 */
internal object SelectionContextBuilder {
    fun build(
        sourcePackage: String,
        eventText: String,
        selectionStart: Int,
        selectionEnd: Int,
        selectionEvent: Boolean,
        visibleNodes: List<VisibleTextNode>,
    ): SelectionSnapshot? {
        val selectedFromEvent = substring(eventText, selectionStart, selectionEnd)
            ?: selectedTextFromEvent(selectionEvent, eventText, selectionStart, selectionEnd)
        val selectedFromNode = visibleNodes.firstNotNullOfOrNull { node ->
            substring(node.text, node.selectionStart, node.selectionEnd)
        }
        val selected = selectedFromEvent ?: selectedFromNode
        if (selected.isNullOrBlank()) return null

        val fromEvent = if (eventText.isNotBlank()) {
            SentenceWindow.around(eventText, selected)
        } else {
            null
        }
        val fromWindow = SentenceWindow.around(visibleNodes.joinToString("\n") { it.text }, selected)
        val surrounding = prefer(fromEvent, fromWindow)
        return SelectionSnapshot(
            sourcePackage = sourcePackage,
            selectedText = selected,
            precedingContext = surrounding?.preceding,
            followingContext = surrounding?.following,
            nodeCount = visibleNodes.size,
            selectedFound = true,
        )
    }

    private fun prefer(primary: SurroundingSentences?, fallback: SurroundingSentences?): SurroundingSentences? {
        val primaryScore = score(primary)
        val fallbackScore = score(fallback)
        return if (fallbackScore > primaryScore) fallback else primary
    }

    private fun score(sentences: SurroundingSentences?): Int {
        if (sentences == null) return -1
        var score = 0
        if (!sentences.preceding.isNullOrBlank()) score += 1
        if (!sentences.following.isNullOrBlank()) score += 1
        return score
    }

    private fun selectedTextFromEvent(
        selectionEvent: Boolean,
        eventText: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): String? {
        if (!selectionEvent || eventText.isBlank()) return null
        if (selectionStart >= 0 && selectionEnd >= 0) return null
        val trimmed = eventText.trim()
        val sentences = SentenceWindow.splitSentences(SentenceWindow.normalize(trimmed))
        return if (sentences.size <= 1) trimmed else null
    }

    private fun substring(text: String, start: Int, end: Int): String? {
        if (text.isEmpty() || start < 0 || end < 0 || start == end) return null
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        if (from >= to) return null
        val value = text.substring(from, to).trim()
        return value.ifEmpty { null }
    }
}
