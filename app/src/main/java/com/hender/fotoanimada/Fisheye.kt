package com.hender.fotoanimada

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Ojo de pez (Kotlin puro, se prueba sin cel). Dos estilos:
 * - [WIDE] "Gran angular": toda la foto curvada como una cámara de acción. No inventa lo que la
 *   cámara no vio: el centro se acerca un poco y los bordes se comprimen, y así cabe la sensación
 *   de un lente 0.6.
 * - [MIRROR] "Espejo de tienda": la foto entera dentro de un espejo redondo y abombado, con su
 *   aro, el brillo del vidrio y el fondo oscuro y borroso.
 *
 * La vista previa usa la misma cuenta en un shader (InstantFx.LENS), así lo que se ve es lo que
 * se guarda. Calidad: donde la foto se agranda (el centro) cada color sale de una interpolación
 * cúbica, para que no quede blando; donde se comprime (los bordes), de versiones reducidas según
 * cuánto se comprime cada zona (filtrado trilineal, como una GPU), sin dientes ni moiré.
 * Memoria: no copia la foto; además de ella usa un tercio de su tamaño (las versiones reducidas)
 * y una franja de filas por hilo.
 */
object Fisheye {
    const val OFF = 0
    const val WIDE = 1
    const val MIRROR = 2

    /** Fuerza de la curva (tangente del medio ángulo "virtual"). Más = más ojo de pez. */
    const val WIDE_C = 1.35f
    const val MIRROR_C = 2.2f

    /** Radio del espejo respecto al lado corto, y ancho del aro respecto al radio. */
    const val MIRROR_RADIUS = 0.455f
    const val RIM = 0.05f

    /** Cuánto se oscurece el borde en gran angular (como un lente de verdad). */
    const val WIDE_VIGNETTE = 0.16f

    /** Fondo del espejo: cuánta luz conserva y cuánto se desenfoca (respecto al lado corto). */
    const val BACK_LIGHT = 0.32f
    const val BACK_BLUR = 0.0275f

    /** Filas por franja: cada hilo dibuja una y la entrega. */
    private const val BAND = 32

    private const val LUT = 2048

    /** Curva del ojo de pez: radio de salida (0..1) -> radio de la foto original (0..1). */
    private fun curve(c: Float): FloatArray {
        val ac = atan(c.toDouble())
        return FloatArray(LUT + 1) { i -> (tan(i.toDouble() / LUT * ac) / c).toFloat() }
    }

    /** Derivada de la curva (cuánto se comprime a lo largo del radio). */
    private fun slope(c: Float): FloatArray {
        val ac = atan(c.toDouble())
        return FloatArray(LUT + 1) { i ->
            val t = tan(i.toDouble() / LUT * ac)
            (ac * (1 + t * t) / c).toFloat()
        }
    }

    /**
     * Tabla del gran angular sobre q = rho² (0..1), de a 3 valores: cuánto se acerca (rin/rho),
     * el nivel de detalle y la viñeta. Con rho² no hace falta raíz ni logaritmo por píxel.
     */
    private fun wideTable(c: Float): FloatArray {
        val ac = atan(c.toDouble())
        val tab = FloatArray((LUT + 1) * 3)
        for (i in 0..LUT) {
            val q = i.toDouble() / LUT
            val rho = sqrt(q)
            val t = tan(rho * ac)
            val k = if (rho > 1e-6) t / c / rho else ac / c
            val scale = max(ac * (1 + t * t) / c, k)
            tab[i * 3] = k.toFloat()
            tab[i * 3 + 1] = if (scale <= 1.0) 0f else min(3.0, ln(scale) / ln(2.0)).toFloat()
            tab[i * 3 + 2] = (1 - WIDE_VIGNETTE * q * sqrt(rho)).toFloat()
        }
        return tab
    }

    private fun lut(a: FloatArray, rho: Float): Float {
        val f = rho.coerceIn(0f, 1f) * LUT
        val i = min(f.toInt(), LUT - 1)
        val t = f - i
        return a[i] * (1f - t) + a[i + 1] * t
    }

