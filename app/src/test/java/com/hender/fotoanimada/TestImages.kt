package com.hender.fotoanimada

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Imágenes sintéticas para las pruebas (sin archivos): escenas con textura, ruido y movimiento. */
internal object TestImages {

    /** Escena con degradados, rayas y manchas de color, en 0..1 por canal (planos r, g, b). */
    class Scene(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray)

    fun scene(w: Int, h: Int, seed: Long, level: Float = 0.35f): Scene {
        val rnd = Random(seed)
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val fx = DoubleArray(4) { 2 + rnd.nextInt(9).toDouble() }
        val fy = DoubleArray(4) { 2 + rnd.nextInt(9).toDouble() }
        for (y in 0 until h) for (x in 0 until w) {
            val u = x.toDouble() / w
            val v = y.toDouble() / h
            var t = 0.5
            for (k in 0 until 4) t += 0.12 * sin(2 * PI * (fx[k] * u + fy[k] * v + k * 0.37))
            val i = y * w + x
            r[i] = (t * (0.9 + 0.2 * u)).toFloat()
            g[i] = (t * 0.95).toFloat()
            b[i] = (t * (1.1 - 0.2 * v)).toFloat()
        }
        // Manchas de color con bordes (para que haya qué alinear)
        repeat(40) {
            val cw = 8 + rnd.nextInt(w / 6)
            val ch = 8 + rnd.nextInt(h / 6)
            val x0 = rnd.nextInt(w - cw)
            val y0 = rnd.nextInt(h - ch)
            val cr = rnd.nextFloat()
            val cg = rnd.nextFloat()
            val cb = rnd.nextFloat()
            for (y in y0 until y0 + ch) for (x in x0 until x0 + cw) {
                val i = y * w + x
                r[i] = cr; g[i] = cg; b[i] = cb
            }
        }
        for (i in r.indices) {
            r[i] = (r[i] * level).coerceIn(0f, 1f)
            g[i] = (g[i] * level).coerceIn(0f, 1f)
            b[i] = (b[i] * level).coerceIn(0f, 1f)
        }
        return Scene(w, h, r, g, b)
    }

    /** Recorte de [s] corrido (dx, dy): el cel se movió. */
    fun crop(s: Scene, x0: Int, y0: Int, w: Int, h: Int): Scene {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y0 + y) * s.w + (x0 + x)
            val o = y * w + x
            r[o] = s.r[i]; g[o] = s.g[i]; b[o] = s.b[i]
        }
        return Scene(w, h, r, g, b)
    }

    /** Copia de [s] con el rectángulo [x0, x1) × [y0, y1) tomado corrido (dx, dy): algo se movió. */
    fun moveRegion(s: Scene, x0: Int, y0: Int, x1: Int, y1: Int, dx: Int, dy: Int): Scene {
        val r = s.r.copyOf()
        val g = s.g.copyOf()
        val b = s.b.copyOf()
        for (y in y0 until y1) for (x in x0 until x1) {
            val sx = (x - dx).coerceIn(0, s.w - 1)
            val sy = (y - dy).coerceIn(0, s.h - 1)
            val i = sy * s.w + sx
            val o = y * s.w + x
            r[o] = s.r[i]; g[o] = s.g[i]; b[o] = s.b[i]
        }
        return Scene(s.w, s.h, r, g, b)
    }

    /** A píxeles ARGB con grano (sigma en niveles de 0..255), como sale de la cámara. */
    fun pixels(s: Scene, sigma: Float, seed: Long): IntArray {
        val rnd = Random(seed)
        return IntArray(s.w * s.h) {
            fun ch(v: Float) = (v * 255f + rnd.nextGaussian().toFloat() * sigma + 0.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(s.r[it]) shl 16) or (ch(s.g[it]) shl 8) or ch(s.b[it])
        }
    }

    fun luma(p: Int): Float =
        0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)

    /** Error medio (por canal, en niveles) de [px] contra la escena limpia. */
    fun rms(px: IntArray, s: Scene): Double {
        var acc = 0.0
        for (i in px.indices) {
            val p = px[i]
            val dr = ((p shr 16) and 255) - s.r[i] * 255f
            val dg = ((p shr 8) and 255) - s.g[i] * 255f
            val db = (p and 255) - s.b[i] * 255f
            acc += (dr * dr + dg * dg + db * db).toDouble()
        }
        return sqrt(acc / (px.size * 3))
    }

    /** Diferencia de luz entre dos imágenes promediada en bloques de [k]×[k] (lo que se ve, sin grano). */
    fun blockedDiff(a: IntArray, b: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, k: Int = 8): Float {
        var worst = 0f
        var y = y0
        while (y + k <= y1) {
            var x = x0
            while (x + k <= x1) {
                var sa = 0f
                var sb = 0f
                for (yy in y until y + k) for (xx in x until x + k) {
                    sa += luma(a[yy * w + xx]); sb += luma(b[yy * w + xx])
                }
                worst = maxOf(worst, kotlin.math.abs(sa - sb) / (k * k))
                x += k
            }
            y += k
        }
        return worst
    }
}
