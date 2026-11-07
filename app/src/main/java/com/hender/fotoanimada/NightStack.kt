package com.hender.fotoanimada

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Noche de verdad, como el iPhone: varias fotos seguidas con la misma exposición,
 * alineadas y promediadas. Con 4 fotos el grano baja a la mitad y el detalle se queda.
 *
 * La primera foto manda: de las otras solo entra lo que coincide con ella, así lo que se
 * movió (una mano, alguien caminando) no deja fantasmas. Si una foto no alinea bien,
 * casi nada de ella entra y el resultado queda como la primera (nunca peor).
 *
 * Las otras fotos se leen por filas ([Rows]) para no tener todas completas en memoria.
 */
class NightStack(private val ref: IntArray, private val w: Int, private val h: Int, threads: Int = 4) {

    fun interface Rows {
        fun read(y: Int, out: IntArray)
    }

    private val threads = threads.coerceIn(1, 8)
    private val n = w * h
    private val sr = ShortArray(n)
    private val sg = ShortArray(n)
    private val sb = ShortArray(n)
    private val ws = ByteArray(n)
    private val hw = w / 2
    private val hh = h / 2
    private val refHalf: ByteArray

    /** Fotos que sí entraron y cuánto de cada una (0..1), para el registro. */
    val used = ArrayList<Float>()

