package com.arm.aichat.internal

import android.content.Context
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 单线程串行 JNI 包装，native 实现来自固定版本的官方 llama.cpp Android 示例。 */
internal class InferenceEngineImpl private constructor(nativeLibDir: String) : InferenceEngine {
    private val mutableState = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = mutableState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        try {
            mutableState.value = InferenceEngine.State.Initializing
            System.loadLibrary("ai-chat")
            init(nativeLibDir)
            mutableState.value = InferenceEngine.State.Initialized
        } catch (error: Throwable) {
            mutableState.value = InferenceEngine.State.Error(error)
            throw error
        }
    }

    override suspend fun loadModel(pathToModel: String) = withContext(dispatcher) {
        check(mutableState.value is InferenceEngine.State.Initialized) {
            "无法在 ${mutableState.value.javaClass.simpleName} 状态加载模型"
        }
        val file = File(pathToModel)
        require(file.isFile && file.canRead()) { "GGUF 模型文件不可读" }
        try {
            mutableState.value = InferenceEngine.State.LoadingModel
            if (load(pathToModel) != 0) throw IOException("llama.cpp 无法加载该 GGUF 架构")
            if (prepare() != 0) throw IOException("llama.cpp 上下文初始化失败")
            mutableState.value = InferenceEngine.State.ModelReady
        } catch (error: Throwable) {
            mutableState.value = InferenceEngine.State.Error(error)
            throw error
        }
    }

    override suspend fun setSystemPrompt(systemPrompt: String) = withContext(dispatcher) {
        require(systemPrompt.isNotBlank()) { "系统提示不能为空" }
        check(mutableState.value is InferenceEngine.State.ModelReady)
        try {
            mutableState.value = InferenceEngine.State.ProcessingSystemPrompt
            if (processSystemPrompt(systemPrompt) != 0) {
                throw IOException("端侧模型无法处理系统提示")
            }
            mutableState.value = InferenceEngine.State.ModelReady
        } catch (error: Throwable) {
            mutableState.value = InferenceEngine.State.Error(error)
            throw error
        }
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        require(message.isNotBlank()) { "用户提示不能为空" }
        check(mutableState.value is InferenceEngine.State.ModelReady)
        try {
            mutableState.value = InferenceEngine.State.ProcessingUserPrompt
            if (processUserPrompt(message, predictLength) != 0) {
                throw IOException("端侧模型无法处理用户提示")
            }
            mutableState.value = InferenceEngine.State.Generating
            while (true) {
                val token = generateNextToken() ?: break
                if (token.isNotEmpty()) emit(token)
            }
            mutableState.value = InferenceEngine.State.ModelReady
        } catch (cancelled: CancellationException) {
            mutableState.value = InferenceEngine.State.ModelReady
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = InferenceEngine.State.Error(error)
            throw error
        }
    }.flowOn(dispatcher)

    override suspend fun cleanUp() = withContext(dispatcher) {
        when {
            mutableState.value.isModelLoaded -> {
                mutableState.value = InferenceEngine.State.UnloadingModel
                unload()
                mutableState.value = InferenceEngine.State.Initialized
            }
            mutableState.value is InferenceEngine.State.Error -> {
                unload()
                mutableState.value = InferenceEngine.State.Initialized
            }
            mutableState.value is InferenceEngine.State.Initialized -> Unit
            else -> error("无法在 ${mutableState.value.javaClass.simpleName} 状态卸载模型")
        }
    }

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun prepare(): Int
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unload()

    companion object {
        @Volatile
        private var instance: InferenceEngine? = null

        fun getInstance(context: Context): InferenceEngine =
            instance ?: synchronized(this) {
                instance ?: InferenceEngineImpl(context.applicationInfo.nativeLibraryDir).also {
                    instance = it
                }
            }
    }
}
