package com.example.osmemory.ui.memory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.osmemory.R
import com.example.osmemory.core.pipeline.MemoryPipeline
import com.example.osmemory.data.MemoryRepository
import com.example.osmemory.data.MemoryService
import com.example.osmemory.data.cloud.TreeSyncManager
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆库页（阶段 1 修复 + 阶段 2 + 阶段 2 修复）
 *
 * - 网络状态条：在线（内网）/离线 + Cloud Tree 可达 + 模型最近调用（降级原因可见）
 * - 双树切换：本地树（Local Tree，source of truth）/ 云端树（Cloud Tree，内网云端库）
 *   - 断网：云端树面板锁定——白色虚化遮罩 + 中央锁（内容不可查看，不拉取云端记忆）
 *   - 联网（内网）：云端树可用；每次联网自动拉取本地待同步记忆（敏感/保密记忆除外）
 * - 语义检索：关键词召回 + LLM 重排（阶段 2）
 * - FAB：本地树添加本地记忆；云端树添加云端记忆（云端创建，敏感判断与本地一致）
 * - 交互：单击卡片 = 查看 + 编辑（先画像后改）；长按 = 删除
 */
class MemoryListFragment : Fragment() {

    private lateinit var repo: MemoryRepository
    private lateinit var adapter: MemoryAdapter

    /** 当前树：LOCAL / CLOUD；搜索态时忽略 */
    private var currentTree = "LOCAL"

    /** 本地树与云端树的最新缓存 */
    private var localRows: List<MemoryRow> = emptyList()
    private var cloudRows: List<MemoryRow> = emptyList()

    /** 本地实体索引（删除/编辑需要原始实体） */
    private var localEntityMap: Map<String, MemoryItemEntity> = emptyMap()

    /** 搜索态：非空表示正在展示检索结果 */
    private var searchResults: List<MemoryRow>? = null

    /** 当前已渲染的树（双树内容完全不同，切换时整体替换，不走 diff 动画） */
    private var shownTree: String? = null

    private var online = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_memory_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = MemoryService.repo(requireContext())

        adapter = MemoryAdapter(
            onClick = { row -> if (!row.isCloud) showEditDialog(row) else toastCloudReadOnly() },
            onLongClick = { row -> if (!row.isCloud) confirmDelete(row) }
        )
        val rv = view.findViewById<RecyclerView>(R.id.rvMemory)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        // 双树切换整体换列表时禁用 item 动画，避免内容交叉淡入造成的闪屏
        rv.itemAnimator = null

        view.findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { showAddDialog() }

        setupTreeTabs(view)
        setupSearch(view)
        observeStatus(view)
        observeTrees()

