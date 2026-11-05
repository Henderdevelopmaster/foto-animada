package com.hender.fotoanimada

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cuadro de enfoque tipo iPhone: cuadro amarillo con muescas y, al lado,
 * un solecito sobre una línea que sube o baja según el brillo elegido.
 */
class FocusBoxView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density

    /** Lado del cuadro. */
    val box = 74 * d

    /** Recorrido del sol hacia arriba o hacia abajo. */
    val travel = 58 * d

    /** Brillo de -1 (oscuro) a 1 (claro). Mueve el sol. */
    var level = 0f
        set(v) {
            field = v.coerceIn(-1f, 1f)
            invalidate()
        }

    /** Mostrar la línea completa (mientras se arrastra). */
    var dragging = false
        set(v) {
            field = v
            invalidate()
        }

    private val yellow = 0xFFFFD60A.toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * d
        color = yellow
        setShadowLayer(2f * d, 0f, 0f, 0x55000000)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = yellow
        setShadowLayer(2f * d, 0f, 0f, 0x55000000)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // para la sombrita
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((box + 44 * d).toInt(), (box + travel * 2 + 24 * d).toInt())
    }

    /** Centro del cuadro dentro de la vista (para ubicarla donde se tocó). */
    val boxCenterX get() = box / 2f + 2 * d
    val boxCenterY get() = height / 2f

    override fun onDraw(c: Canvas) {
        val cx = boxCenterX
        val cy = boxCenterY
        val h = box / 2f
        // Cuadro con muescas en la mitad de cada lado
        c.drawRect(cx - h, cy - h, cx + h, cy + h, stroke)
        val n = 5 * d
        c.drawLine(cx, cy - h, cx, cy - h + n, stroke)
        c.drawLine(cx, cy + h, cx, cy + h - n, stroke)
        c.drawLine(cx - h, cy, cx - h + n, cy, stroke)
        c.drawLine(cx + h, cy, cx + h - n, cy, stroke)

        // Sol y su línea
        val sx = cx + h + 20 * d
        val sy = cy - level * travel
        val r = 5.5f * d
        val gap = r + 6 * d
        if (dragging || level != 0f) {
            val top = cy - travel - 6 * d
            val bottom = cy + travel + 6 * d
            if (sy - gap > top) c.drawLine(sx, top, sx, sy - gap, stroke)
            if (sy + gap < bottom) c.drawLine(sx, sy + gap, sx, bottom, stroke)
        }
        c.drawCircle(sx, sy, r, fill)
        for (i in 0 until 8) {
            val a = i * PI / 4
            val x0 = sx + (r + 2.5f * d) * cos(a).toFloat()
            val y0 = sy + (r + 2.5f * d) * sin(a).toFloat()
            val x1 = sx + (r + 5.5f * d) * cos(a).toFloat()
            val y1 = sy + (r + 5.5f * d) * sin(a).toFloat()
            c.drawLine(x0, y0, x1, y1, stroke)
        }
    }
}
