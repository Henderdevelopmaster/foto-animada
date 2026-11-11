package com.hender.fotoanimada

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Brillo digital para cuando la cámara ya no da más (de noche, sobre todo con la frontal):
 * el sensor está al tope y subirle la compensación no cambia nada.
 *
 * La misma curva va en la vista previa, en la foto y en el video del Live,
 * así lo que se ve en la pantalla es lo que queda guardado.
 */
object Brightness {

    /** Brillo al que se lleva la escena de noche (promedio con peso al centro, en gamma). */
    const val NIGHT_AIM = 0.28f

    /** Lo máximo que se sube solo; con el ± a tope se deja más (el grano ya lo pidió el usuario). */
    private const val MAX_AUTO = 4.5f
    private const val MAX_ALL = 8f

    /** Levantado de sombras que ya pone la mejora de noche por su cuenta. */
    const val NIGHT_LIFT = 0.2f

    /**
     * Levantado de sombras de la vista previa según cuánto se subió: sin subir es el de siempre
     * (1.1, como la mejora de día); subiendo mucho casi nada (si no, solo se ve grano).
     */
    fun previewLift(g: Float): Float = 1.1f - (1.1f - NIGHT_LIFT) * ((g - 1f) / 0.6f).coerceIn(0f, 1f)

    /** 1 EV en la imagen (que está en gamma) ≈ ×2^(1/2.2). */
    fun evFactor(ev: Float): Float = Math.pow(2.0, ev / 2.2).toFloat()

    /** A dónde llevar la escena con el brillo que eligió el usuario (en EV). */
    fun aim(userEv: Float): Float = NIGHT_AIM * evFactor(userEv)

    fun maxGain(userEv: Float): Float = (MAX_AUTO * evFactor(userEv)).coerceIn(1.3f, MAX_ALL)

    /** Cuánto subir para que una escena con esta [key] llegue a [aim]. */
    fun gain(key: Float, aim: Float, maxGain: Float): Float =
        (aim / max(key, 0.012f)).coerceIn(1f, max(1f, maxGain))

    /** Sube lo oscuro ×g y deja el blanco en blanco (no quema). */
    fun curve(l: Float, g: Float): Float = l * g / (1f + (g - 1f) * l)

    private fun weight(nx: Float, ny: Float): Float = exp(-(nx * nx + ny * ny) / (2f * 0.26f * 0.26f))

    /**
     * Luz de la escena: promedio con más peso en el centro (donde suelen estar las caras).
     * Las luces fuertes se recortan para que un bombillo no mande sobre todo lo demás.
     */
    fun key(px: IntArray, w: Int, h: Int): Float {
        var acc = 0.0
        var sum = 0.0
        for (y in 0 until h) {
            val ny = (y + 0.5f) / h - 0.5f
            for (x in 0 until w) {
                val c = px[y * w + x]
                val l = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
                val wt = weight((x + 0.5f) / w - 0.5f, ny)
                acc += wt * min(l, 0.75f)
                sum += wt
            }
        }
        return if (sum > 0.0) (acc / sum).toFloat() else 0.5f
    }

    /** Igual que [key], sobre planos 0..1. */
    fun key(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int): Float {
        var acc = 0.0
        var sum = 0.0
        for (y in 0 until h) {
            val ny = (y + 0.5f) / h - 0.5f
            for (x in 0 until w) {
                val i = y * w + x
                val l = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
                val wt = weight((x + 0.5f) / w - 0.5f, ny)
                acc += wt * min(l, 0.75f)
                sum += wt
            }
        }
        return if (sum > 0.0) (acc / sum).toFloat() else 0.5f
    }

    /**
     * Aplica la curva a la luz de cada píxel (filas [y0], [y1]) conservando el color:
     * los tres canales suben igual y nunca más de lo que deja el canal más alto.
     */
    fun apply(px: IntArray, w: Int, y0: Int, y1: Int, g: Float, extraLift: Float = 0f) {
        val ratio = FloatArray(256) { i ->
            val l = max(i, 1) / 255f
            lift(curve(l, g), extraLift) / l
        }
        for (i in y0 * w until y1 * w) {
            val c = px[i]
            val r = (c shr 16) and 255
            val gg = (c shr 8) and 255
            val b = c and 255
            val mx = max(r, max(gg, b))
            if (mx == 0) continue
            var k = ratio[(r * 77 + gg * 150 + b * 29) shr 8]
            k = min(k, 255f / mx)
            px[i] = (c and 0xFF000000.toInt()) or
                ((r * k + 0.5f).toInt().coerceAtMost(255) shl 16) or
                ((gg * k + 0.5f).toInt().coerceAtMost(255) shl 8) or
                (b * k + 0.5f).toInt().coerceAtMost(255)
        }
    }

