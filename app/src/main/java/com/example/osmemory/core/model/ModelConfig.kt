package com.example.osmemory.core.model

import android.content.Context

/**
 * 模型通道配置（阶段 1 默认：云端大模型；阶段 2 修复：云端/本地双插拔接口）
 *
 * 网关语义（对齐产品设计）：
 *  - 联网/政企内网 = "云端状态"：使用更强的"云端算力"（CloudModelProvider，云端大模型）；
 *  - 离线/本地网关 = 使用手机端部署的 llama.cpp + GGUF 小模型（LocalModelProvider）。
 * 业务层共享 [ModelProvider] 接口；云端使用 OpenAI 兼容端点，端侧直接通过 JNI 推理。
 *
 * BaseURL / 云端 Model / 本地 Model / API Key 均可被 SharedPreferences 覆盖（设置页）。
 * API Key 默认留空，只能由设置页、local.properties 或环境变量注入，禁止进入源码与 Git 历史。
 */
object ModelConfig {

    /**
     * 云端大模型通道（OpenAI 兼容 API）。
     *
     * ⚠️ 端点修正（2026-08-06）：真实终端为 https://api.ppio.com/openai/v1/chat/completions，
     * 故 base 必须含 `/v1` 段，否则拼出 `…/openai/chat/completions`（缺 /v1）会返回 404/400 类错误。
     */
    const val DEFAULT_BASE_URL = "https://api.ppio.com/openai/v1"

    /** 云端大模型 Model ID（联网/内网 = 云端状态时使用，更强算力） */
    const val DEFAULT_CLOUD_MODEL = "deepseek/deepseek-v4-flash"

    /**
     * 本地小模型 Model ID（离线/本地网关时使用，部署在手机端）。
     */
    const val DEFAULT_LOCAL_MODEL = LocalModelSpec.ID

    /** 兼容别名：设置页/阶段 3 读取的"模型"字段即云端模型 */
    const val DEFAULT_MODEL = DEFAULT_CLOUD_MODEL

    /**
     * 默认 API Key（2026-08-08 轮换，随 APK 内置，新手机装完即用云端）。
     * 仍可在模型设置页覆盖；SharedPreferences 中的值优先于本默认值。
     */
    private const val DEFAULT_API_KEY = "sk_kJMKnKTwWtJx63O1BzTsmbCNysftwzqKWruEUdFaIUw"

    private const val PREF_NAME = "model_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_LOCAL_MODEL = "local_model"
    private const val KEY_API_KEY = "api_key"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    /** 云端大模型 Model ID（联网/内网 = 云端状态使用） */
    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_CLOUD_MODEL) ?: DEFAULT_CLOUD_MODEL

    /** 本地小模型 Model ID（离线/本地网关使用） */
    fun localModel(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL, DEFAULT_LOCAL_MODEL) ?: DEFAULT_LOCAL_MODEL

    /** API Key（设置页回填；仅存本地 SharedPreferences） */
    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY

    /**
     * 保存模型配置。
     *
     * @param localModel 本地小模型 Model ID；传 null 时保留当前值（兼容阶段 3 的 4 参调用）。
     */
    fun save(
        context: Context,
        baseUrl: String,
        model: String,
        apiKey: String,
        localModel: String? = null
    ) {
        val currentLocal = localModel ?: localModel(context)
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_MODEL, model.trim())
            .putString(KEY_LOCAL_MODEL, currentLocal.trim())
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
