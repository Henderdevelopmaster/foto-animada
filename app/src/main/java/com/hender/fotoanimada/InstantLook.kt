package com.hender.fotoanimada

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.max
import kotlin.random.Random

/** Filtros estilo cámara instantánea (Instax, Polaroid, flash, rollo…). */
enum class InstantLook(
    val id: String,
    val label: String,
    val grain: Float,
    val leak: Boolean,
    val vignette: Float,
    val flash: Boolean,
    /** Necesita cálculo por píxel (ver [InstantFx]), no solo color. */
    val fx: Boolean = false,
) {
    NONE("none", "Sin filtro", 0f, false, 0f, false),
    INSTAX("instax", "Instax", 0.05f, false, 0.25f, false),
    POLAROID("polaroid", "Polaroid", 0.07f, true, 0.35f, false),
    FLASH("flash", "Flash", 0.04f, false, 0.5f, true),
    FILM("film", "Rollo 400", 0.09f, false, 0.3f, false),
    BN("bn", "B/N rollo", 0.1f, false, 0.4f, false),
    SUNSET("sunset", "Atardecer", 0.06f, true, 0.3f, false),
    PIXEL("pixel", "Pixelado", 0f, false, 0f, false, fx = true),
    VHS("vhs", "VHS", 0.06f, false, 0.3f, false, fx = true),
    COMIC("comic", "Cómic", 0f, false, 0f, false, fx = true),
    LEAK("leak", "Fuga de luz", 0.05f, false, 0.25f, false, fx = true);

    fun matrix(): ColorMatrix {
        val m = ColorMatrix()
        when (this) {
            NONE -> {}
            INSTAX -> {
                m.postConcat(scale(0.96f, 1.0f, 1.06f)); m.postConcat(sat(0.88f)); m.postConcat(lift(20f)); m.postConcat(contrast(0.95f))
            }
            POLAROID -> {
                m.postConcat(scale(1.08f, 1.0f, 0.86f)); m.postConcat(sat(0.8f)); m.postConcat(lift(28f)); m.postConcat(contrast(0.9f))
            }
            FLASH -> {
                m.postConcat(scale(1.12f, 1.12f, 1.12f)); m.postConcat(contrast(1.25f)); m.postConcat(sat(1.05f))
            }
            FILM -> {
                m.postConcat(scale(1.05f, 1.0f, 0.92f)); m.postConcat(contrast(1.1f)); m.postConcat(sat(1.12f)); m.postConcat(lift(10f))
            }
            BN -> {
                m.postConcat(sat(0f)); m.postConcat(contrast(1.2f)); m.postConcat(lift(12f))
            }
            SUNSET -> {
                m.postConcat(scale(1.12f, 1.0f, 0.82f)); m.postConcat(sat(1.15f)); m.postConcat(lift(8f))
            }
            // Aproximaciones de color para la vista previa en cels sin shaders
            PIXEL -> { m.postConcat(contrast(1.12f)); m.postConcat(sat(1.2f)) }
            VHS -> { m.postConcat(sat(0.82f)); m.postConcat(lift(14f)); m.postConcat(scale(1.0f, 0.98f, 1.05f)) }
            COMIC -> { m.postConcat(contrast(1.35f)); m.postConcat(sat(1.4f)) }
            LEAK -> { m.postConcat(scale(1.1f, 1.0f, 0.9f)); m.postConcat(lift(16f)) }
        }
        return m
    }

    fun colorFilter(): ColorMatrixColorFilter? = if (this == NONE) null else ColorMatrixColorFilter(matrix())

    /** Aplica el look completo (color + viñeta + flash + luz quemada + grano). */
    fun apply(src: Bitmap): Bitmap {
        val out: Bitmap
        if (fx) {
            out = InstantFx.render(this, src)
        } else {
            out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = colorFilter() })
        }
        val c = Canvas(out)
        if (this == NONE) return out
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val r = max(w, h) * 0.75f
        if (flash) {
            c.drawRect(0f, 0f, w, h, Paint().apply {
                shader = RadialGradient(w / 2, h * 0.45f, r * 0.6f,
                    intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            })
        }
        if (vignette > 0f) {
            c.drawRect(0f, 0f, w, h, Paint().apply {
                shader = RadialGradient(w / 2, h / 2, r,
                    intArrayOf(0, 0, Color.argb((vignette * 210).toInt(), 0, 0, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
        }
        if (leak) {
            c.drawRect(0f, 0f, w, h, Paint().apply {
                shader = LinearGradient(0f, 0f, w * 0.6f, h * 0.35f,
                    intArrayOf(Color.argb(120, 255, 120, 40), Color.argb(50, 255, 60, 80), Color.argb(0, 255, 60, 80)),
                    floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            })
        }
        if (grain > 0f) {
            val n = 256
            val px = IntArray(n * n) {
                val v = Random.nextInt(256)
                Color.argb(255, v, v, v)
            }
            val tile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
            c.drawRect(0f, 0f, w, h, Paint().apply {
                shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                alpha = (grain * 255).toInt()
                xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            })
            tile.recycle()
        }
        return out
    }

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NONE
        private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }
        private fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }
        private fun lift(t: Float): ColorMatrix {
            val k = 1f - t / 255f
            return ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f))
        }
        private fun contrast(c: Float): ColorMatrix {
            val t = (1f - c) * 128f
            return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        }
    }
}
