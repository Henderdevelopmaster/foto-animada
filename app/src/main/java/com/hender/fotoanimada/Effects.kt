package com.hender.fotoanimada

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Un efecto es un paquete de datos (JSON + imágenes). Así los de la biblioteca
 * se descargan y funcionan igual que los que vienen con la app.
 */
data class Sticker(
    val image: String,
    val anchor: String,
    val width: Float,
    val offsetX: Float,
    val offsetY: Float,
    val trigger: String,
    /** Animación: none, bob, spin, pulse, sway, blink */
    val anim: String = "none",
    val animAmount: Float = 0.1f,
    val animSpeed: Float = 1f,
)

/**
 * Partículas. mode:
 *  face  = salen alrededor de la cara y suben
 *  rise  = suben desde abajo de la pantalla
 *  fall  = caen desde arriba (nieve, confeti, pétalos)
 *  orbit = giran alrededor de la cabeza
 */
data class Particles(
    val images: List<String>,
    val size: Float,
    val rate: Float,
    val mode: String = "face",
    val spin: Float = 0f,
    val count: Int = 6,
)

/** Capa de pantalla completa: imagen (marco), degradado, fecha de cámara o REC. */
data class Overlay(
    val type: String,          // image, tint, date, rec
    val image: String? = null,
    val fit: String = "cover", // cover, top, bottom, center
    val alpha: Float = 1f,
    val colors: List<Int> = emptyList(),
    val color: Int = 0xFFFF8A00.toInt(),
)

data class FaceEffect(
    val id: String,
    val name: String,
    val emoji: String,
    val icon: String?,
    val stickers: List<Sticker>,
    val warp: String?,
    val warpStrength: Float,
    val filter: PhotoFilter,
    val glitch: Boolean,
    val scanlines: Boolean,
    val mirror: Boolean,
    val particles: Particles?,
    /** Carpeta de donde salen las imágenes: "asset:effects" o una ruta de archivos. */
    val base: String,
    val colorMatrix: FloatArray? = null,
    val overlays: List<Overlay> = emptyList(),
    val category: String = "",
    val custom: Boolean = false,
) {
    val isNone get() = stickers.isEmpty() && warp == null && filter == PhotoFilter.ORIGINAL &&
        !glitch && !scanlines && !mirror && particles == null && colorMatrix == null && overlays.isEmpty()

    /** Color total del efecto (filtro + matriz propia). null = sin cambio. */
    fun matrix(): ColorMatrix? {
        if (filter == PhotoFilter.ORIGINAL && colorMatrix == null) return null
        val m = filter.matrix()
        colorMatrix?.let { m.postConcat(ColorMatrix(it)) }
        return m
    }

    companion object {
        private fun color(s: String): Int = try {
            android.graphics.Color.parseColor(s)
        } catch (_: Exception) {
            0
        }

        fun parse(o: JSONObject, base: String): FaceEffect {
            val st = mutableListOf<Sticker>()
            o.optJSONArray("stickers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    st += Sticker(
                        s.getString("image"), s.optString("anchor", "eyes"),
                        s.optDouble("width", 1.0).toFloat(),
                        s.optDouble("offsetX", 0.0).toFloat(), s.optDouble("offsetY", 0.0).toFloat(),
                        s.optString("trigger", "always"),
                        s.optString("anim", "none"),
                        s.optDouble("animAmount", 0.1).toFloat(),
                        s.optDouble("animSpeed", 1.0).toFloat(),
                    )
                }
            }
            val warp = o.optJSONObject("warp")
            val p = o.optJSONObject("particles")
            val cm = o.optJSONArray("colorMatrix")?.let { a ->
                if (a.length() == 20) FloatArray(20) { a.getDouble(it).toFloat() } else null
            }
            val overlays = mutableListOf<Overlay>()
            o.optJSONArray("overlays")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    val cols = v.optJSONArray("colors")?.let { c -> (0 until c.length()).map { color(c.getString(it)) } } ?: emptyList()
                    overlays += Overlay(
                        v.optString("type", "image"),
                        v.optString("image").ifBlank { null },
                        v.optString("fit", "cover"),
                        v.optDouble("alpha", 1.0).toFloat(),
                        cols,
                        v.optString("color").ifBlank { null }?.let { color(it) } ?: 0xFFFF8A00.toInt(),
                    )
                }
            }
            return FaceEffect(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                emoji = o.optString("emoji", "✨"),
                icon = o.optString("icon").ifBlank { null },
                stickers = st,
                warp = warp?.optString("type"),
                warpStrength = warp?.optDouble("strength", 0.4)?.toFloat() ?: 0f,
                filter = PhotoFilter.fromId(o.optString("filter")),
                glitch = o.optBoolean("glitch"),
                scanlines = o.optBoolean("scanlines"),
                mirror = o.optBoolean("mirror"),
                particles = p?.let {
                    val imgs = it.optJSONArray("images")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } }
                        ?: listOf(it.getString("image"))
                    Particles(
                        imgs, it.optDouble("size", 0.15).toFloat(), it.optDouble("rate", 3.0).toFloat(),
                        it.optString("mode", "face"), it.optDouble("spin", 0.0).toFloat(), it.optInt("count", 6),
                    )
                },
                base = base,
                colorMatrix = cm,
                overlays = overlays,
                category = o.optString("category", ""),
                custom = o.optBoolean("custom"),
            )
        }
    }
}

