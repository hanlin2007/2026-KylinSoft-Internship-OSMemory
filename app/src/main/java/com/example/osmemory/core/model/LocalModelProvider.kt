package com.example.osmemory.core.model

import android.content.Context
import android.os.Build
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** llama.cpp Android/JNI 端侧通道；只在离线新增本地记忆的演示链路使用。 */
class LocalModelProvider(context: Context) : ModelProvider {
    private val appContext = context.applicationContext
    private val inferenceMutex = Mutex()

    override val name: String = "端侧小模型（llama.cpp · ${LocalModelSpec.DISPLAY_NAME}）"

    fun isAvailable(): Boolean =
        runtimeAbiSupported() && LocalModelStore.readyFile(appContext) != null

    override suspend fun complete(
        system: String,
        user: String,
        temperature: Double
    ): String = inferenceMutex.withLock {
        val startedAt = System.currentTimeMillis()
        try {
            if (!runtimeAbiSupported()) {
                throw ModelException("当前 ABI 不支持端侧模型：${Build.SUPPORTED_ABIS.joinToString()}")
            }
            val model = LocalModelStore.readyFile(appContext)
                ?: throw ModelException("端侧模型尚未准备，请先在模型配置页点击“测试端侧模型”")
            val engine = AiChat.getInferenceEngine(appContext)
            awaitInitialized(engine)
            if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
                runCatching { engine.cleanUp() }
            }
            awaitInitialized(engine)
            engine.loadModel(model.absolutePath)
            engine.setSystemPrompt(system)
            val reply = engine.sendUserPrompt(
                message = "$user\n/no_think",
                predictLength = MAX_PREDICT_TOKENS
            ).toList().joinToString("").trim()
            if (reply.isBlank()) throw ModelException("端侧模型返回空内容")

            val duration = System.currentTimeMillis() - startedAt
            ModelDiagnostics.success(name, duration)
            reply
        } catch (error: Throwable) {
            val duration = System.currentTimeMillis() - startedAt
            val message = error.message ?: error.javaClass.simpleName
            ModelDiagnostics.failure(name, message, duration)
            throw if (error is ModelException) error
            else ModelException("端侧模型调用失败：$message", error)
        } finally {
            runCatching {
                val engine = AiChat.getInferenceEngine(appContext)
                if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
                    engine.cleanUp()
                }
            }
        }
    }

    private suspend fun awaitInitialized(engine: InferenceEngine) {
        val state = withTimeout(30_000L) {
            engine.state.first {
                it !is InferenceEngine.State.Uninitialized &&
                    it !is InferenceEngine.State.Initializing &&
                    it !is InferenceEngine.State.LoadingModel &&
                    it !is InferenceEngine.State.UnloadingModel
            }
        }
        if (state is InferenceEngine.State.Error) {
            runCatching { engine.cleanUp() }
        }
    }

    companion object {
        private const val MAX_PREDICT_TOKENS = 384

        fun runtimeAbiSupported(): Boolean = Build.SUPPORTED_ABIS.any { it == "x86_64" }
    }
}
