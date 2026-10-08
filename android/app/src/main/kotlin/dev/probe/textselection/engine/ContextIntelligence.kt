package dev.probe.textselection.engine

/**
 * Rates normalized context on the device.
 * No model, embeddings, database, or network. Missing sides stay null.
 */
object ContextIntelligence {
    fun analyze(context: NormalizedContext): ContextAnalysis {
        val selected = context.selectedText?.takeIf { it.isNotBlank() }
        val preceding = context.precedingContext?.takeIf { it.isNotBlank() }
        val following = context.followingContext?.takeIf { it.isNotBlank() }
        val before = signal(preceding, selected)
        val after = signal(following, selected)
        val quality = quality(selected, before, after)
        return ContextAnalysis(
            selectedText = selected,
            precedingContext = preceding,
            followingContext = following,
            contextQuality = quality,
            relevance = relevance(quality),
            segments = listOf(
                segment(ContextSegmentKind.SELECTED, selected),
                segment(ContextSegmentKind.PRECEDING, preceding),
                segment(ContextSegmentKind.FOLLOWING, following),
            ),
        )
    }

    /**
     * How a surrounding side relates to the selected text.
     * This is an input to [ContextQuality], not a second public rating.
     */
    private enum class SideSignal {
        UNAVAILABLE,
        WEAK,
        LIMITED,
        USABLE,
    }

    private fun signal(side: String?, selected: String?): SideSignal {
        if (side.isNullOrBlank()) return SideSignal.UNAVAILABLE
        val letters = side.count { it.isLetter() }
        val sideStems = stems(side)
        if (letters < ContextLimits.MIN_MEANINGFUL_LETTERS || sideStems.size < 2) {
            return SideSignal.WEAK
        }
        val selectedStems = stems(selected.orEmpty()).toSet()
        if (selectedStems.isEmpty()) {
            return if (sideStems.size >= USABLE_STEMS) SideSignal.USABLE else SideSignal.LIMITED
        }
        val shared = sideStems.toSet().intersect(selectedStems).size
        if (shared == 0) return SideSignal.WEAK
        return if (sideStems.size >= USABLE_STEMS) SideSignal.USABLE else SideSignal.LIMITED
    }

    private fun quality(
        selected: String?,
        before: SideSignal,
        after: SideSignal,
    ): ContextQuality {
        if (selected.isNullOrBlank() && before == SideSignal.UNAVAILABLE && after == SideSignal.UNAVAILABLE) {
            return ContextQuality.UNAVAILABLE
        }
        if (before == SideSignal.UNAVAILABLE && after == SideSignal.UNAVAILABLE) {
            return ContextQuality.UNAVAILABLE
        }
        if (before == SideSignal.USABLE && after == SideSignal.USABLE) {
            return ContextQuality.HIGH
        }
        val signals = listOf(before, after)
        val hasUsable = signals.any { it == SideSignal.USABLE }
        val hasLimited = signals.any { it == SideSignal.LIMITED }
        val hasWeak = signals.any { it == SideSignal.WEAK }
        if ((hasUsable || hasLimited) && !hasWeak) return ContextQuality.MEDIUM
        return ContextQuality.LOW
    }

    private fun relevance(quality: ContextQuality): String = when (quality) {
        ContextQuality.HIGH ->
            "Meaningful usable surrounding context is relevant to the selected text."
        ContextQuality.MEDIUM ->
            "Some limited surrounding context is relevant to the selected text."
        ContextQuality.LOW ->
            "Surrounding context is weak or noisy relative to the selected text."
        ContextQuality.UNAVAILABLE ->
            "Surrounding context is unavailable."
    }

    private fun segment(kind: ContextSegmentKind, text: String?): ContextSegment {
        return ContextSegment(
            kind = kind,
            text = text,
            available = text != null,
        )
    }

    private fun stems(text: String): List<String> {
        return WORD.findAll(text.lowercase())
            .map { it.value.trim('\'') }
            .filter { it.isNotEmpty() && it !in STOP_WORDS }
            .map { if (it.endsWith("s") && it.length > 4) it.dropLast(1) else it }
            .filter { it.length >= 4 }
            .toList()
    }

    private const val USABLE_STEMS = 4

    private val WORD = Regex("[\\p{L}']+")

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "for", "with",
        "as", "by", "from", "that", "this", "these", "those", "it", "its", "is", "are",
        "was", "were", "be", "been", "being", "can", "could", "should", "would", "will",
        "not", "no", "yes", "do", "does", "did", "has", "have", "had", "using", "used",
        "into", "than", "then", "about", "after", "before", "over", "also", "only", "just",
        "more", "most", "other", "such", "there", "here", "when", "where", "what", "which",
        "who", "how", "why", "you", "your", "we", "our", "they", "their", "them", "he",
        "she", "his", "her", "at", "up", "out", "so", "too", "very", "really", "help",
        "still", "than", "its",
    )
}
