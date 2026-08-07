package com.example.osmemory.data

import android.content.Context
import com.example.osmemory.core.model.ModelConfig
import com.example.osmemory.core.model.CloudModelProvider
import com.example.osmemory.core.model.ModelDiagnostics
import com.example.osmemory.core.model.ModelManager
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.core.net.NetworkMonitor
import com.example.osmemory.core.pipeline.MemoryPipeline
import com.example.osmemory.core.profile.ProfileBuilder
import com.example.osmemory.core.retrieval.MemoryRetriever
import com.example.osmemory.core.retrieval.SemanticReranker
import com.example.osmemory.data.cloud.CloudMemoryItemEntity
import com.example.osmemory.data.cloud.CloudTreeDatabase
import com.example.osmemory.data.cloud.TreeSyncManager
import com.example.osmemory.data.db.OSMemoryDatabase
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import com.example.osmemory.data.db.entity.RegisteredAppEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 数据门面（进程内"系统 API"形态，对应 PPT memo_collect / get_memo）
 *
 * 阶段 1 修复：接入 本地树/云端树 双库、Network Gateway 单向同步、网络路由；
 * 阶段 2：语义检索（LLM 重排）、记忆画像、记忆修改、模型设置、审计导出。
 * 阶段 3：本类方法被 vibe 三应用（记事本/对话问答/文件分类器）直接调用；
 * 阶段 4：包装为 Binder/AIDL 跨进程服务，接口不变。
 */
class MemoryRepository(context: Context) {
    private val context = context.applicationContext

    private val db = OSMemoryDatabase.get(context)
    private val cloudDb = CloudTreeDatabase.get(context)
    private val itemDao = db.memoryItemDao()
    private val logDao = db.memoryLogDao()
    private val appDao = db.registeredAppDao()
    private val cloudDao = cloudDb.cloudMemoryItemDao()

    // 模型通道（设置页变更后 refreshChannels() 重建）
    private var provider: ModelProvider = ModelManager.provider(context)
    private lateinit var pipeline: MemoryPipeline
    private lateinit var retriever: MemoryRetriever
    private lateinit var profileBuilder: ProfileBuilder
    private lateinit var syncManager: TreeSyncManager

    private val _lastProfile = kotlinx.coroutines.flow.MutableStateFlow<ProfileBuilder.ProfileResult?>(null)

    init {
        NetworkMonitor.init(context)
        refreshChannels()
    }

    /** 模型/网络配置变更后重建通道依赖（provider 热插拔） */
    fun refreshChannels() {
        provider = ModelManager.provider(context)
        pipeline = MemoryPipeline(itemDao, logDao, provider)
        val reranker = SemanticReranker(provider)
        retriever = MemoryRetriever(itemDao, logDao, reranker)
        profileBuilder = ProfileBuilder(provider, itemDao, logDao)
        syncManager = TreeSyncManager(context, itemDao, cloudDao, logDao, provider)
    }

    /** 当前模型通道名（控制台展示） */
    val providerName: String get() = provider.name

    // ---------- 观察流（控制台 UI） ----------

    fun observeItems(): Flow<List<MemoryItemEntity>> = itemDao.observeAll()

    /** 云端树观察流（仅展示，本地树永不从这里读回） */
    fun observeCloudItems(): Flow<List<CloudMemoryItemEntity>> = cloudDao.observeAll()

    fun observeLogs(type: String): Flow<List<MemoryLogEntity>> = logDao.observeByType(type)

    fun observeApps(): Flow<List<RegisteredAppEntity>> = appDao.observeAll()

    /** 联网状态路由（在线/离线） */
    fun observeNetwork(): StateFlow<Boolean> = NetworkMonitor.online

    /** 最近一次模型调用（成功/失败 + 原因） */
    fun observeModelCall(): StateFlow<ModelDiagnostics.CallRecord?> = ModelDiagnostics.lastCall

    /** 最近一次 本地→云端 同步结果 */
    fun observeLastSync(): StateFlow<TreeSyncManager.SyncReport?> = syncManager.lastSync

    /** 最近一次画像结果 */
    fun observeLastProfile(): StateFlow<ProfileBuilder.ProfileResult?> = _lastProfile

    // ---------- 记忆写入（memo_collect） ----------

    suspend fun collect(
        memoryText: String,
        source: String,
        appId: String = MemoryPipeline.CONSOLE_APP_ID
    ): MemoryPipeline.CollectResult = pipeline.collect(memoryText, source, appId)

    /** 记忆修改（先画像后改）：保留 memoId，重跑流水线，敏感项可强制保密隔离 */
    suspend fun updateItem(
        memoId: String,
        newContent: String,
        forceSecret: Boolean? = null,
        appId: String = MemoryPipeline.CONSOLE_APP_ID
    ): MemoryPipeline.UpdateResult = pipeline.update(memoId, newContent, appId = appId, forceSecret = forceSecret)

    suspend fun deleteItem(item: MemoryItemEntity) {
        itemDao.delete(item)
        // 云端树仅展示，不随本地删除（模拟云端已有副本）；如需强一致可在此调用云删除（阶段 3）
        logDao.insert(
            MemoryLogEntity(
                logType = MemoryPipeline.LOG_COLLECT,
                action = "delete",
                appId = MemoryPipeline.CONSOLE_APP_ID,
                memoIds = item.memoId,
                timestamp = System.currentTimeMillis(),
                source = item.source,
                contentSummary = "删除本地记忆：${item.title}（${item.memoId}）",
                tags = item.tags
            )
        )
    }

