package com.hender.fotoanimada

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
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

    private fun reload() {
        banner.visibility = if (GalleryAccess.hasFull(this)) View.GONE else View.VISIBLE
        banner.text = if (GalleryAccess.has(this))
            "Solo ves algunas fotos. Toca para dar acceso a toda la galería."
        else
            "Toca para ver todas las fotos de tu celular aquí."
        io.execute {
            val list = MediaRepo.list(this)
            runOnUiThread {
                items.clear()
                items.addAll(list)
                grid.adapter?.notifyDataSetChanged()
                count.text = "${items.size} fotos"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val live: View = view.findViewById(R.id.liveIcon)
        var boundId = -1L
    }

    inner class Adapter(private val cell: Int) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid, parent, false)
            v.layoutParams.height = cell
            val h = Holder(v)
            v.setOnClickListener {
                val item = items.getOrNull(h.bindingAdapterPosition) ?: return@setOnClickListener
                startActivity(
                    Intent(this@GalleryActivity, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_ID, item.id)
                )
            }
            return h
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.live.visibility = if (item.isLive) View.VISIBLE else View.GONE
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
