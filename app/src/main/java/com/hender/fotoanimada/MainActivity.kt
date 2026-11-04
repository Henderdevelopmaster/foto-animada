package com.hender.fotoanimada

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaActionSound
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    enum class Mode(val label: String) {
        TIMELAPSE("TIME-LAPSE"), VIDEO("VIDEO"), PHOTO("FOTO"), EFFECTS("EFECTOS"),
        PORTRAIT("RETRATO"), NIGHT("NOCHE"), INSTANT("INSTANTÁNEA")
    }

    private lateinit var previewView: PreviewView
    private lateinit var shutter: ImageButton
    private lateinit var flip: ImageButton
    private lateinit var liveToggle: ImageButton
    private lateinit var filterButton: ImageButton
    private lateinit var gridButton: ImageButton
    private lateinit var timerButton: TextView
    private lateinit var resButton: TextView
    private lateinit var livePill: TextView
    private lateinit var modeScroll: HorizontalScrollView
    private lateinit var modeList: LinearLayout
    private lateinit var captionPill: TextView
    private lateinit var modeHint: TextView
    private lateinit var qrChip: TextView
    private lateinit var effectView: TextureView
    private lateinit var faceOverlay: FaceOverlayView
    private lateinit var beautyButton: ImageButton
    private lateinit var beautyPanel: LinearLayout
    private lateinit var effectBar: HorizontalScrollView
    private lateinit var effectList: LinearLayout
    private lateinit var effectName: TextView
    private lateinit var engine: EffectsEngine
    private var effects = listOf<FaceEffect>()
    private var effectId = "perrito"
    private var effectRecording = false
    private var instantLook = InstantLook.NONE
    private lateinit var toast: TextView
    private lateinit var countdown: TextView
    private lateinit var recTimer: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var flash: View
    private lateinit var focusRing: View
    private lateinit var gridOverlay: GridOverlay
    private lateinit var zoom1: TextView
    private lateinit var zoom2: TextView
    private lateinit var filterBar: HorizontalScrollView
    private lateinit var filterList: LinearLayout
    private lateinit var maskTop: View
    private lateinit var maskBottom: View
    private lateinit var developOverlay: View
    private lateinit var developImage: ImageView

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val recorder = MotionRecorder()
    private val audio = AudioRingRecorder()
    private val sound = MediaActionSound()
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var liveOn = true
    private var busy = false
    private var mode = Mode.PHOTO
    private var filter = PhotoFilter.ORIGINAL
    private var timerSecs = 0
    private var gridOn = false

    // QuickTake y ráfaga
    private var quickTaking = false
    private var quickStart = 0L
    private var bursting = false
    private var burstCount = 0
    private var countingDown = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.CAMERA] == true) startCamera()
            else showToast("Necesito permiso de cámara")
            updateAudio()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        shutter = findViewById(R.id.shutter)
        flip = findViewById(R.id.flip)
        liveToggle = findViewById(R.id.liveToggle)
        filterButton = findViewById(R.id.filterButton)
        gridButton = findViewById(R.id.gridButton)
        timerButton = findViewById(R.id.timerButton)
        resButton = findViewById(R.id.resButton)
        resButton.setOnClickListener { showResolutionMenu() }
        livePill = findViewById(R.id.livePill)
        modeScroll = findViewById(R.id.modeScroll)
        modeList = findViewById(R.id.modeList)
        captionPill = findViewById(R.id.captionPill)
        modeHint = findViewById(R.id.modeHint)
        qrChip = findViewById(R.id.qrChip)
        effectView = findViewById(R.id.effectView)
        faceOverlay = findViewById(R.id.faceOverlay)
        beautyButton = findViewById(R.id.beautyButton)
        beautyPanel = findViewById(R.id.beautyPanel)
        Beauty.load(this)
        buildBeautyPanel()
        beautyButton.setOnClickListener {
            val show = beautyPanel.visibility != View.VISIBLE
            beautyPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                beautyPanel.alpha = 0f
                beautyPanel.translationY = dp(20).toFloat()
                beautyPanel.animate().alpha(1f).translationY(0f).setDuration(180).start()
            }
            tap(it)
        }
        effectBar = findViewById(R.id.effectBar)
        effectList = findViewById(R.id.effectList)
        effectName = findViewById(R.id.effectName)
        engine = EffectsEngine(this, effectView, faceOverlay)
        engine.audio = audio
        effectId = prefs.getString(KEY_EFFECT, "perrito") ?: "perrito"
        instantLook = InstantLook.fromId(prefs.getString(KEY_LOOK, null))
        toast = findViewById(R.id.toast)
        countdown = findViewById(R.id.countdown)
        recTimer = findViewById(R.id.recTimer)
        thumbnail = findViewById(R.id.thumbnail)
        flash = findViewById(R.id.flash)
        focusRing = findViewById(R.id.focusRing)
        gridOverlay = findViewById(R.id.gridOverlay)
        zoom1 = findViewById(R.id.zoom1)
        zoom2 = findViewById(R.id.zoom2)
        filterBar = findViewById(R.id.filterBar)
        filterList = findViewById(R.id.filterList)
        maskTop = findViewById(R.id.instantMaskTop)
        maskBottom = findViewById(R.id.instantMaskBottom)
        developOverlay = findViewById(R.id.developOverlay)
        developImage = findViewById(R.id.developImage)

        // TextureView: permite pintar la vista previa con el filtro en vivo
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING) applyPreviewFilter()
        }

        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
        liveOn = prefs.getBoolean(KEY_LIVE, true)
        filter = PhotoFilter.fromId(prefs.getString(KEY_FILTER, null))
        mode = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.PHOTO.name)!!) }.getOrDefault(Mode.PHOTO)
        timerSecs = prefs.getInt(KEY_TIMER, 0)
        gridOn = prefs.getBoolean(KEY_GRID, false)
        recorder.audio = audio
        caption = prefs.getString(KEY_CAPTION, "") ?: ""
        buildModes()
        buildEffects()
        buildFilterChips()
        applyModeUi()
        applyTimerUi()
        applyGridUi()

        setupShutter()
        setupZoomAndFocus()

        liveToggle.setOnClickListener {
            liveOn = !liveOn
            prefs.edit().putBoolean(KEY_LIVE, liveOn).apply()
            tap(it)
            applyModeUi()
            showToast(if (liveOn) "LIVE activado" else "LIVE desactivado")
        }
        filterButton.setOnClickListener {
            val show = filterBar.visibility != View.VISIBLE
            filterBar.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                refreshFilterThumbs()
                filterBar.alpha = 0f
                filterBar.translationY = dp(20).toFloat()
                filterBar.animate().alpha(1f).translationY(0f).setDuration(180).start()
            }
            tap(it)
        }
        timerButton.setOnClickListener {
            timerSecs = when (timerSecs) { 0 -> 3; 3 -> 10; else -> 0 }
            prefs.edit().putInt(KEY_TIMER, timerSecs).apply()
            tap(it)
            applyTimerUi()
            showToast(if (timerSecs == 0) "Temporizador apagado" else "Temporizador: $timerSecs s")
        }
        gridButton.setOnClickListener {
            gridOn = !gridOn
            prefs.edit().putBoolean(KEY_GRID, gridOn).apply()
            tap(it)
            applyGridUi()
        }
        captionPill.setOnClickListener { editCaption() }
        qrChip.setOnClickListener { openQr() }
        findViewById<View>(R.id.resultDone).setOnClickListener { hideDevelop() }
        findViewById<View>(R.id.resultSend).setOnClickListener { sendResult() }
        flip.setOnClickListener {
            if (busy || recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            it.animate().rotationBy(180f).setDuration(300).start()
            engine.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
            bindCamera()
        }
        thumbnail.setOnClickListener {
            if (recording == null) startActivity(Intent(this, ViewerActivity::class.java))
        }
        developOverlay.setOnClickListener { }

        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.all { has(it) }) startCamera() else permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        updateAudio()
        refreshThumbnail()
        main.postDelayed(qrScan, 1200)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(qrScan)
        if (timelapsing) stopTimelapse()
        if (quickTaking) stopQuickTake()
        if (effectRecording) stopEffectRec()
        bursting = false
        cancelCountdown()
        recording?.stop()
        audio.stop()
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private val liveActive get() = liveOn && mode == Mode.PHOTO

    private val usesAnalysis get() = mode == Mode.PHOTO || mode == Mode.TIMELAPSE || mode == Mode.INSTANT

    // ---------- Modos, filtros, temporizador, cuadrícula ----------

    private fun setMode(m: Mode) {
        if (mode == m || busy || recording != null || quickTaking || timelapsing) return
        val old = mode
        mode = m
        if (m == Mode.EFFECTS && lensFacing != CameraSelector.LENS_FACING_FRONT) {
            lensFacing = CameraSelector.LENS_FACING_FRONT
            if (groupOf(old) == groupOf(m)) bindCamera()
        }
        prefs.edit().putString(KEY_MODE, m.name).apply()
        tap(modeList)
        applyModeUi()
        if (groupOf(old) != groupOf(m)) bindCamera()
    }

    /** Qué combinación de cámara necesita cada modo (si cambia, hay que reconectar). */
    private fun groupOf(m: Mode) = when (m) {
        Mode.VIDEO -> 1
        Mode.NIGHT -> 2
        Mode.PORTRAIT -> 3
        Mode.EFFECTS -> 4
        else -> 0
    }

    // ---------- Belleza ----------

    private fun updateBeautyIcon() {
        beautyButton.setColorFilter(if (Beauty.active) 0xFF00E676.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun buildBeautyPanel() {
        beautyPanel.removeAllViews()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Belleza"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val rows = mutableListOf<Pair<android.widget.SeekBar, TextView>>()
        header.addView(TextView(this).apply {
            text = "Restablecer"
            textSize = 13f
            setTextColor(0xFF00E676.toInt())
            setPadding(dp(8), dp(4), 0, dp(4))
            setOnClickListener {
                Beauty.smooth = 0f; Beauty.bright = 0f; Beauty.slim = 0f; Beauty.eyes = 0f
                Beauty.save(this@MainActivity)
                rows.forEach { (sb, v) -> sb.progress = 0; v.text = "0" }
                updateBeautyIcon(); engine.refresh()
                tap(it)
            }
        })
        beautyPanel.addView(header)

        fun row(label: String, get: () -> Float, set: (Float) -> Unit) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            line.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(0xE6FFFFFF.toInt())
            }, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
            val value = TextView(this).apply {
                textSize = 13f
                gravity = android.view.Gravity.END
                setTextColor(0xFF00E676.toInt())
                text = (get() * 100).toInt().toString()
            }
            val bar = android.widget.SeekBar(this).apply {
                max = 100
                progress = (get() * 100).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF00E676.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        set(p / 100f)
                        value.text = p.toString()
                        updateBeautyIcon()
                        engine.refresh()
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) = Beauty.save(this@MainActivity)
                })
            }
            line.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(value, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
            beautyPanel.addView(line)
            rows += bar to value
        }
        row("Suavizar piel", { Beauty.smooth }, { Beauty.smooth = it })
        row("Iluminar", { Beauty.bright }, { Beauty.bright = it })
        row("Afinar cara", { Beauty.slim }, { Beauty.slim = it })
        row("Agrandar ojos", { Beauty.eyes }, { Beauty.eyes = it })
    }

    // ---------- Efectos tipo TikTok ----------

    fun buildEffects() {
        effects = EffectStore.all(this)
        effectList.removeAllViews()
        val size = dp(52)
        for (fx in effects) {
            val tv = TextView(this).apply {
                text = fx.emoji
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_effect)
                tag = fx.id
                setOnClickListener { selectEffect(fx.id, true) }
            }
            val lp = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
            effectList.addView(tv, lp)
        }
        val more = TextView(this).apply {
            text = "＋"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF00E676.toInt())
            setBackgroundResource(R.drawable.bg_effect)
            setOnClickListener {
                libraryLauncher.launch(Intent(this@MainActivity, EffectsLibraryActivity::class.java))
            }
        }
        effectList.addView(more, LinearLayout.LayoutParams(size, size))
        selectEffect(effectId, false)
    }

    private val libraryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { buildEffects() }

    private fun selectEffect(id: String, user: Boolean) {
        val fx = effects.firstOrNull { it.id == id } ?: effects.firstOrNull() ?: return
        effectId = fx.id
        prefs.edit().putString(KEY_EFFECT, fx.id).apply()
        engine.effect = fx
        applyPreviewFilter()
        for (i in 0 until effectList.childCount) {
            val v = effectList.getChildAt(i)
            v.isSelected = v.tag == fx.id
            v.scaleX = if (v.isSelected) 1.15f else 1f
            v.scaleY = v.scaleX
        }
        effectName.text = fx.name
        if (user) {
            tap(effectList)
            val v = effectList.findViewWithTag<View>(fx.id)
            v?.let { effectBar.smoothScrollTo(it.left - effectBar.width / 2 + it.width / 2, 0) }
        }
    }

    private val effectTick = object : Runnable {
        override fun run() {
            if (!effectRecording) return
            val secs = ((System.currentTimeMillis() - quickStart) / 1000).toInt()
            recTimer.text = String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
            if (secs >= MAX_QUICKTAKE_S) {
                stopEffectRec(); return
            }
            main.postDelayed(this, 250)
        }
    }

    private fun startEffectRec() {
        effectRecording = true
        busy = true
        quickStart = System.currentTimeMillis()
        audio.keepUs = (MAX_QUICKTAKE_S + 5) * 1_000_000L
        updateAudio()
        engine.startRecording()
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        shutter.setBackgroundResource(R.drawable.shutter_rec)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "0:00"
        main.post(effectTick)
    }

    private fun stopEffectRec() {
        if (!effectRecording) return
        effectRecording = false
        main.removeCallbacks(effectTick)
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        recTimer.visibility = View.GONE
        shutter.setBackgroundResource(R.drawable.shutter)
        showToast("Guardando video…")
        engine.stopRecording { clip ->
            ioExecutor.execute {
                var msg: String? = null
                try {
                    if (clip == null) msg = "No se pudo grabar el video"
                    else Exporter.saveVideoBytes(this, MotionPhotoWriter.muxToMp4(clip, cacheDir).bytes, "_EFECTO")
                } catch (e: Throwable) {
                    msg = "Error: ${e.message}"
                }
                runOnUiThread {
                    audio.keepUs = 6_000_000
                    updateAudio()
                }
                finishSave(msg)
            }
        }
    }

    private fun saveEffectPhoto(photo: ByteArray?, front: Boolean) {
        val fx = engine.effect
        if (photo == null || fx == null) {
            finishSave(if (photo == null) "No se pudo tomar la foto" else null)
            return
        }
        val msg = try {
            var bmp = Processing.decode(photo, 2400)
            if (front) {
                val m = android.graphics.Matrix().apply { postScale(-1f, 1f) }
                val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                bmp.recycle()
                bmp = flipped
            }
            val out = if (fx.isNone && !Beauty.active) bmp else EffectsEngine.renderPhoto(this, bmp, fx).also { bmp.recycle() }
            MotionPhotoWriter.save(this, Processing.encode(out, 94), null, "_EFECTO")
            out.recycle()
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Foto con efecto", e)
            "Error: ${e.message}"
        }
        finishSave(msg)
    }

    private fun buildModes() {
        modeList.removeAllViews()
        val side = resources.displayMetrics.widthPixels / 2
        modeList.setPadding(side, 0, side, 0)
        for (m in Mode.entries) {
            val tv = TextView(this).apply {
                text = m.label
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(13), dp(4), dp(13), dp(4))
                tag = m
                setOnClickListener { setMode(m) }
            }
            modeList.addView(tv)
        }
    }

    private fun centerMode() {
        val v = modeList.findViewWithTag<View>(mode) ?: return
        modeScroll.post {
            modeScroll.smoothScrollTo(v.left + v.width / 2 - modeScroll.width / 2, 0)
        }
    }

    private fun swipeMode(dir: Int) {
        val all = Mode.entries
        val next = all.getOrNull(all.indexOf(mode) + dir) ?: return
        setMode(next)
    }

    private fun applyModeUi() {
        val green = 0xFF00E676.toInt()
        val white = 0xCCFFFFFF.toInt()
        for (i in 0 until modeList.childCount) {
            val v = modeList.getChildAt(i) as TextView
            v.setTextColor(if (v.tag == mode) green else white)
        }
        centerMode()
        updateResLabel()
        // Llenar el ancho (anclado arriba) para que no quede un hueco a un lado
        previewView.scaleType = if (mode == Mode.VIDEO) PreviewView.ScaleType.FILL_CENTER
        else PreviewView.ScaleType.FILL_START
        captionPill.visibility = if (mode == Mode.INSTANT) View.VISIBLE else View.GONE
        val fxMode = mode == Mode.EFFECTS
        beautyButton.visibility = if (fxMode) View.VISIBLE else View.GONE
        if (!fxMode) beautyPanel.visibility = View.GONE
        updateBeautyIcon()
        if (fxMode) {
            engine.effect = engine.effect // muestra la capa que toque
        } else {
            effectView.visibility = View.GONE
            faceOverlay.visibility = View.GONE
        }
        effectBar.visibility = if (fxMode) View.VISIBLE else View.GONE
        effectName.visibility = if (fxMode) View.VISIBLE else View.GONE
        engine.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        captionPill.text = caption.ifBlank { "Toca para escribir algo" }
        qrChip.visibility = View.GONE
        val hint = when (mode) {
            Mode.PORTRAIT -> "El fondo se desenfoca al tomar la foto"
            Mode.NIGHT -> "Mantén el cel quieto al disparar"
            Mode.TIMELAPSE -> "Toca para empezar y para terminar"
            Mode.INSTANT -> "Toca para foto · mantén para video"
            Mode.EFFECTS -> "Elige un efecto · mantén para grabar"
            else -> null
        }
        modeHint.text = hint
        modeHint.visibility = if (hint != null) View.VISIBLE else View.GONE
        modeHint.alpha = 1f
        modeHint.animate().alpha(0f).setStartDelay(2500).setDuration(400).start()

        liveToggle.visibility = if (mode == Mode.PHOTO) View.VISIBLE else View.INVISIBLE
        liveToggle.setImageResource(if (liveOn) R.drawable.ic_live_on else R.drawable.ic_live_off)
        val noFilter = mode == Mode.VIDEO || mode == Mode.TIMELAPSE || mode == Mode.EFFECTS
        if (filterList.childCount > 0) {
            val wantInstant = mode == Mode.INSTANT
            if ((filterList.getChildAt(0).tag is InstantLook) != wantInstant) buildFilterChips()
        }
        filterButton.visibility = if (noFilter) View.INVISIBLE else View.VISIBLE
        if (noFilter) filterBar.visibility = View.GONE
        shutter.setBackgroundResource(
            if (mode == Mode.VIDEO || mode == Mode.TIMELAPSE) R.drawable.shutter_rec else R.drawable.shutter
        )
        recorder.enabled = liveActive
        updateAudio()
        applyPreviewFilter()

        // Guías de recorte cuadrado para la instantánea
        val show = false
        previewView.post {
            val w = previewView.width
            val photoH = w * 4 / 3
            val band = (photoH - w) / 2
            (maskTop.layoutParams as FrameLayout.LayoutParams).height = band
            (maskBottom.layoutParams as FrameLayout.LayoutParams).apply {
                height = band
                topMargin = band + w
            }
            maskTop.requestLayout()
            maskBottom.requestLayout()
            maskTop.visibility = if (show) View.VISIBLE else View.GONE
            maskBottom.visibility = if (show) View.VISIBLE else View.GONE
            gridOverlay.areaHeight = if (mode == Mode.VIDEO) w * 16 / 9 else photoH
        }
    }

    // ---------- Resolución ----------

    private fun updateResLabel() {
        resButton.text = when (mode) {
            Mode.VIDEO -> when (CameraSettings.video(this)) { "uhd" -> "4K"; "hd" -> "720p"; else -> "1080p" }
            Mode.EFFECTS -> if (CameraSettings.effects(this) == "960") "960p" else "720p"
            Mode.TIMELAPSE -> CameraSettings.liveSize(this).height.toString() + "p"
            else -> CameraSettings.label(this)
        }
    }

    /** Panel inferior de calidad, con botones segmentados tipo iPhone. */
    private fun showResolutionMenu() {
        if (busy || recording != null) return
        val dialog = android.app.Dialog(this)
        var changed = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(20), dp(10), dp(20), dp(28))
        }
        root.addView(View(this).apply { setBackgroundResource(R.drawable.bg_handle) },
            LinearLayout.LayoutParams(dp(38), dp(5)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        root.addView(TextView(this).apply {
            text = "Calidad"
            textSize = 21f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Más calidad = archivos más pesados"
            textSize = 13f
            setTextColor(0x8CFFFFFF.toInt())
            setPadding(0, dp(2), 0, dp(10))
        })

        data class Seg(val id: String, val label: String, val note: String)
        fun section(title: String, key: String, segs: List<Seg>) {
            root.addView(TextView(this).apply {
                text = title.uppercase()
                textSize = 11.5f
                letterSpacing = 0.06f
                setTextColor(0x8CFFFFFF.toInt())
                setPadding(dp(2), dp(16), 0, dp(8))
            })
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_segmented)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val note = TextView(this).apply {
                textSize = 12.5f
                setTextColor(0xB3FFFFFF.toInt())
                setPadding(dp(2), dp(7), 0, 0)
            }
            fun paint(current: String) {
                for (i in 0 until bar.childCount) {
                    val v = bar.getChildAt(i) as TextView
                    val on = v.tag == current
                    v.setBackgroundResource(if (on) R.drawable.bg_segment_on else 0)
                    v.setTextColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
                note.text = segs.firstOrNull { it.id == current }?.note ?: ""
            }
            for (sg in segs) {
                bar.addView(TextView(this).apply {
                    text = sg.label
                    tag = sg.id
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setOnClickListener {
                        CameraSettings.set(this@MainActivity, key, sg.id)
                        changed = true
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        paint(sg.id)
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f))
            }
            root.addView(bar, LinearLayout.LayoutParams(-1, -2))
            root.addView(note)
            paint(CameraSettings.get(this, key, segs[segs.size / 2].id).let { cur ->
                if (segs.any { it.id == cur }) cur else segs.first().id
            })
        }

        section("Foto", CameraSettings.PHOTO, listOf(
            Seg("3", "3 MP", "Ocupa poco espacio"),
            Seg("8", "8 MP", "Equilibrada"),
            Seg("12", "12 MP", "Recomendada"),
            Seg("max", "MÁX", "Hasta 200 MP · tarda más y cada foto pesa mucho"),
        ))
        section("Video", CameraSettings.VIDEO, listOf(
            Seg("hd", "720p", "Más liviano"),
            Seg("fhd", "1080p", "Recomendado"),
            Seg("uhd", "4K", "Máxima nitidez · ocupa mucho espacio"),
        ))
        section("Live, QuickTake y time-lapse", CameraSettings.LIVE, listOf(
            Seg("720", "Ligera", "960 × 720 · más fluido"),
            Seg("960", "Normal", "1280 × 960 · recomendada"),
            Seg("1440", "Alta", "1920 × 1440 · más nítido, más pesado"),
        ))
        section("Video con efectos", CameraSettings.EFFECTS, listOf(
            Seg("720", "Fluido", "960 × 720 · recomendado"),
            Seg("960", "Alta", "1280 × 960 · puede ir más lento"),
        ))

        root.addView(TextView(this).apply {
            text = "Listo"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF000000.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_live_pill)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(24) })

        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(android.view.Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.45f)
        }
        dialog.setOnDismissListener {
            if (changed) {
                updateResLabel()
                bindCamera()
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun applyTimerUi() {
        timerButton.text = if (timerSecs == 0) "" else "${timerSecs}s"
        timerButton.compoundDrawablesRelative[0]?.setTint(
            if (timerSecs == 0) 0xFFFFFFFF.toInt() else 0xFF00E676.toInt()
        )
    }

    private fun applyGridUi() {
        gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE
        gridButton.setColorFilter(if (gridOn) 0xFF00E676.toInt() else 0xFFFFFFFF.toInt())
    }

    /** Filtros como miniaturas redondas con la imagen real de la cámara (estilo Instagram). */
    private fun buildFilterChips() {
        filterList.removeAllViews()
        val options: List<Pair<Any, String>> = if (mode == Mode.INSTANT)
            InstantLook.entries.map { it to it.label }
        else PhotoFilter.entries.map { it to it.label }
        val size = dp(60)
        for ((key, label) in options) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                tag = key
                setOnClickListener {
                    when (key) {
                        is InstantLook -> {
                            instantLook = key
                            prefs.edit().putString(KEY_LOOK, key.id).apply()
                        }
                        is PhotoFilter -> {
                            filter = key
                            prefs.edit().putString(KEY_FILTER, key.id).apply()
                        }
                    }
                    tap(filterButton)
                    applyPreviewFilter()
                }
            }
            val frame = FrameLayout(this)
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_thumb_round)
                clipToOutline = true
            }
            frame.addView(img, FrameLayout.LayoutParams(size, size))
            val ring = View(this).apply {
                setBackgroundResource(R.drawable.ring_selected)
                visibility = View.INVISIBLE
            }
            frame.addView(ring, FrameLayout.LayoutParams(size, size))
            item.addView(frame, LinearLayout.LayoutParams(size, size))
            item.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(0xE6FFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
                maxLines = 1
            })
            val lp = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            filterList.addView(item, lp)
        }
        applyPreviewFilter()
    }

    /** Pinta cada miniatura con su filtro sobre lo que ve la cámara ahora. */
    private fun refreshFilterThumbs() {
        val src = previewView.bitmap ?: return
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val base = Bitmap.createScaledBitmap(sq, 180, 180, true)
        val keys = (0 until filterList.childCount).map { filterList.getChildAt(it).tag }
        ioExecutor.execute {
            val thumbs = keys.map { key ->
                when (key) {
                    is InstantLook -> key.apply(base)
                    is PhotoFilter -> Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888).also {
                        android.graphics.Canvas(it).drawBitmap(base, 0f, 0f, key.paint())
                    }
                    else -> base
                }
            }
            runOnUiThread {
                for (i in 0 until minOf(filterList.childCount, thumbs.size)) {
                    val item = filterList.getChildAt(i) as LinearLayout
                    ((item.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView).setImageBitmap(thumbs[i])
                }
            }
        }
    }

    private fun applyPreviewFilter() {
        val cf = when (mode) {
            Mode.VIDEO, Mode.TIMELAPSE -> null
            Mode.EFFECTS -> engine.effect?.filter?.colorFilter()
            Mode.INSTANT -> instantLook.colorFilter()
            else -> filter.colorFilter()
        }
        findTexture(previewView)?.setLayerPaint(cf?.let { Paint().apply { colorFilter = it } })
        val selected: Any = if (mode == Mode.INSTANT) instantLook else filter
        for (i in 0 until filterList.childCount) {
            val item = filterList.getChildAt(i) as LinearLayout
            val on = item.tag == selected
            (item.getChildAt(0) as FrameLayout).getChildAt(1).visibility = if (on) View.VISIBLE else View.INVISIBLE
            (item.getChildAt(1) as TextView).setTextColor(if (on) 0xFF00E676.toInt() else 0xE6FFFFFF.toInt())
        }
        val active = if (mode == Mode.INSTANT) instantLook != InstantLook.NONE else filter != PhotoFilter.ORIGINAL
        filterButton.setColorFilter(if (active) 0xFF00E676.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun findTexture(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTexture(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun updateAudio() {
        val want = (liveActive || quickTaking || effectRecording) && has(Manifest.permission.RECORD_AUDIO)
        if (want) audio.start() else audio.stop()
    }

    // ---------- Zoom y enfoque ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoomAndFocus() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val state = cam.cameraInfo.zoomState.value ?: return true
                val target = (state.zoomRatio * d.scaleFactor).coerceIn(1f, minOf(state.maxZoomRatio, 10f))
                cam.cameraControl.setZoomRatio(target)
                return true
            }
        })
        var downX = 0f
        var downY = 0f
        var multi = false
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; multi = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    when {
                        multi || scale.isInProgress -> {}
                        abs(dx) > dp(70) && abs(dx) > abs(dy) * 1.5f -> swipeMode(if (dx < 0) 1 else -1)
                        ev.eventTime - ev.downTime < 250 && abs(dx) < dp(10) -> focusAt(ev.x, ev.y)
                    }
                }
            }
            true
        }
        zoom1.setOnClickListener { camera?.cameraControl?.setZoomRatio(1f); tap(it) }
        zoom2.setOnClickListener { camera?.cameraControl?.setZoomRatio(2f); tap(it) }
    }

    private fun observeZoom() {
        camera?.cameraInfo?.zoomState?.observe(this) { z ->
            val r = z.zoomRatio
            val near2 = abs(r - 2f) < 0.05f
            zoom1.isSelected = r < 1.95f
            zoom2.isSelected = r >= 1.95f
            zoom1.text = if (r < 1.95f) String.format(Locale.US, "%.1fx", r).replace(".0x", "x") else "1x"
            zoom2.text = if (r >= 1.95f && !near2) String.format(Locale.US, "%.1fx", r) else "2x"
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        focusRing.x = x - focusRing.width / 2
        focusRing.y = y - focusRing.height / 2
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
        }.start()
    }

    // ---------- Obturador: toque, mantener (QuickTake) y deslizar (ráfaga) ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        var downX = 0f
        var longStarted = false
        val longPress = Runnable {
            if ((mode == Mode.PHOTO || mode == Mode.INSTANT) && !busy) {
                longStarted = true
                startQuickTake()
            } else if (mode == Mode.EFFECTS && !busy) {
                longStarted = true
                startEffectRec()
            }
        }
        shutter.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    longStarted = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    if ((mode == Mode.PHOTO || mode == Mode.INSTANT || mode == Mode.EFFECTS) && timerSecs == 0) main.postDelayed(longPress, 400)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    if (mode == Mode.PHOTO && !bursting && !quickTaking && dx < -dp(70) && !busy) {
                        main.removeCallbacks(longPress)
                        startBurst()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    when {
                        quickTaking -> stopQuickTake()
                        effectRecording -> stopEffectRec()
                        bursting -> stopBurst()
                        longStarted -> {}
                        ev.actionMasked == MotionEvent.ACTION_UP -> onShutterTap()
                    }
                }
            }
            true
        }
    }

    private fun onShutterTap() {
        when {
            mode == Mode.VIDEO -> toggleVideo()
            mode == Mode.TIMELAPSE -> if (timelapsing) stopTimelapse() else startTimelapse()
            countingDown -> cancelCountdown()
            timerSecs > 0 -> startCountdown()
            else -> capture()
        }
    }

    private var countdownLeft = 0
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!countingDown) return
            if (countdownLeft <= 0) {
                countingDown = false
                countdown.visibility = View.GONE
                capture()
                return
            }
            countdown.text = countdownLeft.toString()
            countdown.alpha = 1f
            countdown.scaleX = 1.3f
            countdown.scaleY = 1.3f
            countdown.animate().scaleX(1f).scaleY(1f).alpha(0.6f).setDuration(800).start()
            shutter.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            countdownLeft--
            main.postDelayed(this, 1000)
        }
    }

    private fun startCountdown() {
        if (busy) return
        countingDown = true
        countdownLeft = timerSecs
        countdown.visibility = View.VISIBLE
        main.post(countdownTick)
    }

    private fun cancelCountdown() {
        if (!countingDown) return
        countingDown = false
        main.removeCallbacks(countdownTick)
        countdown.visibility = View.GONE
    }

    // QuickTake: video mientras mantienes presionado (usa el mismo grabador del LIVE)
    private val quickTick = object : Runnable {
        override fun run() {
            if (!quickTaking) return
            val secs = ((System.currentTimeMillis() - quickStart) / 1000).toInt()
            recTimer.text = String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
            if (secs >= (if (mode == Mode.INSTANT) 15 else MAX_QUICKTAKE_S)) {
                stopQuickTake()
                return
            }
            main.postDelayed(this, 250)
        }
    }

    private fun startQuickTake() {
        quickTaking = true
        busy = true
        quickStart = System.currentTimeMillis()
        audio.keepUs = (MAX_QUICKTAKE_S + 5) * 1_000_000L
        recorder.enabled = true
        updateAudio()
        recorder.startLong()
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        shutter.setBackgroundResource(R.drawable.shutter_rec)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "0:00"
        main.post(quickTick)
    }

    private fun stopQuickTake() {
        if (!quickTaking) return
        quickTaking = false
        main.removeCallbacks(quickTick)
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        recTimer.visibility = View.GONE
        shutter.setBackgroundResource(R.drawable.shutter)
        showToast("Guardando video…")
        recorder.stopLong { clip ->
            ioExecutor.execute {
                var msg: String? = null
                try {
                    if (clip == null) msg = "No se pudo grabar el video"
                    else {
                        val mp4 = MotionPhotoWriter.muxToMp4(clip, cacheDir)
                        val instant = mode == Mode.INSTANT
                        val uri = Exporter.saveVideoBytes(this, mp4.bytes, if (instant) "_INSTANT" else "")
                        if (instant) {
                            val thumb = MediaRepo.thumbnail(this, uri, 1080)
                            runOnUiThread { showResult(thumb, uri, "video/mp4") }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "QuickTake", e)
                    msg = "Error: ${e.message}"
                }
                runOnUiThread {
                    audio.keepUs = 6_000_000
                    recorder.enabled = liveActive
                    updateAudio()
                }
                finishSave(msg)
            }
        }
    }

    // Ráfaga: fotos seguidas mientras mantienes el obturador deslizado
    private fun startBurst() {
        bursting = true
        busy = true
        burstCount = 0
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "0"
        burstShot()
    }

    private fun burstShot() {
        val ic = imageCapture ?: return stopBurst()
        if (!bursting) return
        val f = filter
        val file = File(cacheDir, "burst.jpg")
        sound.play(MediaActionSound.SHUTTER_CLICK)
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bytes = file.readBytes()
                    burstCount++
                    try {
                        MotionPhotoWriter.save(this@MainActivity, MotionPhotoWriter.applyFilter(bytes, f), null, "_BURST$burstCount")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Ráfaga", e)
                    }
                    runOnUiThread {
                        recTimer.text = burstCount.toString()
                        if (bursting && burstCount < 50) burstShot() else stopBurst()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { stopBurst() }
                }
            }
        )
    }

    private fun stopBurst() {
        if (!bursting && !busy) return
        bursting = false
        recTimer.visibility = View.GONE
        val n = burstCount
        ioExecutor.execute { finishSave(if (n > 0) "Ráfaga: $n fotos" else null) }
    }

    // ---------- Modo VIDEO (1080p con CameraX) ----------

    @SuppressLint("MissingPermission")
    private fun toggleVideo() {
        val vc = videoCapture ?: return
        recording?.let {
            it.stop()
            return
        }
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, MotionPhotoWriter.FOLDER)
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = vc.output.prepareRecording(this, options)
        if (has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    shutter.setBackgroundResource(R.drawable.shutter_stop)
                    recTimer.visibility = View.VISIBLE
                    recTimer.text = "0:00"
                }
                is VideoRecordEvent.Status -> {
                    val s = event.recordingStats.recordedDurationNanos / 1_000_000_000
                    recTimer.text = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    shutter.setBackgroundResource(R.drawable.shutter_rec)
                    recTimer.visibility = View.GONE
                    if (event.hasError()) showToast("Error grabando: ${event.error}")
                    refreshThumbnail()
                }
            }
        }
    }

    // ---------- Cámara ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = provider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val group = when (mode) {
            Mode.VIDEO -> buildVideoGroup()
            Mode.NIGHT -> buildPhotoGroup(night = true, analysis = false)
            Mode.PORTRAIT -> buildPhotoGroup(night = false, analysis = false)
            Mode.EFFECTS -> buildEffectsGroup()
            else -> buildPhotoGroup(night = false, analysis = true)
        }
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, group)
            observeZoom()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la cámara", e)
            showToast("Error de cámara: ${e.message}")
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun buildPhotoGroup(night: Boolean, analysis: Boolean): UseCaseGroup {
        videoCapture = null
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val previewB = Preview.Builder().setResolutionSelector(ratio43)
        val captureB = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(CameraSettings.photoSelector(this))
        if (night) {
            // Noche: dejar que la cámara use exposiciones más largas y reducción de ruido fuerte
            val range = nightFpsRange()
            for (ext in listOf(
                androidx.camera.camera2.interop.Camera2Interop.Extender(previewB),
                androidx.camera.camera2.interop.Camera2Interop.Extender(captureB),
            )) {
                range?.let { ext.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                ext.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
                    android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
            }
        }
        val preview = previewB.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = captureB.build()
        imageCapture = capture
        if (!analysis) {
            return UseCaseGroup.Builder()
                .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build())
                .addUseCase(preview)
                .addUseCase(capture)
                .build()
        }
        val analysisUc = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(CameraSettings.liveSize(this), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, recorder) }
        // Mismo encuadre 3:4 para vista previa, foto y video
        return UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build())
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(analysisUc)
            .build()
    }

    /** El rango de fps más bajo que soporte la cámara (más luz por cuadro). */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun nightFpsRange(): android.util.Range<Int>? = try {
        val info = provider?.availableCameraInfos?.firstOrNull {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(it)
                .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                (if (lensFacing == CameraSelector.LENS_FACING_BACK) android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                else android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT)
        }
        val ranges = info?.let {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(it)
                .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }
        ranges?.minWithOrNull(compareBy<android.util.Range<Int>>({ it.lower }, { it.upper }))
    } catch (_: Exception) {
        null
    }

    private fun buildEffectsGroup(): UseCaseGroup {
        videoCapture = null
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(CameraSettings.photoSelector(this))
            .build()
        imageCapture = capture
        val preview = Preview.Builder().setResolutionSelector(ratio43).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(CameraSettings.effectsSize(this), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(effectsExecutor, engine) }
        return UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build())
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(analysis)
            .build()
    }

    private val effectsExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private fun buildVideoGroup(): UseCaseGroup {
        imageCapture = null
        val ratio169 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio169).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val rec = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    CameraSettings.videoQuality(this),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
            )
            .build()
        val vc = VideoCapture.withOutput(rec)
        videoCapture = vc
        return UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0).build())
            .addUseCase(preview)
            .addUseCase(vc)
            .build()
    }

    private fun capture() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        filterBar.visibility = View.GONE

        // Sensación de cámara: sonido, vibración y destello
        sound.play(MediaActionSound.SHUTTER_CLICK)
        shutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(250).start()

        val live = liveActive
        val currentMode = mode
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        if (mode == Mode.PORTRAIT || mode == Mode.NIGHT) showToast(if (mode == Mode.NIGHT) "Quieto…" else "Procesando retrato…")
        val currentFilter = filter
        if (live) {
            livePill.visibility = View.VISIBLE
            livePill.alpha = 1f
        }

        val lock = Any()
        var photo: ByteArray? = null
        var clip: MotionRecorder.Clip? = null
        var remaining = if (live) 2 else 1

        fun finishOne() {
            synchronized(lock) {
                remaining--
                if (remaining > 0) return
            }
            ioExecutor.execute {
                val (p, c) = synchronized(lock) { photo to clip }
                when (currentMode) {
                    Mode.INSTANT -> saveInstant(p, currentFilter)
                    Mode.PORTRAIT -> saveProcessed(p, "_RETRATO") { Processing.portrait(it, currentFilter) }
                    Mode.EFFECTS -> saveEffectPhoto(p, front)
                    Mode.NIGHT -> saveProcessed(p, "_NOCHE") { Processing.night(it, currentFilter) }
                    else -> saveResult(p, c, live, currentFilter)
                }
            }
        }

        if (live) {
            recorder.requestClip(waitForMark = true) { result ->
                synchronized(lock) { clip = result }
                runOnUiThread { livePill.animate().alpha(0f).setDuration(200).start() }
                finishOne()
            }
        }

        val photoFile = File(cacheDir, "photo.jpg")
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).setMetadata(meta).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    // Instante real de la foto: el Live queda centrado aquí
                    if (live) recorder.markShutter(System.nanoTime() / 1000)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bytes = photoFile.readBytes()
                    photoFile.delete()
                    synchronized(lock) { photo = bytes }
                    finishOne()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Error tomando la foto", exception)
                    finishOne()
                }
            }
        )
    }

    private fun saveResult(photo: ByteArray?, clip: MotionRecorder.Clip?, live: Boolean, f: PhotoFilter) {
        var message: String? = null
        try {
            when {
                photo == null -> message = "No se pudo tomar la foto"
                clip == null -> {
                    MotionPhotoWriter.save(this, MotionPhotoWriter.applyFilter(photo, f), null)
                    if (live) message = "Se guardó sin movimiento"
                }
                else -> {
                    val mp4 = MotionPhotoWriter.muxToMp4(clip, cacheDir)
                    val xmp = MotionPhotoWriter.buildXmp(mp4.bytes.size, mp4.presentationUs, f)
                    val jpeg = MotionPhotoWriter.insertXmp(MotionPhotoWriter.applyFilter(photo, f), xmp)
                    MotionPhotoWriter.save(this, jpeg, mp4.bytes)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error guardando", e)
            message = "Error: ${e.message}"
        }
        finishSave(message)
    }

    private fun saveInstant(photo: ByteArray?, f: PhotoFilter) {
        if (photo == null) {
            finishSave("No se pudo tomar la foto")
            return
        }
        try {
            val bmp = Processing.captioned(photo, caption, instantLook)
            val uri = MotionPhotoWriter.save(this, Processing.encode(bmp, 93), null, "_INSTANT")
            runOnUiThread { showResult(bmp, uri, "image/jpeg") }
            finishSave(null)
        } catch (e: Throwable) {
            Log.e(TAG, "Error en instantánea", e)
            finishSave("Error: ${e.message}")
        }
    }

    private fun saveProcessed(photo: ByteArray?, suffix: String, process: (ByteArray) -> ByteArray) {
        if (photo == null) {
            finishSave("No se pudo tomar la foto")
            return
        }
        val msg = try {
            MotionPhotoWriter.save(this, process(photo), null, suffix)
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Proceso $suffix", e)
            // Si falla (ej. el modelo aún se descarga), guardar la foto normal
            try {
                MotionPhotoWriter.save(this, photo, null)
            } catch (_: Throwable) {
            }
            "Se guardó sin efecto: ${e.message}"
        }
        finishSave(msg)
    }

    // ---------- Instantánea tipo IG ----------

    private var caption = ""
    private var resultUri: android.net.Uri? = null
    private var resultMime = "image/jpeg"

    private fun editCaption() {
        val input = android.widget.EditText(this).apply {
            setText(caption)
            setSelection(caption.length)
            hint = "Escribe algo…"
            maxLines = 3
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Texto de la instantánea")
            .setView(input)
            .setPositiveButton("Listo") { _, _ ->
                caption = input.text.toString().trim()
                prefs.edit().putString(KEY_CAPTION, caption).apply()
                captionPill.text = caption.ifBlank { "Toca para escribir algo" }
            }
            .setNeutralButton("Borrar") { _, _ ->
                caption = ""
                prefs.edit().putString(KEY_CAPTION, "").apply()
                captionPill.text = "Toca para escribir algo"
            }
            .show()
    }

    private fun showResult(bmp: Bitmap?, uri: android.net.Uri, mime: String) {
        resultUri = uri
        resultMime = mime
        captionPill.visibility = View.GONE
        developOverlay.visibility = View.VISIBLE
        developOverlay.alpha = 0f
        developOverlay.animate().alpha(1f).setDuration(200).start()
        developImage.setImageBitmap(bmp)
        developImage.scaleX = 0.85f
        developImage.scaleY = 0.85f
        developImage.rotation = -2f
        developImage.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(350).start()
    }

    private fun sendResult() {
        val uri = resultUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = resultMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Enviar instantánea"))
        hideDevelop()
    }

    // ---------- Time-lapse ----------

    private var timelapsing = false
    private var timelapseStart = 0L
    private val timelapseTick = object : Runnable {
        override fun run() {
            if (!timelapsing) return
            val secs = ((System.currentTimeMillis() - timelapseStart) / 1000).toInt()
            recTimer.text = String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
            if (secs >= 300) {
                stopTimelapse(); return
            }
            main.postDelayed(this, 500)
        }
    }

    private fun startTimelapse() {
        if (busy) return
        timelapsing = true
        busy = true
        timelapseStart = System.currentTimeMillis()
        recorder.timelapseStep = 15
        recorder.enabled = true
        recorder.startLong()
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        shutter.setBackgroundResource(R.drawable.shutter_stop)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "0:00"
        main.post(timelapseTick)
    }

    private fun stopTimelapse() {
        if (!timelapsing) return
        timelapsing = false
        main.removeCallbacks(timelapseTick)
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        shutter.setBackgroundResource(R.drawable.shutter_rec)
        recTimer.visibility = View.GONE
        showToast("Guardando time-lapse…")
        recorder.stopLong { clip ->
            ioExecutor.execute {
                var msg: String? = null
                try {
                    if (clip == null || clip.samples.size < 10) msg = "Graba un poco más para el time-lapse"
                    else Exporter.saveVideoBytes(this, MotionPhotoWriter.muxToMp4(clip, cacheDir).bytes, "_TIMELAPSE")
                } catch (e: Throwable) {
                    msg = "Error: ${e.message}"
                }
                runOnUiThread {
                    recorder.timelapseStep = 0
                    recorder.enabled = liveActive
                }
                finishSave(msg)
            }
        }
    }

    // ---------- Lector QR (como iPhone: aparece solo en modo foto) ----------

    private var qrValue: String? = null
    private var qrBusy = false
    private val scanner by lazy {
        com.google.mlkit.vision.barcode.BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    private val qrScan = object : Runnable {
        override fun run() {
            main.postDelayed(this, 800)
            if (qrBusy || busy || (mode != Mode.PHOTO && mode != Mode.PORTRAIT && mode != Mode.NIGHT)) return
            val bmp = previewView.bitmap ?: return
            val small = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            qrBusy = true
            scanner.process(com.google.mlkit.vision.common.InputImage.fromBitmap(small, 0))
                .addOnSuccessListener { codes ->
                    val v = codes.firstOrNull()?.rawValue
                    if (v != null) {
                        if (v != qrValue) shutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        qrValue = v
                        val label = if (v.startsWith("http")) "Abrir " + android.net.Uri.parse(v).host else "QR: $v"
                        qrChip.text = label
                        qrChip.visibility = View.VISIBLE
                        qrChip.removeCallbacks(hideQr)
                        qrChip.postDelayed(hideQr, 2500)
                    }
                }
                .addOnCompleteListener { qrBusy = false }
        }
    }
    private val hideQr = Runnable { qrChip.visibility = View.GONE; qrValue = null }

    private fun openQr() {
        val v = qrValue ?: return
        if (v.startsWith("http://") || v.startsWith("https://")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(v)))
                return
            } catch (_: Exception) {
            }
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("QR", v))
        showToast("Texto del QR copiado")
    }

    private fun finishSave(message: String?) {
        val thumb = MediaRepo.list(this, onlyOwn = true).firstOrNull()
            ?.let { MediaRepo.thumbnail(this, it.uri, 200) }
        runOnUiThread {
            busy = false
            livePill.visibility = View.GONE
            message?.let { showToast(it) }
            if (thumb != null) {
                thumbnail.setImageBitmap(thumb)
                thumbnail.scaleX = 0.6f
                thumbnail.scaleY = 0.6f
                thumbnail.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    // ---------- Revelado tipo Polaroid ----------

    private var developAnim: ValueAnimator? = null

    private fun hideDevelop() {
        if (developOverlay.visibility != View.VISIBLE) return
        developAnim?.cancel()
        developImage.clearColorFilter()
        developOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            developOverlay.visibility = View.GONE
            developImage.setImageDrawable(null)
            if (mode == Mode.INSTANT) captionPill.visibility = View.VISIBLE
        }.start()
    }

    private fun refreshThumbnail() {
        ioExecutor.execute {
            val thumb = MediaRepo.list(this, onlyOwn = true).firstOrNull()
                ?.let { MediaRepo.thumbnail(this, it.uri, 200) }
            runOnUiThread {
                if (thumb != null) thumbnail.setImageBitmap(thumb) else thumbnail.setImageDrawable(null)
            }
        }
    }

    private fun showToast(text: String) {
        toast.text = text
        toast.animate().cancel()
        toast.alpha = 1f
        toast.animate().alpha(0f).setStartDelay(1500).setDuration(300).start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        audio.stop()
        sound.release()
        analysisExecutor.execute { recorder.release() }
        effectsExecutor.execute { engine.release() }
        effectsExecutor.shutdown()
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Camaratrix"
        private const val KEY_LIVE = "live_on"
        private const val KEY_FILTER = "filter"
        private const val KEY_MODE = "mode"
        private const val KEY_TIMER = "timer"
        private const val KEY_GRID = "grid"
        private const val KEY_CAPTION = "caption"
        private const val KEY_EFFECT = "effect"
        private const val KEY_LOOK = "instant_look"
        private const val MAX_QUICKTAKE_S = 60
    }
}
