package com.hender.fotoanimada

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Juntar fotos de noche: debe quitar grano cuando todo está quieto y no dejar fantasmas,
 * parches ni doble exposición cuando algo se movió (fallos vistos en v1.0.71: cara con pasto
 * encima, manchas en la nariz y una costura en el borde).
 */
class NightStackTest {
    private val w = 480
    private val h = 360
    private val sigma = 6f

    private fun stack(ref: IntArray, frames: List<IntArray>): Pair<IntArray, List<Float>> {
        val st = NightStack(ref.copyOf(), w, h, 2)
        val shares = frames.map { f -> st.add { y, out -> System.arraycopy(f, y * w, out, 0, w) } }
        return st.result() to shares
    }

    @Test
    fun quietScene_isMergedAndHasLessGrain() {
        val clean = TestImages.scene(w, h, seed = 1)
        val ref = TestImages.pixels(clean, sigma, 100)
        val frames = (1..3).map { TestImages.pixels(clean, sigma, 100L + it) }
        val (out, shares) = stack(ref, frames)
        assertTrue("todas entran: $shares", shares.all { it > 0.8f })
        val before = TestImages.rms(ref, clean)
        val after = TestImages.rms(out, clean)
        assertTrue("menos grano: antes $before, después $after", after < before * 0.75)
    }

    @Test
    fun smallCameraShake_isAlignedAndMerged() {
        val big = TestImages.scene(w + 40, h + 40, seed = 2)
        val clean = TestImages.crop(big, 20, 20, w, h)
        val ref = TestImages.pixels(clean, sigma, 200)
        val frames = listOf(3 to -2, -4 to 3, 5 to 4).mapIndexed { k, (dx, dy) ->
            TestImages.pixels(TestImages.crop(big, 20 + dx, 20 + dy, w, h), sigma, 201L + k)
        }
        val (out, shares) = stack(ref, frames)
        assertTrue("se alinean y entran: $shares", shares.all { it > 0.6f })
        assertTrue("menos grano", TestImages.rms(out, clean) < TestImages.rms(ref, clean) * 0.85)
    }

    @Test
    fun bigCameraMove_isRejectedAndKeepsTheFirstPhoto() {
        val big = TestImages.scene(w + 120, h + 120, seed = 3)
        val clean = TestImages.crop(big, 60, 60, w, h)
        val ref = TestImages.pixels(clean, sigma, 300)
        // 50 px de 480 (10 %): el cel se movió mucho
        val frames = (1..3).map { TestImages.pixels(TestImages.crop(big, 60 + 50, 60 + 30, w, h), sigma, 300L + it) }
        val (out, shares) = stack(ref, frames)
        assertTrue("no entra ninguna: $shares", shares.all { it == 0f })
        assertTrue("queda la primera", TestImages.blockedDiff(out, ref, w, 0, 0, w, h) < 1f)
    }

    @Test
    fun otherScene_isRejected_noDoubleExposure() {
        val clean = TestImages.scene(w, h, seed = 4)
        val other = TestImages.scene(w, h, seed = 44)
        val ref = TestImages.pixels(clean, sigma, 400)
        val frames = (1..3).map { TestImages.pixels(other, sigma, 400L + it) }
        val (out, shares) = stack(ref, frames)
        assertTrue("otra escena no entra: $shares", shares.all { it < 0.05f })
        assertTrue("sin doble exposición", TestImages.blockedDiff(out, ref, w, 0, 0, w, h) < 2f)
    }

    @Test
    fun headMovedInTheCenter_leavesNoGhost() {
        val clean = TestImages.scene(w, h, seed = 5)
        val ref = TestImages.pixels(clean, sigma, 500)
        // La "cara" (centro) se corre 14 px en las otras fotos; el fondo queda quieto
        val frames = (1..3).map {
            TestImages.pixels(TestImages.moveRegion(clean, 120, 80, 360, 300, 14, 6), sigma, 500L + it)
        }
        val (out, _) = stack(ref, frames)
        val ghost = TestImages.blockedDiff(out, ref, w, 120, 80, 360, 300)
        assertTrue("sin fantasma en la cara (diferencia $ghost)", ghost < 3f)
    }

    @Test
    fun noFrames_resultIsTheReference() {
        val clean = TestImages.scene(w, h, seed = 6)
        val ref = TestImages.pixels(clean, sigma, 600)
        val (out, _) = stack(ref, emptyList())
        var maxDiff = 0
        for (i in ref.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(TestImages.luma(out[i]) - TestImages.luma(ref[i])).toInt())
        assertEquals(0, maxDiff)
    }
}
