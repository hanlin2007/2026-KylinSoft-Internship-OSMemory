package com.example.osmemory.data

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.data.cloud.CloudMemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 审计导出（阶段 2 + 阶段 2 修复）
 *
 * - [build]：序列化为 JSON 审计快照（本地树 + 云端树 + 全部调用日志）。
 * - [buildHtml]：生成**自包含可视化 HTML**——手机上用浏览器直接打开即可查看
 *   （Pixel 等机型没有 JSON 查看器，纯 JSON 无法可视化）。底部附原始 JSON（<details> 折叠），
 *   数据与可视化两者兼得。
 *
 * 结构：
 * {
 *   "exportedAt": …,
 *   "version": "2.0",
 *   "trees": { "local": …, "cloud": … },
 *   "logs": { "count": K, "items": […] }
 * }
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

    // ------------------------------------------------------------------
    // 可视化 HTML 导出（阶段 2 修复：解决 Pixel 无法打开 JSON 的问题）
    // ------------------------------------------------------------------

    /**
     * 生成自包含可视化审计快照。样式内联、无外部依赖，浏览器（Chrome/自带浏览器）直接打开。
     * 底部 <details> 折叠块保留完整原始 JSON，便于另行解析。
     */
    fun buildHtml(
        localItems: List<MemoryItemEntity>,
        cloudItems: List<CloudMemoryItemEntity>,
        logs: List<MemoryLogEntity>
    ): String {
        val now = System.currentTimeMillis()
        val localSensitive = localItems.count { it.policyLevel >= 2 }
        val cloudSensitive = cloudItems.count { it.policyLevel >= 2 }
        val securityLogs = logs.count { it.logType == MemoryLogTypeSecurity }
        val sb = StringBuilder()

        sb.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>OS Memory 审计快照</title><style>")
        sb.append(CSS)
        sb.append("</style></head><body>")

        // 头部 + 摘要
        sb.append("<header><h1>OS Memory 记忆审计快照</h1>")
        sb.append("<p class=\"sub\">导出时间：${TIME_FORMAT.format(Date(now))} · 版本 v2.0（阶段 2 修复 · 可视化导出）</p>")
        sb.append("<div class=\"stats\">")
        sb.append(statCard("本地树记忆", localItems.size, "local"))
        sb.append(statCard("云端树记忆", cloudItems.size, "cloud"))
        sb.append(statCard("调用日志", logs.size, "log"))
        sb.append(statCard("敏感记忆", localSensitive + cloudSensitive, "sensitive"))
        sb.append(statCard("安全敏感日志", securityLogs, "security"))
        sb.append("</div></header>")

        // 本地树
        sb.append("<section><h2>本地树（Local Tree · source of truth）</h2>")
        if (localItems.isEmpty()) sb.append(emptyTip("本地树暂无记忆"))
        else localItems.forEach { sb.append(memoryCard(it)) }
        sb.append("</section>")

        // 云端树（内网云端库：来源两分，敏感判断与本地一致）
        sb.append("<section><h2>云端树（Cloud Tree · 内网云端库）</h2>")
        if (cloudItems.isEmpty()) sb.append(emptyTip("云端树为空（联网后自动拉取本地待同步记忆）"))
        else cloudItems.forEach { sb.append(memoryCard(it)) }
        sb.append("</section>")

        // 日志
        sb.append("<section><h2>调用日志（全链路可审计）</h2>")
        if (logs.isEmpty()) sb.append(emptyTip("暂无日志"))
        else {
            sb.append("<table><thead><tr>")
            sb.append("<th>类型</th><th>动作</th><th>时间</th><th>来源</th><th>摘要</th><th>标签</th>")
            sb.append("</tr></thead><tbody>")
            logs.forEach { l ->
                sb.append("<tr>")
                sb.append("<td>${logBadge(l.logType)}</td>")
                sb.append("<td>").append(esc(l.action)).append("</td>")
                sb.append("<td class=\"nowrap\">").append(TIME_FORMAT.format(Date(l.timestamp))).append("</td>")
                sb.append("<td>").append(esc(l.source)).append("</td>")
                sb.append("<td>").append(esc(l.contentSummary)).append("</td>")
                sb.append("<td>").append(esc(l.tags.ifBlank { "—" })).append("</td>")
                sb.append("</tr>")
            }
            sb.append("</tbody></table>")
        }
        sb.append("</section>")

        // 原始 JSON（折叠保留，数据完整）
        sb.append("<details class=\"raw\"><summary>查看原始 JSON（供二次解析）</summary>")
        sb.append("<pre>").append(esc(build(localItems, cloudItems, logs))).append("</pre>")
        sb.append("</details>")

        sb.append("<footer>OS Memory · 本地优先 · 权限可控 · 可审计</footer>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun statCard(label: String, value: Int, kind: String): String {
        val cls = when (kind) {
            "sensitive", "security" -> "stat sensitive"
            "cloud" -> "stat cloud"
            else -> "stat"
        }
        return "<div class=\"$cls\"><div class=\"num\">$value</div><div class=\"lbl\">$label</div></div>"
    }

    private fun emptyTip(text: String): String = "<div class=\"empty\">$text</div>"

    private fun logBadge(type: String): String {
        val (label, cls) = when (type) {
            "COLLECT" -> "传入" to "badge collect"
            "RETRIEVE" -> "检索" to "badge retrieve"
            "INFER" -> "推理" to "badge infer"
            "SECURITY" -> "安全敏感" to "badge security"
            else -> type to "badge"
        }
        return "<span class=\"$cls\">$label</span>"
    }

    private fun memoryCard(item: MemoryItemEntity): String {
        val policy = policyBadge(item.policyLevel)
        val sync = syncBadge(item.syncState, item.policyLevel)
        return """
            <div class="card">
              <div class="card-head">
                <strong>${esc(item.title)}</strong>
                <span class="cat">${esc(item.category)}</span>
              </div>
              <div class="badges">$policy$sync</div>
              <p class="content">${esc(item.content)}</p>
              <div class="meta">来源：${esc(item.source)} · ${TIME_FORMAT.format(Date(item.createdAt))} · 置信 ${(item.confidence * 100).toInt().coerceIn(0, 100)}%</div>
              ${if (item.tags.isBlank()) "" else "<div class=\"tags\">${item.tags.split(",", "，").joinToString("") { "<span class=\"tag\">${esc(it.trim())}</span>" }}</div>"}
            </div>
        """.trimIndent()
    }

    private fun memoryCard(item: CloudMemoryItemEntity): String {
        val origin = if (item.origin == CloudMemoryItemEntity.ORIGIN_LOCAL_SYNC)
            "<span class=\"badge infer\">来自本地同步</span>"
        else
            "<span class=\"badge cloud\">云端创建</span>"
        val policy = policyBadge(item.policyLevel)
        return """
            <div class="card">
              <div class="card-head">
                <strong>${esc(item.title)}</strong>
                <span class="cat">${esc(item.category)}</span>
              </div>
              <div class="badges">$origin$policy</div>
              <p class="content">${esc(item.content)}</p>
              <div class="meta">来源：${esc(item.source)} · ${TIME_FORMAT.format(Date(item.createdAt))} · 上云：${TIME_FORMAT.format(Date(item.syncedAt))}</div>
              ${if (item.tags.isBlank()) "" else "<div class=\"tags\">${item.tags.split(",", "，").joinToString("") { "<span class=\"tag\">${esc(it.trim())}</span>" }}</div>"}
            </div>
        """.trimIndent()
    }

    private fun policyBadge(level: Int): String = when (level) {
        2 -> "<span class=\"badge security\">敏感</span>"
        0 -> "<span class=\"badge\">公开</span>"
        else -> "<span class=\"badge normal\">普通</span>"
    }

    private fun syncBadge(state: Int, policyLevel: Int): String = when {
        policyLevel >= 2 -> "<span class=\"badge security\">敏感不迁移</span>"
        state == 2 -> "<span class=\"badge normal\">已同步</span>"
        state == 3 -> "<span class=\"badge security\">同步失败</span>"
        state == 1 -> "<span class=\"badge infer\">待同步</span>"
        else -> "<span class=\"badge\">仅本地</span>"
    }

    /** HTML 转义（内容来自记忆/日志，必须转义避免破坏页面） */
    private fun esc(raw: String): String = buildString {
        raw.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON 构建（供 build() 与 HTML 内嵌原始 JSON 复用）
    // ------------------------------------------------------------------

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
        put("origin", origin)
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

    private const val MemoryLogTypeSecurity = "SECURITY"

    private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private val CSS = """
        * { box-sizing: border-box; }
        body { margin: 0; font-family: -apple-system, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif; background: #F6F7FB; color: #1B1C22; line-height: 1.5; padding: 16px; }
        header { padding: 8px 4px 12px; }
        h1 { font-size: 20px; margin: 0 0 4px; }
        .sub { color: #546E7A; font-size: 12px; margin: 0 0 12px; }
        .stats { display: flex; flex-wrap: wrap; gap: 8px; }
        .stat { background: #fff; border: 1px solid #E1E3EB; border-radius: 12px; padding: 10px 14px; min-width: 92px; }
        .stat .num { font-size: 22px; font-weight: 700; }
        .stat .lbl { font-size: 11px; color: #546E7A; }
        .stat.sensitive .num { color: #C62828; }
        .stat.cloud .num { color: #283593; }
        section { background: #fff; border: 1px solid #E1E3EB; border-radius: 14px; padding: 14px; margin-bottom: 14px; }
        h2 { font-size: 15px; margin: 0 0 10px; }
        .empty { color: #90A4AE; font-size: 13px; padding: 12px; text-align: center; }
        .card { border: 1px solid #E8EAF6; border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; }
        .card-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
        .card-head strong { font-size: 14px; }
        .cat { color: #4527A0; background: #EDE7F6; border-radius: 6px; padding: 1px 8px; font-size: 11px; }
        .badges { margin: 6px 0; display: flex; gap: 6px; }
        .badge { background: #ECEFF1; color: #546E7A; border-radius: 6px; padding: 1px 8px; font-size: 11px; }
        .badge.normal { background: #E8F5E9; color: #2E7D32; }
        .badge.security { background: #FDECEA; color: #C62828; }
        .badge.infer { background: #FFF3E0; color: #E65100; }
        .badge.collect { background: #E0F2F1; color: #00695C; }
        .badge.retrieve { background: #E8EAF6; color: #283593; }
        .badge.cloud { background: #E8EAF6; color: #283593; }
        .content { margin: 4px 0; font-size: 13px; color: #37474F; white-space: pre-wrap; word-break: break-word; }
        .meta { font-size: 11px; color: #78909C; }
        .tags { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 4px; }
        .tag { background: #EDE7F6; color: #4527A0; border-radius: 6px; padding: 1px 8px; font-size: 11px; }
        table { width: 100%; border-collapse: collapse; font-size: 12px; }
        th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #F1F3F9; vertical-align: top; }
        th { color: #78909C; font-weight: 600; }
        .nowrap { white-space: nowrap; }
        details.raw { margin-top: 6px; }
        details.raw summary { font-size: 13px; color: #283593; cursor: pointer; }
        pre { background: #14161F; color: #E4E6F0; border-radius: 10px; padding: 12px; font-size: 11px; overflow-x: auto; white-space: pre-wrap; word-break: break-word; }
        footer { text-align: center; color: #B0BEC5; font-size: 11px; padding: 8px 0 24px; }
    """.trimIndent()
}
