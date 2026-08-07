package com.example.osmemory.core.model

/**
 * 模型通道统一契约（双通道设计，杜绝规则引擎替代 AI 能力）
 *
 * 业务层只依赖本接口：云端通道（阶段 1 默认）与本地小模型通道（阶段 4 扩展点）
 * 实现可热插拔，业务代码零改动。
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

/** 通道管理器：当前固定走云端；本地通道可用后自动优先本地 */
object ModelManager {

    @Volatile
    private var cached: ModelProvider? = null

    /** 获取当前模型通道（缓存实例；设置页修改配置后调用 [reset] 重建） */
    fun provider(context: android.content.Context): ModelProvider {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: buildProvider(context).also { cached = it }
        }
    }

    private fun buildProvider(context: android.content.Context): ModelProvider {
        val local = LocalModelProvider
        return if (local.isAvailable(context)) local else CloudModelProvider(context)
    }

    /** 设置变更后重建通道（新的 baseUrl/model/apiKey 生效） */
    fun reset() {
        cached = null
    }
}
