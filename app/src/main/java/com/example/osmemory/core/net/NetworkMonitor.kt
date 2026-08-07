package com.example.osmemory.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 网络状态路由（阶段 1 修复 + 阶段 2 双树的基础）
 *
 * 通过 ConnectivityManager 注册默认网络回调，把"在线 / 离线"抽象为 StateFlow：
 * - 在线（有 INTERNET 且 VALIDATED 能力）：Cloud Tree 可达，允许 本地→云端 单向拉取
 * - 离线：Cloud Tree 不可达，本地树独立可用（本地优先，见 PPT 第 6 页）
 *
 * UI 消费 [online]，断网/联网即时反馈（控制台顶部状态徽标）。
 */
object NetworkMonitor {

    private val _online = MutableStateFlow(false)

    /** 当前是否在线（联网状态路由的唯一判据） */
    val online: StateFlow<Boolean> = _online

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _online.value = isOnline(cm)
        // registerDefaultNetworkCallback 需要主线程 Looper（repo 可能在 IO 线程首次创建）
        Handler(Looper.getMainLooper()).post {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = true
                }

                override fun onLost(network: Network) {
                    _online.value = isOnline(cm)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = hasInternet(caps)
                }
            })
        }
    }

    /** 同步查询（初始化前 / 回调不可靠时兜底） */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return isOnline(cm)
    }

    private fun isOnline(cm: ConnectivityManager): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return hasInternet(caps)
    }

    private fun hasInternet(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
