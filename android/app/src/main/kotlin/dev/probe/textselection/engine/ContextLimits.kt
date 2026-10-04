package dev.probe.textselection.engine

/**
 * Knobs for the processed package. Change them here; the accessibility service
 * does not own these limits.
 */
object ContextLimits {
    const val MAX_SURROUNDING_SENTENCES = 3

    /** Shorter surrounding text is kept, but it is not treated as meaningful. */
    const val MIN_MEANINGFUL_LETTERS = 12
}
