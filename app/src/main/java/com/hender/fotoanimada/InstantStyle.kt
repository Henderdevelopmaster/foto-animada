package com.hender.fotoanimada

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Forma de la foto en la instantánea. */
enum class InstantShape(val id: String, val label: String) {
    SQUARE("square", "Cuadrada"),
    ROUND("round", "Redonda"),
    HEART("heart", "Corazón"),
    STAR("star", "Estrella"),
    CLOUD("cloud", "Nube"),
    STICKER("sticker", "Sticker");

    /** Contorno dentro de un cuadrado de lado [size] que empieza en (x, y). */
    fun path(size: Float, x: Float = 0f, y: Float = 0f): Path {
        val p = Path()
        when (this) {
            SQUARE -> p.addRoundRect(RectF(0f, 0f, 1f, 1f), 0.05f, 0.05f, Path.Direction.CW)
            STICKER -> p.addRoundRect(RectF(0.06f, 0.06f, 0.94f, 0.94f), 0.17f, 0.17f, Path.Direction.CW)
            ROUND -> p.addCircle(0.5f, 0.5f, 0.5f, Path.Direction.CW)
            HEART -> {
                p.moveTo(0.5f, 0.93f)
                p.cubicTo(0.16f, 0.72f, -0.04f, 0.47f, 0.09f, 0.25f)
                p.cubicTo(0.19f, 0.06f, 0.43f, 0.05f, 0.5f, 0.24f)
                p.cubicTo(0.57f, 0.05f, 0.81f, 0.06f, 0.91f, 0.25f)
                p.cubicTo(1.04f, 0.47f, 0.84f, 0.72f, 0.5f, 0.93f)
                p.close()
            }
            STAR -> {
                for (i in 0 until 10) {
                    val a = -PI / 2 + i * PI / 5
                    val r = if (i % 2 == 0) 0.5 else 0.23
                    val px = (0.5 + r * cos(a)).toFloat()
                    val py = (0.53 + r * sin(a)).toFloat()
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close()
            }
            CLOUD -> {
                for ((cx, cy, r) in listOf(
                    Triple(0.27f, 0.56f, 0.19f), Triple(0.44f, 0.39f, 0.24f), Triple(0.66f, 0.42f, 0.21f),
                    Triple(0.8f, 0.58f, 0.16f), Triple(0.52f, 0.6f, 0.2f),
                )) p.addCircle(cx, cy, r, Path.Direction.CW)
                p.addRoundRect(RectF(0.1f, 0.51f, 0.93f, 0.76f), 0.12f, 0.12f, Path.Direction.CW)
            }
        }
        val m = Matrix()
        m.setScale(size, size)
        m.postTranslate(x, y)
        p.transform(m)
        return p
    }

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SQUARE
    }
}

/** Marco alrededor de la instantánea. */
enum class InstantFrame(val id: String, val label: String) {
    NONE("none", "Sin marco"),
    POLAROID("polaroid", "Polaroid"),
    FILM("film", "Negativo"),
    BOOTH("booth", "Fotomatón"),
    TAPE("tape", "Cinta");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Arma la instantánea final: filtro + forma + marco + texto. */
object InstantStyle {

    private val hand: Typeface by lazy {
        Typeface.create("casual", Typeface.NORMAL).takeIf { it != Typeface.DEFAULT } ?: Typeface.create("cursive", Typeface.NORMAL)
    }
    private val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun today(): String = SimpleDateFormat("dd MMM yyyy", Locale("es")).format(Date())

