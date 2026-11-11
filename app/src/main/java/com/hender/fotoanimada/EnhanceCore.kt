package com.hender.fotoanimada

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mejora automática de fotos (el "toque iPhone").
 *
 * No usa nada de Android para poder probarla en la compu con fotos de prueba.
 * Trabaja sobre los píxeles ARGB y en este orden:
 *  1. Quita el halo que dejan las luces fuertes (el "astigmatismo" del lente).
 *  2. Limpia el ruido fino y el ruido de color, y afina un poco los bordes.
 *  3. Equilibra luces y sombras por zonas (tipo HDR) sin halos en los bordes.
 *  4. Si hay una cara en sombra, la levanta un poco.
 *  5. Corrige el color (balance de blancos suave, viveza que respeta la piel)
 *     y suaviza las altas luces para que no se quemen de golpe.
 */
object EnhanceCore {

    /** Cara detectada, en coordenadas normalizadas 0..1. */
    class Face(val cx: Float, val cy: Float, val w: Float, val h: Float)

    class Options(
        val night: Boolean = false,
        /** Modo atardecer: respeta los naranjas y levanta más la tierra. */
        val sunset: Boolean = false,
        val faces: List<Face> = emptyList(),
        val threads: Int = 4,
        val strength: Float = 1f,
        /**
         * De noche: brillo al que llevar la escena si la cámara no llegó (0 = no tocar).
         * Es la misma cuenta que la vista previa ([Brightness]), así la foto queda como se veía.
         */
        val aim: Float = 0f,
        val maxGain: Float = 1f,
        /** Belleza apagada: la piel de las caras conserva su textura (no se alisa). */
        val naturalSkin: Boolean = false,
        /**
         * Viva (solo si el usuario la prende): más nitidez, contraste local y color.
         * 0 = apagada, 1 = completa. Con [strength] 0 queda solo esto (mejora automática apagada).
         */
        val vivid: Float = 0f,
    )

    class Report(
        val exposure: Float, val compress: Float, val glare: Float,
        val noise: Float, val faceBoost: Float, val wbR: Float, val wbB: Float,
        val gain: Float = 1f,
    ) {
        override fun toString() = String.format(
            java.util.Locale.US, "gain=%.2f exp=%.2f comp=%.2f glare=%.2f noise=%.4f face=%.2f wb=%.3f/%.3f",
            gain, exposure, compress, glare, noise, faceBoost, wbR, wbB,
        )
    }

    // ---------------------------------------------------------------- principal

    fun process(px: IntArray, w: Int, h: Int, o: Options = Options()): Report {
        val long = max(w, h)
        val f = max(1, (long + 511) / 512)
        var s0 = downsample(px, w, h, f)
        // ---- 0) De noche, si la cámara no llegó: subir con la curva de la vista previa ----
        var gainUsed = 1f
        if (o.aim > 0f) {
            val g0 = Brightness.gain(Brightness.key(s0.r, s0.g, s0.b, s0.w, s0.h), o.aim, max(1f, o.maxGain))
            // Lo que la vista previa levanta de más respecto a la mejora de noche (igual que allá)
            val extra = Brightness.previewLift(g0) - Brightness.NIGHT_LIFT
            if (g0 > 1.03f || extra > 0.02f) {
                val tn = o.threads.coerceIn(1, 8)
                val rws = (h + tn - 1) / tn
                (0 until tn).map { ti ->
                    Thread { Brightness.apply(px, w, ti * rws, min(h, ti * rws + rws), g0, extra) }.also { it.start() }
                }.forEach { it.join() }
                s0 = downsample(px, w, h, f)
            }
            gainUsed = g0
        }
        val s = s0
        val sw = s.w
        val sh = s.h
        val n = sw * sh
        val k = o.strength.coerceIn(0f, 1.5f)
        // Viva: de noche va a la mitad (más contraste o nitidez también subiría el grano)
        val viv = o.vivid.coerceIn(0f, 1f)
        val vn = if (o.night) viv * 0.45f else viv

        // Caras: el anti-halo no las toca (el brillo de la piel no es un bombillo; antes la
        // dejaba gris, como una mancha de ceniza en el cachete)
        val faceGuard = FloatArray(n)
        for (fc in o.faces) {
            val rx = max(1f, fc.w * sw * 0.75f)
            val ry = max(1f, fc.h * sh * 0.85f)
            val cx = fc.cx * sw
            val cy = fc.cy * sh
            for (y in 0 until sh) for (x in 0 until sw) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d2 = dx * dx + dy * dy
                if (d2 < 2.25f) {
                    val i = y * sw + x
                    faceGuard[i] = max(faceGuard[i], smooth(2.25f, 1f, d2))
                }
            }
        }

