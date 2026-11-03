package com.hender.fotoanimada

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** Procesos de imagen: retrato, noche, instantánea con texto y ediciones. */
object Processing {

    fun decode(jpeg: ByteArray, maxSide: Int): Bitmap {
        val src = ImageDecoder.createSource(ByteBuffer.wrap(jpeg))
        return ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = true
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val k = maxSide.toFloat() / longest
                d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
            }
        }
    }

    fun encode(bmp: Bitmap, quality: Int = 95): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Desenfoque rápido: reducir y volver a agrandar con filtro, dos pasadas. */
    fun blur(src: Bitmap, factor: Int = 10): Bitmap {
        val w = max(1, src.width / factor)
        val h = max(1, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val smaller = Bitmap.createScaledBitmap(small, max(1, w / 2), max(1, h / 2), true)
        val back = Bitmap.createScaledBitmap(smaller, w, h, true)
        val out = Bitmap.createScaledBitmap(back, src.width, src.height, true)
        small.recycle(); smaller.recycle(); back.recycle()
        return out
    }

    /**
     * Modo retrato: separa a la persona/objeto con ML Kit y desenfoca el fondo.
     * Se llama desde un hilo de fondo (espera el resultado de ML Kit).
     */
    fun portrait(jpeg: ByteArray, filter: PhotoFilter): ByteArray {
        val sharp = decode(jpeg, 3000)
        val options = SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        val result = Tasks.await(SubjectSegmentation.getClient(options).process(InputImage.fromBitmap(sharp, 0)))
        val mask = result.foregroundConfidenceMask ?: throw IllegalStateException("No encontré a nadie para el retrato")
        val blurred = blur(sharp, 14)

        val w = sharp.width
        val h = sharp.height
        val a = IntArray(w)
        val b = IntArray(w)
        mask.rewind()
        for (y in 0 until h) {
            sharp.getPixels(a, 0, w, 0, y, w, 1)
            blurred.getPixels(b, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                // Borde suave: la confianza decide cuánto se ve nítido
                val m = ((mask.get() - 0.35f) / 0.4f).coerceIn(0f, 1f)
                if (m >= 0.999f) continue
                val p = a[x]
                val q = b[x]
                val r = (((p shr 16) and 0xFF) * m + ((q shr 16) and 0xFF) * (1 - m)).toInt()
                val g = (((p shr 8) and 0xFF) * m + ((q shr 8) and 0xFF) * (1 - m)).toInt()
                val bl = ((p and 0xFF) * m + (q and 0xFF) * (1 - m)).toInt()
                a[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
            sharp.setPixels(a, 0, w, 0, y, w, 1)
        }
        blurred.recycle()
        return finish(sharp, filter)
    }

    /** Noche: levanta sombras y limpia un poco el ruido de color. */
    fun night(jpeg: ByteArray, filter: PhotoFilter): ByteArray {
        val bmp = decode(jpeg, 4096)
        // Brillo promedio con una muestra pequeña
        val probe = Bitmap.createScaledBitmap(bmp, 64, 48, true)
        val px = IntArray(64 * 48)
        probe.getPixels(px, 0, 64, 0, 0, 64, 48)
        probe.recycle()
        val mean = px.map { ((it shr 16 and 0xFF) * 299 + (it shr 8 and 0xFF) * 587 + (it and 0xFF) * 114) / 1000 }.average()
        val gain = (110.0 / max(mean, 20.0)).coerceIn(1.0, 2.2).toFloat()

        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setScale(gain, gain, gain, 1f) }
        cm.postConcat(ColorMatrix().apply { setSaturation(0.92f) })
        // Una pasada de suavizado muy leve para el ruido
        val soft = blur(bmp, 2)
        val c = Canvas(out)
        c.drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        c.drawBitmap(soft, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm); alpha = 90 })
        soft.recycle()
        bmp.recycle()
        return finish(out, filter)
    }

    private fun finish(bmp: Bitmap, filter: PhotoFilter): ByteArray {
        val out = if (filter == PhotoFilter.ORIGINAL) bmp else {
            val o = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(o).drawBitmap(bmp, 0f, 0f, filter.paint())
            bmp.recycle()
            o
        }
        val bytes = encode(out, 95)
        out.recycle()
        return bytes
    }

    /** Instantánea tipo IG: foto con el texto encima, en una franja suave. */
    fun captioned(jpeg: ByteArray, caption: String, look: InstantLook): Bitmap {
        val src = decode(jpeg, 2400)
        val out = look.apply(src)
        val c = Canvas(out)
        src.recycle()
        if (caption.isNotBlank()) drawCaption(c, out.width, out.height, caption)
        return out
    }

    fun drawCaption(c: Canvas, w: Int, h: Int, caption: String) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = w * 0.055f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(w * 0.01f, 0f, 0f, 0x99000000.toInt())
        }
        val maxW = (w * 0.84f).toInt()
        val layout = StaticLayout.Builder.obtain(caption, 0, caption.length, tp, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(4)
            .build()
        val padV = w * 0.03f
        val boxH = layout.height + padV * 2
        val top = h * 0.70f - boxH / 2
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000 }
        c.drawRoundRect(RectF(w * 0.05f, top, w * 0.95f, top + boxH), w * 0.03f, w * 0.03f, bg)
        c.save()
        c.translate((w - maxW) / 2f, top + padV)
        layout.draw(c)
        c.restore()
    }

    // ---------- Editor ----------

    data class Edit(
        var exposure: Float = 0f,   // -1..1
        var contrast: Float = 0f,   // -1..1
        var saturation: Float = 0f, // -1..1
        var warmth: Float = 0f,     // -1..1
        var vignette: Float = 0f,   // 0..1
        var filter: PhotoFilter = PhotoFilter.ORIGINAL,
        var rotation: Int = 0,      // 0, 90, 180, 270
        var flip: Boolean = false,
        var aspect: Float = 0f,     // 0 = original; si no, ancho/alto
        var polaroid: Boolean = false,
    )

    fun colorMatrix(e: Edit): ColorMatrix {
        val m = e.filter.matrix()
        val ex = 1f + e.exposure * 0.6f
        m.postConcat(ColorMatrix().apply { setScale(ex, ex, ex, 1f) })
        val c = 1f + e.contrast * 0.6f
        val t = (1f - c) * 128f
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        m.postConcat(ColorMatrix().apply { setSaturation(1f + e.saturation) })
        val wr = 1f + e.warmth * 0.15f
        val wb = 1f - e.warmth * 0.15f
        m.postConcat(ColorMatrix().apply { setScale(wr, 1f, wb, 1f) })
        return m
    }

    /** Aplica giro, espejo y recorte (centrado) a un bitmap. */
    fun geometry(src: Bitmap, e: Edit): Bitmap {
        val m = Matrix()
        if (e.flip) m.postScale(-1f, 1f)
        if (e.rotation != 0) m.postRotate(e.rotation.toFloat())
        val rotated = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (e.aspect <= 0f) return rotated
        val w = rotated.width
        val h = rotated.height
        val cur = w.toFloat() / h
        val (cw, ch) = if (cur > e.aspect) (h * e.aspect).toInt() to h else w to (w / e.aspect).toInt()
        val out = Bitmap.createBitmap(rotated, (w - cw) / 2, (h - ch) / 2, cw, ch)
        if (rotated !== src && rotated !== out) rotated.recycle()
        return out
    }

    /** Resultado final de la edición. */
    fun render(src: Bitmap, e: Edit): Bitmap {
        val geo = geometry(src, e)
        val out = Bitmap.createBitmap(geo.width, geo.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(geo, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix(e))
        })
        if (geo !== src) geo.recycle()
        if (e.vignette > 0f) {
            val r = max(out.width, out.height) * 0.75f
            val p = Paint().apply {
                shader = RadialGradient(
                    out.width / 2f, out.height / 2f, r,
                    intArrayOf(0, 0, Color.argb((e.vignette * 200).toInt(), 0, 0, 0)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
            }
            c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), p)
        }
        if (!e.polaroid) return out
        // Marco tipo Polaroid
        val side = min(out.width, out.height)
        val photo = 1600
        val border = 90
        val bottom = 380
        val pol = Bitmap.createBitmap(photo + border * 2, photo + border + bottom, Bitmap.Config.ARGB_8888)
        val pc = Canvas(pol)
        pc.drawColor(Color.rgb(250, 249, 245))
        val srcRect = Rect((out.width - side) / 2, (out.height - side) / 2, (out.width + side) / 2, (out.height + side) / 2)
        pc.drawBitmap(out, srcRect, Rect(border, border, border + photo, border + photo), Paint(Paint.FILTER_BITMAP_FLAG))
        out.recycle()
        return pol
    }
}
