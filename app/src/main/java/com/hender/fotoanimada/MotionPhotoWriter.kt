package com.hender.fotoanimada

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import android.content.Context
import android.media.MediaCodec
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arma el archivo Motion Photo de Google:
 * JPEG (con XMP que describe el contenedor) + MP4 pegado al final.
 * Google Fotos lo reconoce y lo reproduce como foto animada.
 */
object MotionPhotoWriter {

    const val FOLDER = "DCIM/Camaratrix"


    class Mp4(val bytes: ByteArray, val presentationUs: Long)

    fun muxToMp4(clip: MotionRecorder.Clip, cacheDir: File): Mp4 {
        val start = clip.samples.indexOfFirst { it.isKey }
        require(start >= 0) { "El clip no tiene keyframes" }
        val samples = clip.samples.subList(start, clip.samples.size)
        val base = samples.first().ptsUs

        val file = File(cacheDir, "clip.mp4")
        file.delete()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(clip.rotation)
        val videoTrack = muxer.addTrack(clip.format)
        val audioTrack = clip.audio?.let { muxer.addTrack(it.format) } ?: -1
        muxer.start()

        // Intercalar video y audio en orden de tiempo
        val end = samples.last().ptsUs
        val audioSamples = clip.audio?.samples?.filter { it.ptsUs in base..end } ?: emptyList()
        val all = samples.map { videoTrack to it } + audioSamples.map { audioTrack to it }
        val info = MediaCodec.BufferInfo()
        for ((track, s) in all.sortedBy { it.second.ptsUs }) {
            val flags = if (track == videoTrack && s.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, s.data.size, s.ptsUs - base, flags)
            muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
        }
        muxer.stop()
        muxer.release()

        val bytes = file.readBytes()
        file.delete()
        return Mp4(bytes, (clip.keyPtsUs - base).coerceAtLeast(0))
    }

    fun buildXmp(videoLength: Int, presentationUs: Long, filter: PhotoFilter = PhotoFilter.ORIGINAL): String = """
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="FotoAnimada">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    xmlns:Camaratrix="https://github.com/Henderdevelopmaster/camaratrix/ns/1.0/"
    Camaratrix:Filter="${filter.id}"
    GCamera:MotionPhoto="1"
    GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="$presentationUs"
    GCamera:MicroVideo="1"
    GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="$videoLength"
    GCamera:MicroVideoPresentationTimestampUs="$presentationUs">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
""".trim()

