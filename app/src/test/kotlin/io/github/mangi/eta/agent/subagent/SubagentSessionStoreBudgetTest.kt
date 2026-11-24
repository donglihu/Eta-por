package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理历史缓存的两层上限：单 agent 保留最近一段，全进程总量按 LRU 淘汰最旧且未在跑的历史。
 */
class SubagentSessionStoreBudgetTest {

    private val profile = SubagentProfile(
        name = "test-agent",
        summary = "测试用子代理",
        whenToUse = "仅测试",
        toolHighlights = emptyList(),
        roleInstruction = "测试",
    )

    @Test
    fun completedHistoryIsTrimmedToPerAgentBudget() {
        val key = "test-trim-${System.nanoTime()}"
        val entry = SubagentSessionStore.create(key, profile)
        val history = (1..40).map { index ->
            AgentModelClient.ConversationMessage(
                role = if (index % 2 == 0) "assistant" else "user",
                content = "m$index:" + "x".repeat(20_000),
            )
        }

        SubagentSessionStore.complete(key, entry.subagentId, history)

        val stored = SubagentSessionStore.get(key, entry.subagentId)!!.history
        assertTrue(stored.isNotEmpty())
        assertTrue(stored.size < history.size)
        assertTrue(stored.last().content.startsWith("m40:"))
        assertTrue(entry.historyChars <= SubagentSessionStore.MAX_HISTORY_CHARS_PER_AGENT)
    }

    @Test
    fun shortHistoryIsStoredUnchanged() {
        val key = "test-keep-${System.nanoTime()}"
        val entry = SubagentSessionStore.create(key, profile)
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "问题"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "回答"),
        )

        SubagentSessionStore.complete(key, entry.subagentId, history)

        assertEquals(history, SubagentSessionStore.get(key, entry.subagentId)!!.history)
    }

    @Test
    fun totalBudgetEvictsOldestIdleHistories() {
        val key = "test-total-${System.nanoTime()}"
        val entries = (1..14).map { SubagentSessionStore.create(key, profile) }
        val history = (1..12).map { index ->
            AgentModelClient.ConversationMessage(
                role = "user",
                content = "h$index:" + "y".repeat(20_000),
            )
        }

        entries.forEach { entry -> SubagentSessionStore.complete(key, entry.subagentId, history) }

        assertTrue(
            SubagentSessionStore.stats().totalHistoryChars <=
                SubagentSessionStore.MAX_TOTAL_HISTORY_CHARS,
        )
        // 刚写入的那条永远保留，被淘汰的只能是最旧的历史。
        val latest = SubagentSessionStore.get(key, entries.last().subagentId)!!
        assertTrue(latest.history.isNotEmpty())
        val oldest = SubagentSessionStore.get(key, entries.first().subagentId)!!
        assertTrue(oldest.history.isEmpty())
    }
}
