package com.hender.fotoanimada

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * Mejora automática de cada foto (ver [EnhanceCore]). Va siempre activada,
 * aunque se puede apagar en el panel de Calidad para comparar.
 */
object Enhance {

    private const val TAG = "Enhance"

    fun enabled(c: Context) = CameraSettings.get(c, CameraSettings.ENHANCE, "on") == "on"

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    /** Devuelve la foto mejorada. Si algo falla, devuelve la original tal cual. */
    fun jpeg(context: Context, jpeg: ByteArray, night: Boolean = false, sunset: Boolean = false): ByteArray {
        if (!enabled(context) && !sunset && !night) return jpeg
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            // Las fotos gigantes (modo MÁX) se dejan con todo su detalle
            if (bounds.outWidth.toLong() * bounds.outHeight > 17_000_000L) return jpeg
            val bmp = Processing.decode(jpeg, 8192)
            val out = bitmap(context, bmp, night, sunset)
            val bytes = Processing.encode(out, 95)
            out.recycle()
            bytes
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo mejorar la foto", e)
            jpeg
        }
    }

    /** Mejora un bitmap mutable en el lugar y lo devuelve. */
    fun bitmap(context: Context, bmp: Bitmap, night: Boolean = false, sunset: Boolean = false): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val faces = faces(bmp)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val rep = EnhanceCore.process(px, w, h, EnhanceCore.Options(night = night, sunset = sunset, faces = faces, threads = threads))
        Log.d(TAG, "${w}x$h $rep")
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Caras con ML Kit sobre una copia pequeña (rápido). */
    private fun faces(bmp: Bitmap): List<EnhanceCore.Face> = try {
        val k = 640f / maxOf(bmp.width, bmp.height)
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        val found = Tasks.await(detector.process(InputImage.fromBitmap(small, 0)), 3, TimeUnit.SECONDS)
        val sw = small.width.toFloat()
        val sh = small.height.toFloat()
        val list = found.map {
            val b = it.boundingBox
            EnhanceCore.Face(b.exactCenterX() / sw, b.exactCenterY() / sh, b.width() / sw, b.height() / sh)
        }
        if (small !== bmp) small.recycle()
        list
    } catch (e: Throwable) {
        Log.w(TAG, "Sin detección de caras: ${e.message}")
        emptyList()
    }
}
