package com.example.osmemory.data

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.data.cloud.CloudMemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 审计导出（阶段 2）：把 本地树 + 云端树 + 全部调用日志 序列化为一份 JSON 审计快照。
 *
 * 结构：
 * {
 *   "exportedAt": …,
 *   "version": "2.0",
 *   "trees": {
 *     "local": {"count": N, "items": […]},
 *     "cloud": {"count": M, "items": […]}
 *   },
 *   "logs": { "count": K, "items": […] }
 * }
 *
 * 每张记忆卡包含 来源/内容/权限/时间/证据/质量/云同步状态 等可审计字段（对应 PPT 安全审计）。
 */
object AuditExporter {

    fun build(
        localItems: List<MemoryItemEntity>,
        cloudItems: List<CloudMemoryItemEntity>,
        logs: List<MemoryLogEntity>
    ): String {
        val root = JSONObject()
        root.put("exportedAt", System.currentTimeMillis())
        root.put("app", "OS Memory")
        root.put("version", "2.0")

        val trees = JSONObject()
        trees.put("local", itemsTree(localItems.map { it.toAuditMap() }))
        trees.put("cloud", itemsTree(cloudItems.map { it.toAuditMap() }))
        root.put("trees", trees)

        val logArr = JSONArray()
        logs.forEach { l ->
            logArr.put(JSONObject().apply {
                put("logType", l.logType)
                put("action", l.action)
                put("appId", l.appId)
                put("memoIds", l.memoIds)
                put("timestamp", l.timestamp)
                put("source", l.source)
                put("contentSummary", l.contentSummary)
                put("tags", l.tags)
                put("extra", safeJson(l.extra))
            })
        }
        root.put("logs", JSONObject().apply {
            put("count", logs.size)
            put("items", logArr)
        })
        return root.toString(2)
    }

    private fun itemsTree(items: List<JSONObject>): JSONObject = JSONObject().apply {
        put("count", items.size)
        put("items", JSONArray(items))
    }

    private fun MemoryItemEntity.toAuditMap(): JSONObject = JSONObject().apply {
        put("memoId", memoId)
        put("title", title)
        put("category", category)
        put("content", content)
        put("tags", tags)
        put("source", source)
        put("appId", appId)
        put("policyLevel", policyLevel)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("confidence", confidence)
        put("reuseCount", reuseCount)
        put("cloudEligible", cloudEligible)
        put("syncState", syncState)
        put("syncedAt", syncedAt ?: JSONObject.NULL)
    }

    private fun CloudMemoryItemEntity.toAuditMap(): JSONObject = JSONObject().apply {
        put("memoId", memoId)
        put("title", title)
        put("category", category)
        put("content", content)
        put("tags", tags)
        put("source", source)
        put("appId", appId)
        put("policyLevel", policyLevel)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("syncedAt", syncedAt)
        put("tree", "cloud")
    }

    /** 日志 extra 可能来自模型或系统，兜底避免非法 JSON 污染导出文件 */
    private fun safeJson(raw: String): Any = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) {
        raw
    }
}
