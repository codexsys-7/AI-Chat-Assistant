package dev.probe.textselection.engine

/**
 * Whitespace-normalized sides after empty text, duplicated selection, and obvious
 * UI noise have been removed. This is not a [ContextPackage].
 */
data class NormalizedContext(
    val selectedText: String?,
    val precedingContext: String?,
    val followingContext: String?,
)
