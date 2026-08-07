package com.example.osmemory.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 模型通道诊断（阶段 1 修复）：记录最近一次模型调用的成败与原因。
 *
 * 目的：把"模型为什么降级"从被隐藏的日志 extra 提升为控制台可见的状态，
 * 让用户断网/换 key 时能立刻看到具体原因（网络断开 / HTTP 状态码 / 解析失败 / 超时）。
 */
object ModelDiagnostics {

    /** 一次模型调用结果 */
    data class CallRecord(
        val channel: String,          // 通道名（含模型）
        val ok: Boolean,
        val message: String,          // 成功: "调用成功"；失败: 具体原因
        val at: Long,                 // 调用时间
        val durationMs: Long
    ) {
        val degraded: Boolean get() = !ok
    }

    private val _lastCall = MutableStateFlow<CallRecord?>(null)

    /** 最近一次模型调用结果（null = 尚未调用） */
    val lastCall: StateFlow<CallRecord?> = _lastCall

    fun success(channel: String, durationMs: Long) {
        _lastCall.value = CallRecord(channel, ok = true, message = "调用成功", at = now(), durationMs = durationMs)
    }

    fun failure(channel: String, message: String, durationMs: Long) {
        _lastCall.value = CallRecord(channel, ok = false, message = message, at = now(), durationMs = durationMs)
    }

    fun reset() {
        _lastCall.value = null
    }

    private fun now(): Long = System.currentTimeMillis()
}
