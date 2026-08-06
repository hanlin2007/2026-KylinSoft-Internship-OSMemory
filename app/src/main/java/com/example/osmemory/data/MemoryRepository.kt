package com.example.osmemory.data

import android.content.Context
import com.example.osmemory.core.pipeline.MemoryPipeline
import com.example.osmemory.core.model.ModelManager
import com.example.osmemory.core.retrieval.MemoryRetriever
import com.example.osmemory.data.db.OSMemoryDatabase
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import com.example.osmemory.data.db.entity.RegisteredAppEntity
import kotlinx.coroutines.flow.Flow

/**
 * 数据门面（进程内"系统 API"形态，对应 PPT memo_collect / get_memo）
 *
 * 阶段 3：本类方法被 vibe 三应用（记事本/对话问答/文件分类器）直接调用；
 * 阶段 4：包装为 Binder/AIDL 跨进程服务，接口不变。
 */
class MemoryRepository(private val context: Context) {

    private val db = OSMemoryDatabase.get(context)
    private val itemDao = db.memoryItemDao()
    private val logDao = db.memoryLogDao()
    private val appDao = db.registeredAppDao()

    private val pipeline = MemoryPipeline(itemDao, logDao, ModelManager.provider(context))
    private val retriever = MemoryRetriever(itemDao, logDao)

    /** 当前模型通道名（控制台展示） */
    val providerName: String
        get() = ModelManager.provider(context).name

    // ---------- 观察流（控制台 UI） ----------

    fun observeItems(): Flow<List<MemoryItemEntity>> = itemDao.observeAll()

    fun observeLogs(type: String): Flow<List<MemoryLogEntity>> = logDao.observeByType(type)

    fun observeApps(): Flow<List<RegisteredAppEntity>> = appDao.observeAll()

    // ---------- 记忆写入（memo_collect） ----------

    suspend fun collect(
        memoryText: String,
        source: String,
        appId: String = MemoryPipeline.CONSOLE_APP_ID
    ): MemoryPipeline.CollectResult = pipeline.collect(memoryText, source, appId)

    suspend fun deleteItem(item: MemoryItemEntity) {
        itemDao.delete(item)
        logDao.insert(
            MemoryLogEntity(
                logType = MemoryPipeline.LOG_COLLECT,
                action = "delete",
                appId = MemoryPipeline.CONSOLE_APP_ID,
                memoIds = item.memoId,
                timestamp = System.currentTimeMillis(),
                source = item.source,
                contentSummary = "删除记忆：${item.title}（${item.memoId}）",
                tags = item.tags
            )
        )
    }

    // ---------- 记忆读取（get_memo） ----------

    suspend fun getMemo(
        appId: String,
        query: String,
        limit: Int = 10,
        policyMax: Int = 1
    ): List<MemoryItemEntity> = retriever.retrieve(appId, query, limit, policyMax)

    // ---------- 应用登记 ----------

    suspend fun registerApp(appId: String, appName: String, scope: String) {
        appDao.upsert(RegisteredAppEntity(appId = appId, appName = appName, scope = scope))
    }

    // ---------- 演示工具 ----------

    /** 一键装载示例数据（10 条记忆 + 3 个示例应用），返回装载的记忆条数 */
    suspend fun loadSampleData(): Int {
        val count = SampleDataProvider.load(itemDao, logDao, appDao)
        logDao.insert(
            MemoryLogEntity(
                logType = MemoryPipeline.LOG_INFER,
                action = "seed",
                appId = MemoryPipeline.CONSOLE_APP_ID,
                timestamp = System.currentTimeMillis(),
                source = "demo",
                contentSummary = "示例数据装载完成：$count 条记忆直接结构化入库（跳过模型抽取，离线可用）"
            )
        )
        return count
    }

    /** 清空记忆库与日志 */
    suspend fun clearAll() {
        itemDao.deleteAll()
        logDao.deleteAll()
    }
}

/** 进程内单例（模拟系统服务定位器；阶段 4 可替换为 Binder 绑定） */
object MemoryService {
    @Volatile
    private var repository: MemoryRepository? = null

    fun repo(context: Context): MemoryRepository =
        repository ?: synchronized(this) {
            MemoryRepository(context).also { repository = it }
        }
}
