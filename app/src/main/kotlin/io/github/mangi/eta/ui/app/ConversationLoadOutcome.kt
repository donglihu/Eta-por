package io.github.mangi.eta.ui.app

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.toSafeLogToken
import io.github.mangi.eta.data.db.ChunkedTextFailureReason
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import kotlinx.coroutines.CancellationException

/**
 * 会话补载结果。
 *
 * 为什么必须用 sealed 而不是 `AgentChatHomeUiState?`：null 同时表示「会话不存在」和「恢复失败」，
 * 调用方只能 `?: return` 静默收场。于是
 * [io.github.mangi.eta.data.db.ChunkedTextDao.restoreTextTail] 在引用头损坏 / 分块缺失时抛的错
 * 被上游压成 null，用户点开会话「什么都没发生」、也没有一行日志（2026-11-26 审核 P1-4）。
 * 三种结果分开，调用方就必须各自给出不同反应。
 */
internal sealed interface ConversationLoadOutcome {
    /** 会话行存在，消息与上下文都读出来了。 */
    data class Loaded(val state: AgentChatHomeUiState) : ConversationLoadOutcome

    /** 会话行不存在（已被删除）。这不是失败，调用方静默跳过即可。 */
    data object Missing : ConversationLoadOutcome

    /** 读取失败：reason 是稳定原因码（可进日志与界面），detail 是完整诊断行。 */
    data class Failed(val reason: String, val detail: String) : ConversationLoadOutcome
}

/**
 * 补载失败的归类与上报。
 *
 * 纯逻辑、不碰 Android：归类与日志行都在这里生成，[AgentConversationStore] 只负责把 DAO 异常送进来。
 * 这样「失败必须可见」这条契约能被纯 JVM 单测覆盖，而不依赖 Robolectric
 * （arm64 上 Robolectric 会被 assumeTrue 静默跳过，被跳过＝零执行）。
 */
/** 补载失败时贴到输入框上方的结果卡标题；用户点开会话必须看到它，而不是「什么都没发生」。 */
internal const val CONVERSATION_LOAD_FAILED_TITLE = "会话读取失败"

/** 失败的用户可见说明：带上稳定原因码，便于用户报障时对上日志。 */
internal fun ConversationLoadOutcome.Failed.userMessage(): String =
    "$CONVERSATION_LOAD_FAILED_TITLE（$reason）：消息或上下文的分块数据读不出来，" +
        "原始数据保持原样、未被改写。可先导出备份，再重启应用重试。"

/**
 * 把一次补载包成三态结果。
 *
 * 这里是「不再吞异常」的唯一收口：只有 [CancellationException] 继续上抛（取消不是失败），
 * 其余异常一律变成 [ConversationLoadOutcome.Failed]，调用方没有 null 可以静默 return。
 */
internal suspend fun loadConversationOutcome(
    conversationId: String,
    phase: String,
    loader: suspend () -> AgentChatHomeUiState?,
): ConversationLoadOutcome =
    try {
        val state = loader()
        if (state == null) ConversationLoadOutcome.Missing else ConversationLoadOutcome.Loaded(state)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        ConversationLoadFailure.of(conversationId, phase, failure)
    }

internal object ConversationLoadFailure {

    fun of(conversationId: String, phase: String, failure: Throwable): ConversationLoadOutcome.Failed {
        val reason = ChunkedTextFailureReason.of(failure)
        val detail = "Conversation load failed: conversation=${conversationId.toSafeLogToken()} " +
            "phase=${phase.toSafeLogToken()} reason=$reason type=${failure.safeLogType()}"
        return ConversationLoadOutcome.Failed(reason = reason, detail = detail)
    }

    /**
     * 上报失败。注入了 sink（单测）走 sink，否则走应用日志。
     * 日志是旁路设施：它自己失败也绝不能让上报路径再炸一次，所以整体包 runCatching。
     */
    fun emit(failed: ConversationLoadOutcome.Failed, sink: ((String) -> Unit)?) {
        if (sink != null) {
            runCatching { sink(failed.detail) }
            return
        }
        runCatching { AndroidAgentLogger.error(failed.detail) }
    }
}
