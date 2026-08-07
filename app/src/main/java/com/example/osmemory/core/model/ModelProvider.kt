package com.example.osmemory.core.model

import com.example.osmemory.core.net.NetworkMonitor

/**
 * 模型通道统一契约（双插拔接口设计，杜绝规则引擎替代 AI 能力）
 *
 * 两个可插拔模型接口（对齐产品"网关分离"设计）：
 *  - [CloudModelProvider]：云端大模型（联网/政企内网 = "云端状态"时使用，算力更强）
 *  - [LocalModelProvider]：手机端部署的本地小模型（离线/本地网关使用，共享 BaseURL/端点，仅 Model ID 不同）
 *
 * 业务层只依赖本接口；[ModelManager] 按网络状态路由通道，接入本地小模型后业务代码零改动。
 */
interface ModelProvider {

    /** 通道显示名（控制台展示用） */
    val name: String

    /**
     * 完成一次模型对话。
     *
     * @param system 系统提示（约束输出格式等）
     * @param user   用户内容
     * @param temperature 采样温度
     * @return 模型回复文本
     * @throws ModelException 调用失败（上层负责降级）
     */
    suspend fun complete(system: String, user: String, temperature: Double = 0.3): String
}

/** 模型调用异常（网络 / 网关 / 解析失败均归一为此异常，便于上层降级） */
open class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 携带 HTTP 状态码的模型异常（用于端点 /v1 变体自动重试） */
class HttpModelException(val code: Int, message: String, cause: Throwable? = null) :
    ModelException(message, cause)

/**
 * 通道管理器：按网络状态路由（云端状态 → 云端大模型；离线 → 本地小模型）。
 *
 * - 联网/内网（[NetworkMonitor.online] = true）：云端大模型通道（更强算力）。
 * - 离线：本地小模型通道；本地小模型未接入（isAvailable=false）时回退云端通道，
 *   离线网络调用失败即降级留痕（见流水线 degradeReason）。
 * - 设置页修改配置后调用 [reset] 重建。
 */
object ModelManager {

    @Volatile
    private var cachedCloud: ModelProvider? = null

    @Volatile
    private var cachedLocal: ModelProvider? = null

    /** 获取当前模型通道（按网络状态路由；缓存实例，配置变更后调用 [reset] 重建） */
    fun provider(context: android.content.Context): ModelProvider {
        val local = LocalModelProvider
        return if (NetworkMonitor.isOnline(context)) {
            // 云端状态：云端大模型（更强算力）
            cachedCloud ?: synchronized(this) {
                cachedCloud ?: CloudModelProvider(context).also { cachedCloud = it }
            }
        } else {
            // 离线/本地网关：优先本地小模型；未接入时回退云端通道（离线即降级）
            if (local.isAvailable(context)) {
                cachedLocal ?: synchronized(this) {
                    cachedLocal ?: local.also { cachedLocal = it }
                }
            } else {
                cachedCloud ?: synchronized(this) {
                    cachedCloud ?: CloudModelProvider(context).also { cachedCloud = it }
                }
            }
        }
    }

    /** 设置变更后重建通道（新的 baseUrl/model/localModel/apiKey 生效） */
    fun reset() {
        cachedCloud = null
        cachedLocal = null
    }
}
