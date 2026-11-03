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
import android.util.Log
import android.view.Surface
import android.view.TextureView
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
 * Modo EFECTOS: cada cuadro de la cámara pasa por aquí.
 * Detecta caras, dibuja el efecto, lo muestra en pantalla y, si se está grabando, lo manda al video.
 */
class EffectsEngine(private val context: Context, private val view: TextureView) : ImageAnalysis.Analyzer {

    @Volatile
    var effect: FaceEffect? = null

    @Volatile
    var mirror = true

    @Volatile
    var audio: AudioRingRecorder? = null

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
    )
    private val renderer = EffectRenderer(context)
    private var upright: Bitmap? = null
    private var out: Bitmap? = null
    private val m = Matrix()

    // Grabación
    private val recStart = AtomicBoolean(false)
    private val recStop = AtomicReference<((MotionRecorder.Clip?) -> Unit)?>(null)
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

    override fun analyze(image: ImageProxy) {
        try {
            val fx = effect ?: return
            val rot = image.imageInfo.rotationDegrees
            val src = image.toBitmap()
            val uw = if (rot % 180 == 0) src.width else src.height
            val uh = if (rot % 180 == 0) src.height else src.width

            // Imagen derecha y espejada (selfie), como se ve en pantalla
            var up = upright
            if (up == null || up.width != uw || up.height != uh) {
                up = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
                upright = up
                out = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
            }
            m.reset()
            m.postRotate(rot.toFloat())
            val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            m.mapRect(r)
            m.postTranslate(-r.left, -r.top)
            if (mirror) m.postScale(-1f, 1f, uw / 2f, 0f)
            Canvas(up!!).drawBitmap(src, m, null)
            src.recycle()

            val faces = if (fx.isNone) emptyList() else try {
                Tasks.await(detector.process(InputImage.fromBitmap(up, 0)), 400, TimeUnit.MILLISECONDS)
                    .mapNotNull { FaceGeom.from(it, uw, false) }
            } catch (_: Exception) {
                emptyList()
            }

            val o = out!!
            renderer.render(Canvas(o), up, faces, fx, System.currentTimeMillis(), uw, uh)
            show(o)
            record(o)
        } catch (e: Exception) {
            Log.e("Effects", "Cuadro", e)
        } finally {
            image.close()
        }
    }

    /** Pinta el resultado en pantalla, pegado arriba como la vista previa normal. */
    private fun show(bmp: Bitmap) {
        if (!view.isAvailable) return
        val c = view.lockCanvas() ?: return
        try {
            c.drawColor(Color.BLACK)
            val s = minOf(c.width.toFloat() / bmp.width, c.height.toFloat() / bmp.height)
            val dx = (c.width - bmp.width * s) / 2f
            c.save()
            c.translate(dx, 0f)
            c.scale(s, s)
            c.drawBitmap(bmp, 0f, 0f, null)
            c.restore()
        } finally {
            view.unlockCanvasAndPost(c)
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
        detector.close()
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
            val faces = try {
                Tasks.await(det.process(InputImage.fromBitmap(photo, 0)), 8, TimeUnit.SECONDS)
                    .mapNotNull { FaceGeom.from(it, photo.width, false) }
            } catch (_: Exception) {
                emptyList()
            } finally {
                det.close()
            }
            val out = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            EffectRenderer(context).render(Canvas(out), photo, faces, effect, System.currentTimeMillis(), photo.width, photo.height)
            return out
        }
    }
}
