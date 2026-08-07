package com.arm.aichat

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl

/** OS Memory 使用的轻量 llama.cpp Android 门面。 */
object AiChat {
    fun getInferenceEngine(context: Context): InferenceEngine =
        InferenceEngineImpl.getInstance(context.applicationContext)
}
