package com.hender.fotoanimada

/**
 * Giro del cel para la foto (la interfaz es siempre vertical). Kotlin puro para poder probarlo.
 * Los grados son los de OrientationEventListener: 0 = vertical, 90 = girado a la derecha,
 * 180 = de cabeza, 270 = girado a la izquierda.
 */
object Orientation {
    /** Margen pasado el medio camino (45°) para cambiar: así no salta en el borde. */
    const val HYSTERESIS = 15

    /** La posición (0, 90, 180 o 270) que corresponde a [degrees], quedándose en [current] si no es claro. */
    fun snap(degrees: Int, current: Int): Int {
        val d = ((degrees % 360) + 360) % 360
        var diff = kotlin.math.abs(d - current) % 360
        if (diff > 180) diff = 360 - diff
        if (diff <= 45 + HYSTERESIS) return current
        return ((d + 45) / 90 % 4) * 90
    }

    /** Rotación de la foto (valores de android.view.Surface.ROTATION_*) para un giro del cel. */
    fun surfaceRotation(deg: Int): Int = when (deg) {
        90 -> 3 // Surface.ROTATION_270
        180 -> 2 // Surface.ROTATION_180
        270 -> 1 // Surface.ROTATION_90
        else -> 0 // Surface.ROTATION_0
    }
}