    /** Tinte normal de una escena con caras (la piel no es gris: tiene un poco menos verde). */
    private const val TINT_TARGET = 0.92f

    /** Azul de más respecto al verde que se deja (la luz de la pantalla es azulada). */
    private const val BLUE_TARGET = 0.98f

    /** Ganancias de color de noche: R, G y B (con el mismo brillo). */
    class NightWb(val r: Float, val g: Float, val b: Float) {
        val neutral get() = r == 1f && g == 1f && b == 1f
    }

    /**
     * De noche el sensor de este cel tira a morado (le falta verde) y con la luz de la pantalla
     * a azul: la piel sale morada. Se sube el verde (solo el tinte verde ↔ magenta) y se baja
     * el azul que sobra. Lo cálido (una lámpara) se respeta. Se mide sin luces quemadas ni
     * negros y sin colores muy fuertes (un letrero no manda).
     */
    fun nightWb(sumR: Double, sumG: Double, sumB: Double, count: Int, total: Int): NightWb {
        if (count < total / 40 || sumG <= 0.0) return NightWb(1f, 1f, 1f)
        val rb = (sumR + sumB) * 0.5
        if (rb <= 0.0) return NightWb(1f, 1f, 1f)
        val t = (sumG / rb).toFloat()
        val gG = if (t >= TINT_TARGET) 1f else Math.pow((TINT_TARGET / t).toDouble(), 0.8).toFloat().coerceAtMost(1.25f)
        val bg = (sumB / (sumG * gG)).toFloat()
        val gB = if (bg <= BLUE_TARGET) 1f else Math.pow((BLUE_TARGET / bg).toDouble(), 0.6).toFloat().coerceAtLeast(0.85f)
        // Mismo brillo: la luz no cambia, solo el color
        val n = 1f / (0.299f + 0.587f * gG + 0.114f * gB)
        return NightWb(n, gG * n, gB * n)
    }

    fun nightWb(px: IntArray): NightWb {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var c = 0
        for (p in px) {
            val pr = ((p shr 16) and 255) / 255f
            val pg = ((p shr 8) and 255) / 255f
            val pb = (p and 255) / 255f
            if (tintUsable(pr, pg, pb)) {
                r += pr; g += pg; b += pb; c++
            }
        }
        return nightWb(r, g, b, c, px.size)
    }

    fun nightWb(rs: FloatArray, gs: FloatArray, bs: FloatArray): NightWb {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var c = 0
        for (i in rs.indices) {
            if (tintUsable(rs[i], gs[i], bs[i])) {
                r += rs[i]; g += gs[i]; b += bs[i]; c++
            }
        }
        return nightWb(r, g, b, c, rs.size)
    }

    private fun tintUsable(r: Float, g: Float, b: Float): Boolean {
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        if (l < 0.03f || l > 0.92f) return false
        val mx = max(r, max(g, b))
        return (mx - min(r, min(g, b))) / mx <= 0.6f
    }

    /** Sombras levantadas de la vista previa (igual que en el shader). */
    fun lift(y: Float, amount: Float): Float = y + amount * y * (1f - y) * (1f - y)

    /**
     * Tabla 0..255 para la luz (Y) del video del Live: curva + levantado, y con Viva ([vivid] > 0)
     * la misma curva S suave de la vista previa y la foto.
     */
    fun lumaLut(g: Float, lift: Float, vivid: Float = 0f): ByteArray = ByteArray(256) { i ->
        var y = lift(curve(i / 255f, g), lift).coerceIn(0f, 1f)
        if (vivid > 0f) y += 0.20f * vivid * (y * y * (3f - 2f * y) - y)
        (y * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    /** Tabla 0..255 para el color (U/V) del video: un poco más de color al subir la luz (y con Viva). */
    fun chromaLut(g: Float, vivid: Float = 0f): ByteArray {
        val k = min(1f + 0.25f * (g - 1f), 1.5f) * (1f + 0.22f * vivid)
        return ByteArray(256) { i -> (128f + (i - 128) * k + 0.5f).toInt().coerceIn(0, 255).toByte() }
    }
}
