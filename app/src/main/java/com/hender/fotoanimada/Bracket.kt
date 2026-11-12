package com.hender.fotoanimada

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Junta varias fotos de la misma escena tomadas con distinta exposición
 * (una oscura, una normal y una clara) en una sola con todo el rango.
 *
 * Lo usa el modo ATARDECER: el cielo sale de la foto oscura y la tierra de la clara.
 * No usa nada de Android, para poder probarlo aparte.
 *
 * Pasos: alinear (el pulso mueve el cel), pasar a luz lineal, medir cuánto más
 * clara es cada toma, promediar dándole más peso a la que mejor expuso cada
 * pedazo, y volver a 8 bits con las luces comprimidas.
 */
object Bracket {

    class Report(val shifts: List<Pair<Int, Int>>, val scales: List<Float>, val range: Float) {
        override fun toString() = "shifts=$shifts scales=${scales.map { String.format(java.util.Locale.US, "%.2f", it) }} " +
            "range=${String.format(java.util.Locale.US, "%.1f", range)}x"
    }

    /** Índices en orden de captura: centro, oscuro y claro. */
    fun hdrExposureIndices(center: Int, step: Float, lower: Int, upper: Int): List<Int>? {
        if (!step.isFinite() || step <= 0f || center !in lower..upper) return null
        val margin = minOf(center.toLong() - lower, upper.toLong() - center)
        val delta = (1.5f / step).roundToInt().coerceAtLeast(1).toLong().coerceAtMost(margin)
        if (delta * step < 0.75f) return null
        return listOf(center, (center - delta).toInt(), (center + delta).toInt())
    }

    /** [frames] en orden oscuro, centro, claro. Devuelve null si es más seguro guardar la toma central. */
    fun fuseHdr(frames: List<IntArray>, w: Int, h: Int, threads: Int): Pair<IntArray, Report>? {
        if (frames.size != 3 || w <= 0 || h <= 0 || threads <= 0 ||
            frames.any { it.size.toLong() != w.toLong() * h }
        ) return null
        return fuseBracket(frames, w, h, threads, rejectMotion = true)
    }

    /** [frames] en ARGB, del mismo tamaño, de la más oscura a la más clara. */
    fun fuse(frames: List<IntArray>, w: Int, h: Int, threads: Int = 4): Pair<IntArray, Report> =
        checkNotNull(fuseBracket(frames, w, h, threads, rejectMotion = false))

