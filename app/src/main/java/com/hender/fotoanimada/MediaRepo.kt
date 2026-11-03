package com.hender.fotoanimada

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

/** Fotos tomadas por la app (DCIM/FotoAnimada), de la más nueva a la más vieja. */
object MediaRepo {

    data class Item(val id: Long, val uri: Uri, val isLive: Boolean)

    fun list(context: Context): List<Item> {
        val result = mutableListOf<Item>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("DCIM/FotoAnimada%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: ""
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                result += Item(id, uri, name.startsWith("MVIMG_"))
            }
        }
        return result
    }

    fun thumbnail(context: Context, uri: Uri, size: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(size, size), null)
    } catch (_: Exception) {
        null
    }
}
