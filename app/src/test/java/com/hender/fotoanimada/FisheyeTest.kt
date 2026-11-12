package com.hender.fotoanimada

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Ojo de pez: la geometría (centro, esquinas, el espejo con la foto entera y sin aplastar caras),
 * la calidad (sin moiré donde se comprime, nítido donde se agranda) y el dibujo por franjas.
 * La foto de prueba es vertical 3:4, como la del cel.
 */
class FisheyeTest {
    private val w = 300
    private val h = 400

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun flat(v: Int) = IntArray(w * h) { gray(v) }
    private fun luma(p: Int) = (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)) / 3f

    /** Rayas de 1 píxel (lo más fino que puede haber): blanco y negro alternados por columna. */
    private fun stripes() = IntArray(w * h) { i -> gray(if ((i % w) % 2 == 0) 255 else 0) }

    /** Desvío de la luz en un cuadro de n×n: cuánto "dibujo" queda ahí. */
    private fun spread(px: IntArray, x0: Int, y0: Int, n: Int): Float {
        var s = 0.0
        var s2 = 0.0
        for (y in y0 until y0 + n) for (x in x0 until x0 + n) {
            val l = luma(px[y * w + x]).toDouble()
            s += l
            s2 += l * l
        }
        val m = s / (n * n)
        return sqrt((s2 / (n * n) - m * m).coerceAtLeast(0.0)).toFloat()
    }

    @Test
    fun wide_centerAndCornersStay() {
        val c = Fisheye.sourcePoint(Fisheye.WIDE, w / 2f, h / 2f, w, h)!!
        assertEquals(w / 2f, c.first, 0.01f)
        assertEquals(h / 2f, c.second, 0.01f)
        // Toda la foto cabe: la esquina sigue siendo la esquina
        val corner = Fisheye.sourcePoint(Fisheye.WIDE, 0f, 0f, w, h)!!
        assertEquals(0f, corner.first, 0.5f)
        assertEquals(0f, corner.second, 0.5f)
        // A medio camino viene de más cerca del centro (el centro se agranda) y una línea por el
        // centro sigue derecha
        val mid = Fisheye.sourcePoint(Fisheye.WIDE, w / 2f + 60f, h / 2f, w, h)!!
        assertTrue("el centro se agranda", mid.first - w / 2f < 55f)
        assertEquals(h / 2f, mid.second, 0.01f)
    }

    @Test
    fun wide_flatPhoto_onlyGetsTheVignette() {
        val out = Fisheye.apply(flat(200), w, h, Fisheye.WIDE, 2)
        assertEquals(200f, luma(out[(h / 2) * w + w / 2]), 1f)
        assertEquals(200f * (1f - Fisheye.WIDE_VIGNETTE), luma(out[0]), 3f)
    }

    @Test
    fun mirror_holdsTheWholePhoto_withRoundFaces() {
        val cx = w / 2f
        val cy = h / 2f
        val rc = Fisheye.MIRROR_RADIUS * minOf(w, h)
        // Al borde del espejo llega la orilla de la foto, a lo ancho y a lo alto
        assertEquals(w.toFloat(), Fisheye.sourcePoint(Fisheye.MIRROR, cx + rc * 0.9999f, cy, w, h)!!.first, 1f)
        assertEquals(0f, Fisheye.sourcePoint(Fisheye.MIRROR, cx, cy - rc * 0.9999f, w, h)!!.second, 1f)
        // Fuera del espejo no hay foto: es el fondo
        assertTrue(Fisheye.sourcePoint(Fisheye.MIRROR, 2f, 2f, w, h) == null)
        // Cerca del centro agranda igual a lo ancho que a lo alto, aunque la foto no sea cuadrada
        val dx = Fisheye.sourcePoint(Fisheye.MIRROR, cx + 10f, cy, w, h)!!.first - cx
        val dy = Fisheye.sourcePoint(Fisheye.MIRROR, cx, cy + 10f, w, h)!!.second - cy
        assertEquals(dx, dy, 0.02f * dx)
    }

    @Test
    fun mirror_flatPhoto_sameInTheGlass_darkOutside() {
        val out = Fisheye.apply(flat(200), w, h, Fisheye.MIRROR, 2)
        assertEquals(200f, luma(out[(h / 2) * w + w / 2]), 1f)
        assertTrue("fondo oscuro", luma(out[0]) < 200f * Fisheye.BACK_LIGHT + 2f)
    }

    @Test
    fun compressedEdges_haveNoMoire_centerStaysSharp() {
        val wide = Fisheye.apply(stripes(), w, h, Fisheye.WIDE, 2)
        // Sin filtrar, la esquina daría rayas falsas a todo contraste (desvío ~127)
        val corner = spread(wide, 0, 0, 24)
        assertTrue("esquina sin moiré (desvío $corner)", corner < 35f)
        val center = spread(wide, w / 2 - 12, h / 2 - 12, 24)
        assertTrue("centro nítido (desvío $center)", center > 70f)
        // En el espejo, el borde del vidrio va muy comprimido
        val mirror = Fisheye.apply(stripes(), w, h, Fisheye.MIRROR, 2)
        val rc = Fisheye.MIRROR_RADIUS * minOf(w, h)
        val edge = spread(mirror, (w / 2 + rc * 0.85f).toInt() - 6, h / 2 - 6, 12)
        assertTrue("borde del espejo sin moiré (desvío $edge)", edge < 30f)
    }

    @Test
    fun bands_coverEveryRowOnce_sameResultWithAnyThreads() {
        val src = TestImages.pixels(TestImages.scene(w, h, 7), 3f, 9)
        val before = src.copyOf()
        val rows = IntArray(h)
        Fisheye.render(src, w, h, Fisheye.MIRROR, 3) { y0, n, _ -> for (y in y0 until y0 + n) rows[y]++ }
        assertTrue("cada fila una sola vez", rows.all { it == 1 })
        val one = Fisheye.apply(src, w, h, Fisheye.WIDE, 1)
        val four = Fisheye.apply(src, w, h, Fisheye.WIDE, 4)
        assertTrue("igual con 1 o 4 hilos", one.contentEquals(four))
        // La original no se toca: las franjas pueden ir al mismo bitmap del que salió
        assertTrue("la original queda igual", src.contentEquals(before))
        assertTrue("apagado = la misma foto", Fisheye.apply(src, w, h, Fisheye.OFF).contentEquals(src))
    }
}
