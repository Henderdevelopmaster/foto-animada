package com.hender.fotoanimada

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Editor tipo iPhone: Ajustar, Filtros y Recortar. */
class EditorActivity : ComponentActivity() {

    private lateinit var image: ImageView
    private lateinit var busy: TextView
    private lateinit var adjustPanel: View
    private lateinit var filterPanel: View
    private lateinit var cropPanel: View
    private lateinit var adjustSeek: SeekBar
    private lateinit var adjustValue: TextView
    private lateinit var adjustList: LinearLayout
    private lateinit var filterList: LinearLayout
    private lateinit var cropList: LinearLayout
    private lateinit var tabs: LinearLayout

    private val io = Executors.newSingleThreadExecutor()
    private var uri: Uri? = null
    private var isOwn = false
    private var isLive = false
    private var base: Bitmap? = null      // foto reducida para la vista previa
    private var geo: Bitmap? = null       // con giro/recorte aplicado
    private val edit = Processing.Edit()

    enum class Adjust(val label: String) {
        EXPOSURE("Exposición"), CONTRAST("Contraste"), SATURATION("Saturación"),
        WARMTH("Calidez"), VIGNETTE("Viñeta")
    }

    private var adjust = Adjust.EXPOSURE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        image = findViewById(R.id.image)
        busy = findViewById(R.id.busy)
        adjustPanel = findViewById(R.id.adjustPanel)
        filterPanel = findViewById(R.id.filterPanel)
        cropPanel = findViewById(R.id.cropPanel)
        adjustSeek = findViewById(R.id.adjustSeek)
        adjustValue = findViewById(R.id.adjustValue)
        adjustList = findViewById(R.id.adjustList)
        filterList = findViewById(R.id.filterList)
        cropList = findViewById(R.id.cropList)
        tabs = findViewById(R.id.tabs)

        uri = intent.data
        isOwn = intent.getBooleanExtra(EXTRA_OWN, false)
        isLive = intent.getBooleanExtra(EXTRA_LIVE, false)

        findViewById<View>(R.id.cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.reset).setOnClickListener { reset() }
        findViewById<View>(R.id.done).setOnClickListener { save() }

        buildTabs()
        buildAdjust()
        buildFilters()
        buildCrop()
        showTab(0)

        adjustSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = (p - 100) / 100f
                when (adjust) {
                    Adjust.EXPOSURE -> edit.exposure = v
                    Adjust.CONTRAST -> edit.contrast = v
                    Adjust.SATURATION -> edit.saturation = v
                    Adjust.WARMTH -> edit.warmth = v
                    Adjust.VIGNETTE -> edit.vignette = (p / 200f)
                }
                if (p == 100) sb.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                showValue()
                refreshColor(adjust == Adjust.VIGNETTE)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (adjust == Adjust.VIGNETTE) refreshGeometry()
            }
        })

        val u = uri ?: return finish()
        io.execute {
            val bmp = MediaRepo.loadFull(this, u, 1600, software = true)
            runOnUiThread {
                busy.visibility = View.GONE
                if (bmp == null) {
                    Toast.makeText(this, "No se pudo abrir la foto", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    base = bmp
                    refreshGeometry()
                }
            }
        }
    }

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setBackgroundResource(R.drawable.bg_filter_chip)
        setOnClickListener { onClick() }
    }

    private fun addChip(parent: LinearLayout, v: View) {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        parent.addView(v, lp)
    }

    private fun select(parent: LinearLayout, index: Int) {
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i) as TextView
            v.isSelected = i == index
            v.setTextColor(if (i == index) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun buildTabs() {
        listOf("Ajustar", "Filtros", "Recortar").forEachIndexed { i, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setOnClickListener { showTab(i) }
            }
            tabs.addView(tv)
        }
    }

    private fun showTab(i: Int) {
        adjustPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        filterPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
        cropPanel.visibility = if (i == 2) View.VISIBLE else View.GONE
        for (k in 0 until tabs.childCount) {
            (tabs.getChildAt(k) as TextView).setTextColor(if (k == i) 0xFF00E676.toInt() else 0x99FFFFFF.toInt())
        }
    }

    private fun buildAdjust() {
        Adjust.entries.forEachIndexed { i, a ->
            addChip(adjustList, chip(a.label) {
                adjust = a
                select(adjustList, i)
                adjustSeek.progress = when (a) {
                    Adjust.EXPOSURE -> (edit.exposure * 100 + 100).roundToInt()
                    Adjust.CONTRAST -> (edit.contrast * 100 + 100).roundToInt()
                    Adjust.SATURATION -> (edit.saturation * 100 + 100).roundToInt()
                    Adjust.WARMTH -> (edit.warmth * 100 + 100).roundToInt()
                    Adjust.VIGNETTE -> (edit.vignette * 200).roundToInt()
                }
                showValue()
            })
        }
        select(adjustList, 0)
        showValue()
    }

    private fun showValue() {
        val v = when (adjust) {
            Adjust.EXPOSURE -> edit.exposure
            Adjust.CONTRAST -> edit.contrast
            Adjust.SATURATION -> edit.saturation
            Adjust.WARMTH -> edit.warmth
            Adjust.VIGNETTE -> edit.vignette
        }
        adjustValue.text = "${adjust.label}  ${(v * 100).roundToInt()}"
    }

    private fun buildFilters() {
        PhotoFilter.entries.forEachIndexed { i, f ->
            addChip(filterList, chip(f.label) {
                edit.filter = f
                select(filterList, i)
                refreshColor(false)
            })
        }
        select(filterList, 0)
    }

    private fun buildCrop() {
        val opts = listOf<Pair<String, () -> Unit>>(
            "↺ Girar" to { edit.rotation = (edit.rotation + 270) % 360 },
            "⇋ Espejo" to { edit.flip = !edit.flip },
            "Original" to { edit.aspect = 0f },
            "Cuadrado" to { edit.aspect = 1f },
            "4:5" to { edit.aspect = 4f / 5f },
            "3:4" to { edit.aspect = 3f / 4f },
            "9:16" to { edit.aspect = 9f / 16f },
            "16:9" to { edit.aspect = 16f / 9f },
            "Marco instantánea" to { edit.polaroid = !edit.polaroid },
        )
        for ((label, action) in opts) {
            addChip(cropList, chip(label) {
                action()
                refreshGeometry()
            })
        }
    }

    /** Giro/recorte/viñeta/marco: rehace la imagen de vista previa. */
    private fun refreshGeometry() {
        val b = base ?: return
        io.execute {
            val noColor = edit.copy(
                exposure = 0f, contrast = 0f, saturation = 0f, warmth = 0f, filter = PhotoFilter.ORIGINAL
            )
            val out = Processing.render(b, noColor)
            runOnUiThread {
                geo?.takeIf { it !== base }?.recycle()
                geo = out
                image.setImageBitmap(out)
                refreshColor(false)
            }
        }
    }

    /** Color: se aplica en vivo con un filtro de color sobre la vista previa. */
    private fun refreshColor(fromVignette: Boolean) {
        if (fromVignette) return
        image.colorFilter = ColorMatrixColorFilter(Processing.colorMatrix(edit))
    }

    private fun reset() {
        edit.exposure = 0f; edit.contrast = 0f; edit.saturation = 0f; edit.warmth = 0f
        edit.vignette = 0f; edit.filter = PhotoFilter.ORIGINAL; edit.rotation = 0
        edit.flip = false; edit.aspect = 0f; edit.polaroid = false
        adjustSeek.progress = if (adjust == Adjust.VIGNETTE) 0 else 100
        select(filterList, 0)
        showValue()
        refreshGeometry()
    }

    private fun save() {
        val u = uri ?: return
        busy.text = "Guardando…"
        busy.visibility = View.VISIBLE
        io.execute {
            val msg = try {
                val full = MediaRepo.loadFull(this, u, 4096, software = true)
                    ?: throw IllegalStateException("no se pudo abrir")
                val out = Processing.render(full, edit)
                full.recycle()
                val jpeg = Processing.encode(out, 95)
                out.recycle()
                // Igual que iPhone: las fotos propias se reemplazan; las demás y los Live se guardan como copia
                val replaced = isOwn && !isLive && try {
                    contentResolver.openOutputStream(u, "wt")!!.use { it.write(jpeg) }
                    true
                } catch (_: SecurityException) {
                    false
                }
                if (replaced) {
                    "Cambios guardados"
                } else {
                    MotionPhotoWriter.save(this, jpeg, null, "_EDIT")
                    if (isLive) "Guardada como copia (sin movimiento)" else "Guardada como copia"
                }
            } catch (e: Throwable) {
                "No se pudo guardar: ${e.message}"
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    companion object {
        const val EXTRA_OWN = "own"
        const val EXTRA_LIVE = "live"
    }
}