        // ---- 1) Halo alrededor de luces --------------------------------------
        var luma = FloatArray(n) { 0.299f * s.r[it] + 0.587f * s.g[it] + 0.114f * s.b[it] }
        val median0 = percentile(luma, 0.5f)
        val glareStrength = (if (o.night) 1f else smooth(0.40f, 0.16f, median0)) * k
        val src = FloatArray(n) { smooth(0.90f, 0.985f, max(s.r[it], max(s.g[it], s.b[it]))) }
        var glare: Array<FloatArray>? = null
        var glareAmp = 0f
        if (glareStrength > 0.03f) {
            // Solo luces "puntuales" (focos, faroles); el cielo o una ventana grande no cuentan
            val density = boxBlur(src, sw, sh, max(2, max(sw, sh) / 26))
            val point = FloatArray(n) { src[it] * (1f - smooth(0.16f, 0.45f, density[it])) * (1f - faceGuard[it]) }
            if (point.sum() > 0.6f) {
                val big = max(sw, sh).toFloat()
                // Solo el velo ancho; el brillito pegado a la luz se ve natural y se deja
                val k1 = gauss(point, sw, sh, big * 0.025f)
                val k2 = gauss(point, sw, sh, big * 0.065f)
                val kg = FloatArray(n) { 0.55f * k1[it] + 0.45f * k2[it] }
                val ratios = ArrayList<Float>()
                for (i in 0 until n) {
                    if (kg[i] > 0.01f && src[i] < 0.05f) ratios.add(luma[i] / kg[i])
                }
                if (ratios.size > 40) {
                    ratios.sort()
                    glareAmp = min(ratios[(ratios.size * 0.25f).toInt()], 30f)
                    // Fondo "sin halo": lo que hay más lejos de la luz, rellenado hacia adentro.
                    // Solo se quita lo que sobra encima de ese fondo, canal por canal, y
                    // nunca más que lo más oscuro de alrededor (así no quedan anillos ni manchas).
                    val model = FloatArray(n) { glareAmp * kg[it] }
                    val keepW = FloatArray(n) { 1f - smooth(0.015f, 0.06f, model[it]) }
                    val den = gauss(keepW, sw, sh, big * 0.09f)
                    glare = Array(3) { c ->
                        val ch = when (c) { 0 -> s.r; 1 -> s.g; else -> s.b }
                        val num = gauss(FloatArray(n) { ch[it] * keepW[it] }, sw, sh, big * 0.09f)
                        val low = gauss(ch, sw, sh, big * 0.015f)
                        val fallback = percentile(ch, 0.5f)
                        FloatArray(n) {
                            val bg = (num[it] + 1e-3f * fallback) / (den[it] + 1e-3f)
                            val excess = max(0f, low[it] - bg)
                            min(excess, model[it] * 1.3f) * glareStrength * 0.7f * (1f - faceGuard[it])
                        }
                    }
                    for (i in 0 until n) {
                        val ns = 1f - src[i]
                        val sr = glare[0][i] * ns
                        val sg = glare[1][i] * ns
                        val sb = glare[2][i] * ns
                        val tt = keepRatio(s.r[i], s.g[i], s.b[i], sr, sg, sb)
                        s.r[i] -= sr * tt
                        s.g[i] -= sg * tt
                        s.b[i] -= sb * tt
                    }
                    luma = FloatArray(n) { 0.299f * s.r[it] + 0.587f * s.g[it] + 0.114f * s.b[it] }
                }
            }
        }

