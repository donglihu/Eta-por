package io.github.mangi.eta.agent.runtime

import android.os.SystemClock
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.UNKNOWN_LOG_TOKEN
import io.github.mangi.eta.core.toSafeLogToken
import org.json.JSONObject

/**
 * 记录首请求关键边界，区分本地准备、网络握手和模型首 Token 延迟。
 *
 * 2026-11-22 增加 [EtaTrace] 落盘打点：原来的 `logger.debug` 在 release 被 R8 整条删除，
 * 线上只能看到 run.start / run.end 两条，卡在哪一段无法判断。现在同一边界同时写：
 * - `perf.provider.request`：本轮请求发出（含距 run 开始、距上一轮结束的间隔）
 * - `perf.provider.headers`：收到响应头（= 排队 + 握手 + 服务商首字节）
 * - `perf.provider.first_delta`：首段正文/思考到达（= 真 TTFT）
 * - `perf.provider.done`：本轮结束（含正文长度、思考长度、工具数）
 * - `perf.tool.started` / `perf.tool.finished`：每次工具执行的起止与实际耗时
 * 并额外提供 [runStartElapsed]，供工具批次计算「间隔里到底花在哪」。
 */
internal class AgentRunTiming(
    private val logger: AgentLogger,
) {
    private val runStartedAt = SystemClock.elapsedRealtime()
    private val requestStartedAt = mutableMapOf<Int, Long>()
    private val responseStartedAt = mutableMapOf<Int, Long>()
    private val firstDeltaRounds = mutableSetOf<Int>()
    private val lastRoundFinishedAt = mutableMapOf<Int, Long>()
    private val toolStartedAt = mutableMapOf<String, Long>()

    /** run 开始的单调时钟，供同 run 内其它组件对齐口径。 */
    val runStartElapsed: Long get() = runStartedAt

    fun preparationFinished(skillCount: Int) {
        AgentPerfTrace.trace(
            "perf.prepare.done",
            "ms=${elapsedSince(runStartedAt)} skills=$skillCount",
        )
        logger.debug {
            "Agent runtime preparation finished: elapsed_ms=${elapsedSince(runStartedAt)}, " +
                "skills=$skillCount"
        }
    }

    /** 准备阶段的细分节点：每次调用都写一行，用来看准备阶段慢在哪一步。 */
    fun stage(name: String, mark: Long) {
        AgentPerfTrace.trace(
            "perf.prepare.$name",
            "ms=${SystemClock.elapsedRealtime() - mark} total_ms=${elapsedSince(runStartedAt)}",
        )
    }

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.ProviderRequestStarted -> onRequestStarted(event)
            is AgentEvent.ProviderResponseStarted -> onResponseHeaders(event)
            is AgentEvent.AssistantBlockDelta -> onDelta(event)
            is AgentEvent.AssistantReceived -> onAssistantReceived(event)
            is AgentEvent.ToolStarted -> onToolStarted(event)
            is AgentEvent.ToolFinished -> onToolFinished(event)
            is AgentEvent.ModelRetryScheduled -> onRetryScheduled(event)
            is AgentEvent.RoundStarted -> onRoundStarted(event)
            else -> Unit
        }
    }

    private fun onRequestStarted(event: AgentEvent.ProviderRequestStarted) {
        requestStartedAt[event.round] = SystemClock.elapsedRealtime()
        val gap = lastRoundFinishedAt[event.round - 1]
            ?.let { SystemClock.elapsedRealtime() - it }
        AgentPerfTrace.trace(
            "perf.provider.request",
            "round=${event.round} run_ms=${elapsedSince(runStartedAt)}" +
                (gap?.let { " since_prev_round_ms=$it" } ?: ""),
        )
        logger.debug {
            "Agent provider request started: round=${event.round}, " +
                "run_elapsed_ms=${elapsedSince(runStartedAt)}"
        }
    }

    private fun onResponseHeaders(event: AgentEvent.ProviderResponseStarted) {
        responseStartedAt[event.round] = SystemClock.elapsedRealtime()
        AgentPerfTrace.trace(
            "perf.provider.headers",
            "round=${event.round} http=${event.httpCode} " +
                "request_ms=${elapsedSince(requestStartedAt[event.round])}",
        )
        logger.debug {
            "Agent provider response headers received: round=${event.round}, " +
                "request_elapsed_ms=${elapsedSince(requestStartedAt[event.round])}"
        }
    }

    private fun onDelta(event: AgentEvent.AssistantBlockDelta) {
        if (!firstDeltaRounds.add(event.round)) return
        AgentPerfTrace.trace(
            "perf.provider.first_delta",
            "round=${event.round} ttft_ms=${elapsedSince(requestStartedAt[event.round])} " +
                "headers_ms=${elapsedSince(responseStartedAt[event.round])}",
        )
        logger.debug {
            "Agent provider first delta received: round=${event.round}, " +
                "request_elapsed_ms=${elapsedSince(requestStartedAt[event.round])}, " +
                "headers_elapsed_ms=${elapsedSince(responseStartedAt[event.round])}"
        }
    }

    private fun onAssistantReceived(event: AgentEvent.AssistantReceived) {
        lastRoundFinishedAt[event.round] = SystemClock.elapsedRealtime()
        AgentPerfTrace.trace(
            "perf.provider.done",
            "round=${event.round} request_ms=${elapsedSince(requestStartedAt[event.round])} " +
                "content=${event.contentChars} reasoning=${event.reasoningContent.length} " +
                "tools=${event.toolNames.size}",
        )
    }

    private fun onToolStarted(event: AgentEvent.ToolStarted) {
        toolStartedAt[event.toolCallId] = SystemClock.elapsedRealtime()
        AgentPerfTrace.trace(
            "perf.tool.started",
            "round=${event.round} name=${event.name} id=${event.toolCallId.take(8)}",
        )
    }

    private fun onToolFinished(event: AgentEvent.ToolFinished) {
        val spent = toolStartedAt.remove(event.toolCallId)
            ?.let { SystemClock.elapsedRealtime() - it }
        AgentPerfTrace.trace(
            "perf.tool.finished",
            buildString {
                append("round=").append(event.round)
                append(" name=").append(event.name)
                append(" ms=").append(spent ?: -1)
                append(" ok=").append(event.success != false)
                append(" images=").append(event.imageCount)
                // 失败必须留下原因码：线上只有 `ok=false` 时无法区分失败类别。
                toolFailureLogCode(event.resultSummary, event.success)?.let { code ->
                    append(" code=").append(code)
                }
            },
        )
    }

    private fun onRetryScheduled(event: AgentEvent.ModelRetryScheduled) {
        AgentPerfTrace.trace(
            "perf.provider.retry",
            "round=${event.round} attempt=${event.attempt}/${event.maxAttempts} " +
                "delay_ms=${event.delayMs} reason=${event.reasonCode}",
        )
    }

    private fun onRoundStarted(event: AgentEvent.RoundStarted) {
        val gap = lastRoundFinishedAt[event.round - 1]
            ?.let { SystemClock.elapsedRealtime() - it } ?: -1L
        AgentPerfTrace.trace(
            "perf.round.started",
            "round=${event.round} messages=${event.messageCount} since_prev_round_ms=$gap",
        )
    }

    private fun elapsedSince(startedAt: Long?): Long =
        startedAt?.let { SystemClock.elapsedRealtime() - it } ?: -1L
}

