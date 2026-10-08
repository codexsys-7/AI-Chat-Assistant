package dev.probe.textselection.engine

/**
 * Local reading of a [NormalizedContext].
 * [contextQuality] is the relevance decision. There is no second quality scale.
 * This type feeds [ContextPackage]; it is not sent to a provider.
 */
data class ContextAnalysis(
    val selectedText: String?,
    val precedingContext: String?,
    val followingContext: String?,
    val contextQuality: ContextQuality,
    val relevance: String,
    val segments: List<ContextSegment>,
)
