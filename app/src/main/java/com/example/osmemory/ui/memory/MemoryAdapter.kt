package com.example.osmemory.ui.memory

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
import com.example.osmemory.data.db.entity.MemoryItemEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆库卡片列表适配器
 * 展示：标题 / 内容 / 敏感级徽标 / 分类 / 来源 / 时间 / 置信度 / 标签
 */
class MemoryAdapter(private val onClick: (MemoryItemEntity) -> Unit) :
    ListAdapter<MemoryItemEntity, MemoryAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false),
            onClick
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onClick: (MemoryItemEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvTitle = itemView.findViewById<TextView>(R.id.tvTitle)
        private val tvContent = itemView.findViewById<TextView>(R.id.tvContent)
        private val tvPolicy = itemView.findViewById<TextView>(R.id.tvPolicy)
        private val tvCategory = itemView.findViewById<TextView>(R.id.tvCategory)
        private val tvMeta = itemView.findViewById<TextView>(R.id.tvMeta)
        private val tvTags = itemView.findViewById<TextView>(R.id.tvTags)

        fun bind(item: MemoryItemEntity) {
            val context = itemView.context
            tvTitle.text = item.title
            tvContent.text = item.content

            // 权限 / 敏感级徽标
            val (policyLabel, policyColor, policyBg) = when (item.policyLevel) {
                2 -> Triple("敏感", R.color.semantic_sensitive, R.color.semantic_sensitive_bg)
                0 -> Triple("公开", R.color.semantic_public, R.color.semantic_public_bg)
                else -> Triple("普通", R.color.semantic_normal, R.color.semantic_normal_bg)
            }
            tvPolicy.text = policyLabel
            tvPolicy.setTextColor(ContextCompat.getColor(context, policyColor))
            tvPolicy.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, policyBg))

            // 分类徽标
            tvCategory.text = item.category
            tvCategory.setTextColor(ContextCompat.getColor(context, R.color.semantic_category))
            tvCategory.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.semantic_category_bg))

            tvMeta.text = "%s · %s · 置信 %d%%".format(
                Locale.CHINA,
                sourceLabel(item.source),
                TIME_FORMAT.format(Date(item.createdAt)),
                (item.confidence * 100).toInt().coerceIn(0, 100)
            )

            tvTags.text = if (item.tags.isBlank()) "暂无标签" else item.tags

            itemView.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MemoryItemEntity>() {
            override fun areItemsTheSame(oldItem: MemoryItemEntity, newItem: MemoryItemEntity) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MemoryItemEntity, newItem: MemoryItemEntity) =
                oldItem == newItem
        }

        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        fun sourceLabel(source: String): String = when (source) {
            "console" -> "控制台"
            "notes" -> "记事本"
            "chat" -> "对话"
            "files" -> "文件"
            "demo" -> "示例"
            else -> source
        }
    }
}
