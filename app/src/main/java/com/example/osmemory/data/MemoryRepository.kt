package com.example.osmemory.data

import android.content.Context
import com.example.osmemory.core.dream.CloudTreeOps
import com.example.osmemory.core.dream.DreamEngine
import com.example.osmemory.core.dream.DreamPreferences
import com.example.osmemory.core.dream.DreamReport
import com.example.osmemory.core.dream.DreamScheduler
import com.example.osmemory.core.dream.LocalTreeOps
import com.example.osmemory.core.model.CloudModelProvider
import com.example.osmemory.core.model.LocalModelProvider
import com.example.osmemory.core.model.LocalModelStore
import com.example.osmemory.core.model.ModelConfig
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    // AutoDream（阶段 4）：本地树整合用端侧算力（本地小模型），云端树整合用云端模型
    private lateinit var localDreamEngine: DreamEngine
    private lateinit var cloudDreamEngine: DreamEngine
    private lateinit var dreamScheduler: DreamScheduler
    private val dreamCycleMutex = Mutex()
    private val localDreamMutex = Mutex()
    private val cloudDreamMutex = Mutex()

    private val _lastProfile = MutableStateFlow<ProfileBuilder.ProfileResult?>(null)
    private val _lastDream = MutableStateFlow<DreamReport?>(null)
    private val pendingDreamChange = AtomicReference<DreamReport?>(null)

    /** 端侧模型后台自动下载进度（首次启动触发；设置页实时展示） */
    private val _localModelDownload = MutableStateFlow<LocalModelStore.Progress?>(null)

    /** 后台任务（自动下载等）用进程级 scope；下载完成即置 null */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每次进程启动只尝试一次自动下载（失败不重试，可用"测试端侧模型"手动重试） */
    private val localModelAutoDownloadAttempted = AtomicBoolean(false)

    init {
        NetworkMonitor.init(context)
        refreshChannels()
        autoDownloadLocalModelIfNeeded()
    }

    /**
     * 首次启动自动触发端侧模型下载（新手机装完即用，无需手动安装）。
     * 仅在线且模型未就绪时启动；进度经 [observeLocalModelDownload] 暴露给设置页。
     */
    private fun autoDownloadLocalModelIfNeeded() {
        if (!localModelAutoDownloadAttempted.compareAndSet(false, true)) return
        appScope.launch {
            try {
                if (LocalModelStore.readyFile(context) != null) return@launch
                if (!NetworkMonitor.isOnline(context)) return@launch
                LocalModelStore.ensureReady(context) { progress ->
                    _localModelDownload.value = progress
                }
                _localModelDownload.value = null
            } catch (_: Exception) {
                // 下载失败（网络中断/校验失败）：置 null 恢复状态行展示，用户可点"测试端侧模型"重试
                _localModelDownload.value = null
            }
        }
    }

    /** 端侧模型自动下载进度（null = 未在下载） */
    fun observeLocalModelDownload(): StateFlow<LocalModelStore.Progress?> = _localModelDownload

    /** 模型/网络配置变更后重建通道依赖（provider 热插拔） */
    fun refreshChannels() {
        if (::dreamScheduler.isInitialized) dreamScheduler.stop()
        provider = ModelManager.provider(context)
        pipeline = MemoryPipeline(itemDao, logDao, provider)
        val reranker = SemanticReranker(provider)
        retriever = MemoryRetriever(itemDao, logDao, reranker)
        profileBuilder = ProfileBuilder(provider, itemDao, logDao)
        syncManager = TreeSyncManager(context, itemDao, cloudDao, logDao, provider)

        // AutoDream 引擎：本地树（端侧算力）＋ 云端树（云端算力），互不越界
        localDreamEngine = DreamEngine(
            provider = ModelManager.localProvider(context),
            ops = LocalTreeOps(itemDao, logDao),
            logDao = logDao
        )
        cloudDreamEngine = DreamEngine(
            provider = provider,
            ops = CloudTreeOps(cloudDao, logDao),
            logDao = logDao
        )
        dreamScheduler = DreamScheduler(
            context = context,
            dreamCycle = { includeCloud, shouldRun ->
                executeDreamCycle(includeCloud, shouldRun, publishScheduled = true)
            },
            isOnline = { NetworkMonitor.isOnline(context) }
        )
        dreamScheduler.start()
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

    /** 最近一次 Dream 结果（AutoDream 联动画像用） */
    fun observeLastDream(): StateFlow<DreamReport?> = _lastDream

    // ---------- AutoDream（阶段 4：记忆自进化） ----------

    /**
     * 立即对本地树做一次 Dream（端侧算力；手动触发/离线周期用）。
     *
     * 守卫：端侧模型未就绪或可用内存不足时不触发 llama.cpp 加载——469MB 模型加载
     * 需 1.5-2GB 内存；但仍执行确定性重复合并/常见冲突规则，并完整写入 DREAM 日志。
     */
    suspend fun dreamLocalNow(publish: Boolean = true): DreamReport = localDreamMutex.withLock {
        val local = ModelManager.localProvider(context)
        val online = NetworkMonitor.isOnline(context)
        val skip = skipLocalDreamReason(local)
        val report = localDreamEngine.dream(
            online = online,
            useModel = skip == null,
            fallbackReason = skip.orEmpty()
        )
        if (publish) _lastDream.value = report
        report
    }

    /**
     * 本地 Dream 不加载模型的原因；null 表示可安全执行端侧推理。
     * ① 端侧模型未就绪（ABI / GGUF 未下载校验）；
     * ② 可用内存不足 [MIN_FREE_MEMORY_FOR_LOCAL_DREAM]——469MB GGUF 加载需 ~1.5-2GB，
     *    低内存设备强行加载会 OOM 崩溃（Error 抓不住 Exception 直接杀进程）。
     */
    private fun skipLocalDreamReason(local: LocalModelProvider): String? {
        if (!local.isAvailable()) return "端侧模型未就绪（联网后自动下载）"
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.getMemoryInfo(memoryInfo)
        if (memoryInfo.availMem < MIN_FREE_MEMORY_FOR_LOCAL_DREAM) {
            val freeGb = "%.1f".format(memoryInfo.availMem / 1024.0 / 1024.0 / 1024.0)
            return "可用内存不足（$freeGb GB），端侧模型暂不加载"
        }
        return null
    }

    /** 立即对云端树做一次 Dream（云端算力；整合结果不脱离云端树）。离线时不可达，返回 null。 */
    suspend fun dreamCloudNow(publish: Boolean = true): DreamReport? = cloudDreamMutex.withLock {
        if (!NetworkMonitor.isOnline(context)) return@withLock null
        val report = cloudDreamEngine.dream(online = true)
        if (publish) _lastDream.value = report
        report
    }

    /**
     * 手动"立即 Dream"：始终先整合控制台写入的本地树；在线且云端 Dream 开启时，
     * 再同步并整合云端树。最终返回组合报告，避免联网时误处理空的云端树而漏掉本地新增记忆。
     */
    suspend fun dreamNow(): DreamReport? {
        dreamScheduler.cancelPendingNewMemory()
        val reports = executeDreamCycle(
            includeCloud = NetworkMonitor.isOnline(context) &&
                DreamPreferences.isCloudDreamEnabled(context)
        )
        val current = reports.toCombinedDreamReport()
        if (current.changed) pendingDreamChange.set(current)
        val recentChange = if (current.changed) null else pendingDreamChange.getAndSet(null)?.takeIf {
            System.currentTimeMillis() - it.at <= RECENT_DREAM_RESULT_MS
        }
        val displayed = if (!current.changed && recentChange?.changed == true) {
            recentChange.copy(message = "最近一轮 Dream 已提前完成整合；${recentChange.message}")
        } else {
            current
        }
        _lastDream.value = displayed
        return displayed
    }

    /** 新记忆入库后通知调度器（去抖后先整合本地树，再按网络策略整合云端树）。 */
    fun notifyNewMemoryArrived() = dreamScheduler.notifyNewMemory()

    /** 整个周期持有同一把锁，手动 Dream、AutoDream 与清空数据不会彼此穿插写入。 */
    private suspend fun executeDreamCycle(
        includeCloud: Boolean,
        shouldRun: () -> Boolean = { true },
        publishScheduled: Boolean = false
    ): List<DreamReport> =
        dreamCycleMutex.withLock {
            if (!shouldRun()) return@withLock emptyList()
            val reports = mutableListOf(dreamLocalNow(publish = false))
            if (includeCloud && NetworkMonitor.isOnline(context)) {
                // 先把本地 Dream 结果（含归档状态）推送到云端，再整合云端树。
                ensureCloudIntegrated()
                dreamCloudNow(publish = false)?.let(reports::add)
            }
            // 与整轮写入共用临界区，防止手动 waiter 抢在 AutoDream 发布前得到“稳定”报告。
            if (publishScheduled) publishDreamReports(reports)
            reports
        }

    /** AutoDream 在本地与云端都完成后只发布一次，避免云端“无变化”覆盖本地合并明细。 */
    private fun publishDreamReports(reports: List<DreamReport>) {
        if (reports.isEmpty()) return
        val current = reports.toCombinedDreamReport()
        if (current.changed) pendingDreamChange.set(current)
        val recentChange = pendingDreamChange.get()?.takeIf {
            System.currentTimeMillis() - it.at <= RECENT_DREAM_RESULT_MS
        }
        if (recentChange == null) pendingDreamChange.set(null)
        // 稳定轮次不抹掉尚待控制台展示的最近变更；手动 Dream 会消费这份报告。
        _lastDream.value = if (!current.changed && recentChange != null) recentChange else current
    }

    private fun List<DreamReport>.toCombinedDreamReport(): DreamReport =
        if (size == 1) single() else combineDreamReports(this)

    private fun combineDreamReports(reports: List<DreamReport>): DreamReport = DreamReport(
        tree = reports.joinToString(" + ") { it.tree },
        online = reports.any { it.online },
        conflictsResolved = reports.sumOf { it.conflictsResolved },
        splitCount = reports.sumOf { it.splitCount },
        mergedCount = reports.sumOf { it.mergedCount },
        distilledCount = reports.sumOf { it.distilledCount },
        archivedCount = reports.sumOf { it.archivedCount },
        message = reports.joinToString("；") { it.message },
        degraded = reports.any { it.degraded },
        reason = reports.map { it.reason }.filter { it.isNotBlank() }.distinct().joinToString("；"),
        at = reports.maxOf { it.at },
        details = reports.flatMap { report -> report.details.map { "${report.tree} · $it" } },
        affectedMemoIds = reports.flatMap { it.affectedMemoIds }.distinct()
    )

    // ---------- 记忆写入（memo_collect） ----------

    suspend fun collect(
        memoryText: String,
        source: String,
        appId: String = MemoryPipeline.CONSOLE_APP_ID,
        allowDuplicateForDream: Boolean = false
    ): MemoryPipeline.CollectResult {
        val result = pipeline.collect(memoryText, source, appId, allowDuplicateForDream)
        if (result is MemoryPipeline.CollectResult.Success) notifyNewMemoryArrived()
        return result
    }

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
    suspend fun syncNow(): TreeSyncManager.SyncReport = cloudDreamMutex.withLock {
        syncManager.sync()
    }

    /**
     * 联网自动拉取：每次 离线→在线 网络切换时把本地待同步记忆拉取到云端树
     * （由 MainActivity 监听网络状态触发；敏感/保密记忆由 cloudEligible 隔离）。离线返回 null。
     */
    suspend fun ensureCloudIntegrated(): TreeSyncManager.SyncReport? {
        if (!NetworkMonitor.isOnline(context)) return null
        return cloudDreamMutex.withLock { syncManager.autoIntegrateIfNeeded() }
    }

    /** 云端树 FAB 添加入口：云端创建记忆，敏感判断与本地一致（断网返回 Unreachable） */
    suspend fun addToCloud(
        content: String,
        source: String,
        appId: String = MemoryPipeline.CONSOLE_APP_ID
    ): TreeSyncManager.CloudAddResult = cloudDreamMutex.withLock {
        syncManager.addToCloud(content, source, appId)
    }

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
        dreamScheduler.cancelPendingNewMemory()
        dreamCycleMutex.withLock {
            localDreamMutex.withLock {
                cloudDreamMutex.withLock {
                    itemDao.deleteAll()
                    cloudDao.deleteAll()
                    logDao.deleteAll()
                }
            }
            _lastDream.value = null
            _lastProfile.value = null
            pendingDreamChange.set(null)
            syncManager.resetLastSync()
        }
    }

    private companion object {
        /** 本地 Dream 最低可用内存：469MB GGUF 加载峰值约需 1.5-2GB，低内存跳过防 OOM */
        const val MIN_FREE_MEMORY_FOR_LOCAL_DREAM = 1_200L * 1024 * 1024
        /** 手动按钮可回显最近一次由 AutoDream 抢先完成的真实变更。 */
        const val RECENT_DREAM_RESULT_MS = 15L * 60_000L
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
