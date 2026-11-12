package dev.probe.textselection.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CosineSimilarityTest {
    @Test
    fun identicalVectorsScoreOne() {
        val vector = floatArrayOf(1f, 2f, 3f)
        assertFinite(1.0, cosineSimilarity(vector, vector.copyOf()))
    }

    @Test
    fun oppositeVectorsScoreNegativeOne() {
        assertFinite(-1.0, cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)))
    }

    @Test
    fun orthogonalVectorsScoreZero() {
        assertFinite(0.0, cosineSimilarity(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)))
    }

    @Test
    fun knownValue() {
        val score = cosineSimilarity(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))
        assertFinite(32.0 / sqrt(14.0 * 77.0), score)
    }

    @Test
    fun emptyVectorsAreRejected() {
        assertNull(cosineSimilarity(floatArrayOf(), floatArrayOf()))
        assertNull(cosineSimilarity(floatArrayOf(), floatArrayOf(1f)))
        assertNull(cosineSimilarity(floatArrayOf(1f), floatArrayOf()))
        assertNull(cosineSimilarity(emptyList<Float>(), listOf(1f)))
    }

    @Test
    fun mismatchedDimensionsAreRejected() {
        assertNull(cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f)))
        assertNull(cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 0f, 0f)))
        assertNull(cosineSimilarity(listOf(1f, 2f), listOf(1f, 2f, 3f)))
    }

    @Test
    fun zeroMagnitudeIsRejected() {
        assertNull(cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f)))
        assertNull(cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)))
        assertNull(cosineSimilarity(floatArrayOf(3f, 4f), floatArrayOf(0f, 0f, 0f).copyOf(2)))
        assertNull(cosineSimilarity(floatArrayOf(0f), floatArrayOf(-0f)))
    }

    @Test
    fun nonFiniteInputsAreRejected() {
        assertNull(cosineSimilarity(floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, 1f)))
        assertNull(cosineSimilarity(floatArrayOf(1f, 1f), floatArrayOf(1f, Float.NaN)))
        assertNull(cosineSimilarity(floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(1f)))
        assertNull(cosineSimilarity(floatArrayOf(1f), floatArrayOf(Float.NEGATIVE_INFINITY)))
        assertNull(cosineSimilarity(floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY)))
    }

    @Test
    fun supports1536Dimensions() {
        val left = FloatArray(1536) { index -> if (index == 0) 1f else 0f }
        val same = FloatArray(1536) { index -> if (index == 0) 1f else 0f }
        val orthogonal = FloatArray(1536) { index -> if (index == 1) 1f else 0f }
        assertEquals(1536, left.size)
        assertFinite(1.0, cosineSimilarity(left, same))
        assertFinite(0.0, cosineSimilarity(left, orthogonal))
        assertNull(cosineSimilarity(left, FloatArray(1535)))
    }

    private fun assertFinite(expected: Double, actual: Double?) {
        assertTrue(actual != null && actual.isFinite())
        assertTrue(actual!! in -1.0..1.0)
        assertEquals(expected, actual, 1e-12)
    }
}
