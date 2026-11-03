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
)

data class Particles(val image: String, val size: Float, val rate: Float)

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
) {
    val isNone get() = stickers.isEmpty() && warp == null && filter == PhotoFilter.ORIGINAL &&
        !glitch && !scanlines && !mirror && particles == null

    companion object {
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
                    )
                }
            }
            val warp = o.optJSONObject("warp")
            val p = o.optJSONObject("particles")
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
                    Particles(it.getString("image"), it.optDouble("size", 0.15).toFloat(), it.optDouble("rate", 3.0).toFloat())
                },
                base = base,
            )
        }
    }
}

object EffectStore {

    /** Efectos que trae la app + los descargados de la biblioteca. */
    fun all(context: Context): List<FaceEffect> {
        val list = mutableListOf<FaceEffect>()
        try {
            val arr = JSONArray(context.assets.open("effects/effects.json").bufferedReader().readText())
            for (i in 0 until arr.length()) list += FaceEffect.parse(arr.getJSONObject(i), "asset:effects")
        } catch (_: Exception) {
        }
        list += downloaded(context)
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
        }?.sortedBy { it.name } ?: emptyList()

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

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val size: Float, val rot: Float)

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
        if (effect.warp != null && face != null) {
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

        if (effect.glitch) drawGlitch(canvas, frame, nowMs, sx, sy)
        if (effect.scanlines) drawScanlines(canvas, outW, outH)

        drawOverlay(canvas, faces, effect, nowMs, sx, sy)
    }

    /** Solo stickers y partículas (para pintar encima de la vista previa normal). */
    fun drawOverlay(canvas: Canvas, faces: List<FaceGeom>, effect: FaceEffect, nowMs: Long, sx: Float, sy: Float) {
        paint.colorFilter = null
        paint.alpha = 255
        val face = faces.firstOrNull()
        // 2) Stickers en cada cara
        for (f in faces) {
            for (st in effect.stickers) {
                if (st.trigger == "mouthOpen" && f.mouthOpen < 0.07f) continue
                if (st.trigger == "smile" && f.smile < 0.6f) continue
                val bmp = EffectStore.image(context, effect.base, st.image) ?: continue
                val a = f.anchor(st.anchor)
                val c = cos(f.angle)
                val sn = sin(f.angle)
                val ox = (st.offsetX * c - st.offsetY * sn) * f.faceWidth
                val oy = (st.offsetX * sn + st.offsetY * c) * f.faceWidth
                val wPx = st.width * f.faceWidth * sx
                val k = wPx / bmp.width
                matrix.reset()
                matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                matrix.postScale(k, k)
                matrix.postRotate(Math.toDegrees(f.angle.toDouble()).toFloat())
                matrix.postTranslate((a.x + ox) * sx, (a.y + oy) * sy)
                canvas.drawBitmap(bmp, matrix, paint)
            }
        }

        // 3) Partículas que flotan alrededor de la cara
        effect.particles?.let { p -> drawParticles(canvas, p, effect, face, nowMs, sx, sy) }
        lastMs = nowMs
    }

    val hasParticles get() = particles.isNotEmpty()

    companion object {
        /** ¿El efecto cambia la imagen entera? (si no, basta con pintar encima). */
        fun needsFrame(e: FaceEffect) = e.warp != null || e.glitch || e.scanlines || e.mirror
    }

    private fun colorFilter(effect: FaceEffect, nowMs: Long): ColorMatrixColorFilter? {
        val m = effect.filter.matrix()
        if (effect.filter == PhotoFilter.ORIGINAL && !effect.glitch) return null
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
                for (f in faces) {
                    if (effect.warp == "bigEyes") {
                        for (eye in listOf(f.leftEye, f.rightEye)) {
                            val rad = f.faceWidth * 0.32f
                            val dx = x - eye.x
                            val dy = y - eye.y
                            val d = hypot(dx, dy)
                            if (d < rad) {
                                val t = 1f - d / rad
                                val k = 1f + effect.warpStrength * t * t
                                x = eye.x + dx * k
                                y = eye.y + dy * k
                            }
                        }
                    } else if (effect.warp == "slim") {
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
                                x -= dx * effect.warpStrength * 0.35f * t * vy
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

    private fun drawParticles(canvas: Canvas, p: Particles, effect: FaceEffect, face: FaceGeom?, nowMs: Long, sx: Float, sy: Float) {
        val bmp = EffectStore.image(context, effect.base, p.image) ?: return
        val dt = if (lastMs == 0L) 0.033f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.2f)
        if (face != null && Random.nextFloat() < p.rate * dt) {
            val e = face.eyes
            val ang = Random.nextFloat() * Math.PI.toFloat() * 2
            val r = face.faceWidth * (0.6f + Random.nextFloat() * 0.3f)
            particles += Particle(
                e.x + cos(ang) * r, e.y + sin(ang) * r * 1.1f,
                (Random.nextFloat() - 0.5f) * face.faceWidth * 0.3f, -face.faceWidth * (0.4f + Random.nextFloat() * 0.4f),
                1f, face.faceWidth * p.size * (0.7f + Random.nextFloat() * 0.6f), (Random.nextFloat() - 0.5f) * 40f
            )
        }
        val it = particles.iterator()
        val pp = Paint(Paint.FILTER_BITMAP_FLAG)
        while (it.hasNext()) {
            val q = it.next()
            q.x += q.vx * dt
            q.y += q.vy * dt
            q.life -= dt / 2.2f
            if (q.life <= 0f) {
                it.remove(); continue
            }
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
