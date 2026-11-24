package io.github.mangi.eta.agent.model

import androidx.compose.runtime.Immutable

/**
 * 模型测速结果，对齐 miniAI 的 `ModelTestResult`：
 * success / latencyMs / 请求响应详情 / timings（五段耗时）/ replyText。
 */
@Immutable
data class ModelTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String = "",
    val requestUrl: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val responseCode: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String = "",
    val errorDetail: String = "",
    val timings: NetworkPhaseTimings = NetworkPhaseTimings(),
    val replyText: String = "",
)

/**
 * 网络阶段耗时，对齐 miniAI 的 `NetworkPhaseTimings`：
 * connectMs / sendMs / processMs / downloadMs / totalMs / connectionReused。
 */
@Immutable
data class NetworkPhaseTimings(
    val connectMs: Long = 0L,
    val sendMs: Long = 0L,
    val processMs: Long = 0L,
    val downloadMs: Long = 0L,
    val totalMs: Long = 0L,
    val connectionReused: Boolean = false,
)

/**
 * 单个模型的测速状态，按模型挂在模型列表每一行上。
 *
 * miniAI 的测速结果直接回写在模型行里（绿勾 + 耗时 / 红色感叹号），
 * 不再弹窗打断选择，所以这里保留「进行中」与「已出结果」两种状态。
 */
@Immutable
data class ModelTestState(
    val running: Boolean = false,
    val result: ModelTestResult? = null,
) {
    /** 成功且有结果时才给出耗时，失败不给假数字。 */
    val successLatencyMs: Long?
        get() = result?.takeIf { it.success }?.let { if (it.latencyMs > 0) it.latencyMs else it.timings.totalMs }

    val failed: Boolean get() = result != null && !result.success
}

/** 失败详情弹窗需要的内容：哪个模型 + 这次测速的完整结果。 */
@Immutable
data class ModelTestDetail(
    val modelName: String,
    val result: ModelTestResult,
)
