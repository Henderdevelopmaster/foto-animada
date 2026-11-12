package com.hender.fotoanimada

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log

/**
 * Ojo de pez sobre la foto final (la cuenta está en [Fisheye], que se prueba sin cel). Va después
 * de la mejora y antes del filtro de color, en el mismo orden que la vista previa.
 * Si algo falla (por ejemplo, falta memoria), la foto sigue sin el efecto: nunca se pierde.
 */
object Lens {
    private const val TAG = "Lens"

    /** Lado largo máximo al abrir un JPEG: con las fotos MÁX (50 MP) no alcanzaría la memoria. */
    private const val MAX_SIDE = 4096

    /** Nombre corto para el registro. */
    fun name(style: Int) = when (style) {
        Fisheye.WIDE -> "gran-angular"
        Fisheye.MIRROR -> "espejo"
        else -> "no"
    }

    /**
     * Aplica el estilo sobre [bmp] y lo devuelve (el mismo si es mutable). Toda la memoria se
     * pide antes de escribir la primera franja, así que si falta queda la foto como estaba.
     */
    fun apply(bmp: Bitmap, style: Int): Bitmap {
        if (style == Fisheye.OFF) return bmp
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            Fisheye.render(px, w, h, style, threads) { y0, rows, band -> out.setPixels(band, 0, w, 0, y0, w, rows) }
            Log.i(TAG, "Ojo de pez ${name(style)} ${w}x$h en ${SystemClock.elapsedRealtime() - t0} ms")
            out
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo aplicar el ojo de pez: la foto sigue sin él", e)
            bmp
        }
    }

    /** Lo mismo sobre un JPEG: lo abre derecho, aplica y lo vuelve a guardar. Si falla, el JPEG tal cual. */
    fun jpeg(jpeg: ByteArray, style: Int): ByteArray {
        if (style == Fisheye.OFF) return jpeg
        return try {
            val bmp = Processing.decode(jpeg, MAX_SIDE)
            try {
                Processing.encode(apply(bmp, style), 95)
            } finally {
                bmp.recycle()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo abrir la foto para el ojo de pez", e)
            jpeg
        }
    }
}