/** 失败码上限：只留可检索的短标识，长内容一律截断。 */
private const val MAX_TOOL_FAILURE_CODE_CHARS = 40

/** 摘要超过该长度不再尝试 JSON 解析，避免失败路径上做无谓的全量解析。 */
private const val MAX_RESULT_JSON_SCAN_CHARS = 4_096

/** 取到了码但过不了安全闸：与「根本没取到码」区分开，便于判断工具是否吐了脏码。 */
private const val UNSANITIZED_FAILURE_CODE = "unsanitized"

/**
 * 工具失败行补的安全短码。
 *
 * - 成功：返回 null，不写 code 字段；
 * - 失败且解析不到码：返回空串，日志落成 `code=`；
 * - 失败且取到码但过不了字符白名单：返回 `unsanitized`，日志落成 `code=unsanitized`。
 *
 * 只取 `code`/`error` 字段值，摘要原文（可能含手机内容、命令输出）绝不进入日志。
 */
internal fun toolFailureLogCode(resultSummary: String, success: Boolean?): String? {
    if (success != false) return null
    val code = resultSummary.extractResultCode()
        ?: resultSummary.extractJsonFailureCode()
        ?: return ""
    val bounded = code.take(MAX_TOOL_FAILURE_CODE_CHARS)
    if (bounded.isEmpty()) return ""
    return bounded.toSafeLogToken().takeUnless { it == UNKNOWN_LOG_TOKEN } ?: UNSANITIZED_FAILURE_CODE
}

/** 摘要是完整 JSON 时再取一次 code/error；不合法或过长直接放弃并返回 null。 */
private fun String.extractJsonFailureCode(): String? {
    if (length > MAX_RESULT_JSON_SCAN_CHARS || !startsWith("{")) return null
    val json = try {
        JSONObject(this)
    } catch (_: org.json.JSONException) {
        return null
    }
    json.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }?.let { return it }
    json.optString("code").takeIf { it.isNotBlank() }?.let { return it }
    return json.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
}
