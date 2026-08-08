package com.example.osmemory.core.dream

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AutoDream 调度器（阶段 4：网关联动 + 记忆自进化）
 *
 * 双策略（对齐需求）：
 *  - 在线（内网/联网）：每 [DreamPreferences.intervalMinutes] 分钟触发一次
 *    ① 端侧算力整合本地树；② 同步整合结果；③ 云端树 Dream（结果不脱离云端树）。
 *    且当记忆库存在新记忆时（collect 成功），后台去抖后执行同一顺序。
 *  - 离线：每 [DreamPreferences.intervalMinutes] 分钟，用端侧算力整合本地树（算力有限，不触发云端）。
 *
 * 工程机制（提炼自 Claude Code autoDream 与 Hermes curator）：
 *  - 整合互斥锁：并发重入直接跳过（对应 Claude Code 的跨进程 consolidate lock）；
 *  - 失败退避：连续失败后间隔翻倍，上限 [MAX_BACKOFF_MINUTES]（对应锁时间戳回滚的天然退避）；
 *  - 来源标记：所有 Dream 产物 source=dream_split/dream_distill，日志全程留痕。
 */
class DreamScheduler(
    private val context: Context,
    private val dreamCycle: suspend (
        includeCloud: Boolean,
        shouldRun: () -> Boolean
    ) -> List<DreamReport>,
    private val isOnline: () -> Boolean
) {
    /**
     * 后台整合永不崩进程：任何未捕获异常（含 OOM 等 Error）只被吞掉记录，
     * 绝不走默认 handler 杀进程（本地模型加载失败/内存压力时兜底关键）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
    )
    private val lock = AtomicBoolean(false)
    private val newMemoryGeneration = AtomicLong(0)

    /** 连续失败次数（退避用）；成功后清零 */
    private var failStreak = 0

    /** 退避上限：30 分钟（不把演示周期拖太长） */
    private val maxBackoffMinutes = 30L

    fun start() {
        scope.launch {
            // 启动后先等待一个配置周期；新记忆由 notifyNewMemory 单独去抖触发。
            // 避免应用刚打开就抢先整合演示数据，导致随后手动 Dream 只能显示“保持稳定”。
            var nextDelay = DreamPreferences.intervalMinutes(context) * 60_000L
            while (isActive) {
                if (!DreamPreferences.isEnabled(context)) {
                    delay(DISABLED_POLL_MS)
                    nextDelay = DreamPreferences.intervalMinutes(context) * 60_000L
                    continue
                }
                delay(nextDelay)
                if (!DreamPreferences.isEnabled(context)) continue
                nextDelay = runDreamCycle()
            }
        }
    }

    /** 停止旧调度器；模型配置刷新时防止多个周期任务并存。 */
    fun stop() = scope.cancel()

    /**
     * 新记忆到达：等待短暂静默窗口后先 Dream 本地树；在线且云端开关开启时再 Dream 云端树。
     * generation 去抖保证连续添加多条记忆时只在最后一条之后执行一次，恰好能看到完整冲突/重复集合。
     */
    fun notifyNewMemory() {
        val generation = newMemoryGeneration.incrementAndGet()
        scope.launch {
            delay(NEW_MEMORY_DEBOUNCE_MS)
            if (generation != newMemoryGeneration.get()) return@launch
            if (!DreamPreferences.isEnabled(context)) return@launch
            executeCycleSafe(
                includeCloud = isOnline() && DreamPreferences.isCloudDreamEnabled(context),
                shouldRun = { generation == newMemoryGeneration.get() }
            )
        }
    }

    /** 手动 Dream 开始前取消尚未执行的新记忆任务，避免后台任务抢先消费演示数据。 */
    fun cancelPendingNewMemory() {
        newMemoryGeneration.incrementAndGet()
    }

    /** 执行一次完整 Dream 周期；返回下一次等待间隔（毫秒，含失败退避） */
    private suspend fun runDreamCycle(): Long {
        val online = isOnline()
        val cloudEnabled = DreamPreferences.isCloudDreamEnabled(context)
        val success = executeCycleSafe(includeCloud = online && cloudEnabled)
        if (!success) {
            failStreak++
            return minOf(maxBackoffMinutes, DREAM_MINUTES_BASE * (1L shl minOf(failStreak, 6))) * 60_000L
        }
        failStreak = 0
        return DreamPreferences.intervalMinutes(context) * 60_000L
    }

    /** 一次本地→同步→云端周期作为整体互斥，最终只发布一份组合报告。 */
    private suspend fun executeCycleSafe(
        includeCloud: Boolean,
        shouldRun: () -> Boolean = { true }
    ): Boolean {
        return try {
            if (!lock.compareAndSet(false, true)) return true
            try {
                dreamCycle(includeCloud, shouldRun)
                true
            } finally {
                lock.set(false)
            }
        } catch (e: Throwable) { // 含 OOM/StackOverflow 等 Error：降级为失败并退避，不崩进程
            false
        }
    }

    private companion object {
        /** 基础间隔（分钟）：用于退避计算与默认周期 */
        const val DREAM_MINUTES_BASE = 5L
        const val NEW_MEMORY_DEBOUNCE_MS = 60_000L
        const val DISABLED_POLL_MS = 30_000L
    }
}
