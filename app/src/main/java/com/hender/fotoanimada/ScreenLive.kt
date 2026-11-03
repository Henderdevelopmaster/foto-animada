package com.hender.fotoanimada

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * "Live pantalla": botón en Ajustes rápidos que graba 3 s de la pantalla
 * y los guarda como foto Live (foto quieta + movimiento).
 */
class ScreenLiveTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        val on = ScreenLiveService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Live pantalla"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Toca para capturar" else "Apagado"
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ScreenLiveActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun refresh(context: Context) {
            try {
                TileService.requestListeningState(context, ComponentName(context, ScreenLiveTile::class.java))
            } catch (_: Exception) {
            }
        }
    }
}

/** Pantalla invisible: pide el permiso de grabar o manda a capturar. */
class ScreenLiveActivity : ComponentActivity() {

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, ScreenLiveService::class.java)
                    .setAction(ScreenLiveService.ACTION_START)
                    .putExtra(ScreenLiveService.EXTRA_CODE, res.resultCode)
                    .putExtra(ScreenLiveService.EXTRA_DATA, data)
                startForegroundService(svc)
            } else {
                Toast.makeText(this, "Sin permiso no puedo grabar la pantalla", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenLiveService.running) {
            startService(Intent(this, ScreenLiveService::class.java).setAction(ScreenLiveService.ACTION_CAPTURE))
            finish()
        } else {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            consent.launch(mpm.createScreenCaptureIntent())
        }
    }
}

class ScreenLiveService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_CAPTURE = "capture"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen_live"
        private const val NOTIF_ID = 42
        private const val TAG = "ScreenLive"
        private const val CLIP_MS = 3000L

        @Volatile
        var running = false
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("screen-live").apply { start() }
    private val workHandler = Handler(worker.looper)
    @Volatile
    private var capturing = false
    private var w = 0
    private var h = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_CAPTURE -> main.postDelayed({ record() }, 650)
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // Android 14: primero la notificación de servicio, después el permiso
        val notif = notification("Toca Capturar o el botón de Ajustes rápidos")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopAll(); return
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("sin proyección")
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopAll()
            }, main)
            projection = p
            pickSize()
            val dpi = resources.displayMetrics.densityDpi
            // Una sola pantalla virtual por sesión; se le pone la superficie solo al grabar
            display = p.createVirtualDisplay(
                "CamaratrixLive", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main
            )
            running = true
            ScreenLiveTile.refresh(this)
            main.postDelayed({ record() }, 900)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar", e)
            Toast.makeText(this, "No se pudo grabar la pantalla: ${e.message}", Toast.LENGTH_LONG).show()
            stopAll()
        }
    }

    /** Tamaño de grabación: ancho máx. 1080 y que el codificador lo soporte. */
    private fun pickSize() {
        val wm = getSystemService(WindowManager::class.java)
        val (rw, rh) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }
        var scale = minOf(1f, 1080f / rw)
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        repeat(6) {
            w = ((rw * scale).toInt() / 16) * 16
            h = ((rh * scale).toInt() / 16) * 16
            if (caps == null || caps.isSizeSupported(w, h)) return
            scale *= 0.8f
        }
    }

    private fun record() {
        val vd = display ?: return
        if (capturing) return
        capturing = true
        updateNotification("Grabando la pantalla…")
        workHandler.post {
            var enc: MediaCodec? = null
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // Si la pantalla no cambia, repetir el último cuadro para que el clip dure 3 s
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 66_000L)
                }
                val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                enc = e
                e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = e.createInputSurface()
                e.start()
                vd.surface = surface

                val samples = mutableListOf<MotionRecorder.Sample>()
                var outFormat: MediaFormat? = null
                val info = MediaCodec.BufferInfo()
                val until = SystemClock.uptimeMillis() + CLIP_MS
                var ended = false
                var eosSent = false
                while (!ended) {
                    if (!eosSent && SystemClock.uptimeMillis() >= until) {
                        vd.surface = null
                        e.signalEndOfInputStream()
                        eosSent = true
                    }
                    val o = e.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = e.outputFormat
                        o >= 0 -> {
                            val buf = e.getOutputBuffer(o)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buf != null && info.size > 0 && !config) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buf.get(bytes)
                                samples += MotionRecorder.Sample(bytes, info.presentationTimeUs, info.flags)
                            }
                            e.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                        }
                    }
                }
                surface.release()
                save(outFormat, samples)
            } catch (ex: Throwable) {
                Log.e(TAG, "Error grabando", ex)
                main.post { Toast.makeText(this, "Error: ${ex.message}", Toast.LENGTH_SHORT).show() }
                try {
                    display?.surface = null
                } catch (_: Exception) {
                }
            } finally {
                try {
                    enc?.stop()
                } catch (_: Exception) {
                }
                enc?.release()
                capturing = false
                updateNotification("Toca Capturar o el botón de Ajustes rápidos")
            }
        }
    }

    private fun save(format: MediaFormat?, samples: List<MotionRecorder.Sample>) {
        if (format == null || samples.none { it.isKey }) throw IllegalStateException("no se grabó nada")
        val first = samples.first { it.isKey }.ptsUs
        val last = samples.last().ptsUs
        val key = first + (last - first) / 2
        val clip = MotionRecorder.Clip(format, samples, key, 0, null)
        val mp4 = MotionPhotoWriter.muxToMp4(clip, cacheDir)

        // Foto de portada: el cuadro del medio del clip
        val tmp = File(cacheDir, "screen.mp4")
        tmp.writeBytes(mp4.bytes)
        val mmr = MediaMetadataRetriever()
        val frame: Bitmap = try {
            mmr.setDataSource(tmp.path)
            mmr.getFrameAtTime(mp4.presentationUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            mmr.release()
            tmp.delete()
        } ?: throw IllegalStateException("sin portada")
        val jpeg = Processing.encode(frame, 95)
        frame.recycle()
        val xmp = MotionPhotoWriter.buildXmp(mp4.bytes.size, mp4.presentationUs)
        MotionPhotoWriter.save(this, MotionPhotoWriter.insertXmp(jpeg, xmp), mp4.bytes, "_SCREEN")
        main.post { Toast.makeText(this, "Live de pantalla guardado ✓", Toast.LENGTH_SHORT).show() }
    }

    private fun stopAll() {
        running = false
        try {
            display?.release()
        } catch (_: Exception) {
        }
        display = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ScreenLiveTile.refresh(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        worker.quitSafely()
        ScreenLiveTile.refresh(this)
    }

    // ---------- Notificación ----------

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Live pantalla", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val capture = PendingIntent.getActivity(
            this, 1,
            Intent(this, ScreenLiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, ScreenLiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_live_small)
            .setContentTitle("Live pantalla activo")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Capturar", capture).build())
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
}
