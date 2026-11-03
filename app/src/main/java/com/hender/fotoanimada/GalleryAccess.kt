package com.hender.fotoanimada

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Permiso para leer todas las fotos del celular. */
object GalleryAccess {

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** true si hay acceso total o parcial. */
    fun has(context: Context): Boolean = permissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasFull(context: Context): Boolean = permissions().first().let {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun askedBefore(context: Context) =
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).getBoolean("asked", false)

    fun markAsked(context: Context) =
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
}
