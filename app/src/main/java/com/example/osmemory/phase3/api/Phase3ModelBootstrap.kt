package com.example.osmemory.phase3.api

import android.content.Context
import com.example.osmemory.BuildConfig
import com.example.osmemory.core.model.ModelConfig
import com.example.osmemory.core.model.ModelManager

/**
 * 把本机构建时注入的演示 Key 安装到既有模型设置中。
 *
 * Key 不存在时什么也不做；用户已在控制台设置过 Key 时绝不覆盖。该适配层避免阶段三
 * 修改同事正在演进的 ModelConfig / 双模型网关文件，合并后可直接移除或保留为安全注入入口。
 */
internal object Phase3ModelBootstrap {
    private const val MODEL_PREFS = "model_config"
    private const val API_KEY_PREF = "api_key"

    fun installLocalKeyIfUnset(context: Context): Boolean {
        val key = BuildConfig.PHASE3_API_KEY.trim()
        if (key.isEmpty()) return false
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE)
        if (preferences.contains(API_KEY_PREF)) return false

        ModelConfig.save(
            context = appContext,
            baseUrl = ModelConfig.baseUrl(appContext),
            model = ModelConfig.model(appContext),
            apiKey = key
        )
        ModelManager.reset()
        return true
    }
}
