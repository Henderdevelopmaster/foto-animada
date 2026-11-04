package com.hender.fotoanimada

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Luna: junta varias fotos alineadas (menos ruido) y afina los detalles. Sin inventar nada. */
object Astro {

    private class Spot(val x: Float, val y: Float)

    /** Centro de lo más brillante (la luna), en píxeles de la foto. */
    private fun centroid(bmp: Bitmap): Spot? {
        val k = 8
        val sw = max(1, bmp.width / k)
        val sh = max(1, bmp.height / k)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bmp) small.recycle()
        var top = 0f
        val l = FloatArray(px.size) {
            val c = px[it]
            val v = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
            top = max(top, v)
            v
        }
        if (top < 0.15f) return null
        val th = max(top * 0.5f, 0.12f)
        var sx = 0f; var sy = 0f; var sum = 0f
        for (i in l.indices) if (l[i] >= th) {
            val wgt = l[i]
            sx += (i % sw + 0.5f) * wgt; sy += (i / sw + 0.5f) * wgt; sum += wgt
        }
        if (sum <= 0f) return null
        return Spot(sx / sum * bmp.width / sw, sy / sum * bmp.height / sh)
    }

    fun stackMoon(shots: List<ByteArray>): Bitmap {
        val base = Processing.decode(shots[0], 8192)
        val w = base.width
        val h = base.height
        val c0 = centroid(base) ?: return base
        val side = min(1600, min(w, h))
        val x0 = (c0.x - side / 2f).roundToInt().coerceIn(0, w - side)
        val y0 = (c0.y - side / 2f).roundToInt().coerceIn(0, h - side)
        val n = side * side
        val ar = FloatArray(n); val ag = FloatArray(n); val ab = FloatArray(n)
        val buf = IntArray(n)
        var used = 0
        for ((i, bytes) in shots.withIndex()) {
            val bmp = if (i == 0) base else try { Processing.decode(bytes, 8192) } catch (_: Throwable) { continue }
            val c = if (i == 0) c0 else centroid(bmp)
            if (c != null && bmp.width == w && bmp.height == h) {
                val dx = (c.x - c0.x).roundToInt()
                val dy = (c.y - c0.y).roundToInt()
                val sx = x0 + dx
                val sy = y0 + dy
                if (sx >= 0 && sy >= 0 && sx + side <= w && sy + side <= h) {
                    bmp.getPixels(buf, 0, side, sx, sy, side, side)
                    for (j in 0 until n) {
                        val p = buf[j]
                        ar[j] += ((p shr 16) and 255).toFloat(); ag[j] += ((p shr 8) and 255).toFloat(); ab[j] += (p and 255).toFloat()
                    }
                    used++
                }
            }
            if (bmp !== base) bmp.recycle()
        }
        if (used == 0) return base
        val inv = 1f / used
        for (j in 0 until n) { ar[j] = ar[j] * inv; ag[j] = ag[j] * inv; ab[j] = ab[j] * inv }
        // Nitidez: máscara de enfoque sobre el promedio (ya sin ruido)
        val lum = FloatArray(n) { 0.299f * ar[it] + 0.587f * ag[it] + 0.114f * ab[it] }
        val blur = box(box(lum, side, 2), side, 2)
        val amount = 1.1f
        for (j in 0 until n) {
            val d = (lum[j] - blur[j]) * amount
            val k = if (lum[j] > 1f) (lum[j] + d) / lum[j] else 1f
            fun ch(v: Float) = (v * k).coerceIn(0f, 255f).toInt()
            buf[j] = (0xFF shl 24) or (ch(ar[j]) shl 16) or (ch(ag[j]) shl 8) or ch(ab[j])
        }
        base.setPixels(buf, 0, side, x0, y0, side, side)
        return base
    }

    private fun box(src: FloatArray, side: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until side) for (x in 0 until side) {
            var a = 0f; var c = 0
            for (k in max(0, x - r)..min(side - 1, x + r)) { a += src[y * side + k]; c++ }
            tmp[y * side + x] = a / c
        }
        for (y in 0 until side) for (x in 0 until side) {
            var a = 0f; var c = 0
            for (k in max(0, y - r)..min(side - 1, y + r)) { a += tmp[k * side + x]; c++ }
            out[y * side + x] = a / c
        }
        return out
    }
}
