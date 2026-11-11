package com.hender.fotoanimada

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Color de noche: quitar el morado del sensor sin tocar el brillo ni la luz cálida. */
class BrightnessTest {
    private fun wb(r: Double, g: Double, b: Double, n: Int = 1000) = Brightness.nightWb(r * n, g * n, b * n, n, n)

    private fun luma(w: Brightness.NightWb) = 0.299f * w.r + 0.587f * w.g + 0.114f * w.b

    @Test
    fun neutralScene_needsNoCorrection() {
        assertTrue(wb(0.3, 0.3, 0.3).neutral)
    }

    @Test
    fun magentaCast_getsMoreGreenAndLessBlue_sameBrightness() {
        // Como la foto morada del Redmi a ISO 6000: poco verde y azul de más
        val w = wb(0.125, 0.082, 0.106)
        assertTrue("más verde: ${w.g}", w.g > 1.02f)
        assertTrue("menos azul que verde: ${w.b} vs ${w.g}", w.b < w.g)
        assertEquals("mismo brillo", 1f, luma(w), 0.002f)
    }

    @Test
    fun warmLamp_isRespected() {
        // Luz de lámpara: mucho rojo, verde en medio, poco azul (no es morado)
        val w = wb(0.60, 0.42, 0.20)
        assertTrue("la luz cálida no se toca: ${w.r}/${w.g}/${w.b}", w.neutral)
    }

    @Test
    fun tooFewUsablePixels_doesNothing() {
        assertTrue(Brightness.nightWb(10.0, 5.0, 9.0, 10, 1000).neutral)
    }

    @Test
    fun correctionHasALimit() {
        val w = wb(0.5, 0.05, 0.5)
        assertTrue("tope de verde", w.g <= 1.25f + 0.01f)
    }
}
