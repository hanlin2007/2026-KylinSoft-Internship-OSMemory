package com.example.osmemory

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.osmemory.data.MemoryService
import com.example.osmemory.ui.log.LogFragment
import com.example.osmemory.ui.memory.MemoryListFragment
import com.example.osmemory.ui.profile.ProfileFragment
import com.example.osmemory.ui.settings.DreamSettingsFragment
import com.example.osmemory.ui.settings.ModelSettingsFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OS Memory 控制台（阶段 1 修复 + 阶段 2）
 *
 * - 顶部工具栏只保留标题 + 汉堡，操作全部收进左侧抽屉（修复 Pixel 9 工具栏超高无法点击）
 * - 抽屉操作：装载示例数据 / 同步到云端（单向拉取）/ 模型设置 / 审计导出 / 清空记忆库
 * - 底部导航三页：记忆库（双树）/ 画像（三板块）/ 调用日志（四板块）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navHeaderNetwork: TextView
    private lateinit var navHeaderModel: TextView

    /** 可视化导出（阶段 2 修复）：HTML 快照，Pixel 浏览器可直接打开查看 */
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) writeAudit(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawerLayout)
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.open_drawer, R.string.close_drawer
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        val navDrawer = findViewById<NavigationView>(R.id.navDrawer)
        val header = navDrawer.getHeaderView(0)
        navHeaderNetwork = header.findViewById(R.id.navHeaderNetwork)
        navHeaderModel = header.findViewById(R.id.navHeaderModel)
        // 阶段 2 修复：侧栏头部整体下移一个状态栏高度，避免与系统状态栏文字重叠
        fixNavHeaderStatusBarInset(header)
        observeStatus()

        navDrawer.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_load_sample -> loadSampleData()
                R.id.action_sync_cloud -> syncToCloud()
                R.id.action_model_settings -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    showFragment(ModelSettingsFragment.newInstance())
                }
                R.id.action_dream_settings -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    showFragment(DreamSettingsFragment.newInstance())
                }
                R.id.action_audit_export -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    startAuditExport()
                }
                R.id.action_clear -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    confirmClearAll()
                }
            }
            true
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_memory -> {
                    showFragment(MemoryListFragment.newInstance())
                    true
                }
                R.id.nav_profile -> {
                    showFragment(ProfileFragment.newInstance())
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

    /**
     * 抽屉头部与系统状态栏重叠修复：把头部内容整体下移一个状态栏高度。
     * （header 背景会延伸到状态栏区域，文本随之让出，保证可读、可点击。）
     */
    private fun fixNavHeaderStatusBarInset(header: View) {
        val statusBarHeight = statusBarHeightPx()
        if (statusBarHeight <= 0) return
        header.setPadding(
            header.paddingLeft,
            header.paddingTop + statusBarHeight,
            header.paddingRight,
            header.paddingBottom
        )
    }

    /** 读取系统状态栏高度（像素）；不可用时返回 0 */
    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    /** 抽屉头部实时展示：联网状态 + 模型通道 + 上次模型调用；联网（内网）时自动触发云端树整合拉取 */
    private fun observeStatus() {
        val repo = MemoryService.repo(this)
        var lastOnline: Boolean? = null
        lifecycleScope.launch {
            repo.observeNetwork().collect { online ->
                navHeaderNetwork.text = if (online) "● 在线（内网 · Cloud Tree 可达）" else "● 离线（Cloud Tree 不可达）"
                navHeaderNetwork.setTextColor(if (online) 0xFFB9F6CA.toInt() else 0xFFFFCDD2.toInt())
                // 每次 离线 → 在线 网络切换：自动拉取本地待同步记忆到云端树（敏感/保密记忆隔离）
                val was = lastOnline
                lastOnline = online
                if (online && was == false) {
                    lifecycleScope.launch {
                        val report = withContext(Dispatchers.IO) { repo.ensureCloudIntegrated() }
                        if (report != null) {
                            Toast.makeText(
                                this@MainActivity,
                                report.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repo.observeModelCall().collect { call ->
                navHeaderModel.text = buildString {
                    append("模型通道：${repo.providerName}")
                    if (call != null) {
                        append("\n最近调用：")
                        append(if (call.ok) "成功（${call.durationMs}ms）" else "失败 → ${call.message.take(40)}")
                    }
                }
            }
        }
    }

    private fun loadSampleData() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                MemoryService.repo(this@MainActivity).loadSampleData()
            }
            val message = if (count > 0) {
                "已装载 $count 条示例记忆（含 3 个示例应用登记），可到抽屉「同步到云端」演示单向拉取"
            } else {
                "示例数据已存在，无需重复装载"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncToCloud() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                MemoryService.repo(this@MainActivity).syncNow()
            }
            Toast.makeText(this@MainActivity, report.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startAuditExport() {
        exportLauncher.launch("osmemory_audit_${System.currentTimeMillis()}.html")
    }

    private fun writeAudit(uri: android.net.Uri) {
        lifecycleScope.launch {
            // 可视化 HTML 快照（含原始 JSON 折叠块），Pixel 浏览器直接打开查看
            val html = withContext(Dispatchers.IO) { MemoryService.repo(this@MainActivity).exportAuditHtml() }
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) "可视化审计快照已导出（本地树+云端树+全部日志），用浏览器打开即可查看"
                else "导出失败：无法写入目标文件",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空记忆库")
            .setMessage("将删除本地树与云端树的全部记忆与调用日志，且不可恢复。确定清空？")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { MemoryService.repo(this@MainActivity).clearAll() }
                    Toast.makeText(this@MainActivity, "本地树与云端树已清空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