    private fun fuseBracket(
        frames: List<IntArray>,
        w: Int,
        h: Int,
        threads: Int,
        rejectMotion: Boolean,
    ): Pair<IntArray, Report>? {
        require(frames.isNotEmpty()) { "Sin fotos para juntar" }
        val n = frames.size
        if (n == 1) return frames[0].copyOf() to Report(listOf(0 to 0), listOf(1f), 1f)
        val ref = n / 2

        // 1) Alinear cada toma con la del medio (el pulso mueve el cel entre fotos)
        val lumas = frames.map { luma(it, w, h) }
        val shifts = ArrayList<Pair<Int, Int>>(n)
        for (i in 0 until n) shifts += if (i == ref) 0 to 0 else align(lumas[ref], lumas[i], w, h)

        // Tablas: 8 bits -> luz lineal, y cuánto pesa cada valor
        val lin = FloatArray(256) { toLinear(it) }
        val wgt = FloatArray(256) { weight(it / 255f) + 1e-3f }
        val channels = intArrayOf(16, 8, 0)

        fun at(i: Int, x: Int, y: Int): Int {
            val sx = (x + shifts[i].first).coerceIn(0, w - 1)
            val sy = (y + shifts[i].second).coerceIn(0, h - 1)
            return frames[i][sy * w + sx]
        }

        // 2) Cuánto más clara es cada toma que la del medio (mediana de las razones)
        val scales = FloatArray(n) { 1f }
        val stepX = max(1, w / 220)
        val stepY = max(1, h / 220)
        for (i in 0 until n) {
            if (i == ref) continue
            val ratios = ArrayList<Float>()
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val ca = at(ref, x, y)
                    val cb = at(i, x, y)
                    for (sh in channels) {
                        val va = (ca shr sh) and 255
                        val vb = (cb shr sh) and 255
                        if (va in 12..232 && vb in 12..232) ratios.add(lin[vb] / lin[va])
                    }
                    x += stepX
                }
                y += stepY
            }
            if (rejectMotion && ratios.size <= 50) return null
            scales[i] = if (ratios.size > 50) {
                ratios.sort()
                ratios[ratios.size / 2].coerceIn(0.05f, 20f)
            } else if (i < ref) 0.5f else 2f
        }

        if (rejectMotion) {
            if (scales[0] >= 0.9f || scales[2] <= 1.1f) return null
            for (i in frames.indices) {
                if (i == ref) continue
                val (dx, dy) = shifts[i]
                if (abs(dx) > w / 25 || abs(dy) > h / 25 ||
                    hasLocalMotion(frames[ref], frames[i], w, h, dx, dy, scales[i], lin, channels)
                ) return null
            }
        }

        /** Radiancia de un píxel: promedio ponderado de las tomas, ya sin su exposición. */
        fun radiance(x: Int, y: Int, out: FloatArray) {
            var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
            for (i in 0 until n) {
                val c = at(i, x, y)
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val k = wgt[max(r, max(g, b))]
                val inv = k / scales[i]
                sr += inv * lin[r]; sg += inv * lin[g]; sb += inv * lin[b]
                sw += k
            }
            out[0] = sr / sw; out[1] = sg / sw; out[2] = sb / sw
        }

        // 3) Hasta dónde llegan las luces (con una muestra basta)
        val tmp = FloatArray(3)
        val sample = ArrayList<Float>()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                radiance(x, y, tmp)
                sample.add(max(tmp[0], max(tmp[1], tmp[2])))
                x += stepX
            }
            y += stepY
        }
        sample.sort()
        val range = max(1f, sample[(sample.size * 0.999f).toInt().coerceAtMost(sample.size - 1)])

        // 4) De vuelta a 8 bits, comprimiendo solo las luces altas
        val out = IntArray(w * h)
        val rows = (h + threads - 1) / threads
        (0 until threads).map { t ->
            Thread {
                val px3 = FloatArray(3)
                val y0 = t * rows
                val y1 = min(h, y0 + rows)
                for (yy in y0 until y1) {
                    var o = yy * w
                    for (xx in 0 until w) {
                        radiance(xx, yy, px3)
                        val mx = max(px3[0], max(px3[1], px3[2]))
                        val k = if (mx > 1e-5f) shoulder(mx, range) / mx else 1f
                        out[o++] = (0xFF shl 24) or
                            (to8(px3[0] * k) shl 16) or (to8(px3[1] * k) shl 8) or to8(px3[2] * k)
                    }
                }
            }.also { it.start() }
        }.forEach { it.join() }
        return out to Report(shifts.toList(), scales.toList(), range)
    }

    /** Compara muestras por zona después de alinear y compensar la exposición. */
    private fun hasLocalMotion(
        reference: IntArray,
        other: IntArray,
        w: Int,
        h: Int,
        dx: Int,
        dy: Int,
        scale: Float,
        linear: FloatArray,
        channels: IntArray,
    ): Boolean {
        val tileWidth = max(4, (w + 19) / 20)
        val tileHeight = max(4, (h + 19) / 20)
        val sampleX = max(1, tileWidth / 8)
        val sampleY = max(1, tileHeight / 8)
        for (top in 0 until h step tileHeight) for (left in 0 until w step tileWidth) {
            var samples = 0
            var mismatches = 0
            for (y in top until minOf(top + tileHeight, h) step sampleY) {
                if (y + dy !in 0 until h) continue
                for (x in left until minOf(left + tileWidth, w) step sampleX) {
                    if (x + dx !in 0 until w) continue
                    val a = reference[y * w + x]
                    val b = other[(y + dy) * w + x + dx]
                    samples++
                    for (shift in channels) {
                        val va = (a shr shift) and 255
                        val vb = (b shr shift) and 255
                        val expected = (linear[va] * scale).coerceAtMost(1f)
                        val actual = linear[vb]
                        // Un blanco recortado solo indica un mínimo, no su brillo exacto.
                        if ((va >= 250 && actual >= expected) || (vb >= 250 && expected >= actual)) continue
                        val tolerance = max(0.045f, max(expected, actual) * 0.24f)
                        if (abs(expected - actual) > tolerance) {
                            mismatches++
                            break
                        }
                    }
                }
            }
            if (mismatches >= 6 && mismatches * 5 >= samples * 2) return true
        }
        return false
    }

    // ------------------------------------------------------------------ curvas

    /** Peso: máximo en los tonos medios, casi cero en lo negro y lo quemado. */
    private fun weight(v: Float): Float {
        val s = srgb(v)
        return when {
            s <= 0.02f || s >= 0.98f -> 0f
            else -> {
                val t = (s - 0.5f) / 0.42f
                val e = kotlin.math.exp(-t * t * 2f)
                e * e
            }
        }
    }

    /**
     * Hombro suave: hasta 1 no cambia nada, y de ahí para arriba se comprime
     * hasta [range] para que quepa en el blanco sin quemarse.
     */
    private fun shoulder(v: Float, range: Float): Float {
        if (v <= 0.5f) return v
        val top = 1.02f
        val k = (top - 0.5f)
        val x = (v - 0.5f) / (range - 0.5f).coerceAtLeast(0.01f)
        return 0.5f + k * (1f - kotlin.math.exp(-3f * x)) / (1f - kotlin.math.exp(-3f))
    }

    private fun toLinear(v8: Int): Float {
        val s = v8 / 255f
        return if (s <= 0.04045f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    private fun srgb(l: Float): Float {
        val v = l.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else (1.055f * Math.pow(v.toDouble(), 1 / 2.4).toFloat() - 0.055f)
    }

    private fun to8(l: Float) = (srgb(l) * 255f + 0.5f).toInt().coerceIn(0, 255)

    // --------------------------------------------------------------- alineado

    private fun luma(px: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            val c = px[i]
            out[i] = ((((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 151 + (c and 255) * 28) shr 8).toByte()
        }
        return out
    }

    private fun half(src: ByteArray, w: Int, h: Int): ByteArray {
        val nw = w / 2
        val nh = h / 2
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val i = (y * 2) * w + x * 2
            val s = (src[i].toInt() and 255) + (src[i + 1].toInt() and 255) +
                (src[i + w].toInt() and 255) + (src[i + w + 1].toInt() and 255)
            out[y * nw + x] = (s shr 2).toByte()
        }
        return out
    }

    /** Mapa de "más claro o más oscuro que la mediana": no depende de la exposición. */
    private fun bitmaps(src: ByteArray): Pair<BooleanArray, BooleanArray> {
        val hist = IntArray(256)
        for (b in src) hist[b.toInt() and 255]++
        var acc = 0
        var med = 128
        val half = src.size / 2
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc >= half) { med = v; break }
        }
        val on = BooleanArray(src.size)
        val use = BooleanArray(src.size)
        for (i in src.indices) {
            val v = src[i].toInt() and 255
            on[i] = v > med
            use[i] = abs(v - med) > 4
        }
        return on to use
    }

    /** Desplazamiento (dx, dy) que mejor hace coincidir [b] con [a]. */
    private fun align(a: ByteArray, b: ByteArray, w: Int, h: Int, maxLevels: Int = 6): Pair<Int, Int> {
        val pa = ArrayList<Triple<ByteArray, Int, Int>>()
        val pb = ArrayList<Triple<ByteArray, Int, Int>>()
        var ca = a; var cb = b; var cw = w; var ch = h
        pa += Triple(ca, cw, ch); pb += Triple(cb, cw, ch)
        while (pa.size < maxLevels && cw > 40 && ch > 40) {
            ca = half(ca, cw, ch); cb = half(cb, cw, ch)
            cw /= 2; ch /= 2
            pa += Triple(ca, cw, ch); pb += Triple(cb, cw, ch)
        }
        var dx = 0
        var dy = 0
        for (lvl in pa.indices.reversed()) {
            val (ia, lw, lh) = pa[lvl]
            val (ib, _, _) = pb[lvl]
            val (aon, ause) = bitmaps(ia)
            val (bon, buse) = bitmaps(ib)
            dx *= 2; dy *= 2
            var best = Int.MAX_VALUE
            var bx = dx
            var by = dy
            for (oy in dy - 1..dy + 1) for (ox in dx - 1..dx + 1) {
                var err = 0
                var y = 1
                while (y < lh - 1) {
                    val sy = y + oy
                    if (sy in 0 until lh) {
                        var x = 1
                        while (x < lw - 1) {
                            val sx = x + ox
                            if (sx in 0 until lw) {
                                val i = y * lw + x
                                val j = sy * lw + sx
                                if (ause[i] && buse[j] && aon[i] != bon[j]) err++
                            }
                            x++
                        }
                    }
                    y++
                }
                if (err < best) { best = err; bx = ox; by = oy }
            }
            dx = bx; dy = by
        }
        // Un salto enorme casi siempre es un error de medida: mejor no mover nada
        val limit = max(w, h) / 12
        if (abs(dx) > limit || abs(dy) > limit) return 0 to 0
        return dx to dy
    }

    /** Igual que [shiftOf], sobre mapas de luz ya hechos (para no copiar fotos enteras). */
    fun alignLuma(a: ByteArray, b: ByteArray, w: Int, h: Int): Pair<Int, Int> = align(a, b, w, h)

    /** Desplazamiento entre dos fotos ya con la misma exposición (para apilar). */
    fun shiftOf(a: IntArray, b: IntArray, w: Int, h: Int): Pair<Int, Int> =
        align(luma(a, w, h), luma(b, w, h), w, h)

    /** Promedia varias tomas ya alineadas: baja el ruido. */
    fun stack(frames: List<IntArray>, shifts: List<Pair<Int, Int>>, w: Int, h: Int): IntArray {
        val n = frames.size
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (i in 0 until n) {
                val sx = (x + shifts[i].first).coerceIn(0, w - 1)
                val sy = (y + shifts[i].second).coerceIn(0, h - 1)
                val c = frames[i][sy * w + sx]
                r += (c shr 16) and 255
                g += (c shr 8) and 255
                b += c and 255
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }

    @Suppress("unused")
    private fun round(v: Float) = v.roundToInt()
}
