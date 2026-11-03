package com.hender.fotoanimada

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaActionSound
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.HapticFeedbackConstants
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
import androidx.camera.core.CameraSelector
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    enum class Mode { PHOTO, INSTANT }

    private lateinit var previewView: PreviewView
    private lateinit var shutter: ImageButton
    private lateinit var flip: ImageButton
    private lateinit var liveToggle: ImageButton
    private lateinit var filterButton: ImageButton
    private lateinit var livePill: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeInstant: TextView
    private lateinit var toast: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var flash: View
    private lateinit var filterBar: HorizontalScrollView
    private lateinit var filterList: LinearLayout
    private lateinit var maskTop: View
    private lateinit var maskBottom: View
    private lateinit var developOverlay: View
    private lateinit var developImage: ImageView

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recorder = MotionRecorder()
    private val audio = AudioRingRecorder()
    private val sound = MediaActionSound()
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var liveOn = true
    private var busy = false
    private var mode = Mode.PHOTO
    private var filter = PhotoFilter.ORIGINAL

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
        livePill = findViewById(R.id.livePill)
        modePhoto = findViewById(R.id.modePhoto)
        modeInstant = findViewById(R.id.modeInstant)
        toast = findViewById(R.id.toast)
        thumbnail = findViewById(R.id.thumbnail)
        flash = findViewById(R.id.flash)
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
        liveOn = prefs.getBoolean(KEY_LIVE, true)
        filter = PhotoFilter.fromId(prefs.getString(KEY_FILTER, null))
        mode = if (prefs.getString(KEY_MODE, null) == Mode.INSTANT.name) Mode.INSTANT else Mode.PHOTO
        recorder.audio = audio
        buildFilterChips()
        applyModeUi()

        shutter.setOnClickListener { capture() }
        liveToggle.setOnClickListener {
            liveOn = !liveOn
            prefs.edit().putBoolean(KEY_LIVE, liveOn).apply()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applyModeUi()
            showToast(if (liveOn) "LIVE activado" else "LIVE desactivado")
        }
        filterButton.setOnClickListener {
            val show = filterBar.visibility != View.VISIBLE
            filterBar.visibility = if (show) View.VISIBLE else View.GONE
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        modeInstant.setOnClickListener { setMode(Mode.INSTANT) }
        flip.setOnClickListener {
            if (busy) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            it.animate().rotationBy(180f).setDuration(300).start()
            bindCamera()
        }
        thumbnail.setOnClickListener {
            startActivity(Intent(this, ViewerActivity::class.java))
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
        audio.stop()
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val liveActive get() = liveOn && mode == Mode.PHOTO

    // ---------- Modos y filtros ----------

    private fun setMode(m: Mode) {
        if (mode == m || busy) return
        mode = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
        modePhoto.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        applyModeUi()
    }

    private fun applyModeUi() {
        val green = 0xFF00E676.toInt()
        val white = 0xCCFFFFFF.toInt()
        modePhoto.setTextColor(if (mode == Mode.PHOTO) green else white)
        modeInstant.setTextColor(if (mode == Mode.INSTANT) green else white)

        liveToggle.visibility = if (mode == Mode.PHOTO) View.VISIBLE else View.INVISIBLE
        liveToggle.setImageResource(if (liveOn) R.drawable.ic_live_on else R.drawable.ic_live_off)
        recorder.enabled = liveActive
        updateAudio()

        // Guías de recorte cuadrado para la instantánea
        val show = mode == Mode.INSTANT
        previewView.post {
            val w = previewView.width
            val photoH = w * 4 / 3
            val band = (photoH - w) / 2
            (maskTop.layoutParams as FrameLayout.LayoutParams).apply { height = band }
            (maskBottom.layoutParams as FrameLayout.LayoutParams).apply {
                height = band
                topMargin = band + w
            }
            maskTop.requestLayout()
            maskBottom.requestLayout()
            maskTop.visibility = if (show) View.VISIBLE else View.GONE
            maskBottom.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun buildFilterChips() {
        filterList.removeAllViews()
        val pad = dp(14)
        for (f in PhotoFilter.entries) {
            val chip = TextView(this).apply {
                text = f.label
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(pad, dp(7), pad, dp(7))
                setBackgroundResource(R.drawable.bg_filter_chip)
                isSelected = f == filter
                tag = f
                setOnClickListener { selectFilter(f) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            filterList.addView(chip, lp)
        }
    }

    private fun selectFilter(f: PhotoFilter) {
        filter = f
        prefs.edit().putString(KEY_FILTER, f.id).apply()
        for (i in 0 until filterList.childCount) {
            val v = filterList.getChildAt(i)
            v.isSelected = v.tag == f
            if (v.isSelected) v.setBackgroundResource(R.drawable.bg_filter_chip)
            (v as TextView).setTextColor(if (v.isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        filterButton.setColorFilter(if (f == PhotoFilter.ORIGINAL) 0xFFFFFFFF.toInt() else 0xFF00E676.toInt())
        filterButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        applyPreviewFilter()
    }

    private fun applyPreviewFilter() {
        val tv = findTexture(previewView) ?: return
        val paint = Paint().apply { colorFilter = filter.colorFilter() }
        tv.setLayerPaint(if (filter == PhotoFilter.ORIGINAL) null else paint)
        // Pintar chips seleccionados con texto negro
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
        if (liveActive && has(Manifest.permission.RECORD_AUDIO)) audio.start() else audio.stop()
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
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(ratio43)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(ratio43)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, recorder) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // Mismo encuadre 3:4 para vista previa, foto y video
        val viewPort = ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build()
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(analysis)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, group)
            imageCapture = capture
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la cámara", e)
            showToast("Error de cámara: ${e.message}")
        }
    }

    private fun capture() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        filterBar.visibility = View.GONE

        // Sensación de cámara: sonido, vibración, destello y botón que se hunde
        sound.play(MediaActionSound.SHUTTER_CLICK)
        shutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        shutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
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

    private fun saveResult(
        photo: ByteArray?,
        clip: MotionRecorder.Clip?,
        live: Boolean,
        f: PhotoFilter,
    ) {
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

        // Empieza oscuro y lavado, y se va revelando
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
    }
}
