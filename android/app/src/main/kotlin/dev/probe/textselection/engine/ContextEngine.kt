package dev.probe.textselection.engine

import dev.probe.textselection.context.ContextCapture

/**
 * Capture goes through normalization, then context intelligence, then a [ContextPackage].
 * No network, no model, and no new sentences.
 */
object ContextEngine {
    fun process(capture: ContextCapture): ContextPackage {
        val normalized = ContextNormalizer.normalize(
            selectedText = capture.selectedText,
            precedingContext = capture.precedingContext,
            followingContext = capture.followingContext,
        )
        val analysis = ContextIntelligence.analyze(normalized)
        return ContextPackage(
            selectedText = analysis.selectedText,
            precedingContext = analysis.precedingContext,
            followingContext = analysis.followingContext,
            sourcePackage = ContextNormalizer.collapse(capture.sourcePackage),
            captureMethod = capture.captureMethod,
            captureStatus = capture.status,
            contextQuality = analysis.contextQuality,
        )
    }
}
