package com.hender.fotoanimada

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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

    private lateinit var previewView: PreviewView
    private lateinit var shutter: ImageButton
    private lateinit var flip: ImageButton
    private lateinit var liveToggle: ImageButton
    private lateinit var livePill: TextView
    private lateinit var modeLabel: TextView
    private lateinit var toast: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var flash: View

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recorder = MotionRecorder()
    private val audio = AudioRingRecorder()
    private val sound = MediaActionSound()

    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var liveOn = true
    private var busy = false

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
        livePill = findViewById(R.id.livePill)
        modeLabel = findViewById(R.id.modeLabel)
        toast = findViewById(R.id.toast)
        thumbnail = findViewById(R.id.thumbnail)
        flash = findViewById(R.id.flash)

        sound.load(MediaActionSound.SHUTTER_CLICK)
        liveOn = getPreferences(MODE_PRIVATE).getBoolean(KEY_LIVE, true)
        recorder.audio = audio
        applyLiveUi()

        shutter.setOnClickListener { capture() }
        liveToggle.setOnClickListener {
            liveOn = !liveOn
            getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_LIVE, liveOn).apply()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applyLiveUi()
            updateAudio()
            showToast(if (liveOn) "LIVE activado" else "LIVE desactivado")
        }
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

    private fun applyLiveUi() {
        liveToggle.setImageResource(if (liveOn) R.drawable.ic_live_on else R.drawable.ic_live_off)
        modeLabel.text = if (liveOn) "FOTO · LIVE" else "FOTO"
        modeLabel.setTextColor(if (liveOn) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
        recorder.enabled = liveOn
    }

    private fun updateAudio() {
        if (liveOn && has(Manifest.permission.RECORD_AUDIO)) audio.start() else audio.stop()
    }

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
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, capture, analysis)
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

        // Sensación de cámara: sonido, vibración, destello y botón que se hunde
        sound.play(MediaActionSound.SHUTTER_CLICK)
        shutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        shutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(250).start()

        val live = liveOn
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
                saveResult(p, c, live)
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

    private fun saveResult(photo: ByteArray?, clip: MotionRecorder.Clip?, live: Boolean) {
        var message: String? = null
        try {
            when {
                photo == null -> message = "No se pudo tomar la foto"
                clip == null -> {
                    MotionPhotoWriter.save(this, photo, null)
                    if (live) message = "Se guardó sin movimiento"
                }
                else -> {
                    val mp4 = MotionPhotoWriter.muxToMp4(clip, cacheDir)
                    val xmp = MotionPhotoWriter.buildXmp(mp4.bytes.size, mp4.presentationUs)
                    val jpeg = MotionPhotoWriter.insertXmp(photo, xmp)
                    MotionPhotoWriter.save(this, jpeg, mp4.bytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando", e)
            message = "Error: ${e.message}"
        }
        val thumb = MediaRepo.list(this).firstOrNull()?.let { MediaRepo.thumbnail(this, it.uri, 200) }
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

    private fun refreshThumbnail() {
        ioExecutor.execute {
            val thumb = MediaRepo.list(this).firstOrNull()?.let { MediaRepo.thumbnail(this, it.uri, 200) }
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

    override fun onDestroy() {
        super.onDestroy()
        audio.stop()
        sound.release()
        analysisExecutor.execute { recorder.release() }
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
    }

    companion object {
        private const val TAG = "FotoAnimada"
        private const val KEY_LIVE = "live_on"
    }
}
