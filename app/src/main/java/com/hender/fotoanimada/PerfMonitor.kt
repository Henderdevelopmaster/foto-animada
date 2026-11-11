package com.hender.fotoanimada

import android.os.Debug
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Panel de rendimiento (Ajustes > Rendimiento, apagado por defecto). Mide en el cel de verdad
 * para optimizar con datos y no a ciegas:
 * - cuadros por segundo de la cámara, de la pantalla y del análisis (Live),
 * - el peor cuadro del último segundo (si pasa de ~20 ms se nota un tironcito),
 * - cuánto tarda la foto desde el toque y cuánto en quedar lista,
 * - la memoria en uso.
 */
class PerfMonitor(private val view: TextView) {

    /** Cuadros que entrega la cámara (se cuentan desde su hilo). */
    val camFrames = AtomicInteger()

    /** Total de cuadros analizados (lo lleva [MotionRecorder]). */
    var analyzed: () -> Int = { 0 }

    /** Línea extra con el estado (ZSL, Live, Viva…). */
    var info: () -> String = { "" }

    private var running = false
    private var uiFrames = 0
    private var lastFrameNs = 0L
    private var worstNs = 0L
    private var lastTick = 0L
    private var lastAna = 0

    @Volatile private var pressedAt = 0L
    @Volatile private var capturedAt = 0L
    @Volatile private var shotMs = -1L
    @Volatile private var readyMs = -1L
    @Volatile private var waiting = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            if (lastFrameNs != 0L) worstNs = maxOf(worstNs, t - lastFrameNs)
            lastFrameNs = t
            uiFrames++
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            update()
            view.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastTick = SystemClock.elapsedRealtime()
        camFrames.set(0)
        uiFrames = 0
        lastFrameNs = 0L
        worstNs = 0L
        lastAna = analyzed()
        view.text = "Midiendo…"
        view.visibility = View.VISIBLE
        Choreographer.getInstance().postFrameCallback(frame)
        view.postDelayed(tick, 1000)
    }

    fun stop() {
        running = false
        view.removeCallbacks(tick)
        Choreographer.getInstance().removeFrameCallback(frame)
        view.visibility = View.GONE
    }

    /** Se tocó el botón (o el de volumen). */
    fun pressed() {
        pressedAt = SystemClock.elapsedRealtime()
        capturedAt = 0L
        waiting = true
    }

    /** La cámara empezó a tomar la foto. */
    fun captured() {
        val p = pressedAt
        if (p == 0L || capturedAt != 0L) return
        capturedAt = SystemClock.elapsedRealtime()
        shotMs = capturedAt - p
    }

    /** La foto quedó guardada en su versión final. */
    fun ready() {
        val p = pressedAt
        if (p == 0L || !waiting) return
        readyMs = SystemClock.elapsedRealtime() - p
        waiting = false
    }

    private fun update() {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTick).coerceAtLeast(1L) / 1000f
        lastTick = now
        val cam = camFrames.getAndSet(0) / dt
        val ui = uiFrames / dt
        uiFrames = 0
        val worst = worstNs / 1_000_000L
        worstNs = 0L
        val a = analyzed()
        val ana = (a - lastAna).coerceAtLeast(0) / dt
        lastAna = a
        val rt = Runtime.getRuntime()
        val heap = (rt.totalMemory() - rt.freeMemory()) shr 20
        val max = rt.maxMemory() shr 20
        val native = Debug.getNativeHeapAllocatedSize() shr 20
        view.text = buildString {
            append(String.format(Locale.US, "Cámara %.0f fps · Análisis %.0f fps\n", cam, ana))
            append(String.format(Locale.US, "Pantalla %.0f fps · peor cuadro %d ms\n", ui, worst))
            append("Disparo ").append(ms(shotMs, waiting && capturedAt == 0L))
            append(" · Lista ").append(ms(readyMs, waiting)).append('\n')
            append("Memoria ").append(heap).append('/').append(max).append(" MB · nativa ").append(native).append(" MB")
            val extra = info()
            if (extra.isNotEmpty()) append('\n').append(extra)
        }
    }

    private fun ms(v: Long, pending: Boolean): String = when {
        pending -> "…"
        v < 0 -> "sin datos"
        v < 1000 -> "$v ms"
        else -> String.format(Locale.US, "%.1f s", v / 1000f)
    }
}
