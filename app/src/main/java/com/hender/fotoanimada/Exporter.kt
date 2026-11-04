package com.hender.fotoanimada

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Guardar videos y exportar efectos como video o GIF. */
object Exporter {

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun insert(context: Context, collection: Uri, name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, MotionPhotoWriter.FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("No se pudo crear el archivo")
    }

    private fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun saveVideoBytes(context: Context, bytes: ByteArray, suffix: String = ""): Uri {
        val uri = insert(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VID_${stamp()}$suffix.mp4", "video/mp4")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        publish(context, uri)
        return uri
    }

    fun saveVideoFile(context: Context, file: File, suffix: String = ""): Uri =
        saveVideoBytes(context, file.readBytes(), suffix)

    fun saveGif(context: Context, file: File): Uri {
        val uri = insert(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "GIF_${stamp()}.gif", "image/gif")
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        publish(context, uri)
        return uri
    }

    fun savePng(context: Context, bmp: Bitmap, prefix: String): Uri {
        val uri = insert(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${prefix}_${stamp()}.png", "image/png")
        context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        publish(context, uri)
        return uri
    }

    /** Orden de fotogramas: bucle normal o ida y vuelta. */
    private fun sequence(n: Int, bounce: Boolean, loops: Int): List<Int> {
        val one = if (bounce) (0 until n) + (n - 2 downTo 1) else (0 until n).toList()
        return (0 until loops).flatMap { one }
    }

    /** Convierte fotogramas en un MP4 (sin sonido). */
    fun framesToMp4(frames: Frames, bounce: Boolean, filter: PhotoFilter, out: File) {
        val first = frames.bitmaps.first()
        val w = (first.width / 16) * 16
        val h = (first.height / 16) * 16
        val fps = frames.fps
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var written = 0
        val frameUs = (1_000_000f / fps).toLong()
        val info = MediaCodec.BufferInfo()
        val paint = filter.paint()
        val dst = Rect(0, 0, w, h)

        fun drain(end: Boolean) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(enc.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            info.presentationTimeUs = written * frameUs
                            written++
                            muxer.writeSampleData(track, buf, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (i in sequence(frames.bitmaps.size, bounce, if (bounce) 3 else 3)) {
                val canvas = surface.lockHardwareCanvas()
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(frames.bitmaps[i], null, dst, paint)
                surface.unlockCanvasAndPost(canvas)
                Thread.sleep(8)
                drain(false)
            }
            enc.signalEndOfInputStream()
            drain(true)
        } finally {
            enc.stop()
            enc.release()
            surface.release()
            if (track >= 0) muxer.stop()
            muxer.release()
        }
    }

    /**
     * Arma un MP4 (sin sonido) dibujando cada fotograma con [draw].
     * Lo usa el boomerang de las instantáneas.
     */
    fun renderMp4(w0: Int, h0: Int, fps: Float, count: Int, out: File, draw: (Canvas, Int, Rect) -> Unit) {
        val w = (w0 / 16) * 16
        val h = (h0 / 16) * 16
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var written = 0
        val frameUs = (1_000_000f / fps).toLong()
        val info = MediaCodec.BufferInfo()
        val dst = Rect(0, 0, w, h)

        fun drain(end: Boolean) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(enc.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            info.presentationTimeUs = written * frameUs
                            written++
                            muxer.writeSampleData(track, buf, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (i in 0 until count) {
                val canvas = surface.lockHardwareCanvas()
                canvas.drawColor(Color.BLACK)
                draw(canvas, i, dst)
                surface.unlockCanvasAndPost(canvas)
                Thread.sleep(8)
                drain(false)
            }
            enc.signalEndOfInputStream()
            drain(true)
        } finally {
            enc.stop()
            enc.release()
            surface.release()
            if (track >= 0) muxer.stop()
            muxer.release()
        }
    }

    /**
     * Video vertical 9:16 para historias: la foto centrada y, de fondo, ella
     * misma desenfocada y oscurecida, para que llene la pantalla sin franjas.
     */
    fun framesToStory(frames: Frames, bounce: Boolean, filter: PhotoFilter, out: File, loops: Int = 3) {
        val w = 1080
        val h = 1920
        val seq = sequence(frames.bitmaps.size, bounce, loops)
        // Fondo: versiones diminutas que el lienzo estira (sale desenfocado y pesa nada)
        val tiny = frames.bitmaps.map {
            Bitmap.createScaledBitmap(it, max(1, it.width / 16), max(1, it.height / 16), true)
        }
        val paint = filter.paint()
        val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = filter.paint().colorFilter
        }
        val dark = Paint().apply { color = 0x66000000 }
        val first = frames.bitmaps.first()
        val k = min(w.toFloat() / first.width, h * 0.72f / first.height)
        val fw = first.width * k
        val fh = first.height * k
        val dst = RectF((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
        try {
            renderMp4(w, h, frames.fps, seq.size, out) { c, i, _ ->
                val f = seq[i]
                // Fondo que cubre toda la pantalla
                val t = tiny[f]
                val kk = max(w.toFloat() / t.width, h.toFloat() / t.height)
                val bw = t.width * kk
                val bh = t.height * kk
                c.drawBitmap(t, null, RectF((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f), bgPaint)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dark)
                c.drawBitmap(frames.bitmaps[f], null, dst, paint)
            }
        } finally {
            tiny.forEach { it.recycle() }
        }
    }

    /** Convierte fotogramas en un GIF animado que se repite siempre. */
    fun framesToGif(frames: Frames, bounce: Boolean, filter: PhotoFilter, out: File, maxSide: Int = 360) {
        val src = frames.bitmaps.first()
        val k = maxSide.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * k).toInt().coerceAtLeast(2)
        val h = (src.height * k).toInt().coerceAtLeast(2)
        // Máximo ~15 fps para que no pese tanto
        val skip = if (frames.fps > 16) 2 else 1
        val fps = frames.fps / skip
        val idx = (frames.bitmaps.indices step skip).toList()
        val order = if (bounce) idx + idx.reversed().drop(1).dropLast(1) else idx
        val delayCs = (100f / fps).toInt().coerceAtLeast(2)

        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val paint = filter.paint()
        val dst = Rect(0, 0, w, h)
        val px = IntArray(w * h)

        BufferedOutputStream(out.outputStream()).use { os ->
            val gif = GifWriter(os, w, h)
            for (i in order) {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(frames.bitmaps[i], null, dst, paint)
                frame.getPixels(px, 0, w, 0, 0, w, h)
                gif.addFrame(px, delayCs)
            }
            gif.finish()
        }
        frame.recycle()
    }
}

/** Codificador GIF sencillo: paleta fija de 216 colores con tramado ordenado y LZW. */
class GifWriter(private val out: OutputStream, private val w: Int, private val h: Int) {

    private val indices = ByteArray(w * h)
    private val table = IntArray(4096 * 256)
    private val stamp = IntArray(4096 * 256)
    private var gen = 0

    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(w); short(h)
        out.write(0xF7) // tabla global de 256 colores
        out.write(0); out.write(0)
        for (i in 0 until 256) {
            if (i < 216) {
                out.write((i / 36) * 51); out.write(((i / 6) % 6) * 51); out.write((i % 6) * 51)
            } else {
                out.write(0); out.write(0); out.write(0)
            }
        }
        // Repetir para siempre
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun short(v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun addFrame(argb: IntArray, delayCs: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = argb[y * w + x]
                val d = (bayer[(y and 3) * 4 + (x and 3)] - 8) * 3
                val r = q(((c shr 16) and 0xFF) + d)
                val g = q(((c shr 8) and 0xFF) + d)
                val b = q((c and 0xFF) + d)
                indices[y * w + x] = (r * 36 + g * 6 + b).toByte()
            }
        }
        // Control gráfico: retardo
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))
        short(delayCs)
        out.write(0); out.write(0)
        // Descriptor de imagen
        out.write(0x2C)
        short(0); short(0); short(w); short(h)
        out.write(0)
        lzw()
    }

    private fun q(v: Int): Int = ((v.coerceIn(0, 255) * 5 + 127) / 255)

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    // ----- LZW -----
    private val block = ByteArray(256)
    private var blockLen = 0
    private var accum = 0
    private var bits = 0
    private var nBits = 9
    private var maxCode = 511
    private var freeEnt = 258
    private var clearFlg = false

    private fun lzw() {
        val clear = 256
        val eoi = 257
        out.write(8)
        accum = 0; bits = 0; blockLen = 0
        nBits = 9; maxCode = 511; freeEnt = 258; clearFlg = false
        gen++
        output(clear)
        var ent = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val c = indices[i].toInt() and 0xFF
            val key = (ent shl 8) or c
            if (stamp[key] == gen) {
                ent = table[key]
                continue
            }
            output(ent)
            ent = c
            if (freeEnt < 4096) {
                table[key] = freeEnt++
                stamp[key] = gen
            } else {
                gen++
                freeEnt = 258
                clearFlg = true
                output(clear)
            }
        }
        output(ent)
        output(eoi)
        while (bits > 0) {
            byteOut(accum and 0xFF)
            accum = accum ushr 8
            bits = (bits - 8).coerceAtLeast(0)
        }
        flushBlock()
        out.write(0)
    }

    private fun output(code: Int) {
        accum = accum or (code shl bits)
        bits += nBits
        while (bits >= 8) {
            byteOut(accum and 0xFF)
            accum = accum ushr 8
            bits -= 8
        }
        if (freeEnt > maxCode || clearFlg) {
            if (clearFlg) {
                nBits = 9
                maxCode = 511
                clearFlg = false
            } else {
                nBits++
                maxCode = if (nBits == 12) 4096 else (1 shl nBits) - 1
            }
        }
    }

    private fun byteOut(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen >= 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }
}
