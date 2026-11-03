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

    enum class Mode { VIDEO, PHOTO, INSTANT }

    private lateinit var previewView: PreviewView
    private lateinit var shutter: ImageButton
    private lateinit var flip: ImageButton
    private lateinit var liveToggle: ImageButton
    private lateinit var filterButton: ImageButton
    private lateinit var gridButton: ImageButton
    private lateinit var timerButton: TextView
    private lateinit var livePill: TextView
    private lateinit var modeVideo: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeInstant: TextView
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
        livePill = findViewById(R.id.livePill)
        modeVideo = findViewById(R.id.modeVideo)
        modePhoto = findViewById(R.id.modePhoto)
        modeInstant = findViewById(R.id.modeInstant)
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
            filterBar.visibility = if (filterBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
        modeVideo.setOnClickListener { setMode(Mode.VIDEO) }
        modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        modeInstant.setOnClickListener { setMode(Mode.INSTANT) }
        flip.setOnClickListener {
            if (busy || recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            it.animate().rotationBy(180f).setDuration(300).start()
            bindCamera()
        }
        thumbnail.setOnClickListener {
            if (recording == null) startActivity(Intent(this, ViewerActivity::class.java))
        }
        developOverlay.setOnClickListener { hideDevelop() }

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { has(it) }) startCamera() else permissionLauncher.launch(needed)
    }

    override fun onResume() {
        super.onResume()
        updateAudio()
        refreshThumbnail()
    }

    override fun onPause() {
        super.onPause()
        if (quickTaking) stopQuickTake()
        bursting = false
        cancelCountdown()
        recording?.stop()
        audio.stop()
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private val liveActive get() = liveOn && mode == Mode.PHOTO

    // ---------- Modos, filtros, temporizador, cuadrícula ----------

    private fun setMode(m: Mode) {
        if (mode == m || busy || recording != null || quickTaking) return
        val wasVideo = mode == Mode.VIDEO
        mode = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
        tap(modePhoto)
        applyModeUi()
        if (wasVideo || m == Mode.VIDEO) bindCamera()
    }

    private fun applyModeUi() {
        val green = 0xFF00E676.toInt()
        val white = 0xCCFFFFFF.toInt()
        modeVideo.setTextColor(if (mode == Mode.VIDEO) green else white)
        modePhoto.setTextColor(if (mode == Mode.PHOTO) green else white)
        modeInstant.setTextColor(if (mode == Mode.INSTANT) green else white)

        liveToggle.visibility = if (mode == Mode.PHOTO) View.VISIBLE else View.INVISIBLE
        liveToggle.setImageResource(if (liveOn) R.drawable.ic_live_on else R.drawable.ic_live_off)
        filterButton.visibility = if (mode == Mode.VIDEO) View.INVISIBLE else View.VISIBLE
        if (mode == Mode.VIDEO) filterBar.visibility = View.GONE
        shutter.setBackgroundResource(if (mode == Mode.VIDEO) R.drawable.shutter_rec else R.drawable.shutter)
        recorder.enabled = liveActive
        updateAudio()
        applyPreviewFilter()

        // Guías de recorte cuadrado para la instantánea
        val show = mode == Mode.INSTANT
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

    private fun buildFilterChips() {
        filterList.removeAllViews()
        val pad = dp(14)
        for (f in PhotoFilter.entries) {
            val chip = TextView(this).apply {
                text = f.label
                textSize = 13f
                setPadding(pad, dp(7), pad, dp(7))
                setBackgroundResource(R.drawable.bg_filter_chip)
                tag = f
                setOnClickListener { selectFilter(f) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            filterList.addView(chip, lp)
        }
        applyPreviewFilter()
    }

    private fun selectFilter(f: PhotoFilter) {
        filter = f
        prefs.edit().putString(KEY_FILTER, f.id).apply()
        tap(filterButton)
        applyPreviewFilter()
    }

    private fun applyPreviewFilter() {
        val active = if (mode == Mode.VIDEO) PhotoFilter.ORIGINAL else filter
        findTexture(previewView)?.setLayerPaint(
            if (active == PhotoFilter.ORIGINAL) null else Paint().apply { colorFilter = active.colorFilter() }
        )
        for (i in 0 until filterList.childCount) {
            val v = filterList.getChildAt(i) as TextView
            v.isSelected = v.tag == filter
            v.setTextColor(if (v.isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        filterButton.setColorFilter(if (filter == PhotoFilter.ORIGINAL) 0xFFFFFFFF.toInt() else 0xFF00E676.toInt())
    }

    private fun findTexture(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTexture(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun updateAudio() {
        val want = (liveActive || quickTaking) && has(Manifest.permission.RECORD_AUDIO)
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
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP && !scale.isInProgress && ev.eventTime - ev.downTime < 250) {
                focusAt(ev.x, ev.y)
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
            zoom1.text = if (r < 1.95f) String.format(Locale.US, "%.1fx", r).replace(".0x", "x") else "1"
            zoom2.text = if (r >= 1.95f && !near2) String.format(Locale.US, "%.1fx", r) else if (near2) "2x" else "2"
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
            if (mode == Mode.PHOTO && !busy) {
                longStarted = true
                startQuickTake()
            }
        }
        shutter.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    longStarted = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    if (mode == Mode.PHOTO && timerSecs == 0) main.postDelayed(longPress, 400)
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
            if (secs >= MAX_QUICKTAKE_S) {
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
                        Exporter.saveVideoBytes(this, mp4.bytes)
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
        val group = if (mode == Mode.VIDEO) buildVideoGroup() else buildPhotoGroup()
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, group)
            observeZoom()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la cámara", e)
            showToast("Error de cámara: ${e.message}")
        }
    }

    private fun buildPhotoGroup(): UseCaseGroup {
        videoCapture = null
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio43).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ratio43)
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, recorder) }
        imageCapture = capture
        // Mismo encuadre 3:4 para vista previa, foto y video
        return UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build())
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(analysis)
            .build()
    }

    private fun buildVideoGroup(): UseCaseGroup {
        imageCapture = null
        val ratio169 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio169).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val rec = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
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
                if (currentMode == Mode.INSTANT) saveInstant(p, currentFilter)
                else saveResult(p, c, live, currentFilter)
            }
        }

        if (live) {
            recorder.requestClip { result ->
                synchronized(lock) { clip = result }
                runOnUiThread { livePill.animate().alpha(0f).setDuration(200).start() }
                finishOne()
            }
        }

        val photoFile = File(cacheDir, "photo.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
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
            val bmp = MotionPhotoWriter.makeInstant(photo, f)
            runOnUiThread { showDevelop(bmp) }
            MotionPhotoWriter.save(this, MotionPhotoWriter.encodeInstant(bmp), null, "_INST")
            finishSave(null)
        } catch (e: Throwable) {
            Log.e(TAG, "Error en instantánea", e)
            finishSave("Error: ${e.message}")
        }
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

    private fun showDevelop(bmp: Bitmap) {
        developOverlay.visibility = View.VISIBLE
        developOverlay.alpha = 0f
        developOverlay.animate().alpha(1f).setDuration(200).start()
        developImage.setImageBitmap(bmp)
        developImage.translationY = developOverlay.height.toFloat()
        developImage.rotation = -3f
        developImage.animate().translationY(0f).rotation(0f).setDuration(600).start()

        developAnim?.cancel()
        developAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            startDelay = 400
            addUpdateListener {
                val t = it.animatedValue as Float
                val cm = ColorMatrix().apply { setSaturation(t) }
                val b = -120f * (1 - t)
                val k = 0.55f + 0.45f * t
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            k, 0f, 0f, 0f, b + 30f * (1 - t),
                            0f, k, 0f, 0f, b + 30f * (1 - t),
                            0f, 0f, k, 0f, b + 45f * (1 - t),
                            0f, 0f, 0f, 1f, 0f,
                        )
                    )
                )
                developImage.colorFilter = ColorMatrixColorFilter(cm)
            }
            start()
        }
        developOverlay.postDelayed({ hideDevelop() }, 5200)
    }

    private fun hideDevelop() {
        if (developOverlay.visibility != View.VISIBLE) return
        developAnim?.cancel()
        developImage.clearColorFilter()
        developOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            developOverlay.visibility = View.GONE
            developImage.setImageDrawable(null)
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
        private const val MAX_QUICKTAKE_S = 60
    }
}
