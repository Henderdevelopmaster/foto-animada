package com.hender.fotoanimada

import android.content.Context

/** Ajustes de belleza (0..1). Se aplican en vivo, en la foto y en el video del modo EFECTOS. */
object Beauty {
    @Volatile var smooth = 0f
    @Volatile var bright = 0f
    @Volatile var slim = 0f
    @Volatile var eyes = 0f

    val active get() = smooth > 0.01f || bright > 0.01f || slim > 0.01f || eyes > 0.01f
    val warps get() = slim > 0.01f || eyes > 0.01f

    fun load(c: Context) {
        val p = c.getSharedPreferences("beauty", Context.MODE_PRIVATE)
        smooth = p.getFloat("smooth", 0f); bright = p.getFloat("bright", 0f)
        slim = p.getFloat("slim", 0f); eyes = p.getFloat("eyes", 0f)
    }

    fun save(c: Context) {
        c.getSharedPreferences("beauty", Context.MODE_PRIVATE).edit()
            .putFloat("smooth", smooth).putFloat("bright", bright)
            .putFloat("slim", slim).putFloat("eyes", eyes).apply()
    }
}
