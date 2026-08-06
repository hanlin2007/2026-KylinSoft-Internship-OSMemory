package com.example.osmemory

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.osmemory.data.MemoryService
import com.example.osmemory.ui.log.LogFragment
import com.example.osmemory.ui.memory.MemoryListFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OS Memory 控制台（阶段 1：记忆库 + 调用日志）
 *
 * 工具栏：装载示例数据 / 清空记忆库（演示专用）
 * 底部导航：记忆库（原子记忆卡列表）/ 调用日志（传入/检索/推理三板块）
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_load_sample -> {
                    loadSampleData()
                    true
                }
                R.id.action_clear -> {
                    confirmClearAll()
                    true
                }
                else -> false
            }
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_memory -> {
                    showFragment(MemoryListFragment.newInstance())
                    true
                }
                R.id.nav_logs -> {
                    showFragment(LogFragment.newInstance())
                    true
                }
                else -> false
            }
        }
        bottomNav.selectedItemId = R.id.nav_memory
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun loadSampleData() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                MemoryService.repo(this@MainActivity).loadSampleData()
            }
            val message = if (count > 0) {
                "已装载 $count 条示例记忆（含 3 个示例应用登记）"
            } else {
                "示例数据已存在，无需重复装载"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空记忆库")
            .setMessage("将删除全部记忆与调用日志，且不可恢复。确定清空？")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { MemoryService.repo(this@MainActivity).clearAll() }
                    Toast.makeText(this@MainActivity, "记忆库已清空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
