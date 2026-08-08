package com.example.osmemory.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.osmemory.R
import com.example.osmemory.core.dream.DreamPreferences
import com.example.osmemory.core.dream.DreamReport
import com.example.osmemory.data.MemoryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AutoDream 设置（阶段 4）：调度参数 + 手动触发 + 最近结果。
 *
 * 时间参数（间隔分钟）按需求放入侧边栏工具栏（抽屉），可随时调整。
 */
class DreamSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dream_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()

        val swEnabled = view.findViewById<SwitchCompat>(R.id.swDreamEnabled)
        val swCloud = view.findViewById<SwitchCompat>(R.id.swCloudDream)
        val etInterval = view.findViewById<EditText>(R.id.etDreamInterval)

        swEnabled.isChecked = DreamPreferences.isEnabled(context)
        swCloud.isChecked = DreamPreferences.isCloudDreamEnabled(context)
        etInterval.setText(DreamPreferences.intervalMinutes(context).toString())

        view.findViewById<View>(R.id.btnSaveDream).setOnClickListener {
            DreamPreferences.setEnabled(context, swEnabled.isChecked)
            DreamPreferences.setCloudDreamEnabled(context, swCloud.isChecked)
            val minutes = etInterval.text?.toString()?.toIntOrNull()
                ?: DreamPreferences.DEFAULT_INTERVAL_MINUTES
            DreamPreferences.setIntervalMinutes(
                context,
                minutes.coerceIn(DreamPreferences.MIN_INTERVAL_MINUTES, DreamPreferences.MAX_INTERVAL_MINUTES)
            )
            Toast.makeText(
                context,
                "AutoDream 设置已保存（间隔 ${DreamPreferences.intervalMinutes(context)} 分钟）",
                Toast.LENGTH_SHORT
            ).show()
        }

        view.findViewById<View>(R.id.btnDreamNow).setOnClickListener {
            triggerDreamNow(view)
        }

        // 观察最近 Dream 结果（跨页保持）
        viewLifecycleOwner.lifecycleScope.launch {
            MemoryService.repo(requireContext()).observeLastDream().collect { report ->
                if (report != null) renderResult(view, report)
            }
        }
    }

    private fun triggerDreamNow(root: View) {
        val ctx = requireContext()
        val online = MemoryService.repo(ctx).observeNetwork().value
        val tv = root.findViewById<TextView>(R.id.tvDreamResult)
        tv.isVisible = true
        tv.text = if (online) {
            "正在整合本地树，并同步检查云端树…"
        } else {
            "正在执行本地树 Dream（端侧算力/规则兜底）…"
        }
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.semantic_public))

        viewLifecycleOwner.lifecycleScope.launch {
            val report = try {
                withContext(Dispatchers.IO) {
                    MemoryService.repo(requireContext()).dreamNow()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAdded || view !== root) return@launch
                tv.text = "Dream 执行失败：${e.message ?: "未知错误"}"
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.semantic_sensitive))
                Toast.makeText(requireContext(), "Dream 执行失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!isAdded || view !== root) return@launch
            if (report == null) {
                tv.text = "云端树不可达（离线）：本地树 Dream 已跳过"
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.semantic_sensitive))
                return@launch
            }
            renderResult(root, report)
            Toast.makeText(
                requireContext(),
                if (report.changed) "Dream 完成：${report.message}" else "Dream 完成：记忆保持稳定",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderResult(root: View, report: DreamReport) {
        val tv = root.findViewById<TextView>(R.id.tvDreamResult)
        tv.isVisible = true
        val summary = buildString {
            append("${report.tree} 树 · ${TIME_FORMAT.format(Date(report.at))}\n")
            append("冲突消解 ${report.conflictsResolved} · 拆分 ${report.splitCount} · 合并 ${report.mergedCount} · 高维提炼 ${report.distilledCount} · 归档 ${report.archivedCount}\n")
            if (report.details.isNotEmpty()) {
                append(report.details.take(8).joinToString("\n") { "• $it" })
                append('\n')
            } else {
                append("• 本轮没有需要修改的记忆\n")
            }
            if (report.degraded) append("（部分步骤降级：${report.reason.take(80)}）")
        }
        tv.text = summary
        tv.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (report.degraded) R.color.semantic_degraded else R.color.semantic_normal
            )
        )
    }

    companion object {
        fun newInstance() = DreamSettingsFragment()
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    }
}