    /**
     * Para un punto de la imagen con ojo de pez (x, y), el punto de la foto original. Sirve para
     * enfocar donde se toca: lo que se ve en ese punto está en otro lugar del sensor.
     * Devuelve null si el punto está fuera del espejo.
     */
    fun sourcePoint(style: Int, x: Float, y: Float, w: Int, h: Int): Pair<Float, Float>? {
        if (style == OFF) return x to y
        val cx = w * 0.5f
        val cy = h * 0.5f
        val dx = x - cx
        val dy = y - cy
        val r = sqrt(dx * dx + dy * dy)
        if (r < 1e-3f) return cx to cy
        val ux = dx / r
        val uy = dy / r
        return if (style == WIDE) {
            val rr = sqrt(cx * cx + cy * cy)
            val rin = (tan((r / rr).coerceAtMost(1f) * atan(WIDE_C)) / WIDE_C) * rr
            cx + ux * rin to cy + uy * rin
        } else {
            val rho = r / (MIRROR_RADIUS * min(w, h))
            if (rho > 1f) return null
            val rin = (tan(rho * atan(MIRROR_C)) / MIRROR_C) * span(rho, reach(ux, uy, cx, cy), min(cx, cy))
            cx + ux * rin to cy + uy * rin
        }
    }

    /**
     * Hasta dónde llega la foto en la dirección (ux, uy) desde el centro: un rectángulo de
     * medio ancho [cx] y medio alto [cy] con las puntas redondeadas (superelipse de grado 4).
     * Con el rectángulo exacto quedaba un pliegue en las diagonales; así la curva es suave.
     */
    fun reach(ux: Float, uy: Float, cx: Float, cy: Float): Float {
        val a = abs(ux) / cx
        val b = abs(uy) / cy
        val a2 = a * a
        val b2 = b * b
        return 1f / sqrt(sqrt(a2 * a2 + b2 * b2)).coerceAtLeast(1e-6f)
    }

    /**
     * Cuánto de la foto cabe hasta el radio [rho] del espejo en una dirección: en el centro lo
     * mismo para todos lados (las caras no se aplastan aunque la foto no sea cuadrada) y en el
     * borde hasta la orilla de la foto ([rmax]), así se ve la foto entera.
     */
    private fun span(rho: Float, rmax: Float, r0: Float): Float = r0 + (rmax - r0) * rho * rho

    // ---------------------------------------------------------------- foto

    /** Recibe cada franja terminada: filas [y0] a [y0] + [rows], [px] con el ancho de la foto. */
    fun interface Sink {
        fun put(y0: Int, rows: Int, px: IntArray)
    }

    /** Aplica el estilo a [src] (ARGB, w×h) y devuelve una imagen nueva del mismo tamaño. */
    fun apply(src: IntArray, w: Int, h: Int, style: Int, threads: Int = 4): IntArray {
        if (style == OFF) return src.copyOf()
        val out = IntArray(w * h)
        render(src, w, h, style, threads) { y0, rows, px -> System.arraycopy(px, 0, out, y0 * w, rows * w) }
        return out
    }

