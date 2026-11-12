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
import android.graphics.RectF
import android.media.MediaActionSound
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
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
        PORTRAIT("RETRATO"), NIGHT("NOCHE"), NIGHT_VISION("VISIÓN NOCTURNA"),
        SUNSET("ATARDECER"), MOON("LUNA"), INSTANT("INSTANTÁNEA")
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
    private lateinit var filterBar: LinearLayout
    private lateinit var filterList: LinearLayout
    private lateinit var developOverlay: View
    private lateinit var developImage: ImageView
    private lateinit var developHint: TextView
    private lateinit var root: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var topBar: View
    private lateinit var bottomPanel: View
    private lateinit var formatButton: TextView
    private lateinit var boomButton: ImageButton
    private lateinit var shapeMask: ShapeMaskView
    private lateinit var switchShot: ImageView
    private lateinit var instantTabs: LinearLayout

    // Formato de foto e instantánea
    private var format = "4:3"
    private var instantShape = InstantShape.SQUARE
    private var instantFrame = InstantFrame.NONE
    private var instantTab = 0
    private var boomKind = "photo" // photo, normal, slow, echo
    private lateinit var flashButton: ImageButton
    private lateinit var screenFlash: FlashView
    private lateinit var ringLight: FlashView
    private var flashShape = "full"
    private var flashMode = "off" // off, auto, on, torch

    // Enfoque y brillo al tocar (tipo iPhone)
    private lateinit var focusBox: FocusBoxView
    private lateinit var lockBadge: TextView
    private var evBias = 0          // pasos de brillo que eligió el usuario (se mantienen)
    private lateinit var evButton: TextView
    private lateinit var vividButton: ImageButton
    /** Viva (más nitidez y color): solo si el usuario lo prende; queda guardado. */
    private var vividOn = false
    private lateinit var lensButton: ImageButton
    /** Ojo de pez (Fisheye.OFF, WIDE o MIRROR): solo en Foto y se apaga al cerrar la app. */
    private var lensStyle = Fisheye.OFF
    /** La vista previa muestra el ojo de pez (el filtro de color va entonces después del lente). */
    private var lensShown = false
    private lateinit var evDial: ExposureDialView
    private var dialTouching = false
    private var aeLocked = false
    private var flashColor = 0xFFFFFFFF.toInt()

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Procesado pesado (mejora, instantáneas, boomerang) sin frenar la cámara
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val recorder = MotionRecorder()
    private val audio = AudioRingRecorder()
    private val sound = MediaActionSound()
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var videoStopping = false
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
        Registro.install(this)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        shutter = findViewById(R.id.shutter)
        flip = findViewById(R.id.flip)
        liveToggle = findViewById(R.id.liveToggle)
        filterButton = findViewById(R.id.filterButton)
        gridButton = findViewById(R.id.gridButton)
        timerButton = findViewById(R.id.timerButton)
        resButton = findViewById(R.id.resButton)
        resButton.setOnClickListener {
            closePanels()
            showResolutionMenu()
        }
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
        nightAuto = CameraSettings.get(this, CameraSettings.NIGHT_AUTO, "on") == "on"
        zslBroken = prefs.getBoolean(KEY_ZSL_BROKEN, false)
        buildBeautyPanel()
        beautyButton.setOnClickListener {
            val show = beautyPanel.visibility != View.VISIBLE
            if (show) closePanels(except = beautyPanel)
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
        developOverlay = findViewById(R.id.developOverlay)
        developImage = findViewById(R.id.developImage)
        developHint = findViewById(R.id.developHint)
        root = findViewById(R.id.root)
        previewBox = findViewById(R.id.previewBox)
        topBar = findViewById(R.id.topBar)
        bottomPanel = findViewById(R.id.bottomPanel)
        formatButton = findViewById(R.id.formatButton)
        boomButton = findViewById(R.id.boomButton)
        shapeMask = findViewById(R.id.shapeMask)
        switchShot = findViewById(R.id.switchShot)
        instantTabs = findViewById(R.id.instantTabs)
        format = CameraSettings.format(this)
        instantShape = InstantShape.fromId(prefs.getString(KEY_SHAPE, null))
        instantFrame = InstantFrame.fromId(prefs.getString(KEY_FRAME, null))
        boomKind = prefs.getString(KEY_BOOM, "photo") ?: "photo"
        previewBox.cameraDistance = 9000 * resources.displayMetrics.density
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) layoutPreview()
        }
        // El ojo de pez se calcula con el tamaño del visor: si cambia el formato, se rehace
        previewView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l != or - ol || b - t != ob - ot) && lensStyle != Fisheye.OFF) applyTone()
        }
        formatButton.setOnClickListener {
            if (busy || recording != null) return@setOnClickListener
            val all = CameraSettings.formats
            format = all[(all.indexOf(format) + 1) % all.size]
            CameraSettings.set(this, CameraSettings.FORMAT, format)
            tap(it)
            freezePreview()
            applyModeUi()
            bindCamera()
        }
        boomButton.setOnClickListener {
            closePanels()
            showBoomMenu()
        }
        flashButton = findViewById(R.id.flashButton)
        screenFlash = findViewById(R.id.screenFlash)
        ringLight = findViewById(R.id.ringLight)
        flashShape = prefs.getString(KEY_FLASH_SHAPE, "full") ?: "full"
        flashMode = prefs.getString(KEY_FLASH, "off") ?: "off"
        flashColor = prefs.getInt(KEY_FLASH_COLOR, 0xFFFFFFFF.toInt())
        flashButton.setOnClickListener {
            closePanels()
            showFlashMenu()
        }
        buildMoonPanel()
        buildFocusBox()
        evButton = findViewById(R.id.evButton)
        evDial = findViewById(R.id.evDial)
        evButton.setOnClickListener {
            tap(it)
            if (evDial.visibility == View.VISIBLE) hideDial() else {
                closePanels(except = evDial)
                showDial()
            }
        }
        evButton.setOnLongClickListener {
            setUserEv(0f)
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showToast("Brillo automático")
            true
        }
        evDial.onChange = { ev -> setUserEv(ev, fromDial = true) }
        vividButton = findViewById(R.id.vividButton)
        vividOn = CameraSettings.get(this, CameraSettings.VIVID, "off") == "on"
        vividButton.setOnClickListener {
            tap(it)
            closePanels()
            setVivid(!vividOn)
        }
        lensButton = findViewById(R.id.lensButton)
        lensButton.setOnClickListener {
            tap(it)
            closePanels()
            // Apagado -> gran angular -> espejo -> apagado
            setLens(when (lensStyle) {
                Fisheye.OFF -> Fisheye.WIDE
                Fisheye.WIDE -> Fisheye.MIRROR
                else -> Fisheye.OFF
            })
        }
        evDial.onTouchState = { touching ->
            dialTouching = touching
            main.removeCallbacks(hideDialLater)
            if (!touching) main.postDelayed(hideDialLater, 3000)
        }

        // TextureView: permite pintar la vista previa con el filtro en vivo
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING) {
                applyPreviewFilter()
                applyFlash() // la linterna solo prende con la cámara ya activa
                applyExposure()
                // El cambio de modo/cámara termina con un desvanecido suave (tipo iPhone)
                main.postDelayed({ unfreezePreview() }, 120)
            }
        }

        // La cámara no se apaga sola mientras la usas (time-lapse, luna, etc.)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
        liveOn = prefs.getBoolean(KEY_LIVE, true)
        filter = PhotoFilter.fromId(prefs.getString(KEY_FILTER, null))
        mode = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.PHOTO.name)!!) }.getOrDefault(Mode.PHOTO)
        timerSecs = prefs.getInt(KEY_TIMER, 0)
        gridOn = prefs.getBoolean(KEY_GRID, false)
        recorder.audio = audio
        recorder.peek = qrPeek
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
            if (hdrDeadline != null) return@setOnClickListener
            val wasHdrSession = hdrSession(mode)
            liveOn = !liveOn
            prefs.edit().putBoolean(KEY_LIVE, liveOn).apply()
            tap(it)
            applyModeUi()
            if (wasHdrSession != hdrSession(mode)) bindCamera()
            showToast(when {
                !liveOn -> "LIVE desactivado"
                lensStyle != Fisheye.OFF -> "LIVE activado · vuelve al apagar el ojo de pez"
                else -> "LIVE activado"
            })
        }
        filterButton.setOnClickListener {
            val show = filterBar.visibility != View.VISIBLE
            if (show) {
                closePanels(except = filterBar)
                filterBar.visibility = View.VISIBLE
                filterBar.alpha = 0f
                filterBar.translationY = dp(20).toFloat()
                filterBar.animate().alpha(1f).translationY(0f).setDuration(180).start()
                main.removeCallbacks(filterThumbTick)
                main.post(filterThumbTick)
            } else hideFilterBar()
            tap(it)
        }
        timerButton.setOnClickListener {
            cancelCountdown()
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
            cancelCountdown()
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            it.animate().rotationBy(180f).setDuration(300).start()
            engine.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
            clearFocusLock()
            lowLight = false
            autoEv = 0
            darkTicks = 0
            setDigitalGain(1f)
            // Giro 3D de la vista, como el iPhone al cambiar de cámara
            freezePreview()
            previewBox.animate().rotationY(90f).setDuration(150).withEndAction {
                previewBox.rotationY = -90f
                bindCamera()
                previewBox.animate().rotationY(0f).setDuration(220).start()
            }.start()
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
        main.removeCallbacks(balanceTick)
        main.postDelayed(balanceTick, 900)
        updatePerf()
    }

    // ---------- Botones de volumen = obturador (mantener = video, como el iPhone) ----------

    private var volLong = false
    private var volHeld = false
    private val volLongPress = Runnable {
        if ((mode == Mode.PHOTO || mode == Mode.INSTANT) && (!busy || preShot != null) && timerSecs == 0) {
            dropPreShot()
            volLong = true
            startQuickTake()
        }
    }

    private fun playSound(id: Int) {
        if (CameraSettings.get(this, CameraSettings.SOUND, "on") == "on") sound.play(id)
    }

    private fun isVolume(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolume(keyCode) && developOverlay.visibility != View.VISIBLE) {
            if (event.repeatCount == 0 && !volHeld) {
                volHeld = true
                volLong = false
                shutter.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                main.postDelayed(volLongPress, 450)
                startPreShot()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolume(keyCode) && volHeld) {
            volHeld = false
            main.removeCallbacks(volLongPress)
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            when {
                volLong -> if (quickTaking) stopQuickTake()
                commitPreShot() -> {}
                else -> onShutterTap()
            }
            volLong = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        dropPreShot()
        liveClipDeadline?.run()
        hdrDeadline?.run()
        hideFilterBar()
        main.removeCallbacks(volLongPress)
        volHeld = false
        clearFocusLock()
        main.removeCallbacks(qrScan)
        main.removeCallbacks(balanceTick)
        perf?.stop()
        if (timelapsing) stopTimelapse()
        if (quickTaking) stopQuickTake()
        if (effectRecording) stopEffectRec()
        bursting = false
        stopReveal()
        ringLight.visibility = View.GONE
        cancelCountdown()
        if (!videoStopping) recording?.stop()
        audio.stop()
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    /** El Live va en pausa con el ojo de pez (el video saldría derecho y la foto curva). */
    private val liveActive get() = liveOn && mode == Mode.PHOTO && lensStyle == Fisheye.OFF

    private val usesAnalysis get() = mode == Mode.PHOTO || mode == Mode.TIMELAPSE || mode == Mode.INSTANT

    // ---------- Modos, filtros, temporizador, cuadrícula ----------

    private fun setMode(m: Mode) {
        if (mode == m || busy || recording != null || quickTaking || timelapsing) return
        cancelCountdown()
        val old = mode
        val rebind = groupOf(old) != groupOf(m) || aspectOf(old) != aspectOf(m) ||
            hdrSession(old) != hdrSession(m) ||
            (m == Mode.EFFECTS && lensFacing != CameraSelector.LENS_FACING_FRONT)
        if (rebind) freezePreview()
        clearFocusLock()
        closePanels()
        mode = m
        if (m == Mode.EFFECTS) lensFacing = CameraSelector.LENS_FACING_FRONT
        prefs.edit().putString(KEY_MODE, m.name).apply()
        tap(modeList)
        applyModeUi()
        if (rebind) bindCamera()
    }

    // ---------- Formato del visor y transiciones ----------

    /** Proporción (alto / ancho, en vertical) de lo que se ve y se guarda en cada modo. */
    private fun aspectOf(m: Mode): String = when (m) {
        Mode.VIDEO -> "16:9"
        Mode.EFFECTS, Mode.TIMELAPSE -> "4:3"
        Mode.INSTANT -> "1:1"
        else -> format
    }

    private fun ratioOf(a: String): Float = when (a) {
        "16:9" -> 16f / 9f
        "1:1" -> 1f
        "full" -> {
            val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            h.toFloat() / w
        }
        else -> 4f / 3f
    }

    /** ViewPort de CameraX con la misma forma que el visor. */
    private fun viewPortRational(): Rational {
        val a = aspectOf(mode)
        if (a == "full") {
            val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            return Rational(w, h)
        }
        return when (a) { "16:9" -> Rational(9, 16); "1:1" -> Rational(1, 1); else -> Rational(3, 4) }
    }

    private val wideFormat get() = aspectOf(mode).let { it == "16:9" || it == "full" }

    /** Acomoda el visor: 4:3 bajo la barra, 1:1 centrado en esa zona, 16:9 y completa detrás de los controles. */
    private fun layoutPreview() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val a = aspectOf(mode)
        val ratio = ratioOf(a)
        var bw = w
        var bh = (w * ratio).toInt()
        if (bh > h) {
            bh = h
            bw = (h / ratio).toInt()
        }
        val barBottom = topBar.bottom
        val area43 = w * 4 / 3
        val top = when (a) {
            "full" -> 0
            "16:9" -> ((h - bh) / 2).coerceAtLeast(0)
            "1:1" -> barBottom + (area43 - bh) / 2
            else -> barBottom
        }.coerceIn(0, (h - bh).coerceAtLeast(0))
        val lp = previewBox.layoutParams as FrameLayout.LayoutParams
        if (lp.width != bw || lp.height != bh || lp.topMargin != top) {
            lp.width = bw
            lp.height = bh
            lp.topMargin = top
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            previewBox.layoutParams = lp
        }
        // Si el visor pasa por detrás de las barras, ponerles una sombra suave para leer los íconos
        topBar.setBackgroundResource(if (top < barBottom) R.drawable.bg_top_fade else 0)
        bottomPanel.setBackgroundResource(if (top + bh > bottomPanel.top) R.drawable.bg_bottom_fade else 0)
    }

    private var frozen = false

    /** Congela la última imagen, desenfocada, mientras la cámara se reconecta. */
    private fun freezePreview() {
        val src = (if (effectView.visibility == View.VISIBLE) effectView.bitmap else previewView.bitmap) ?: return
        val small = Bitmap.createScaledBitmap(src, maxOf(1, src.width / 10), maxOf(1, src.height / 10), true)
        switchShot.setImageBitmap(small)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            switchShot.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.CLAMP))
        }
        switchShot.animate().cancel()
        switchShot.alpha = 1f
        switchShot.visibility = View.VISIBLE
        frozen = true
        main.removeCallbacks(unfreezeLate)
        main.postDelayed(unfreezeLate, 1800)
    }

    private val unfreezeLate = Runnable { unfreezePreview() }

    private fun unfreezePreview() {
        if (!frozen) return
        frozen = false
        main.removeCallbacks(unfreezeLate)
        switchShot.animate().alpha(0f).setDuration(260).withEndAction {
            switchShot.visibility = View.GONE
            switchShot.setImageDrawable(null)
        }.start()
    }

    /** Qué combinación de cámara necesita cada modo (si cambia, hay que reconectar). */
    private fun groupOf(m: Mode) = when (m) {
        Mode.VIDEO -> 1
        Mode.NIGHT, Mode.NIGHT_VISION -> 2
        Mode.PORTRAIT -> 3
        Mode.EFFECTS -> 4
        Mode.SUNSET, Mode.MOON -> 5
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
        val lines = mutableListOf<View>()
        fun paintEnabled() {
            for (l in lines) {
                l.alpha = if (Beauty.enabled) 1f else 0.35f
                for (i in 0 until (l as ViewGroup).childCount) l.getChildAt(i).isEnabled = Beauty.enabled
            }
        }
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
        // Encender / apagar toda la belleza sin perder cómo estaban los ajustes
        header.addView(android.widget.Switch(this).apply {
            isChecked = Beauty.enabled
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFD0D0D0.toInt()))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(0xFF00E676.toInt(), 0x55FFFFFF))
            setPadding(dp(10), 0, 0, 0)
            setOnCheckedChangeListener { v, on ->
                Beauty.setEnabled(this@MainActivity, on)
                paintEnabled()
                updateBeautyIcon()
                engine.refresh()
                tap(v)
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
            lines += line
        }
        row("Suavizar piel", { Beauty.smooth }, { Beauty.smooth = it })
        row("Iluminar", { Beauty.bright }, { Beauty.bright = it })
        row("Afinar cara", { Beauty.slim }, { Beauty.slim = it })
        row("Agrandar ojos", { Beauty.eyes }, { Beauty.eyes = it })
        paintEnabled()
    }

    // ---------- Efectos tipo TikTok ----------

    fun buildEffects() {
        effects = EffectStore.all(this)
        effectList.removeAllViews()
        val size = dp(54)
        for (fx in effects) {
            val cell = FrameLayout(this).apply {
                tag = fx.id
                setBackgroundResource(R.drawable.bg_effect)
                setOnClickListener { selectEffect(fx.id, true) }
                if (fx.custom || !fx.base.startsWith("asset:")) {
                    setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        Sheet.actions(this@MainActivity, fx.name, listOf(
                            Sheet.Item("Quitar este efecto", R.drawable.ic_delete, destructive = true) {
                                EffectStore.forget(fx.base)
                                java.io.File(fx.base).deleteRecursively()
                                if (effectId == fx.id) effectId = "none"
                                buildEffects()
                            }
                        ), subtitle = if (fx.custom) "Creado por ti" else "De la biblioteca")
                        true
                    }
                }
            }
            val iconBmp = fx.icon?.let { EffectStore.image(this, fx.base, it) }
            if (iconBmp != null) {
                cell.addView(ImageView(this).apply {
                    setImageBitmap(iconBmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                }, FrameLayout.LayoutParams(size, size))
            } else {
                cell.addView(TextView(this).apply {
                    text = fx.emoji
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                }, FrameLayout.LayoutParams(size, size))
            }
            val lp = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
            effectList.addView(cell, lp)
        }
        val more = TextView(this).apply {
            text = "＋"
            textSize = 26f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF00E676.toInt())
            setBackgroundResource(R.drawable.bg_effect_more)
            setOnClickListener {
                libraryLauncher.launch(Intent(this@MainActivity, EffectsLibraryActivity::class.java))
            }
        }
        effectList.addView(more, LinearLayout.LayoutParams(size, size))
        selectEffect(effectId, false)
    }

    private val libraryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { buildEffects() }

    override fun onRestart() {
        super.onRestart()
        // Pudo crear o descargar efectos desde otra pantalla
        if (mode == Mode.EFFECTS) buildEffects()
    }

    private fun selectEffect(id: String, user: Boolean) {
        val fx = effects.firstOrNull { it.id == id } ?: effects.firstOrNull() ?: return
        effectId = fx.id
        prefs.edit().putString(KEY_EFFECT, fx.id).apply()
        engine.effect = fx
        applyPreviewFilter()
        for (i in 0 until effectList.childCount) {
            val v = effectList.getChildAt(i)
            v.isSelected = v.tag == fx.id
            val target = if (v.isSelected) 1.14f else 1f
            if (user) v.animate().scaleX(target).scaleY(target).setDuration(140).start()
            else { v.scaleX = target; v.scaleY = target }
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
        playSound(MediaActionSound.START_VIDEO_RECORDING)
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
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        recTimer.visibility = View.GONE
        shutter.setBackgroundResource(R.drawable.shutter)
        processingStart("Guardando video…")
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
        if (photo == null) {
            finishSave("No se pudo tomar la foto")
            return
        }
        if (fx == null) {
            finishSave(saveOriginalPhoto(photo))
            return
        }
        val msg = try {
            var bmp = Processing.decode(photo, 2400)
            // Los efectos siempre quedan como en la pantalla; si la foto no vino ya volteada, se voltea aquí
            if (front && !mirrorSelfies()) {
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
            saveOriginalPhoto(photo)
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
                setPadding(dp(14), dp(6), dp(14), dp(6))
                letterSpacing = 0.04f
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
            val on = v.tag == mode
            v.setTextColor(if (on) green else white)
            v.setBackgroundResource(if (on) R.drawable.bg_mode_selected else 0)
        }
        centerMode()
        updateResLabel()
        updateShutterDescription()
        layoutPreview()
        val photoLike = mode == Mode.PHOTO || mode == Mode.NIGHT || mode == Mode.PORTRAIT || mode == Mode.NIGHT_VISION ||
            mode == Mode.SUNSET || mode == Mode.MOON
        updateFlashIcon()
        updateEvUi()
        updateVividUi()
        updateRecorderLuts()
        moonPanel.visibility = if (mode == Mode.MOON) View.VISIBLE else View.GONE
        formatButton.visibility = if (photoLike) View.VISIBLE else View.GONE
        formatButton.text = CameraSettings.formatLabel(format)
        boomButton.visibility = if (mode == Mode.INSTANT) View.VISIBLE else View.GONE
        boomButton.setColorFilter(if (boomKind != "photo") 0xFF00E676.toInt() else 0xFFFFFFFF.toInt())
        shapeMask.shape = instantShape
        shapeMask.visibility = if (mode == Mode.INSTANT) View.VISIBLE else View.GONE
        captionPill.visibility = if (mode == Mode.INSTANT) View.VISIBLE else View.GONE
        val fxMode = mode == Mode.EFFECTS
        beautyButton.visibility = if (fxMode) View.VISIBLE else View.GONE
        if (!fxMode) beautyPanel.visibility = View.GONE
        if (mode == Mode.MOON) hideDial()
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
            Mode.SUNSET -> "Protege los colores del cielo y aclara la tierra"
            Mode.MOON -> "Apunta a la luna · apoya el cel · ajusta el brillo abajo"
            Mode.NIGHT_VISION -> "Para ver en lo oscuro · prende la linterna si no hay nada de luz"
            Mode.TIMELAPSE -> "Toca para empezar y para terminar"
            Mode.INSTANT -> when {
                instantFrame == InstantFrame.BOOTH -> "Fotomatón: toca y posa para 4 fotos"
                boomKind != "photo" -> "Toca para hacer un boomerang"
                else -> "Toca para foto · mantén para video"
            }
            Mode.EFFECTS -> "Elige un efecto · mantén para grabar"
            else -> null
        }
        modeHint.text = hint
        modeHint.visibility = if (hint != null) View.VISIBLE else View.GONE
        modeHint.alpha = 1f
        modeHint.animate().alpha(0f).setStartDelay(2500).setDuration(400).start()

        liveToggle.visibility = if (mode == Mode.PHOTO) View.VISIBLE else View.INVISIBLE
        liveToggle.setImageResource(if (liveOn) R.drawable.ic_live_on else R.drawable.ic_live_off)
        updateLensUi()
        val noFilter = mode == Mode.VIDEO || mode == Mode.TIMELAPSE || mode == Mode.EFFECTS || mode == Mode.NIGHT_VISION
        if (filterList.childCount > 0) {
            val wantInstant = mode == Mode.INSTANT
            if (isInstantKey(filterList.getChildAt(0).tag) != wantInstant) buildFilterChips()
        }
        instantTabs.visibility = if (mode == Mode.INSTANT) View.VISIBLE else View.GONE
        filterButton.visibility = if (noFilter) View.INVISIBLE else View.VISIBLE
        if (noFilter) hideFilterBar()
        shutter.setBackgroundResource(
            if (mode == Mode.VIDEO || mode == Mode.TIMELAPSE) R.drawable.shutter_rec else R.drawable.shutter
        )
        recorder.enabled = liveActive
        updateAudio()
        applyPreviewFilter()

        gridOverlay.areaHeight = 0
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
        dialog.setCanceledOnTouchOutside(true)
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

        // Lo de abajo se desliza: con todas las opciones ya no cabe en la pantalla
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body)
        }

        data class Seg(val id: String, val label: String, val note: String)
        // rebind = el cambio necesita volver a abrir la cámara (si no, se aplica sin cortar la vista previa)
        fun section(title: String, key: String, segs: List<Seg>, def: String = segs[segs.size / 2].id, rebind: Boolean = true) {
            body.addView(TextView(this).apply {
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
                        if (rebind) changed = true
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        paint(sg.id)
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f))
            }
            body.addView(bar, LinearLayout.LayoutParams(-1, -2))
            body.addView(note)
            paint(CameraSettings.get(this, key, def).let { cur ->
                if (segs.any { it.id == cur }) cur else segs.first().id
            })
        }

        section("Foto", CameraSettings.PHOTO, listOf(
            Seg("3", "3 MP", "Ocupa poco espacio"),
            Seg("8", "8 MP", "Equilibrada"),
            Seg("12", "12 MP", "Recomendada"),
            Seg("max", "MÁX", "Hasta 200 MP · tarda más y cada foto pesa mucho"),
        ))
        section("Selfies", CameraSettings.MIRROR, listOf(
            Seg("mirror", "Como te ves", "Igual que en la pantalla, como en un espejo"),
            Seg("true", "Como te ven", "Volteada: así te ven los demás"),
        ), def = "mirror", rebind = false)
        val zslOk = zslSupported()
        section("Disparo", CameraSettings.ZSL, listOf(
            Seg("on", "Sin retraso", if (zslOk) "La foto es del instante exacto en que apoyas el dedo"
            else "Se toma al apoyar el dedo (tu cel no guarda cuadros, puede haber un pelito de retraso)"),
            Seg("off", "Normal", "La cámara toma una foto nueva al pedirla (más lento)"),
        ), def = "on")
        section("Modo noche", CameraSettings.NIGHT_AUTO, listOf(
            Seg("on", "Automático", "De noche junta varias fotos y sube el brillo (sale la lunita)"),
            Seg("off", "Apagado", "Una sola foto, sin subir el brillo de más"),
        ), def = "on", rebind = false)
        section("HDR en Foto", CameraSettings.HDR, listOf(
            Seg("off", "Apagado", "Disparo normal"),
            Seg("on", "Encendido", "Combina tres exposiciones en FOTO con buena luz, LIVE y flash apagados"),
        ), def = "off", rebind = mode == Mode.PHOTO && !liveOn)
        section("Belleza", CameraSettings.BEAUTY, listOf(
            Seg("off", "Apagada", "Piel natural: sin alisar ni retocar la cara"),
            Seg("on", "Encendida", "Usa tus ajustes de belleza (en Efectos) y alisa un poco la piel"),
        ), def = "off", rebind = false)
        section("Sonido de la cámara", CameraSettings.SOUND, listOf(
            Seg("on", "Con sonido", "Clic al tomar la foto y pitido al grabar"),
            Seg("off", "En silencio", "Sin ningún sonido"),
        ), def = "on", rebind = false)
        section("Mejora automática", CameraSettings.ENHANCE, listOf(
            Seg("on", "Activada", "Luces y sombras parejas, caras más claras, menos ruido y menos halo en las luces"),
            Seg("off", "Apagada", "La foto tal cual sale del sensor"),
        ), def = "on", rebind = false)
        section("Viva", CameraSettings.VIVID, listOf(
            Seg("off", "Apagada", "La foto con la mejora normal"),
            Seg("on", "Prendida", "Más nitidez, contraste y color. También con el botón de destellos del visor"),
        ), def = "off", rebind = false)
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
        ), def = "720")
        section("Rendimiento", CameraSettings.PERF, listOf(
            Seg("off", "Oculto", "Sin panel"),
            Seg("on", "Mostrar", "Panel arriba a la derecha: fps, demora del disparo, tiempo de guardado y memoria"),
        ), def = "off", rebind = false)

        body.addView(TextView(this).apply {
            text = "Compartir registro"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_outline_pill)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_share_small, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            setPadding(dp(18), 0, dp(18), 0)
            contentDescription = "Compartir el registro de la app para revisar errores"
            setOnClickListener {
                isEnabled = false
                alpha = 0.5f
                text = "Preparando…"
                Registro.share(this@MainActivity, settingsSummary()) { ok ->
                    isEnabled = true
                    alpha = 1f
                    text = "Compartir registro"
                    if (!ok) showToast("No se pudo armar el registro")
                }
            }
        }, LinearLayout.LayoutParams(-2, dp(42)).apply {
            topMargin = dp(22)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        body.addView(TextView(this).apply {
            text = "Para revisar errores: lo que la app anotó (sin fotos) y tus ajustes"
            textSize = 11.5f
            gravity = android.view.Gravity.CENTER
            setTextColor(0x99FFFFFF.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        body.addView(TextView(this).apply {
            text = "Listo"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF000000.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_live_pill)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(24) })
        // Alto máximo: como una hoja que sube desde abajo, no toda la pantalla
        val maxBody = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        body.measure(
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels - dp(40), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.addView(scroll, LinearLayout.LayoutParams(-1, if (body.measuredHeight > maxBody) maxBody else -2))

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
            Beauty.load(this)
            val na = CameraSettings.get(this, CameraSettings.NIGHT_AUTO, "on") == "on"
            if (na != nightAuto) setNightAuto(na, announce = false)
            val vv = CameraSettings.get(this, CameraSettings.VIVID, "off") == "on"
            if (vv != vividOn) {
                vividOn = vv
                updateVividUi()
                updateRecorderLuts()
            }
            recorder.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT && mirrorSelfies()
            updateBeautyIcon()
            engine.refresh()
            if (beautyPanel.visibility == View.VISIBLE) buildBeautyPanel()
            updatePerf()
            if (changed) {
                updateResLabel()
                bindCamera()
            }
            // Mejora o Viva pudieron cambiar: la vista previa se pone igual de una vez
            applyTone()
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
    private fun isInstantKey(k: Any?) = k is InstantLook || k is InstantShape || k is InstantFrame

    /** Pestañas Filtro · Forma · Marco (solo en instantánea). */
    private fun buildInstantTabs() {
        instantTabs.removeAllViews()
        listOf("Filtro", "Forma", "Marco").forEachIndexed { i, label ->
            instantTabs.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val on = i == instantTab
                setBackgroundResource(if (on) R.drawable.bg_segment_on else 0)
                setTextColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setOnClickListener {
                    instantTab = i
                    tap(it)
                    buildFilterChips()
                    refreshFilterThumbs()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildFilterChips() {
        filterList.removeAllViews()
        val options: List<Pair<Any, String>> = if (mode == Mode.INSTANT) {
            buildInstantTabs()
            when (instantTab) {
                1 -> InstantShape.entries.map { it to it.label }
                2 -> InstantFrame.entries.map { it to it.label }
                else -> InstantLook.entries.map { it to it.label }
            }
        } else PhotoFilter.entries.map { it to it.label }
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
                        is InstantShape -> {
                            instantShape = key
                            shapeMask.shape = key
                            prefs.edit().putString(KEY_SHAPE, key.id).apply()
                        }
                        is InstantFrame -> {
                            instantFrame = key
                            prefs.edit().putString(KEY_FRAME, key.id).apply()
                            if (key == InstantFrame.BOOTH) showToast("Fotomatón: 4 fotos seguidas")
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
                // Cada filtro de color se ve sobre la misma imagen en vivo
                if (key is PhotoFilter) colorFilter = key.colorFilter()
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

    /**
     * Miniaturas de los filtros con lo que ve la cámara AHORA (se repinta varias veces por
     * segundo mientras la barra está abierta). Los filtros de color usan la misma imagen con su
     * filtro encima; los de instantánea se dibujan aparte, sin trabar la vista previa.
     */
    private fun refreshFilterThumbs() {
        if (thumbsBusy || filterList.childCount == 0) return
        // Lo mismo que se ve en pantalla (derecho, espejo en la frontal), en chiquito
        val frame = previewSnapshot(200) ?: return
        val side = minOf(frame.width, frame.height)
        val base = Bitmap.createBitmap(frame, (frame.width - side) / 2, (frame.height - side) / 2, side, side)
        if (base !== frame) frame.recycle()
        val keys = (0 until filterList.childCount).map { filterList.getChildAt(it).tag }
        fun imageAt(i: Int) = ((filterList.getChildAt(i) as LinearLayout).getChildAt(0) as FrameLayout).getChildAt(0) as ImageView
        if (keys.all { it is PhotoFilter }) {
            for (i in keys.indices) imageAt(i).setImageBitmap(base)
            return
        }
        thumbsBusy = true
        ioExecutor.execute {
            val thumbs = keys.map { key ->
                try {
                    when (key) {
                        is InstantLook -> key.apply(base)
                        is InstantShape -> InstantStyle.thumb(base, key, InstantFrame.NONE)
                        is InstantFrame -> InstantStyle.thumb(base, instantShape, key)
                        else -> base
                    }
                } catch (e: Throwable) {
                    base
                }
            }
            runOnUiThread {
                thumbsBusy = false
                if (filterBar.visibility != View.VISIBLE) return@runOnUiThread
                for (i in 0 until minOf(filterList.childCount, thumbs.size)) imageAt(i).setImageBitmap(thumbs[i])
            }
        }
    }

    private fun applyPreviewFilter() {
        val fx = applyTone()
        val cf = previewColorFilter(fx)
        findTexture(previewView)?.setLayerPaint(cf?.let { Paint().apply { colorFilter = it } })
        shapeMask.shape = instantShape
        for (i in 0 until filterList.childCount) {
            val item = filterList.getChildAt(i) as LinearLayout
            val on = when (val t = item.tag) {
                is InstantLook -> t == instantLook
                is InstantShape -> t == instantShape
                is InstantFrame -> t == instantFrame
                else -> t == filter
            }
            (item.getChildAt(0) as FrameLayout).getChildAt(1).visibility = if (on) View.VISIBLE else View.INVISIBLE
            (item.getChildAt(1) as TextView).setTextColor(if (on) 0xFF00E676.toInt() else 0xE6FFFFFF.toInt())
        }
        val active = if (mode == Mode.INSTANT) instantLook != InstantLook.NONE else filter != PhotoFilter.ORIGINAL
        filterButton.setColorFilter(if (active) 0xFF00E676.toInt() else 0xFFFFFFFF.toInt())
    }

    /** Filtro de color pintado en la textura del visor. Con ojo de pez va en la cadena, después del lente. */
    private fun previewColorFilter(fx: android.graphics.RenderEffect?): android.graphics.ColorFilter? = when {
        fx != null || lensShown -> null
        mode == Mode.NIGHT_VISION -> android.graphics.ColorMatrixColorFilter(InstantFx.nightVisionMatrix())
        mode == Mode.VIDEO || mode == Mode.TIMELAPSE -> null
        mode == Mode.EFFECTS -> engine.effect?.matrix()?.let { android.graphics.ColorMatrixColorFilter(it) }
        mode == Mode.INSTANT -> instantLook.colorFilter()
        else -> filter.colorFilter()
    }

    /**
     * Efecto de toda la vista previa: los filtros con shader (Android 13+) o el tono de la
     * mejora, y de noche el brillo digital (lo mismo que llevará la foto). Encima, el ojo de pez
     * y después el filtro de color (el mismo orden que la foto). Devuelve el filtro con shader.
     */
    private fun applyTone(): android.graphics.RenderEffect? {
        val fx = when {
            mode == Mode.NIGHT_VISION -> InstantFx.nightVisionPreview(previewView.width, previewView.height)
            mode == Mode.INSTANT && instantLook.fx -> InstantFx.preview(instantLook, previewView.width, previewView.height)
            else -> null
        }
        val photoLike = mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.INSTANT || mode == Mode.NIGHT ||
            mode == Mode.SUNSET
        val night = nightHelp() && gainModes()
        val auto = Enhance.enabled(this)
        val viv = vividAmount(night)
        // En instantánea con look de shader, el tono va debajo del look (igual que la foto:
        // primero la mejora y Viva, después el look)
        val toneOk = photoLike && (fx == null || mode == Mode.INSTANT) &&
            ((auto && mode != Mode.SUNSET) || night || viv > 0f)
        val tone = if (toneOk) {
            val g = if (night) digitalGain else 1f
            InstantFx.tonePreview(
                gain = g,
                // De noche casi no se levanta (la foto de noche tampoco) y, con mucho brillo,
                // se suaviza el grano en vez de marcarlo. Con la mejora apagada, solo Viva.
                lift = when {
                    night -> Brightness.previewLift(g)
                    auto && mode != Mode.SUNSET -> 1.1f
                    else -> 0f
                },
                sharp = when {
                    night -> 0.45f - 0.85f * ((g - 1.2f) / 1.8f).coerceIn(0f, 1f)
                    auto && mode != Mode.SUNSET -> 0.45f
                    else -> 0f
                },
                vivid = viv,
                wbR = if (night) previewWb.r else 1f,
                wbG = if (night) previewWb.g else 1f,
                wbB = if (night) previewWb.b else 1f,
            )
        } else null
        val lens = if (lensActive()) {
            InstantFx.lensPreview(lensStyle, previewView.width, previewView.height, deviceDegrees)
        } else null
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            var effect = if (fx != null && tone != null) android.graphics.RenderEffect.createChainEffect(fx, tone) else fx ?: tone
            if (lens != null) {
                val curved = if (effect != null) android.graphics.RenderEffect.createChainEffect(lens, effect) else lens
                val cf = filter.colorFilter()
                effect = if (cf != null) android.graphics.RenderEffect.createColorFilterEffect(cf, curved) else curved
            }
            previewView.setRenderEffect(effect)
        }
        if ((lens != null) != lensShown) {
            // El filtro de color pasa de la textura a la cadena (o vuelve)
            lensShown = lens != null
            findTexture(previewView)?.setLayerPaint(previewColorFilter(fx)?.let { Paint().apply { colorFilter = it } })
        }
        // El video del Live sigue a la vista previa (de noche Viva va más suave)
        updateRecorderLuts()
        updateNightBadge()
        return fx
    }

    /** Los ajustes del momento, en una línea, para el registro. */
    private fun settingsSummary(): String = buildString {
        append("modo=").append(mode.name)
        append(" cámara=").append(if (lensFacing == CameraSelector.LENS_FACING_FRONT) "frontal" else "trasera")
        append(" calidad=").append(resButton.text)
        append(" zsl=").append(if (zslAsked && zslSupported()) "sí" else "no")
        append(" noche-auto=").append(nightAuto).append(" poca-luz=").append(lowLight)
        append(" ganancia=").append(String.format(java.util.Locale.US, "%.2f", digitalGain))
        append(" mejora=").append(Enhance.enabled(this@MainActivity))
        append(" viva=").append(vividOn)
        append(" lente=").append(Lens.name(lensStyle))
        append(" hdr=").append(hdrEnabled())
        append(" belleza=").append(Beauty.enabled)
        append(" selfie=").append(if (mirrorSelfies()) "como-te-ves" else "como-te-ven")
        append(" live=").append(liveActive)
        append(" flash=").append(flashMode)
        append(" xiaomi=").append(vendorExt)
    }

    // ---------- Panel de rendimiento (Ajustes > Rendimiento; apagado por defecto) ----------

    @Volatile private var perf: PerfMonitor? = null
    private var perfView: TextView? = null

    private fun updatePerf() {
        val on = CameraSettings.get(this, CameraSettings.PERF, "off") == "on"
        if (!on) {
            perf?.stop()
            perf = null
            return
        }
        if (perf == null) {
            val holder = evButton.parent as? FrameLayout ?: return
            val tv = perfView ?: TextView(this).apply {
                textSize = 10.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(0xFFFFFFFF.toInt())
                setLineSpacing(0f, 1.1f)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x99000000.toInt())
                }
                visibility = View.GONE
                holder.addView(this, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    topMargin = dp(12)
                    marginEnd = dp(12)
                })
                perfView = this
            }
            perf = PerfMonitor(tv).apply {
                analyzed = { recorder.analyzedFrames }
                info = {
                    "ZSL " + (if (zslAsked && zslSupported()) "sí" else "no") +
                        " · Live " + (if (liveActive) "sí" else "no") +
                        " · Viva " + (if (vividActive()) "sí" else "no") +
                        "\nProcesado Xiaomi: " + vendorExt
                }
            }
        }
        perf?.start()
    }

    // ---------- Viva: más nitidez y color (botón abajo a la derecha; apagado por defecto) ----------

    /** Modos donde Viva tiene efecto: los de foto (no video, efectos, visión nocturna, luna ni boomerang). */
    private fun vividModes() = mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.NIGHT ||
        mode == Mode.SUNSET || (mode == Mode.INSTANT && boomKind == "photo")

    private fun vividActive() = vividOn && vividModes()

    /** Cuánto Viva lleva la vista previa y el video del Live: de noche menos (no subir el grano). */
    private fun vividAmount(night: Boolean = nightHelp() && gainModes()) =
        if (!vividActive()) 0f else if (night || mode == Mode.NIGHT) 0.45f else 1f

    private fun setVivid(on: Boolean) {
        vividOn = on
        CameraSettings.set(this, CameraSettings.VIVID, if (on) "on" else "off")
        updateVividUi()
        applyTone()
        updateRecorderLuts()
        showToast(if (on) "Viva: más nitidez y color" else "Viva apagada")
    }

    /** Ojo de pez a la vista ahora: en Foto, y no en ráfaga ni QuickTake (esas van derechas). */
    private fun lensActive() = lensStyle != Fisheye.OFF && mode == Mode.PHOTO && !quickTaking && !bursting &&
        InstantFx.lensSupported()

    /** El estilo con el que sale una foto tomada ahora. */
    private fun lensNow() = if (lensActive()) lensStyle else Fisheye.OFF

    private fun setLens(style: Int) {
        val liveBefore = liveActive
        lensStyle = style
        updateLensUi()
        recorder.enabled = liveActive
        updateAudio()
        applyPreviewFilter()
        Log.i(TAG, "Ojo de pez: ${Lens.name(style)}")
        val live = when {
            liveBefore && !liveActive -> " · LIVE en pausa"
            !liveBefore && liveActive -> " · LIVE de vuelta"
            else -> ""
        }
        showToast(when (style) {
            Fisheye.WIDE -> "Gran angular (efecto)"
            Fisheye.MIRROR -> "Espejo de tienda"
            else -> "Ojo de pez apagado"
        } + live)
    }

    private fun updateLensUi() {
        if (!::lensButton.isInitialized) return
        val on = lensStyle != Fisheye.OFF
        lensButton.visibility = if (mode == Mode.PHOTO && InstantFx.lensSupported()) View.VISIBLE else View.GONE
        lensButton.isActivated = on
        lensButton.setImageResource(if (lensStyle == Fisheye.MIRROR) R.drawable.ic_mirror else R.drawable.ic_fisheye)
        lensButton.setColorFilter(if (on) 0xFFFFD60A.toInt() else 0xFFFFFFFF.toInt())
        lensButton.contentDescription = when (lensStyle) {
            Fisheye.WIDE -> "Gran angular prendido. Toca para espejo de tienda"
            Fisheye.MIRROR -> "Espejo de tienda prendido. Toca para apagar"
            else -> "Ojo de pez apagado. Toca para gran angular"
        }
        // LIVE en pausa mientras haya ojo de pez: el botón queda tenue
        liveToggle.alpha = if (liveOn && on && mode == Mode.PHOTO) 0.45f else 1f
    }

    /** Destello amarillo con aro cuando está prendido, blanco cuando no (como el ±). */
    private fun updateVividUi() {
        if (!::vividButton.isInitialized) return
        vividButton.isActivated = vividOn
        vividButton.setColorFilter(if (vividOn) 0xFFFFD60A.toInt() else 0xFFFFFFFF.toInt())
        vividButton.contentDescription = if (vividOn) "Viva prendida: toca para apagarla" else "Viva: más nitidez y color"
        vividButton.visibility = if (vividModes()) View.VISIBLE else View.GONE
    }

    /** El video del Live y QuickTake lleva lo mismo que se ve: el brillo de noche y Viva. */
    private fun updateRecorderLuts() {
        if (!::vividButton.isInitialized) return
        val g = digitalGain
        val v = vividAmount()
        recorder.lumaLut = if (g > 1f || v > 0f) Brightness.lumaLut(g, if (g > 1f) Brightness.previewLift(g) else 0f, v) else null
        recorder.chromaLut = if (g > 1f || v > 0f) Brightness.chromaLut(g, v) else null
    }

    /**
     * La vista previa tal como se ve en pantalla, en chiquito y barato: la GPU entrega el cuadro
     * reducido y aquí se le aplican las mismas transformaciones que usa la vista (giro, espejo,
     * recorte). Así no se pide una imagen de pantalla completa cada vez (eso trababa).
     */
    private fun previewSnapshot(target: Int): Bitmap? {
        val tv = findTexture(previewView) ?: return null
        val pw = previewView.width
        val ph = previewView.height
        if (tv.width <= 0 || tv.height <= 0 || pw <= 0 || ph <= 0) return null
        val k = maxOf(pw, ph).toFloat() / target
        val sw = (tv.width / k).toInt().coerceAtLeast(1)
        val sh = (tv.height / k).toInt().coerceAtLeast(1)
        val src = try { tv.getBitmap(sw, sh) } catch (e: Exception) { null } ?: return null
        val m = android.graphics.Matrix()
        m.setScale(tv.width.toFloat() / sw, tv.height.toFloat() / sh)
        m.postConcat(tv.getTransform(null))
        m.postConcat(tv.matrix)
        m.postTranslate(tv.left.toFloat(), tv.top.toFloat())
        m.postScale(1f / k, 1f / k)
        val out = Bitmap.createBitmap((pw / k).toInt().coerceAtLeast(1), (ph / k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
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
        var longDone = false
        var adjusting = false
        var startBias = 0
        var lastX = 0f
        var lastY = 0f
        val longPress = Runnable {
            if (!multi && !scale.isInProgress) {
                longDone = true
                lockAt(lastX, lastY)
            }
        }
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                    multi = false; longDone = false; adjusting = false
                    startBias = evBias
                    main.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    main.removeCallbacks(longPress)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = ev.x; lastY = ev.y
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > dp(10) || abs(dy) > dp(10)) main.removeCallbacks(longPress)
                    // Con el cuadro a la vista, arrastrar hacia arriba/abajo cambia el brillo
                    if (!multi && !longDone && focusBox.visibility == View.VISIBLE &&
                        (adjusting || (abs(dy) > dp(8) && abs(dy) > abs(dx) * 1.3f))
                    ) {
                        adjusting = true
                        dragExposure(startBias, dy)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    when {
                        ev.actionMasked == MotionEvent.ACTION_CANCEL -> {}
                        adjusting -> endExposureDrag()
                        longDone || multi || scale.isInProgress -> {}
                        abs(dx) > dp(70) && abs(dx) > abs(dy) * 1.5f -> swipeMode(if (dx < 0) 1 else -1)
                        ev.eventTime - ev.downTime < 400 && abs(dx) < dp(10) && abs(dy) < dp(10) ->
                            if (!closePanels()) focusAt(ev.x, ev.y)
                    }
                }
            }
            true
        }
        zoom1.setOnClickListener { animateZoom(1f); tap(it) }
        zoom2.setOnClickListener { animateZoom(2f); tap(it) }
    }

    private var zoomAnim: ValueAnimator? = null

    /** Zoom suave entre 1x y 2x, como deslizar en el iPhone. */
    private fun animateZoom(target: Float) {
        val cam = camera ?: return
        val from = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        zoomAnim?.cancel()
        zoomAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { cam.cameraControl.setZoomRatio(it.animatedValue as Float) }
            start()
        }
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

    // ---------- Enfoque y brillo al tocar ----------

    private fun buildFocusBox() {
        focusBox = FocusBoxView(this).apply { visibility = View.GONE; alpha = 0f }
        previewBox.addView(focusBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lockBadge = TextView(this).apply {
            text = "BLOQUEO AE/AF"
            textSize = 12f
            letterSpacing = 0.06f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF000000.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFFFFD60A.toInt())
            }
            visibility = View.GONE
        }
        previewBox.addView(lockBadge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })
    }

    private val dimFocus = Runnable {
        focusBox.dragging = false
        focusBox.animate().alpha(if (aeLocked) 0.85f else 0.5f).setDuration(300).start()
    }

    private val hideFocus = Runnable {
        if (aeLocked) return@Runnable
        focusBox.animate().alpha(0f).setDuration(300).withEndAction { focusBox.visibility = View.GONE }.start()
    }

    /** Pone el cuadro donde se tocó, con el "rebote" de entrada del iPhone. */
    private fun showFocusBox(x: Float, y: Float) {
        focusBox.measure(0, 0)
        val w = focusBox.measuredWidth
        val h = focusBox.measuredHeight
        // Si el toque está muy a la derecha, el sol iría fuera: se corre el cuadro un poco
        val bx = x - focusBox.boxCenterX
        focusBox.x = bx.coerceIn(-focusBox.boxCenterX / 2f, previewBox.width - w.toFloat())
        focusBox.y = y - h / 2f
        focusBox.level = evLevel()
        focusBox.dragging = false
        focusBox.animate().cancel()
        focusBox.visibility = View.VISIBLE
        focusBox.alpha = 1f
        focusBox.scaleX = 1.3f
        focusBox.scaleY = 1.3f
        focusBox.pivotX = focusBox.boxCenterX
        focusBox.pivotY = h / 2f
        focusBox.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        main.removeCallbacks(dimFocus)
        main.removeCallbacks(hideFocus)
        main.postDelayed(dimFocus, 1800)
        main.postDelayed(hideFocus, 5000)
    }

    /** Punto del sensor para enfocar donde se tocó (con ojo de pez, lo que se ve ahí viene de otro lado). */
    private fun meteringPoint(x: Float, y: Float): androidx.camera.core.MeteringPoint {
        val (sx, sy) = (if (lensShown) Fisheye.sourcePoint(lensStyle, x, y, previewView.width, previewView.height) else null)
            ?: (x to y) // fuera del espejo el fondo está en su sitio
        return previewView.meteringPointFactory.createPoint(sx, sy)
    }

    /** Toque: enfoca y mide la luz en ese punto (y el brillo vuelve a lo normal). */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        if (mode == Mode.MOON) {
            showToast("En Luna el enfoque va fijo al infinito · usa la barra de brillo")
            return
        }
        val wasLocked = aeLocked
        aeLocked = false
        lockBadge.visibility = View.GONE
        autoEv = 0
        lastTapAt = android.os.SystemClock.uptimeMillis()
        applyExposure()
        if (wasLocked) applySmoothFps() // quita el bloqueo de exposición
        val point = meteringPoint(x, y)
        try {
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
                ).setAutoCancelDuration(6, java.util.concurrent.TimeUnit.SECONDS).build()
            )
        } catch (_: Exception) {
        }
        if (wasLocked) showToast("Bloqueo quitado")
        showFocusBox(x, y)
    }

    /** Mantener presionado: bloquea enfoque y brillo hasta que vuelvas a tocar. */
    private fun lockAt(x: Float, y: Float) {
        val cam = camera ?: return
        if (mode == Mode.MOON) return
        autoEv = 0
        lastTapAt = android.os.SystemClock.uptimeMillis()
        applyExposure()
        val point = meteringPoint(x, y)
        aeLocked = true
        try {
            val future = cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
                ).disableAutoCancel().build()
            )
            // Cuando la cámara ya midió ese punto, se congela la exposición (bloqueo AE de verdad)
            future.addListener({
                main.postDelayed({ if (aeLocked) applySmoothFps() }, 350)
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
        }
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showFocusBox(x, y)
        // Pulso doble del cuadro y el aviso amarillo arriba
        focusBox.animate().scaleX(0.9f).scaleY(0.9f).setDuration(120).withEndAction {
            focusBox.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
        lockBadge.alpha = 0f
        lockBadge.visibility = View.VISIBLE
        lockBadge.animate().alpha(1f).setDuration(200).start()
        main.removeCallbacks(hideFocus)
    }

    private fun clearFocusLock() {
        val wasLocked = aeLocked
        aeLocked = false
        if (wasLocked) applySmoothFps()
        main.removeCallbacks(dimFocus)
        main.removeCallbacks(hideFocus)
        if (::focusBox.isInitialized) {
            focusBox.animate().cancel()
            focusBox.visibility = View.GONE
            lockBadge.visibility = View.GONE
        }
    }

    // ---------- Brillo del usuario: botón ±, regla y el sol del cuadro ----------

    private val hideDialLater = Runnable { if (!dialTouching) hideDial() }

    private fun evStepSize(): Float =
        camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat()?.takeIf { it > 0f } ?: (1f / 3f)

    private fun showDial() {
        evDial.step = evStepSize()
        evDial.setValue(evBias * evStepSize())
        evDial.alpha = 0f
        evDial.translationY = dp(10).toFloat()
        evDial.visibility = View.VISIBLE
        evDial.animate().alpha(1f).translationY(0f).setDuration(180).start()
        main.removeCallbacks(hideDialLater)
        main.postDelayed(hideDialLater, 3500)
    }

    private fun hideDial() {
        main.removeCallbacks(hideDialLater)
        if (evDial.visibility != View.VISIBLE) return
        evDial.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(160)
            .withEndAction { evDial.visibility = View.GONE }.start()
    }

    /** Cambia el brillo elegido por el usuario (en EV). Queda puesto hasta que lo cambie. */
    private fun setUserEv(ev: Float, fromDial: Boolean = false) {
        val n = evSteps()
        val step = evStepSize()
        evBias = if (n == 0) 0 else kotlin.math.round(ev / step).toInt().coerceIn(-n, n)
        applyExposure()
        refreshGainNow()
        updateEvUi(fromDial)
    }

    /** Botón ± amarillo con el valor cuando hay brillo puesto, como en el iPhone. */
    private fun updateEvUi(fromDial: Boolean = false) {
        if (!::evButton.isInitialized) return
        val ev = evBias * evStepSize()
        evButton.isActivated = evBias != 0
        if (evBias == 0) {
            evButton.text = "±"
            evButton.textSize = 18f
            evButton.setTextColor(0xFFFFFFFF.toInt())
        } else {
            evButton.text = String.format(Locale.US, "%+.1f", ev)
            evButton.textSize = 12f
            evButton.setTextColor(0xFFFFD60A.toInt())
        }
        if (!fromDial && evDial.visibility == View.VISIBLE) evDial.setValue(ev)
        if (focusBox.visibility == View.VISIBLE) focusBox.level = evLevel()
        evButton.visibility = if (mode == Mode.MOON) View.GONE else View.VISIBLE
        if (mode == Mode.MOON) hideDial()
    }

    // ---------- Exposición equilibrada: que no se queme lo claro ni quede negro lo oscuro ----------

    private var autoEv = 0       // pasos que pone la app sola, encima de la cámara
    private var lastTapAt = 0L

    private val balanceTick = object : Runnable {
        override fun run() {
            try {
                balanceExposure()
            } catch (e: Exception) {
                Log.w(TAG, "Equilibrio", e)
            }
            main.postDelayed(this, 500)
        }
    }

    private fun balanceModes() = mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.INSTANT ||
        mode == Mode.EFFECTS || mode == Mode.VIDEO

    /** Modos donde la vista previa lleva el brillo digital de noche (los mismos donde la foto lo lleva). */
    private fun gainModes() = mode == Mode.PHOTO || mode == Mode.PORTRAIT ||
        (mode == Mode.INSTANT && !instantLook.fx) || (mode == Mode.NIGHT && Enhance.enabled(this))

    /** Si la ayuda de noche está activa: brillo digital y varias fotos juntas (o el modo Noche). */
    private fun nightHelp() = (lowLight && nightAuto) || mode == Mode.NIGHT

    /** La imagen real de la vista previa, chiquitica (sin filtros ni brillo digital). */
    private fun sample(w: Int, h: Int): IntArray? {
        val tv = findTexture(previewView) ?: return null
        val bmp = try { tv.getBitmap(w, h) } catch (e: Exception) { null } ?: return null
        if (bmp.width != w || bmp.height != h) {
            bmp.recycle()
            return null
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return px
    }

    /**
     * Mira la imagen real (pequeñita) dos veces por segundo:
     *  - de noche, si la cámara ya no da más, sube el brillo por su cuenta (vista previa, foto y Live);
     *  - si hay zonas grandes quemadas, baja la exposición (lo que hace el iPhone);
     *  - si todo quedó muy oscuro y hay margen, la sube;
     *  - si no, vuelve despacio a lo que decide la cámara.
     */
    private fun balanceExposure() {
        val cam = camera ?: return
        val balance = balanceModes()
        val gainOn = gainModes()
        if (!balance && autoEv != 0) {
            autoEv = 0
            applyExposure()
        }
        if (!gainOn) setDigitalGain(1f)
        if (!balance && !gainOn) return
        if (busy || frozen || screenFlash.visibility == View.VISIBLE) return
        val st = cam.cameraInfo.exposureState
        val canEv = st.isExposureCompensationSupported && st.exposureCompensationStep.toFloat() > 0f
        val step = if (canEv) st.exposureCompensationStep.toFloat() else 1f / 3f
        val w = 72
        val h = 96
        val px = sample(w, h) ?: return
        val userEv = evBias * step

        // 1) Brillo de noche (no depende de tocar la pantalla: con el ± a tope tiene que verse)
        val key = Brightness.key(px, w, h)
        watchDarkness(key, userEv)
        // Con bloqueo AE/AF el brillo se queda quieto (solo lo mueve el ±)
        if (gainOn && !aeLocked) updateDigitalGain(key, userEv, immediate = false)
        updatePreviewWb(if (gainOn && nightHelp()) Brightness.nightWb(px) else null)

        // 2) Equilibrio de la cámara
        if (!balance || !canEv) return
        // Después de tocar la pantalla manda el usuario por un rato
        if (aeLocked || focusBox.visibility == View.VISIBLE ||
            android.os.SystemClock.uptimeMillis() - lastTapAt < 7000) return
        // Lo que el usuario subió o bajó a propósito no cuenta: se mide la imagen
        // "sin su ajuste" (en gamma, 1 EV ≈ ×2^(1/2.2)), así el equilibrio no lo deshace
        val k = Math.pow(2.0, (-userEv / 2.2f).toDouble()).toFloat()
        var clipped = 0
        val hist = IntArray(256)
        for (c in px) {
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val mx = maxOf(r, maxOf(g, b))
            if (mx * k >= 245f) clipped++
            hist[(((r * 77 + g * 150 + b * 29) shr 8) * k).toInt().coerceIn(0, 255)]++
        }
        val n = px.size
        val clip = clipped.toFloat() / n
        fun pct(q: Float): Float {
            var acc = 0
            val target = (n * q).toInt()
            for (v in 0 until 256) {
                acc += hist[v]
                if (acc >= target) return v / 255f
            }
            return 1f
        }
        val median = maxOf(pct(0.5f), 0.008f)
        val p98 = pct(0.98f)
        val moving = recording != null || quickTaking
        val evDelta = when {
            // Quemado: bajar seguro (más rápido cuanto más quemado)
            clip > 0.03f -> -(when {
                clip > 0.15f -> 0.67f
                clip > 0.06f -> 0.5f
                else -> 0.34f
            })
            // Muy oscuro y con margen arriba: subir (la imagen está en gamma: ~2.2 por paso)
            median < 0.11f && clip < 0.012f -> (2.2f * log2(0.15f / median) * 0.5f).coerceIn(0.17f, 1f)
            // Nada raro: volver poco a poco a lo que decide la cámara
            autoEv < 0 && clip < 0.008f && p98 < 0.86f -> step
            autoEv > 0 && median > 0.28f -> -step
            else -> 0f
        }
        if (evDelta == 0f) return
        var delta = kotlin.math.round(evDelta / step).toInt()
        if (delta == 0) delta = if (evDelta > 0) 1 else -1
        if (moving) delta = delta.coerceIn(-1, 1) // grabando: sin saltos bruscos
        val maxDown = kotlin.math.round(2.5f / step).toInt()
        val maxUp = kotlin.math.round((if (lowLight) 0.5f else 1.5f) / step).toInt()
        val next = (autoEv + delta).coerceIn(-maxDown, maxUp)
        if (next != autoEv) {
            autoEv = next
            applyExposure()
        }
    }

    // ---------- Noche: brillo digital cuando la cámara ya no da más ----------

    /** Cuánto se sube la imagen por software (×, en gamma). 1 = nada. */
    private var digitalGain = 1f
    private var darkTicks = 0
    private var nightBadge: TextView? = null

    /** Modo noche automático (se prende y apaga tocando la lunita o en Ajustes). */
    private var nightAuto = true

    private fun setNightAuto(on: Boolean, announce: Boolean = true) {
        nightAuto = on
        CameraSettings.set(this, CameraSettings.NIGHT_AUTO, if (on) "on" else "off")
        if (on) refreshGainNow() else setDigitalGain(1f)
        applyTone()
        if (announce) showToast(if (on) "Modo noche activado" else "Modo noche apagado")
    }

    /**
     * Por si la cámara no avisa bien (la frontal a veces se queda corta de ISO o de tiempo):
     * imagen muy oscura con la cámara ya exponiendo largo = es de noche.
     */
    private fun watchDarkness(key: Float, userEv: Float) {
        if (lowLight || !(mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.INSTANT)) {
            darkTicks = 0
            return
        }
        // Lo que el usuario bajó a propósito no cuenta como "oscuro"
        val plain = key / Brightness.evFactor(userEv)
        val longExposure = lastExpNs >= normalCapNs / 2 || lastIso >= darkIso / 2
        darkTicks = if (plain < 0.10f && longExposure) darkTicks + 1 else 0
        if (darkTicks >= 3) {
            darkTicks = 0
            lowLight = true
            applySmoothFps()
            applyPreviewFilter()
        }
    }

    /** Color de noche de la vista previa (el mismo arreglo que lleva la foto), suavizado. */
    private var previewWb = Brightness.NightWb(1f, 1f, 1f)

    private fun updatePreviewWb(target: Brightness.NightWb?) {
        val t = target ?: Brightness.NightWb(1f, 1f, 1f)
        var r = previewWb.r + (t.r - previewWb.r) * 0.5f
        var g = previewWb.g + (t.g - previewWb.g) * 0.5f
        var b = previewWb.b + (t.b - previewWb.b) * 0.5f
        // Al salir de la noche, ya cerca de neutro, queda exacto
        if (target == null && kotlin.math.abs(r - 1f) < 0.01f && kotlin.math.abs(g - 1f) < 0.01f &&
            kotlin.math.abs(b - 1f) < 0.01f
        ) {
            r = 1f; g = 1f; b = 1f
        }
        val next = Brightness.NightWb(r, g, b)
        // Cambios chiquitos no se aplican (sin parpadeo por el grano)
        val moved = kotlin.math.abs(r - previewWb.r) > 0.008f || kotlin.math.abs(g - previewWb.g) > 0.008f ||
            kotlin.math.abs(b - previewWb.b) > 0.008f || next.neutral != previewWb.neutral
        if (!moved) return
        previewWb = next
        applyTone()
    }

    private fun updateDigitalGain(key: Float, userEv: Float, immediate: Boolean) {
        val target = if (nightHelp()) Brightness.gain(key, Brightness.aim(userEv), Brightness.maxGain(userEv)) else 1f
        setDigitalGain(if (immediate) target else digitalGain + (target - digitalGain) * 0.5f)
    }

    private fun setDigitalGain(g: Float) {
        val v = if (g < 1.04f) 1f else g
        if (v == digitalGain) return
        // Cambios chiquitos no se aplican (sin parpadeo por el grano de la imagen)
        if (v != 1f && digitalGain != 1f && kotlin.math.abs(v - digitalGain) < 0.04f * digitalGain) return
        digitalGain = v
        updateRecorderLuts()
        applyTone()
    }

    /** El usuario movió el brillo: la vista previa responde ya, sin esperar el siguiente vistazo. */
    private fun refreshGainNow() {
        if (!gainModes() || !nightHelp() || busy || frozen) return
        val px = sample(48, 64) ?: return
        updateDigitalGain(Brightness.key(px, 48, 64), evBias * evStepSize(), immediate = true)
    }

    /** Lunita amarilla arriba, como el modo noche del iPhone: avisa que la cámara está ayudando. */
    private fun updateNightBadge() {
        val show = lowLight && gainModes() && mode != Mode.NIGHT
        val badge = nightBadge ?: run {
            if (!show || !::evButton.isInitialized) return
            val holder = evButton.parent as? FrameLayout ?: return
            TextView(this).apply {
                text = "Noche"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFD60A.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(5), dp(11), dp(5))
                compoundDrawablePadding = dp(5)
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_night, 0, 0, 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0x8C000000.toInt())
                }
                alpha = 0f
                visibility = View.GONE
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    setNightAuto(!nightAuto)
                }
                holder.addView(this, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    topMargin = dp(12)
                    marginStart = dp(14)
                })
                nightBadge = this
            }
        }
        // Prendido: luna y letras amarillas. Apagado: en blanco y tachado (como el iPhone)
        val color = if (nightAuto) 0xFFFFD60A.toInt() else 0xB3FFFFFF.toInt()
        badge.setTextColor(color)
        badge.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        badge.paintFlags = if (nightAuto) badge.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
        else badge.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        if (show && badge.visibility != View.VISIBLE) {
            badge.visibility = View.VISIBLE
            badge.animate().cancel()
            badge.animate().alpha(1f).setDuration(220).start()
        } else if (!show && badge.visibility == View.VISIBLE) {
            badge.animate().cancel()
            badge.animate().alpha(0f).setDuration(220).withEndAction { badge.visibility = View.GONE }.start()
        }
    }

    private fun log2(v: Float) = (kotlin.math.ln(v.toDouble()) / kotlin.math.ln(2.0)).toFloat()

    /** Pasos de compensación disponibles (máximo ±2 EV, como el iPhone). */
    private fun evSteps(): Int {
        val st = camera?.cameraInfo?.exposureState ?: return 0
        if (!st.isExposureCompensationSupported) return 0
        val step = st.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: return 0
        return kotlin.math.round(2f / step).toInt().coerceAtLeast(1)
    }

    private fun evLevel(): Float {
        val n = evSteps()
        return if (n == 0) 0f else (evBias.toFloat() / n).coerceIn(-1f, 1f)
    }

    /** Arrastrar: hacia arriba más claro, hacia abajo más oscuro. */
    private fun dragExposure(startBias: Int, dy: Float) {
        val n = evSteps()
        if (n == 0) return
        val pxPerStep = dp(120).toFloat() / n
        val target = (startBias - (dy / pxPerStep).toInt()).coerceIn(-n, n)
        main.removeCallbacks(dimFocus)
        main.removeCallbacks(hideFocus)
        focusBox.animate().cancel()
        focusBox.alpha = 1f
        focusBox.dragging = true
        if (target != evBias) {
            evBias = target
            focusBox.level = evLevel()
            applyExposure()
            refreshGainNow()
            updateEvUi()
            if (evBias == 0) shutter.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun endExposureDrag() {
        main.postDelayed(dimFocus, 1500)
        if (!aeLocked) main.postDelayed(hideFocus, 6000)
    }

    // ---------- Obturador: toque, mantener (QuickTake) y deslizar (ráfaga) ----------

    private fun setupShutter() {
        var downX = 0f
        var longStarted = false
        val longPress = Runnable {
            if ((mode == Mode.PHOTO || mode == Mode.INSTANT) && (!busy || preShot != null)) {
                // Era video: la foto del toque no va
                dropPreShot()
                longStarted = true
                startQuickTake()
            } else if (mode == Mode.EFFECTS && !busy) {
                longStarted = true
                startEffectRec()
            }
        }
        shutter.isSoundEffectsEnabled = false
        shutter.setOnClickListener {
            if (!commitPreShot()) onShutterTap()
        }
        shutter.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    longStarted = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    if ((mode == Mode.PHOTO || mode == Mode.INSTANT || mode == Mode.EFFECTS) && timerSecs == 0) main.postDelayed(longPress, 400)
                    // FOTO: se toma ya, en el instante en que apoyas el dedo
                    startPreShot()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    if (mode == Mode.PHOTO && !bursting && !quickTaking && dx < -dp(70) && (!busy || preShot != null)) {
                        main.removeCallbacks(longPress)
                        dropPreShot()
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
                        ev.actionMasked == MotionEvent.ACTION_UP -> v.performClick()
                        else -> dropPreShot()
                    }
                }
            }
            true
        }
    }

    private fun updateShutterDescription() {
        shutter.contentDescription = when (mode) {
            Mode.VIDEO -> when {
                videoStopping -> "Guardando video"
                recording != null -> "Detener video"
                else -> "Grabar video"
            }
            Mode.TIMELAPSE -> if (timelapsing) "Detener time-lapse" else "Grabar time-lapse"
            else -> "Tomar foto"
        }
    }

    private fun onShutterTap() {
        when {
            mode == Mode.VIDEO -> toggleVideo()
            mode == Mode.TIMELAPSE -> if (timelapsing) stopTimelapse() else startTimelapse()
            countingDown -> cancelCountdown()
            timerSecs > 0 -> startCountdown()
            else -> shoot()
        }
    }

    /** Captura "normal" o especial según el modo (boomerang y fotomatón en instantánea). */
    private fun shoot() {
        when {
            mode == Mode.INSTANT && instantFrame == InstantFrame.BOOTH -> captureBooth()
            mode == Mode.INSTANT && boomKind != "photo" -> captureBoomerang()
            mode == Mode.MOON -> captureMoon()
            mode == Mode.SUNSET -> captureSunset()
            mode == Mode.PHOTO -> {
                val indices = hdrPlan()
                if (indices == null) takeStill() else captureHdr(indices)
            }
            else -> takeStill()
        }
    }

    private var countdownLeft = 0
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!countingDown) return
            if (countdownLeft <= 0) {
                countingDown = false
                countdown.visibility = View.GONE
                shoot()
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
        if (lensStyle != Fisheye.OFF) applyTone()
        busy = true
        quickStart = System.currentTimeMillis()
        audio.keepUs = (MAX_QUICKTAKE_S + 5) * 1_000_000L
        recorder.enabled = true
        updateAudio()
        recorder.startLong()
        playSound(MediaActionSound.START_VIDEO_RECORDING)
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        shutter.setBackgroundResource(R.drawable.shutter_rec)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "0:00"
        main.post(quickTick)
    }

    private fun stopQuickTake() {
        if (!quickTaking) return
        quickTaking = false
        if (lensStyle != Fisheye.OFF) applyTone()
        main.removeCallbacks(quickTick)
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        recTimer.visibility = View.GONE
        shutter.setBackgroundResource(R.drawable.shutter)
        processingStart("Guardando video…")
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
                    busy = false
                }
                finishSave(msg)
            }
        }
    }

    // Ráfaga: fotos seguidas mientras mantienes el obturador deslizado
    private fun startBurst() {
        bursting = true
        if (lensStyle != Fisheye.OFF) applyTone()
        busy = true
        burstCount = 0
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "0"
        burstShot()
    }

    private fun burstShot() {
        val ic = imageCapture ?: return finishBurst("No se pudo tomar la foto")
        if (!bursting) return
        val f = filter
        val file = File(cacheDir, "burst.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).setMetadata(selfieMeta()).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    playSound(MediaActionSound.SHUTTER_CLICK)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bytes = readCapturedJpeg(file)
                    if (bytes == null) {
                        runOnUiThread { finishBurst("No se pudo tomar la foto") }
                        return
                    }
                    burstCount++
                    try {
                        MotionPhotoWriter.save(this@MainActivity, MotionPhotoWriter.applyFilter(bytes, f), null, "_BURST$burstCount")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Ráfaga", e)
                    }
                    runOnUiThread {
                        recTimer.text = burstCount.toString()
                        if (bursting && burstCount < 50) burstShot() else finishBurst()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    runOnUiThread { finishBurst("No se pudo tomar la foto") }
                }
            }
        )
    }

    private fun stopBurst() {
        bursting = false
        if (lensStyle != Fisheye.OFF) applyTone()
        recTimer.visibility = View.GONE
    }

    private fun finishBurst(error: String? = null) {
        stopBurst()
        busy = false
        val n = burstCount
        processingStart()
        ioExecutor.execute { finishSave(error ?: if (n > 0) "Ráfaga: $n fotos" else null) }
    }

    // ---------- Modo VIDEO (1080p con CameraX) ----------

    @SuppressLint("MissingPermission")
    private fun toggleVideo() {
        val vc = videoCapture ?: return
        recording?.let {
            if (videoStopping) return
            videoStopping = true
            // Se corta en este instante: el aviso va ya (antes sonaba cuando terminaba de guardar)
            it.stop()
            playSound(MediaActionSound.STOP_VIDEO_RECORDING)
            shutter.setBackgroundResource(R.drawable.shutter_rec)
            recTimer.visibility = View.GONE
            processingStart()
            updateShutterDescription()
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
        playSound(MediaActionSound.START_VIDEO_RECORDING)
        // El botón cambia al tocar (no medio segundo después, cuando la cámara confirma)
        shutter.setBackgroundResource(R.drawable.shutter_stop)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "0:00"
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {}
                is VideoRecordEvent.Status -> {
                    val s = event.recordingStats.recordedDurationNanos / 1_000_000_000
                    recTimer.text = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    shutter.setBackgroundResource(R.drawable.shutter_rec)
                    recTimer.visibility = View.GONE
                    if (videoStopping) processingEnd()
                    videoStopping = false
                    updateShutterDescription()
                    if (event.hasError()) showToast("Error grabando: ${event.error}")
                    refreshThumbnail()
                }
            }
        }
        updateShutterDescription()
    }

    // ---------- Cámara ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
            checkVendorExtensions()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * ¿El fabricante deja usar su procesado (HDR, Noche, Auto…) desde otras apps? En muchos cels
     * no (solo lo usa su cámara). Se revisa una vez y se ve en el panel de Rendimiento.
     */
    @Volatile private var vendorExt = "revisando…"

    private fun checkVendorExtensions() {
        val provider = provider ?: return
        val f = try {
            androidx.camera.extensions.ExtensionsManager.getInstanceAsync(this, provider)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar la consulta de extensiones", e)
            vendorExt = "no se pudo revisar"
            return
        }
        f.addListener({
            vendorExt = try {
                val em = f.get()
                val modes = listOf(
                    androidx.camera.extensions.ExtensionMode.AUTO to "Auto",
                    androidx.camera.extensions.ExtensionMode.HDR to "HDR",
                    androidx.camera.extensions.ExtensionMode.NIGHT to "Noche",
                    androidx.camera.extensions.ExtensionMode.BOKEH to "Retrato",
                    androidx.camera.extensions.ExtensionMode.FACE_RETOUCH to "Retoque",
                )
                fun describeLens(lens: String, selector: CameraSelector): String {
                    try {
                        if (!provider.hasCamera(selector)) return "$lens: cámara no disponible"
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo consultar la cámara $lens", e)
                        return "$lens: no se pudo revisar"
                    }
                    val supported = mutableListOf<String>()
                    val failed = mutableListOf<String>()
                    for ((extension, label) in modes) {
                        try {
                            if (em.isExtensionAvailable(selector, extension)) supported += label
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo consultar $label en cámara $lens", e)
                            failed += label
                        }
                    }
                    val available = when {
                        supported.isNotEmpty() -> supported.joinToString(", ")
                        failed.isEmpty() -> "ninguna"
                        else -> "sin confirmar"
                    }
                    val warning = if (failed.isEmpty()) "" else " (sin revisar: ${failed.joinToString(", ")})"
                    return "$lens: $available$warning"
                }
                describeLens("trasera", CameraSelector.DEFAULT_BACK_CAMERA) + " · " +
                    describeLens("frontal", CameraSelector.DEFAULT_FRONT_CAMERA)
            } catch (e: Exception) {
                Log.w(TAG, "Extensiones", e)
                "no se pudo revisar"
            }
            Log.i(TAG, "Procesado del fabricante: $vendorExt")
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Orientación: la interfaz es vertical, pero la foto sigue al cel ----------

    /** Giro del cel en grados (0, 90, 180 o 270, como OrientationEventListener). */
    @Volatile private var deviceDegrees = 0

    /** Análisis que sigue el giro (video del Live, QuickTake, time-lapse y QR). */
    private var rotatingAnalysis: ImageAnalysis? = null

    /**
     * Antes la foto siempre salía como si el cel estuviera vertical: con el cel de lado, la foto
     * quedaba acostada en la galería. Ahora la foto, el video y el Live salen derechos, y los
     * íconos giran para leerse bien.
     */
    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
                val next = Orientation.snap(degrees, deviceDegrees)
                if (next == deviceDegrees) return
                deviceDegrees = next
                Log.i(TAG, "Orientación: $next°")
                applyDeviceRotation()
            }
        }
    }

    private var orientationWatched = false

    /** El sensor de giro solo escucha mientras la cámara está en pantalla. */
    private fun watchOrientation() {
        if (orientationWatched) return
        orientationWatched = true
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                if (orientationListener.canDetectOrientation()) orientationListener.enable()
            }

            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                orientationListener.disable()
            }
        })
    }

    private fun applyDeviceRotation() {
        watchOrientation()
        val r = Orientation.surfaceRotation(deviceDegrees)
        if (hdrDeadline == null) imageCapture?.targetRotation = r
        videoCapture?.targetRotation = r
        rotatingAnalysis?.targetRotation = r
        rotateControls()
        if (lensShown && lensStyle == Fisheye.MIRROR) applyTone()
    }

    /** Los íconos giran en su sitio (por el camino corto) para quedar derechos con el cel de lado. */
    private fun rotateControls() {
        if (!::flip.isInitialized) return
        val target = -deviceDegrees.toFloat()
        val views = listOf<View>(flip, thumbnail, flashButton, timerButton, liveToggle, filterButton, gridButton,
            beautyButton, evButton, vividButton, lensButton, zoom1, zoom2)
        for (v in views) {
            var t = target
            while (t - v.rotation > 180f) t -= 360f
            while (t - v.rotation < -180f) t += 360f
            v.animate().rotation(t).setDuration(220).start()
        }
    }

    private fun bindCamera() {
        val provider = provider ?: return
        recorder.mirror = front && mirrorSelfies()
        dropPreShot()
        zslAsked = false
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val group = when (mode) {
            Mode.VIDEO -> buildVideoGroup()
            Mode.NIGHT, Mode.NIGHT_VISION -> buildPhotoGroup(night = true, analysis = false)
            Mode.SUNSET, Mode.MOON -> buildPhotoGroup(night = false, analysis = false)
            Mode.PORTRAIT -> buildPhotoGroup(night = false, analysis = false)
            Mode.EFFECTS -> buildEffectsGroup()
            else -> buildPhotoGroup(night = false, analysis = true)
        }
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, group)
            // El análisis de Efectos dibuja su propia vista previa: ese se queda vertical
            rotatingAnalysis = if (mode == Mode.EFFECTS) null else group.useCases.filterIsInstance<ImageAnalysis>().firstOrNull()
            applyDeviceRotation()
            watchCameraState()
            observeZoom()
            applySmoothFps()
            applyFlash()
            if (mode == Mode.MOON) main.postDelayed({ animateZoom(minOf(4f, camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 4f)) }, 600)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la cámara", e)
            showToast("Error de cámara: ${e.message}")
        }
    }

    // ---------- Visor fluido: 30 fps estables, y más luz solo cuando de verdad está oscuro ----------

    @Volatile private var lowLight = false
    @Volatile private var darkIso = 2800
    private var darkSince = 0L
    private var brightSince = 0L
    @Volatile private var lastIso = 0
    @Volatile private var lastExpNs = 0L
    /** Tiempo máximo por cuadro con los fps normales (1/20 s si el cel tiene ese rango). */
    @Volatile private var normalCapNs = 50_000_000L

    /**
     * Hora exacta (del sensor) en que se expuso cada foto: con ella el video del Live queda
     * centrado en el mismo instante que la foto. Con ZSL es la hora del cuadro elegido.
     */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun watchStill(b: ImageCapture.Builder) {
        androidx.camera.camera2.interop.Camera2Interop.Extender(b).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long,
                ) {
                    val intent = request.get(android.hardware.camera2.CaptureRequest.CONTROL_CAPTURE_INTENT)
                    if (request.isReprocess ||
                        intent == android.hardware.camera2.CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                    ) recorder.markShutterSensor(timestamp)
                }
            })
    }

    // ---------- Disparo sin retraso (ZSL) ----------

    private var zslAsked = false
    private var zslBroken = false
    private var stateWatched: androidx.lifecycle.LiveData<androidx.camera.core.CameraState>? = null

    private fun useZsl() = !hdrSession(mode) && !zslBroken &&
        CameraSettings.get(this, CameraSettings.ZSL, "on") == "on"

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalZeroShutterLag::class)
    private fun zslSupported() = !zslBroken && (camera?.cameraInfo?.isZslSupported ?: false)

    /** Si este cel no aguanta el ZSL junto con lo demás, se apaga solo y se vuelve a abrir. */
    private fun watchCameraState() {
        stateWatched?.removeObservers(this)
        val live = camera?.cameraInfo?.cameraState ?: return
        stateWatched = live
        live.observe(this) { st ->
            val err = st.error ?: return@observe
            if (zslAsked && !zslBroken && err.code == androidx.camera.core.CameraState.ERROR_STREAM_CONFIG) {
                Log.w(TAG, "ZSL no funciona en este cel: se apaga")
                zslBroken = true
                prefs.edit().putBoolean(KEY_ZSL_BROKEN, true).apply()
                main.post { bindCamera() }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun watchExposure(b: Preview.Builder) {
        androidx.camera.camera2.interop.Camera2Interop.Extender(b).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult,
                ) {
                    perf?.camFrames?.incrementAndGet()
                    val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return
                    val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                    onExposure(iso, exp)
                }
            })
    }

    private fun onExposure(iso: Int, expNs: Long) {
        lastIso = iso
        lastExpNs = expNs
        val now = android.os.SystemClock.uptimeMillis()
        val cap = normalCapNs
        if (!lowLight) {
            // "Oscuro" = la cámara ya expone todo el tiempo que le dejamos, o ya está al tope de
            // sensibilidad. Se mira sobre todo el tiempo: la frontal se queda corta de ISO y con
            // solo el ISO nunca se enteraba (por eso quedaba negra aunque se subiera el brillo)
            if (expNs >= cap * 8 / 10 || (iso >= darkIso && expNs >= 28_000_000L)) {
                if (darkSince == 0L) darkSince = now
                if (now - darkSince > 1200) {
                    lowLight = true
                    darkSince = 0
                    main.post { applySmoothFps(); applyPreviewFilter() }
                }
            } else darkSince = 0
        } else {
            // Claro de verdad: pudiendo exponer más, la cámara eligió poco tiempo y poco ISO
            if (expNs < cap / 2 && iso < darkIso / 2) {
                if (brightSince == 0L) brightSince = now
                if (now - brightSince > 1500) {
                    lowLight = false
                    brightSince = 0
                    main.post { applySmoothFps(); applyPreviewFilter() }
                }
            } else brightSince = 0
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applySmoothFps() {
        val cam = camera ?: return
        try {
            val ctl = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
            if (mode == Mode.NIGHT || mode == Mode.NIGHT_VISION || mode == Mode.VIDEO) {
                ctl.clearCaptureRequestOptions()
                return
            }
            val ranges = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: return
            val chars = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
            val isoTop = chars.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 3200
            darkIso = minOf(2800, (isoTop * 0.7f).toInt())
            val at30 = ranges.filter { it.upper == 30 }
            // Normal: hasta 1/20 s por cuadro (nítido aunque la gente se mueva).
            // Solo cuando ya está al tope de sensibilidad se deja exponer más tiempo.
            val normal = at30.minByOrNull { kotlin.math.abs(it.lower - 20) * 2 + (if (it.lower < 20) 1 else 0) }
            normalCapNs = 1_000_000_000L / (normal?.lower ?: 20).coerceAtLeast(1)
            val pick = if (lowLight) at30.minByOrNull { it.lower } else normal
            val opts = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            pick?.let { opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            // Bloqueo AE/AF: la exposición se queda quieta (el brillo ± sigue funcionando)
            if (aeLocked) opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK, true)
            if (lensFacing == CameraSelector.LENS_FACING_FRONT && mode != Mode.MOON && mode != Mode.SUNSET) {
                // Selfies: la cámara mide la luz sobre la cara (si el cel lo soporta)
                val scenes = chars.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                if (scenes?.contains(android.hardware.camera2.CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY) == true) {
                    opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY)
                }
            }
            if (mode == Mode.MOON) {
                // Enfoque fijo al infinito
                opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
                opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                // Exposición manual (la luna es tan brillante como un día de sol)
                if (manualSensor()) {
                    opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF)
                    opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, 100)
                    opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, moonExposureNs)
                    opts.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                }
            }
            ctl.setCaptureRequestOptions(opts.build())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo fijar los fps", e)
        }
    }

    // ---------- Luna y atardecer ----------

    private var moonExposureNs = 400_000L // 1/2500 s
    private lateinit var moonPanel: LinearLayout

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun manualSensor(): Boolean = try {
        val caps = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera!!.cameraInfo)
            .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        caps?.contains(android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    } catch (_: Exception) {
        false
    }

    /** Compensación de exposición: más oscuro en atardecer (cielo con color) y luna sin control manual. */
    private fun applyExposure() {
        if (hdrDeadline != null) return
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return
        val step = st.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: return
        val base = when {
            mode == Mode.SUNSET -> (-1f / step).roundToIntSafe()
            mode == Mode.MOON && !manualSensor() -> st.exposureCompensationRange.lower
            else -> 0
        }
        val idx = (base + if (mode == Mode.MOON) 0 else evBias + autoEv)
            .coerceIn(st.exposureCompensationRange.lower, st.exposureCompensationRange.upper)
        try {
            cam.cameraControl.setExposureCompensationIndex(idx)
        } catch (_: Exception) {
        }
    }

    private fun Float.roundToIntSafe() = kotlin.math.round(this).toInt()

    private var hdrDeadline: Runnable? = null

    private fun hdrEnabled() = CameraSettings.get(this, CameraSettings.HDR, "off") == "on"

    private fun hdrSession(m: Mode) = m == Mode.PHOTO && !liveOn && hdrEnabled() &&
        CameraSettings.photo(this) != "max"

    private fun hdrPlan(): List<Int>? {
        if (!hdrSession(mode) || flashMode != "off" || lowLight || aeLocked ||
            frozen || pendingSaves > 0 || zslAsked) return null
        val state = camera?.cameraInfo?.exposureState ?: return null
        if (!state.isExposureCompensationSupported) return null
        val range = state.exposureCompensationRange
        return Bracket.hdrExposureIndices(
            state.exposureCompensationIndex, state.exposureCompensationStep.toFloat(), range.lower, range.upper,
        )
    }

    private fun captureHdr(indices: List<Int>) {
        val cam = camera ?: return
        if (busy || imageCapture == null) return
        busy = true
        closePanels()
        perf?.pressed()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        shotAt = startedAt
        val currentFilter = filter
        val vivid = vividActive()
        val lens = lensNow()
        val auto = Enhance.enabled(this)
        var reference: ByteArray? = null
        var cancelled = false
        lateinit var deadline: Runnable
        holdStart(3)
        Log.i(TAG, "Disparo: FOTO HDR ${if (front) "frontal" else "trasera"} EV=$indices lente=${Lens.name(lens)}")

        fun finish(bracket: Pair<ByteArray, ByteArray>? = null) {
            if (hdrDeadline !== deadline) return
            hdrDeadline = null
            main.removeCallbacks(deadline)
            applyExposure()
            applyDeviceRotation()
            holdEnd(processing = true, note = if (bracket != null) "Juntando las exposiciones…" else null)
            val original = reference
            // Durante la fusión queda ocupado para limitar memoria y mantener la medición por toma.
            saveExecutor.execute {
                saveHdr(original, bracket, currentFilter, vivid, auto, startedAt, lens)
            }
        }

        deadline = Runnable {
            cancelled = true
            if (reference != null) finish()
        }
        hdrDeadline = deadline

        fun takeExtra(index: Int, done: (ByteArray) -> Unit) {
            if (hdrDeadline !== deadline || cancelled || camera !== cam) {
                finish()
                return
            }
            val target = try {
                cam.cameraControl.setExposureCompensationIndex(index)
            } catch (e: Exception) {
                Log.w(TAG, "HDR: no se pudo pedir la exposición $index", e)
                finish()
                return
            }
            target.addListener({
                if (hdrDeadline === deadline && !cancelled) {
                    try {
                        target.get()
                        grab(onStarted = { if (hdrDeadline === deadline) shotTaken(sound = false) }) { bytes ->
                            main.post {
                                if (hdrDeadline === deadline) {
                                    if (bytes == null) finish() else done(bytes)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "HDR: no se pudo capturar la exposición $index", e)
                        finish()
                    }
                }
            }, ContextCompat.getMainExecutor(this))
        }

        try {
            grab(onStarted = {
                if (hdrDeadline === deadline) {
                    perf?.captured()
                    shotTaken(sound = true)
                }
            }) { bytes ->
                main.post {
                    if (hdrDeadline === deadline) {
                        reference = bytes
                        if (bytes == null || cancelled) finish()
                        else {
                            main.postDelayed(deadline, 4_000L)
                            takeExtra(indices[1]) { low ->
                                takeExtra(indices[2]) { high ->
                                    finish(low to high)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HDR: no se pudo tomar la foto de referencia", e)
            finish()
        }
    }

    private fun saveHdr(
        original: ByteArray?, bracket: Pair<ByteArray, ByteArray>?, filter: PhotoFilter,
        vivid: Boolean, auto: Boolean, startedAt: Long, lens: Int = Fisheye.OFF,
    ) {
        val message = when {
            original == null -> "No se pudo tomar la foto"
            bracket == null -> saveOriginalPhoto(original)
            else -> try {
                val center = Processing.decode(original, 2400)
                val w = center.width
                val h = center.height
                val reference = try {
                    IntArray(w * h).also { center.getPixels(it, 0, w, 0, 0, w, h) }
                } finally {
                    center.recycle()
                }
                fun pixels(jpeg: ByteArray): IntArray {
                    val bmp = Processing.decode(jpeg, 2400)
                    return try {
                        require(bmp.width == w && bmp.height == h) { "Las exposiciones tienen tamaños distintos" }
                        IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
                    } finally {
                        bmp.recycle()
                    }
                }
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val merged = Bracket.fuseHdr(listOf(pixels(bracket.first), reference, pixels(bracket.second)), w, h, threads)
                if (merged == null) {
                    Log.i(TAG, "HDR: exposiciones descartadas, guardando la referencia")
                    saveOriginalPhoto(original)
                } else {
                    val (fused, report) = merged
                    Log.i(TAG, "HDR: $report")
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val jpeg = try {
                        bmp.setPixels(fused, 0, w, 0, 0, w, h)
                        if (auto || vivid) Enhance.bitmap(this, bmp, vivid = vivid, auto = auto)
                        Lens.apply(bmp, lens)
                        Processing.encode(bmp, 95)
                    } finally {
                        bmp.recycle()
                    }
                    MotionPhotoWriter.save(this, MotionPhotoWriter.applyFilter(jpeg, filter), null, "_HDR")
                    null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "HDR: no se pudo guardar la fusión", e)
                saveOriginalPhoto(original)
            }
        }
        Log.i(TAG, "HDR: guardado terminó en ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
        finishSave(message)
        runOnUiThread { busy = false }
    }

    private fun buildMoonPanel() {
        val parent = findViewById<View>(R.id.zoomBar).parent as FrameLayout
        moonPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        moonPanel.addView(TextView(this).apply {
            text = "Brillo de la luna"
            textSize = 12f
            setTextColor(0xE6FFFFFF.toInt())
        })
        val bar = android.widget.SeekBar(this).apply {
            max = 100
            progress = 25
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    // De 1/6000 s a 1/120 s (escala logarítmica)
                    val t = p / 100.0
                    moonExposureNs = (1_000_000_000.0 / (6000.0 * Math.pow(120.0 / 6000.0, t))).toLong()
                    applySmoothFps()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        moonPanel.addView(bar, LinearLayout.LayoutParams(0, dp(36), 1f))
        parent.addView(moonPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM
            setMargins(dp(20), 0, dp(20), dp(116))
        })
    }

    /**
     * Atardecer: tres fotos seguidas (oscura, normal y clara) que luego se juntan.
     * El cielo y el sol salen de la oscura, y la tierra de la clara.
     */
    private fun captureSunset() {
        val cam = camera
        if (busy || imageCapture == null || cam == null) return
        val st = cam.cameraInfo.exposureState
        val step = st.exposureCompensationStep.toFloat()
        if (!st.isExposureCompensationSupported || step <= 0f) {
            takeStill()
            return
        }
        val want = kotlin.math.round(2f / step).toInt()
        val lo = maxOf(-want, st.exposureCompensationRange.lower)
        val hi = minOf(want, st.exposureCompensationRange.upper)
        // Sin margen de exposición no hay nada que juntar: foto normal
        if (lo > -2 || hi < 2) {
            takeStill()
            return
        }
        busy = true
        filterBar.visibility = View.GONE
        shutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        holdStart(3)
        val order = listOf(lo, 0, hi)
        val shots = ArrayList<ByteArray>()
        var neutral: ByteArray? = null
        val currentFilter = filter
        val vivid = vividActive()
        fun next(i: Int) {
            if (i == order.size) {
                applyExposure()
                busy = false
                holdEnd(processing = true, note = "Juntando las 3 fotos…")
                saveExecutor.execute { saveSunset(shots, neutral, currentFilter, vivid) }
                return
            }
            val future = try {
                cam.cameraControl.setExposureCompensationIndex(order[i])
            } catch (e: Exception) {
                null
            }
            val take = Runnable {
                // Un respiro para que la cámara se acomode a la nueva exposición
                main.postDelayed({
                    grab(onStarted = { shotTaken(sound = i == 0) }) { b ->
                        runOnUiThread {
                            if (b != null) shots += b
                            if (i == 1) neutral = b
                            next(i + 1)
                        }
                    }
                }, 280)
            }
            if (future == null) main.post(take)
            else future.addListener(take, ContextCompat.getMainExecutor(this))
        }
        next(0)
    }

    private fun saveSunset(shots: List<ByteArray>, neutral: ByteArray?, f: PhotoFilter, vivid: Boolean = false) {
        val reference = neutral ?: shots.lastOrNull()
        if (reference == null) {
            finishSave("No se pudo tomar la foto")
            return
        }
        val msg = try {
            if (shots.size < 3) {
                // Si falta una toma, mejora la exposición de referencia.
                MotionPhotoWriter.save(
                    this,
                    MotionPhotoWriter.applyFilter(Enhance.jpeg(this, reference, sunset = true, vivid = vivid), f),
                    null, "_ATARDECER",
                )
                null
            } else {
                // Tamaño contenido: juntar tres fotos de 12 MP no cabe en memoria
                val bmps = shots.map { Processing.decode(it, 3000) }
                val w = bmps[0].width
                val h = bmps[0].height
                val frames = bmps.map { b -> IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } }
                bmps.forEach { it.recycle() }
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val (fused, rep) = Bracket.fuse(frames, w, h, threads)
                Log.d(TAG, "Atardecer $rep")
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(fused, 0, w, 0, 0, w, h)
                Enhance.bitmap(this, bmp, sunset = true, vivid = vivid)
                MotionPhotoWriter.save(
                    this,
                    MotionPhotoWriter.applyFilter(Processing.encode(bmp, 95), f),
                    null, "_ATARDECER",
                )
                bmp.recycle()
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Atardecer", e)
            saveOriginalPhoto(reference)
        }
        finishSave(msg)
    }

    /** 6 fotos seguidas de la luna, luego se alinean y se juntan. */
    private fun captureMoon() {
        if (busy || imageCapture == null) return
        busy = true
        shutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        holdStart(6)
        val shots = ArrayList<ByteArray>()
        fun next(i: Int) {
            if (i == 6) {
                busy = false
                holdEnd(processing = true, note = "Juntando las fotos de la luna…")
                saveExecutor.execute { saveMoon(shots) }
                return
            }
            grab(onStarted = { shotTaken(sound = i == 0) }) { b ->
                runOnUiThread {
                    if (b != null) shots += b
                    main.postDelayed({ next(i + 1) }, 120)
                }
            }
        }
        // Un momento para que el cel deje de moverse después del toque
        main.postDelayed({ next(0) }, 500)
    }

    private fun saveMoon(shots: List<ByteArray>) {
        if (shots.isEmpty()) {
            finishSave("No se pudo tomar la foto de la luna")
            return
        }
        val msg = try {
            val bmp = Astro.stackMoon(shots)
            MotionPhotoWriter.save(this, Processing.encode(bmp, 95), null, "_LUNA")
            bmp.recycle()
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Luna", e)
            saveOriginalPhoto(shots.first())
        }
        finishSave(msg)
    }

    @androidx.annotation.OptIn(
        androidx.camera.camera2.interop.ExperimentalCamera2Interop::class,
        androidx.camera.core.ExperimentalZeroShutterLag::class,
    )
    private fun buildPhotoGroup(night: Boolean, analysis: Boolean): UseCaseGroup {
        videoCapture = null
        val wide = wideFormat
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
            .build()
        val previewB = Preview.Builder().setResolutionSelector(ratio43)
        if (!night) watchExposure(previewB)
        // Disparo sin retraso (ZSL): la cámara guarda los últimos cuadros a resolución completa y
        // la foto sale del cuadro del instante en que se pidió, no de uno posterior. Si el cel no
        // lo permite, CameraX usa el modo rápido normal. Nada de "máxima calidad": ese modo
        // vuelve a enfocar antes de tomar (hasta 1 s de espera).
        zslAsked = useZsl()
        val captureB = ImageCapture.Builder()
            .setCaptureMode(
                if (zslAsked) ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            )
            .setJpegQuality(98)
            .setResolutionSelector(CameraSettings.photoSelector(this, wide))
        watchStill(captureB)
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
                .setViewPort(ViewPort.Builder(viewPortRational(), Surface.ROTATION_0).build())
                .addUseCase(preview)
                .addUseCase(capture)
                .build()
        }
        val analysisUc = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(CameraSettings.liveSize(this), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, recorder) }
        // Mismo encuadre para vista previa, foto y video (el del formato elegido)
        return UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(viewPortRational(), Surface.ROTATION_0).build())
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
        val preview = Preview.Builder().setResolutionSelector(ratio43).also { watchExposure(it) }.build()
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

    private val front get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    /** Selfies como te ves en la pantalla (espejo), salvo que el usuario elija "como te ven". */
    private fun mirrorSelfies() = CameraSettings.get(this, CameraSettings.MIRROR, "mirror") == "mirror"

    /** La foto de la frontal sale igual que la vista previa (volteada como espejo). */
    private fun selfieMeta() = ImageCapture.Metadata().apply {
        isReversedHorizontal = front && mirrorSelfies()
    }

    /** Con la cámara frontal el "flash" es la pantalla encendida a todo brillo (del color elegido). */
    private fun wantsScreenFlash() = front && when (flashMode) {
        "on", "torch" -> true
        "auto" -> lowLight
        else -> false
    }

    private fun showScreenFlash() {
        screenFlash.color = flashColor
        screenFlash.shape = flashShape
        screenFlash.alpha = 1f
        screenFlash.visibility = View.VISIBLE
        window.attributes = window.attributes.apply { screenBrightness = 1f }
    }

    private fun hideScreenFlash() {
        screenFlash.animate().alpha(0f).setDuration(200).withEndAction {
            screenFlash.visibility = View.GONE
            if (ringLight.visibility != View.VISIBLE) window.attributes = window.attributes.apply {
                screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }.start()
    }

    // ---------- Aviso de captura: se marca cuando la foto de verdad se toma ----------

    private var holdPill: TextView? = null
    private var holdTotal = 0
    private var holdDone = 0
    private var spinRing: SpinRing? = null
    private var pendingSaves = 0
    private val holdShow = Runnable { showHoldPill() }
    private val spinSafety = Runnable {
        pendingSaves = 0
        spinRing?.spin(false)
    }

    /**
     * Al tocar el obturador: el botón se hunde hasta que la foto se toma de verdad. Si la cámara
     * tarda un instante (o son varias fotos), aparece "Quieto…" para no mover el cel antes de tiempo.
     */
    private fun holdStart(total: Int) {
        holdTotal = total
        holdDone = 0
        shutter.animate().cancel()
        shutter.animate().scaleX(0.86f).scaleY(0.86f).setDuration(110).start()
        main.removeCallbacks(holdShow)
        main.postDelayed(holdShow, if (total > 1) 0L else 250L)
    }

    /** La cámara empezó a tomar la foto: aquí (y no al tocar) van el parpadeo y el clic. */
    private fun shotTaken(sound: Boolean) {
        if (holdDone == 0) blink(sound)
        holdDone++
        holdPill?.let { if (it.visibility == View.VISIBLE) it.text = holdText() }
    }

    /** Parpadeo negro de la vista previa + clic: el instante exacto de la foto. */
    private fun blink(sound: Boolean) {
        if (sound) playSound(MediaActionSound.SHUTTER_CLICK)
        flash.animate().cancel()
        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(220).start()
    }

    /** Ya se tomó todo: el botón vuelve y, si falta procesar, gira el aro de la miniatura. */
    private fun holdEnd(processing: Boolean, note: String? = null) {
        if (holdTotal == 0) return // ya terminó (no contar dos veces el proceso)
        main.removeCallbacks(holdShow)
        shutter.animate().cancel()
        shutter.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        val pill = holdPill
        if (pill != null && pill.visibility == View.VISIBLE) {
            val multi = holdTotal > 1
            if (multi) {
                pill.text = "Listo, ya puedes moverte"
                shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            pill.animate().cancel()
            pill.animate().alpha(0f).setStartDelay(if (multi) 650L else 0L).setDuration(200)
                .withEndAction { pill.visibility = View.GONE; pill.animate().startDelay = 0 }.start()
        }
        holdTotal = 0
        if (processing) processingStart(note)
    }

    private fun holdText() = if (holdTotal > 1) "Quieto · ${holdDone.coerceAtMost(holdTotal)} de $holdTotal" else "Quieto…"

    private fun showHoldPill() {
        if (holdTotal == 0) return
        val pill = holdPill ?: run {
            if (!::evButton.isInitialized) return
            val holder = evButton.parent as? FrameLayout ?: return
            TextView(this).apply {
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(7), dp(16), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(17).toFloat()
                    setColor(0xA6000000.toInt())
                }
                visibility = View.GONE
                holder.addView(this, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = dp(52)
                })
                holdPill = this
            }
        }
        pill.text = holdText()
        pill.animate().cancel()
        pill.animate().startDelay = 0
        pill.visibility = View.VISIBLE
        pill.alpha = 0f
        pill.scaleX = 0.92f
        pill.scaleY = 0.92f
        pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    /** La foto ya está tomada pero se sigue procesando: aro girando en la miniatura (y aviso si tarda). */
    private fun processingStart(note: String? = null) {
        pendingSaves++
        val ring = spinRing ?: run {
            val holder = thumbnail.parent as? FrameLayout ?: return@run null
            SpinRing(this).also { r ->
                holder.addView(r, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    marginStart = -dp(5)
                })
                spinRing = r
            }
        }
        ring?.spin(true)
        note?.let { showToast(it) }
        main.removeCallbacks(spinSafety)
        main.postDelayed(spinSafety, 30_000)
    }

    /** Cuándo se tocó el botón (para el registro: cuánto tardó en quedar lista). */
    private var shotAt = 0L

    private fun processingEnd() {
        perf?.ready()
        if (shotAt > 0L) {
            Log.i(TAG, "Foto lista en ${android.os.SystemClock.elapsedRealtime() - shotAt} ms")
            shotAt = 0L
        }
        pendingSaves = (pendingSaves - 1).coerceAtLeast(0)
        if (pendingSaves == 0) {
            main.removeCallbacks(spinSafety)
            spinRing?.spin(false)
        }
    }

    /** Aviso para los modos que tardan en procesar. */
    private fun processingNote(m: Mode, dark: Boolean = false, lens: Int = Fisheye.OFF): String? = when {
        m == Mode.PORTRAIT -> "Procesando retrato…"
        m == Mode.NIGHT -> "Juntando las fotos de noche…"
        dark && (m == Mode.PHOTO || m == Mode.INSTANT) -> "Mejorando la foto de noche…"
        lens == Fisheye.WIDE -> "Curvando la foto…"
        lens == Fisheye.MIRROR -> "Armando el espejo…"
        else -> null
    }

    private fun takeStill() {
        if (busy || imageCapture == null) return
        if (wantsScreenFlash()) {
            busy = true
            showScreenFlash()
            // Dar tiempo a que la cámara se acomode a la luz de la pantalla
            main.postDelayed({
                busy = false
                doTakeStill(screenLit = true)
                // Por si la cámara nunca avisa, no dejar la pantalla prendida
                main.postDelayed({ if (screenFlash.visibility == View.VISIBLE) hideScreenFlash() }, 4000)
            }, 700)
        } else doTakeStill()
    }

    private var liveClipDeadline: Runnable? = null

    private fun doTakeStill(screenLit: Boolean = false, pre: PreShot? = null) {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        perf?.pressed()
        closePanels()

        val live = liveActive
        // El toque se siente ya; el clic y el parpadeo van cuando la cámara de verdad toma
        // la foto (shotTaken), así se sabe el instante exacto
        shutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        val currentMode = mode
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val currentFilter = filter
        // Viva se decide al disparar (si se apaga mientras se procesa, esta foto igual la lleva)
        val vivid = vividActive()
        val lens = lensNow()
        // Con el modo noche apagado la foto se trata como una normal (sin subirla de más)
        val dark = lowLight && nightAuto
        // De noche la foto se sube igual que la vista previa (con el brillo que eligió el usuario)
        val userEv = evBias * evStepSize()
        val aim = if (dark || currentMode == Mode.NIGHT) Brightness.aim(userEv) else 0f
        val maxG = Brightness.maxGain(userEv)
        // Noche: unas fotos más, iguales, para juntarlas (menos grano, como el iPhone)
        val burst = if (pre != null) 0 else nightBurstCount(currentMode, screenLit)
        shotAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "Disparo: ${currentMode.name} ${if (front) "frontal" else "trasera"} noche=$dark ganancia=" +
            String.format(java.util.Locale.US, "%.2f", digitalGain) + " ev=$evBias fotos=${1 + burst} live=$live " +
            "viva=$vivid lente=${Lens.name(lens)} zsl=$zslAsked pantalla=$screenLit al-apoyar=${pre != null}")
        // Foto al apoyar el dedo: el instante ya quedó; el aviso (clic) va al soltar
        if (pre == null) holdStart(1 + burst)
        if (live && pre == null) {
            livePill.visibility = View.VISIBLE
            livePill.alpha = 1f
        }

        val lock = Any()
        var photo: ByteArray? = null
        var clip: MotionRecorder.Clip? = null
        var extras: List<ByteArray> = emptyList()
        var remaining = if (live) 2 else 1

        fun finishOne() {
            synchronized(lock) {
                remaining--
                if (remaining > 0) return
            }
            val (p, c) = synchronized(lock) { photo to clip }
            val ex = synchronized(lock) { extras }
            val save = {
                // La cámara queda libre ya; el procesado sigue por detrás
                runOnUiThread {
                    busy = false
                    if (live) livePill.visibility = View.GONE
                    if (pre != null) processingStart(processingNote(currentMode, dark, lens))
                }
                saveFinished(currentMode, p, c, ex, live, front, currentFilter, dark, aim, maxG, vivid, lens)
            }
            if (pre == null) save()
            else pre.ready(save) {
                // Era video (mantener) o ráfaga (deslizar): esta foto no se guarda
                runOnUiThread {
                    if (!quickTaking && !bursting && !effectRecording) busy = false
                    livePill.visibility = View.GONE
                }
            }
        }

        if (live) {
            lateinit var request: MotionRecorder.ClipRequest
            val deadline = Runnable { request.cancel() }
            request = recorder.requestClip(waitForMark = true) { result ->
                main.post {
                    main.removeCallbacks(deadline)
                    if (liveClipDeadline === deadline) liveClipDeadline = null
                }
                synchronized(lock) { clip = result }
                finishOne()
            }
            liveClipDeadline = deadline
            main.postDelayed(deadline, 4_000L)
        }

        val photoFile = File(cacheDir, "photo_${System.nanoTime()}.jpg")
        val meta = selfieMeta()
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).setMetadata(meta).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    perf?.captured()
                    // Respaldo por si no llega la hora exacta del sensor (watchStill)
                    if (live) recorder.markShutter(System.nanoTime() / 1000)
                    // Con LIVE no suena el clic (como en el iPhone: quedaría grabado en el audio)
                    if (pre == null) main.post { shotTaken(sound = !live) }
                    if (screenLit) main.postDelayed({ hideScreenFlash() }, 350)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (screenLit) main.post { if (screenFlash.visibility == View.VISIBLE) hideScreenFlash() }
                    val bytes = readCapturedJpeg(photoFile)
                    synchronized(lock) { photo = bytes }
                    if (burst > 0 && bytes != null) {
                        main.post {
                            grabBurst(burst) { list ->
                                synchronized(lock) { extras = list }
                                // Ya se puede mover: lo que sigue es procesar
                                holdEnd(processing = true, note = processingNote(currentMode, dark, lens))
                                finishOne()
                            }
                        }
                    } else {
                        if (pre == null) main.post {
                            holdEnd(processing = true, note = if (bytes != null) processingNote(currentMode, dark, lens) else null)
                        }
                        finishOne()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Error tomando la foto", exception)
                    if (screenLit) main.post { hideScreenFlash() }
                    if (pre == null) main.post { holdEnd(processing = true) }
                    photoFile.delete()
                    finishOne()
                }
            }
        )
    }

    /** Guarda lo que salió de la cámara según el modo (en segundo plano). */
    private fun saveFinished(
        currentMode: Mode, p: ByteArray?, c: MotionRecorder.Clip?, ex: List<ByteArray>, live: Boolean,
        front: Boolean, currentFilter: PhotoFilter, dark: Boolean, aim: Float, maxG: Float, vivid: Boolean = false,
        lens: Int = Fisheye.OFF,
    ) {
        saveExecutor.execute {
            run {
                when (currentMode) {
                    Mode.INSTANT -> saveInstant(p, dark, aim, maxG, vivid)
                    Mode.PORTRAIT -> saveProcessed(p, "_RETRATO") {
                        Processing.portrait(Enhance.jpeg(this, it, night = dark, aim = aim, maxGain = maxG, vivid = vivid), currentFilter)
                    }
                    Mode.EFFECTS -> saveEffectPhoto(p, front)
                    Mode.SUNSET -> saveProcessed(p, "_ATARDECER") {
                        MotionPhotoWriter.applyFilter(Enhance.jpeg(this, it, sunset = true, vivid = vivid), currentFilter)
                    }
                    Mode.NIGHT_VISION -> saveProcessed(p, "_NOCTURNA") {
                        val b = Processing.decode(Enhance.jpeg(this, it, night = true), 4096)
                        val nv = InstantFx.nightVision(b)
                        b.recycle()
                        Processing.encode(nv, 94).also { nv.recycle() }
                    }
                    Mode.NIGHT -> saveProcessed(p, "_NOCHE") {
                        val base = mergeNight(it, ex)
                        when {
                            Enhance.enabled(this) -> MotionPhotoWriter.applyFilter(
                                Enhance.jpeg(this, base, night = true, aim = aim, maxGain = maxG, vivid = vivid), currentFilter)
                            // Mejora apagada con Viva: la noche simple y encima solo Viva
                            vivid -> MotionPhotoWriter.applyFilter(Enhance.jpeg(
                                this, Processing.night(base, PhotoFilter.ORIGINAL), night = true, vivid = true, auto = false,
                            ), currentFilter)
                            else -> Processing.night(base, currentFilter)
                        }
                    }
                    else -> saveResult(p, c, live, currentFilter, dark, aim, maxG, ex, vivid, lens)
                }
            }
        }
    }

    // ---------- Foto al apoyar el dedo ----------

    /**
     * En FOTO la foto se pide al APOYAR el dedo (o al presionar el botón de volumen), no al
     * soltarlo: ese es el instante que uno quiere. Con el disparo sin retraso sale justo de ese
     * cuadro. Si el toque termina siendo video (mantener) o ráfaga (deslizar), se descarta.
     */
    private class PreShot(val live: Boolean) {
        private var state = 0
        private var save: (() -> Unit)? = null
        private var drop: (() -> Unit)? = null

        @Synchronized
        fun ready(onSave: () -> Unit, onDrop: () -> Unit) {
            when (state) {
                1 -> onSave()
                -1 -> onDrop()
                else -> {
                    save = onSave
                    drop = onDrop
                }
            }
        }

        @Synchronized
        fun decide(go: Boolean) {
            if (state != 0) return
            state = if (go) 1 else -1
            if (go) save?.invoke() else drop?.invoke()
            save = null
            drop = null
        }
    }

    private var preShot: PreShot? = null

    private fun ledFlashFires() = !front && (flashMode == "on" || (flashMode == "auto" && lowLight))

    /** Solo en FOTO normal: sin temporizador, sin flash y sin la noche de varias fotos. */
    private fun canPreShoot() = mode == Mode.PHOTO && timerSecs == 0 && !countingDown && !busy &&
        recording == null && !bursting && !quickTaking && imageCapture != null &&
        developOverlay.visibility != View.VISIBLE && !wantsScreenFlash() && !ledFlashFires() &&
        !(lowLight && nightAuto) && hdrPlan() == null

    private fun startPreShot() {
        if (!canPreShoot()) return
        val p = PreShot(liveActive)
        preShot = p
        doTakeStill(pre = p)
    }

    /** Fue un toque: se guarda la foto del instante en que se apoyó el dedo. */
    private fun commitPreShot(): Boolean {
        val p = preShot ?: return false
        preShot = null
        if (p.live) {
            livePill.visibility = View.VISIBLE
            livePill.alpha = 1f
        }
        blink(sound = !p.live)
        p.decide(true)
        return true
    }

    /** Terminó siendo video o ráfaga: esa foto no se guarda. */
    private fun dropPreShot() {
        val p = preShot ?: return
        preShot = null
        p.decide(false)
    }

    // ---------- Paneles: uno a la vez, se cierran al tocar de nuevo o fuera ----------

    /** Cierra lo que esté abierto (filtros, belleza, brillo). Devuelve si había algo abierto. */
    private fun closePanels(except: View? = null): Boolean {
        var closed = false
        if (except !== filterBar && filterBar.visibility == View.VISIBLE) {
            hideFilterBar()
            closed = true
        }
        if (except !== beautyPanel && beautyPanel.visibility == View.VISIBLE) {
            beautyPanel.visibility = View.GONE
            closed = true
        }
        if (except !== evDial && evDial.visibility == View.VISIBLE) {
            hideDial()
            closed = true
        }
        return closed
    }

    private fun hideFilterBar() {
        main.removeCallbacks(filterThumbTick)
        filterBar.visibility = View.GONE
    }

    /** Miniaturas de filtros en vivo mientras la barra está abierta (no una foto quieta). */
    private val filterThumbTick = object : Runnable {
        override fun run() {
            if (filterBar.visibility != View.VISIBLE) return
            refreshFilterThumbs()
            main.postDelayed(this, if (mode == Mode.INSTANT) 650L else 250L)
        }
    }
    private var thumbsBusy = false

    private fun readCapturedJpeg(file: File): ByteArray? = try {
        file.readBytes().takeIf { it.isNotEmpty() }
    } catch (e: java.io.IOException) {
        Log.e(TAG, "No se pudo leer la foto temporal", e)
        null
    } finally {
        file.delete()
    }

    /** Una foto a memoria para capturas de varias tomas. */
    private fun grab(onStarted: (() -> Unit)? = null, onDone: (ByteArray?) -> Unit) {
        val ic = imageCapture ?: return onDone(null)
        val f = File(cacheDir, "grab_${System.nanoTime()}.jpg")
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(f).setMetadata(selfieMeta()).build(), ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    onStarted?.let { cb -> main.post { cb() } }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onDone(readCapturedJpeg(f))
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "No se pudo tomar una foto de la secuencia", exception)
                    f.delete()
                    onDone(null)
                }
            })
    }

    /** Cuántas fotos extra tomar de noche para juntarlas (0 = una sola, como siempre). */
    private fun nightBurstCount(m: Mode, screenLit: Boolean): Int {
        // Con flash la escena ya tiene luz (y dispararlo varias veces sería feo)
        if (screenLit) return 0
        if (!front && (flashMode == "on" || (flashMode == "auto" && lowLight))) return 0
        // A 200 MP no cabe en memoria ni vale la pena la espera
        if (CameraSettings.get(this, CameraSettings.PHOTO, "12") == "max") return 0
        return when {
            m == Mode.NIGHT -> 4
            m == Mode.PHOTO && lowLight && nightAuto -> 3
            else -> 0
        }
    }

    /** Fotos extra, una tras otra, con la misma exposición. */
    private fun grabBurst(count: Int, done: (List<ByteArray>) -> Unit) {
        val shots = ArrayList<ByteArray>()
        fun next() {
            if (shots.size >= count) {
                done(shots)
                return
            }
            grab(onStarted = { shotTaken(sound = false) }) { b ->
                runOnUiThread {
                    if (b == null) done(shots) else {
                        shots += b
                        next()
                    }
                }
            }
        }
        next()
    }

    /**
     * Junta la foto con las extras (alineadas y promediadas, sin fantasmas). Si algo falla
     * o no hay extras, devuelve la foto tal cual.
     */
    /** Foto gigante (modo MÁX): se deja con todo su detalle, sin juntar ni mejorar. */
    private fun isHuge(jpeg: ByteArray): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return o.outWidth.toLong() * o.outHeight > 17_000_000L
    }

    private fun mergeNight(photo: ByteArray, extras: List<ByteArray>): ByteArray {
        if (extras.isEmpty() || isHuge(photo)) return photo
        val bmp = mergeNightBitmap(photo, extras) ?: return photo
        return Processing.encode(bmp, 97).also { bmp.recycle() }
    }

    /**
     * Junta las fotos de noche y devuelve la imagen (mutable, lista para mejorar), o null si no se
     * pudo. Tope de 4096 px: la frontal de 16 MP queda en 12 MP (a ISO 6000 no hay más detalle
     * de verdad) y tarda bastante menos en un cel como este.
     */
    private fun mergeNightBitmap(photo: ByteArray, extras: List<ByteArray>): Bitmap? = if (extras.isEmpty()) null else try {
        val t0 = System.currentTimeMillis()
        // Con poca memoria se junta un poco más pequeña (igual queda mejor que con grano)
        val maxSide = if (Runtime.getRuntime().maxMemory() >= 400L * 1024 * 1024) 4096 else 3000
        val refBmp = Processing.decode(photo, maxSide)
        val w = refBmp.width
        val h = refBmp.height
        val ref = IntArray(w * h)
        refBmp.getPixels(ref, 0, w, 0, 0, w, h)
        refBmp.recycle()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val st = NightStack(ref, w, h, threads)
        for (b in extras) {
            val bmp = Processing.decode(b, maxSide)
            if (bmp.width == w && bmp.height == h) {
                st.add { y, row -> bmp.getPixels(row, 0, w, 0, y, w, 1) }
            }
            bmp.recycle()
        }
        val out = st.result()
        Log.d(TAG, "Noche: ${extras.size + 1} fotos, usado ${st.used} ${st.notes}, ${System.currentTimeMillis() - t0} ms")
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    } catch (e: Throwable) {
        Log.e(TAG, "No se pudieron juntar las fotos de noche", e)
        null
    }

    private fun saveResult(
        photo: ByteArray?, clip: MotionRecorder.Clip?, live: Boolean, f: PhotoFilter, dark: Boolean = false,
        aim: Float = 0f, maxG: Float = 1f, extras: List<ByteArray> = emptyList(), vivid: Boolean = false,
        lens: Int = Fisheye.OFF,
    ) {
        if (photo == null) {
            finishSave("No se pudo tomar la foto")
            return
        }
        val improved = try {
            when {
                (dark || extras.isNotEmpty()) && !isHuge(photo) -> {
                    val base = mergeNightBitmap(photo, extras) ?: Processing.decode(photo, 4096)
                    val jpeg = try {
                        // El ojo de pez va después de la mejora y antes del filtro (como la vista previa)
                        val bmp = Lens.apply(Enhance.bitmap(this, base, night = dark, aim = aim, maxGain = maxG, vivid = vivid), lens)
                        try {
                            Processing.encode(bmp, 95)
                        } finally {
                            if (bmp !== base) bmp.recycle()
                        }
                    } finally {
                        base.recycle()
                    }
                    MotionPhotoWriter.applyFilter(jpeg, f)
                }
                dark || extras.isNotEmpty() -> Lens.jpeg(photo, lens)
                Enhance.enabled(this) || f != PhotoFilter.ORIGINAL || vivid -> {
                    val jpeg = Enhance.jpeg(this, photo, night = dark, aim = aim, maxGain = maxG, vivid = vivid, lens = lens)
                    MotionPhotoWriter.applyFilter(jpeg, f)
                }
                else -> Lens.jpeg(photo, lens)
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mejorar la foto", e)
            photo
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "No hubo memoria para mejorar la foto", e)
            photo
        }

        if (clip != null) {
            val savedMotion = try {
                val mp4 = MotionPhotoWriter.muxToMp4(clip, cacheDir)
                val xmp = MotionPhotoWriter.buildXmp(mp4.bytes.size, mp4.presentationUs, f)
                MotionPhotoWriter.save(this, MotionPhotoWriter.insertXmp(improved, xmp), mp4.bytes)
                true
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo guardar el movimiento", e)
                false
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "No hubo memoria para guardar el movimiento", e)
                false
            }
            if (savedMotion) {
                finishSave(null)
                return
            }
        }

        val message = try {
            MotionPhotoWriter.save(this, improved, null)
            if (live) "Se guardó sin movimiento" else null
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar la foto", e)
            if (improved === photo) "No se pudo guardar la foto" else saveOriginalPhoto(photo)
        }
        finishSave(message)
    }

    private fun saveInstant(photo: ByteArray?, dark: Boolean = false, aim: Float = 0f, maxG: Float = 1f, vivid: Boolean = false) {
        if (photo == null) {
            finishSave("No se pudo tomar la foto")
            return
        }
        try {
            val src = Processing.decode(photo, 1600)
            if (Enhance.enabled(this) || dark || vivid) Enhance.bitmap(this, src, night = dark, aim = aim, maxGain = maxG, vivid = vivid)
            val bmp = InstantStyle.render(listOf(src), instantLook, instantShape, instantFrame, caption)
            src.recycle()
            val uri = MotionPhotoWriter.save(this, Processing.encode(bmp, 93), null, "_INSTANT")
            runOnUiThread { showResult(bmp, uri, "image/jpeg") }
            finishSave(null)
        } catch (e: Throwable) {
            Log.e(TAG, "Error en instantánea", e)
            finishSave(saveOriginalPhoto(photo))
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
            saveOriginalPhoto(photo)
        }
        finishSave(msg)
    }

    private fun saveOriginalPhoto(photo: ByteArray): String = try {
        MotionPhotoWriter.save(this, photo, null)
        "Se guardó la foto original"
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo guardar la foto original", e)
        "No se pudo guardar la foto"
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
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x66FFFFFF)
            setBackgroundResource(R.drawable.bg_segmented)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        Sheet.custom(this, "Texto de la instantánea", input, listOf(
            "Listo" to {
                caption = input.text.toString().trim()
                prefs.edit().putString(KEY_CAPTION, caption).apply()
                captionPill.text = caption.ifBlank { "Toca para escribir algo" }
            },
            "Borrar" to {
                caption = ""
                prefs.edit().putString(KEY_CAPTION, "").apply()
                captionPill.text = "Toca para escribir algo"
            },
        ), primary = 0)
        input.requestFocus()
        input.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 250)
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
        startReveal()
    }

    // ---------- Revelado: sale pálida y aparece sola, o más rápido si sacudes el cel ----------

    private var revealProgress = 1f
    private var sensors: android.hardware.SensorManager? = null

    private val shake = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val g = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            val jolt = abs(g - 9.81f)
            if (jolt > 5f) revealProgress = minOf(1f, revealProgress + minOf(0.1f, jolt / 140f))
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private val revealTick = object : Runnable {
        override fun run() {
            revealProgress = minOf(1f, revealProgress + 0.008f) // sola tarda ~6 s
            val t = revealProgress * revealProgress * (3 - 2 * revealProgress)
            developImage.colorFilter = android.graphics.ColorMatrixColorFilter(revealMatrix(t))
            if (revealProgress >= 1f) {
                developImage.clearColorFilter()
                developHint.animate().alpha(0f).setDuration(300).start()
                stopReveal()
            } else main.postDelayed(this, 50)
        }
    }

    /** Mezcla entre "sin revelar" (pálida, casi crema) y la foto normal. */
    private fun revealMatrix(t: Float): android.graphics.ColorMatrix {
        val k = 0.16f
        val cream = floatArrayOf(0.93f, 0.92f, 0.88f)
        val m = FloatArray(20)
        for (row in 0 until 3) {
            val base = row * 5
            val luma = floatArrayOf(0.299f, 0.587f, 0.114f)
            for (col in 0 until 3) {
                val start = k * luma[col]
                val end = if (col == row) 1f else 0f
                m[base + col] = start + (end - start) * t
            }
            m[base + 4] = (1 - k) * cream[row] * 255f * (1 - t)
        }
        m[18] = 1f
        return android.graphics.ColorMatrix(m)
    }

    private fun startReveal() {
        revealProgress = 0f
        developHint.alpha = 1f
        developHint.visibility = View.VISIBLE
        sensors = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
        sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sensors?.registerListener(shake, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        main.removeCallbacks(revealTick)
        main.post(revealTick)
    }

    private fun stopReveal() {
        main.removeCallbacks(revealTick)
        sensors?.unregisterListener(shake)
        sensors = null
    }

    // ---------- Boomerang ----------

    private var boomFrames: Frames? = null
    private val boomAnimator by lazy { FrameAnimator(developImage) }

    private fun updateFlashIcon() {
        flashButton.visibility = if (mode == Mode.EFFECTS && !front) View.GONE else View.VISIBLE
        flashButton.setImageResource(when (flashMode) {
            "auto" -> R.drawable.ic_flash_auto
            "on" -> R.drawable.ic_flash_on
            "torch" -> R.drawable.ic_flash_torch
            else -> R.drawable.ic_flash_off
        })
        flashButton.setColorFilter(if (flashMode == "off") 0xFFFFFFFF.toInt() else 0xFFFFD54F.toInt())
    }

    /** Flash de verdad atrás; adelante, la pantalla. Linterna = luz fija (sirve para video y visión nocturna). */
    private fun applyFlash() {
        val back = !front
        imageCapture?.flashMode = when {
            !back -> ImageCapture.FLASH_MODE_OFF
            flashMode == "auto" -> ImageCapture.FLASH_MODE_AUTO
            flashMode == "on" -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        val torch = back && (flashMode == "torch" || (flashMode == "on" && mode == Mode.VIDEO))
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) camera?.cameraControl?.enableTorch(torch)
        } catch (_: Exception) {
        }
        // Adelante no hay LED: la "linterna" es un aro de luz en la pantalla
        val ring = !back && flashMode == "torch"
        if (ring) {
            ringLight.color = flashColor
            val l = previewBox.left.toFloat()
            val t = previewBox.top.toFloat()
            val bw = previewBox.width.toFloat()
            val bh = previewBox.height.toFloat()
            val inset = bw * 0.12f
            ringLight.hole = RectF(l + inset, t + inset, l + bw - inset, t + bh - inset * 1.4f)
            ringLight.visibility = View.VISIBLE
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        } else if (ringLight.visibility == View.VISIBLE) {
            ringLight.visibility = View.GONE
            window.attributes = window.attributes.apply {
                screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        updateFlashIcon()
    }

    private fun showFlashMenu() {
        val modes = listOf(
            Triple("off", "Apagado", R.drawable.ic_flash_off),
            Triple("auto", "Automático", R.drawable.ic_flash_auto),
            Triple("on", "Encendido", R.drawable.ic_flash_on),
            Triple("torch", "Linterna (luz fija)", R.drawable.ic_flash_torch),
        )
        val items = modes.map { (id, label, icon) ->
            Sheet.Item(label, icon, checked = flashMode == id) {
                flashMode = id
                prefs.edit().putString(KEY_FLASH, id).apply()
                applyFlash()
                if (id == "torch" && front) showToast("Adelante la linterna es un aro de luz en la pantalla")
            }
        } + listOf(
            Sheet.Item("Color del flash de pantalla", R.drawable.ic_filters, subtitle = "Cámara frontal") { showFlashColors() },
            Sheet.Item("Forma del flash", R.drawable.ic_flash_on, subtitle = "Se refleja en tus ojos") { showFlashShapes() },
        )
        Sheet.actions(this, "Flash", items,
            subtitle = if (front) "Adelante la pantalla hace de flash" else "Atrás usa el flash del cel")
    }

    private fun previewFlash() {
        if (flashMode == "off") {
            flashMode = "on"
            prefs.edit().putString(KEY_FLASH, "on").apply()
        }
        applyFlash()
        showScreenFlash()
        main.postDelayed({ hideScreenFlash() }, 450)
    }

    private fun showFlashColors() {
        val colors = listOf(
            "Blanco" to 0xFFFFFFFF.toInt(), "Cálido" to 0xFFFFE2B8.toInt(), "Rosa" to 0xFFFF9EC7.toInt(),
            "Morado" to 0xFFC9A2FF.toInt(), "Azul" to 0xFF9CCBFF.toInt(), "Verde" to 0xFFB4F5C2.toInt(),
            "Atardecer" to 0xFFFFB27A.toInt(),
        )
        Sheet.actions(this, "Color del flash", colors.map { (label, color) ->
            Sheet.Item(label, R.drawable.ic_flash_on, checked = flashColor == color) {
                flashColor = color
                prefs.edit().putInt(KEY_FLASH_COLOR, color).apply()
                previewFlash()
            }
        })
    }

    private fun showFlashShapes() {
        val shapes = listOf(
            "full" to "Pantalla completa", "ring" to "Aro (brillo redondo en los ojos)",
            "circle" to "Círculo", "heart" to "Corazón", "star" to "Estrella",
        )
        Sheet.actions(this, "Forma del flash", shapes.map { (id, label) ->
            Sheet.Item(label, R.drawable.ic_flash_on, checked = flashShape == id) {
                flashShape = id
                prefs.edit().putString(KEY_FLASH_SHAPE, id).apply()
                previewFlash()
            }
        }, subtitle = "La forma se refleja como brillito en los ojos")
    }

    private fun showBoomMenu() {
        val kinds = listOf(
            Triple("photo", "Foto", R.drawable.ic_photo),
            Triple("normal", "Boomerang", R.drawable.ic_bounce),
            Triple("slow", "Boomerang lento", R.drawable.ic_bounce),
            Triple("echo", "Boomerang eco", R.drawable.ic_long_exposure),
        )
        Sheet.actions(this, "Tipo de instantánea", kinds.map { (id, label, icon) ->
            Sheet.Item(label, icon, checked = boomKind == id) {
                boomKind = id
                prefs.edit().putString(KEY_BOOM, id).apply()
                applyModeUi()
            }
        }, subtitle = "El boomerang graba un segundo y lo repite de ida y vuelta")
    }

    private fun captureBoomerang() {
        if (busy) return
        busy = true
        filterBar.visibility = View.GONE
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        shutter.setBackgroundResource(R.drawable.shutter_rec)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "∞"
        recorder.enabled = true
        recorder.startLong()
        main.postDelayed({
            recorder.stopLong { clip ->
                runOnUiThread {
                    shutter.setBackgroundResource(R.drawable.shutter)
                    recTimer.visibility = View.GONE
                    recorder.enabled = liveActive
                    busy = false
                    processingStart("Armando el boomerang…")
                }
                saveExecutor.execute { makeBoomerang(clip, boomKind) }
            }
        }, 1300)
    }

    private fun makeBoomerang(clip: MotionRecorder.Clip?, kind: String) {
        if (clip == null) {
            finishSave("No se pudo grabar el boomerang")
            return
        }
        try {
            val mp4 = MotionPhotoWriter.muxToMp4(clip, cacheDir)
            val src = File(cacheDir, "boom_src.mp4")
            src.writeBytes(mp4.bytes)
            val frames = Frames.load(src, maxSide = 640, maxFrames = 30) ?: throw IllegalStateException("No salieron fotogramas")
            src.delete()
            val styled = frames.bitmaps.map {
                InstantStyle.render(listOf(it), instantLook, instantShape, instantFrame, caption, side = 560)
            }
            frames.recycle()
            val fps = if (kind == "slow") 15f else 30f
            val n = styled.size
            val once = (0 until n) + (n - 2 downTo 1)
            val seq = (0 until 3).flatMap { once }
            val out = File(cacheDir, "boomerang.mp4")
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val ghost = Paint(Paint.FILTER_BITMAP_FLAG)
            Exporter.renderMp4(styled[0].width, styled[0].height, fps, seq.size, out) { c, i, dst ->
                c.drawBitmap(styled[seq[i]], null, dst, paint)
                if (kind == "echo") {
                    // Rastro: los cuadros anteriores encima, cada vez más transparentes
                    for ((back, a) in listOf(2 to 110, 4 to 70, 6 to 40)) {
                        if (i - back < 0) continue
                        ghost.alpha = a
                        c.drawBitmap(styled[seq[i - back]], null, dst, ghost)
                    }
                }
            }
            val uri = Exporter.saveVideoFile(this, out, "_BOOMERANG")
            out.delete()
            val anim = Frames(styled, fps)
            runOnUiThread {
                showResult(styled[0], uri, "video/mp4")
                boomFrames?.recycle()
                boomFrames = anim
                boomAnimator.start(anim, true)
            }
            finishSave(null)
        } catch (e: Throwable) {
            Log.e(TAG, "Boomerang", e)
            finishSave("Error en el boomerang: ${e.message}")
        }
    }

    // ---------- Fotomatón: 4 fotos seguidas con cuenta regresiva ----------

    private fun captureBooth() {
        if (busy || imageCapture == null) return
        busy = true
        filterBar.visibility = View.GONE
        val shots = ArrayList<ByteArray>()
        val vivid = vividActive()
        fun next(i: Int) {
            if (i == 4) {
                busy = false
                processingStart("Armando el fotomatón…")
                saveExecutor.execute { saveBooth(shots, vivid) }
                return
            }
            var left = if (i == 0) 3 else 2
            countdown.visibility = View.VISIBLE
            val tick = object : Runnable {
                override fun run() {
                    if (left > 0) {
                        countdown.text = left.toString()
                        countdown.alpha = 1f
                        countdown.scaleX = 1.3f
                        countdown.scaleY = 1.3f
                        countdown.animate().scaleX(1f).scaleY(1f).alpha(0.6f).setDuration(550).start()
                        shutter.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        left--
                        main.postDelayed(this, 650)
                    } else {
                        countdown.text = "${i + 1}/4"
                        grab(onStarted = { blink(sound = true) }) { b ->
                            runOnUiThread {
                                if (b != null) shots += b
                                if (i == 3) countdown.visibility = View.GONE
                                next(i + 1)
                            }
                        }
                    }
                }
            }
            main.post(tick)
        }
        next(0)
    }

    private fun saveBooth(shots: List<ByteArray>, vivid: Boolean = false) {
        if (shots.isEmpty()) {
            finishSave("No se pudo tomar el fotomatón")
            return
        }
        try {
            val photos = shots.map { b ->
                Processing.decode(b, 1200).also {
                    if (Enhance.enabled(this) || vivid) Enhance.bitmap(this, it, vivid = vivid)
                }
            }
            val bmp = InstantStyle.render(photos, instantLook, instantShape, InstantFrame.BOOTH, caption)
            photos.forEach { it.recycle() }
            val uri = MotionPhotoWriter.save(this, Processing.encode(bmp, 93), null, "_FOTOMATON")
            runOnUiThread { showResult(bmp, uri, "image/jpeg") }
            finishSave(null)
        } catch (e: Throwable) {
            Log.e(TAG, "Fotomatón", e)
            finishSave(saveOriginalPhoto(shots.first()))
        }
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
        updateShutterDescription()
        busy = true
        timelapseStart = System.currentTimeMillis()
        recorder.timelapseStep = 15
        recorder.enabled = true
        recorder.startLong()
        playSound(MediaActionSound.START_VIDEO_RECORDING)
        shutter.setBackgroundResource(R.drawable.shutter_stop)
        recTimer.visibility = View.VISIBLE
        recTimer.text = "0:00"
        main.post(timelapseTick)
    }

    private fun stopTimelapse() {
        if (!timelapsing) return
        timelapsing = false
        updateShutterDescription()
        main.removeCallbacks(timelapseTick)
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        shutter.setBackgroundResource(R.drawable.shutter_rec)
        recTimer.visibility = View.GONE
        processingStart("Guardando time-lapse…")
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
                    busy = false
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
    /** El analizador entrega el próximo cuadro para buscar un QR (ver [qrPeek]). */
    @Volatile private var qrWant = false
    private var qrLastAna = -1
    private var qrTicks = 0

    private val qrScan = object : Runnable {
        override fun run() {
            main.postDelayed(this, 800)
            if (qrBusy || busy || (mode != Mode.PHOTO && mode != Mode.PORTRAIT && mode != Mode.NIGHT && mode != Mode.NIGHT_VISION)) return
            // En FOTO se lee del cuadro que ya llega para el Live: así no se le pide una copia de
            // la pantalla a la GPU (eso frenaba la vista previa un instante cada vez)
            val ana = recorder.analyzedFrames
            val analysisAlive = ana != qrLastAna
            qrLastAna = ana
            if (mode == Mode.PHOTO && analysisAlive) {
                qrWant = true
                return
            }
            // Los demás modos no tienen análisis: copia pequeña de la pantalla, cada 1.6 s
            if (qrTicks++ % 2 != 0) return
            val tv = findTexture(previewView) ?: return
            if (tv.width <= 0 || tv.height <= 0) return
            val qw = 360
            val small = try { tv.getBitmap(qw, qw * tv.height / tv.width) } catch (e: Exception) { null } ?: return
            scanQr(com.google.mlkit.vision.common.InputImage.fromBitmap(small, 0))
        }
    }

    /**
     * En el hilo del análisis: copia la luz (Y) del cuadro a la mitad de tamaño, de sobra para un
     * QR, sin tocar el cuadro que sigue al video. El color va gris (al lector no le importa).
     */
    private val qrPeek: (androidx.camera.core.ImageProxy) -> Unit = peek@{ image ->
        if (!qrWant) return@peek
        qrWant = false
        val plane = image.planes[0]
        val sw = image.width / 2
        val sh = image.height / 2
        if (sw < 16 || sh < 16) return@peek
        val buf = plane.buffer.duplicate()
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val out = ByteArray(sw * sh * 3 / 2)
        val row = ByteArray(rs)
        for (j in 0 until sh) {
            val start = 2 * j * rs
            if (start >= buf.limit()) break
            buf.position(start)
            val len = minOf(rs, buf.remaining())
            buf.get(row, 0, len)
            var o = j * sw
            var x = 0
            while (x < sw) {
                val k = 2 * x * ps
                if (k < len) out[o] = row[k]
                o++
                x++
            }
        }
        java.util.Arrays.fill(out, sw * sh, out.size, 128.toByte())
        val rot = image.imageInfo.rotationDegrees
        main.post {
            scanQr(com.google.mlkit.vision.common.InputImage.fromByteArray(
                out, sw, sh, rot, com.google.mlkit.vision.common.InputImage.IMAGE_FORMAT_NV21))
        }
    }

    private fun scanQr(img: com.google.mlkit.vision.common.InputImage) {
        if (qrBusy || busy) return
        qrBusy = true
        scanner.process(img)
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

    /** Recorta marcos lisos (Polaroid, negativo…) y deja un cuadrado centrado para la bolita. */
    private fun roundThumb(orig: Bitmap?): Bitmap? = try {
        trimThumb(orig)
    } catch (e: Exception) {
        orig
    }

    private fun trimThumb(orig: Bitmap?): Bitmap? {
        orig ?: return null
        val src = if (orig.config == Bitmap.Config.HARDWARE) orig.copy(Bitmap.Config.ARGB_8888, false) else orig
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        fun plainRow(y: Int): Boolean {
            val c0 = px[y * w]
            for (x in 0 until w step 3) if (diff(px[y * w + x], c0) > 40) return false
            return true
        }
        fun plainCol(x: Int): Boolean {
            val c0 = px[x]
            for (y in 0 until h step 3) if (diff(px[y * w + x], c0) > 40) return false
            return true
        }
        var top = 0; var bottom = h - 1; var left = 0; var right = w - 1
        while (top < h / 3 && plainRow(top)) top++
        while (bottom > h * 2 / 3 && plainRow(bottom)) bottom--
        while (left < w / 3 && plainCol(left)) left++
        while (right > w * 2 / 3 && plainCol(right)) right--
        val cw = right - left + 1
        val ch = bottom - top + 1
        val side = minOf(cw, ch)
        return Bitmap.createBitmap(src, left + (cw - side) / 2, top + (ch - side) / 2, side, side)
    }

    private fun diff(a: Int, b: Int): Int =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))

    private fun finishSave(message: String?) {
        val thumb = try {
            roundThumb(MediaRepo.list(this, onlyOwn = true).firstOrNull()
                ?.let { MediaRepo.thumbnail(this, it.uri, 320) })
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo actualizar la miniatura", e)
            null
        }
        runOnUiThread {
            processingEnd()
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
        stopReveal()
        developHint.visibility = View.GONE
        boomAnimator.stop()
        boomFrames?.let { f -> main.postDelayed({ f.recycle() }, 400) }
        boomFrames = null
        developImage.clearColorFilter()
        developOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            developOverlay.visibility = View.GONE
            developImage.setImageDrawable(null)
            if (mode == Mode.INSTANT) captionPill.visibility = View.VISIBLE
        }.start()
    }

    private fun refreshThumbnail() {
        ioExecutor.execute {
            val thumb = roundThumb(MediaRepo.list(this, onlyOwn = true).firstOrNull()
                ?.let { MediaRepo.thumbnail(this, it.uri, 320) })
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
        saveExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Camaratrix"
        private const val KEY_LIVE = "live_on"
        private const val KEY_ZSL_BROKEN = "zsl_broken"
        private const val KEY_FILTER = "filter"
        private const val KEY_MODE = "mode"
        private const val KEY_TIMER = "timer"
        private const val KEY_GRID = "grid"
        private const val KEY_CAPTION = "caption"
        private const val KEY_EFFECT = "effect"
        private const val KEY_LOOK = "instant_look"
        private const val KEY_SHAPE = "instant_shape"
        private const val KEY_FRAME = "instant_frame"
        private const val KEY_BOOM = "instant_boom"
        private const val KEY_FLASH = "flash_mode"
        private const val KEY_FLASH_COLOR = "flash_color"
        private const val KEY_FLASH_SHAPE = "flash_shape"
        private const val MAX_QUICKTAKE_S = 60
    }
}
