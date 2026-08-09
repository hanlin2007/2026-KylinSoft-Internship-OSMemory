package com.example.osmemory.core.dream

import android.content.Context
import com.example.osmemory.data.cloud.CloudMemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryItemEntity

/**
 * AutoDream 通用类型（阶段 4：记忆自进化 / AutoDream 接入）
 *
 * 树无关的统一记忆视图：本地树（MemoryItemEntity）与云端树（CloudMemoryItemEntity）
 * 通过 [fromLocal] / [fromCloud] 归一化为 [DreamItem]，Dream 引擎只依赖这一模型，
 * 写入由 [TreeOps] 抽象回落到各自树（云端树 Dream 结果不脱离云端树）。
 */
data class DreamItem(
    val id: Long,
    val memoId: String,
    val content: String,
    val title: String,
    val category: String,
    val tags: String,
    val source: String,
    val policyLevel: Int,
    val confidence: Float,
    val createdAt: Long,
    val updatedAt: Long,
    val evidenceRaw: String,
    val dreamState: Int = 0,
    val mergedInto: String = ""
) {
    companion object {
        /** Dream 状态：0=活跃 1=陈旧 2=已归档（被整合吞并，可恢复） */
        const val STATE_ACTIVE = 0
        const val STATE_STALE = 1
        const val STATE_ARCHIVED = 2

        fun fromLocal(item: MemoryItemEntity) = DreamItem(
            id = item.id,
            memoId = item.memoId,
            content = item.content,
            title = item.title,
            category = item.category,
            tags = item.tags,
            source = item.source,
            policyLevel = item.policyLevel,
            confidence = item.confidence,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            evidenceRaw = item.evidenceRaw,
            dreamState = item.dreamState,
            mergedInto = item.mergedInto
        )

        fun fromCloud(item: CloudMemoryItemEntity) = DreamItem(
            id = item.id,
            memoId = item.memoId,
            content = item.content,
            title = item.title,
            category = item.category,
            tags = item.tags,
            source = item.source,
            policyLevel = item.policyLevel,
            confidence = item.confidence,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            evidenceRaw = item.evidenceRaw,
            dreamState = item.dreamState,
            mergedInto = item.mergedInto
        )
    }
}

/** 一次 Dream 的结果报告（供 UI / 日志 / 画像联动使用） */
data class DreamReport(
    /** 目标树：LOCAL（本地树·端侧算力） / CLOUD（云端树·云端算力） */
    val tree: String,
    val online: Boolean,
    /** 冲突消解条数（安全等级优先；同级后写入优先，输家归档） */
    val conflictsResolved: Int,
    /** 原子拆分条数（复合记忆拆成多条原子记忆） */
    val splitCount: Int,
    /** 去重合并条数（等价或经模型复核的语义重复合并，被并者归档） */
    val mergedCount: Int,
    /** 高维提炼条数（包含/推理整合后提炼的高维记忆） */
    val distilledCount: Int,
    /** 归档条数（被覆盖/被合并/被包含，可恢复） */
    val archivedCount: Int,
    /** 不活跃遗忘删除条数（≥3 个 Dream 周期无检索/推理 → 物理删除） */
    val deletedCount: Int,
    val message: String,
    val degraded: Boolean,
    val reason: String,
    val at: Long,
    /** 控制台直接展示的整合明细（如“旧记忆 → 保留记忆”）。 */
    val details: List<String> = emptyList(),
    /** 本轮实际涉及的 memoId，供 DREAM 审计日志追溯。 */
    val affectedMemoIds: List<String> = emptyList()
) {
    val succeeded: Boolean get() = !degraded || conflictsResolved > 0 || mergedCount > 0 || splitCount > 0

    val changed: Boolean
        get() = conflictsResolved > 0 || splitCount > 0 || mergedCount > 0 ||
            distilledCount > 0 || archivedCount > 0 || deletedCount > 0
}

/** 树写入抽象：Dream 引擎通过它读写任一树（云端 Dream 结果不脱离云端树） */
interface TreeOps {
    val tree: String
    suspend fun allActive(): List<DreamItem>
    suspend fun allArchived(): List<DreamItem>
    suspend fun update(item: DreamItem)
    suspend fun insert(item: DreamItem): String
    suspend fun archive(item: DreamItem, mergedInto: String)

    /** 上一轮 Dream 时间戳（毫秒，0=从未运行） */
    suspend fun lastDreamTimestamp(): Long

    /** 递增所有在 [lastDreamAt] 之后未被引用的记忆的不活跃周期计数 */
    suspend fun incrementInactiveCycles(lastDreamAt: Long)

    /** 返回不活跃周期 ≥ [minCycles] 的活跃记忆 memoId 列表 */
    suspend fun staleMemoIds(minCycles: Int): List<String>

    /** 物理删除不活跃周期 ≥3 的活跃记忆，返回删除条数 */
    suspend fun deleteStaleInactive(): Int
}

/**
 * AutoDream 参数（SharedPreferences 持久化）。
 * 默认：启用，间隔 5 分钟（演示可下调到 1 分钟）。
 */
object DreamPreferences {
    private const val PREFS = "osmemory_dream"
    private const val KEY_ENABLED = "dream_enabled"
    private const val KEY_INTERVAL_MIN = "dream_interval_min"
    private const val KEY_CLOUD_ENABLED = "dream_cloud_enabled"

    const val DEFAULT_INTERVAL_MINUTES = 5
    const val MIN_INTERVAL_MINUTES = 1
    const val MAX_INTERVAL_MINUTES = 60

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Dream 间隔（分钟）；非法值回落默认 */
    fun intervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MINUTES)
            .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_MIN, minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)).apply()
    }

    /** 云端树 Dream 开关（在线时基于云端树的整合；演示默认开） */
    fun isCloudDreamEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLOUD_ENABLED, true)

    fun setCloudDreamEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOUD_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
