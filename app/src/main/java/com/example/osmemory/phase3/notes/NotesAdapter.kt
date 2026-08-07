package com.example.osmemory.phase3.notes

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.osmemory.R
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class NotesAdapter(
    private val onClick: (NoteRecord) -> Unit
) : ListAdapter<NoteRecord, NotesAdapter.NoteViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder =
        NoteViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.phase3_notes_item, parent, false)
        )

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView as MaterialCardView
        private val preview = itemView.findViewById<TextView>(R.id.notesItemPreview)
        private val time = itemView.findViewById<TextView>(R.id.notesItemTime)
        private val memoryBadge = itemView.findViewById<TextView>(R.id.notesItemMemoryBadge)

        fun bind(note: NoteRecord) {
            val context = itemView.context
            preview.text = note.content.trim().ifEmpty { "空白记录" }
            preview.alpha = if (note.content.isBlank()) 0.55f else 1f
            time.text = TIME_FORMAT.format(Date(note.updatedAt))
            memoryBadge.text = if (note.isLinked) "已关联记忆" else "仅本地"
            memoryBadge.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (note.isLinked) R.color.semantic_normal else R.color.semantic_public
                )
            )
            memoryBadge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (note.isLinked) R.color.semantic_normal_bg else R.color.semantic_public_bg
                )
            )
            card.strokeWidth = dp(1)
            card.strokeColor = ContextCompat.getColor(context, R.color.brand_outline)
            card.cardElevation = 0f
            card.setOnClickListener { onClick(note) }
        }

        private fun dp(value: Int): Int =
            (value * itemView.resources.displayMetrics.density).toInt()
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        val DIFF = object : DiffUtil.ItemCallback<NoteRecord>() {
            override fun areItemsTheSame(oldItem: NoteRecord, newItem: NoteRecord): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NoteRecord, newItem: NoteRecord): Boolean =
                oldItem == newItem
        }
    }
}
