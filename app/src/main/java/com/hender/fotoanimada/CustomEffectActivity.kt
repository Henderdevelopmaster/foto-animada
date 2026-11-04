package com.hender.fotoanimada

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Crea tu propio efecto: eliges una imagen, dónde va en la cara, el tamaño y el movimiento,
 * lo ves en vivo con la cámara frontal y se guarda en tu tira de efectos.
 */
class CustomEffectActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FaceOverlayView
    private lateinit var engine: EffectsEngine
    private lateinit var thumb: ImageView
    private lateinit var status: TextView
    private lateinit var removeBg: TextView
    private lateinit var nameField: EditText
    private lateinit var panel: View
    private lateinit var pickBig: View
    private lateinit var anchorsRow: LinearLayout
    private lateinit var animsRow: LinearLayout
    private lateinit var slidersBox: LinearLayout

    private val io = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val work by lazy { File(cacheDir, "custom_work").apply { deleteRecursively(); mkdirs() } }

    private var original: Bitmap? = null   // imagen elegida
    private var sticker: Bitmap? = null    // la que se usa (con o sin fondo)
    private var stickerFile = ""
    private var counter = 0
    private var bgRemoved = false

    private var anchor = "headTop"
    private var width = 1.0f
    private var offX = 0f
    private var offY = 0.1f
    private var anim = "none"

    private val anchors = listOf(
        Triple("headTop", "Cabeza", Triple(1.0f, 0f, 0.15f)),
        Triple("forehead", "Frente", Triple(0.8f, 0f, 0f)),
        Triple("eyes", "Ojos", Triple(1.0f, 0f, 0f)),
        Triple("nose", "Nariz", Triple(0.4f, 0f, 0f)),
        Triple("mouth", "Boca", Triple(0.5f, 0f, 0.02f)),
        Triple("chin", "Barbilla", Triple(0.8f, 0f, 0.1f)),
        Triple("leftCheek", "Mejilla izq.", Triple(0.35f, 0f, 0f)),
        Triple("rightCheek", "Mejilla der.", Triple(0.35f, 0f, 0f)),
        Triple("screen", "Toda la pantalla", Triple(1f, 0f, 0f)),
    )
    private val anims = listOf("none" to "Quieto", "bob" to "Flotar", "sway" to "Mecer", "pulse" to "Latir", "spin" to "Girar", "blink" to "Parpadear")

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri)
    }

    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_effect)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        thumb = findViewById(R.id.thumb)
        status = findViewById(R.id.status)
        removeBg = findViewById(R.id.removeBg)
        nameField = findViewById(R.id.name)
        panel = findViewById(R.id.panel)
        pickBig = findViewById(R.id.pickBig)
        anchorsRow = findViewById(R.id.anchors)
        animsRow = findViewById(R.id.anims)
        slidersBox = findViewById(R.id.sliders)

        engine = EffectsEngine(this, findViewById<TextureView>(R.id.frameView), overlay)
        engine.mirror = true

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.save).setOnClickListener { save() }
        val pick = View.OnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.pick).setOnClickListener(pick)
        pickBig.setOnClickListener(pick)
        removeBg.setOnClickListener { toggleRemoveBg() }

        buildAnchors()
        buildSliders()
        buildAnims()
        panel.visibility = View.GONE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            camPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun chip(label: String, tag: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setBackgroundResource(R.drawable.bg_filter_chip)
        this.tag = tag
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    private fun paintChips(row: LinearLayout, selected: String) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i) as TextView
            v.isSelected = v.tag == selected
            v.setTextColor(if (v.isSelected) Color.BLACK else Color.WHITE)
        }
    }

    private fun buildAnchors() {
        for ((id, label, def) in anchors) {
            anchorsRow.addView(chip(label, id) {
                anchor = id
                width = def.first; offX = def.second; offY = def.third
                syncSliders()
                paintChips(anchorsRow, anchor)
                updateEffect()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        paintChips(anchorsRow, anchor)
    }

    private fun buildAnims() {
        for ((id, label) in anims) {
            animsRow.addView(chip(label, id) {
                anim = id
                paintChips(animsRow, anim)
                updateEffect()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        paintChips(animsRow, anim)
    }

    private val bars = HashMap<String, SeekBar>()

    private fun buildSliders() {
        fun row(key: String, label: String, max: Int, toValue: (Int) -> Float, set: (Float) -> Unit) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            line.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(0xE6FFFFFF.toInt())
            }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            val bar = SeekBar(this).apply {
                this.max = max
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF00E676.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        set(toValue(p))
                        updateEffect()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            bars[key] = bar
            line.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            slidersBox.addView(line)
        }
        row("size", "Tamaño", 240, { 0.1f + it / 100f }) { width = it }
        row("y", "Arriba / abajo", 200, { (it - 100) / 100f }) { offY = it }
        row("x", "Izq. / der.", 200, { (it - 100) / 100f }) { offX = it }
        syncSliders()
    }

    private fun syncSliders() {
        bars["size"]?.progress = ((width - 0.1f) * 100).roundToInt()
        bars["y"]?.progress = (offY * 100 + 100).roundToInt()
        bars["x"]?.progress = (offX * 100 + 100).roundToInt()
        val screen = anchor == "screen"
        slidersBox.alpha = if (screen) 0.35f else 1f
        for ((_, b) in bars) b.isEnabled = !screen
    }

    // ---------- Cámara ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val ratio43 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(ratio43).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, engine) }
            val group = UseCaseGroup.Builder()
                .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build())
                .addUseCase(preview).addUseCase(analysis).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, group)
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Imagen ----------

    private fun busy(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun loadImage(uri: Uri) {
        busy("Cargando…")
        io.execute {
            val bmp = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1024) {
                        val k = 1024f / longest
                        d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
                    }
                }.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                busy(null)
                if (bmp == null) {
                    Toast.makeText(this, "No se pudo abrir la imagen", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                original = bmp
                bgRemoved = false
                removeBg.setTextColor(Color.WHITE)
                removeBg.setBackgroundResource(R.drawable.bg_segmented)
                // Si la imagen no tiene transparencia, ofrecer quitar el fondo de una
                useSticker(trimTransparent(bmp))
                pickBig.visibility = View.GONE
                panel.visibility = View.VISIBLE
                if (!bmp.hasAlpha()) toggleRemoveBg()
            }
        }
    }

    private fun toggleRemoveBg() {
        val src = original ?: return
        if (bgRemoved) {
            bgRemoved = false
            removeBg.setTextColor(Color.WHITE)
            removeBg.setBackgroundResource(R.drawable.bg_segmented)
            useSticker(trimTransparent(src))
            return
        }
        busy("Quitando el fondo…")
        io.execute {
            val cut = try {
                val opts = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
                Tasks.await(SubjectSegmentation.getClient(opts).process(InputImage.fromBitmap(src, 0))).foregroundBitmap
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                busy(null)
                if (cut == null) {
                    Toast.makeText(this, "No pude quitar el fondo (el modelo puede estar descargándose)", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                bgRemoved = true
                removeBg.setTextColor(Color.BLACK)
                removeBg.setBackgroundResource(R.drawable.bg_live_pill)
                useSticker(trimTransparent(cut))
            }
        }
    }

    /** Recorta los bordes transparentes para que el tamaño sea el de la figura. */
    private fun trimTransparent(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 16) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < l || b < t) return src
        return Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
    }

    private fun useSticker(bmp: Bitmap) {
        sticker = bmp
        thumb.setImageBitmap(bmp)
        counter++
        stickerFile = "sticker_$counter.png"
        val f = File(work, stickerFile)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        updateEffect()
    }

    // ---------- Efecto ----------

    private fun effectJson(id: String, name: String, image: String): JSONObject {
        val o = JSONObject()
            .put("id", id).put("name", name).put("emoji", "⭐")
            .put("category", "Mis efectos").put("custom", true)
        if (anchor == "screen") {
            o.put("overlays", JSONArray().put(JSONObject().put("type", "image").put("image", image).put("fit", "stretch")))
        } else {
            val s = JSONObject()
                .put("image", image).put("anchor", anchor).put("width", width.toDouble())
                .put("offsetX", offX.toDouble()).put("offsetY", offY.toDouble())
            if (anim != "none") {
                s.put("anim", anim)
                s.put("animAmount", if (anim == "pulse") 0.08 else if (anim == "sway") 0.15 else 0.06)
                s.put("animSpeed", if (anim == "spin") 0.4 else 1.0)
            }
            o.put("stickers", JSONArray().put(s))
        }
        return o
    }

    private fun updateEffect() {
        if (sticker == null) return
        engine.effect = FaceEffect.parse(effectJson("custom_preview", "Vista previa", stickerFile), work.path)
    }

    private fun save() {
        val bmp = sticker ?: run {
            Toast.makeText(this, "Primero elige una imagen", Toast.LENGTH_SHORT).show(); return
        }
        val name = nameField.text.toString().trim().ifBlank { "Mi efecto" }
        val id = "custom_" + System.currentTimeMillis()
        val dir = File(EffectStore.downloadedDir(this), id).apply { mkdirs() }
        File(dir, "sticker.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "icon.png").outputStream().use { makeIcon(bmp).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val json = effectJson(id, name, "sticker.png").put("icon", "icon.png")
        File(dir, "effect.json").writeText(json.toString(1))
        Toast.makeText(this, "“$name” guardado. Ya está en tu tira de efectos.", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    /** Ícono redondo con degradado verde y la imagen al centro. */
    private fun makeIcon(src: Bitmap): Bitmap {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(size * 0.35f, size * 0.3f, size * 0.9f, 0xFFB9F6CA.toInt(), 0xFF00A152.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2, bg)
        val box = (size * 0.68f)
        val k = minOf(box / src.width, box / src.height)
        val w = src.width * k
        val h = src.height * k
        val dst = android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
        c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute { engine.release() }
        analysisExecutor.shutdown()
        io.shutdown()
    }
}
