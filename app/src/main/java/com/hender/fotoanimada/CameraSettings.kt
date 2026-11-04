package com.hender.fotoanimada

import android.content.Context
import android.util.Size
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.Quality

/** Resoluciones elegibles por el usuario. */
object CameraSettings {

    class Option(val id: String, val label: String)

    val photoOptions = listOf(
        Option("max", "Máxima (hasta 200 MP si el cel lo permite)"),
        Option("12", "Alta · 12 MP"),
        Option("8", "Media · 8 MP"),
        Option("3", "Baja · 3 MP (ahorra espacio)"),
    )
    val videoOptions = listOf(
        Option("uhd", "4K"),
        Option("fhd", "1080p"),
        Option("hd", "720p"),
    )
    val liveOptions = listOf(
        Option("1440", "Alta · 1920×1440"),
        Option("960", "Normal · 1280×960"),
        Option("720", "Ligera · 960×720"),
    )
    val effectsOptions = listOf(
        Option("960", "Alta · 1280×960"),
        Option("720", "Normal · 960×720 (más fluido)"),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(c: Context, key: String, def: String): String = prefs(c).getString(key, def) ?: def
    fun set(c: Context, key: String, value: String) = prefs(c).edit().putString(key, value).apply()

    const val PHOTO = "photo_res"
    const val VIDEO = "video_res"
    const val LIVE = "live_res"
    const val EFFECTS = "effects_res"
    const val ENHANCE = "enhance"
    const val FORMAT = "format"

    /** Formato de la foto: 4:3, 16:9, 1:1 o pantalla completa. */
    val formats = listOf("4:3", "16:9", "1:1", "full")
    fun format(c: Context) = get(c, FORMAT, "4:3").let { if (it in formats) it else "4:3" }
    fun formatLabel(f: String) = if (f == "full") "Completa" else f

    fun photo(c: Context) = get(c, PHOTO, "12")
    fun video(c: Context) = get(c, VIDEO, "fhd")
    fun live(c: Context) = get(c, LIVE, "960")
    fun effects(c: Context) = get(c, EFFECTS, "720")

    /** Selector de resolución para la foto (4:3, o 16:9 para formatos alargados). */
    fun photoSelector(c: Context, wide: Boolean = false): ResolutionSelector {
        val b = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
        when (photo(c)) {
            "max" -> {
                b.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                b.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            }
            "8" -> b.setResolutionStrategy(ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            "3" -> b.setResolutionStrategy(ResolutionStrategy(Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            else -> b.setResolutionStrategy(ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        }
        return b.build()
    }

    fun liveSize(c: Context): Size = when (live(c)) {
        "1440" -> Size(1920, 1440)
        "720" -> Size(960, 720)
        else -> Size(1280, 960)
    }

    fun effectsSize(c: Context): Size = if (effects(c) == "960") Size(1280, 960) else Size(960, 720)

    fun videoQuality(c: Context): Quality = when (video(c)) {
        "uhd" -> Quality.UHD
        "hd" -> Quality.HD
        else -> Quality.FHD
    }

    fun label(c: Context): String {
        val p = when (photo(c)) { "max" -> "MÁX"; "8" -> "8MP"; "3" -> "3MP"; else -> "12MP" }
        return p
    }
}
