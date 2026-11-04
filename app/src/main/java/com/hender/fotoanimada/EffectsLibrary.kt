package com.hender.fotoanimada

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    data class Entry(
        val id: String, val name: String, val emoji: String, val category: String,
        val icon: String, val files: List<String>, val size: Long,
    )

    private val io = Executors.newFixedThreadPool(3)
    private lateinit var grid: RecyclerView
    private lateinit var cats: LinearLayout
    private lateinit var subtitle: TextView
    private var entries = listOf<Entry>()
    private var shown = listOf<Entry>()
    private var categories = listOf<String>()
    private var category = "Todos"
    private val downloading = HashSet<String>()
    private val icons = HashMap<String, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        grid = findViewById(R.id.grid)
        cats = findViewById(R.id.cats)
        subtitle = findViewById(R.id.subtitle)
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.getAll).setOnClickListener { downloadAll() }
        findViewById<View>(R.id.create).setOnClickListener {
            startActivity(Intent(this, CustomEffectActivity::class.java))
            setResult(RESULT_OK)
        }
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = Adapter()
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        grid.adapter?.notifyDataSetChanged()
        updateSubtitle()
    }

    private fun get(path: String): ByteArray {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
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
                        o.optString("category", "Otros").ifBlank { "Otros" }, o.optString("icon"),
                        (0 until files.length()).map { files.getString(it) }, o.optLong("size")
                    )
                }
                val order = json.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: out.map { it.category }.distinct()
                runOnUiThread {
                    entries = out
                    categories = listOf("Todos") + order.filter { c -> out.any { it.category == c } } +
                        out.map { it.category }.distinct().filter { it !in order }
                    buildCats()
                    showCategory(category)
                }
            } catch (e: Exception) {
                runOnUiThread { subtitle.text = "Sin conexión. Revisa tu internet." }
            }
        }
    }

    private fun buildCats() {
        cats.removeAllViews()
        for (c in categories) {
            cats.addView(TextView(this).apply {
                text = c
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setBackgroundResource(R.drawable.bg_filter_chip)
                tag = c
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showCategory(c)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    private fun showCategory(c: String) {
        category = c
        for (i in 0 until cats.childCount) {
            val v = cats.getChildAt(i) as TextView
            v.isSelected = v.tag == c
            v.setTextColor(if (v.isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        shown = if (c == "Todos") entries else entries.filter { it.category == c }
        grid.adapter?.notifyDataSetChanged()
        grid.scrollToPosition(0)
        updateSubtitle()
    }

    private fun updateSubtitle() {
        if (entries.isEmpty()) return
        val have = entries.count { installed(it.id) }
        subtitle.text = "${entries.size} efectos · $have descargados"
    }

    private fun installedDir(id: String) = File(EffectStore.downloadedDir(this), id)
    private fun installed(id: String) = File(installedDir(id), "effect.json").exists()

    private fun iconFor(e: Entry, onReady: (Bitmap?) -> Unit) {
        if (icons.containsKey(e.id)) { onReady(icons[e.id]); return }
        val local = File(installedDir(e.id), e.icon)
        val cached = File(cacheDir, "lib_icon_${e.id}.png")
        io.execute {
            val bmp = try {
                when {
                    e.icon.isBlank() -> null
                    local.exists() -> BitmapFactory.decodeFile(local.path)
                    cached.exists() -> BitmapFactory.decodeFile(cached.path)
                    else -> get("efectos/${e.id}/${e.icon}").let { bytes ->
                        cached.writeBytes(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                icons[e.id] = bmp
                onReady(bmp)
            }
        }
    }

    private fun download(e: Entry, done: (() -> Unit)? = null) {
        if (!downloading.add(e.id)) return
        grid.adapter?.notifyDataSetChanged()
        io.execute {
            val tmp = File(cacheDir, "fx_${e.id}").apply { deleteRecursively(); mkdirs() }
            val ok = try {
                for (f in e.files) {
                    // No dejar que un nombre raro escriba fuera de la carpeta
                    require(!f.contains("/") && !f.contains("..")) { "archivo inválido" }
                    File(tmp, f).writeBytes(get("efectos/${e.id}/$f"))
                }
                JSONObject(File(tmp, "effect.json").readText()) // validar
                val dst = installedDir(e.id)
                dst.deleteRecursively()
                EffectStore.forget(dst.path)
                if (!tmp.renameTo(dst)) {
                    tmp.copyRecursively(dst, overwrite = true)
                    tmp.deleteRecursively()
                }
                true
            } catch (ex: Exception) {
                tmp.deleteRecursively()
                false
            }
            runOnUiThread {
                downloading.remove(e.id)
                if (!ok) Toast.makeText(this, "No se pudo descargar ${e.name}", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                grid.adapter?.notifyDataSetChanged()
                updateSubtitle()
                done?.invoke()
            }
        }
    }

    private fun downloadAll() {
        val missing = shown.filter { !installed(it.id) }
        if (missing.isEmpty()) {
            Toast.makeText(this, "Ya los tienes todos", Toast.LENGTH_SHORT).show(); return
        }
        missing.forEach { download(it) }
    }

    private fun remove(e: Entry) {
        val dir = installedDir(e.id)
        EffectStore.forget(dir.path)
        dir.deleteRecursively()
        setResult(RESULT_OK)
        grid.adapter?.notifyDataSetChanged()
        updateSubtitle()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val emoji: TextView = v.findViewById(R.id.emoji)
        val badge: View = v.findViewById(R.id.badge)
        val name: TextView = v.findViewById(R.id.name)
        val action: TextView = v.findViewById(R.id.action)
        var boundId = ""
    }

    inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = shown[position]
            h.boundId = e.id
            h.name.text = e.name
            h.emoji.text = e.emoji
            h.icon.setImageDrawable(null)
            iconFor(e) { bmp -> if (h.boundId == e.id) h.icon.setImageBitmap(bmp) }
            val have = installed(e.id)
            val busy = e.id in downloading
            h.badge.visibility = if (have) View.VISIBLE else View.GONE
            when {
                busy -> {
                    h.action.text = "Bajando…"
                    h.action.setTextColor(0xFFFFFFFF.toInt())
                    h.action.setBackgroundResource(R.drawable.bg_pill_dark)
                }
                have -> {
                    h.action.text = "Quitar"
                    h.action.setTextColor(0xB3FFFFFF.toInt())
                    h.action.setBackgroundResource(R.drawable.bg_pill_dark)
                }
                else -> {
                    h.action.text = "Obtener"
                    h.action.setTextColor(0xFF000000.toInt())
                    h.action.setBackgroundResource(R.drawable.bg_live_pill)
                }
            }
            h.action.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when {
                    e.id in downloading -> {}
                    installed(e.id) -> remove(e)
                    else -> download(e)
                }
            }
            h.itemView.setOnClickListener { if (!installed(e.id)) download(e) }
        }
    }
}
