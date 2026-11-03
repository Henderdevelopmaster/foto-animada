package com.hender.fotoanimada

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Biblioteca de efectos: se descargan del repo público camaratrix-efectos.
 * Para agregar efectos nuevos solo hay que subirlos allá; la app no cambia.
 */
class EffectsLibraryActivity : ComponentActivity() {

    companion object {
        const val BASE = "https://raw.githubusercontent.com/Henderdevelopmaster/camaratrix-efectos/main/"
    }

    data class Entry(val id: String, val name: String, val emoji: String, val files: List<String>, val size: Long)

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var entries = listOf<Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(16), dp(34), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(4), 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Biblioteca de efectos"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(header)
        status = TextView(this).apply {
            text = "Cargando catálogo…"
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        loadCatalog()
    }

    private fun get(path: String): ByteArray {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.useCaches = false
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun loadCatalog() {
        io.execute {
            try {
                val json = JSONObject(String(get("catalog.json?t=${System.currentTimeMillis() / 60000}")))
                val arr = json.getJSONArray("effects")
                val out = mutableListOf<Entry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val files = o.getJSONArray("files")
                    out += Entry(
                        o.getString("id"), o.optString("name"), o.optString("emoji", "✨"),
                        (0 until files.length()).map { files.getString(it) }, o.optLong("size")
                    )
                }
                runOnUiThread {
                    entries = out
                    status.text = "${out.size} efectos disponibles"
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "No pude cargar la biblioteca. Revisa tu internet.\n(${e.message})" }
            }
        }
    }

    private fun installedDir(id: String) = File(EffectStore.downloadedDir(this), id)

    private fun render() {
        list.removeAllViews()
        for (e in entries) {
            val installed = File(installedDir(e.id), "effect.json").exists()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(R.drawable.bg_chip)
            }
            row.addView(TextView(this).apply {
                text = e.emoji
                textSize = 30f
                setPadding(0, 0, dp(14), 0)
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = e.name
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = "${(e.size / 1024).coerceAtLeast(1)} KB"
                    textSize = 12f
                    setTextColor(0x99FFFFFF.toInt())
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (installed) "Quitar" else "Descargar"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                if (installed) {
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundResource(R.drawable.bg_chip)
                } else {
                    setTextColor(0xFF000000.toInt())
                    setBackgroundResource(R.drawable.bg_live_pill)
                }
                setOnClickListener { if (installed) remove(e) else download(e, this) }
            })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun download(e: Entry, button: TextView) {
        button.text = "…"
        button.isEnabled = false
        io.execute {
            val tmp = File(cacheDir, "fx_${e.id}").apply { deleteRecursively(); mkdirs() }
            val msg = try {
                for (f in e.files) {
                    // No dejar que un nombre raro escriba fuera de la carpeta
                    require(!f.contains("/") && !f.contains("..")) { "archivo inválido" }
                    File(tmp, f).writeBytes(get("efectos/${e.id}/$f"))
                }
                JSONObject(File(tmp, "effect.json").readText()) // validar
                val dst = installedDir(e.id)
                dst.deleteRecursively()
                if (!tmp.renameTo(dst)) {
                    tmp.copyRecursively(dst, overwrite = true)
                    tmp.deleteRecursively()
                }
                setResult(RESULT_OK)
                "${e.name} listo. Ya está en tus efectos."
            } catch (ex: Exception) {
                tmp.deleteRecursively()
                "No se pudo descargar: ${ex.message}"
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private fun remove(e: Entry) {
        installedDir(e.id).deleteRecursively()
        setResult(RESULT_OK)
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
