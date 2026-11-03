package com.hender.fotoanimada

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.TextureView
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.view.ViewGroup
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Galería tipo iPhone.
 * - LIVE: mantén presionado para ver el movimiento con sonido.
 * - Bucle / Rebote: se reproduce solo, sin parar.
 * - Exposición larga: mezcla todos los fotogramas en una sola imagen.
 * - Fotos normales: opción "Animar" (zoom y paneo lento).
 */
class ViewerActivity : ComponentActivity() {

    enum class Fx(val label: String) {
        LIVE("LIVE"),
        LOOP("BUCLE"),
        BOUNCE("REBOTE"),
        LONG("EXPOSICIÓN LARGA"),
        OFF("LIVE DESACTIVADO"),
        STILL("FOTO"),
        ANIMATE("ANIMADA"),
    }

    private lateinit var pager: ViewPager2
    private lateinit var strip: RecyclerView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var fxChip: TextView
    private lateinit var hint: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var player: ExoPlayer

    private val items = mutableListOf<MediaRepo.Item>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("fx", MODE_PRIVATE) }

    private var current = -1
    private var playing: PageHolder? = null
    private var activeHolder: PageHolder? = null
    private var frames: Frames? = null
    private var framesId = -1L
    private var kenBurns: ValueAnimator? = null
    private var barsVisible = true
    private var effectToken = 0
    private var videoHolder: PageHolder? = null
    private var album = MediaRepo.ALBUM_ALL
    private lateinit var favorite: ImageButton
    private lateinit var keyPanel: View
    private lateinit var keySeek: SeekBar
    private lateinit var busyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        pager = findViewById(R.id.pager)
        strip = findViewById(R.id.strip)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        fxChip = findViewById(R.id.fxChip)
        hint = findViewById(R.id.hint)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)

        favorite = findViewById(R.id.favorite)
        keyPanel = findViewById(R.id.keyPanel)
        keySeek = findViewById(R.id.keySeek)
        busyText = findViewById(R.id.busyText)
        album = intent.getStringExtra(EXTRA_ALBUM) ?: MediaRepo.ALBUM_ALL

        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                (playing ?: videoHolder)?.playerView?.animate()?.alpha(1f)?.setDuration(90)?.start()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && playing != null) stopPlayback()
            }
        })

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.share).setOnClickListener { shareCurrent() }
        findViewById<ImageButton>(R.id.delete).setOnClickListener { deleteCurrent() }
        fxChip.setOnClickListener { showFxMenu() }
        favorite.setOnClickListener { toggleFavorite() }
        findViewById<ImageButton>(R.id.moreButton).setOnClickListener { showMoreMenu(it) }
        findViewById<View>(R.id.editButton).setOnClickListener {
            val item = items.getOrNull(current) ?: return@setOnClickListener
            if (item.isVideo) {
                say("La edición de videos llega pronto"); return@setOnClickListener
            }
            editLauncher.launch(
                Intent(this, EditorActivity::class.java)
                    .setData(item.uri)
                    .putExtra(EditorActivity.EXTRA_OWN, item.isOwn)
                    .putExtra(EditorActivity.EXTRA_LIVE, item.isLive)
            )
        }
        findViewById<TextView>(R.id.keyCancel).setOnClickListener { closeKeyPanel(apply = false) }
        findViewById<TextView>(R.id.keyApply).setOnClickListener { closeKeyPanel(apply = true) }

        pager.adapter = PageAdapter()
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = onSelected(position)
        })

        strip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        strip.adapter = StripAdapter()
        strip.post {
            val pad = strip.width / 2 - dp(18)
            strip.setPadding(pad, 0, pad, 0)
        }

        findViewById<ImageButton>(R.id.gridButton).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
            finish()
        }

        startId = intent.getLongExtra(EXTRA_ID, -1L)
        if (!GalleryAccess.has(this) && !GalleryAccess.askedBefore(this)) {
            GalleryAccess.markAsked(this)
            readPermission.launch(GalleryAccess.permissions())
        } else {
            reload()
        }
    }

    private var startId = -1L

    private val readPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    private val deleteRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == RESULT_OK) pendingDelete?.let { removeItem(it) }
            pendingDelete = null
        }
    private var pendingDelete: MediaRepo.Item? = null

    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                startId = items.getOrNull(current)?.id ?: startId
                cacheDir.listFiles()?.filter { it.name.startsWith("live_") }?.forEach { it.delete() }
                reload()
            }
        }

    private fun reload() {
        io.execute {
            val list = MediaRepo.filter(MediaRepo.list(this), album, MediaRepo.favorites(this))
            runOnUiThread {
                items.clear()
                items.addAll(list)
                pager.adapter?.notifyDataSetChanged()
                strip.adapter?.notifyDataSetChanged()
                if (items.isEmpty()) {
                    hint.text = "Todavía no hay fotos"
                    fxChip.visibility = View.GONE
                } else {
                    val start = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)
                    pager.setCurrentItem(start, false)
                    pager.post { onSelected(pager.currentItem) }
                }
            }
        }
    }

    // ---------- Selección y efectos ----------

    private fun fxOf(item: MediaRepo.Item): Fx {
        val saved = prefs.getString("fx_${item.id}", null)?.let { runCatching { Fx.valueOf(it) }.getOrNull() }
        return when {
            item.isLive -> saved?.takeIf { it != Fx.STILL && it != Fx.ANIMATE } ?: Fx.LIVE
            else -> saved?.takeIf { it == Fx.STILL || it == Fx.ANIMATE } ?: Fx.STILL
        }
    }

    private fun onSelected(position: Int) {
        val item = items.getOrNull(position) ?: return
        val old = current
        current = position
        strip.adapter?.notifyItemChanged(old)
        strip.adapter?.notifyItemChanged(position)
        strip.smoothScrollToPosition(position)

        val date = Date(item.dateMs)
        title.text = when {
            DateUtils.isToday(item.dateMs) -> "Hoy"
            else -> SimpleDateFormat("d 'de' MMMM yyyy", Locale("es")).format(date)
        }
        subtitle.text = SimpleDateFormat("h:mm a", Locale("es")).format(date)
        updateFavoriteIcon()
        if (keyPanel.visibility == View.VISIBLE) closeKeyPanel(apply = false)
        applyEffect()
    }

    private fun holderAt(pos: Int): PageHolder? =
        (pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(pos) as? PageHolder

    private fun clearEffect() {
        effectToken++
        stopPlayback()
        videoHolder?.let { h ->
            player.stop()
            h.playerView.alpha = 0f
            h.playerView.player = null
        }
        videoHolder = null
        activeHolder?.let { h ->
            h.animator.stop()
            h.frames.visibility = View.GONE
            h.frames.setImageDrawable(null)
            h.photo.scaleX = 1f
            h.photo.scaleY = 1f
            h.photo.translationX = 0f
            h.photo.translationY = 0f
        }
        kenBurns?.cancel()
        kenBurns = null
        activeHolder = null
    }

    private fun applyEffect() {
        clearEffect()
        val item = items.getOrNull(current) ?: return
        val holder = holderAt(current) ?: run {
            // La página aún no está creada; reintentar en el siguiente frame
            pager.post { if (holderAt(current) != null) applyEffect() }
            return
        }
        activeHolder = holder
        if (item.isVideo) {
            fxChip.visibility = View.GONE
            hint.text = ""
            videoHolder = holder
            holder.playerView.player = player
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setMediaItem(MediaItem.fromUri(item.uri))
            player.prepare()
            player.play()
            return
        }
        val fx = fxOf(item)
        updateChip(item, fx)
        holder.frames.colorFilter = null
        (holder.playerView.videoSurfaceView as? TextureView)?.setLayerPaint(null)
        if (item.isLive) {
            val token = effectToken
            io.execute {
                val f = MotionPhotoWriter.readFilter(this, item.uri)
                if (f == PhotoFilter.ORIGINAL) return@execute
                runOnUiThread {
                    if (token != effectToken) return@runOnUiThread
                    holder.frames.colorFilter = f.colorFilter()
                    (holder.playerView.videoSurfaceView as? TextureView)?.setLayerPaint(f.paint())
                }
            }
        }

        when (fx) {
            Fx.LIVE -> hint.text = "Mantén presionado para ver el movimiento"
            Fx.OFF, Fx.STILL -> hint.text = ""
            Fx.ANIMATE -> {
                hint.text = ""
                startKenBurns(holder)
            }
            Fx.LOOP, Fx.BOUNCE, Fx.LONG -> {
                hint.text = "Preparando efecto…"
                val token = effectToken
                withFrames(item) { f ->
                    if (token != effectToken) return@withFrames
                    hint.text = ""
                    if (f == null) {
                        hint.text = "No se pudo crear el efecto"
                        return@withFrames
                    }
                    holder.frames.visibility = View.VISIBLE
                    holder.frames.alpha = 0f
                    holder.frames.animate().alpha(1f).setDuration(250).start()
                    if (fx == Fx.LONG) {
                        io.execute {
                            val bmp = f.longExposure()
                            runOnUiThread {
                                if (token == effectToken) holder.frames.setImageBitmap(bmp)
                            }
                        }
                    } else {
                        holder.animator.start(f, bounce = fx == Fx.BOUNCE)
                    }
                }
            }
        }
    }

    private fun updateChip(item: MediaRepo.Item, fx: Fx) {
        fxChip.visibility = View.VISIBLE
        fxChip.text = fx.label
        fxChip.alpha = if (fx == Fx.OFF || fx == Fx.STILL) 0.6f else 1f
        fxChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (item.isLive) R.drawable.ic_live_small else 0, 0, R.drawable.ic_chevron, 0
        )
    }

    private fun showFxMenu() {
        val item = items.getOrNull(current) ?: return
        val menu = PopupMenu(this, fxChip)
        val options = if (item.isLive)
            listOf(Fx.LIVE, Fx.LOOP, Fx.BOUNCE, Fx.LONG, Fx.OFF)
        else
            listOf(Fx.STILL, Fx.ANIMATE)
        val names = mapOf(
            Fx.LIVE to "Live", Fx.LOOP to "Bucle", Fx.BOUNCE to "Rebote",
            Fx.LONG to "Exposición larga", Fx.OFF to "Desactivado",
            Fx.STILL to "Foto normal", Fx.ANIMATE to "Animar",
        )
        val selected = fxOf(item)
        options.forEachIndexed { i, fx ->
            val label = (if (fx == selected) "✓  " else "     ") + names[fx]
            menu.menu.add(Menu.NONE, i, i, label)
        }
        menu.setOnMenuItemClickListener { mi ->
            val fx = options[mi.itemId]
            prefs.edit().putString("fx_${item.id}", fx.name).apply()
            fxChip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applyEffect()
            true
        }
        menu.show()
    }

    private fun withFrames(item: MediaRepo.Item, onReady: (Frames?) -> Unit) {
        frames?.takeIf { framesId == item.id }?.let { onReady(it); return }
        io.execute {
            val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item))
            val f = file?.let { Frames.load(it, maxSide = 540, maxFrames = 45) }
            runOnUiThread {
                if (items.getOrNull(current)?.id == item.id && f != null) {
                    activeHolder?.animator?.stop()
                    frames?.takeIf { it !== f }?.let { old ->
                        // Liberar después de que nadie la esté mostrando
                        main.postDelayed({ old.recycle() }, 500)
                    }
                    frames = f
                    framesId = item.id
                } else {
                    f?.recycle()
                }
                onReady(if (framesId == item.id) frames else null)
            }
        }
    }

    private fun startKenBurns(holder: PageHolder) {
        val v = holder.photo
        kenBurns = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val s = 1f + 0.12f * t
                v.scaleX = s
                v.scaleY = s
                v.translationX = -v.width * 0.03f * t
                v.translationY = -v.height * 0.02f * t
            }
            start()
        }
    }

    private fun videoFile(item: MediaRepo.Item) = File(cacheDir, "live_${item.id}.mp4")

    // ---------- LIVE: mantener presionado ----------

    private fun startPlayback(holder: PageHolder) {
        val item = items.getOrNull(holder.bindingAdapterPosition) ?: return
        if (!item.isLive || fxOf(item) != Fx.LIVE) return
        val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item)) ?: return

        stopPlayback()
        playing = holder
        pager.isUserInputEnabled = false
        holder.page.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setBars(false)

        holder.playerView.player = player
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.play()
        // Pequeño "respiro" como en iPhone: se aleja un poco y vuelve
        holder.content.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).withEndAction {
            holder.content.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
        }.start()
    }

    private fun stopPlayback() {
        val holder = playing ?: return
        playing = null
        pager.isUserInputEnabled = true
        setBars(true)
        holder.playerView.animate().alpha(0f).setDuration(250).withEndAction {
            if (playing !== holder) holder.playerView.player = null
        }.start()
        player.pause()
    }

    private fun setBars(visible: Boolean) {
        if (barsVisible == visible) return
        barsVisible = visible
        val a = if (visible) 1f else 0f
        topBar.animate().alpha(a).setDuration(200).start()
        bottomBar.animate().alpha(a).setDuration(200).start()
    }

    // ---------- Acciones ----------

    private fun shareCurrent() {
        val item = items.getOrNull(current) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartir foto"))
    }

    private fun deleteCurrent() {
        val pos = current
        val item = items.getOrNull(pos) ?: return
        clearEffect()
        try {
            contentResolver.delete(item.uri, null, null)
            removeItem(item)
        } catch (e: SecurityException) {
            // Foto de otra app: Android pide confirmación al usuario
            try {
                val sender = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri)).intentSender
                pendingDelete = item
                deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
            } catch (_: Exception) {
                hint.text = "No se pudo eliminar"
            }
        } catch (e: Exception) {
            hint.text = "No se pudo eliminar"
        }
    }

    private fun removeItem(item: MediaRepo.Item) {
        val pos = items.indexOfFirst { it.id == item.id }
        if (pos < 0) return
        videoFile(item).delete()
        prefs.edit().remove("fx_${item.id}").apply()
        items.removeAt(pos)
        pager.adapter?.notifyItemRemoved(pos)
        strip.adapter?.notifyItemRemoved(pos)
        if (items.isEmpty()) finish() else pager.post { onSelected(pager.currentItem) }
    }

    override fun onPause() {
        super.onPause()
        clearEffect()
    }

    override fun onResume() {
        super.onResume()
        if (current >= 0) pager.post { applyEffect() }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        frames?.recycle()
        io.shutdown()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Favoritos ----------

    private fun updateFavoriteIcon() {
        val item = items.getOrNull(current) ?: return
        val fav = item.id.toString() in MediaRepo.favorites(this)
        favorite.setImageResource(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart)
    }

    private fun toggleFavorite() {
        val item = items.getOrNull(current) ?: return
        val fav = item.id.toString() !in MediaRepo.favorites(this)
        MediaRepo.setFavorite(this, item.id, fav)
        favorite.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        updateFavoriteIcon()
        favorite.scaleX = 0.6f
        favorite.scaleY = 0.6f
        favorite.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    // ---------- Más opciones ----------

    private fun showMoreMenu(anchor: View) {
        val item = items.getOrNull(current) ?: return
        val menu = PopupMenu(this, anchor)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!item.isVideo) {
            actions += "Texto en vivo" to { runOcr(item) }
            actions += "Recortar sujeto" to { runSegmentation(item) }
        }
        if (item.isLive) {
            actions += "Elegir portada" to { openKeyPanel(item) }
            actions += "Exportar como video" to { exportVideo(item) }
            actions += "Exportar como GIF" to { exportGif(item) }
        }
        actions.forEachIndexed { i, a -> menu.menu.add(Menu.NONE, i, i, a.first) }
        menu.setOnMenuItemClickListener { actions[it.itemId].second(); true }
        menu.show()
    }

    private fun showBusy(text: String?) {
        busyText.text = text
        busyText.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun say(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // Texto en vivo (ML Kit, en el cel)
    private fun runOcr(item: MediaRepo.Item) {
        val image = try {
            InputImage.fromFilePath(this, item.uri)
        } catch (e: Exception) {
            say("No se pudo abrir la foto"); return
        }
        showBusy("Buscando texto…")
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { result ->
                showBusy(null)
                if (result.text.isBlank()) say("No encontré texto en esta foto")
                else showTextDialog(result.text)
            }
            .addOnFailureListener { e ->
                showBusy(null)
                say("Texto en vivo no disponible todavía (${e.message}). Intenta en un rato.")
            }
    }

    private fun showTextDialog(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(22), dp(12), dp(22), dp(12))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Texto en la foto")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copiar todo") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Texto", text))
                say("Texto copiado")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // Recortar sujeto (ML Kit Subject Segmentation)
    private fun runSegmentation(item: MediaRepo.Item) {
        showBusy("Recortando sujeto…")
        io.execute {
            val bmp = MediaRepo.loadFull(this, item.uri, 1600, software = true)
            runOnUiThread {
                if (bmp == null) {
                    showBusy(null); say("No se pudo abrir la foto"); return@runOnUiThread
                }
                val options = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
                SubjectSegmentation.getClient(options).process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { r ->
                        showBusy(null)
                        val fg = r.foregroundBitmap
                        if (fg == null) say("No encontré un sujeto claro") else showCutout(fg)
                    }
                    .addOnFailureListener { e ->
                        showBusy(null)
                        say("Recorte no disponible todavía (${e.message}). Se está descargando; intenta en un rato.")
                    }
            }
        }
    }

    private fun showCutout(bmp: Bitmap) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setBackgroundColor(0xFF3A3A3A.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sujeto recortado")
            .setView(iv)
            .setPositiveButton("Compartir") { _, _ ->
                io.execute {
                    val uri = Exporter.savePng(this, bmp, "RECORTE")
                    runOnUiThread {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, "Compartir recorte"))
                    }
                }
            }
            .setNegativeButton("Guardar") { _, _ ->
                io.execute {
                    Exporter.savePng(this, bmp, "RECORTE")
                    runOnUiThread { say("Recorte guardado en la galería") }
                }
            }
            .setNeutralButton("Cerrar", null)
            .show()
    }

    // Exportar
    private fun exportVideo(item: MediaRepo.Item) {
        val fx = fxOf(item)
        showBusy("Exportando video…")
        io.execute {
            val msg = try {
                val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item))
                    ?: throw IllegalStateException("sin video")
                if (fx == Fx.LOOP || fx == Fx.BOUNCE) {
                    val f = Frames.load(file, maxSide = 960, maxFrames = 90) ?: throw IllegalStateException("sin fotogramas")
                    val out = File(cacheDir, "export.mp4")
                    Exporter.framesToMp4(f, fx == Fx.BOUNCE, MotionPhotoWriter.readFilter(this, item.uri), out)
                    f.recycle()
                    Exporter.saveVideoFile(this, out, "_" + fx.name)
                    out.delete()
                } else {
                    Exporter.saveVideoFile(this, file, "_LIVE")
                }
                "Video guardado en la galería"
            } catch (e: Throwable) {
                "No se pudo exportar: ${e.message}"
            }
            runOnUiThread { showBusy(null); say(msg) }
        }
    }

    private fun exportGif(item: MediaRepo.Item) {
        val bounce = fxOf(item) == Fx.BOUNCE
        showBusy("Creando GIF…")
        io.execute {
            val msg = try {
                val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item))
                    ?: throw IllegalStateException("sin video")
                val f = Frames.load(file, maxSide = 480, maxFrames = 60) ?: throw IllegalStateException("sin fotogramas")
                val out = File(cacheDir, "export.gif")
                Exporter.framesToGif(f, bounce, MotionPhotoWriter.readFilter(this, item.uri), out)
                f.recycle()
                Exporter.saveGif(this, out)
                out.delete()
                "GIF guardado en la galería"
            } catch (e: Throwable) {
                "No se pudo crear el GIF: ${e.message}"
            }
            runOnUiThread { showBusy(null); say(msg) }
        }
    }

    // Elegir portada
    private var keyItem: MediaRepo.Item? = null

    private fun openKeyPanel(item: MediaRepo.Item) {
        val holder = holderAt(current) ?: return
        clearEffect()
        activeHolder = holder
        keyItem = item
        showBusy("Cargando…")
        withFrames(item) { f ->
            showBusy(null)
            if (f == null) {
                say("No se pudo cargar el movimiento"); return@withFrames
            }
            keyPanel.visibility = View.VISIBLE
            bottomBar.visibility = View.INVISIBLE
            pager.isUserInputEnabled = false
            holder.frames.visibility = View.VISIBLE
            holder.frames.alpha = 1f
            keySeek.max = f.bitmaps.size - 1
            keySeek.progress = f.bitmaps.size / 2
            holder.frames.setImageBitmap(f.bitmaps[keySeek.progress])
            keySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    frames?.bitmaps?.getOrNull(p)?.let { holder.frames.setImageBitmap(it) }
                    if (fromUser && p % 3 == 0) sb.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
    }

    private fun closeKeyPanel(apply: Boolean) {
        keyPanel.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
        pager.isUserInputEnabled = true
        val item = keyItem ?: return
        keyItem = null
        val f = frames
        if (!apply || f == null) {
            applyEffect(); return
        }
        val timeUs = (keySeek.progress / f.fps * 1_000_000).toLong()
        showBusy("Guardando portada…")
        io.execute {
            val ok = try {
                MotionPhotoWriter.replaceKeyPhoto(this, item.uri, timeUs)
            } catch (_: Exception) {
                false
            }
            val bmp = if (ok) MediaRepo.loadFull(this, item.uri, 2400) else null
            runOnUiThread {
                showBusy(null)
                if (ok) {
                    holderAt(current)?.photo?.setImageBitmap(bmp)
                    say("Portada actualizada")
                } else {
                    say("Solo se puede cambiar la portada de fotos tomadas con Camaratrix")
                }
                applyEffect()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_ALBUM = "album"
    }

    // ---------- Páginas ----------

    inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val page: View = view.findViewById(R.id.page)
        val content: View = view.findViewById(R.id.content)
        val photo: ImageView = view.findViewById(R.id.photo)
        val frames: ImageView = view.findViewById(R.id.frames)
        val playerView: PlayerView = view.findViewById(R.id.player)
        val animator = FrameAnimator(frames)
        var boundId = -1L
    }

    inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
            val holder = PageHolder(view)
            val slop = ViewConfiguration.get(parent.context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var moved = false
            var longPressed = false
            val startRunnable = Runnable {
                longPressed = true
                startPlayback(holder)
            }

            holder.page.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        moved = false
                        longPressed = false
                        main.postDelayed(startRunnable, 200)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (playing !== holder &&
                            (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)
                        ) {
                            moved = true
                            main.removeCallbacks(startRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(startRunnable)
                        if (playing === holder) stopPlayback()
                        else if (!moved && !longPressed) setBars(!barsVisible) // toque simple
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(startRunnable)
                        if (playing === holder) stopPlayback()
                    }
                }
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val item = items[position]
            holder.boundId = item.id
            holder.photo.setImageDrawable(null)
            holder.frames.visibility = View.GONE
            holder.playerView.alpha = 0f
            val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 3 / 2
            io.execute {
                val bmp: Bitmap? = if (item.isVideo) MediaRepo.thumbnail(this@ViewerActivity, item.uri, 1080)
                else MediaRepo.loadFull(this@ViewerActivity, item.uri, maxSide)
                if (item.isLive) MotionPhotoWriter.extractVideo(this@ViewerActivity, item.uri, videoFile(item))
                runOnUiThread {
                    if (holder.boundId == item.id) holder.photo.setImageBitmap(bmp)
                }
            }
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.animator.stop()
            if (activeHolder === holder) clearEffect()
        }
    }

    // ---------- Tira de miniaturas ----------

    inner class StripHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val selected: View = view.findViewById(R.id.selected)
        var boundId = -1L
    }

    inner class StripAdapter : RecyclerView.Adapter<StripHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_strip, parent, false)
            val holder = StripHolder(view)
            view.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) pager.setCurrentItem(pos, false)
            }
            return holder
        }

        override fun onBindViewHolder(holder: StripHolder, position: Int) {
            val item = items[position]
            val isSel = position == current
            holder.selected.visibility = if (isSel) View.VISIBLE else View.GONE
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                width = dp(if (isSel) 44 else 30)
            }
            if (holder.boundId == item.id) return
            holder.boundId = item.id
            holder.thumb.setImageDrawable(null)
            io.execute {
                val bmp = MediaRepo.thumbnail(this@ViewerActivity, item.uri, 160)
                runOnUiThread { if (holder.boundId == item.id) holder.thumb.setImageBitmap(bmp) }
            }
        }
    }
}
