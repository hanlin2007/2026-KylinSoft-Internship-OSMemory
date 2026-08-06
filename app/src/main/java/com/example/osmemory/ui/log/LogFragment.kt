package com.example.osmemory.ui.log

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.osmemory.R
import com.example.osmemory.data.MemoryRepository
import com.example.osmemory.data.MemoryService
import com.example.osmemory.data.db.entity.MemoryLogEntity
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * 调用日志页（对应开发方案"日志三板块"）
 * 传入（COLLECT）/ 检索（RETRIEVE）/ 推理（INFER），TabLayout 切换，Flow 实时刷新
 */
class LogFragment : Fragment() {

    private lateinit var repo: MemoryRepository
    private lateinit var adapter: LogAdapter

    /** 当前 Tab 对应的日志类型 */
    private var currentType = LOG_TYPES[0]

    /** 各类型最新日志缓存 */
    private val cached = mutableMapOf<String, List<MemoryLogEntity>>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_logs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = MemoryService.repo(requireContext())

        adapter = LogAdapter()
        val rv = view.findViewById<RecyclerView>(R.id.rvLogs)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val tabs = view.findViewById<TabLayout>(R.id.tabs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentType = LOG_TYPES[tab.position.coerceIn(0, LOG_TYPES.lastIndex)]
                render(view)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        // 三个板块的观察流
        LOG_TYPES.forEach { type ->
            viewLifecycleOwner.lifecycleScope.launch {
                repo.observeLogs(type).collect { list ->
                    cached[type] = list
                    if (type == currentType) render(view)
                }
            }
        }
    }

    private fun render(root: View) {
        val list = cached[currentType] ?: return
        adapter.submitList(list)
        root.findViewById<TextView>(R.id.tvLogEmpty).isVisible = list.isEmpty()
    }

    companion object {
        fun newInstance() = LogFragment()

        private val LOG_TYPES = listOf("COLLECT", "RETRIEVE", "INFER")
    }
}
