package com.hender.fotoanimada

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Capa que dibuja stickers y partículas encima de la vista previa normal.
 * Así la cámara va fluida (la pinta la GPU) y solo los stickers se dibujan aquí.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val renderer = EffectRenderer(context)

    @Volatile
    var faces: List<FaceGeom> = emptyList()

    @Volatile
    var effect: FaceEffect? = null

    /** Ancho de la imagen analizada (para convertir a pixeles de la vista). */
    @Volatile
    var imageWidth = 0

    override fun onDraw(canvas: Canvas) {
        val fx = effect ?: return
        if (imageWidth <= 0) return
        val s = width.toFloat() / imageWidth
        renderer.drawOverlay(canvas, faces, fx, System.currentTimeMillis(), s, s)
        if (fx.particles != null) postInvalidateOnAnimation()
    }
}

/**
 * Modo EFECTOS: recibe los cuadros de la cámara.
 * - La detección de caras corre aparte (no frena la imagen).
 * - Si el efecto solo pone stickers, se pintan encima de la vista previa normal.
 * - Si el efecto cambia la imagen (deformar, glitch, espejo) se dibuja el cuadro completo.
 * - Al grabar, cada cuadro con su efecto va al video.
 */
class EffectsEngine(
    private val context: Context,
    private val frameView: TextureView,
    private val overlay: FaceOverlayView,
) : ImageAnalysis.Analyzer {

    @Volatile
    var effect: FaceEffect? = null
        set(v) {
            field = v
            overlay.effect = v
            overlay.post { updateViews() }
        }

    @Volatile
    var mirror = true

    @Volatile
    var audio: AudioRingRecorder? = null

    private fun options(contours: Boolean) = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(if (contours) FaceDetectorOptions.CONTOUR_MODE_ALL else FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.15f)
        .build()

    // Sin contornos es mucho más rápido; solo se usan si el efecto reacciona a la boca
    private val fast: FaceDetector = FaceDetection.getClient(options(false))
    private val withContours: FaceDetector = FaceDetection.getClient(options(true))
    private val detecting = AtomicBoolean(false)

    @Volatile
    private var faces: List<FaceGeom> = emptyList()
    private var lastFaceMs = 0L

    private val renderer = EffectRenderer(context)
    private var upright: Bitmap? = null
    private var small: Bitmap? = null
    private var out: Bitmap? = null
    private val m = Matrix()

    // Grabación
    private val recStart = AtomicBoolean(false)
    private val recStop = AtomicReference<((MotionRecorder.Clip?) -> Unit)?>(null)

    @Volatile
    private var recording = false
    private var encoder: MediaCodec? = null
    private var encSurface: Surface? = null
    private var encFormat: MediaFormat? = null
    private val samples = mutableListOf<MotionRecorder.Sample>()
    private var ew = 0
    private var eh = 0

    fun startRecording() = recStart.set(true)

    fun stopRecording(cb: (MotionRecorder.Clip?) -> Unit) {
        recStop.getAndSet(cb)?.invoke(null)
    }

    private fun fullFrame(fx: FaceEffect) = EffectRenderer.needsFrame(fx)

    /** Muestra la capa de cuadro completo o la de stickers según el efecto. */
    private fun updateViews() {
        val fx = effect ?: return
        val full = fullFrame(fx)
        frameView.visibility = if (full) View.VISIBLE else View.INVISIBLE
        overlay.visibility = if (full) View.INVISIBLE else View.VISIBLE
        overlay.invalidate()
    }

    override fun analyze(image: ImageProxy) {
        try {
            val fx = effect ?: return
            val rot = image.imageInfo.rotationDegrees
            val src = image.toBitmap()
            val uw = if (rot % 180 == 0) src.width else src.height
            val uh = if (rot % 180 == 0) src.height else src.width

            var up = upright
            if (up == null || up.width != uw || up.height != uh) {
                up = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
                upright = up
                out = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
                small = Bitmap.createBitmap(uw / 2, uh / 2, Bitmap.Config.ARGB_8888)
            }
            // Imagen derecha y espejada (selfie), como se ve en pantalla
            m.reset()
            m.postRotate(rot.toFloat())
            val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            m.mapRect(r)
            m.postTranslate(-r.left, -r.top)
            if (mirror) m.postScale(-1f, 1f, uw / 2f, 0f)
            Canvas(up!!).drawBitmap(src, m, null)
            src.recycle()
            overlay.imageWidth = uw

            detectAsync(up, fx, uw)

            // Si no hay cara hace rato, quitar los stickers
            if (System.currentTimeMillis() - lastFaceMs > 600 && faces.isNotEmpty()) {
                faces = emptyList()
                overlay.faces = faces
                overlay.postInvalidate()
            }

            val needOut = fullFrame(fx) || recording || recStart.get() || recStop.get() != null
            if (needOut) {
                val o = out!!
                renderer.render(Canvas(o), up, faces, fx, System.currentTimeMillis(), uw, uh)
                if (fullFrame(fx)) show(o)
                record(o)
            }
        } catch (e: Exception) {
            Log.e("Effects", "Cuadro", e)
        } finally {
            image.close()
        }
    }

    /** Detecta caras sobre una copia pequeña, sin hacer esperar al cuadro. */
    private fun detectAsync(up: Bitmap, fx: FaceEffect, uw: Int) {
        if (fx.isNone || !detecting.compareAndSet(false, true)) return
        val sm = small!!
        Canvas(sm).drawBitmap(up, null, Rect(0, 0, sm.width, sm.height), null)
        val copy = sm.copy(Bitmap.Config.ARGB_8888, false)
        val needMouth = fx.stickers.any { it.trigger == "mouthOpen" }
        val det = if (needMouth) withContours else fast
        val k = uw.toFloat() / copy.width
        det.process(InputImage.fromBitmap(copy, 0))
            .addOnSuccessListener { list ->
                val prev = faces
                val geoms = list.mapNotNull { FaceGeom.from(it, copy.width, false)?.scaled(k) }
                    .mapIndexed { i, g -> g.smoothFrom(prev.getOrNull(i), 0.65f) }
                if (geoms.isNotEmpty()) lastFaceMs = System.currentTimeMillis()
                faces = geoms
                overlay.faces = geoms
                overlay.postInvalidate()
            }
            .addOnCompleteListener {
                copy.recycle()
                detecting.set(false)
            }
    }

    private fun show(bmp: Bitmap) {
        if (!frameView.isAvailable) return
        val c = frameView.lockCanvas() ?: return
        try {
            c.drawColor(Color.BLACK)
            val s = minOf(c.width.toFloat() / bmp.width, c.height.toFloat() / bmp.height)
            c.save()
            c.scale(s, s)
            c.drawBitmap(bmp, 0f, 0f, null)
            c.restore()
        } finally {
            frameView.unlockCanvasAndPost(c)
        }
    }

    private fun record(frame: Bitmap) {
        if (recStart.getAndSet(false) && encoder == null) {
            try {
                ew = (frame.width / 16) * 16
                eh = (frame.height / 16) * 16
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ew, eh).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encSurface = e.createInputSurface()
                e.start()
                encoder = e
                encFormat = null
                samples.clear()
                recording = true
            } catch (ex: Exception) {
                Log.e("Effects", "Encoder", ex)
                encoder = null
            }
        }
        val e = encoder
        val surf = encSurface
        if (e != null && surf != null) {
            val c = surf.lockHardwareCanvas()
            c.drawBitmap(frame, null, Rect(0, 0, ew, eh), null)
            surf.unlockCanvasAndPost(c)
            drain(e, false)
        }
        recStop.getAndSet(null)?.let { cb ->
            recording = false
            if (e == null) {
                cb(null)
            } else {
                try {
                    e.signalEndOfInputStream()
                    drain(e, true)
                } catch (_: Exception) {
                }
                try {
                    e.stop()
                } catch (_: Exception) {
                }
                e.release()
                encSurface?.release()
                encoder = null
                encSurface = null
                val fmt = encFormat
                val list = samples.toList()
                samples.clear()
                if (fmt == null || list.none { it.isKey }) {
                    cb(null)
                } else {
                    val a = audio?.snapshot(list.first().ptsUs, list.last().ptsUs)
                    cb(MotionRecorder.Clip(fmt, list, list.first().ptsUs, 0, a))
                }
            }
        }
    }

    private fun drain(e: MediaCodec, end: Boolean) {
        val info = MediaCodec.BufferInfo()
        var tries = 0
        while (true) {
            val o = e.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++tries > 50) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> encFormat = e.outputFormat
                o >= 0 -> {
                    val buf = e.getOutputBuffer(o)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !config) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        samples += MotionRecorder.Sample(bytes, info.presentationTimeUs, info.flags)
                    }
                    e.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun release() {
        recStop.getAndSet(null)?.invoke(null)
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        encoder?.release()
        encoder = null
        encSurface?.release()
        encSurface = null
        fast.close()
        withContours.close()
    }

    companion object {
        /** Aplica el efecto a una foto de alta calidad (ya derecha y espejada si es selfie). */
        fun renderPhoto(context: Context, photo: Bitmap, effect: FaceEffect): Bitmap {
            val det = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .build()
            )
            // Detectar en una copia reducida (rápido) y escalar el resultado
            val k = (1280f / maxOf(photo.width, photo.height)).coerceAtMost(1f)
            val probe = if (k < 1f) Bitmap.createScaledBitmap(photo, (photo.width * k).toInt(), (photo.height * k).toInt(), true) else photo
            val faces = try {
                Tasks.await(det.process(InputImage.fromBitmap(probe, 0)), 8, TimeUnit.SECONDS)
                    .mapNotNull { FaceGeom.from(it, probe.width, false)?.scaled(1f / k) }
            } catch (_: Exception) {
                emptyList()
            } finally {
                det.close()
                if (probe !== photo) probe.recycle()
            }
            val out = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            EffectRenderer(context).render(Canvas(out), photo, faces, effect, System.currentTimeMillis(), photo.width, photo.height)
            return out
        }
    }
}
