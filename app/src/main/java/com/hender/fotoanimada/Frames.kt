package com.hender.fotoanimada

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File

/** Fotogramas del clip, reducidos, para los efectos Bucle / Rebote / Exposición larga. */
class Frames(val bitmaps: List<Bitmap>, val fps: Float) {

    fun recycle() = bitmaps.forEach { it.recycle() }

    /** Promedio de todos los fotogramas: efecto "Exposición larga". */
    fun longExposure(): Bitmap? {
        if (bitmaps.isEmpty()) return null
        val w = bitmaps[0].width
        val h = bitmaps[0].height
        val n = w * h
        val r = IntArray(n)
        val g = IntArray(n)
        val b = IntArray(n)
        val px = IntArray(n)
        var count = 0
        for (bmp in bitmaps) {
            if (bmp.width != w || bmp.height != h) continue
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            for (i in 0 until n) {
                val c = px[i]
                r[i] += (c shr 16) and 0xFF
                g[i] += (c shr 8) and 0xFF
                b[i] += c and 0xFF
            }
            count++
        }
        if (count == 0) return null
        for (i in 0 until n) {
            px[i] = (0xFF shl 24) or ((r[i] / count) shl 16) or ((g[i] / count) shl 8) or (b[i] / count)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Saca hasta maxFrames fotogramas, lado mayor <= maxSide. */
        fun load(file: File, maxSide: Int = 720, maxFrames: Int = 60): Frames? {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.path)
                val count = mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
                )?.toIntOrNull() ?: return null
                val durMs = mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: return null
                val rotation = mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
                if (count <= 0 || durMs <= 0) return null

                val step = ((count + maxFrames - 1) / maxFrames).coerceAtLeast(1)
                val params = MediaMetadataRetriever.BitmapParams().apply {
                    preferredConfig = Bitmap.Config.RGB_565
                }
                val out = mutableListOf<Bitmap>()
                val chunk = 8
                var start = 0
                while (start < count) {
                    val n = minOf(chunk, count - start)
                    val batch = try {
                        mmr.getFramesAtIndex(start, n, params)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    batch.forEachIndexed { i, frame ->
                        if ((start + i) % step == 0) out += shrink(frame, maxSide, rotation)
                        if (!frame.isRecycled) frame.recycle()
                    }
                    start += n
                }
                if (out.isEmpty()) return null
                val fps = out.size / (durMs / 1000f)
                return Frames(out, fps.coerceIn(5f, 30f))
            } catch (_: Exception) {
                return null
            } finally {
                mmr.release()
            }
        }

        private fun shrink(src: Bitmap, maxSide: Int, rotation: Int): Bitmap {
            val m = Matrix()
            val longest = maxOf(src.width, src.height)
            val s = if (longest > maxSide) maxSide.toFloat() / longest else 1f
            m.postScale(s, s)
            // Si el decodificador no aplicó la rotación del video, la aplicamos aquí
            val needsRotate = (rotation == 90 || rotation == 270) && src.width > src.height
            if (needsRotate || rotation == 180) m.postRotate(rotation.toFloat())
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            return if (out === src) src.copy(src.config ?: Bitmap.Config.RGB_565, false) else out
        }
    }
}

/** Reproduce fotogramas en un ImageView en bucle o ida y vuelta (rebote). */
class FrameAnimator(private val view: ImageView) {
    private val handler = Handler(Looper.getMainLooper())
    private var frames: Frames? = null
    private var bounce = false
    private var index = 0
    private var dir = 1
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            val f = frames ?: return
            if (!running || f.bitmaps.isEmpty()) return
            view.setImageBitmap(f.bitmaps[index])
            val last = f.bitmaps.size - 1
            if (bounce) {
                if (index + dir !in 0..last) dir = -dir
                index = (index + dir).coerceIn(0, last)
            } else {
                index = if (index >= last) 0 else index + 1
            }
            handler.postDelayed(this, (1000f / f.fps).toLong())
        }
    }

    fun start(frames: Frames, bounce: Boolean) {
        stop()
        this.frames = frames
        this.bounce = bounce
        index = 0
        dir = 1
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }
}
