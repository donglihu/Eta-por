package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话内 Subagent 实例上下文缓存，供主 Agent 通过 `resume` 续接已有的 Subagent。
 *
 * 这里存的是**进程生命周期**内的历史，早期没有任何体积闸门：16 个会话 × 16 个 agent ×
 * 各自几十轮工具输出，实测足以把 Java 堆推到 500MB 量级（2026-11-22 OOM 的并发侧放大器）。
 * 现在两层上限：
 * - 单个 agent 历史按字符预算保留最近一段（[MAX_HISTORY_CHARS_PER_AGENT]）；
 * - 全进程历史总字符超预算时，按 LRU 把最旧且未在跑的子代理历史清空（[MAX_TOTAL_HISTORY_CHARS]）。
 * 淘汰不做静默：通过 [onEvicted] 回调交给运行时写日志。
 */
internal object SubagentSessionStore {
    private const val MAX_CONVERSATIONS = 16
    private const val MAX_AGENTS_PER_CONVERSATION = 16
    private const val MAX_HISTORY_MESSAGES_PER_AGENT = 64

    /** 单个子代理历史保留的字符预算：`resume` 只需要最近一段上下文。 */
    const val MAX_HISTORY_CHARS_PER_AGENT = 240_000

    /** 全进程子代理历史的总字符预算（UTF-16 下约 4MB 上界之外再多也算超）。 */
    const val MAX_TOTAL_HISTORY_CHARS = 2_000_000

    /** 淘汰回调：运行时接到 AndroidAgentLogger，保证「裁掉多少」在日志里可见。 */
    @Volatile
    var onEvicted: ((String) -> Unit)? = null

    class Entry(
        val subagentId: String,
        val profile: SubagentProfile,
        @Volatile var history: List<AgentModelClient.ConversationMessage> = emptyList(),
        val running: AtomicBoolean = AtomicBoolean(false),
    ) {
        @Volatile
        var historyChars: Int = 0
            internal set
    }

    data class Stats(
        val entryCount: Int,
        val totalHistoryChars: Int,
    )

    private class ConversationBucket {
        val sequence = AtomicInteger(0)
        val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
                size > MAX_AGENTS_PER_CONVERSATION && eldest?.value?.running?.get() != true
        }
    }

    private val lock = Any()
    private val buckets = object : LinkedHashMap<String, ConversationBucket>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ConversationBucket>?,
        ): Boolean = size > MAX_CONVERSATIONS
    }

    fun create(conversationKey: String, profile: SubagentProfile): Entry = synchronized(lock) {
        val bucket = buckets.getOrPut(conversationKey) { ConversationBucket() }
        val id = "sub_${bucket.sequence.incrementAndGet()}"
        val entry = Entry(subagentId = id, profile = profile, running = AtomicBoolean(true))
        bucket.entries[id] = entry
        entry
    }

    fun get(conversationKey: String, subagentId: String): Entry? = synchronized(lock) {
        buckets[conversationKey]?.entries?.get(subagentId)
    }

    fun complete(
        conversationKey: String,
        subagentId: String,
        updatedHistory: List<AgentModelClient.ConversationMessage>,
    ) {
        val trimmed = trimHistory(updatedHistory)
        val dropped = updatedHistory.size - trimmed.size
        synchronized(lock) {
            val entry = buckets[conversationKey]?.entries?.get(subagentId) ?: return
            entry.history = trimmed
            entry.historyChars = trimmed.historyChars()
            entry.running.set(false)
            if (dropped > 0) {
                notifyEvicted(
                    "subagent.history.trimmed conversation=$conversationKey subagent=$subagentId " +
                        "droppedMessages=$dropped keptChars=${entry.historyChars}",
                )
            }
            enforceTotalBudgetLocked(keepEntry = entry)
        }
    }

    fun releaseRunning(conversationKey: String, subagentId: String) {
        synchronized(lock) {
            buckets[conversationKey]?.entries?.get(subagentId)?.running?.set(false)
        }
    }

    /** 当前缓存规模，供测试与诊断页读取。 */
    fun stats(): Stats = synchronized(lock) {
        var entries = 0
        var chars = 0
        buckets.values.forEach { bucket ->
            bucket.entries.values.forEach { entry ->
                entries += 1
                chars += entry.historyChars
            }
        }
        Stats(entryCount = entries, totalHistoryChars = chars)
    }

    /** 保留最近的整段消息直到字符预算用尽；至少留最后一条。 */
    private fun trimHistory(
        history: List<AgentModelClient.ConversationMessage>,
    ): List<AgentModelClient.ConversationMessage> {
        if (history.isEmpty()) return history
        val total = history.historyChars()
        if (total <= MAX_HISTORY_CHARS_PER_AGENT) return history
        var kept = 0
        var start = history.size
        for (index in history.indices.reversed()) {
            val chars = history[index].footprintChars()
            if (kept + chars > MAX_HISTORY_CHARS_PER_AGENT && start < history.size) break
            kept += chars
            start = index
        }
        return history.subList(start, history.size).toList()
    }

    /**
     * 总量闸门：按 LRU 顺序清空最旧且未在运行的子代理历史。
     * 保留 [keepEntry]（刚写入的那条）避免「写进去立刻被自己挤掉」。
     */
    private fun enforceTotalBudgetLocked(keepEntry: Entry) {
        var total = stats().totalHistoryChars
        if (total <= MAX_TOTAL_HISTORY_CHARS) return
        for (bucket in buckets.values.toList()) {
            for (entry in bucket.entries.values.toList()) {
                if (total <= MAX_TOTAL_HISTORY_CHARS) return
                if (entry === keepEntry) continue
                if (entry.running.get()) continue
                val released = entry.historyChars
                if (released == 0) continue
                entry.history = emptyList()
                entry.historyChars = 0
                total -= released
                notifyEvicted(
                    "subagent.history.evicted subagent=${entry.subagentId} " +
                        "releasedChars=$released remainingChars=$total",
                )
            }
        }
    }

    private fun List<AgentModelClient.ConversationMessage>.historyChars(): Int =
        sumOf { it.footprintChars() }

    private fun AgentModelClient.ConversationMessage.footprintChars(): Int =
        content.length + contentJson.length + reasoningContent.length + toolCallsJson.length + role.length

    private fun notifyEvicted(message: String) {
        onEvicted?.invoke(message)

    fun clearConversation(conversationKey: String) {
        if (conversationKey.isBlank()) return
        synchronized(lock) {
            buckets.remove(conversationKey)
        }
    }
    }
}
