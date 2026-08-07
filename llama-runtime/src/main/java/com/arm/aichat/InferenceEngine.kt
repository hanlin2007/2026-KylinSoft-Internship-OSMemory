package com.arm.aichat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InferenceEngine {
    val state: StateFlow<State>

    suspend fun loadModel(pathToModel: String)
    suspend fun setSystemPrompt(systemPrompt: String)
    fun sendUserPrompt(message: String, predictLength: Int = 384): Flow<String>
    suspend fun cleanUp()

    sealed class State {
        data object Uninitialized : State()
        data object Initializing : State()
        data object Initialized : State()
        data object LoadingModel : State()
        data object UnloadingModel : State()
        data object ModelReady : State()
        data object ProcessingSystemPrompt : State()
        data object ProcessingUserPrompt : State()
        data object Generating : State()
        data class Error(val throwable: Throwable) : State()
    }
}

val InferenceEngine.State.isModelLoaded: Boolean
    get() = this is InferenceEngine.State.ModelReady ||
        this is InferenceEngine.State.ProcessingSystemPrompt ||
        this is InferenceEngine.State.ProcessingUserPrompt ||
        this is InferenceEngine.State.Generating
