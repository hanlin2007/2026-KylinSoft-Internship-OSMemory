package com.example.osmemory.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.osmemory.R
import com.example.osmemory.core.profile.ProfileBuilder
import com.example.osmemory.data.MemoryRepository
import com.example.osmemory.data.MemoryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆画像页（阶段 2）。
 *
 * 三板块（用户画像/风格偏好/工作项目）+ 遴选标签。
 * LLM 从本地树聚合生成；离线/失败统计降级（原因显示在状态行，可审计）。
 * Dream 整合后的扫描特效已移至记忆主页（MemoryListFragment）。
 */
class ProfileFragment : Fragment() {

    private lateinit var repo: MemoryRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = MemoryService.repo(requireContext())

        view.findViewById<TextView>(R.id.btnRegenerate).setOnClickListener { generate() }

        // 观察已生成的画像（跨页保持）；首次进入自动生成
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeLastProfile().collect { result ->
                if (result != null) render(view, result)
            }
        }
        if (repo.observeLastProfile().value == null) generate()
    }

    private fun generate() {
        val root = view ?: return
        val pb = root.findViewById<ProgressBar>(R.id.pbProfile)
        pb?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repo.buildProfile() }
                pb?.isVisible = false
                render(root, result)
                if (!isAdded) return@launch
                Toast.makeText(
                    requireContext(),
                    if (result.degraded) "画像已刷新（统计降级：${result.reason.take(40)}）"
                    else "画像已刷新（LLM 三板块）",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (error: Throwable) {
                pb?.isVisible = false
                if (!isAdded) return@launch
                val message = error.message ?: error.javaClass.simpleName
                renderPlaceholder(root, "画像生成失败（$message）")
            }
        }
    }

    /** 画像失败占位：三板块保留上次内容，状态行显示失败原因（可审计） */
    private fun renderPlaceholder(root: View, message: String) {
        val tvStatus = root.findViewById<TextView>(R.id.tvProfileStatus)
        tvStatus.text = "$message · 可稍后点「重新生成」重试"
        tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.semantic_sensitive))
    }

    private fun render(root: View, result: ProfileBuilder.ProfileResult) {
        bindSection(root, R.id.sectionProfile, "用户画像", result.userProfile)
        bindSection(root, R.id.sectionStyle, "风格偏好", result.stylePreference)
        bindSection(root, R.id.sectionWork, "工作项目", result.workProject)

        val tvTags = root.findViewById<TextView>(R.id.tvTags)
        tvTags.text = if (result.tags.isEmpty()) "暂无标签" else result.tags.joinToString("  ") { "#$it" }

        val tvStatus = root.findViewById<TextView>(R.id.tvProfileStatus)
        val base = "已聚合 ${result.usedCount} 条记忆 · ${TIME_FORMAT.format(Date(result.at))}"
        tvStatus.text = when {
            result.degraded -> "$base · 统计降级（LLM 不可用）：${result.reason.take(50)}"
            else -> "$base · LLM 生成（${repo.providerName}）"
        }
        tvStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (result.degraded) R.color.semantic_degraded else R.color.semantic_normal
            )
        )
    }

    private fun bindSection(root: View, includeId: Int, title: String, body: String) {
        val include = root.findViewById<View>(includeId)
        include.findViewById<TextView>(R.id.sectionTitle).text = title
        include.findViewById<TextView>(R.id.sectionBody).text = body
    }

    companion object {
        fun newInstance() = ProfileFragment()
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }
}
