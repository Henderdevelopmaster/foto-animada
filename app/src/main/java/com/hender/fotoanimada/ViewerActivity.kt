package com.hender.fotoanimada

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private var vividSheet: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Registro.install(this)
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
                videoHolder?.playerView?.animate()?.alpha(1f)?.setDuration(90)?.start()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (playing != null) stopPlayback() else if (vitalityOn) endVitality()
                }
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
        resetLive()
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
            Fx.LIVE -> {
                hint.text = "Mantén presionado para ver el movimiento"
                prepareLive(holder, item)
            }
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
        val selected = fxOf(item)
        fun pick(fx: Fx) {
            prefs.edit().putString("fx_${item.id}", fx.name).apply()
            fxChip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applyEffect()
        }
        val options = if (item.isLive) listOf(
            Sheet.Item("Live", R.drawable.ic_live_small, selected == Fx.LIVE, subtitle = "Mantén presionado para verla moverse") { pick(Fx.LIVE) },
            Sheet.Item("Bucle", R.drawable.ic_loop, selected == Fx.LOOP, subtitle = "Se repite sin parar") { pick(Fx.LOOP) },
            Sheet.Item("Rebote", R.drawable.ic_bounce, selected == Fx.BOUNCE, subtitle = "Va y vuelve") { pick(Fx.BOUNCE) },
            Sheet.Item("Exposición larga", R.drawable.ic_long_exposure, selected == Fx.LONG, subtitle = "Mezcla el movimiento en una foto") { pick(Fx.LONG) },
            Sheet.Item("Desactivado", R.drawable.ic_off, selected == Fx.OFF, subtitle = "Solo la foto") { pick(Fx.OFF) },
        ) else listOf(
            Sheet.Item("Foto normal", R.drawable.ic_photo, selected == Fx.STILL) { pick(Fx.STILL) },
            Sheet.Item("Animar", R.drawable.ic_animate, selected == Fx.ANIMATE, subtitle = "Zoom y paneo lento") { pick(Fx.ANIMATE) },
        )
        Sheet.actions(this, if (item.isLive) "Efecto Live" else "Efecto", options)
    }

    private fun withFrames(item: MediaRepo.Item, onReady: (Frames?) -> Unit) {
        frames?.takeIf { framesId == item.id }?.let { onReady(it); return }
        io.execute {
            val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item))
            // Más nítido y más fluido que antes (540 px a 15 cuadros/s se veía borroso y a saltos)
            val f = file?.let { Frames.load(it, maxSide = 900, maxFrames = 60) }
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

    // ---------- LIVE: reproducción estilo iPhone ----------
    //
    // 1) Al llegar a una foto Live se precarga el clip, recortado a 1.5 s antes y 1.5 s después.
    // 2) "Vitalidad": un movimiento corto y sin sonido que termina justo en la foto.
    // 3) Al mantener presionado: desenfoque breve, un zoom suave y el clip completo con sonido.
    // 4) Al soltar o terminar: vuelve a la foto con el mismo desenfoque.

    private var liveHolder: PageHolder? = null   // página con el clip precargado
    private var liveKeyMs = 0L                    // posición de la foto dentro del clip
    private var vitalityMsg: androidx.media3.exoplayer.PlayerMessage? = null
    private var vitalityOn = false
    private var scaleAnim: android.animation.Animator? = null
    private val metaCache = HashMap<Long, Long>()

    private fun prepareLive(holder: PageHolder, item: MediaRepo.Item) {
        val token = effectToken
        io.execute {
            val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item))
            val keyUs = metaCache.getOrPut(item.id) { MotionPhotoWriter.readKeyUs(this, item.uri) }
            runOnUiThread {
                if (token != effectToken || file == null) return@runOnUiThread
                val keyMs = keyUs / 1000
                val startMs = (keyMs - 1500).coerceAtLeast(0)
                liveKeyMs = keyMs - startMs
                val media = MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(keyMs + 1500)
                            .build()
                    )
                    .build()
                holder.playerView.alpha = 0f
                holder.playerView.player = player
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItem(media)
                player.prepare()
                player.pause()
                liveHolder = holder
                // Mini movimiento al llegar (como al deslizar en Fotos de iPhone)
                main.postDelayed({ if (token == effectToken) startVitality() }, 380)
            }
        }
    }

    private fun startVitality() {
        val holder = liveHolder ?: return
        if (playing != null) return
        vitalityOn = true
        player.volume = 0f
        player.seekTo((liveKeyMs - 900).coerceAtLeast(0))
        player.play()
        holder.playerView.animate().alpha(1f).setDuration(120).start()
        blurPulse(holder.content, 8f, 220)
        vitalityMsg?.cancel()
        vitalityMsg = player.createMessage { _, _ -> endVitality() }
            .setPosition(liveKeyMs)
            .setLooper(Looper.getMainLooper())
            .setDeleteAfterDelivery(true)
            .send()
    }

    private fun endVitality() {
        if (!vitalityOn) return
        vitalityOn = false
        vitalityMsg?.cancel()
        vitalityMsg = null
        val holder = liveHolder ?: return
        if (playing != null) return
        holder.playerView.animate().alpha(0f).setDuration(260).withEndAction {
            if (playing == null) {
                player.pause()
                player.seekTo(0)
                player.volume = 1f
            }
        }.start()
        blurPulse(holder.content, 6f, 260)
    }

    private fun startPlayback(holder: PageHolder) {
        val item = items.getOrNull(holder.bindingAdapterPosition) ?: return
        if (!item.isLive || fxOf(item) != Fx.LIVE) return
        if (liveHolder !== holder) {
            prepareLive(holder, item)
            return
        }
        vitalityOn = false
        vitalityMsg?.cancel()
        vitalityMsg = null
        playing = holder
        pager.isUserInputEnabled = false
        holder.page.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setBars(false)

        player.volume = 1f
        player.seekTo(0)
        player.play()
        holder.playerView.animate().cancel()
        holder.playerView.animate().alpha(1f).setDuration(140).start()
        blurPulse(holder.content, 14f, 300)
        // Zoom suave mientras se mueve (la foto "cobra vida")
        animateScale(holder.content, 1.045f, 650, android.view.animation.DecelerateInterpolator(1.6f))
        pulseLiveChip(true)
    }

    private fun stopPlayback() {
        val holder = playing ?: return
        playing = null
        pager.isUserInputEnabled = true
        setBars(true)
        pulseLiveChip(false)
        holder.playerView.animate().cancel()
        holder.playerView.animate().alpha(0f).setDuration(300).withEndAction {
            if (playing == null) {
                player.pause()
                player.seekTo(0)
            }
        }.start()
        blurPulse(holder.content, 12f, 320)
        animateScale(holder.content, 1f, 320, android.view.animation.DecelerateInterpolator())
    }

    /** Detiene todo lo del Live (al cambiar de foto o de efecto). */
    private fun resetLive() {
        vitalityOn = false
        vitalityMsg?.cancel()
        vitalityMsg = null
        stopPlayback()
        liveHolder?.let { h ->
            h.playerView.animate().cancel()
            h.playerView.alpha = 0f
            h.content.scaleX = 1f
            h.content.scaleY = 1f
            if (android.os.Build.VERSION.SDK_INT >= 31) h.content.setRenderEffect(null)
            h.playerView.player = null
        }
        if (liveHolder != null) player.stop()
        liveHolder = null
    }

    private fun animateScale(v: View, to: Float, ms: Long, interp: android.animation.TimeInterpolator) {
        scaleAnim?.cancel()
        scaleAnim = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_X, v.scaleX, to),
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_Y, v.scaleY, to),
            )
            duration = ms
            interpolator = interp
            start()
        }
    }

    /** Desenfoque que sube y baja: tapa el cambio entre foto y video (Android 12+). */
    private fun blurPulse(v: View, peak: Float, ms: Long) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            addUpdateListener {
                val t = it.animatedValue as Float
                val r = peak * kotlin.math.sin(t * Math.PI).toFloat()
                v.setRenderEffect(
                    if (r < 0.5f) null
                    else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)
                )
            }
            start()
        }
    }

    private var chipPulse: ValueAnimator? = null

    private fun pulseLiveChip(on: Boolean) {
        chipPulse?.cancel()
        if (!on) {
            fxChip.alpha = 1f
            fxChip.setTextColor(0xFFFFFFFF.toInt())
            return
        }
        fxChip.setTextColor(0xFF00E676.toInt())
        chipPulse = ValueAnimator.ofFloat(1f, 0.55f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { fxChip.alpha = it.animatedValue as Float }
            start()
        }
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
        if (!item.isLive) {
            sendUri(item.uri, if (item.isVideo) "video/mp4" else "image/jpeg")
            return
        }
        // Las fotos animadas no se mueven en Instagram ni en WhatsApp:
        // allá hay que mandarlas como video.
        val last = prefs.getString(KEY_SHARE, "story")
        Sheet.actions(this, "Compartir", listOf(
            Sheet.Item("Video para historias", R.drawable.ic_export_video, checked = last == "story",
                subtitle = "Vertical 9:16, llena la pantalla") { shareAs(item, "story") },
            Sheet.Item("Video", R.drawable.ic_export_video, checked = last == "video",
                subtitle = "Tal como se ve, en bucle") { shareAs(item, "video") },
            Sheet.Item("GIF", R.drawable.ic_gif, checked = last == "gif") { shareAs(item, "gif") },
            Sheet.Item("Foto quieta", R.drawable.ic_photo, checked = last == "photo") { shareAs(item, "photo") },
        ), subtitle = "En Instagram y WhatsApp solo se mueve el video")
    }

    private fun sendUri(uri: android.net.Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartir"))
    }

    /** Prepara la foto animada en el formato elegido y abre el menú de compartir. */
    private fun shareAs(item: MediaRepo.Item, kind: String) {
        prefs.edit().putString(KEY_SHARE, kind).apply()
        prepare(item, kind) { uri -> sendUri(uri, if (kind == "gif") "image/gif" else if (kind == "photo") "image/jpeg" else "video/mp4") }
    }

    /**
     * Deja el archivo listo en el formato pedido (video, 9:16, GIF o la foto tal cual)
     * y lo entrega. Lo exportado queda también guardado en la galería.
     */
    private fun prepare(item: MediaRepo.Item, kind: String, onReady: (android.net.Uri) -> Unit) {
        if (kind == "photo" || item.isVideo || !item.isLive) {
            onReady(item.uri)
            return
        }
        showBusy(if (kind == "gif") "Creando GIF…" else "Creando video…")
        io.execute {
            var uri: android.net.Uri? = null
            var msg: String? = null
            try {
                val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item))
                    ?: throw IllegalStateException("esta foto no tiene movimiento")
                val filter = MotionPhotoWriter.readFilter(this, item.uri)
                val bounce = fxOf(item) == Fx.BOUNCE
                when (kind) {
                    "gif" -> {
                        val f = Frames.load(file, maxSide = 480, maxFrames = 60) ?: throw IllegalStateException("sin fotogramas")
                        val out = File(cacheDir, "share.gif")
                        Exporter.framesToGif(f, bounce, filter, out)
                        f.recycle()
                        uri = Exporter.saveGif(this, out)
                        out.delete()
                    }
                    "story" -> {
                        val f = Frames.load(file, maxSide = 1280, maxFrames = 90) ?: throw IllegalStateException("sin fotogramas")
                        val out = File(cacheDir, "share.mp4")
                        Exporter.framesToStory(f, bounce, filter, out)
                        f.recycle()
                        uri = Exporter.saveVideoFile(this, out, "_HISTORIA")
                        out.delete()
                    }
                    else -> {
                        val f = Frames.load(file, maxSide = 1280, maxFrames = 90) ?: throw IllegalStateException("sin fotogramas")
                        val out = File(cacheDir, "share.mp4")
                        Exporter.framesToMp4(f, bounce, filter, out)
                        f.recycle()
                        uri = Exporter.saveVideoFile(this, out, "_LIVE")
                        out.delete()
                    }
                }
            } catch (e: Throwable) {
                msg = "No se pudo preparar: ${e.message}"
            }
            runOnUiThread {
                showBusy(null)
                val u = uri
                if (u != null) onReady(u) else msg?.let { say(it) }
            }
        }
    }

    // ---------- Seguir editando en otra app ----------

    /** CapCut internacional y la versión china (Jianying). */
    private val capcutPkgs = listOf("com.lemon.lvoverseas", "com.lemon.lv")

    private fun installedEditor() = capcutPkgs.firstOrNull {
        try {
            packageManager.getPackageInfo(it, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showEditMenu(item: MediaRepo.Item) {
        val has = installedEditor() != null
        val actions = mutableListOf<Sheet.Item>()
        if (item.isLive || item.isVideo) {
            actions += Sheet.Item(
                if (has) "Abrir en CapCut" else "Instalar CapCut",
                R.drawable.ic_export_video,
                subtitle = if (has) "Se manda como video" else "No lo tienes instalado",
            ) { openInCapCut(item) }
        }
        actions += Sheet.Item("Otra app de edición", R.drawable.ic_tune,
            subtitle = "Elige entre las que tengas") { openInOther(item) }
        Sheet.actions(this, "Seguir editando", actions)
    }

    private fun openInCapCut(item: MediaRepo.Item) {
        val pkg = installedEditor()
        if (pkg == null) {
            val id = capcutPkgs[0]
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$id")))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$id")))
            }
            return
        }
        prepare(item, "video") { uri ->
            val send = Intent(Intent.ACTION_SEND).apply {
                setPackage(pkg)
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(send)
            } catch (e: Exception) {
                // Si CapCut no acepta el envío, queda el menú normal
                sendUri(uri, "video/mp4")
            }
        }
    }

    private fun openInOther(item: MediaRepo.Item) {
        val kind = if (item.isLive || item.isVideo) "video" else "photo"
        prepare(item, kind) { uri ->
            val mime = if (kind == "video") "video/mp4" else "image/jpeg"
            val edit = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, mime)
                putExtra("mimeType", mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(edit, "Editar con"))
            } catch (e: Exception) {
                sendUri(uri, mime)
            }
        }
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
        vividSheet?.dismiss()
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
        val actions = mutableListOf<Sheet.Item>()
        if (!item.isVideo) {
            actions += Sheet.Item("Viva", R.drawable.ic_vivid,
                subtitle = if (item.isLive) "Compara y guarda una copia quieta" else "Compara el resultado antes de guardar",
            ) { compareVivid(item) }
            actions += Sheet.Item("Texto en vivo", R.drawable.ic_text_scan, subtitle = "Copia el texto de la foto") { runOcr(item) }
            actions += Sheet.Item("Recortar sujeto", R.drawable.ic_cutout, subtitle = "Separa a la persona del fondo") { runSegmentation(item) }
        }
        if (item.isLive) {
            actions += Sheet.Item("Elegir portada", R.drawable.ic_cover) { openKeyPanel(item) }
            actions += Sheet.Item("Video para historias", R.drawable.ic_export_video,
                subtitle = "Vertical 9:16 con fondo desenfocado") { exportStory(item) }
            actions += Sheet.Item("Exportar como video", R.drawable.ic_export_video) { exportVideo(item) }
            actions += Sheet.Item("Exportar como GIF", R.drawable.ic_gif) { exportGif(item) }
        }
        actions += Sheet.Item("Seguir editando en otra app", R.drawable.ic_wand,
            subtitle = "CapCut y las demás") { showEditMenu(item) }
        Sheet.actions(this, null, actions)
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
            setLineSpacing(0f, 1.15f)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_segmented)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        Sheet.custom(this, "Texto en la foto", tv, listOf(
            "Copiar todo" to {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Texto", text))
                say("Texto copiado")
            },
            "Cerrar" to {},
        ), primary = 0, scroll = text.length > 300)
    }

    private fun compareVivid(item: MediaRepo.Item) {
        vividSheet?.dismiss()
        val original = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Original"
            setTextColor(android.graphics.Color.WHITE)
            minimumHeight = dp(48)
        }
        val vivid = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Viva"
            setTextColor(android.graphics.Color.WHITE)
            minimumHeight = dp(48)
        }
        val variants = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(original, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(vivid, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            check(original.id)
        }
        original.isEnabled = false
        vivid.isEnabled = false
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Foto original"
        }
        val status = TextView(this).apply {
            text = "Preparando la comparación…"
            setTextColor(android.graphics.Color.WHITE)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val save = android.widget.Button(this).apply {
            text = "Guardar copia"
            isAllCaps = false
            isEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp((resources.configuration.screenHeightDp / 3).coerceIn(120, 280)),
            ))
            addView(variants)
            addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = Sheet.custom(this, "Comparar Viva", content, listOf("Cancelar" to {}), primary = -1)
        vividSheet = dialog
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            variants.setOnCheckedChangeListener(null)
            if (vividSheet === dialog) vividSheet = null
        }
        save.setOnClickListener {
            dialog.dismiss()
            runVivid(item)
        }

        io.execute {
            val previews = try {
                val before = MediaRepo.loadFull(this, item.uri, 1280, software = true)
                    ?: throw IllegalStateException("No se pudo abrir la foto")
                val after = try {
                    before.copy(Bitmap.Config.ARGB_8888, true)?.also { copy ->
                        try {
                            Enhance.bitmap(this, copy, vivid = true, auto = false)
                        } catch (e: Throwable) {
                            copy.recycle()
                            throw e
                        }
                    } ?: throw IllegalStateException("No se pudo preparar la foto")
                } catch (e: Throwable) {
                    before.recycle()
                    throw e
                }
                before to after
            } catch (e: Throwable) {
                android.util.Log.e("Viewer", "Comparar Viva", e)
                null
            }
            runOnUiThread {
                if (vividSheet !== dialog || !dialog.isShowing) {
                    previews?.first?.recycle()
                    previews?.second?.recycle()
                    return@runOnUiThread
                }
                if (previews == null) {
                    status.text = "No se pudo preparar la comparación"
                    return@runOnUiThread
                }
                status.visibility = View.GONE
                image.setImageBitmap(previews.first)
                original.isEnabled = true
                vivid.isEnabled = true
                save.isEnabled = true
                variants.setOnCheckedChangeListener { _, checked ->
                    val showVivid = checked == vivid.id
                    image.setImageBitmap(if (showVivid) previews.second else previews.first)
                    image.contentDescription = if (showVivid) "Foto con Viva" else "Foto original"
                }
            }
        }
    }

    /** Viva sobre una foto ya tomada: guarda una copia nueva y la original queda igual. */
    private fun runVivid(item: MediaRepo.Item) {
        showBusy("Aplicando Viva…")
        io.execute {
            val saved = try {
                val src = MediaRepo.loadFull(this, item.uri, 4096, software = true)
                    ?: throw IllegalStateException("No se pudo abrir la foto")
                val bytes = try {
                    val bmp = Enhance.bitmap(this, src, vivid = true, auto = false)
                    try {
                        Processing.encode(bmp, 95)
                    } finally {
                        if (bmp !== src) bmp.recycle()
                    }
                } finally {
                    src.recycle()
                }
                MotionPhotoWriter.save(this, bytes, null, "_VIVA")
            } catch (e: Throwable) {
                android.util.Log.e("Viewer", "Viva", e)
                null
            }
            runOnUiThread {
                showBusy(null)
                if (saved == null) {
                    say("No se pudo aplicar Viva a esta foto")
                } else {
                    say("Listo: copia con Viva guardada")
                    startId = runCatching { android.content.ContentUris.parseId(saved) }.getOrDefault(startId)
                    reload()
                }
            }
        }
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
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.bg_checker)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            maxHeight = dp(360)
        }
        Sheet.custom(this, "Sujeto recortado", iv, listOf(
            "Compartir" to {
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
            },
            "Guardar" to {
                io.execute {
                    Exporter.savePng(this, bmp, "RECORTE")
                    runOnUiThread { say("Recorte guardado en la galería") }
                }
            },
        ), primary = 1)
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
                    val f = Frames.load(file, maxSide = 1280, maxFrames = 90) ?: throw IllegalStateException("sin fotogramas")
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

    private fun exportStory(item: MediaRepo.Item) {
        val bounce = fxOf(item) == Fx.BOUNCE
        showBusy("Creando video de historia…")
        io.execute {
            val msg = try {
                val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item))
                    ?: throw IllegalStateException("sin video")
                val f = Frames.load(file, maxSide = 1280, maxFrames = 90) ?: throw IllegalStateException("sin fotogramas")
                val out = File(cacheDir, "export.mp4")
                Exporter.framesToStory(f, bounce, MotionPhotoWriter.readFilter(this, item.uri), out)
                f.recycle()
                Exporter.saveVideoFile(this, out, "_HISTORIA")
                out.delete()
                "Video 9:16 guardado en la galería"
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
        private const val KEY_SHARE = "share_kind"
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
                val bmp = MediaRepo.thumbnail(this@ViewerActivity, item.uri, 240)
                runOnUiThread { if (holder.boundId == item.id) holder.thumb.setImageBitmap(bmp) }
            }
        }
    }
}