    /** Inserta un segmento APP1 XMP después de los APP0/APP1 (EXIF) iniciales del JPEG. */
    fun insertXmp(jpeg: ByteArray, xmp: String): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
            "La foto no es un JPEG válido"
        }
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) +
            xmp.toByteArray(Charsets.UTF_8)
        val segLen = payload.size + 2
        require(segLen <= 0xFFFF) { "XMP demasiado grande" }
        val segment = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            (segLen shr 8).toByte(), (segLen and 0xFF).toByte()
        ) + payload

        var pos = 2
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker != 0xE0 && marker != 0xE1) break
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            pos += 2 + len
        }
        return jpeg.copyOfRange(0, pos) + segment + jpeg.copyOfRange(pos, jpeg.size)
    }

    /** Guarda en DCIM/FotoAnimada. Si mp4 es null guarda solo la foto. */
    fun save(context: Context, jpeg: ByteArray, mp4: ByteArray?, suffix: String = ""): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = if (mp4 != null) "MVIMG_$stamp$suffix.jpg" else "IMG_$stamp$suffix.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear el archivo en la galería")
        resolver.openOutputStream(uri)!!.use { out ->
            out.write(jpeg)
            if (mp4 != null) out.write(mp4)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private val offsetRegex = Regex("""GCamera:MicroVideoOffset="(\d+)"""")
    private val lengthRegex = Regex("""Item:Semantic="MotionPhoto"[^>]*?Item:Length="(\d+)"""")

    /** Saca el MP4 de una foto animada. Devuelve null si es una foto normal. */
    fun extractVideo(context: Context, uri: Uri, out: File): File? {
        if (out.exists() && out.length() > 0) return out
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val head = String(bytes, 0, minOf(bytes.size, 128 * 1024), Charsets.ISO_8859_1)
        val len = (offsetRegex.find(head) ?: lengthRegex.find(head))
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (len <= 0 || len >= bytes.size) return null
        out.writeBytes(bytes.copyOfRange(bytes.size - len, bytes.size))
        return out
    }

    private val filterRegex = Regex("""Camaratrix:Filter="([a-z]+)"""")

    /** Filtro guardado en el XMP (para pintar el video con el mismo color que la foto). */
    fun readFilter(context: Context, uri: Uri): PhotoFilter = try {
        val head = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(128 * 1024)
            val n = input.read(buf)
            if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
        } ?: ""
        PhotoFilter.fromId(filterRegex.find(head)?.groupValues?.get(1))
    } catch (_: Exception) {
        PhotoFilter.ORIGINAL
    }

    private fun decode(jpeg: ByteArray, maxSide: Int = 0): Bitmap {
        val src = ImageDecoder.createSource(ByteBuffer.wrap(jpeg))
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (maxSide in 1 until longest) {
                val k = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
            }
        }
    }

    private fun encode(bmp: Bitmap, quality: Int = 95): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Aplica el filtro a la foto (si es Original devuelve la misma). */
    fun applyFilter(jpeg: ByteArray, filter: PhotoFilter): ByteArray {
        if (filter == PhotoFilter.ORIGINAL) return jpeg
        // Con fotos gigantes (200 MP) se reduce a 5000 px para no quedarse sin memoria
        val src = decode(jpeg, maxSide = 5000)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, filter.paint())
        src.recycle()
        val bytes = encode(out)
        out.recycle()
        return bytes
    }

    /** Arma una foto instantánea: recorte cuadrado, marco blanco y fecha abajo. */
    fun makeInstant(jpeg: ByteArray, filter: PhotoFilter): Bitmap {
        val src = decode(jpeg, maxSide = 2400)
        val side = minOf(src.width, src.height)
        val photoSize = 1600
        val border = 90
        val bottom = 380
        val w = photoSize + border * 2
        val h = photoSize + border + bottom
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(250, 249, 245))

        val srcRect = Rect(
            (src.width - side) / 2, (src.height - side) / 2,
            (src.width + side) / 2, (src.height + side) / 2
        )
        val dst = Rect(border, border, border + photoSize, border + photoSize)
        c.drawBitmap(src, srcRect, dst, filter.paint())
        src.recycle()

        // Sombra interior suave sobre la foto
        val inner = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(40, 0, 0, 0)
        }
        c.drawRect(dst, inner)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 70, 75)
            textSize = 64f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        val date = SimpleDateFormat("d MMM yyyy", Locale("es")).format(Date())
        c.drawText(date, w / 2f, border + photoSize + bottom * 0.58f, text)
        return out
    }

    fun encodeInstant(bmp: Bitmap): ByteArray = encode(bmp, 93)

    /**
     * Cambia la portada de una foto animada por el fotograma en timeUs del clip.
     * Solo funciona con fotos de la app (Android no deja reescribir las de otras apps).
     */
    fun replaceKeyPhoto(context: Context, uri: Uri, timeUs: Long): Boolean {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val head = String(bytes, 0, minOf(bytes.size, 128 * 1024), Charsets.ISO_8859_1)
        val len = (offsetRegex.find(head) ?: lengthRegex.find(head))
            ?.groupValues?.get(1)?.toIntOrNull() ?: return false
        if (len <= 0 || len >= bytes.size) return false
        val video = bytes.copyOfRange(bytes.size - len, bytes.size)
        val filter = PhotoFilter.fromId(filterRegex.find(head)?.groupValues?.get(1))

        val tmp = File(context.cacheDir, "key.mp4")
        tmp.writeBytes(video)
        val mmr = android.media.MediaMetadataRetriever()
        val frame = try {
            mmr.setDataSource(tmp.path)
            mmr.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            mmr.release()
            tmp.delete()
        } ?: return false

        val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(frame, 0f, 0f, filter.paint())
        frame.recycle()
        val jpeg = encode(out, 95)
        out.recycle()
        val xmp = buildXmp(video.size, timeUs, filter)
        val newBytes = insertXmp(jpeg, xmp)
        context.contentResolver.openOutputStream(uri, "wt")!!.use {
            it.write(newBytes)
            it.write(video)
        }
        return true
    }
}
