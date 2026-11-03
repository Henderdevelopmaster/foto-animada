package com.hender.fotoanimada

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2

/** Cuadrícula de tercios y nivel (la línea se pone verde cuando el cel está derecho). */
class GridOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs), SensorEventListener {

    /** Alto del área de la foto (desde arriba). 0 = toda la vista. */
    var areaHeight = 0
        set(value) {
            field = value; invalidate()
        }

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val level = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var roll = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        // Si el cel está casi acostado (boca arriba) el nivel no tiene sentido
        if (abs(z) > 8.5f) {
            roll = Float.NaN
        } else {
            val r = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
            roll = roll.takeIf { !it.isNaN() }?.let { it * 0.8f + r * 0.2f } ?: r
        }
        if (visibility == VISIBLE) invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = (if (areaHeight > 0) areaHeight else height).toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3, 0f, w * i / 3, h, grid)
            canvas.drawLine(0f, h * i / 3, w, h * i / 3, grid)
        }
        if (roll.isNaN()) return
        val ok = abs(roll) < 1.5f
        level.color = if (ok) 0xFF00E676.toInt() else 0xCCFFFFFF.toInt()
        val half = w * 0.12f
        canvas.save()
        canvas.translate(w / 2, h / 2)
        if (!ok) canvas.rotate(-roll)
        canvas.drawLine(-half, 0f, half, 0f, level)
        canvas.restore()
    }
}
