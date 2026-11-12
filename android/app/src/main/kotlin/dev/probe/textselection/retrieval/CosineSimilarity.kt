package dev.probe.textselection.retrieval

import kotlin.math.sqrt

/**
 * Cosine similarity of two float vectors.
 * Returns null when the score would not be a finite number in [-1, 1]:
 * an empty vector, unequal lengths, a non-finite component, or a zero magnitude.
 * 1536 dimensions are supported. This function does not touch the network or the UI.
 */
fun cosineSimilarity(left: FloatArray, right: FloatArray): Double? {
    if (left.isEmpty() || right.isEmpty()) return null
    if (left.size != right.size) return null
    var dot = 0.0
    var leftSquare = 0.0
    var rightSquare = 0.0
    for (index in left.indices) {
        val a = left[index].toDouble()
        val b = right[index].toDouble()
        if (!a.isFinite() || !b.isFinite()) return null
        dot += a * b
        leftSquare += a * a
        rightSquare += b * b
    }
    if (!dot.isFinite() || !leftSquare.isFinite() || !rightSquare.isFinite()) return null
    if (leftSquare == 0.0 || rightSquare == 0.0) return null
    val score = dot / sqrt(leftSquare * rightSquare)
    if (!score.isFinite()) return null
    if (score > 1.0) return 1.0
    if (score < -1.0) return -1.0
    return score
}

fun cosineSimilarity(left: List<Float>, right: List<Float>): Double? {
    return cosineSimilarity(left.toFloatArray(), right.toFloatArray())
}
