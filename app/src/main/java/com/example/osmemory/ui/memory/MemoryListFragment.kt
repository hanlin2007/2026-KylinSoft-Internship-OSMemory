package com.example.osmemory.ui.memory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.osmemory.R
import com.example.osmemory.core.pipeline.MemoryPipeline
import com.example.osmemory.data.MemoryRepository
import com.example.osmemory.data.MemoryService
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆库页（阶段 1）
 * - 原子记忆卡列表（Flow 自动刷新）
 * - FAB 添加记忆（走完整流水线：净化→门控→LLM 抽取→去重→入库）
 * - 点击卡片删除
 */
class MemoryListFragment : Fragment() {

    private lateinit var repo: MemoryRepository
    private lateinit var adapter: MemoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_memory_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = MemoryService.repo(requireContext())

        adapter = MemoryAdapter { item -> confirmDelete(item) }
        val rv = view.findViewById<RecyclerView>(R.id.rvMemory)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            showAddDialog()
        }

        // 记忆库观察流
        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeItems().collect { list ->
                adapter.submitList(list)
                view.findViewById<TextView>(R.id.tvEmpty).isVisible = list.isEmpty()
            }
        }
    }

    private fun showAddDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_memory, null)
        val etContent = dialogView.findViewById<EditText>(R.id.etContent)
        val spSource = dialogView.findViewById<Spinner>(R.id.spSource)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加记忆")
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

    private fun addMemory(text: String, source: String) {
        val pb = view?.findViewById<ProgressBar>(R.id.pbLoading)
        pb?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repo.collect(text, source) }
            pb?.isVisible = false
            val context = requireContext()
            when (result) {
                is MemoryPipeline.CollectResult.Success -> {
                    val suffix = if (result.degraded) "（模型通道不可用，已降级原文入库）" else ""
                    Toast.makeText(
                        context,
                        "已入库${suffix}：${result.item.title}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is MemoryPipeline.CollectResult.Duplicate -> {
                    Toast.makeText(
                        context,
                        "重复记忆已拒绝（命中 ${result.existing.memoId}，24h 内同源同内容）",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is MemoryPipeline.CollectResult.Rejected -> {
                    Toast.makeText(context, "记忆被拒绝：${result.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDelete(item: MemoryItemEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除记忆")
            .setMessage("「${item.title}」\n${item.content}\n\n删除后不可恢复，确定删除？")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deleteItem(item) }
                    Toast.makeText(
                        requireContext(),
                        "已删除记忆 ${item.memoId}（日志已留痕）",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        fun newInstance() = MemoryListFragment()

        /** 与 strings source_options 顺序对应：控制台/记事本/对话/文件 */
        private val SOURCES = arrayOf("console", "notes", "chat", "files")
    }
}