    /**
     * Dibuja el estilo por franjas y se las pasa a [sink], una a la vez. [src] no se modifica, así
     * que las franjas pueden ir al mismo bitmap del que salió. Toda la memoria se pide antes de
     * entregar la primera franja: si falta, falla sin haber tocado nada.
     */
    fun render(src: IntArray, w: Int, h: Int, style: Int, threads: Int, sink: Sink) {
        require(style == WIDE || style == MIRROR) { "Estilo $style" }
        require(w > 0 && h > 0 && src.size >= w * h) { "Tamaño ${w}x$h" }
        val n = max(1, threads)
        val pool = Executors.newFixedThreadPool(n)
        try {
            val l0 = Level(src, w, h)
            val l1 = l0.half(pool, n)
            val l2 = l1.half(pool, n)
            val l3 = l2.half(pool, n)
            val levels = arrayOf(l0, l1, l2, l3)
            val back = if (style == MIRROR) Backdrop(l3, w, h) else null
            val wt = if (style == WIDE) wideTable(WIDE_C) else null
            val cv = curve(MIRROR_C)
            val sl = slope(MIRROR_C)
            val rows = min(BAND, h)
            val bufs = Array(n) { IntArray(w * rows) }
            val bands = (h + rows - 1) / rows
            val next = AtomicInteger()
            val lock = Any()
            parallel(pool, n) { worker ->
                val buf = bufs[worker]
                val rgb = FloatArray(3)
                while (true) {
                    val b = next.getAndIncrement()
                    if (b >= bands) break
                    val y0 = b * rows
                    val y1 = min(h, y0 + rows)
                    for (y in y0 until y1) {
                        val o = (y - y0) * w
                        if (wt != null) wideRow(y, w, h, levels, wt, buf, o, rgb)
                        else mirrorRow(y, w, h, levels, cv, sl, back!!, buf, o, rgb)
                    }
                    synchronized(lock) { sink.put(y0, y1 - y0, buf) }
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    /** Corre [task] en [n] hilos y espera a todos; si uno falla, lanza su error. */
    private fun parallel(pool: ExecutorService, n: Int, task: (Int) -> Unit) {
        val futures = pool.invokeAll(List(n) { i -> Callable { task(i) } })
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private fun wideRow(
        y: Int, w: Int, h: Int, lv: Array<Level>, tab: FloatArray, out: IntArray, o: Int, rgb: FloatArray,
    ) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val inv = LUT / (cx * cx + cy * cy)
        val fy = y + 0.5f - cy
        val fy2 = fy * fy
        for (x in 0 until w) {
            val fx = x + 0.5f - cx
            val f = min((fx * fx + fy2) * inv, LUT.toFloat())
            val i = min(f.toInt(), LUT - 1)
            val t = f - i
            val j = i * 3
            // Cuánto se acerca ese punto, con qué detalle se toma y cuánto se oscurece
            val k = tab[j] + (tab[j + 3] - tab[j]) * t
            val lod = tab[j + 1] + (tab[j + 4] - tab[j + 1]) * t
            val v = tab[j + 2] + (tab[j + 5] - tab[j + 2]) * t
            sample(lv, cx + fx * k, cy + fy * k, lod, rgb)
            out[o + x] = pack(rgb[0] * v, rgb[1] * v, rgb[2] * v)
        }
    }

    private fun mirrorRow(
        y: Int, w: Int, h: Int, lv: Array<Level>, cv: FloatArray, sl: FloatArray, back: Backdrop,
        out: IntArray, o: Int, rgb: FloatArray,
    ) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r0 = min(cx, cy)
        val rc = MIRROR_RADIUS * min(w, h)
        val rim = RIM * rc
        val aa = max(1.2f, rc / 500f) // borde suave de 1 a 2 píxeles
        val fy = y + 0.5f - cy
        for (x in 0 until w) {
            val fx = x + 0.5f - cx
            val r = sqrt(fx * fx + fy * fy)
            val ringOut = 1f - smooth(rc + rim - aa, rc + rim + aa, r) // 1 = espejo, 0 = fondo
            var cr = 0f
            var cg = 0f
            var cb = 0f
            if (ringOut > 0f) {
                // Aro metálico: claro arriba a la izquierda, oscuro abajo a la derecha
                val ang = (-(fx + fy) / max(r, 1f)) * 0.7071f // -1..1
                val bevel = 1f - abs((r - rc - rim * 0.5f) / (rim * 0.5f)).coerceIn(0f, 1f) // brillo al medio
                val m = (0.42f + 0.30f * ang + 0.18f * bevel) * 255f
                if (r < rc + aa) {
                    // Vidrio: la foto entera con ojo de pez
                    val rho = min(r / rc, 1f)
                    val ux = if (r > 1e-3f) fx / r else 1f
                    val uy = if (r > 1e-3f) fy / r else 0f
                    val rmax = reach(ux, uy, cx, cy)
                    val sp = span(rho, rmax, r0)
                    val f = lut(cv, rho)
                    val rin = f * sp
                    val radial = (lut(sl, rho) * sp + f * (rmax - r0) * 2f * rho) / rc
                    val tangential = if (r > 1e-3f) rin / r else radial
                    val scale = max(radial, tangential)
                    sample(lv, cx + ux * rin, cy + uy * rin, if (scale <= 1f) 0f else min(3f, log2(scale)), rgb)
                    // Más oscuro hacia el borde del vidrio y un brillo suave arriba a la izquierda
                    val edge = 1f - 0.22f * smooth(0.72f, 1f, rho)
                    val gl = glare(fx / rc, fy / rc)
                    val ringIn = smooth(rc - aa, rc + aa, r) // 0 dentro del vidrio, 1 en el aro
                    cr = rgb[0] * edge
                    cg = rgb[1] * edge
                    cb = rgb[2] * edge
                    cr += (255f - cr) * gl
                    cg += (255f - cg) * gl
                    cb += (255f - cb) * gl
                    cr += (m - cr) * ringIn
                    cg += (m - cg) * ringIn
                    cb += (m - cb) * ringIn
                } else {
                    cr = m
                    cg = m
                    cb = m
                }
            }
            if (ringOut < 1f) {
                // Fondo: la foto oscura y muy borrosa, con la sombra del espejo debajo
                back.at(x + 0.5f, y + 0.5f, rgb)
                val sh = shadow(fx, fy - rc * 0.035f, rc + rim)
                val br = rgb[0] * sh
                val bg = rgb[1] * sh
                val bb = rgb[2] * sh
                cr = br + (cr - br) * ringOut
                cg = bg + (cg - bg) * ringOut
                cb = bb + (cb - bb) * ringOut
            }
            out[o + x] = pack(cr, cg, cb)
        }
    }

    /** Brillo del vidrio: una media luna suave arriba a la izquierda. */
    private fun glare(nx: Float, ny: Float): Float {
        val dx = nx + 0.32f
        val dy = ny + 0.42f
        return 0.13f * (1f - smooth(0.05f, 0.42f, sqrt(dx * dx * 0.55f + dy * dy * 1.6f)))
    }

    /** Sombra suave del espejo sobre el fondo (1 = sin sombra). */
    private fun shadow(fx: Float, fy: Float, radius: Float): Float =
        1f - 0.55f * (1f - smooth(radius * 0.98f, radius * 1.16f, sqrt(fx * fx + fy * fy)))

    /**
     * Toma un color con el nivel de detalle [lod] (0 = foto completa, 1 = la mitad...), mezclando
     * los dos niveles vecinos (trilineal). La foto completa se lee con cúbica mientras pesa más de
     * un cuarto (nítida donde se agranda); el resto, bilineal.
     */
    private fun sample(lv: Array<Level>, sx: Float, sy: Float, lod: Float, rgb: FloatArray) {
        val i0 = min(lod.toInt(), 3)
        val t = lod - i0
        val f0 = 1f / (1 shl i0)
        if (t > 0.98f && i0 < 3) {
            lv[i0 + 1].bilinear(sx * f0 * 0.5f, sy * f0 * 0.5f, rgb)
            return
        }
        if (i0 == 0 && t < 0.75f) lv[0].cubic(sx, sy, rgb) else lv[i0].bilinear(sx * f0, sy * f0, rgb)
        if (t > 0.02f && i0 < 3) {
            val r0 = rgb[0]
            val g0 = rgb[1]
            val b0 = rgb[2]
            val f1 = f0 * 0.5f
            lv[i0 + 1].bilinear(sx * f1, sy * f1, rgb)
            rgb[0] = r0 + (rgb[0] - r0) * t
            rgb[1] = g0 + (rgb[1] - g0) * t
            rgb[2] = b0 + (rgb[2] - b0) * t
        }
    }

    /** log2 rápido (alcanza para elegir el nivel; error < 0.01). */
    private fun log2(x: Float): Float {
        val bits = x.toRawBits()
        val e = ((bits ushr 23) and 255) - 127
        val m = Float.fromBits((bits and 0x007FFFFF) or 0x3F800000) - 1f
        return e + m * (1.3466f - 0.3466f * m)
    }

    private fun smooth(a: Float, b: Float, v: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)

    private fun clamp8(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

    /** Imagen ARGB empacada (el nivel 0 es la foto misma, sin copiarla). */
    private class Level(val px: IntArray, val w: Int, val h: Int) {

        /** La mitad de tamaño (promedio de 2×2): para las zonas comprimidas. */
        fun half(pool: ExecutorService, n: Int): Level {
            val nw = max(1, w / 2)
            val nh = max(1, h / 2)
            val o = IntArray(nw * nh)
            val chunk = (nh + n - 1) / n
            parallel(pool, n) { part ->
                for (y in part * chunk until min(nh, (part + 1) * chunk)) {
                    val r0 = min(h - 1, y * 2) * w
                    val r1 = min(h - 1, y * 2 + 1) * w
                    for (x in 0 until nw) {
                        val x0 = min(w - 1, x * 2)
                        val x1 = min(w - 1, x * 2 + 1)
                        val a = px[r0 + x0]
                        val b = px[r0 + x1]
                        val c = px[r1 + x0]
                        val d = px[r1 + x1]
                        val rr = (((a shr 16) and 255) + ((b shr 16) and 255) + ((c shr 16) and 255) +
                            ((d shr 16) and 255) + 2) shr 2
                        val gg = (((a shr 8) and 255) + ((b shr 8) and 255) + ((c shr 8) and 255) +
                            ((d shr 8) and 255) + 2) shr 2
                        val bb = ((a and 255) + (b and 255) + (c and 255) + (d and 255) + 2) shr 2
                        o[y * nw + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
            }
            return Level(o, nw, nh)
        }

        /** Color en (x, y) en píxeles de este nivel, con los bordes extendidos. */
        fun bilinear(x: Float, y: Float, out: FloatArray) {
            val fx = (x - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val fy = (y - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = if (x0 + 1 < w) x0 + 1 else x0
            val y1 = if (y0 + 1 < h) y0 + 1 else y0
            val tx = fx - x0
            val ty = fy - y0
            val a = px[y0 * w + x0]
            val b = px[y0 * w + x1]
            val c = px[y1 * w + x0]
            val d = px[y1 * w + x1]
            val wa = (1f - tx) * (1f - ty)
            val wb = tx * (1f - ty)
            val wc = (1f - tx) * ty
            val wd = tx * ty
            out[0] = ((a shr 16) and 255) * wa + ((b shr 16) and 255) * wb + ((c shr 16) and 255) * wc +
                ((d shr 16) and 255) * wd
            out[1] = ((a shr 8) and 255) * wa + ((b shr 8) and 255) * wb + ((c shr 8) and 255) * wc +
                ((d shr 8) and 255) * wd
            out[2] = (a and 255) * wa + (b and 255) * wb + (c and 255) * wc + (d and 255) * wd
        }

        fun cubic(x: Float, y: Float, out: FloatArray) {
            val fx = x - 0.5f
            val fy = y - 0.5f
            var ix = fx.toInt()
            if (fx < ix) ix-- // piso, también con negativos
            var iy = fy.toInt()
            if (fy < iy) iy--
            val tx = fx - ix
            val ty = fy - iy
            val xa = (ix - 1).coerceIn(0, w - 1)
            val xb = ix.coerceIn(0, w - 1)
            val xc = (ix + 1).coerceIn(0, w - 1)
            val xd = (ix + 2).coerceIn(0, w - 1)
            val ax = cr0(tx)
            val bx = cr1(tx)
            val cx = cr2(tx)
            val dx = cr3(tx)
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
            row((iy - 1).coerceIn(0, h - 1) * w, xa, xb, xc, xd, ax, bx, cx, dx, cr0(ty), out)
            row(iy.coerceIn(0, h - 1) * w, xa, xb, xc, xd, ax, bx, cx, dx, cr1(ty), out)
            row((iy + 1).coerceIn(0, h - 1) * w, xa, xb, xc, xd, ax, bx, cx, dx, cr2(ty), out)
            row((iy + 2).coerceIn(0, h - 1) * w, xa, xb, xc, xd, ax, bx, cx, dx, cr3(ty), out)
        }

        /** Una fila de 4 píxeles con sus pesos, sumada en [out] con el peso de la fila [wy]. */
        private fun row(
            base: Int, xa: Int, xb: Int, xc: Int, xd: Int, a: Float, b: Float, c: Float, d: Float, wy: Float,
            out: FloatArray,
        ) {
            val p = px[base + xa]
            val q = px[base + xb]
            val s = px[base + xc]
            val t = px[base + xd]
            out[0] += wy * (a * ((p shr 16) and 255) + b * ((q shr 16) and 255) + c * ((s shr 16) and 255) +
                d * ((t shr 16) and 255))
            out[1] += wy * (a * ((p shr 8) and 255) + b * ((q shr 8) and 255) + c * ((s shr 8) and 255) +
                d * ((t shr 8) and 255))
            out[2] += wy * (a * (p and 255) + b * (q and 255) + c * (s and 255) + d * (t and 255))
        }

        // Pesos de Catmull-Rom según la distancia t (0..1) al píxel de la izquierda (o de arriba)
        private fun cr0(t: Float) = ((-0.5f * t + 1f) * t - 0.5f) * t
        private fun cr1(t: Float) = (1.5f * t - 2.5f) * t * t + 1f
        private fun cr2(t: Float) = ((-1.5f * t + 2f) * t + 0.5f) * t
        private fun cr3(t: Float) = (0.5f * t - 0.5f) * t * t
    }

    /**
     * Fondo del espejo: la foto chiquita (lado corto de 48 a 96 px), desenfocada dos veces con una
     * caja (casi gaussiana) y oscura. Se lee agrandándola en cada punto: no ocupa una foto entera.
     */
    private class Backdrop(small: Level, fullW: Int, fullH: Int) {
        private val w: Int
        private val h: Int
        private val px: FloatArray
        private val kx: Float
        private val ky: Float

        init {
            var lv = small
            val single = Executors.newSingleThreadExecutor()
            try {
                while (min(lv.w, lv.h) > 96) lv = lv.half(single, 1)
            } finally {
                single.shutdown()
            }
            w = lv.w
            h = lv.h
            kx = w.toFloat() / fullW
            ky = h.toFloat() / fullH
            val f = FloatArray(w * h * 3)
            for (i in 0 until w * h) {
                val p = lv.px[i]
                f[i * 3] = ((p shr 16) and 255) * BACK_LIGHT
                f[i * 3 + 1] = ((p shr 8) and 255) * BACK_LIGHT
                f[i * 3 + 2] = (p and 255) * BACK_LIGHT
            }
            // Caja de radio k dos veces ≈ gaussiana con sigma = BACK_BLUR del lado corto
            val sigma = BACK_BLUR * min(w, h)
            val k = max(1, ((sqrt(1f + 6f * sigma * sigma) - 1f) / 2f).roundToInt())
            val tmp = FloatArray(f.size)
            repeat(2) {
                box(f, tmp, w, h, k, 3, w * 3) // a lo ancho
                box(tmp, f, h, w, k, w * 3, 3) // a lo alto
            }
            px = f
        }

        /** Caja de radio [k] por líneas de [len] puntos ([step] entre vecinos, [stride] entre líneas). */
        private fun box(src: FloatArray, dst: FloatArray, len: Int, lines: Int, k: Int, step: Int, stride: Int) {
            val inv = 1f / (2 * k + 1)
            for (l in 0 until lines) {
                val base = l * stride
                for (c in 0 until 3) {
                    var sum = 0f
                    for (i in -k..k) sum += src[base + i.coerceIn(0, len - 1) * step + c]
                    for (i in 0 until len) {
                        dst[base + i * step + c] = sum * inv
                        sum += src[base + min(len - 1, i + k + 1) * step + c] - src[base + max(0, i - k) * step + c]
                    }
                }
            }
        }

        /** Color del fondo en (x, y) de la foto completa. */
        fun at(x: Float, y: Float, out: FloatArray) {
            val fx = (x * kx - 0.5f).coerceIn(0f, (w - 1).toFloat())
            val fy = (y * ky - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = if (x0 + 1 < w) x0 + 1 else x0
            val y1 = if (y0 + 1 < h) y0 + 1 else y0
            val tx = fx - x0
            val ty = fy - y0
            val i00 = (y0 * w + x0) * 3
            val i10 = (y0 * w + x1) * 3
            val i01 = (y1 * w + x0) * 3
            val i11 = (y1 * w + x1) * 3
            for (c in 0 until 3) {
                val top = px[i00 + c] + (px[i10 + c] - px[i00 + c]) * tx
                val bot = px[i01 + c] + (px[i11 + c] - px[i01 + c]) * tx
                out[c] = top + (bot - top) * ty
            }
        }
    }
}
