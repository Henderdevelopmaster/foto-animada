package com.hender.fotoanimada

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Regla de exposición tipo iPhone: se desliza a los lados para subir o bajar
 * el brillo (de -2 a +2). La marca amarilla del centro es el valor actual.
 * Doble toque vuelve a 0.
 */
class ExposureDialView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density

    /** Paso del cel en EV (ej. 1/6 o 1/3). */
    var step = 1f / 3f
        set(v) {
            field = if (v > 0f) v else 1f / 3f
            invalidate()
        }

    /** Valor en EV, de -2 a +2. */
    var value = 0f
        private set

    /** Se llama cada vez que el valor cambia por el dedo. */
    var onChange: ((Float) -> Unit)? = null

    /** Para saber que el usuario la está tocando (no esconderla). */
    var onTouchState: ((Boolean) -> Unit)? = null

    private val pxPerEv = 70 * d
    private val maxEv = 2f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        strokeWidth = 1.3f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val major = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 10.5f * d
        textAlign = Paint.Align.CENTER
    }
    private val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        textSize = 13f * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setValue(ev: Float) {
        value = snap(ev)
        invalidate()
    }

    private fun snap(ev: Float): Float {
        val n = (ev / step).roundToInt()
        val limit = (maxEv / step).roundToInt()
        return n.coerceIn(-limit, limit) * step
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (66 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        c.drawRoundRect(RectF(10 * d, 14 * d, w - 10 * d, h - 2 * d), 18 * d, 18 * d, bg)
        val baseY = h - 14 * d
        // Rayitas cada 1/3 de paso, largas en los enteros
        val thirds = (maxEv * 3).toInt()
        for (i in -thirds..thirds) {
            val ev = i / 3f
            val x = cx + (ev - value) * pxPerEv
            if (x < 18 * d || x > w - 18 * d) continue
            val isMajor = i % 3 == 0
            val len = if (isMajor) 14 * d else 8 * d
            c.drawLine(x, baseY, x, baseY - len, if (isMajor) major else tick)
            if (isMajor && abs(x - cx) > 14 * d) {
                val t = if (i == 0) "0" else String.format(Locale.US, "%+d", i / 3)
                c.drawText(t, x, baseY - len - 4 * d, label)
            }
        }
        // Marca central amarilla y valor actual
        val tri = Path().apply {
            moveTo(cx - 5 * d, 16 * d)
            lineTo(cx + 5 * d, 16 * d)
            lineTo(cx, 22 * d)
            close()
        }
        c.drawPath(tri, yellow)
        c.drawRect(cx - 1.2f * d, 22 * d, cx + 1.2f * d, baseY, yellow)
        val txt = if (abs(value) < 1e-3f) "0.0" else String.format(Locale.US, "%+.1f", value)
        c.drawText(txt, cx, 12 * d, valueText)
    }

    private var downX = 0f
    private var startValue = 0f
    private var lastUp = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                startValue = value
                onTouchState?.invoke(true)
                // Doble toque: volver a 0
                if (e.eventTime - lastUp < 280 && abs(value) > 1e-3f) {
                    value = 0f
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onChange?.invoke(value)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val next = snap(startValue - (e.x - downX) / pxPerEv)
                if (next != value) {
                    value = next
                    // Vibración suave en cada tercio y más marcada en los enteros
                    val thirds = (value * 3f).roundToInt()
                    performHapticFeedback(
                        if (thirds % 3 == 0) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.TEXT_HANDLE_MOVE
                    )
                    onChange?.invoke(value)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastUp = e.eventTime
                onTouchState?.invoke(false)
            }
        }
        return true
    }
}