    init {
        parallel { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = ref[i]
                sr[i] = ((((c shr 16) and 255)) * W).toShort()
                sg[i] = ((((c shr 8) and 255)) * W).toShort()
                sb[i] = (((c and 255)) * W).toShort()
                ws[i] = W.toByte()
            }
        }
        refHalf = halfLuma { y, out -> System.arraycopy(ref, y * w, out, 0, w) }
    }

    /** Suma una foto más. Devuelve cuánto de ella se usó (0..1). */
    fun add(frame: Rows): Float {
        // 1) Alinear: primero a media resolución (rápido), luego afinar el último píxel
        val fh = halfLuma(frame)
        val coarse = Bracket.alignLuma(refHalf, fh, hw, hh)
        val (dx, dy) = refine(frame, coarse.first * 2, coarse.second * 2)

        // 2) Brillo y grano: de una muestra de puntos ya alineados
        var sumR = 0L
        var sumF = 0L
        val buf = IntArray(w)
        var samples = 0
        var y = h / 8
        while (y < h - h / 8) {
            val sy = y + dy
            if (sy in 0 until h) {
                frame.read(sy, buf)
                var x = w / 8
                while (x < w - w / 8) {
                    val sx = x + dx
                    if (sx in 0 until w) {
                        sumR += luma(ref[y * w + x])
                        sumF += luma(buf[sx])
                        samples++
                    }
                    x += 7
                }
            }
            y += 7
        }
        if (samples < 1000) return 0f.also { used += it }
        // La cámara puede variar un poquito el brillo entre fotos: se empareja
        val k = if (sumF < 8L * samples || sumR < 8L * samples) 1f
        else (sumR.toFloat() / sumF).coerceIn(0.85f, 1.18f)
        val kf = (k * 256f).toInt()
        val diff = IntArray(256)
        var dn = 0
        y = h / 8
        while (y < h - h / 8) {
            val sy = y + dy
            if (sy in 0 until h) {
                frame.read(sy, buf)
                var x = w / 8
                while (x < w - w / 8) {
                    val sx = x + dx
                    if (sx in 1 until w - 1) {
                        diff[min(255, distance(ref, y * w + x, buf, sx, kf))]++
                        dn++
                    }
                    x += 7
                }
            }
            y += 7
        }
        // Grano medido como la diferencia típica entre las dos fotos (mediana, robusta)
        val md = median(diff, dn)
        // Por punto solo se descarta lo que cambió mucho (líneas finas que se movieron)
        val thrP = max(8f, 3.5f * 1.4826f * md).toInt()
        val cutP = thrP + max(4, thrP / 2)

        // 3) Qué se movió, por bloques de 8×8: el grano se cancela al promediar y lo que
        //    de verdad cambió (aunque sea del mismo brillo, otro color) se nota entero.
        //    Así no queda moteado (unos puntos mezclados y otros no).
        val bw = (w + B - 1) / B
        val bh = (h + B - 1) / B
        val bd = FloatArray(bw * bh * 3)
        val bc = IntArray(bw * bh)
        val bandRows = (bh + threads - 1) / threads * B
        run {
            val ts = (0 until threads).map { ti ->
                Thread {
                    val row = IntArray(w)
                    val y0 = ti * bandRows
                    val y1 = min(h, y0 + bandRows)
                    for (yy in y0 until y1) {
                        val sy = yy + dy
                        if (sy !in 0 until h) continue
                        frame.read(sy, row)
                        val bo = (yy / B) * bw
                        var i = yy * w
                        for (x in 0 until w) {
                            val sx = x + dx
                            if (sx in 0 until w) {
                                val a = ref[i]
                                val c = row[sx]
                                val k3 = (bo + x / B) * 3
                                bd[k3] += (((a shr 16) and 255) - ((((c shr 16) and 255) * kf) shr 8)).toFloat()
                                bd[k3 + 1] += (((a shr 8) and 255) - ((((c shr 8) and 255) * kf) shr 8)).toFloat()
                                bd[k3 + 2] += ((a and 255) - (((c and 255) * kf) shr 8)).toFloat()
                                bc[bo + x / B]++
                            }
                            i++
                        }
                    }
                }.also { it.start() }
            }
            ts.forEach { it.join() }
        }
        val bm = FloatArray(bw * bh) { b ->
            val c = bc[b]
            if (c < B * B / 4) 999f
            else (abs(bd[b * 3]) + 2f * abs(bd[b * 3 + 1]) + abs(bd[b * 3 + 2])) / (4f * c)
        }
        val sortedB = bm.filter { it < 999f }.sorted()
        val medB = if (sortedB.isEmpty()) 0f else sortedB[sortedB.size / 2]
        val thrB = max(2.5f, 2.5f * 1.4826f * medB)
        val cutB = thrB * 2f + 2f
        // Peso por bloque (1 = igual que la referencia) y se agranda lo que se movió un bloque
        // alrededor, para que los bordes del objeto tampoco se mezclen
        val wb0 = FloatArray(bw * bh) { 1f - smoothstep(thrB, cutB, bm[it]) }
        val wb = FloatArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var m = 1f
            for (oy in -1..1) for (ox in -1..1) {
                val yy = (by + oy).coerceIn(0, bh - 1)
                val xx = (bx + ox).coerceIn(0, bw - 1)
                m = min(m, wb0[yy * bw + xx])
            }
            wb[by * bw + bx] = m
        }

        // 4) Sumar lo que coincide (por filas, en paralelo)
        val got = LongArray(threads)
        parallelIndexed { ti, y0, y1 ->
            val row = IntArray(w)
            val rowW = FloatArray(bw)
            var g = 0L
            for (yy in y0 until y1) {
                val sy = yy + dy
                if (sy !in 0 until h) continue
                frame.read(sy, row)
                // Peso de bloques interpolado (sin escalones)
                val fy = (yy + 0.5f) / B - 0.5f
                val by0 = fy.toInt().coerceIn(0, bh - 1)
                val by1 = min(bh - 1, by0 + 1)
                val ty = (fy - by0).coerceIn(0f, 1f)
                for (bx in 0 until bw) rowW[bx] = wb[by0 * bw + bx] * (1f - ty) + wb[by1 * bw + bx] * ty
                var i = yy * w
                for (x in 0 until w) {
                    val sx = x + dx
                    if (sx in 1 until w - 1 && x in 1 until w - 1) {
                        val fx = (x + 0.5f) / B - 0.5f
                        val bx0 = fx.toInt().coerceIn(0, bw - 1)
                        val bx1 = min(bw - 1, bx0 + 1)
                        val tx = (fx - bx0).coerceIn(0f, 1f)
                        val wbl = rowW[bx0] * (1f - tx) + rowW[bx1] * tx
                        if (wbl > 0.02f) {
                            val d = distance(ref, i, row, sx, kf)
                            val gate = when {
                                d <= thrP -> 1f
                                d >= cutP -> 0f
                                else -> (cutP - d).toFloat() / (cutP - thrP)
                            }
                            val wt = (W * wbl * gate + 0.5f).toInt()
                            if (wt > 0) {
                                val c = row[sx]
                                val r = min(255, (((c shr 16) and 255) * kf) shr 8)
                                val gg = min(255, (((c shr 8) and 255) * kf) shr 8)
                                val b = min(255, ((c and 255) * kf) shr 8)
                                sr[i] = (sr[i] + r * wt).toShort()
                                sg[i] = (sg[i] + gg * wt).toShort()
                                sb[i] = (sb[i] + b * wt).toShort()
                                ws[i] = (ws[i] + wt).toByte()
                                g += wt
                            }
                        }
                    }
                    i++
                }
            }
            got[ti] = g
        }
        val share = got.sum().toFloat() / (n.toFloat() * W)
        used += share
        return share
    }

    /** El resultado (queda escrito encima de la foto de referencia). */
    fun result(): IntArray {
        parallel { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val q = ws[i].toInt()
                val hq = q / 2
                ref[i] = (0xFF shl 24) or
                    (((sr[i].toInt() + hq) / q).coerceAtMost(255) shl 16) or
                    (((sg[i].toInt() + hq) / q).coerceAtMost(255) shl 8) or
                    ((sb[i].toInt() + hq) / q).coerceAtMost(255)
            }
        }
        return ref
    }

    // ------------------------------------------------------------------

    /** Afina ±1 px a resolución completa, comparando una ventana central. */
    private fun refine(frame: Rows, dx0: Int, dy0: Int): Pair<Int, Int> {
        val x0 = w / 4
        val x1 = w - w / 4
        val rowsF = arrayOf(IntArray(w), IntArray(w), IntArray(w))
        val err = LongArray(9)
        var y = h / 4
        while (y < h - h / 4) {
            for (oy in -1..1) {
                val sy = y + dy0 + oy
                if (sy in 0 until h) frame.read(sy, rowsF[oy + 1]) else rowsF[oy + 1].fill(0)
            }
            var x = x0
            while (x < x1) {
                val lr = luma(ref[y * w + x])
                for (oy in -1..1) for (ox in -1..1) {
                    val sx = x + dx0 + ox
                    val lf = if (sx in 0 until w) luma(rowsF[oy + 1][sx]) else 0
                    err[(oy + 1) * 3 + (ox + 1)] += abs(lf - lr).toLong()
                }
                x += 2
            }
            y += 3
        }
        var best = 4
        for (i in 0 until 9) if (err[i] < err[best]) best = i
        return (dx0 + best % 3 - 1) to (dy0 + best / 3 - 1)
    }

    private fun halfLuma(rows: Rows): ByteArray {
        val out = ByteArray(hw * hh)
        val a = IntArray(w)
        val b = IntArray(w)
        for (y in 0 until hh) {
            rows.read(2 * y, a)
            rows.read(2 * y + 1, b)
            var o = y * hw
            for (x in 0 until hw) {
                val s = luma(a[2 * x]) + luma(a[2 * x + 1]) + luma(b[2 * x]) + luma(b[2 * x + 1])
                out[o++] = (s shr 2).toByte()
            }
        }
        return out
    }

    private fun median(hist: IntArray, total: Int): Int {
        var acc = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc * 2 >= total) return v
        }
        return 255
    }

    private inline fun parallel(crossinline body: (Int, Int) -> Unit) = parallelIndexed { _, y0, y1 -> body(y0, y1) }

    private inline fun parallelIndexed(crossinline body: (Int, Int, Int) -> Unit) {
        val rows = (h + threads - 1) / threads
        (0 until threads).map { ti ->
            Thread { body(ti, ti * rows, min(h, ti * rows + rows)) }.also { it.start() }
        }.forEach { it.join() }
    }

    private companion object {
        /** Peso de la foto de referencia (las otras van de 0 a esto). */
        const val W = 8

        /** Lado de los bloques para decidir qué se movió. */
        const val B = 8

        fun smoothstep(a: Float, b: Float, v: Float): Float {
            val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun luma(c: Int): Int = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 151 + (c and 255) * 28) shr 8

        /**
         * Qué tan distinto es el punto de las dos fotos: en los tres colores y promediando con los
         * vecinos de la fila (así el grano pesa menos y lo que de verdad cambió se nota).
         */
        fun distance(a: IntArray, ia: Int, b: IntArray, ib: Int, kf: Int): Int {
            var dr = 0
            var dg = 0
            var db = 0
            for (o in -1..1) {
                val ca = a[ia + o]
                val cb = b[ib + o]
                dr += ((ca shr 16) and 255) - ((((cb shr 16) and 255) * kf) shr 8)
                dg += ((ca shr 8) and 255) - ((((cb shr 8) and 255) * kf) shr 8)
                db += (ca and 255) - (((cb and 255) * kf) shr 8)
            }
            return (abs(dr) + 2 * abs(dg) + abs(db)) / 12
        }
    }
}
