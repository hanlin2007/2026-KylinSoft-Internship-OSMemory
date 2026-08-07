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
import com.example.osmemory.data.cloud.CloudMemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryItemEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆库卡片列表适配器（本地树 / 云端树共用）
 *
 * 展示：标题 / 内容 / 敏感级徽标 / 同步状态徽标 / 分类 / 来源 / 时间 / 置信度 / 标签
 * 交互：单击 = 查看 + 编辑（先画像后改）；长按 = 删除（确认）
 */
class MemoryAdapter(
    private val onClick: (MemoryRow) -> Unit,
    private val onLongClick: (MemoryRow) -> Unit
) : ListAdapter<MemoryRow, MemoryAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false),
            onClick,
            onLongClick
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onClick: (MemoryRow) -> Unit,
        private val onLongClick: (MemoryRow) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvTitle = itemView.findViewById<TextView>(R.id.tvTitle)
        private val tvContent = itemView.findViewById<TextView>(R.id.tvContent)
        private val tvPolicy = itemView.findViewById<TextView>(R.id.tvPolicy)
        private val tvSync = itemView.findViewById<TextView>(R.id.tvSyncBadge)
        private val tvCategory = itemView.findViewById<TextView>(R.id.tvCategory)
        private val tvMeta = itemView.findViewById<TextView>(R.id.tvMeta)
        private val tvTags = itemView.findViewById<TextView>(R.id.tvTags)

        fun bind(row: MemoryRow) {
            val context = itemView.context
            tvTitle.text = row.title
            tvContent.text = row.content

            // 权限 / 敏感级徽标
            val (policyLabel, policyColor, policyBg) = when (row.policyLevel) {
                2 -> Triple("敏感", R.color.semantic_sensitive, R.color.semantic_sensitive_bg)
                0 -> Triple("公开", R.color.semantic_public, R.color.semantic_public_bg)
                else -> Triple("普通", R.color.semantic_normal, R.color.semantic_normal_bg)
            }
            tvPolicy.text = policyLabel
            tvPolicy.setTextColor(ContextCompat.getColor(context, policyColor))
            tvPolicy.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, policyBg))

            // 同步状态徽标（本地树：仅本地/待同步/已同步/同步失败/敏感不迁移；云端树：云端）
            tvSync.text = row.syncLabel
            tvSync.setTextColor(ContextCompat.getColor(context, row.syncColorRes))
            tvSync.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, row.syncBgRes))

            // 分类徽标
            tvCategory.text = row.category
            tvCategory.setTextColor(ContextCompat.getColor(context, R.color.semantic_category))
            tvCategory.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.semantic_category_bg))

            tvMeta.text = "%s · %s · 置信 %d%%".format(
                Locale.CHINA,
                sourceLabel(row.source),
                TIME_FORMAT.format(Date(row.createdAt)),
                (row.confidence * 100).toInt().coerceIn(0, 100)
            )

            tvTags.text = if (row.tags.isBlank()) "暂无标签" else row.tags

            itemView.setOnClickListener { onClick(row) }
            itemView.setOnLongClickListener { onLongClick(row); true }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MemoryRow>() {
            override fun areItemsTheSame(oldItem: MemoryRow, newItem: MemoryRow) =
                oldItem.key == newItem.key && oldItem.tree == newItem.tree

            override fun areContentsTheSame(oldItem: MemoryRow, newItem: MemoryRow) =
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

/** 列表展示模型：本地树 / 云端树统一行 */
data class MemoryRow(
    val key: Long,
    val memoId: String,
    val title: String,
    val content: String,
    val category: String,
    val tags: String,
    val source: String,
    val policyLevel: Int,
    val confidence: Float,
    val createdAt: Long,
    val syncLabel: String,
    val syncColorRes: Int,
    val syncBgRes: Int,
    val tree: String
) {
    val isCloud: Boolean get() = tree == "CLOUD"

    companion object {
        fun fromLocal(item: MemoryItemEntity): MemoryRow {
            val (label, color, bg) = when {
                item.policyLevel >= 2 -> Triple(
                    "敏感不迁移", R.color.semantic_sensitive, R.color.semantic_sensitive_bg
                )
                item.syncState == 2 -> Triple(
                    "已同步", R.color.semantic_normal, R.color.semantic_normal_bg
                )
                item.syncState == 3 -> Triple(
                    "同步失败", R.color.semantic_sensitive, R.color.semantic_sensitive_bg
                )
                item.syncState == 1 -> Triple(
                    "待同步", R.color.semantic_degraded, R.color.log_infer_bg
                )
                else -> Triple(
                    "仅本地", R.color.semantic_public, R.color.semantic_public_bg
                )
            }
            return MemoryRow(
                key = item.id,
                memoId = item.memoId,
                title = item.title,
                content = item.content,
                category = item.category,
                tags = item.tags,
                source = item.source,
                policyLevel = item.policyLevel,
                confidence = item.confidence,
                createdAt = item.createdAt,
                syncLabel = label,
                syncColorRes = color,
                syncBgRes = bg,
                tree = "LOCAL"
            )
        }

        fun fromCloud(item: CloudMemoryItemEntity): MemoryRow {
            return MemoryRow(
                key = item.id,
                memoId = item.memoId,
                title = item.title,
                content = item.content,
                category = item.category,
                tags = item.tags,
                source = item.source,
                policyLevel = item.policyLevel,
                confidence = item.confidence,
                createdAt = item.createdAt,
                syncLabel = "云端",
                syncColorRes = R.color.log_collect,
                syncBgRes = R.color.log_collect_bg,
                tree = "CLOUD"
            )
        }
    }
}