object EffectStore {

    /** Efectos que trae la app + los descargados de la biblioteca + los creados por ti. */
    fun all(context: Context): List<FaceEffect> {
        val list = mutableListOf<FaceEffect>()
        try {
            val arr = JSONArray(context.assets.open("effects/effects.json").bufferedReader().readText())
            for (i in 0 until arr.length()) list += FaceEffect.parse(arr.getJSONObject(i), "asset:effects")
        } catch (_: Exception) {
        }
        val extra = downloaded(context)
        list += extra.filter { it.custom }.sortedByDescending { it.id }
        list += extra.filter { !it.custom }
        return list
    }

    fun downloadedDir(context: Context) = File(context.filesDir, "effects").apply { mkdirs() }

    fun downloaded(context: Context): List<FaceEffect> =
        downloadedDir(context).listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            try {
                FaceEffect.parse(JSONObject(File(dir, "effect.json").readText()), dir.path)
            } catch (_: Exception) {
                null
            }
        }?.sortedWith(compareBy({ it.category }, { it.name })) ?: emptyList()

    private val cache = HashMap<String, Bitmap?>()

    fun image(context: Context, base: String, name: String): Bitmap? = synchronized(cache) {
        val key = "$base/$name"
        cache.getOrPut(key) {
            try {
                if (base.startsWith("asset:")) {
                    context.assets.open(base.removePrefix("asset:") + "/" + name).use { BitmapFactory.decodeStream(it) }
                } else {
                    BitmapFactory.decodeFile(File(base, name).path)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun forget(base: String) = synchronized(cache) {
        cache.keys.filter { it.startsWith("$base/") }.forEach { cache.remove(it) }
    }
}

/** Puntos útiles de una cara, ya en coordenadas de la imagen derecha (y espejada si es selfie). */
class FaceGeom(
    val leftEye: PointF,
    val rightEye: PointF,
    val nose: PointF,
    val mouth: PointF,
    val leftCheek: PointF,
    val rightCheek: PointF,
    val faceWidth: Float,
    val angle: Float,       // radianes, inclinación de la línea de los ojos
    val mouthOpen: Float,   // 0 = cerrada
    val smile: Float,
    val id: Int,
) {
    val eyes get() = PointF((leftEye.x + rightEye.x) / 2, (leftEye.y + rightEye.y) / 2)

    /** Vector "hacia arriba de la cabeza" (perpendicular a la línea de ojos). */
    val up get() = PointF(sin(angle), -cos(angle))

    fun anchor(name: String): PointF {
        val e = eyes
        val u = up
        return when (name) {
            "leftEye" -> leftEye
            "rightEye" -> rightEye
            "nose" -> nose
            "mouth" -> mouth
            "underNose" -> PointF(nose.x * 0.45f + mouth.x * 0.55f, nose.y * 0.45f + mouth.y * 0.55f)
            "leftCheek" -> leftCheek
            "rightCheek" -> rightCheek
            "forehead" -> PointF(e.x + u.x * faceWidth * 0.45f, e.y + u.y * faceWidth * 0.45f)
            "headTop" -> PointF(e.x + u.x * faceWidth * 0.85f, e.y + u.y * faceWidth * 0.85f)
            "chin" -> PointF(mouth.x - u.x * faceWidth * 0.3f, mouth.y - u.y * faceWidth * 0.3f)
            else -> e
        }
    }

    fun scaled(k: Float) = FaceGeom(
        PointF(leftEye.x * k, leftEye.y * k), PointF(rightEye.x * k, rightEye.y * k),
        PointF(nose.x * k, nose.y * k), PointF(mouth.x * k, mouth.y * k),
        PointF(leftCheek.x * k, leftCheek.y * k), PointF(rightCheek.x * k, rightCheek.y * k),
        faceWidth * k, angle, mouthOpen, smile, id
    )

    /** Mezcla suave con la cara anterior para que los stickers no tiemblen. */
    fun smoothFrom(prev: FaceGeom?, a: Float): FaceGeom {
        if (prev == null || hypot(prev.nose.x - nose.x, prev.nose.y - nose.y) > faceWidth * 0.6f) return this
        fun l(p: PointF, q: PointF) = PointF(q.x + (p.x - q.x) * a, q.y + (p.y - q.y) * a)
        return FaceGeom(
            l(leftEye, prev.leftEye), l(rightEye, prev.rightEye), l(nose, prev.nose), l(mouth, prev.mouth),
            l(leftCheek, prev.leftCheek), l(rightCheek, prev.rightCheek),
            prev.faceWidth + (faceWidth - prev.faceWidth) * a,
            prev.angle + (angle - prev.angle) * a,
            mouthOpen, smile, id
        )
    }

    companion object {
        /** De ML Kit a nuestra geometría. width = ancho de la imagen (para espejar). */
        fun from(face: Face, imageWidth: Int, mirror: Boolean): FaceGeom? {
            fun m(p: PointF) = if (mirror) PointF(imageWidth - p.x, p.y) else PointF(p.x, p.y)
            val box = face.boundingBox
            val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
            val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
            val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
                ?: PointF(box.exactCenterX(), box.exactCenterY())
            val ml = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            val mr = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
            val mb = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
            val mouth = when {
                ml != null && mr != null -> PointF((ml.x + mr.x) / 2, (ml.y + mr.y) / 2)
                mb != null -> mb
                else -> PointF(nose.x, nose.y + box.height() * 0.2f)
            }
            val lc = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position ?: PointF(le.x, nose.y)
            val rc = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position ?: PointF(re.x, nose.y)

            // Boca abierta: distancia entre labios / alto de la cara
            val upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
            val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
            val open = if (!upper.isNullOrEmpty() && !lower.isNullOrEmpty()) {
                val u = upper[upper.size / 2]
                val l = lower[lower.size / 2]
                hypot(u.x - l.x, u.y - l.y) / max(1f, box.height().toFloat())
            } else 0f

            val pl = m(le)
            val pr = m(re)
            // Que "izquierda" sea siempre el ojo de la izquierda en pantalla
            val (l, r) = if (pl.x <= pr.x) pl to pr else pr to pl
            val (lch, rch) = m(lc).let { a -> m(rc).let { b -> if (a.x <= b.x) a to b else b to a } }
            val angle = atan2(r.y - l.y, r.x - l.x)
            val width = max(box.width().toFloat(), hypot(r.x - l.x, r.y - l.y) * 2.3f)
            return FaceGeom(
                l, r, m(nose), m(mouth), lch, rch, width, angle,
                open, face.smilingProbability ?: 0f, face.trackingId ?: 0
            )
        }
    }
}

/** Dibuja un efecto sobre una imagen (vista previa, video o foto final). */
class EffectRenderer(private val context: Context) {

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float,
        val size: Float, var rot: Float, val img: Int = 0, val vr: Float = 0f, var phase: Float = 0f,
    )

    private val particles = mutableListOf<Particle>()
    private var lastMs = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var meshVerts = FloatArray(0)

    fun reset() {
        particles.clear()
        lastMs = 0
    }

    /**
     * frame: imagen ya derecha (y espejada si es selfie), mismo sistema de coordenadas que faces.
     * Dibuja en canvas escalando frame a (outW x outH).
     */
    fun render(canvas: Canvas, frame: Bitmap, faces: List<FaceGeom>, effect: FaceEffect, nowMs: Long, outW: Int, outH: Int) {
        val sx = outW.toFloat() / frame.width
        val sy = outH.toFloat() / frame.height
        canvas.drawColor(Color.BLACK)

        // 1) Imagen base, con deformación si hay
        paint.colorFilter = colorFilter(effect, nowMs)
        paint.alpha = 255
        val face = faces.firstOrNull()
        if ((effect.warp != null || Beauty.warps) && face != null) {
            drawWarped(canvas, frame, faces, effect, sx, sy)
        } else if (effect.mirror) {
            // Espejo: la mitad izquierda reflejada a la derecha
            canvas.save()
            canvas.scale(sx, sy)
            canvas.clipRect(0f, 0f, frame.width / 2f, frame.height.toFloat())
            canvas.drawBitmap(frame, 0f, 0f, paint)
            canvas.restore()
            canvas.save()
            canvas.scale(-sx, sy, outW / 2f, 0f)
            canvas.clipRect(0f, 0f, frame.width / 2f, frame.height.toFloat())
            canvas.drawBitmap(frame, 0f, 0f, paint)
            canvas.restore()
        } else {
            canvas.save()
            canvas.scale(sx, sy)
            canvas.drawBitmap(frame, 0f, 0f, paint)
            canvas.restore()
        }
        paint.colorFilter = null

        if (Beauty.smooth > 0.01f && faces.isNotEmpty()) drawSmooth(canvas, frame, faces, sx, sy)

        if (effect.glitch) drawGlitch(canvas, frame, nowMs, sx, sy)
        if (effect.scanlines) drawScanlines(canvas, outW, outH)

        drawOverlay(canvas, faces, effect, nowMs, sx, sy, frame.width, frame.height)
    }

    /**
     * Todo lo que va encima de la imagen: tintes, stickers, partículas y capas de pantalla.
     * Se usa sobre la vista previa normal y también al dibujar el cuadro completo.
     */
    fun drawOverlay(
        canvas: Canvas, faces: List<FaceGeom>, effect: FaceEffect, nowMs: Long,
        sx: Float, sy: Float, imgW: Int, imgH: Int,
    ) {
        paint.colorFilter = null
        paint.alpha = 255
        val face = faces.firstOrNull()
        val outW = imgW * sx
        val outH = imgH * sy
        val t = (nowMs % 1_000_000L) / 1000f

        for (ov in effect.overlays) if (ov.type == "tint") drawTint(canvas, ov, outW, outH)

        // Stickers en cada cara
        for (f in faces) {
            for (st in effect.stickers) {
                if (st.trigger == "mouthOpen" && f.mouthOpen < 0.07f) continue
                if (st.trigger == "smile" && f.smile < 0.6f) continue
                val bmp = EffectStore.image(context, effect.base, st.image) ?: continue
                val a = f.anchor(st.anchor)
                val c = cos(f.angle)
                val sn = sin(f.angle)
                var extraY = 0f
                var rot = Math.toDegrees(f.angle.toDouble()).toFloat()
                var scale = 1f
                var alpha = 255
                val wave = sin(t * st.animSpeed * 2f * Math.PI.toFloat())
                when (st.anim) {
                    "bob" -> extraY = wave * st.animAmount
                    "spin" -> rot += (t * st.animSpeed * 360f) % 360f
                    "pulse" -> scale = 1f + wave * st.animAmount
                    "sway" -> rot += wave * st.animAmount * 100f
                    "blink" -> alpha = if ((t * st.animSpeed) % 1f < 0.55f) 255 else 40
                }
                val offY = st.offsetY + extraY
                val ox = (st.offsetX * c - offY * sn) * f.faceWidth
                val oy = (st.offsetX * sn + offY * c) * f.faceWidth
                val k = st.width * f.faceWidth * sx * scale / bmp.width
                matrix.reset()
                matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                matrix.postScale(k, k)
                matrix.postRotate(rot)
                matrix.postTranslate((a.x + ox) * sx, (a.y + oy) * sy)
                paint.alpha = alpha
                canvas.drawBitmap(bmp, matrix, paint)
            }
        }
        paint.alpha = 255

        effect.particles?.let { p -> drawParticles(canvas, p, effect, face, nowMs, sx, sy, imgW, imgH) }

        for (ov in effect.overlays) {
            when (ov.type) {
                "image" -> drawImageOverlay(canvas, effect, ov, outW, outH)
                "date" -> drawDate(canvas, ov, outW, outH)
                "rec" -> drawRec(canvas, outW, outH, nowMs)
            }
        }
        lastMs = nowMs
    }

    /** ¿Hay algo que se mueva solo? (para seguir redibujando). */
    fun animated(e: FaceEffect) = e.particles != null || e.stickers.any { it.anim != "none" } ||
        e.overlays.any { it.type == "rec" }

    private val ovPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun drawTint(canvas: Canvas, ov: Overlay, w: Float, h: Float) {
        if (ov.colors.isEmpty()) return
        ovPaint.shader = if (ov.colors.size == 1) null else android.graphics.LinearGradient(
            0f, 0f, 0f, h, ov.colors.toIntArray(), null, android.graphics.Shader.TileMode.CLAMP
        )
        ovPaint.color = if (ov.colors.size == 1) ov.colors[0] else Color.WHITE
        ovPaint.alpha = (255 * ov.alpha).toInt()
        canvas.drawRect(0f, 0f, w, h, ovPaint)
        ovPaint.shader = null
        ovPaint.alpha = 255
    }

    private fun drawImageOverlay(canvas: Canvas, effect: FaceEffect, ov: Overlay, w: Float, h: Float) {
        val bmp = EffectStore.image(context, effect.base, ov.image ?: return) ?: return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val (scale, x, y) = when (ov.fit) {
            "top" -> Triple(w / bw, 0f, 0f)
            "bottom" -> Triple(w / bw, 0f, h - bh * (w / bw))
            "center" -> {
                val k = min(w / bw, h / bh) * 0.9f
                Triple(k, (w - bw * k) / 2, (h - bh * k) / 2)
            }
            "stretch" -> Triple(-1f, 0f, 0f)
            else -> {
                val k = max(w / bw, h / bh)
                Triple(k, (w - bw * k) / 2, (h - bh * k) / 2)
            }
        }
        ovPaint.alpha = (255 * ov.alpha).toInt()
        if (scale < 0f) {
            canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, w, h), ovPaint)
        } else {
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(x, y)
            canvas.drawBitmap(bmp, matrix, ovPaint)
        }
        ovPaint.alpha = 255
    }

    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val dateText = java.text.SimpleDateFormat("MM dd ''yy", java.util.Locale.US)

    /** Fecha naranja como las cámaras desechables y digitales de antes. */
    private fun drawDate(canvas: Canvas, ov: Overlay, w: Float, h: Float) {
        datePaint.color = ov.color
        datePaint.textSize = w * 0.06f
        datePaint.setShadowLayer(w * 0.012f, 0f, 0f, (ov.color and 0x00FFFFFF) or 0xAA000000.toInt())
        datePaint.letterSpacing = 0.08f
        canvas.drawText(dateText.format(java.util.Date()), w * 0.93f, h * 0.93f, datePaint)
    }

    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        color = Color.WHITE
    }
    private val recStart = System.currentTimeMillis()

    /** Pantalla de videocámara: REC, contador, batería y esquinas. */
    private fun drawRec(canvas: Canvas, w: Float, h: Float, nowMs: Long) {
        val m = w * 0.06f
        val ts = w * 0.045f
        recPaint.textSize = ts
        recPaint.setShadowLayer(w * 0.008f, 0f, 0f, 0xAA000000.toInt())
        recPaint.style = Paint.Style.FILL
        recPaint.textAlign = Paint.Align.LEFT
        if ((nowMs / 600) % 2 == 0L) {
            recPaint.color = 0xFFFF3B30.toInt()
            canvas.drawCircle(m + ts * 0.35f, m + ts * 0.65f, ts * 0.32f, recPaint)
        }
        recPaint.color = Color.WHITE
        canvas.drawText("REC", m + ts * 0.9f, m + ts, recPaint)
        val secs = ((nowMs - recStart) / 1000) % 3600
        recPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(java.util.Locale.US, "00:%02d:%02d", secs / 60, secs % 60), w - m, m + ts, recPaint)
        recPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(java.text.SimpleDateFormat("MMM dd yyyy", java.util.Locale.US).format(java.util.Date()).uppercase(), m, h - m, recPaint)
        // Batería
        recPaint.style = Paint.Style.STROKE
        recPaint.strokeWidth = w * 0.005f
        val bx = w - m - ts * 1.6f
        val by = h - m - ts * 0.75f
        canvas.drawRect(bx, by, bx + ts * 1.4f, by + ts * 0.75f, recPaint)
        recPaint.style = Paint.Style.FILL
        canvas.drawRect(bx + ts * 1.4f, by + ts * 0.22f, bx + ts * 1.52f, by + ts * 0.53f, recPaint)
        canvas.drawRect(bx + ts * 0.12f, by + ts * 0.12f, bx + ts * 0.95f, by + ts * 0.63f, recPaint)
        // Esquinas
        recPaint.style = Paint.Style.STROKE
        recPaint.strokeWidth = w * 0.006f
        val c = w * 0.07f
        val e = m * 0.5f
        canvas.drawLine(e, e, e + c, e, recPaint); canvas.drawLine(e, e, e, e + c, recPaint)
        canvas.drawLine(w - e, e, w - e - c, e, recPaint); canvas.drawLine(w - e, e, w - e, e + c, recPaint)
        canvas.drawLine(e, h - e, e + c, h - e, recPaint); canvas.drawLine(e, h - e, e, h - e - c, recPaint)
        canvas.drawLine(w - e, h - e, w - e - c, h - e, recPaint); canvas.drawLine(w - e, h - e, w - e, h - e - c, recPaint)
        recPaint.style = Paint.Style.FILL
    }

    val hasParticles get() = particles.isNotEmpty()

    companion object {
        /** ¿El efecto cambia la imagen entera? (si no, basta con pintar encima). */
        fun needsFrame(e: FaceEffect) = e.warp != null || e.glitch || e.scanlines || e.mirror
    }

    private fun colorFilter(effect: FaceEffect, nowMs: Long): ColorMatrixColorFilter? {
        val m = effect.matrix() ?: ColorMatrix()
        if (Beauty.bright > 0.01f) {
            // Iluminar: sube sombras y da un tono cálido suave
            val t = Beauty.bright * 34f
            val k = 1f - t / 255f
            m.postConcat(ColorMatrix(floatArrayOf(
                k * 1.02f, 0f, 0f, 0f, t + 4f * Beauty.bright,
                0f, k, 0f, 0f, t,
                0f, 0f, k * 0.98f, 0f, t - 2f * Beauty.bright,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        if (effect.matrix() == null && !effect.glitch && Beauty.bright <= 0.01f) return null
        if (effect.glitch) {
            // Ligero parpadeo de contraste
            val k = 1f + 0.08f * sin(nowMs / 90.0).toFloat()
            m.postConcat(ColorMatrix().apply { setScale(k, k, k, 1f) })
        }
        return ColorMatrixColorFilter(m)
    }

    private val glitchPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        alpha = 110
    }

    private fun drawGlitch(canvas: Canvas, frame: Bitmap, nowMs: Long, sx: Float, sy: Float) {
        val rnd = Random(nowMs / 120)
        val shift = (6 + rnd.nextInt(14)) * sx
        // Canal rojo a un lado y azul al otro
        glitchPaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        )
        canvas.save(); canvas.translate(shift, 0f); canvas.scale(sx, sy)
        canvas.drawBitmap(frame, 0f, 0f, glitchPaint); canvas.restore()
        glitchPaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        )
        canvas.save(); canvas.translate(-shift, 0f); canvas.scale(sx, sy)
        canvas.drawBitmap(frame, 0f, 0f, glitchPaint); canvas.restore()
        // Franjas desplazadas al azar
        if (rnd.nextFloat() < 0.5f) {
            repeat(3) {
                val y = rnd.nextInt(frame.height)
                val hh = 6 + rnd.nextInt(30)
                val dx = (rnd.nextInt(40) - 20) * sx
                canvas.save()
                canvas.clipRect(0f, y * sy, canvas.width.toFloat(), (y + hh) * sy)
                canvas.translate(dx, 0f); canvas.scale(sx, sy)
                canvas.drawBitmap(frame, 0f, 0f, null)
                canvas.restore()
            }
        }
    }

    private val linePaint = Paint().apply { color = 0x26000000 }

    // ---------- Belleza: piel suave ----------
    private var smallFrame: Bitmap? = null
    private val smoothPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Piel suave: una versión borrosa de la imagen solo dentro del óvalo de la cara,
     * dejando nítidos ojos, cejas y boca.
     */
    private fun drawSmooth(canvas: Canvas, frame: Bitmap, faces: List<FaceGeom>, sx: Float, sy: Float) {
        val w = (frame.width / 6).coerceAtLeast(2)
        val h = (frame.height / 6).coerceAtLeast(2)
        var sm = smallFrame
        if (sm == null || sm.width != w || sm.height != h) {
            sm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            smallFrame = sm
        }
        Canvas(sm!!).drawBitmap(frame, null, android.graphics.Rect(0, 0, w, h), smoothPaint)
        val outW = frame.width * sx
        val outH = frame.height * sy
        val layer = canvas.saveLayer(0f, 0f, outW, outH, null)
        smoothPaint.alpha = (Beauty.smooth * 215).toInt()
        canvas.drawBitmap(sm, null, android.graphics.RectF(0f, 0f, outW, outH), smoothPaint)
        smoothPaint.alpha = 255
        // Máscara: óvalo de la cara (suave en el borde)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        val maskLayer = canvas.saveLayer(0f, 0f, outW, outH, maskPaint)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        for (f in faces) {
            val cx = (f.nose.x) * sx
            val cy = ((f.eyes.y + f.mouth.y) / 2f) * sy
            val rx = f.faceWidth * 0.5f * sx
            val ry = f.faceWidth * 0.68f * sy
            fill.shader = android.graphics.RadialGradient(
                0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.72f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(Math.toDegrees(f.angle.toDouble()).toFloat())
            canvas.scale(rx, ry)
            canvas.drawCircle(0f, 0f, 1f, fill)
            canvas.restore()
            // Quitar ojos y boca para que queden nítidos
            fill.shader = null
            fill.color = Color.BLACK
            fill.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            for (e in listOf(f.leftEye, f.rightEye)) {
                canvas.drawOval(
                    (e.x - f.faceWidth * 0.17f) * sx, (e.y - f.faceWidth * 0.14f) * sy,
                    (e.x + f.faceWidth * 0.17f) * sx, (e.y + f.faceWidth * 0.09f) * sy, fill
                )
            }
            canvas.drawOval(
                (f.mouth.x - f.faceWidth * 0.2f) * sx, (f.mouth.y - f.faceWidth * 0.08f) * sy,
                (f.mouth.x + f.faceWidth * 0.2f) * sx, (f.mouth.y + f.faceWidth * 0.1f) * sy, fill
            )
            fill.xfermode = null
        }
        canvas.restoreToCount(maskLayer)
        canvas.restoreToCount(layer)
    }

    private fun drawScanlines(canvas: Canvas, w: Int, h: Int) {
        var y = 0f
        while (y < h) {
            canvas.drawRect(0f, y, w.toFloat(), y + 2f, linePaint)
            y += 5f
        }
    }

    /** Deforma la imagen con una malla: ojos grandes o cara fina. */
    private fun drawWarped(canvas: Canvas, frame: Bitmap, faces: List<FaceGeom>, effect: FaceEffect, sx: Float, sy: Float) {
        val cols = 36
        val rows = 48
        val n = (cols + 1) * (rows + 1) * 2
        if (meshVerts.size != n) meshVerts = FloatArray(n)
        val fw = frame.width.toFloat()
        val fh = frame.height.toFloat()
        var i = 0
        for (r in 0..rows) {
            for (c in 0..cols) {
                var x = fw * c / cols
                var y = fh * r / rows
                val eyesK = (if (effect.warp == "bigEyes") effect.warpStrength else 0f) + Beauty.eyes * 0.28f
                val slimK = (if (effect.warp == "slim") effect.warpStrength else 0f) + Beauty.slim * 0.55f
                for (f in faces) {
                    if (eyesK > 0f) {
                        for (eye in listOf(f.leftEye, f.rightEye)) {
                            val rad = f.faceWidth * 0.32f
                            val dx = x - eye.x
                            val dy = y - eye.y
                            val d = hypot(dx, dy)
                            if (d < rad) {
                                val t = 1f - d / rad
                                val k = 1f + eyesK * t * t
                                x = eye.x + dx * k
                                y = eye.y + dy * k
                            }
                        }
                    }
                    if (slimK > 0f) {
                        // Acercar las mejillas al centro, de los ojos hacia abajo
                        val cx = f.nose.x
                        val top = f.eyes.y
                        val bottom = f.mouth.y + f.faceWidth * 0.45f
                        if (y in top..bottom) {
                            val dx = x - cx
                            val half = f.faceWidth * 0.75f
                            if (kotlin.math.abs(dx) < half) {
                                val vy = sin(((y - top) / (bottom - top)) * Math.PI).toFloat()
                                val t = 1f - kotlin.math.abs(dx) / half
                                x -= dx * slimK * 0.35f * t * vy
                            }
                        }
                    }
                }
                meshVerts[i++] = x * sx
                meshVerts[i++] = y * sy
            }
        }
        // Los vértices están en coordenadas de salida; la textura se reparte por la cuadrícula
        canvas.drawBitmapMesh(scaledFrame(frame, sx, sy), cols, rows, meshVerts, 0, null, 0, paint)
    }

    private var scaled: Bitmap? = null

    /** drawBitmapMesh reparte la textura uniformemente, así que la imagen va escalada a la salida. */
    private fun scaledFrame(frame: Bitmap, sx: Float, sy: Float): Bitmap {
        if (sx == 1f && sy == 1f) return frame
        val w = (frame.width * sx).toInt().coerceAtLeast(1)
        val h = (frame.height * sy).toInt().coerceAtLeast(1)
        var b = scaled
        if (b == null || b.width != w || b.height != h) {
            b?.recycle()
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            scaled = b
        }
        val c = Canvas(b!!)
        c.save(); c.scale(sx, sy); c.drawBitmap(frame, 0f, 0f, null); c.restore()
        return b
    }

    private fun drawParticles(
        canvas: Canvas, p: Particles, effect: FaceEffect, face: FaceGeom?, nowMs: Long,
        sx: Float, sy: Float, imgW: Int, imgH: Int,
    ) {
        val bmps = p.images.mapNotNull { EffectStore.image(context, effect.base, it) }
        if (bmps.isEmpty()) return
        val dt = if (lastMs == 0L) 0.033f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.2f)
        val pp = Paint(Paint.FILTER_BITMAP_FLAG)

        if (p.mode == "orbit") {
            // Giran alrededor de la cabeza (sin estado)
            val f = face ?: return
            val t = (nowMs % 1_000_000L) / 1000f
            val n = p.count.coerceIn(1, 16)
            for (i in 0 until n) {
                val ang = t * 1.4f + i * (2f * Math.PI.toFloat() / n)
                val depth = 0.75f + 0.25f * sin(ang)
                val x = f.eyes.x + cos(ang) * f.faceWidth * 0.62f
                val y = f.eyes.y - f.faceWidth * 0.72f + sin(ang) * f.faceWidth * 0.16f
                val bmp = bmps[i % bmps.size]
                val k = f.faceWidth * p.size * depth * sx / bmp.width
                pp.alpha = (255 * (0.55f + 0.45f * depth)).toInt()
                matrix.reset()
                matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                matrix.postScale(k, k)
                matrix.postRotate(p.spin * t * 60f + i * 30f)
                matrix.postTranslate(x * sx, y * sy)
                canvas.drawBitmap(bmp, matrix, pp)
            }
            return
        }

        // Crear nuevas
        val screen = p.mode == "fall" || p.mode == "rise"
        if (particles.size < 70 && Random.nextFloat() < p.rate * dt * (if (screen) 1f else 1f)) {
            val img = Random.nextInt(bmps.size)
            when (p.mode) {
                "fall" -> {
                    val size = imgW * p.size * 0.5f * (0.6f + Random.nextFloat() * 0.8f)
                    particles += Particle(
                        Random.nextFloat() * imgW, -size, (Random.nextFloat() - 0.5f) * imgW * 0.08f,
                        imgH * (0.12f + Random.nextFloat() * 0.14f), 1f, size, Random.nextFloat() * 360f,
                        img, (Random.nextFloat() - 0.5f) * p.spin * 200f, Random.nextFloat() * 6f
                    )
                }
                "rise" -> {
                    val size = imgW * p.size * 0.5f * (0.6f + Random.nextFloat() * 0.8f)
                    particles += Particle(
                        Random.nextFloat() * imgW, imgH + size, (Random.nextFloat() - 0.5f) * imgW * 0.05f,
                        -imgH * (0.12f + Random.nextFloat() * 0.12f), 1f, size, (Random.nextFloat() - 0.5f) * 30f,
                        img, (Random.nextFloat() - 0.5f) * p.spin * 120f, Random.nextFloat() * 6f
                    )
                }
                else -> if (face != null) {
                    val e = face.eyes
                    val ang = Random.nextFloat() * Math.PI.toFloat() * 2
                    val r = face.faceWidth * (0.6f + Random.nextFloat() * 0.3f)
                    particles += Particle(
                        e.x + cos(ang) * r, e.y + sin(ang) * r * 1.1f,
                        (Random.nextFloat() - 0.5f) * face.faceWidth * 0.3f, -face.faceWidth * (0.4f + Random.nextFloat() * 0.4f),
                        1f, face.faceWidth * p.size * (0.7f + Random.nextFloat() * 0.6f), (Random.nextFloat() - 0.5f) * 40f,
                        img, (Random.nextFloat() - 0.5f) * p.spin * 120f, 0f
                    )
                }
            }
        }

        val it = particles.iterator()
        while (it.hasNext()) {
            val q = it.next()
            q.phase += dt
            val sway = if (screen) sin(q.phase * 2f) * imgW * 0.03f * dt else 0f
            q.x += q.vx * dt + sway
            q.y += q.vy * dt
            q.rot += q.vr * dt
            q.life -= dt / (if (screen) 9f else 2.2f)
            val out = q.y > imgH + q.size * 2 || q.y < -q.size * 2
            if (q.life <= 0f || (screen && out && q.phase > 0.5f)) {
                it.remove(); continue
            }
            val bmp = bmps[q.img % bmps.size]
            pp.alpha = (255 * min(1f, q.life * 2f)).toInt()
            val k = q.size * sx / bmp.width
            matrix.reset()
            matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
            matrix.postScale(k, k)
            matrix.postRotate(q.rot)
            matrix.postTranslate(q.x * sx, q.y * sy)
            canvas.drawBitmap(bmp, matrix, pp)
        }
    }
}
