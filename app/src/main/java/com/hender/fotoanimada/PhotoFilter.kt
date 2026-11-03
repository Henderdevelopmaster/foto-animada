package com.hender.fotoanimada

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/** Filtros de color. Se aplican en vivo (vista previa) y al guardar. */
enum class PhotoFilter(val id: String, val label: String) {
    ORIGINAL("original", "Original"),
    VIVID("vivid", "Vívido"),
    WARM("warm", "Cálido"),
    COOL("cool", "Frío"),
    DRAMATIC("dramatic", "Dramático"),
    MONO("mono", "B/N"),
    NOIR("noir", "Noir"),
    VINTAGE("vintage", "Vintage");

    fun matrix(): ColorMatrix = when (this) {
        ORIGINAL -> ColorMatrix()
        VIVID -> saturation(1.35f).also { it.postConcat(contrast(1.08f)) }
        WARM -> scale(1.08f, 1.0f, 0.88f).also { it.postConcat(saturation(1.1f)) }
        COOL -> scale(0.92f, 1.0f, 1.10f)
        DRAMATIC -> saturation(0.85f).also { it.postConcat(contrast(1.3f)) }
        MONO -> saturation(0f)
        NOIR -> saturation(0f).also { it.postConcat(contrast(1.45f)) }
        VINTAGE -> ColorMatrix(
            floatArrayOf(
                0.62f, 0.32f, 0.10f, 0f, 22f,
                0.26f, 0.62f, 0.10f, 0f, 14f,
                0.18f, 0.28f, 0.48f, 0f, 8f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }

    fun colorFilter(): ColorMatrixColorFilter? =
        if (this == ORIGINAL) null else ColorMatrixColorFilter(matrix())

    fun paint(): Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = colorFilter() }

    companion object {
        fun fromId(id: String?): PhotoFilter = entries.firstOrNull { it.id == id } ?: ORIGINAL

        private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

        private fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

        private fun contrast(c: Float): ColorMatrix {
            val t = (1f - c) * 128f
            return ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        }
    }
}
