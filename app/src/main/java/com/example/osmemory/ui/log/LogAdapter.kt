package com.example.osmemory.ui.log

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.osmemory.R
import com.example.osmemory.data.db.entity.MemoryLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆调用日志适配器（三板块共用）
 * 展示：类型徽标（传入/检索/推理）/ 动作 / 时间 / 内容摘要 / 来源 / 应用 / 标签
 */
class LogAdapter : ListAdapter<MemoryLogEntity, LogAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvTypeBadge = itemView.findViewById<TextView>(R.id.tvTypeBadge)
        private val tvAction = itemView.findViewById<TextView>(R.id.tvAction)
        private val tvTime = itemView.findViewById<TextView>(R.id.tvTime)
        private val tvSummary = itemView.findViewById<TextView>(R.id.tvSummary)
        private val tvLogMeta = itemView.findViewById<TextView>(R.id.tvLogMeta)
        private val tvLogExtra = itemView.findViewById<TextView>(R.id.tvLogExtra)

        private var expanded = false

        fun bind(log: MemoryLogEntity) {
            val context = itemView.context

            val (typeLabel, typeColor, typeBg) = when (log.logType) {
                "COLLECT" -> Triple("传入", R.color.log_collect, R.color.log_collect_bg)
                "RETRIEVE" -> Triple("检索", R.color.log_retrieve, R.color.log_retrieve_bg)
                "INFER" -> Triple("推理", R.color.log_infer, R.color.log_infer_bg)
                else -> Triple(log.logType, R.color.semantic_public, R.color.semantic_public_bg)
            }
            tvTypeBadge.text = typeLabel
            tvTypeBadge.setTextColor(ContextCompat.getColor(context, typeColor))
            tvTypeBadge.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, typeBg))

            tvAction.text = log.action
            tvTime.text = TIME_FORMAT.format(Date(log.timestamp))
            tvSummary.text = log.contentSummary
            tvLogMeta.text = "来源：${log.source} · 应用：${log.appId}" +
                (if (log.tags.isBlank()) "" else " · 标签：${log.tags}")

            // extra 字段可视化：降级原因 / 模型通道 / HTTP 状态 / 重排状态 等
            val hasExtra = log.extra.isNotBlank() && log.extra != "{}"
            tvLogExtra.text = if (hasExtra) prettyExtra(log.extra) else null
            tvLogExtra.isVisible = expanded && hasExtra
            itemView.setOnClickListener {
                expanded = !expanded
                tvLogExtra.isVisible = expanded && hasExtra
            }
        }

        /** 尽量美化 extra JSON；解析失败原样展示 */
        private fun prettyExtra(raw: String): String = try {
            org.json.JSONObject(raw).toString(2)
        } catch (_: Exception) {
            raw
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MemoryLogEntity>() {
            override fun areItemsTheSame(oldItem: MemoryLogEntity, newItem: MemoryLogEntity) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MemoryLogEntity, newItem: MemoryLogEntity) =
                oldItem == newItem
        }

        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    }
}
