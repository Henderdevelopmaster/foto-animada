package com.hender.fotoanimada

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro para revisar errores (Ajustes > Compartir registro). Junta en un .txt:
 * - la app, el cel y los ajustes del momento,
 * - el último cierre inesperado (si hubo), que se guarda antes de que la app se cierre,
 * - el registro (logcat) de la app: disparos, fotos de noche, mejoras, tiempos y errores.
 * Se guarda en Descargas/Camaratrix y se abre el menú de compartir (WhatsApp, Drive, etc.).
 * No lleva fotos ni nada personal: solo lo que la app escribe en su registro.
 */
object Registro {
    private const val TAG = "Registro"
    private const val CRASH_FILE = "ultimo_error.txt"

    @Volatile private var installed = false

    /** Último estado de temperatura del cel (PowerManager.THERMAL_STATUS_*). */
    @Volatile var thermal = 0
        private set

    /** Guarda el error si la app se cierra de golpe (el registro normal se pierde al reiniciar). */
    fun install(context: Context) {
        if (installed) return
        installed = true
        watchThermal(context.applicationContext)
        val dir = context.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    append("Cierre inesperado ").append(stamp(Date())).append(" en el hilo ").append(thread.name).append('\n')
                    append(Log.getStackTraceString(error))
                }
                File(dir, CRASH_FILE).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * El Helio G99 baja la velocidad cuando se calienta (y la vista previa, el Live y el procesado
     * se ponen lentos sin que la app tenga la culpa). Cada cambio queda en el registro.
     */
    private fun watchThermal(context: Context) {
        try {
            val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return
            thermal = pm.currentThermalStatus
            pm.addThermalStatusListener { status ->
                thermal = status
                Log.i(TAG, "Temperatura del cel: ${thermalName(status)}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "No se pudo vigilar la temperatura", e)
        }
    }

    fun thermalName(status: Int): String = when (status) {
        0 -> "normal"
        1 -> "tibio"
        2 -> "caliente (empieza a frenar)"
        3 -> "muy caliente (frena)"
        4 -> "crítica"
        5 -> "emergencia"
        6 -> "apagándose"
        else -> "desconocida ($status)"
    }

    /** Arma el registro en segundo plano y abre el menú de compartir. */
    fun share(activity: Activity, settings: String, done: (Boolean) -> Unit) {
        val app = activity.applicationContext
        Thread {
            val uri = try {
                val text = build(app, settings)
                val name = "registro-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Camaratrix")
                }
                val u = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("No se pudo crear el archivo")
                app.contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray()) }
                u
            } catch (e: Throwable) {
                Log.e(TAG, "No se pudo armar el registro", e)
                null
            }
            activity.runOnUiThread {
                if (uri == null || activity.isFinishing) {
                    done(false)
                    return@runOnUiThread
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Registro de Camaratrix")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    activity.startActivity(Intent.createChooser(send, "Compartir registro"))
                    done(true)
                } catch (e: Exception) {
                    done(false)
                }
            }
        }.start()
    }

    private fun build(context: Context, settings: String): String = buildString {
        val pm = context.packageManager
        val version = try {
            pm.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        append("Camaratrix ").append(version).append(" · ").append(stamp(Date())).append('\n')
        append("Cel: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.HARDWARE).append(") · Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("Compilación del cel: ").append(Build.VERSION.INCREMENTAL).append('\n')
        val rt = Runtime.getRuntime()
        append("Núcleos: ").append(rt.availableProcessors()).append(" · memoria máx. de la app: ")
            .append(rt.maxMemory() shr 20).append(" MB\n")
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
        append("Temperatura: ").append(thermalName(thermal))
        if (battery != null) {
            append(" · batería ").append(battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)).append(" %")
        }
        if (power?.isPowerSaveMode == true) append(" · ahorro de batería prendido")
        append('\n')
        append("Ajustes: ").append(settings).append("\n\n")

        val crash = File(context.filesDir, CRASH_FILE)
        if (crash.exists()) {
            append("===== Último cierre inesperado =====\n")
            append(crash.readText().take(20_000)).append("\n\n")
        }

        append("===== Registro de la app =====\n")
        append(logcat())
    }

    /** El logcat de este proceso (una app puede leer el suyo sin permisos especiales). */
    private fun logcat(): String = try {
        val pid = android.os.Process.myPid()
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", "4000"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.ifBlank { "(vacío)" }
    } catch (e: Throwable) {
        "(no se pudo leer: ${e.message})"
    }

    private fun stamp(d: Date) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d)
}
