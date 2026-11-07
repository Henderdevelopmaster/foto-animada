package com.hender.fotoanimada

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/** Aro que gira alrededor de la miniatura mientras la foto se termina de procesar (como el iPhone). */
class SpinRing(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        color = 0x33FFFFFF
    }
    private var angle = 0f
    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 850
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    fun spin(on: Boolean) {
        if (on) {
            if (visibility != VISIBLE) {
                visibility = VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(150).start()
            }
            if (!anim.isStarted) anim.start()
        } else if (visibility == VISIBLE) {
            animate().alpha(0f).setDuration(200).withEndAction {
                anim.cancel()
                visibility = GONE
            }.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val i = paint.strokeWidth
        canvas.drawArc(i, i, width - i, height - i, 0f, 360f, false, track)
        canvas.drawArc(i, i, width - i, height - i, angle - 90f, 110f, false, paint)
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }
}
