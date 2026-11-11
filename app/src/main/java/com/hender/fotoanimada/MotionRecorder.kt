package com.hender.fotoanimada

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicReference

/**
 * Graba video sin parar en memoria (buffer circular) y, cuando se le pide,
 * entrega un clip con ~1.5 s antes y ~1.5 s después del disparo.
 *
 * Todo corre en el hilo del analizador de CameraX.
 */
class MotionRecorder(
    private val preUs: Long = 1_500_000,
    private val postUs: Long = 1_500_000,
) : ImageAnalysis.Analyzer {

    class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int) {
        val isKey: Boolean get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    }

    /** Clip listo para meter en un MP4. keyPtsUs = momento del disparo. */
    class Clip(
        val format: MediaFormat,
        val samples: List<Sample>,
        val keyPtsUs: Long,
        val rotation: Int,
        val audio: AudioRingRecorder.Snapshot?,
    )

    internal class ClipRequest(val waitForMark: Boolean, onReady: (Clip?) -> Unit) {
        private val callback = AtomicReference<((Clip?) -> Unit)?>(onReady)
        val isComplete: Boolean get() = callback.get() == null

        fun complete(clip: Clip?) {
            callback.getAndSet(null)?.invoke(clip)
        }

        fun cancel() = complete(null)
    }

    /** Si es false no se graba nada (modo foto normal, ahorra batería). */
    @Volatile
    var enabled = true

    /** Fuente de audio opcional. */
    @Volatile
    var audio: AudioRingRecorder? = null

    /** De noche: el video sube de brillo igual que la vista previa (tablas de [Brightness]). */
    @Volatile
    var lumaLut: ByteArray? = null

    @Volatile
    var chromaLut: ByteArray? = null

    /** Selfie como en la pantalla: el video del Live se voltea igual que la vista previa. */
    @Volatile
    var mirror = false

    /** Cuadros analizados en total (para el panel de rendimiento). */
    private val analyzedCount = java.util.concurrent.atomic.AtomicInteger()
    val analyzedFrames: Int get() = analyzedCount.get()

    /**
     * Mirada rápida a cada cuadro antes de grabarlo (en el hilo del análisis). La usa el lector
     * de QR: así no hay que pedirle una copia de la pantalla a la GPU, que trababa la vista previa.
     */
    @Volatile
    var peek: ((ImageProxy) -> Unit)? = null

    private var codec: MediaCodec? = null
    private var outFormat: MediaFormat? = null
    private var width = 0
    private var height = 0
    private var rotation = 0
    private var rowTmp = ByteArray(0)
    private var cropX = 0
    private var cropY = 0

    private val buffer = ArrayDeque<Sample>()
    private val pending = AtomicReference<ClipRequest?>(null)
    private var activeRequest: ClipRequest? = null

    // Grabación larga (QuickTake)
    private val longStartReq = java.util.concurrent.atomic.AtomicBoolean(false)
    private val longStopReq = AtomicReference<((Clip?) -> Unit)?>(null)
    private var longActive = false
    private var longStartPts = 0L

    /** Time-lapse: 1 de cada N cuadros, reproducido a 30 fps. 0 = apagado. */
    @Volatile
    var timelapseStep = 0
    private var frameCounter = 0L
    private var synthPts = 0L

    /** Empieza a grabar sin recortar el buffer (mantener el obturador). */
    fun startLong() = longStartReq.set(true)

    /** Termina la grabación larga y entrega el clip completo. */
    fun stopLong(onReady: (Clip?) -> Unit) {
        longStopReq.getAndSet(onReady)?.invoke(null)
    }
    private var shutterPtsUs = -1L

    /** Pide un clip. El callback llega en el hilo del analizador. */
    // Momento exacto en que la cámara expuso la foto (llega un poco después del toque)
    private val shutterMark = java.util.concurrent.atomic.AtomicLong(-1)
    private var waitingMark = false
    private var requestAtPts = 0L

    /**
     * Pide un clip. Si waitForMark = true, el centro del clip será el instante que llegue
     * por markShutter() (cuando la cámara realmente toma la foto), no el momento del toque.
     */
    internal fun requestClip(waitForMark: Boolean = false, onReady: (Clip?) -> Unit): ClipRequest {
        markPrecise.set(false)
        shutterMark.set(-1)
        val request = ClipRequest(waitForMark, onReady)
        pending.getAndSet(request)?.cancel()
        return request
    }

    /** Ya llegó la hora exacta del sensor para esta foto: la aproximada no la pisa. */
    private val markPrecise = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Aproximado: la cámara avisó que empezó la foto ahora (reloj System.nanoTime / 1000). */
    fun markShutter(ptsUs: Long) {
        if (!markPrecise.get()) shutterMark.set(ptsUs)
    }

    /**
     * Exacto: la hora del sensor en que se expuso la foto (la misma con la que se marcan los
     * cuadros del video). Así el Live queda centrado en el mismo instante que la foto.
     */
    fun markShutterSensor(sensorNs: Long) {
        shutterMark.set(localNs(sensorNs) / 1000)
        markPrecise.set(true)
    }

    /**
     * Pasa la hora del sensor al reloj del video y del audio (System.nanoTime). Según el cel,
     * el sensor cuenta con el reloj normal o con el que incluye el tiempo dormido: se prueba
     * cuál da un retraso creíble (la foto/cuadro es de hace un momento, no del futuro).
     */
    private fun localNs(sensorNs: Long): Long {
        val mono = System.nanoTime()
        val boot = android.os.SystemClock.elapsedRealtimeNanos()
        val dMono = mono - sensorNs
        val dBoot = boot - sensorNs
        return when {
            dMono in -20_000_000L..2_000_000_000L -> sensorNs
            dBoot in -20_000_000L..2_000_000_000L -> sensorNs - (boot - mono)
            else -> mono - 70_000_000L
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            analyzedCount.incrementAndGet()
            try {
                peek?.invoke(image)
            } catch (e: Exception) {
                Log.w(TAG, "peek", e)
            }
            if (!enabled && !longActive && !longStartReq.get()) {
                if (buffer.isNotEmpty()) buffer.clear()
                pending.getAndSet(null)?.cancel()
                activeRequest?.cancel()
                activeRequest = null
                return
            }
            rotation = image.imageInfo.rotationDegrees
            // Usar solo el recorte común (ViewPort) para que el video tenga
            // el mismo encuadre que la foto. Tamaño múltiplo de 16 para el encoder.
            val crop = image.cropRect
            val cw = (crop.width() / 16) * 16
            val ch = (crop.height() / 16) * 16
            cropX = (crop.left + (crop.width() - cw) / 2) and 1.inv()
            cropY = (crop.top + (crop.height() - ch) / 2) and 1.inv()
            val enc = ensureEncoder(cw, ch)
            // Hora real del cuadro (cuando el sensor lo tomó), en el mismo reloj que el audio.
            // Antes se usaba la hora en que llegaba aquí (≈0.1 s tarde): el video del Live
            // quedaba corrido respecto a la foto
            var ptsUs = localNs(image.imageInfo.timestamp) / 1000
            val step = timelapseStep
            if (step > 0 && (longActive || longStartReq.get())) {
                frameCounter++
                if (frameCounter % step != 0L) return
                synthPts = maxOf(synthPts + 33_333, buffer.lastOrNull()?.ptsUs?.plus(33_333) ?: 0L)
                ptsUs = synthPts
            } else {
                // Que el reloj nunca vaya hacia atrás después de un time-lapse
                ptsUs = maxOf(ptsUs, synthPts + 33_333)
            }

            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val input = enc.getInputImage(idx)
                    ?: throw IllegalStateException("El encoder no expone Image de entrada")
                copyYuv(image, input)
                enc.queueInputBuffer(idx, 0, width * height * 3 / 2, ptsUs, 0)
            }
            drain(enc)
            handleRequests()
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando frame", e)
        } finally {
            image.close()
        }
    }

    fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        codec?.release()
        codec = null
        outFormat = null
        buffer.clear()
        pending.getAndSet(null)?.cancel()
        activeRequest?.cancel()
        activeRequest = null
    }

    private fun ensureEncoder(w: Int, h: Int): MediaCodec {
        codec?.let { if (w == width && h == height) return it }
        release()
        width = w
        height = h
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            // Bitrate según el tamaño (antes 10 Mbps fijo): 1280×960 ≈ 5.3 Mbps, igual de nítido
            // a 30 fps y menos trabajo para el codificador, que va prendido todo el rato con LIVE
            setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * 30 * 0.145).toInt().coerceIn(3_000_000, 9_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            // Un keyframe por segundo para poder cortar el buffer limpio
            // Keyframe cada medio segundo: el clip puede empezar justo 1.5 s antes de la foto
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        Log.i(TAG, "Encoder listo ${w}x$h")
        return c
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = enc.dequeueOutputBuffer(info, 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = enc.outputFormat
                o >= 0 -> {
                    val buf = enc.getOutputBuffer(o)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !isConfig) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        buffer.addLast(Sample(bytes, info.presentationTimeUs, info.flags))
                        if (activeRequest == null && !longActive) trim()
                    }
                    enc.releaseOutputBuffer(o, false)
                }
            }
        }
    }

    /** Deja en el buffer solo desde el keyframe que cubre los últimos preUs. */
    private fun trim() {
        while (buffer.isNotEmpty() && !buffer.first().isKey) buffer.removeFirst()
        val latest = buffer.lastOrNull()?.ptsUs ?: return
        while (true) {
            var nextKey = -1
            for (i in 1 until buffer.size) {
                if (buffer[i].isKey) {
                    nextKey = i; break
                }
            }
            if (nextKey > 0 && buffer[nextKey].ptsUs <= latest - preUs) {
                repeat(nextKey) { buffer.removeFirst() }
            } else {
                break
            }
        }
    }

    private fun handleRequests() {
        if (longStartReq.getAndSet(false)) {
            longActive = true
            longStartPts = buffer.lastOrNull()?.ptsUs ?: (System.nanoTime() / 1000)
            if (timelapseStep > 0) {
                // Arrancar limpio con un keyframe
                buffer.clear()
                longStartPts = 0
                codec?.setParameters(android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }
        longStopReq.getAndSet(null)?.let { cb ->
            if (!longActive) {
                cb(null)
            } else {
                longActive = false
                val fmt = outFormat
                val list = buffer.toList()
                var startIdx = if (timelapseStep > 0) -1 else list.indexOfLast { it.isKey && it.ptsUs <= longStartPts }
                if (startIdx < 0) startIdx = list.indexOfFirst { it.isKey }
                val samples = if (startIdx >= 0) list.subList(startIdx, list.size).toList() else emptyList()
                if (activeRequest == null) trim()
                if (fmt == null || samples.isEmpty()) {
                    cb(null)
                } else {
                    val a = if (timelapseStep > 0) null
                    else audio?.snapshot(samples.first().ptsUs, samples.last().ptsUs)
                    cb(Clip(fmt, samples, samples.first().ptsUs, rotation, a))
                }
            }
        }
        if (activeRequest?.isComplete == true) {
            activeRequest = null
            if (!longActive) trim()
        }
        pending.getAndSet(null)?.takeUnless { it.isComplete }?.let { req ->
            if (activeRequest != null) {
                req.cancel()
            } else {
                activeRequest = req
                shutterPtsUs = buffer.lastOrNull()?.ptsUs ?: -1L
                requestAtPts = shutterPtsUs
                waitingMark = req.waitForMark
            }
        }
        val req = activeRequest ?: return
        val latest = buffer.lastOrNull()?.ptsUs ?: return
        if (shutterPtsUs < 0) {
            shutterPtsUs = latest
            requestAtPts = latest
        }
        val mark = shutterMark.get()
        if (mark > 0 && waitingMark) {
            shutterPtsUs = mark
            waitingMark = false
        }
        // Esperar a saber cuándo se tomó la foto (máximo 1.5 s)
        if (waitingMark && latest - requestAtPts < 1_500_000) return
        if (latest >= shutterPtsUs + postUs) {
            val fmt = outFormat
            val samples = buffer.toList()
            activeRequest = null
            if (!longActive) trim()
            if (fmt == null || samples.isEmpty()) {
                req.cancel()
            } else {
                val a = audio?.snapshot(samples.first().ptsUs, samples.last().ptsUs)
                req.complete(Clip(fmt, samples, shutterPtsUs, rotation, a))
            }
        }
    }

    /** Copia YUV_420_888 de la cámara al Image de entrada del encoder respetando strides. */
    private fun copyYuv(src: ImageProxy, dst: Image) {
        val yLut = lumaLut
        val cLut = chromaLut
        // Volteo como espejo: con el cel vertical (rotación 90/270) el izquierda-derecha de la
        // pantalla son las filas del cuadro; acostado (0/180) son las columnas
        val rot = src.imageInfo.rotationDegrees
        val flipRows = mirror && (rot == 90 || rot == 270)
        val flipCols = mirror && (rot == 0 || rot == 180)
        for (p in 0 until 3) {
            val lut = if (p == 0) yLut else cLut
            val sp = src.planes[p]
            val dp = dst.planes[p]
            val w = if (p == 0) width else width / 2
            val h = if (p == 0) height else height / 2
            val sBuf = sp.buffer
            val dBuf = dp.buffer
            val sRow = sp.rowStride
            val sPix = sp.pixelStride
            val dRow = dp.rowStride
            val dPix = dp.pixelStride
            val srcRowLen = (w - 1) * sPix + 1
            if (rowTmp.size < srcRowLen) rowTmp = ByteArray(srcRowLen)
            val ox = if (p == 0) cropX else cropX / 2
            val oy = if (p == 0) cropY else cropY / 2

            for (y in 0 until h) {
                sBuf.position((y + oy) * sRow + ox * sPix)
                sBuf.get(rowTmp, 0, srcRowLen)
                if (lut != null) {
                    var x = 0
                    while (x < srcRowLen) {
                        rowTmp[x] = lut[rowTmp[x].toInt() and 255]
                        x += sPix
                    }
                }
                val dy = if (flipRows) h - 1 - y else y
                if (sPix == 1 && dPix == 1 && !flipCols) {
                    dBuf.position(dy * dRow)
                    dBuf.put(rowTmp, 0, w)
                } else {
                    val base = dy * dRow
                    for (x in 0 until w) {
                        val sx = if (flipCols) w - 1 - x else x
                        dBuf.put(base + x * dPix, rowTmp[sx * sPix])
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MotionRecorder"
    }
}
