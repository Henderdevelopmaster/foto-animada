package com.hender.fotoanimada

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

/** Fotos y videos del celular, del más nuevo al más viejo. */
object MediaRepo {

    data class Item(
        val id: Long,
        val uri: Uri,
        val isLive: Boolean,
        val dateMs: Long,
        val isVideo: Boolean = false,
        val album: String = "",
        val name: String = "",
    ) {
        val isOwn: Boolean get() = album == "Camaratrix" || album == "FotoAnimada"
    }

    /** Álbumes fijos. Los demás son las carpetas del celular. */
    const val ALBUM_ALL = "@all"
    const val ALBUM_FAVS = "@favs"
    const val ALBUM_LIVE = "@live"
    const val ALBUM_VIDEOS = "@videos"

    /**
     * onlyOwn = true: solo lo de la app.
     * onlyOwn = false: todo a lo que tengamos acceso (galería completa).
     */
    fun list(context: Context, onlyOwn: Boolean = false): List<Item> {
        val result = mutableListOf<Item>()
        val files = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val typeSel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        val selection = if (onlyOwn) "$typeSel AND ($path LIKE ? OR $path LIKE ?)" else typeSel
        val args = if (onlyOwn) arrayOf("DCIM/Camaratrix%", "DCIM/FotoAnimada%") else null

        context.contentResolver.query(
            files, projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: ""
                val video = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = ContentUris.withAppendedId(base, id)
                val live = !video && (name.startsWith("MVIMG_") || name.contains(".MP."))
                result += Item(
                    id, uri, live, c.getLong(dateCol) * 1000, video,
                    c.getString(bucketCol) ?: "", name
                )
            }
        }
        return result
    }

    fun filter(items: List<Item>, album: String, favs: Set<String>): List<Item> = when (album) {
        ALBUM_ALL -> items
        ALBUM_FAVS -> items.filter { it.id.toString() in favs }
        ALBUM_LIVE -> items.filter { it.isLive }
        ALBUM_VIDEOS -> items.filter { it.isVideo }
        else -> items.filter { it.album == album }
    }

    fun favorites(context: Context): MutableSet<String> =
        context.getSharedPreferences("favs", Context.MODE_PRIVATE)
            .getStringSet("ids", emptySet())!!.toMutableSet()

    fun setFavorite(context: Context, id: Long, fav: Boolean) {
        val set = favorites(context)
        if (fav) set += id.toString() else set -= id.toString()
        context.getSharedPreferences("favs", Context.MODE_PRIVATE).edit().putStringSet("ids", set).apply()
    }

    /** Foto real (no miniatura), con la orientación correcta, lado mayor <= maxSide. */
    fun loadFull(context: Context, uri: Uri, maxSide: Int, software: Boolean = false): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
