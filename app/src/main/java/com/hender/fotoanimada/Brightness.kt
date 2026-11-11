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
