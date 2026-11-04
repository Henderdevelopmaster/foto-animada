package com.hender.fotoanimada

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Luz de la pantalla para la cámara frontal.
 * - Como flash: toda la pantalla de un color, o una forma (aro, corazón, estrella)
 *   que se refleja en los ojos.
 * - Como "linterna" frontal: aro de luz alrededor del visor, dejando ver la imagen.
 */
class FlashView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var color = Color.WHITE
        set(v) { field = v; invalidate() }
    var shape = "full" // full, ring, circle, heart, star
        set(v) { field = v; invalidate() }
    /** Si no es null, se ilumina todo menos este hueco (aro de luz). */
    var hole: RectF? = null
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = color
        val w = width.toFloat()
        val h = height.toFloat()
        hole?.let { r ->
            val p = Path().apply { addRect(0f, 0f, w, h, Path.Direction.CW) }
            val inner = Path().apply { addRoundRect(r, r.width() * 0.08f, r.width() * 0.08f, Path.Direction.CW) }
            p.op(inner, Path.Op.DIFFERENCE)
            canvas.drawPath(p, paint)
            return
        }
        if (shape == "full") {
            canvas.drawColor(color)
            return
        }
        canvas.drawColor(Color.BLACK)
        val s = min(w, h) * 0.9f
        val x = (w - s) / 2f
        val y = (h - s) / 2f
        when (shape) {
            "ring" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.16f
                canvas.drawCircle(w / 2f, h / 2f, s * 0.4f, paint)
            }
            "circle" -> canvas.drawCircle(w / 2f, h / 2f, s * 0.48f, paint)
            "heart" -> canvas.drawPath(InstantShape.HEART.path(s, x, y), paint)
            "star" -> canvas.drawPath(InstantShape.STAR.path(s, x, y), paint)
            else -> canvas.drawColor(color)
        }
    }
}