        // ¿Parece atardecer? (muchos naranjas/rojos saturados) → no "corregir" ese color
        var warm = 0
        for (i in 0 until n) {
            val r = s.r[i]; val g = s.g[i]; val b = s.b[i]
            if (r > g && g > b && r - b > 0.12f && luma[i] > 0.2f) {
                val hue = 60f * (g - b) / (r - b)
                if (hue in 4f..50f && (r - b) / r > 0.3f) warm++
            }
        }
        val sunsetLike = o.sunset || warm > n * 0.15f

        // ---- 2) Tono por zonas (filtro guiado: suave pero respeta bordes) ----
        val eps = 1f / 256f
        val l = FloatArray(n) { fastLog2(luma[it] + eps) }
        val gr = max(2, max(sw, sh) / 30)
        val gfA: FloatArray
        val gfB: FloatArray
        run {
            val mean = boxBlur(l, sw, sh, gr)
            val sq = boxBlur(FloatArray(n) { l[it] * l[it] }, sw, sh, gr)
            val a = FloatArray(n)
            val b = FloatArray(n)
            val e = 0.10f
            for (i in 0 until n) {
                val v = max(0f, sq[i] - mean[i] * mean[i])
                a[i] = v / (v + e)
                b[i] = mean[i] - a[i] * mean[i]
            }
            gfA = boxBlur(a, sw, sh, gr)
            gfB = boxBlur(b, sw, sh, gr)
        }
        val base = FloatArray(n) { gfA[it] * l[it] + gfB[it] }
        val bm = percentile(base, 0.5f)
        val blo = percentile(base, 0.02f)
        val bhi = percentile(base, 0.985f)
        val range = max(0.5f, bhi - blo)
        val compress = 1f - (1f - ((if (o.night || o.sunset) 4.6f else 5.2f) / range).coerceIn(0.58f, 1f)) * min(k, 1f)
        val target = fastLog2(if (o.night) 0.30f else 0.43f)
        // Escenas oscuras se aclaran con moderación (que la noche siga pareciendo noche);
        // escenas claras casi no se oscurecen (una pared blanca debe seguir blanca).
        val median = percentile(luma, 0.5f)
        val maxUp = when {
            o.aim > 0f -> 0.25f // ya se subió arriba, igual que en la vista previa
            o.night || o.sunset -> 1.4f
            else -> 0.6f + 0.5f * smooth(0.05f, 0.18f, median)
        }
        // De noche se mide como la vista previa (peso al centro, donde está la cara) y no por la
        // mediana: con fondo negro la mediana es el fondo y la cara terminaba quemada u oscura.
        val nightAim = o.aim > 0f
        val keyL = if (nightAim) fastLog2(Brightness.key(s.r, s.g, s.b, sw, sh) + eps) else bm
        val pivot = if (nightAim) max(bm, keyL) else bm
        val exposure = (if (nightAim) ((fastLog2(o.aim) - keyL) * 0.75f).coerceIn(0f, maxUp)
        else ((target - bm) * 0.75f).coerceIn(-0.1f, maxUp)) * min(k, 1f)
        val hiLo = fastLog2(0.30f)
        val hiHi = fastLog2(0.80f)

