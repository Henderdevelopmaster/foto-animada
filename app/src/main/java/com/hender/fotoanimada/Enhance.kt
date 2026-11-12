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
 * Mejora automática de cada foto (ver [EnhanceCore]). Va activada por defecto y se puede
 * apagar en Ajustes para comparar. Viva (más nitidez y color) va aparte: solo si el usuario
 * la prende con su botón.
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

    /**
     * Devuelve la foto mejorada. Si algo falla, devuelve la original tal cual.
     * [aim] > 0 = de noche: la foto se sube igual que se veía la vista previa ([Brightness]).
     * [vivid] = Viva prendida (más nitidez y color). [auto] = la mejora automática (con ella
     * apagada y Viva prendida, la foto lleva solo Viva). [lens] = ojo de pez ([Fisheye]): va
     * después de la mejora, en la misma pasada (se codifica una sola vez).
     */
    fun jpeg(
        context: Context, jpeg: ByteArray, night: Boolean = false, sunset: Boolean = false,
        aim: Float = 0f, maxGain: Float = 1f, vivid: Boolean = false,
        auto: Boolean = enabled(context) || night || sunset, lens: Int = Fisheye.OFF,
    ): ByteArray {
        if (!auto && !vivid) return Lens.jpeg(jpeg, lens)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            // Las fotos gigantes (modo MÁX) se dejan con todo su detalle
            if (bounds.outWidth.toLong() * bounds.outHeight > 17_000_000L) return Lens.jpeg(jpeg, lens)
            val bmp = Processing.decode(jpeg, 8192)
            val out = Lens.apply(bitmap(context, bmp, night, sunset, aim, maxGain, vivid, auto), lens)
            val bytes = Processing.encode(out, 95)
            out.recycle()
            bytes
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo mejorar la foto", e)
            Lens.jpeg(jpeg, lens) // sin la mejora, pero con el efecto que se veía
        }
    }

    /** Mejora un bitmap mutable en el lugar y lo devuelve. */
    fun bitmap(
        context: Context, bmp: Bitmap, night: Boolean = false, sunset: Boolean = false,
        aim: Float = 0f, maxGain: Float = 1f, vivid: Boolean = false,
        auto: Boolean = enabled(context) || night || sunset,
    ): Bitmap {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val faces = faces(bmp)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val rep = EnhanceCore.process(px, w, h, EnhanceCore.Options(
            night = night, sunset = sunset, faces = faces, threads = threads, aim = aim, maxGain = maxGain,
            naturalSkin = !Beauty.enabled, strength = if (auto) 1f else 0f, vivid = if (vivid) 1f else 0f,
        ))
        Log.i(TAG, "Mejora ${w}x$h en ${android.os.SystemClock.elapsedRealtime() - t0} ms (caras ${faces.size}, " +
            "noche=$night, viva=$vivid, auto=$auto): $rep")
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
