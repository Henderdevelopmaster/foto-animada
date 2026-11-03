package com.hender.fotoanimada

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Visor tipo iPhone: ves la foto quieta y, al mantenerla presionada,
 * se reproduce el movimiento con un zoom suave. Al soltar vuelve a la foto.
 */
class ViewerActivity : ComponentActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var liveBadge: TextView
    private lateinit var hint: TextView
    private lateinit var player: ExoPlayer

    private val items = mutableListOf<MediaRepo.Item>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var playing: PageHolder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        pager = findViewById(R.id.pager)
        liveBadge = findViewById(R.id.liveBadge)
        hint = findViewById(R.id.hint)

        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                playing?.playerView?.animate()?.alpha(1f)?.setDuration(120)?.start()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) stopPlayback()
            }
        })

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.share).setOnClickListener { shareCurrent() }
        findViewById<ImageButton>(R.id.delete).setOnClickListener { deleteCurrent() }

        pager.adapter = Adapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateBadge()
        })

        io.execute {
            val list = MediaRepo.list(this)
            runOnUiThread {
                items.clear()
                items.addAll(list)
                pager.adapter?.notifyDataSetChanged()
                if (items.isEmpty()) {
                    hint.text = "Todavía no hay fotos"
                }
                updateBadge()
            }
        }
    }

    private fun updateBadge() {
        val item = items.getOrNull(pager.currentItem)
        val live = item?.isLive == true
        liveBadge.visibility = if (live) View.VISIBLE else View.GONE
        hint.visibility = if (live || items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun videoFile(item: MediaRepo.Item) = File(cacheDir, "live_${item.id}.mp4")

    // ---------- Reproducción ----------

    private fun startPlayback(holder: PageHolder) {
        val item = items.getOrNull(holder.bindingAdapterPosition) ?: return
        if (!item.isLive) return
        val file = MotionPhotoWriter.extractVideo(this, item.uri, videoFile(item)) ?: return

        stopPlayback()
        playing = holder
        pager.isUserInputEnabled = false
        holder.page.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        holder.playerView.player = player
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.play()

        holder.content.animate().scaleX(1.04f).scaleY(1.04f).setDuration(250).start()
        liveBadge.animate().alpha(0.4f).setDuration(150).start()
    }

    private fun stopPlayback() {
        val holder = playing ?: return
        playing = null
        pager.isUserInputEnabled = true
        holder.playerView.animate().alpha(0f).setDuration(220).withEndAction {
            if (playing !== holder) holder.playerView.player = null
        }.start()
        holder.content.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        liveBadge.animate().alpha(1f).setDuration(150).start()
        player.pause()
        player.seekTo(0)
    }

    // ---------- Acciones ----------

    private fun shareCurrent() {
        val item = items.getOrNull(pager.currentItem) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartir foto"))
    }

    private fun deleteCurrent() {
        val pos = pager.currentItem
        val item = items.getOrNull(pos) ?: return
        stopPlayback()
        try {
            contentResolver.delete(item.uri, null, null)
            videoFile(item).delete()
            items.removeAt(pos)
            pager.adapter?.notifyItemRemoved(pos)
            if (items.isEmpty()) finish() else updateBadge()
        } catch (e: Exception) {
            hint.visibility = View.VISIBLE
            hint.text = "No se pudo eliminar"
        }
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        io.shutdown()
    }

    // ---------- Páginas ----------

    inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val page: View = view.findViewById(R.id.page)
        val content: View = view.findViewById(R.id.content)
        val photo: ImageView = view.findViewById(R.id.photo)
        val playerView: PlayerView = view.findViewById(R.id.player)
        var boundId = -1L
    }

    inner class Adapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
            val holder = PageHolder(view)
            val slop = ViewConfiguration.get(parent.context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            val startRunnable = Runnable { startPlayback(holder) }

            holder.page.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        main.postDelayed(startRunnable, 180)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (playing !== holder &&
                            (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)
                        ) {
                            main.removeCallbacks(startRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
            holder.playerView.alpha = 0f
            holder.content.scaleX = 1f
            holder.content.scaleY = 1f
            io.execute {
                val bmp = MediaRepo.thumbnail(this@ViewerActivity, item.uri, 2048)
                // Deja el video listo para que arranque al instante
                if (item.isLive) MotionPhotoWriter.extractVideo(this@ViewerActivity, item.uri, videoFile(item))
                runOnUiThread {
                    if (holder.boundId == item.id) holder.photo.setImageBitmap(bmp)
                }
            }
        }
    }
}
