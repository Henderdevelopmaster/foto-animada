package com.hender.fotoanimada

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La mejora: lo que debe cumplirse siempre, pase lo que pase con los ajustes finos. */
class EnhanceCoreTest {
    private val w = 320
    private val h = 240

    private fun mean(px: IntArray, f: (Int) -> Float): Double = px.sumOf { f(it).toDouble() } / px.size

    private fun r(p: Int) = ((p shr 16) and 255).toFloat()
    private fun g(p: Int) = ((p shr 8) and 255).toFloat()
    private fun b(p: Int) = (p and 255).toFloat()

    private fun sat(p: Int): Float {
        val mx = maxOf(r(p), g(p), b(p))
        val mn = minOf(r(p), g(p), b(p))
        return if (mx < 1f) 0f else (mx - mn) / mx
    }

    @Test
    fun autoOffAndVivaOff_leavesMidtonesAsTheyAre() {
        val px = TestImages.pixels(TestImages.scene(w, h, seed = 11, level = 0.7f), 0f, 1)
        val out = px.copyOf()
        EnhanceCore.process(out, w, h, EnhanceCore.Options(strength = 0f, threads = 2))
        var worst = 0f
        for (i in px.indices) {
            if (TestImages.luma(px[i]) > 220f) continue // la curva de salida suaviza solo lo casi blanco
            worst = maxOf(worst, kotlin.math.abs(TestImages.luma(out[i]) - TestImages.luma(px[i])))
        }
        assertTrue("sin cambios en los medios tonos (máx $worst)", worst <= 1.5f)
    }

    @Test
    fun brightPhotoMarkedNight_isNotWashedOut() {
        // Selfie con flash de pantalla: la foto ya salió clara aunque la vista previa era de noche
        val px = TestImages.pixels(TestImages.scene(w, h, seed = 12, level = 1.1f), 2f, 2)
        val out = px.copyOf()
        EnhanceCore.process(out, w, h, EnhanceCore.Options(
            night = true, aim = Brightness.aim(0f), maxGain = Brightness.maxGain(0f), threads = 2,
        ))
        val before = mean(px) { TestImages.luma(it) }
        val after = mean(out) { TestImages.luma(it) }
        assertTrue("no se lava: $before -> $after", after < before * 1.06)
    }

    @Test
    fun nightMagentaCast_isPulledTowardNatural() {
        val s = TestImages.scene(w, h, seed = 13, level = 0.35f)
        for (i in s.g.indices) s.g[i] *= 0.72f // poco verde: morado
        val px = TestImages.pixels(s, 3f, 3)
        val out = px.copyOf()
        val rep = EnhanceCore.process(out, w, h, EnhanceCore.Options(
            night = true, aim = Brightness.aim(0f), maxGain = Brightness.maxGain(0f), threads = 2,
        ))
        fun tint(a: IntArray) = mean(a) { g(it) } / ((mean(a) { r(it) } + mean(a) { b(it) }) / 2)
        assertTrue("más verde: ${tint(px)} -> ${tint(out)} ($rep)", tint(out) > tint(px) + 0.03)
    }

    @Test
    fun dayPhoto_hasNoNightColorCorrection() {
        val s = TestImages.scene(w, h, seed = 14, level = 0.8f)
        for (i in s.g.indices) s.g[i] *= 0.8f
        val out = TestImages.pixels(s, 1f, 4)
        val rep = EnhanceCore.process(out, w, h, EnhanceCore.Options(threads = 2))
        assertEquals(1f, rep.wbG, 0.0001f)
    }

    @Test
    fun viva_addsColorAndContrast() {
        val px = TestImages.pixels(TestImages.scene(w, h, seed = 15, level = 0.8f), 1f, 5)
        val plain = px.copyOf()
        EnhanceCore.process(plain, w, h, EnhanceCore.Options(threads = 2))
        val vivid = px.copyOf()
        EnhanceCore.process(vivid, w, h, EnhanceCore.Options(threads = 2, vivid = 1f))
        fun std(a: IntArray): Double {
            val m = mean(a) { TestImages.luma(it) }
            return kotlin.math.sqrt(a.sumOf { (TestImages.luma(it) - m).let { d -> (d * d).toDouble() } } / a.size)
        }
        assertTrue("más color", mean(vivid) { sat(it) } > mean(plain) { sat(it) } + 0.01)
        assertTrue("más contraste", std(vivid) > std(plain))
    }
}
