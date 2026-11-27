package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-4：restoreTextTail 抛错被上游 `runCatching { }.getOrNull()` 吞成 null，用户点开会话毫无反应。
 *
 * 纯 JVM（无 Robolectric runner、无 @Config）：arm64 上 Robolectric 会被 assumeTrue 静默跳过，
 * 被跳过＝零执行，这条契约必须在 skip=0 的普通 JUnit 里跑。
 */
class ConversationLoadFailureTest {

    private fun emptyState() = AgentChatHomeUiState(
        messages = emptyList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    @Test
    fun restoreTextTailFailureBecomesVisibleFailureInsteadOfNull() = runBlocking {
        // 与 ChunkedTextDao.restoreTextTail 在分块缺失时抛出的异常完全一致（check(page.isNotEmpty())）。
        val thrown = IllegalStateException("History payload chunk missing")
        val outcome = loadConversationOutcome("conv-broken", "ensure") { throw thrown }

        assertTrue("失败必须变成 Failed，不能是 null 静默返回", outcome is ConversationLoadOutcome.Failed)
        val failed = outcome as ConversationLoadOutcome.Failed
        assertEquals("chunk_missing", failed.reason)
        // 用户可见文案必须说明失败，而不是留白。
        val message = failed.userMessage()
        assertTrue(message, message.contains(CONVERSATION_LOAD_FAILED_TITLE))
        assertTrue(message, message.contains("chunk_missing"))
        // 诊断行必须带会话 id 与原因，便于从用户报障反查日志。
        assertTrue(failed.detail, failed.detail.contains("conversation=conv-broken"))
        assertTrue(failed.detail, failed.detail.contains("reason=chunk_missing"))
        assertTrue(failed.detail, failed.detail.contains("phase=ensure"))
    }

    @Test
    fun failureIsLoggedThroughInjectedSink() = runBlocking {
        val lines = mutableListOf<String>()
        val outcome = loadConversationOutcome("conv-broken", "await") {
            // 引用头损坏：restoreTextTail 走 parseReference() ?: error("Invalid history payload reference")。
            error("Invalid history payload reference")
        }
        val failed = outcome as ConversationLoadOutcome.Failed
        assertEquals("reference_invalid", failed.reason)

        // 注入 sink（与 EtaDatabase.quarantineLogSink 同模式）：纯 JVM 里 android.util.Log 是 stub。
        ConversationLoadFailure.emit(failed, lines::add)
        assertEquals("失败必须留痕，且只留一条", 1, lines.size)
        assertTrue(lines.single(), lines.single().contains("conversation=conv-broken"))
        assertTrue(lines.single(), lines.single().contains("reason=reference_invalid"))
        assertTrue(lines.single(), lines.single().contains("phase=await"))
    }

    @Test
    fun missingConversationStaysDistinctFromFailure() = runBlocking {
        // 会话行不存在不是失败：调用方要能区分「没有内容」与「恢复失败」。
        val outcome = loadConversationOutcome("conv-gone", "ensure") { null }
        assertEquals(ConversationLoadOutcome.Missing, outcome)
        assertFalse(outcome is ConversationLoadOutcome.Failed)
    }

    @Test
    fun loadedConversationIsNeitherFailureNorMissing() = runBlocking {
        val state = emptyState()
        val outcome = loadConversationOutcome("conv-ok", "ensure") { state }
        assertTrue(outcome is ConversationLoadOutcome.Loaded)
        assertEquals(state, (outcome as ConversationLoadOutcome.Loaded).state)
    }

    @Test
    fun failureReasonCodesAreStablePerCause() {
        // 每条 check/error 文案都要落到自己的稳定原因码，日志才能按原因检索。
        assertEquals(
            "chunk_missing",
            ConversationLoadFailure.of("c", "p", IllegalStateException("History payload chunk missing")).reason,
        )
        assertEquals(
            "chunk_out_of_order",
            ConversationLoadFailure.of("c", "p", IllegalStateException("History payload chunk out of order")).reason,
        )
        assertEquals(
            "length_mismatch",
            ConversationLoadFailure.of("c", "p", IllegalStateException("History payload length mismatch")).reason,
        )
        assertEquals(
            "size_invalid",
            ConversationLoadFailure.of("c", "p", IllegalStateException("Invalid history payload size")).reason,
        )
        // 未知原因也要可辨识，不能退化成空串或 null。
        assertTrue(
            ConversationLoadFailure.of("c", "p", IllegalStateException("boom")).reason
                .startsWith("unknown_"),
        )
    }
}
