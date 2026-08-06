package com.example.osmemory.core.model

import android.content.Context

/**
 * 本地小模型通道：扩展点存根（阶段 4 启用）
 *
 * 【可行性结论】Android 上部署端侧小模型完全可行：
 *  1. llama.cpp Android（llama-android）：GGUF 格式，支持 1~4B 级量化模型，需 NDK 工具链；
 *  2. MLC-LLM：TFLite/Android 运行时，模型需提前转换；
 *  3. Termux + llama.cpp：快速试验路线（非原生）。
 * 代价：模型文件数百 MB~数 GB、推理速度受限、包体积膨胀。
 * 演示环境推荐云端通道（快、稳），本存根保证"统一接口 + 热插拔"。
 *
 * 启用路径：实现 [complete] 并让 [isAvailable] 返回 true（如检测到已下载的 GGUF 模型），
 * ModelManager 会自动优先本地通道，业务代码零改动。
 */
object LocalModelProvider : ModelProvider {

    override val name = "端侧小模型（llama.cpp 扩展点）"

    fun isAvailable(context: Context): Boolean = false

    override suspend fun complete(
        system: String,
        user: String,
        temperature: Double
    ): String {
        throw ModelException(ModelConfig.LOCAL_CHANNEL_DISABLED_REASON)
    }
}
