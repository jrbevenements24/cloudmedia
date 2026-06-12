package com.cloudmedia.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class MediaAdapter(
    private val onToggle: (MediaItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<GalleryRow> = emptyList()

    fun submit(newRows: List<GalleryRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    fun rowAt(pos: Int): GalleryRow = rows[pos]

    companion object { const val HEADER = 0; const val MEDIA = 1 }

    override fun getItemViewType(position: Int) =
        if (rows[position] is DateHeader) HEADER else MEDIA

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == HEADER)
            HeaderVH(inf.inflate(R.layout.item_header, parent, false))
        else
            MediaVH(inf.inflate(R.layout.item_media, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DateHeader -> (holder as HeaderVH).bind(row)
            is MediaItem -> (holder as MediaVH).bind(row)
        }
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val label: TextView = v.findViewById(R.id.headerLabel)
        fun bind(h: DateHeader) { label.text = h.label }
    }

    inner class MediaVH(v: View) : RecyclerView.ViewHolder(v) {
        private val img: ImageView = v.findViewById(R.id.thumb)
        private val check: View = v.findViewById(R.id.check)
        private val videoBadge: View = v.findViewById(R.id.videoBadge)
        private val duration: TextView = v.findViewById(R.id.duration)

        fun bind(item: MediaItem) {
            Glide.with(img).load(item.uri).centerCrop().into(img)
            check.visibility = if (item.selected) View.VISIBLE else View.GONE
            img.alpha = if (item.selected) 0.65f else 1f
            if (item.isVideo) {
                videoBadge.visibility = View.VISIBLE
                duration.visibility = View.VISIBLE
                duration.text = fmtDuration(item.durationMs)
            } else {
                videoBadge.visibility = View.GONE
                duration.visibility = View.GONE
            }
            itemView.setOnClickListener {
                item.selected = !item.selected
                check.visibility = if (item.selected) View.VISIBLE else View.GONE
                img.alpha = if (item.selected) 0.65f else 1f
                onToggle(item)
            }
        }

        private fun fmtDuration(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
