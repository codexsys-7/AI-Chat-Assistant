package dev.probe.textselection.engine

enum class ContextSegmentKind {
    SELECTED,
    PRECEDING,
    FOLLOWING,
}

/**
 * One side of the normalized context.
 * [text] is null when that side was not captured. It is never a placeholder sentence.
 */
data class ContextSegment(
    val kind: ContextSegmentKind,
    val text: String?,
    val available: Boolean,
)