        // Viva: contraste local de tamaño medio (texturas, nubes, hojas, pelo), medido en la
        // versión pequeña (ahí no hay grano). Poco en sombras profundas, en luces y en caras.
        // Donde hay un borde fuerte el filtro guiado ya lo sigue, así que no quedan halos.
        val clarity = CLARITY * vn
        val detail: FloatArray? = if (clarity > 0.01f) {
            val dLo = fastLog2(0.025f)
            val dLo2 = fastLog2(0.10f)
            val dHi = fastLog2(0.55f)
            val dHi2 = fastLog2(0.95f)
            FloatArray(n) {
                val bb = base[it]
                val wgt = smooth(dLo, dLo2, bb) * (1f - 0.6f * smooth(dHi, dHi2, bb)) * (1f - 0.75f * faceGuard[it])
                (l[it] - bb).coerceIn(-0.7f, 0.7f) * wgt * clarity
            }
        } else null

        // Caras en sombra: subirlas un poco (relativo a la escena, no a un tono fijo)
        val faceMap = FloatArray(n)
        var faceBoost = 0f
        for (fc in o.faces) {
            val x0 = ((fc.cx - fc.w / 2) * sw).toInt().coerceIn(0, sw - 1)
            val x1 = ((fc.cx + fc.w / 2) * sw).toInt().coerceIn(x0 + 1, sw)
            val y0 = ((fc.cy - fc.h / 2) * sh).toInt().coerceIn(0, sh - 1)
            val y1 = ((fc.cy + fc.h / 2) * sh).toInt().coerceIn(y0 + 1, sh)
            var acc = 0f
            var cnt = 0
            for (y in y0 until y1) for (x in x0 until x1) { acc += base[y * sw + x]; cnt++ }
            if (cnt == 0) continue
            val fb = acc / cnt
            val rel = fb - pivot
            val after = fb + exposure + squeeze(rel, compress, nightAim)
            val gap = (bm + exposure) - after
            val boost = ((gap - 0.25f) * 0.65f).coerceIn(0f, 0.8f) * min(k, 1f)
            if (boost <= 0.01f) continue
            faceBoost = max(faceBoost, boost)
            val rx = fc.w * sw * 0.85f
            val ry = fc.h * sh * 0.95f
            val cx = fc.cx * sw
            val cy = fc.cy * sh
            for (y in 0 until sh) for (x in 0 until sw) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d2 = dx * dx + dy * dy
                if (d2 < 4f) {
                    val m = smooth(1.9f, 0.7f, d2)
                    val i = y * sw + x
                    faceMap[i] = max(faceMap[i], boost * m)
                }
            }
        }

        // ---- 3) Color: balance de blancos suave -----------------------------
        var mr = 0f; var mg = 0f; var mb = 0f; var cnt = 0
        for (i in 0 until n) {
            val y = luma[i]
            if (y < 0.12f || y > 0.9f) continue
            val mx = max(s.r[i], max(s.g[i], s.b[i]))
            val mn = min(s.r[i], min(s.g[i], s.b[i]))
            if ((mx - mn) / (mx + 1e-4f) > 0.45f) continue
            mr += s.r[i]; mg += s.g[i]; mb += s.b[i]; cnt++
        }
        var wbR = 1f
        var wbB = 1f
        if (!sunsetLike && cnt > n / 50 && mr > 0 && mb > 0) {
            val sWb = (if (o.night) 0.12f else 0.32f) * min(k, 1f)
            wbR = pow(mg / mr, sWb).coerceIn(0.93f, 1.07f)
            wbB = pow(mg / mb, sWb).coerceIn(0.93f, 1.07f)
        }
        // Toque cálido muy leve (piel más bonita, como el iPhone)
        wbR *= 1f + 0.012f * min(k, 1f)
        wbB *= 1f - 0.012f * min(k, 1f)
        val vibrance = (if (o.night) 0.10f else if (sunsetLike) 0.26f else 0.20f) * min(k, 1f)
        val vibExtra = VIBRANCE * vn
        // Viva: curva S suave (negros más profundos, blancos más limpios)
        val contrast = CONTRAST * vn
        val protectSkin = !sunsetLike || o.faces.isNotEmpty()

        // ---- 4) Ruido: versiones a 1/2 (luz) y 1/4 (color) -------------------
        val half = downLuma(px, w, h, 2)
        val hw = max(1, w / 2)
        val hh = max(1, h / 2)
        val q = downsample(px, w, h, 4)
        val qn = q.w * q.h
        val qcb = FloatArray(qn)
        val qcr = FloatArray(qn)
        for (i in 0 until qn) {
            val y = 0.299f * q.r[i] + 0.587f * q.g[i] + 0.114f * q.b[i]
            qcb[i] = q.b[i] - y
            qcr[i] = q.r[i] - y
        }
        val noise = estimateNoise(px, w, h, half, hw, hh)
        val t = max(noise * 2.2f, 0.004f)
        // Con la mejora apagada (solo Viva) no se quita grano: la foto queda como salió
        val keep = 1f - (1f - (if (o.night) 0.30f else 0.45f)) * min(k, 1f)
        val sharpen = (if (o.night) 0.08f else 0.26f) * min(k, 1f) + SHARPEN * vn
        // En la cara, Viva no marca los poros (la nitidez de más va al resto)
        val skinSharp = SHARPEN * vn * 0.7f
        val chromaMix = (if (o.night || noise > 0.012f) 0.9f else 0.65f) * min(k, 1f)

        // ---- 5) Pasada final a resolución completa (en paralelo por filas) ----
        val threads = o.threads.coerceIn(1, 8)
        val rows = (h + threads - 1) / threads
        val gl = glare
        val workers = (0 until threads).map { ti ->
            Thread {
                val y0 = ti * rows
                val y1 = min(h, y0 + rows)
                if (y0 >= y1) return@Thread
                val sA = Sampler(gfA, sw, sh, f.toFloat(), w)
                val sB = Sampler(gfB, sw, sh, f.toFloat(), w)
                val sF = Sampler(faceMap, sw, sh, f.toFloat(), w)
                val sP = if ((o.naturalSkin || vn > 0f) && o.faces.isNotEmpty()) Sampler(faceGuard, sw, sh, f.toFloat(), w) else null
                val sD = detail?.let { Sampler(it, sw, sh, f.toFloat(), w) }
                val sGr = gl?.let { Sampler(it[0], sw, sh, f.toFloat(), w) }
                val sGg = gl?.let { Sampler(it[1], sw, sh, f.toFloat(), w) }
                val sGb = gl?.let { Sampler(it[2], sw, sh, f.toFloat(), w) }
                val sH = Sampler(half, hw, hh, 2f, w)
                val sCb = Sampler(qcb, q.w, q.h, 4f, w)
                val sCr = Sampler(qcr, q.w, q.h, 4f, w)
                for (y in y0 until y1) {
                    sA.row(y); sB.row(y); sF.row(y); sH.row(y); sCb.row(y); sCr.row(y); sP?.row(y); sD?.row(y)
                    sGr?.row(y); sGg?.row(y); sGb?.row(y)
                    var i = y * w
                    for (x in 0 until w) {
                        val c = px[i]
                        val r0 = ((c shr 16) and 255) / 255f
                        val g0 = ((c shr 8) and 255) / 255f
                        val b0 = (c and 255) / 255f
                        val y0l = 0.299f * r0 + 0.587f * g0 + 0.114f * b0
                        var r = r0
                        var g = g0
                        var b = b0
                        // 1) halo
                        if (sGr != null) {
                            val ns = 1f - smooth(0.90f, 0.985f, max(r, max(g, b)))
                            val sr = sGr.at(x) * ns
                            val sg = sGg!!.at(x) * ns
                            val sb = sGb!!.at(x) * ns
                            val tt = keepRatio(r, g, b, sr, sg, sb)
                            r -= sr * tt
                            g -= sg * tt
                            b -= sb * tt
                        }
                        // 2) ruido y nitidez
                        var yy = 0.299f * r + 0.587f * g + 0.114f * b
                        var cbv = b - yy
                        var crv = r - yy
                        val d = y0l - sH.at(x)
                        val ad = abs(d)
                        // En la cara, con la belleza apagada, casi no se alisa (poros y textura reales)
                        val face = sP?.at(x) ?: 0f
                        val kp = if (o.naturalSkin && face > 0f) keep + (max(keep, 0.85f) - keep) * face else keep
                        val shp = sharpen - skinSharp * face
                        val gain = kp + (1f + shp - kp) * smooth(0.5f * t, 1.6f * t, ad)
                        yy += d * (gain - 1f)
                        cbv += chromaMix * (sCb.at(x) - (b0 - y0l))
                        crv += chromaMix * (sCr.at(x) - (r0 - y0l))
                        // 3) tono por zonas
                        val lf = fastLog2(max(yy, 0f) + eps)
                        val bse = sA.at(x) * lf + sB.at(x)
                        // Rango comprimido alrededor de lo importante; de noche solo se bajan las
                        // luces (las sombras negras no se suben: sería subir puro grano)
                        val rel = bse - pivot
                        var off = exposure + squeeze(rel, compress, nightAim) + sF.at(x)
                        // Lo que ya está claro no se sube (ni en la cara): así no se quema
                        if (off > 0f) off *= 1f - smooth(hiLo, hiHi, bse)
                        if (sD != null) off += sD.at(x)
                        val ratio = fastExp2(off)
                        yy *= ratio
                        cbv *= ratio
                        crv *= ratio
                        r = yy + crv
                        b = yy + cbv
                        g = (yy - 0.299f * r - 0.114f * b) / 0.587f
                        // 4) color
                        r *= wbR
                        b *= wbB
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        if (mx > 1e-3f) {
                            val sat = (mx - mn) / mx
                            // Viva le pone menos color de más a la cara (la piel no se pone naranja)
                            var amount = (vibrance + vibExtra * (1f - 0.6f * face)) * (1f - sat)
                            if (protectSkin && r >= g && g >= b && r - b > 1e-3f) {
                                val hue = 60f * (g - b) / (r - b) // 0..60 (rojo → amarillo)
                                amount *= 1f - 0.75f * bump(hue, 6f, 44f)
                            }
                            val ly = 0.299f * r + 0.587f * g + 0.114f * b
                            r = ly + (r - ly) * (1f + amount)
                            g = ly + (g - ly) * (1f + amount)
                            b = ly + (b - ly) * (1f + amount)
                        }
                        // Viva: curva S en la luz, el mismo factor en los tres canales (no cambia el tono)
                        if (contrast > 0f) {
                            val ly = 0.299f * r + 0.587f * g + 0.114f * b
                            if (ly > 1e-4f && ly < 1f) {
                                val ct = contrast * (1f - 0.5f * face)
                                val ys = ly + ct * (ly * ly * (3f - 2f * ly) - ly)
                                val kc = ys / ly
                                r *= kc
                                g *= kc
                                b *= kc
                            }
                        }
                        // 5) altas luces suaves
                        px[i] = (0xFF shl 24) or (out8(r) shl 16) or (out8(g) shl 8) or out8(b)
                        i++
                    }
                }
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        return Report(exposure, compress, glareAmp * glareStrength, noise, faceBoost, wbR, wbB, gainUsed)
    }

    // ---------------------------------------------------------------- Viva

    // Cuánto suma Viva (de noche menos de la mitad). Probado en paisajes, retratos y noche:
    // se nota (más textura, cielo y verdes con más color, bordes limpios) sin verse falso.
    private const val CLARITY = 0.70f
    private const val VIBRANCE = 0.30f
    private const val SHARPEN = 0.32f
    private const val CONTRAST = 0.20f

    // ---------------------------------------------------------------- utilidades

    /**
     * Altas luces suaves pero con blancos de verdad: hasta 0.94 queda igual y de ahí se dobla
     * para llegar a blanco puro en 1.06 (antes el blanco quedaba gris: máximo 240 de 255).
     */
    private fun out8(v: Float): Int {
        var x = v
        if (x > 0.94f) {
            val t = ((x - 0.94f) / 0.12f).coerceAtMost(1f)
            x = 0.94f + 0.12f * t - 0.06f * t * t
        }
        return (x * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Rango comprimido alrededor de lo importante ([rel] = cuántos pasos sobre o bajo eso).
     * De día se suben las sombras y lo claro casi no se toca (si se baja, el blanco queda gris
     * y la foto se ve apagada). De noche al revés: solo se bajan las luces y las sombras negras
     * no se suben (sería subir puro grano).
     */
    private fun squeeze(rel: Float, compress: Float, night: Boolean): Float = when {
        night -> if (rel < 0f) 0f else rel * (compress - 1f)
        rel < 0f -> rel * (compress - 1f)
        else -> rel * (compress - 1f) * 0.25f
    }

    /**
     * Cuánto del halo se puede restar sin dejar ningún canal por debajo del 25 %
     * de lo que tenía. Se usa el mismo factor en los tres canales para no cambiar
     * el color (si no, aparecen manchas moradas alrededor de la luz).
     */
    private fun keepRatio(r: Float, g: Float, b: Float, sr: Float, sg: Float, sb: Float): Float {
        var t = 1f
        if (sr > 0.75f * r) t = min(t, 0.75f * r / sr)
        if (sg > 0.75f * g) t = min(t, 0.75f * g / sg)
        if (sb > 0.75f * b) t = min(t, 0.75f * b / sb)
        // El halo es un velo sobre lo oscuro (cielo, pared de noche). Lo que ya está claro
        // (piel iluminada, una pared con luz) no se toca: si no, queda gris y sin color.
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        val sl = (0.299f * sr + 0.587f * sg + 0.114f * sb) * t
        if (sl > 1e-5f) {
            val allowed = 0.55f * l * (1f - smooth(0.15f, 0.45f, l))
            if (sl > allowed) t *= allowed / sl
        }
        return t
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 1 dentro de [a, b], bajando suave en los bordes. */
    private fun bump(x: Float, a: Float, b: Float): Float {
        val m = (b - a) * 0.25f
        return smooth(a - m, a + m, x) * (1f - smooth(b - m, b + m, x))
    }

    private fun pow(a: Float, e: Float): Float = fastExp2(fastLog2(a) * e)

    fun fastLog2(x: Float): Float {
        val bits = x.toRawBits()
        val e = ((bits ushr 23) and 255) - 128
        val m = Float.fromBits((bits and 0x007FFFFF) or 0x3F800000)
        return e + (-0.34484843f * m + 2.02466578f) * m - 0.67487759f
    }

    fun fastExp2(v: Float): Float {
        val x = v.coerceIn(-24f, 24f)
        val fl = kotlin.math.floor(x)
        val fr = x - fl
        val p = 1f + fr * (0.6960656f + fr * (0.2244943f + fr * 0.0794402f))
        return Float.fromBits((fl.toInt() + 127) shl 23) * p
    }

    private fun percentile(a: FloatArray, p: Float): Float {
        val c = a.copyOf()
        c.sort()
        return c[((c.size - 1) * p).roundToInt().coerceIn(0, c.size - 1)]
    }

    private class Planes(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
    }

    /** Promedio por bloques f×f, en 0..1. */
    private fun downsample(px: IntArray, w: Int, h: Int, f: Int): Planes {
        val p = Planes(max(1, w / f), max(1, h / f))
        val inv = 1f / (f * f * 255f)
        for (sy in 0 until p.h) for (sx in 0 until p.w) {
            var ar = 0
            var ag = 0
            var ab = 0
            for (yy in sy * f until min(h, sy * f + f)) {
                var i = yy * w + sx * f
                for (kk in 0 until f) {
                    val c = px[i++]
                    ar += (c shr 16) and 255
                    ag += (c shr 8) and 255
                    ab += c and 255
                }
            }
            val j = sy * p.w + sx
            p.r[j] = ar * inv
            p.g[j] = ag * inv
            p.b[j] = ab * inv
        }
        return p
    }

    private fun downLuma(px: IntArray, w: Int, h: Int, f: Int): FloatArray {
        val dw = max(1, w / f)
        val dh = max(1, h / f)
        val out = FloatArray(dw * dh)
        val inv = 1f / (f * f * 255f)
        for (sy in 0 until dh) for (sx in 0 until dw) {
            var acc = 0f
            for (yy in sy * f until sy * f + f) {
                var i = yy * w + sx * f
                for (kk in 0 until f) {
                    val c = px[i++]
                    acc += 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
                }
            }
            out[sy * dw + sx] = acc * inv
        }
        return out
    }

    /** Ruido típico: diferencia con la versión a 1/2 en zonas planas (muestra de filas). */
    private fun estimateNoise(px: IntArray, w: Int, h: Int, half: FloatArray, hw: Int, hh: Int): Float {
        val vals = ArrayList<Float>()
        val s = Sampler(half, hw, hh, 2f, w)
        var y = 3
        while (y < h - 3) {
            s.row(y)
            var x = 3
            while (x < w - 3) {
                val c = px[y * w + x]
                val l = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
                if (l > 0.06f && l < 0.85f) vals.add(abs(l - s.at(x)))
                x += 5
            }
            y += max(4, h / 160)
        }
        if (vals.size < 100) return 0.004f
        vals.sort()
        return vals[(vals.size * 0.3f).toInt()] / 0.385f
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (kk in -r..r) acc += src[row + kk.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = acc * inv
                acc += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (kk in -r..r) acc += tmp[kk.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc * inv
                acc += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
            }
        }
        return out
    }

    /** Mínimo en una ventana (2r+1)², separable. */
    private fun erode(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = Float.MAX_VALUE
                for (xx in max(0, x - r)..min(w - 1, x + r)) m = min(m, src[row + xx])
                tmp[row + x] = m
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = Float.MAX_VALUE
            for (yy in max(0, y - r)..min(h - 1, y + r)) m = min(m, tmp[yy * w + x])
            out[y * w + x] = m
        }
        return out
    }

    /** Tres cajas seguidas ≈ desenfoque gaussiano. */
    private fun gauss(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val r = max(1, (sigma * 0.95f).roundToInt())
        return boxBlur(boxBlur(boxBlur(src, w, h, r), w, h, r), w, h, r)
    }

    /** Lee un mapa pequeño con interpolación bilineal, fila por fila. */
    private class Sampler(val map: FloatArray, val mw: Int, val mh: Int, val scale: Float, fullW: Int) {
        private val rowBuf = FloatArray(mw)
        private val ix = IntArray(fullW)
        private val fx = FloatArray(fullW)

        init {
            for (x in 0 until fullW) {
                val m = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, (mw - 1).toFloat())
                val i0 = min(m.toInt(), max(0, mw - 2))
                ix[x] = i0
                fx[x] = if (mw > 1) m - i0 else 0f
            }
        }

        fun row(y: Int) {
            val m = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, (mh - 1).toFloat())
            val j0 = min(m.toInt(), max(0, mh - 2))
            val t = if (mh > 1) m - j0 else 0f
            val a = j0 * mw
            val b = if (mh > 1) (j0 + 1) * mw else a
            for (i in 0 until mw) rowBuf[i] = map[a + i] + (map[b + i] - map[a + i]) * t
        }

        fun at(x: Int): Float {
            val i = ix[x]
            if (mw == 1) return rowBuf[0]
            return rowBuf[i] + (rowBuf[i + 1] - rowBuf[i]) * fx[x]
        }
    }
}
