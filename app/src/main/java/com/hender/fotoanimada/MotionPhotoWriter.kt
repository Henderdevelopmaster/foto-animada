package com.hender.fotoanimada

import android.content.ContentValues
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

    fun buildXmp(videoLength: Int, presentationUs: Long): String = """
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="FotoAnimada">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
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
    fun save(context: Context, jpeg: ByteArray, mp4: ByteArray?): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = if (mp4 != null) "MVIMG_$stamp.jpg" else "IMG_$stamp.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/FotoAnimada")
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
}
