package com.hender.fotoanimada

import android.content.Context

/**
 * Belleza (0..1 cada ajuste). Se aplica en vivo, en la foto y en el video del modo EFECTOS,
 * y solo si está encendida ([enabled]). Apagada, además, las fotos normales dejan la piel
 * natural (sin alisar).
 */
object Beauty {
    @Volatile var enabled = false
    @Volatile var smooth = 0f
    @Volatile var bright = 0f
    @Volatile var slim = 0f
    @Volatile var eyes = 0f

    /** Valores que de verdad se aplican (cero si la belleza está apagada). */
    val smoothFx get() = if (enabled) smooth else 0f
    val brightFx get() = if (enabled) bright else 0f
    val slimFx get() = if (enabled) slim else 0f
    val eyesFx get() = if (enabled) eyes else 0f

    val active get() = smoothFx > 0.01f || brightFx > 0.01f || slimFx > 0.01f || eyesFx > 0.01f
    val warps get() = slimFx > 0.01f || eyesFx > 0.01f

    fun load(c: Context) {
        val p = c.getSharedPreferences("beauty", Context.MODE_PRIVATE)
        smooth = p.getFloat("smooth", 0f); bright = p.getFloat("bright", 0f)
        slim = p.getFloat("slim", 0f); eyes = p.getFloat("eyes", 0f)
        enabled = CameraSettings.get(c, CameraSettings.BEAUTY, "off") == "on"
    }

    fun save(c: Context) {
        c.getSharedPreferences("beauty", Context.MODE_PRIVATE).edit()
            .putFloat("smooth", smooth).putFloat("bright", bright)
            .putFloat("slim", slim).putFloat("eyes", eyes).apply()
    }

    fun setEnabled(c: Context, on: Boolean) {
        enabled = on
        CameraSettings.set(c, CameraSettings.BEAUTY, if (on) "on" else "off")
    }
}
