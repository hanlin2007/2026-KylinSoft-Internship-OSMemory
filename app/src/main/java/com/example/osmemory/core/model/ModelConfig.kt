package com.example.osmemory.core.model

import android.content.Context

/**
 * 模型通道配置（阶段 1 默认：云端大模型替代端侧小模型）
 *
 * BaseURL / Model / API Key 三项均可被 SharedPreferences 覆盖（阶段 2 提供设置页）。
 * API Key 默认留空，由设置页或本机构建配置注入，禁止进入源码与 Git 历史。
 */
object ModelConfig {

    /**
     * 云端模型通道（OpenAI 兼容 API）。
     *
     * ⚠️ 端点修正（2026-08-06）：真实终端为 https://api.ppio.com/openai/v1/chat/completions，
     * 故 base 必须含 `/v1` 段，否则拼出 `…/openai/chat/completions`（缺 /v1）会返回 404/400 类错误。
     */
    const val DEFAULT_BASE_URL = "https://api.ppio.com/openai/v1"
    const val DEFAULT_MODEL = "deepseek/deepseek-v4-flash"

    /** 安全默认值：未配置时由模型网关返回明确鉴权失败，不在源码中携带凭据。 */
    private const val DEFAULT_API_KEY = ""

    /** 本地小模型通道（llama.cpp Android 扩展点，阶段 4）——当前不可用 */
    const val LOCAL_CHANNEL_DISABLED_REASON =
        "本地小模型通道未启用：可接入 llama.cpp Android / MLC-LLM 加载 GGUF 模型（需 NDK 与模型文件），接口已按统一契约预留"

    private const val PREF_NAME = "model_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY

    fun save(context: Context, baseUrl: String, model: String, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_MODEL, model.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    /** 组装 chat/completions 端点：兼容 base 形如 …/openai、…/v1、…/chat/completions 三种形态 */
    fun chatCompletionsEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/chat/completions"
        }
    }

    /** 若 base 不含 /v1，则返回 /v1 变体端点用于失败重试（部分网关要求 /v1 前缀） */
    fun v1FallbackEndpoint(baseUrl: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.contains("/v1") || base.endsWith("/chat/completions")) return null
        return "$base/v1/chat/completions"
    }
}