        // 空态文案默认本地树
        updateEmpty(view)
    }

    // ---------- 网络状态条 ----------

    private fun observeStatus(root: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeNetwork().collect { isOnline ->
                online = isOnline
                val badge = root.findViewById<TextView>(R.id.tvNetworkBadge)
                badge.text = if (isOnline) "在线（内网）" else "离线"
                badge.setTextColor(
                    ContextCompat.getColor(requireContext(),
                        if (isOnline) R.color.semantic_normal else R.color.semantic_sensitive)
                )
                badge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(),
                            if (isOnline) R.color.semantic_normal_bg else R.color.semantic_sensitive_bg)
                    )
                // 断网：云端树面板锁定（白色虚化 + 中央锁遮罩）；不把云端记忆拉取到本地
                updateCloudLock(root)
                updateCloudStatus(root)
                updateEmpty(root)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeLastSync().collect { report ->
                updateCloudStatus(root)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeModelCall().collect { call ->
                val tv = root.findViewById<TextView>(R.id.tvModelStatus)
                tv.text = buildString {
                    append("模型通道：${repo.providerName}")
                    if (call != null) {
                        append(" · 最近调用：")
                        if (call.ok) append("成功（${call.durationMs}ms）")
                        else append("失败 → ${call.message.take(60)}")
                    } else {
                        append(" · 尚未调用")
                    }
                }
                tv.setTextColor(
                    ContextCompat.getColor(requireContext(),
                        if (call == null || call.ok) R.color.semantic_normal
                        else R.color.semantic_sensitive)
                )
            }
        }
    }

    /** 云端树断网锁定遮罩：白色虚化 + 中央锁 + 提示（断网时云端树内容不可查看） */
    private fun updateCloudLock(root: View) {
        root.findViewById<View>(R.id.cloudLockOverlay).isVisible = currentTree == "CLOUD" && !online
    }

    private fun updateCloudStatus(root: View) {
        val tv = root.findViewById<TextView>(R.id.tvCloudStatus)
        val last = repo.observeLastSync().value
        tv.text = when {
            !online -> "Cloud Tree 不可达（Network Gateway 断开 · 已锁定）"
            last == null -> "Cloud Tree 可达 · 每次联网自动拉取待同步记忆"
            else -> "Cloud Tree 可达 · 最近拉取：${last.message}"
        }
    }

    /** FAB 显示规则：非搜索态显示（本地树/云端树都有添加入口）；云端树断网锁定时不显示 */
    private fun updateFab(root: View) {
        root.findViewById<FloatingActionButton>(R.id.fabAdd).isVisible =
            searchResults == null && !(currentTree == "CLOUD" && !online)
    }

    // ---------- 双树切换 ----------

    private fun setupTreeTabs(view: View) {
        val tabs = view.findViewById<TabLayout>(R.id.treeTabs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTree = if (tab.position == 0) "LOCAL" else "CLOUD"
                searchResults = null
                view.findViewById<EditText>(R.id.etSearch).setText("")
                updateCloudLock(view)
                updateFab(view)
                render(view)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun observeTrees() {
        // 本地树
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeItems().collect { items ->
                localRows = items.map { MemoryRow.fromLocal(it) }
                localEntityMap = items.associateBy { it.memoId }
                if (searchResults == null) render(view ?: return@collect)
            }
        }
        // 云端树（独立库，仅展示，本地树不从这里读回）
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeCloudItems().collect { items ->
                cloudRows = items.map { MemoryRow.fromCloud(it) }
                if (searchResults == null && currentTree == "CLOUD") render(view ?: return@collect)
            }
        }
    }

    // ---------- 语义检索（阶段 2） ----------

    private fun setupSearch(view: View) {
        val et = view.findViewById<EditText>(R.id.etSearch)
        val btn = view.findViewById<TextView>(R.id.btnSearch)
        val clear = view.findViewById<TextView>(R.id.btnSearchClear)

        btn.setOnClickListener { runSearch() }
        clear.setOnClickListener {
            et.setText("")
            searchResults = null
            updateFab(view)
            render(view)
        }
        et.setOnEditorActionListener { _, _, _ -> runSearch(); true }
        et.doAfterTextChanged { if (it.isNullOrBlank() && searchResults != null) { searchResults = null; render(view) } }
    }

    private fun runSearch() {
        val query = view?.findViewById<EditText>(R.id.etSearch)?.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            searchResults = null
            render(view ?: return)
            return
        }
        val pb = view?.findViewById<ProgressBar>(R.id.pbLoading)
        pb?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) {
                repo.getMemo(appId = MemoryPipeline.CONSOLE_APP_ID, query = query, limit = 15, policyMax = 2, semantic = true)
            }
            pb?.isVisible = false
            searchResults = hits.map { MemoryRow.fromLocal(it) }
            view?.findViewById<FloatingActionButton>(R.id.fabAdd)?.isVisible = false
            render(view ?: return@launch)
        }
    }

    // ---------- 渲染 ----------

    private fun render(root: View) {
        val visibleRows = searchResults ?: when (currentTree) {
            "LOCAL" -> localRows
            else -> cloudRows
        }
        if (searchResults == null && shownTree != currentTree) {
            // 双树内容完全不同：先同步清空、再整体填充（跳过异步 diff 与动画），避免切回本地树时闪一下
            adapter.submitList(null)
            adapter.submitList(visibleRows)
            shownTree = currentTree
        } else {
            adapter.submitList(visibleRows)
        }
        updateCloudLock(root)
        updateEmpty(root)
    }

    private fun updateEmpty(root: View) {
        val tvEmpty = root.findViewById<TextView>(R.id.tvEmpty)
        val emptyText = when {
            searchResults != null -> "检索无结果\n换一个关键词试试，或清空搜索回到树视图"
            currentTree == "CLOUD" -> "云端树为空\n联网后会自动拉取本地待同步记忆（敏感/保密记忆除外）"
            else -> "本地树为空\n点击右下角 + 添加一条记忆\n或从抽屉「装载示例数据」一键演示"
        }
        tvEmpty.text = emptyText
        tvEmpty.isVisible = adapter.itemCount == 0
    }

    // ---------- 添加记忆 ----------

    private fun showAddDialog() {
        if (currentTree == "CLOUD") {
            showCloudAddDialog()
            return
        }
        showLocalAddDialog()
    }

    /** 本地树添加：走标准流水线（净化→门控→LLM 抽取→去重→入库→双日志） */
    private fun showLocalAddDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_memory, null)
        val etContent = dialogView.findViewById<EditText>(R.id.etContent)
        val spSource = dialogView.findViewById<Spinner>(R.id.spSource)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加记忆（本地树）")
            .setView(dialogView)
            .setPositiveButton("存入记忆库") { _, _ ->
                val text = etContent.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(requireContext(), "记忆内容为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val source = SOURCES[spSource.selectedItemPosition]
                addMemory(text, source)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 云端树添加（阶段 2 修复：云端态 FAB 添加入口，敏感判断与本地一致） */
    private fun showCloudAddDialog() {
        if (!online) {
            Toast.makeText(requireContext(), "云端树离线不可查看，无法添加", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_memory, null)
        val etContent = dialogView.findViewById<EditText>(R.id.etContent)
        val spSource = dialogView.findViewById<Spinner>(R.id.spSource)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加记忆（云端树 · 云端创建）")
            .setMessage("在云端/内网环境中创建记忆：敏感判断与本地一致（命中敏感规则或 LLM 标记即标敏感），断网后云端树不可查看。")
            .setView(dialogView)
            .setPositiveButton("存入云端树") { _, _ ->
                val text = etContent.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(requireContext(), "记忆内容为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val source = SOURCES[spSource.selectedItemPosition]
                addCloudMemory(text, source)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addMemory(text: String, source: String) {
        val pb = view?.findViewById<ProgressBar>(R.id.pbLoading)
        pb?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repo.collect(text, source) }
            pb?.isVisible = false
            val ctx = requireContext()
            when (result) {
                is MemoryPipeline.CollectResult.Success -> {
                    val suffix = if (result.degraded) "（模型通道不可用，已降级原文入库）" else ""
                    Toast.makeText(ctx, "已入库${suffix}：${result.item.title}", Toast.LENGTH_LONG).show()
                }
                is MemoryPipeline.CollectResult.Duplicate ->
                    Toast.makeText(ctx, "重复记忆已拒绝（命中 ${result.existing.memoId}，24h 内同源同内容）", Toast.LENGTH_LONG).show()
                is MemoryPipeline.CollectResult.Rejected ->
                    Toast.makeText(ctx, "记忆被拒绝：${result.reason}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 云端树添加（云端创建，敏感判断与本地一致；断网 Unreachable） */
    private fun addCloudMemory(text: String, source: String) {
        val pb = view?.findViewById<ProgressBar>(R.id.pbLoading)
        pb?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repo.addToCloud(text, source, appId = MemoryPipeline.CONSOLE_APP_ID)
            }
            pb?.isVisible = false
            val ctx = requireContext()
            when (result) {
                is TreeSyncManager.CloudAddResult.Success -> {
                    val suffix = if (result.degraded) "（模型通道不可用，已降级原文入库）" else ""
                    Toast.makeText(ctx, "已存入云端树（敏感）${suffix}：${result.item.title}", Toast.LENGTH_LONG).show()
                }
                is TreeSyncManager.CloudAddResult.Rejected ->
                    Toast.makeText(ctx, "云端树拒绝：${result.reason}", Toast.LENGTH_LONG).show()
                TreeSyncManager.CloudAddResult.Unreachable ->
                    Toast.makeText(ctx, "云端树离线不可达，无法添加", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 查看 + 编辑（先画像后改，阶段 2） ----------

    private fun showEditDialog(row: MemoryRow) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_memory, null)
        val tvContext = dialogView.findViewById<TextView>(R.id.tvProfileContext)
        val etContent = dialogView.findViewById<EditText>(R.id.etContent)
        val cbSecret = dialogView.findViewById<CheckBox>(R.id.cbSecret)

        // 画像上下文：分类/标签/敏感级/置信度/创建时间（先画像后改）
        tvContext.text = "分类：${row.category}\n" +
            "标签：${row.tags.ifBlank { "无" }}\n" +
            "敏感级：${policyLabel(row.policyLevel)} · 置信：${(row.confidence * 100).toInt()}%\n" +
            "创建：${TIME_FORMAT.format(Date(row.createdAt))} · 来源：${MemoryAdapter.sourceLabel(row.source)}"
        etContent.setText(row.content)
        cbSecret.isChecked = row.syncLabel == "敏感不迁移" || row.policyLevel >= 2

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑记忆（先画像后改）")
            .setView(dialogView)
            .setPositiveButton("保存修改") { _, _ ->
                val newText = etContent.text?.toString()?.trim().orEmpty()
                if (newText.isEmpty()) {
                    Toast.makeText(requireContext(), "内容为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                updateMemory(row, newText, cbSecret.isChecked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateMemory(row: MemoryRow, newText: String, secret: Boolean) {
        val pb = view?.findViewById<ProgressBar>(R.id.pbLoading)
        pb?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repo.updateItem(row.memoId, newText, forceSecret = secret)
            }
            pb?.isVisible = false
            val ctx = requireContext()
            when (result) {
                is MemoryPipeline.UpdateResult.Success -> {
                    val suffix = if (result.degraded) "（模型降级，抽取回退原文）" else ""
                    Toast.makeText(ctx, "已更新${suffix}：${result.item.title}", Toast.LENGTH_SHORT).show()
                }
                is MemoryPipeline.UpdateResult.NotFound ->
                    Toast.makeText(ctx, "记忆不存在：${row.memoId}", Toast.LENGTH_SHORT).show()
                is MemoryPipeline.UpdateResult.Rejected ->
                    Toast.makeText(ctx, "更新被拒绝：${result.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 删除 ----------

    private fun confirmDelete(row: MemoryRow) {
        val entity = localEntityMap[row.memoId] ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除记忆")
            .setMessage("「${row.title}」\n${row.content}\n\n删除本地树中的这条记忆后不可恢复，确定删除？")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deleteItem(entity) }
                    Toast.makeText(requireContext(), "已删除记忆 ${row.memoId}（日志已留痕）", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toastCloudReadOnly() {
        Toast.makeText(requireContext(), "云端树为只读镜像（本地不能 pull 云端内容），请在本地树编辑", Toast.LENGTH_SHORT).show()
    }

    private fun policyLabel(level: Int): String = when (level) {
        2 -> "敏感"
        0 -> "公开"
        else -> "普通"
    }

    companion object {
        fun newInstance() = MemoryListFragment()

        private val SOURCES = arrayOf("console", "notes", "chat", "files")
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }
}
