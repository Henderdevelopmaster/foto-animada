package com.hender.fotoanimada

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Todas las fotos del celular en cuadrícula, como la fototeca de iPhone. */
class GalleryActivity : ComponentActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var count: TextView
    private lateinit var banner: TextView
    private val items = mutableListOf<MediaRepo.Item>()
    private var all = listOf<MediaRepo.Item>()
    private var album = MediaRepo.ALBUM_ALL
    private lateinit var albums: LinearLayout
    private lateinit var selectButton: TextView
    private lateinit var selectBar: View
    private lateinit var selCount: TextView
    private var selecting = false
    private val selected = linkedSetOf<Long>()

    private val deleteRequest =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) {
            if (it.resultCode == RESULT_OK) {
                setSelecting(false)
                reload()
            }
        }
    private val io = Executors.newFixedThreadPool(3)

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        grid = findViewById(R.id.grid)
        count = findViewById(R.id.count)
        banner = findViewById(R.id.banner)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        albums = findViewById(R.id.albums)
        selectButton = findViewById(R.id.selectButton)
        selectBar = findViewById(R.id.selectBar)
        selCount = findViewById(R.id.selCount)
        selectButton.setOnClickListener { setSelecting(!selecting) }
        findViewById<View>(R.id.selShare).setOnClickListener { shareSelected() }
        findViewById<View>(R.id.selDelete).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.selFav).setOnClickListener { favSelected() }
        banner.setOnClickListener {
            GalleryAccess.markAsked(this)
            permission.launch(GalleryAccess.permissions())
        }

        val span = 4
        grid.layoutManager = GridLayoutManager(this, span)
        grid.adapter = Adapter(resources.displayMetrics.widthPixels / span)

        if (!GalleryAccess.has(this) && !GalleryAccess.askedBefore(this)) {
            GalleryAccess.markAsked(this)
            permission.launch(GalleryAccess.permissions())
        } else {
            reload()
        }
    }

    override fun onRestart() {
        super.onRestart()
        reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selecting) setSelecting(false) else super.onBackPressed()
    }

    private fun reload() {
        banner.visibility = if (GalleryAccess.hasFull(this)) View.GONE else View.VISIBLE
        banner.text = if (GalleryAccess.has(this))
            "Solo ves algunas fotos. Toca para dar acceso a toda la galería."
        else
            "Toca para ver todas las fotos de tu celular aquí."
        io.execute {
            val list = MediaRepo.list(this)
            runOnUiThread {
                all = list
                buildAlbums()
                showAlbum(album)
            }
        }
    }

    private fun buildAlbums() {
        albums.removeAllViews()
        val fixed = listOf(
            MediaRepo.ALBUM_ALL to "Todas",
            MediaRepo.ALBUM_FAVS to "Favoritas",
            MediaRepo.ALBUM_LIVE to "Live",
            MediaRepo.ALBUM_VIDEOS to "Videos",
        )
        val folders = all.groupingBy { it.album }.eachCount()
            .filterKeys { it.isNotBlank() }
            .entries.sortedByDescending { it.value }
            .map { it.key to it.key }
        for ((key, label) in fixed + folders) {
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setBackgroundResource(R.drawable.bg_filter_chip)
                tag = key
                setOnClickListener { showAlbum(key) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            albums.addView(chip, lp)
        }
    }

    private fun showAlbum(key: String) {
        album = key
        for (i in 0 until albums.childCount) {
            val v = albums.getChildAt(i) as TextView
            v.isSelected = v.tag == key
            v.setTextColor(if (v.isSelected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        items.clear()
        items.addAll(MediaRepo.filter(all, key, MediaRepo.favorites(this)))
        grid.adapter?.notifyDataSetChanged()
        val videos = items.count { it.isVideo }
        count.text = "${items.size - videos} fotos" + if (videos > 0) " · $videos videos" else ""
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Selección (como iPhone) ----------

    private fun setSelecting(on: Boolean) {
        selecting = on
        selected.clear()
        selectButton.text = if (on) "Cancelar" else "Seleccionar"
        selectBar.visibility = if (on) View.VISIBLE else View.GONE
        grid.setPadding(0, 0, 0, if (on) dp(90) else 0)
        updateSelCount()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun toggle(id: Long, pos: Int) {
        if (!selected.add(id)) selected.remove(id)
        grid.adapter?.notifyItemChanged(pos)
        grid.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        updateSelCount()
    }

    private fun updateSelCount() {
        selCount.text = when (selected.size) {
            0 -> "Selecciona elementos"
            1 -> "1 seleccionado"
            else -> "${selected.size} seleccionados"
        }
    }

    private fun selectedItems() = all.filter { it.id in selected }

    private fun shareSelected() {
        val list = selectedItems()
        if (list.isEmpty()) return
        val uris = ArrayList(list.map { it.uri })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = if (list[0].isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (list.any { it.isVideo }) "*/*" else "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Compartir"))
    }

    private fun favSelected() {
        val favs = MediaRepo.favorites(this)
        val list = selectedItems()
        if (list.isEmpty()) return
        val allFav = list.all { it.id.toString() in favs }
        list.forEach { MediaRepo.setFavorite(this, it.id, !allFav) }
        android.widget.Toast.makeText(
            this, if (allFav) "Quitadas de favoritas" else "Agregadas a favoritas", android.widget.Toast.LENGTH_SHORT
        ).show()
        setSelecting(false)
        showAlbum(album)
    }

    private fun deleteSelected() {
        val list = selectedItems()
        if (list.isEmpty()) return
        // Android pide confirmar (como "Eliminar N fotos" en iPhone)
        try {
            val sender = android.provider.MediaStore.createDeleteRequest(contentResolver, list.map { it.uri }).intentSender
            deleteRequest.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "No se pudo eliminar", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val live: View = view.findViewById(R.id.liveIcon)
        val duration: View = view.findViewById(R.id.videoIcon)
        val shade: View = view.findViewById(R.id.selShade)
        val check: View = view.findViewById(R.id.selCheck)
        var boundId = -1L
    }

    inner class Adapter(private val cell: Int) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid, parent, false)
            v.layoutParams.height = cell
            val h = Holder(v)
            v.setOnLongClickListener {
                val item = items.getOrNull(h.bindingAdapterPosition) ?: return@setOnLongClickListener false
                if (!selecting) setSelecting(true)
                toggle(item.id, h.bindingAdapterPosition)
                true
            }
            v.setOnClickListener {
                val item = items.getOrNull(h.bindingAdapterPosition) ?: return@setOnClickListener
                if (selecting) {
                    toggle(item.id, h.bindingAdapterPosition)
                    return@setOnClickListener
                }
                startActivity(
                    Intent(this@GalleryActivity, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_ID, item.id)
                        .putExtra(ViewerActivity.EXTRA_ALBUM, album)
                )
            }
            return h
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.live.visibility = if (item.isLive) View.VISIBLE else View.GONE
            holder.duration.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            val sel = item.id in selected
            holder.shade.visibility = if (sel) View.VISIBLE else View.GONE
            holder.check.visibility = if (sel) View.VISIBLE else View.GONE
            if (holder.boundId == item.id) return
            holder.boundId = item.id
            holder.thumb.setImageDrawable(null)
            io.execute {
                val bmp = MediaRepo.thumbnail(this@GalleryActivity, item.uri, 256)
                runOnUiThread { if (holder.boundId == item.id) holder.thumb.setImageBitmap(bmp) }
            }
        }
    }
}
