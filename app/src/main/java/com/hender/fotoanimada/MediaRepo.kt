package com.hender.fotoanimada

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

/** Fotos tomadas por la app (DCIM/FotoAnimada), de la más nueva a la más vieja. */
object MediaRepo {

    data class Item(val id: Long, val uri: Uri, val isLive: Boolean, val dateMs: Long)

    /**
     * onlyOwn = true: solo las fotos de la app.
     * onlyOwn = false: todas las fotos a las que tengamos acceso (galería completa).
     */
    fun list(context: Context, onlyOwn: Boolean = false): List<Item> {
        val result = mutableListOf<Item>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            if (onlyOwn) "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" else null,
            if (onlyOwn) arrayOf("DCIM/Camaratrix%", "DCIM/FotoAnimada%") else null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: ""
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val live = name.startsWith("MVIMG_") || name.contains(".MP.")
                result += Item(id, uri, live, c.getLong(dateCol) * 1000)
            }
        }
        return result
    }

    /** Foto real (no miniatura), con la orientación correcta, lado mayor <= maxSide. */
    fun loadFull(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt(),
                    (info.size.height * scale).toInt()
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    fun thumbnail(context: Context, uri: Uri, size: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(size, size), null)
    } catch (_: Exception) {
        null
    }
}
