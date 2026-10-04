package dev.probe.textselection.engine

import dev.probe.textselection.context.ContextCapture

/**
 * Turns a [ContextCapture] into a [ContextPackage].
 * Normalization only: no network, no model, and no new sentences.
 */
object ContextEngine {
    fun process(capture: ContextCapture): ContextPackage {
        val selected = normalize(capture.selectedText)
        val preceding = limitSide(capture.precedingContext, selected, nearest = Nearest.END)
        val following = limitSide(capture.followingContext, selected, nearest = Nearest.START)
        return ContextPackage(
            selectedText = selected,
            precedingContext = preceding,
            followingContext = following,
            sourcePackage = normalize(capture.sourcePackage),
            captureMethod = capture.captureMethod,
            captureStatus = capture.status,
            contextQuality = quality(preceding, following),
        )
    }

    private enum class Nearest { START, END }

    private fun limitSide(raw: String?, selected: String?, nearest: Nearest): String? {
        val normalized = normalize(raw) ?: return null
        val pieces = dropSelected(splitPieces(normalized), selected)
        if (pieces.isEmpty()) return null
        val limited = if (pieces.size <= ContextLimits.MAX_SURROUNDING_SENTENCES) {
            pieces
        } else if (nearest == Nearest.END) {
            pieces.takeLast(ContextLimits.MAX_SURROUNDING_SENTENCES)
        } else {
            pieces.take(ContextLimits.MAX_SURROUNDING_SENTENCES)
        }
        return join(limited)
    }

    private fun quality(preceding: String?, following: String?): ContextQuality {
        val beforeMeaningful = meaningful(preceding)
        val afterMeaningful = meaningful(following)
        val beforePresent = preceding != null
        val afterPresent = following != null
        return when {
            beforeMeaningful && afterMeaningful -> ContextQuality.HIGH
            beforeMeaningful && !afterPresent -> ContextQuality.MEDIUM
            afterMeaningful && !beforePresent -> ContextQuality.MEDIUM
            beforePresent || afterPresent -> ContextQuality.LOW
            else -> ContextQuality.UNAVAILABLE
        }
    }

    private fun meaningful(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.count { it.isLetter() } >= ContextLimits.MIN_MEANINGFUL_LETTERS
    }

    internal fun normalize(raw: String?): String? {
        if (raw == null) return null
        val collapsed = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t\\u000B\\f]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return collapsed.ifEmpty { null }
    }

    private data class Piece(val text: String, val paragraphIndex: Int)

    private fun splitPieces(text: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        text.split(Regex("\\n\\n+")).forEachIndexed { paragraphIndex, paragraph ->
            splitSentences(paragraph).forEach { sentence ->
                pieces += Piece(sentence, paragraphIndex)
            }
        }
        return pieces
    }

    private fun splitSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun dropSelected(pieces: List<Piece>, selected: String?): List<Piece> {
        val selectedNorm = normalize(selected) ?: return pieces
        val selectedSentences = splitSentences(selectedNorm)
        if (selectedSentences.isEmpty()) return pieces
        val drop = BooleanArray(pieces.size)
        pieces.forEachIndexed { index, piece ->
            if (piece.text == selectedNorm || selectedSentences.any { it == piece.text }) {
                drop[index] = true
            }
        }
        if (selectedSentences.size > 1) {
            for (start in pieces.indices) {
                val end = start + selectedSentences.size
                if (end > pieces.size) break
                val window = pieces.subList(start, end).map { it.text }
                if (window == selectedSentences) {
                    for (index in start until end) drop[index] = true
                }
            }
        }
        return pieces.filterIndexed { index, _ -> !drop[index] }
    }

    private fun join(pieces: List<Piece>): String? {
        if (pieces.isEmpty()) return null
        val joined = StringBuilder()
        pieces.forEachIndexed { index, piece ->
            if (index > 0) {
                val breakBetween = piece.paragraphIndex != pieces[index - 1].paragraphIndex
                joined.append(if (breakBetween) "\n\n" else " ")
            }
            joined.append(piece.text)
        }
        return joined.toString().ifBlank { null }
    }
}