    // ---------- 记忆读取（get_memo，语义检索） ----------

    /** semantic=true 时：关键词召回 + LLM 语义重排（离线自动降级并留痕） */
    suspend fun getMemo(
        appId: String,
        query: String,
        limit: Int = 10,
        policyMax: Int = 1,
        semantic: Boolean = false
    ): List<MemoryItemEntity> = retriever.retrieve(appId, query, limit, policyMax, semantic)

    // ---------- 应用登记 ----------

    suspend fun registerApp(appId: String, appName: String, scope: String) {
        appDao.upsert(RegisteredAppEntity(appId = appId, appName = appName, scope = scope))
    }

    // ---------- 本地树 ↔ 云端树 单向同步（Network Gateway，阶段 2 修复：自动整合 + 云端 FAB） ----------

    /** 触发一次 本地→云端 单向同步（在线才真正推送，离线返回不可达报告） */
    suspend fun syncNow(): TreeSyncManager.SyncReport = syncManager.sync()

    /**
     * 联网自动拉取：每次 离线→在线 网络切换时把本地待同步记忆拉取到云端树
     * （由 MainActivity 监听网络状态触发；敏感/保密记忆由 cloudEligible 隔离）。离线返回 null。
     */
    suspend fun ensureCloudIntegrated(): TreeSyncManager.SyncReport? {
        if (!NetworkMonitor.isOnline(context)) return null
        return syncManager.autoIntegrateIfNeeded()
    }

    /** 云端树 FAB 添加入口：云端创建记忆，敏感判断与本地一致（断网返回 Unreachable） */
    suspend fun addToCloud(
        content: String,
        source: String,
        appId: String = MemoryPipeline.CONSOLE_APP_ID
    ): TreeSyncManager.CloudAddResult = syncManager.addToCloud(content, source, appId)

    // ---------- 记忆画像（阶段 2） ----------

    suspend fun buildProfile(): ProfileBuilder.ProfileResult {
        val result = profileBuilder.build()
        _lastProfile.value = result
        return result
    }

    // ---------- 模型设置（阶段 2 + 阶段 2 修复：云端/本地双插拔） ----------

    /** 保存模型配置并重建通道；测试连接由调用方执行 complete */
    fun saveModelConfig(baseUrl: String, model: String, apiKey: String, localModel: String? = null) {
        ModelConfig.save(context, baseUrl, model, apiKey, localModel)
        ModelManager.reset()
        ModelDiagnostics.reset()
        refreshChannels()
    }

    /** 云端按钮：直接测试输入框中的配置，不改变当前已保存设置。 */
    suspend fun testCloudConnection(
        baseUrl: String,
        model: String,
        apiKey: String
    ): ModelDiagnostics.CallRecord = testProvider(
        CloudModelProvider(baseUrl, model, apiKey),
        "你是云端模型连通性测试。",
        "请只回复 OK。"
    )

    /** 端侧按钮：绕过网络路由，直接完成一次本机 llama.cpp 推理。 */
    suspend fun testLocalConnection(): ModelDiagnostics.CallRecord = testProvider(
        ModelManager.localProvider(context),
        "你是端侧模型连通性测试。请简短回答。",
        "请只回复：端侧模型正常。"
    )

    private suspend fun testProvider(
        current: ModelProvider,
        system: String,
        user: String
    ): ModelDiagnostics.CallRecord {
        val startedAt = System.currentTimeMillis()
        return try {
            current.complete(system, user, 0.0)
            ModelDiagnostics.lastCall.value ?: ModelDiagnostics.CallRecord(
                current.name,
                ok = true,
                "调用成功",
                System.currentTimeMillis(),
                System.currentTimeMillis() - startedAt
            )
        } catch (e: Exception) {
            val rec = ModelDiagnostics.lastCall.value?.takeIf { it.channel == current.name }
            if (rec == null) {
                val now = System.currentTimeMillis()
                val duration = now - startedAt
                val r = ModelDiagnostics.CallRecord(
                    current.name,
                    false,
                    e.message ?: "未知错误",
                    now,
                    duration
                )
                ModelDiagnostics.failure(current.name, r.message, duration)
                r
            } else rec
        }
    }

    // ---------- 审计导出（阶段 2 + 阶段 2 修复：可视化 HTML） ----------

    /** 生成审计 JSON（本地树 + 云端树 + 全部日志） */
    suspend fun exportAuditJson(): String =
        AuditExporter.build(itemDao.allItems(), cloudDao.all(), logDao.observeAllNow())

    /** 生成可视化审计快照（自包含 HTML，手机上浏览器直接打开查看，内含原始 JSON） */
    suspend fun exportAuditHtml(): String =
        AuditExporter.buildHtml(itemDao.allItems(), cloudDao.all(), logDao.observeAllNow())

    // ---------- 演示工具 ----------

    /** 一键装载示例数据（10 条记忆 + 3 个示例应用登记），返回装载的记忆条数 */
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

    /** 清空本地树 + 云端树 + 全部日志 */
    suspend fun clearAll() {
        itemDao.deleteAll()
        cloudDao.deleteAll()
        logDao.deleteAll()
    }
}

/** 进程内单例（模拟系统服务定位器；阶段 4 可替换为 Binder 绑定） */
object MemoryService {
    @Volatile
    private var repository: MemoryRepository? = null

    fun repo(context: Context): MemoryRepository =
        repository ?: synchronized(this) {
            MemoryRepository(context.applicationContext).also { repository = it }
        }
}
