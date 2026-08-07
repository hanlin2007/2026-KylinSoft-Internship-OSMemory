package com.example.osmemory.core.model

import android.content.Context
import com.example.osmemory.core.model.JsonTools.extractBalancedJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 云端大模型通道：OpenAI 兼容 Chat Completions（阶段 1 默认通道）
 *
 * - 端点自动适配：{base}/chat/completions；当 base 不含 /v1 时，网关返回任意非 2xx 自动重试 {base}/v1/chat/completions
 *   （真实终端 https://api.ppio.com/openai/v1/chat/completions，base 已默认含 /v1，见 [ModelConfig]）
 * - 30s 连接 / 60s 读取超时
 * - 每次调用上报 [ModelDiagnostics]，失败时带精确原因（网络异常 / HTTP 状态码+响应体 / 解析失败 / 超时）
 * - 回复经 JsonTools 健壮提取（容忍 markdown fence / 截断）
 */
class CloudModelProvider(context: Context) : ModelProvider {

    private val baseUrl = ModelConfig.baseUrl(context)
    private val model = ModelConfig.model(context)
    private val apiKey = ModelConfig.apiKey(context)

    override val name = "云端大模型（OpenAI 兼容 · $model）"

    override suspend fun complete(
        system: String,
        user: String,
        temperature: Double
    ): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val endpoint = ModelConfig.chatCompletionsEndpoint(baseUrl)
        val fallback = ModelConfig.v1FallbackEndpoint(baseUrl)

        // 先尝试主端点
        val firstAttempt = runCatching { doComplete(endpoint, system, user, temperature) }
        if (firstAttempt.isSuccess) {
            val duration = System.currentTimeMillis() - start
            ModelDiagnostics.success(name, duration)
            firstAttempt.getOrThrow()
        } else {
            val firstError = firstAttempt.exceptionOrNull()
            // base 不含 /v1 时，任意非 2xx 都再试一次 /v1 变体（修复缺 /v1 导致的 404/400 类误报）
            if (fallback != null && firstError is HttpModelException) {
                val secondAttempt = runCatching { doComplete(fallback, system, user, temperature) }
                if (secondAttempt.isSuccess) {
                    val duration = System.currentTimeMillis() - start
                    ModelDiagnostics.success(name, duration)
                    secondAttempt.getOrThrow()
                } else {
                    val duration = System.currentTimeMillis() - start
                    ModelDiagnostics.failure(name, secondAttempt.exceptionOrNull()?.friendlyMessage().orEmpty(), duration)
                    throw secondAttempt.exceptionOrNull() ?: firstError
                }
            } else {
                val duration = System.currentTimeMillis() - start
                ModelDiagnostics.failure(name, firstError?.friendlyMessage() ?: "未知错误", duration)
                throw firstError ?: ModelException("未知错误")
            }
        }
    }

    private fun doComplete(endpoint: String, system: String, user: String, temperature: Double): String {
        val bodyJson = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", 2048)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ModelException("网络请求失败（${e.javaClass.simpleName}）: ${e.message}", e)
        }

        val bodyText = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            // 明确标注端点，便于判断是不是缺 /v1 或鉴权失败
            throw HttpModelException(
                response.code,
                "模型网关 ${endpoint} 返回 HTTP ${response.code}：${bodyText.take(300)}"
            )
        }
        val reply = try {
            val json = JSONObject(bodyText)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            throw ModelException("响应解析失败（${e.javaClass.simpleName}）: ${e.message}", e)
        }
        if (reply.isBlank()) throw ModelException("模型返回空内容")
        // 尽量抽取完整 JSON 块返回给业务层
        return JsonTools.extractBalancedJson(reply) ?: reply
    }

    /** 把异常归一为可读的降级原因（供日志 / 诊断展示） */
    private fun Throwable.friendlyMessage(): String = when (this) {
        is HttpModelException -> "模型网关返回 HTTP $code：${message ?: ""}".take(400)
        else -> message ?: javaClass.simpleName
    }

    companion object {
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        /** OkHttpClient 复用（进程级），避免每次 collect 都新建连接池 */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
