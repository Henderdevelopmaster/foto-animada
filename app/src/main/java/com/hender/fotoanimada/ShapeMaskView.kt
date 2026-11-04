package com.hender.fotoanimada

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Oscurece lo que queda fuera de la forma de la instantánea (corazón, estrella…). */
class ShapeMaskView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var shape: InstantShape = InstantShape.SQUARE
        set(value) {
            field = value
            invalidate()
        }

    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0000000.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        if (shape == InstantShape.SQUARE) return
        val s = min(width, height).toFloat()
        val x = (width - s) / 2f
        val y = (height - s) / 2f
        val inner = shape.path(s, x, y)
        val outside = Path().apply { addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW) }
        outside.op(inner, Path.Op.DIFFERENCE)
        canvas.drawPath(outside, shade)
        edge.strokeWidth = s * if (shape == InstantShape.STICKER) 0.035f else 0.012f
        canvas.drawPath(inner, edge)
    }
}
