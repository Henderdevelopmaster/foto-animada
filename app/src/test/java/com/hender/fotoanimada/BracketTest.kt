package com.hender.fotoanimada

import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketTest {
    @Test
    fun hdrRecoversDetailWhereTheReferenceClipped() {
        val scene = TestImages.scene(160, 120, seed = 137L, level = 0.70f)
        for (y in 24 until 52) for (x in 24 until 88) {
            val i = y * scene.w + x
            val light = if (x < 56) 1.08f else 1.95f
            scene.r[i] = light
            scene.g[i] = light
            scene.b[i] = light
        }
        val frames = exposures(scene)
        val lowerHighlight = 36 * scene.w + 40
        val higherHighlight = 36 * scene.w + 72
        assertEquals(255, frames[1][lowerHighlight] and 255)
        assertEquals(255, frames[1][higherHighlight] and 255)

        val result = Bracket.fuseHdr(frames, scene.w, scene.h, threads = 2)
        assertNotNull(result)
        val (pixels, report) = requireNotNull(result)
        assertTrue(report.range > 1.2f)
        assertTrue((pixels[higherHighlight] and 255) - (pixels[lowerHighlight] and 255) >= 8)
    }

    @Test
    fun hdrRejectsMovingSubjectInsteadOfBlendingTwoPositions() {
        val scene = TestImages.scene(160, 120, seed = 137L, level = 0.65f)
        fun subject(left: Int): TestImages.Scene {
            val r = scene.r.copyOf()
            val g = scene.g.copyOf()
            val b = scene.b.copyOf()
            for (y in 42 until 75) for (x in left until left + 24) {
                val i = y * scene.w + x
                r[i] = 0.83f
                g[i] = 0.07f
                b[i] = 0.09f
            }
            return TestImages.Scene(scene.w, scene.h, r, g, b)
        }
        val still = subject(left = 45)
        val baseline = exposures(still)
        assertNotNull(Bracket.fuseHdr(baseline, scene.w, scene.h, threads = 2))
        val changed = listOf(baseline[0], baseline[1], expose(subject(left = 70), 2.5f))
        assertNull(Bracket.fuseHdr(changed, scene.w, scene.h, threads = 2))
    }

    @Test
    fun hdrRejectsIncompleteOrRepeatedExposuresAndInvalidDimensions() {
        val scene = TestImages.scene(80, 64, seed = 91L)
        val frames = exposures(scene)
        assertNull(Bracket.fuseHdr(frames.take(2), scene.w, scene.h, threads = 2))
        assertNull(Bracket.fuseHdr(List(3) { frames[1] }, scene.w, scene.h, threads = 2))
        assertNull(Bracket.fuseHdr(frames, scene.w + 1, scene.h, threads = 2))
    }

    @Test
    fun hdrExposureOrderPreservesCurrentCompensationAndUsesAvailableSymmetricMargin() {
        assertEquals(listOf(2, -2, 6), Bracket.hdrExposureIndices(2, 0.375f, -8, 8))
        assertEquals(listOf(2, 0, 4), Bracket.hdrExposureIndices(2, 0.5f, 0, 10))
        assertNull(Bracket.hdrExposureIndices(2, 0.5f, 1, 10))
        assertNull(Bracket.hdrExposureIndices(2, Float.NaN, -8, 8))
    }

    private fun exposures(scene: TestImages.Scene) = listOf(0.4f, 1f, 2.5f).map { expose(scene, it) }

    private fun expose(scene: TestImages.Scene, scale: Float): IntArray {
        fun encode(linear: Float): Int {
            val value = (linear * scale).coerceIn(0f, 1f)
            val srgb = if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f
            return (srgb * 255).roundToInt()
        }
        return IntArray(scene.w * scene.h) {
            (0xFF shl 24) or (encode(scene.r[it]) shl 16) or (encode(scene.g[it]) shl 8) or encode(scene.b[it])
        }
    }
}
