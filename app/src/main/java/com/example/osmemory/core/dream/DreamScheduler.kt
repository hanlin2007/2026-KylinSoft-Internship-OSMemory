package com.example.osmemory.core.dream

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 *    ① 云端树 Dream（云端算力，整合结果不脱离云端树）；
 *    ② 同步用端侧算力整合本地树。
 *    且当记忆库存在新记忆时（collect 成功），后台异步快速触发一次云端树 Dream（去抖）。
 *  - 离线：每 [DreamPreferences.intervalMinutes] 分钟，用端侧算力整合本地树（算力有限，不触发云端）。
 *
 * 工程机制（提炼自 Claude Code autoDream 与 Hermes curator）：
 *  - 整合互斥锁：并发重入直接跳过（对应 Claude Code 的跨进程 consolidate lock）；
 *  - 失败退避：连续失败后间隔翻倍，上限 [MAX_BACKOFF_MINUTES]（对应锁时间戳回滚的天然退避）；
 *  - 来源标记：所有 Dream 产物 source=dream_split/dream_distill，日志全程留痕。
 */
class DreamScheduler(
    private val context: Context,
    private val dreamLocal: suspend () -> DreamReport,
    private val dreamCloud: suspend () -> DreamReport?,
    private val isOnline: () -> Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = AtomicBoolean(false)
    private val lastCloudDreamAt = AtomicLong(0)

    /** 连续失败次数（退避用）；成功后清零 */
    private var failStreak = 0

    /** 新记忆触发云端 Dream 的去抖窗口（合并短时间内的多次触发） */
    private val newMemoryDedupMs = 20_000L

    /** 退避上限：30 分钟（不把演示周期拖太长） */
    private val maxBackoffMinutes = 30L

    fun start() {
        scope.launch {
            while (isActive) {
                val interval = runDreamCycle()
                delay(interval)
            }
        }
    }

    /** 新记忆到达：在线时异步快速触发一次云端树 Dream（离线不触发——算力有限，等周期） */
    fun notifyNewMemory() {
        scope.launch {
            if (!isOnline()) return@launch
            val now = System.currentTimeMillis()
            if (now - lastCloudDreamAt.get() < newMemoryDedupMs) return@launch
            dreamCloudSafe()
        }
    }

    /** 执行一次完整 Dream 周期；返回下一次等待间隔（毫秒，含失败退避） */
    private suspend fun runDreamCycle(): Long {
        val online = isOnline()
        val cloudEnabled = DreamPreferences.isCloudDreamEnabled(context)
        val success = if (online && cloudEnabled) {
            dreamCloudSafe() && dreamLocalSafe()
        } else {
            dreamLocalSafe()
        }
        if (!success) {
            failStreak++
            return minOf(maxBackoffMinutes, DREAM_MINUTES_BASE * (1L shl minOf(failStreak, 6))) * 60_000L
        }
        failStreak = 0
        return DreamPreferences.intervalMinutes(context) * 60_000L
    }

    private suspend fun dreamCloudSafe(): Boolean {
        return try {
            if (!lock.compareAndSet(false, true)) return true // 并发整合：跳过本轮
            try {
                val report = dreamCloud()
                if (report != null) lastCloudDreamAt.set(System.currentTimeMillis())
                true
            } finally {
                lock.set(false)
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun dreamLocalSafe(): Boolean {
        return try {
            if (!lock.compareAndSet(false, true)) return true
            try {
                dreamLocal()
                true
            } finally {
                lock.set(false)
            }
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        /** 基础间隔（分钟）：用于退避计算与默认周期 */
        const val DREAM_MINUTES_BASE = 5L
    }
}
