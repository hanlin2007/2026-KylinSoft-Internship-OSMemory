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
 * - 端点自动适配：{base}/chat/completions，网关报 400/404/405 时自动重试 {base}/v1/chat/completions
 * - 30s 连接 / 60s 读取超时
 * - 回复经 JsonTools 健壮提取（容忍 markdown fence / 截断）
 */
class CloudModelProvider(context: Context) : ModelProvider {

    private val baseUrl = ModelConfig.baseUrl(context)
    private val model = ModelConfig.model(context)
    private val apiKey = ModelConfig.apiKey(context)

    override val name = "云端大模型（OpenAI 兼容 · $model）"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(
        system: String,
        user: String,
        temperature: Double
    ): String = withContext(Dispatchers.IO) {
        val endpoint = ModelConfig.chatCompletionsEndpoint(baseUrl)
        val fallback = ModelConfig.v1FallbackEndpoint(baseUrl)
        try {
            doComplete(endpoint, system, user, temperature)
        } catch (e: HttpModelException) {
            if (fallback != null && (e.code == 400 || e.code == 404 || e.code == 405)) {
                doComplete(fallback, system, user, temperature)
            } else {
                throw e
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
            throw ModelException("网络请求失败：${e.message}", e)
        }

        val bodyText = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw HttpModelException(
                response.code,
                "模型网关返回 ${response.code}：${bodyText.take(300)}"
            )
        }
        val reply = try {
            val json = JSONObject(bodyText)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            throw ModelException("响应解析失败：${e.message}", e)
        }
        if (reply.isBlank()) throw ModelException("模型返回空内容")
        // 尽量抽取完整 JSON 块返回给业务层
        return JsonTools.extractBalancedJson(reply) ?: reply
    }
}