    /** Recorte cuadrado centrado y escalado a [side]. */
    fun square(src: Bitmap, side: Int): Bitmap {
        val s = min(src.width, src.height)
        val r = Rect((src.width - s) / 2, (src.height - s) / 2, (src.width + s) / 2, (src.height + s) / 2)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, r, Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Foto recortada con la forma (transparente afuera, borde suave). */
    fun shaped(photo: Bitmap, shape: InstantShape): Bitmap {
        val s = photo.width
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawPath(shape.path(s.toFloat()), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(photo, 0f, 0f, p)
        return out
    }

    /**
     * Instantánea terminada. [photos] trae 4 fotos para el fotomatón y 1 para lo demás.
     * Siempre devuelve una imagen opaca (sirve igual para foto y para boomerang).
     */
    fun render(
        photos: List<Bitmap>, look: InstantLook, shape: InstantShape, frame: InstantFrame,
        caption: String, side: Int = 1200, date: String = today(),
    ): Bitmap {
        val looks = photos.map { look.apply(square(it, side)) }
        val out = when (frame) {
            InstantFrame.NONE -> plain(looks[0], shape, caption)
            InstantFrame.POLAROID -> polaroid(looks[0], shape, caption, date)
            InstantFrame.FILM -> film(looks[0], shape, caption)
            InstantFrame.BOOTH -> booth(List(4) { looks[it % looks.size] }, shape, caption, date)
            InstantFrame.TAPE -> tape(looks[0], shape, caption)
        }
        looks.forEach { it.recycle() }
        return out
    }

    // ---------------------------------------------------------------- marcos

    /** Sin marco: la forma sobre la misma foto desenfocada, texto curvo arriba. */
    private fun plain(photo: Bitmap, shape: InstantShape, caption: String): Bitmap {
        val s = photo.width
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (shape == InstantShape.SQUARE) {
            c.drawBitmap(photo, 0f, 0f, null)
            if (caption.isNotBlank()) arcText(c, caption, s / 2f, s * 0.98f, s * 0.86f, s * 0.062f, Color.WHITE, true)
            return out
        }
        val bg = Processing.blur(photo, 18)
        c.drawBitmap(bg, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.55f, 0.55f, 0.55f, 1f) })
        })
        bg.recycle()
        val inner = (s * if (caption.isBlank()) 0.86f else 0.78f).toInt()
        val off = (s - inner) / 2f
        val top = if (caption.isBlank()) off else off + s * 0.05f
        drawShape(c, photo, shape, inner, off, top)
        if (caption.isNotBlank()) arcText(c, caption, s / 2f, top + inner / 2f, inner * 0.56f, s * 0.058f, Color.WHITE, true)
        return out
    }

    private fun polaroid(photo: Bitmap, shape: InstantShape, caption: String, date: String): Bitmap {
        val s = photo.width
        val pad = (s * 0.065f).toInt()
        val bottom = (s * 0.32f).toInt()
        val w = s + pad * 2
        val h = s + pad + bottom
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFF8F6F1.toInt())
        if (shape == InstantShape.SQUARE) {
            c.drawRect(pad.toFloat(), pad.toFloat(), (pad + s).toFloat(), (pad + s).toFloat(), Paint().apply { color = 0xFF1E1E1E.toInt() })
            c.drawBitmap(photo, pad.toFloat(), pad.toFloat(), null)
        } else {
            drawShape(c, photo, shape, s, pad.toFloat(), pad.toFloat())
        }
        val text = caption.ifBlank { "" }
        if (text.isNotBlank()) {
            block(c, text, w / 2f, s + pad + bottom * 0.46f, w * 0.86f, s * 0.085f, 0xFF27273A.toInt(), hand, 2)
        }
        small(c, date, w - pad.toFloat(), h - bottom * 0.14f, s * 0.032f, 0xFF9A958C.toInt(), Paint.Align.RIGHT)
        return out
    }

    private fun film(photo: Bitmap, shape: InstantShape, caption: String): Bitmap {
        val s = photo.width
        val band = (s * 0.17f).toInt()
        val side = (s * 0.05f).toInt()
        val w = s + side * 2
        val h = s + band * 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF20140E.toInt())
        // Perforaciones arriba y abajo
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDE6DA.toInt() }
        val hw = s * 0.055f
        val hh = s * 0.075f
        var x = side * 0.6f
        while (x + hw < w) {
            c.drawRoundRect(RectF(x, band * 0.18f, x + hw, band * 0.18f + hh), hw * 0.25f, hw * 0.25f, hole)
            c.drawRoundRect(RectF(x, h - band * 0.18f - hh, x + hw, h - band * 0.18f), hw * 0.25f, hw * 0.25f, hole)
            x += hw * 2f
        }
        if (shape == InstantShape.SQUARE) c.drawBitmap(photo, side.toFloat(), band.toFloat(), null)
        else drawShape(c, photo, shape, s, side.toFloat(), band.toFloat())
        val orange = 0xFFF2A24A.toInt()
        val edge = if (caption.isBlank()) "CAMARATRIX 400" else caption.uppercase().take(28)
        small(c, edge, side.toFloat(), h - band * 0.68f, s * 0.04f, orange, Paint.Align.LEFT, mono)
        small(c, "▸ 24A", w - side.toFloat(), h - band * 0.68f, s * 0.04f, orange, Paint.Align.RIGHT, mono)
        small(c, "24", w - side.toFloat(), band * 0.9f, s * 0.04f, orange, Paint.Align.RIGHT, mono)
        return out
    }

    private fun booth(photos: List<Bitmap>, shape: InstantShape, caption: String, date: String): Bitmap {
        val s = photos[0].width
        val cell = (s * 0.5f).toInt()
        val gap = (s * 0.035f).toInt()
        val bottom = (s * 0.2f).toInt()
        val w = cell + gap * 2
        val h = cell * 4 + gap * 5 + bottom
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFF6F4EF.toInt())
        for (i in 0 until 4) {
            val small = Bitmap.createScaledBitmap(photos[i], cell, cell, true)
            val top = gap + i * (cell + gap)
            if (shape == InstantShape.SQUARE) c.drawBitmap(small, gap.toFloat(), top.toFloat(), null)
            else drawShape(c, small, shape, cell, gap.toFloat(), top.toFloat())
            small.recycle()
        }
        val y = h - bottom
        if (caption.isNotBlank()) block(c, caption, w / 2f, y + bottom * 0.4f, w * 0.9f, s * 0.05f, 0xFF27273A.toInt(), hand, 2)
        small(c, date, w / 2f, h - bottom * 0.12f, s * 0.026f, 0xFF9A958C.toInt(), Paint.Align.CENTER)
        return out
    }

    private fun tape(photo: Bitmap, shape: InstantShape, caption: String): Bitmap {
        val s = photo.width
        val w = (s * 1.24f).toInt()
        val h = (s * 1.42f).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFEDE5D8.toInt())
        // Papel con textura muy suave
        val rnd = java.util.Random(7)
        val dot = Paint().apply { color = 0x0F000000 }
        repeat(2400) { c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, s * 0.0025f, dot) }
        val border = s * 0.045f
        val cx = w / 2f
        val cy = s * 0.62f
        c.save()
        c.rotate(-3f, cx, cy)
        val card = RectF(cx - s / 2f - border, cy - s / 2f - border, cx + s / 2f + border, cy + s / 2f + border)
        c.drawRect(card, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(s * 0.03f, 0f, s * 0.012f, 0x55000000)
        })
        if (shape == InstantShape.SQUARE) c.drawBitmap(photo, cx - s / 2f, cy - s / 2f, null)
        else drawShape(c, photo, shape, s, cx - s / 2f, cy - s / 2f)
        // Dos cintas en las esquinas de arriba
        val tapeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC8F1E3A6.toInt() }
        for ((tx, ang) in listOf(card.left to -38f, card.right to 38f)) {
            c.save()
            c.rotate(ang, tx, card.top)
            c.drawRect(tx - s * 0.13f, card.top - s * 0.035f, tx + s * 0.13f, card.top + s * 0.035f, tapeP)
            c.restore()
        }
        c.restore()
        if (caption.isNotBlank()) block(c, caption, w / 2f, cy + s / 2f + border + s * 0.14f, w * 0.86f, s * 0.075f, 0xFF27273A.toInt(), hand, 2)
        return out
    }

    // ---------------------------------------------------------------- dibujo

    private fun drawShape(c: Canvas, photo: Bitmap, shape: InstantShape, size: Int, x: Float, y: Float) {
        val scaled = if (photo.width == size) photo else Bitmap.createScaledBitmap(photo, size, size, true)
        val cut = shaped(scaled, shape)
        if (shape != InstantShape.SQUARE) {
            // Borde blanco tipo sticker con sombrita
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * if (shape == InstantShape.STICKER) 0.07f else 0.03f
                color = Color.WHITE
                strokeJoin = Paint.Join.ROUND
                setShadowLayer(size * 0.02f, 0f, size * 0.008f, 0x66000000)
            }
            c.drawPath(shape.path(size.toFloat(), x, y), p)
        }
        c.drawBitmap(cut, x, y, null)
        cut.recycle()
        if (scaled !== photo) scaled.recycle()
    }

    /** Texto curvo sobre un arco (como el de las instantáneas de IG). */
    fun arcText(c: Canvas, text: String, cx: Float, cy: Float, radius: Float, size: Float, color: Int, shadow: Boolean) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = bold
            letterSpacing = 0.02f
            if (shadow) setShadowLayer(size * 0.18f, 0f, 0f, 0xAA000000.toInt())
        }
        var t = text.trim().replace('\n', ' ')
        val maxLen = (PI * radius * 0.92).toFloat()
        while (t.length > 1 && p.measureText(t) > maxLen) t = t.dropLast(1)
        if (t.length < text.trim().length) t = t.dropLast(1) + "…"
        val path = Path().apply { addArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), 180f, 180f) }
        val len = PathMeasure(path, false).length
        val tw = p.measureText(t)
        c.drawTextOnPath(t, path, (len - tw) / 2f, -size * 0.25f, p)
    }

    private fun block(c: Canvas, text: String, cx: Float, cy: Float, maxW: Float, size: Float, color: Int, tf: Typeface, lines: Int) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = tf
        }
        var sz = size
        var layout: StaticLayout
        while (true) {
            tp.textSize = sz
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(lines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            if (layout.lineCount < lines || sz < size * 0.6f || layout.getEllipsisCount(layout.lineCount - 1) == 0) break
            sz *= 0.9f
        }
        c.save()
        c.translate(cx - maxW / 2f, cy - layout.height / 2f)
        layout.draw(c)
        c.restore()
    }

    private fun small(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align, tf: Typeface = bold) {
        c.drawText(text, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = tf
            textAlign = align
            letterSpacing = 0.06f
        })
    }

    /** Para las miniaturas del selector de marcos y formas. */
    fun thumb(base: Bitmap, shape: InstantShape, frame: InstantFrame): Bitmap {
        val full = render(listOf(base), InstantLook.NONE, shape, frame, "", side = 240)
        val out = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF2A2A2E.toInt())
        val k = min(170f / full.width, 170f / full.height)
        val w = full.width * k
        val h = full.height * k
        c.drawBitmap(full, null, RectF((180 - w) / 2, (180 - h) / 2, (180 + w) / 2, (180 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        full.recycle()
        return out
    }
}
