package com.example.osmemory.data

import com.example.osmemory.core.model.TextTools
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.dao.RegisteredAppDao
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import com.example.osmemory.data.db.entity.RegisteredAppEntity

/**
 * 示例数据装载（演示用：离线、确定性、不污染真实数据）
 *
 * 装载 10 条覆盖"用户画像/日程/项目/偏好/任务/关系"六类的原子记忆卡 +
 * 3 个示例应用登记（记事本只存 / 对话助手读写 / 文件分类器读写）。
 * 每条记忆带 COLLECT 日志；装载过程留 INFER 日志。
 */
object SampleDataProvider {

    private data class Sample(
        val content: String,
        val title: String,
        val category: String,
        val tags: List<String>,
        val source: String,
        val policyLevel: Int = 1,
        val confidence: Float = 0.9f
    )

    private val SAMPLES = listOf(
        Sample("我平时喜欢喝美式咖啡，不喜欢加糖", "咖啡偏好", "偏好风格", listOf("咖啡", "美式", "饮食偏好"), "demo"),
        Sample("我周末喜欢去西湖边跑步，一般跑 5 公里左右", "跑步习惯", "偏好风格", listOf("西湖", "跑步", "运动"), "demo"),
        Sample("我习惯晚上 11 点前睡觉，早上 7 点起床", "作息习惯", "偏好风格", listOf("作息", "睡眠", "生活习惯"), "demo"),
        Sample("我正在做 OS Memory 项目，负责记忆子系统的设计开发，技术栈是 Kotlin", "OS Memory 项目", "项目上下文", listOf("OSMemory", "Kotlin", "实习项目"), "demo"),
        Sample("我参加的语音竞赛项目，P0 与审计整改轮已经交付，测试全部通过", "语音竞赛进展", "项目上下文", listOf("竞赛", "语音", "项目进展"), "demo"),
        Sample("明天下午 3 点有一个和 mentor 的周会", "mentor 周会", "日程事件", listOf("会议", "周会", "明天"), "demo"),
        Sample("下周三要提交实习中期报告", "实习中期报告", "日程事件", listOf("实习", "报告", "截止日期"), "demo"),
        Sample("我是一名安卓开发实习生，正在研究 AI 操作系统中的记忆子系统", "职业背景", "用户画像", listOf("安卓", "实习生", "AI OS"), "demo"),
        Sample("我需要学习 Room 数据库和 KSP 注解处理的使用", "学习任务", "任务轨迹", listOf("Room", "KSP", "学习"), "demo"),
        Sample("我的 mentor 姓王，每周五下午会和我们一对一沟通", "mentor 关系", "联系人关系", listOf("mentor", "一对一", "周五"), "demo")
    )

    private val APPS = listOf(
        Triple("app_notes", "记事本", "WRITE"),
        Triple("app_chat", "对话助手", "READ_WRITE"),
        Triple("app_files", "文件分类器", "READ_WRITE")
    )

    suspend fun load(
        itemDao: MemoryItemDao,
        logDao: MemoryLogDao,
        appDao: RegisteredAppDao
    ): Int {
        val now = System.currentTimeMillis()

        // 示例应用登记
        APPS.forEachIndexed { i, (appId, name, scope) ->
            appDao.upsert(
                RegisteredAppEntity(appId = appId, appName = name, scope = scope, createdAt = now + i)
            )
        }

        // 示例记忆（确定性 memoId 便于讲解与检索）
        var loaded = 0
        SAMPLES.forEachIndexed { i, s ->
            val memoId = "MEMO-SAMPLE-${i + 1}"
            if (itemDao.byMemoId(memoId) != null) return@forEachIndexed // 已存在跳过
            val created = now - (SAMPLES.size - i) * 60_000L
            val item = MemoryItemEntity(
                memoId = memoId,
                contentHash = TextTools.normalizeHash(s.content),
                content = s.content,
                title = s.title,
                category = s.category,
                tags = s.tags.joinToString(","),
                source = s.source,
                appId = "demo",
                policyLevel = s.policyLevel,
                createdAt = created,
                updatedAt = created,
                confidence = s.confidence,
                evidenceRaw = s.content
            )
            itemDao.insert(item)
            logDao.insert(
                MemoryLogEntity(
                    logType = "COLLECT",
                    action = "seed",
                    appId = "demo",
                    memoIds = memoId,
                    timestamp = created,
                    source = s.source,
                    contentSummary = "示例记忆：${s.title}（${s.category}）",
                    tags = s.tags.joinToString(","),
                    extra = "{\"seed\": true}"
                )
            )
            loaded++
        }
        return loaded
    }
}
