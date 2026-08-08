package com.example.osmemory.ui.profile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
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
 * 记忆画像页（阶段 2 + 阶段 4 AutoDream 联动画像）。
 *
 * 三板块（用户画像/风格偏好/工作项目）+ 遴选标签。
 * LLM 从本地树聚合生成；离线/失败统计降级（原因显示在状态行，可审计）。
 *
 * 阶段 4：每次本地树 Dream 完成并产生变化时，自动重生成画像，
 * 并播放「全屏虚化覆盖 → 亮条刷过 → 新结果呈现」的更新动画
 * （API 31+ 用 RenderEffect 真实虚化；低版本用半透明白遮罩兜底）。
 */
class ProfileFragment : Fragment() {

    private lateinit var repo: MemoryRepository

    /** 刷过动画进行中标记（防重复播放/重入） */
    private var sweepPlaying = false

    /** 上一次已消费的本地树 Dream 时间（避免重复动画） */
    private var lastConsumedDreamAt = 0L

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
        // 阶段 4：本地树 Dream 完成后（有整合变化）→ 虚化刷过 + 重生成画像
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeLastDream().collect { report ->
                if (report == null) return@collect
                if (!report.tree.split(" + ").contains("LOCAL")) return@collect
                if (!report.changed) return@collect
                if (report.at <= lastConsumedDreamAt) return@collect
                lastConsumedDreamAt = report.at
                if (sweepPlaying) return@collect
                generate(sweep = true)
            }
        }
        if (repo.observeLastProfile().value == null) generate()
    }

    private fun generate(sweep: Boolean = false) {
        val root = view ?: return
        val pb = root.findViewById<ProgressBar>(R.id.pbProfile)
        pb?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            if (sweep) showSweepOverlay(root)
            try {
                val result = withContext(Dispatchers.IO) { repo.buildProfile() }
                pb?.isVisible = false
                render(root, result)
                if (sweep) playSweepAnimation(root)
                if (!isAdded) return@launch
                Toast.makeText(
                    requireContext(),
                    if (result.degraded) "画像已刷新（统计降级：${result.reason.take(40)}）"
                    else "画像已刷新（LLM 三板块）",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (error: Throwable) {
                // 画像聚合失败（含模型异常/OOM）：降级显示原因，绝不闪退
                pb?.isVisible = false
                if (sweep) playSweepAnimation(root)
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

    // ---------- AutoDream 虚化刷过动画 ----------

    /** 全屏虚化覆盖：API 31+ 真实模糊内容，低版本用半透明白遮罩 */
    private fun showSweepOverlay(root: View) {
        val overlay = root.findViewById<View>(R.id.dreamOverlay) ?: return
        val content = root.findViewById<View>(R.id.profileContent)
        overlay.alpha = 1f
        overlay.isVisible = true
        if (content != null && Build.VERSION.SDK_INT >= 31) {
            content.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    16f, 16f, android.graphics.Shader.TileMode.CLAMP
                )
            )
        }
    }

    /** 亮条从左侧外滑到右侧外（刷过），随后遮罩渐隐露出新画像 */
    private fun playSweepAnimation(root: View) {
        if (sweepPlaying) return
        sweepPlaying = true
        val overlay = root.findViewById<View>(R.id.dreamOverlay) ?: return
        val bar = root.findViewById<View>(R.id.dreamSweepBar) ?: return
        val content = root.findViewById<View>(R.id.profileContent)

        val barWidth = if (bar.width > 0) bar.width.toFloat() else dp(140).toFloat()
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        bar.translationX = -barWidth

        ValueAnimator.ofFloat(-barWidth, screenWidth).apply {
            duration = 750L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                bar.translationX = anim.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 亮条停留片刻后遮罩渐隐，露出新画像
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(380L)
                        .setStartDelay(120L)
                        .withEndAction {
                            overlay.isVisible = false
                            if (content != null && Build.VERSION.SDK_INT >= 31) {
                                content.setRenderEffect(null)
                            }
                            sweepPlaying = false
                        }
                        .start()
                }
            })
            start()
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance() = ProfileFragment()
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }
}
