package io.github.mangi.eta.ui.app

/**
 * 运行中的 run 与它所属会话的双向登记表。
 *
 * 一张表维护两个方向：事件回灌要 `runId → 会话`，用户手势（暂停／停止／插话）要
 * `会话 → runId`。以前只在局部变量里存了单向 `runId → 会话`，反向查询靠遍历，
 * 停 run、插话这类操作就退化成「取最近一次启动的 run」——多会话并行时必然串台，
 * 用户在 A 会话插的话会被投进 B 会话的运行里。
 *
 * 只有主线程（[AgentAppState] 的 UI 状态机）读写，因此不加锁；跨线程调用必须先回主线程。
 */
internal class ConversationRunRegistry {
    private val runToConversation = LinkedHashMap<String, String>()

    /** 当前并行的 run 数量，用来做并发闸门。 */
    val size: Int
        get() = runToConversation.size

    /** 登记一个 run；同一会话允许同时登记多个 run（改写、压缩与主对话互不干扰）。 */
    fun bind(runId: String, conversationId: String) {
        runToConversation[runId] = conversationId
    }

    /** run 归属的会话；未知 run 返回 null，调用方不得猜。 */
    fun conversationIdOf(runId: String): String? = runToConversation[runId]

    /**
     * 会话正在跑的 run。
     *
     * 同一会话理论上只跑一个主 run；万一并存（例如改写回复紧跟一条新消息），
     * 取**最后登记**的那个，与「最近一次派发」语义一致。
     */
    fun runIdOf(conversationId: String?): String? {
        if (conversationId == null) return null
        return runToConversation.entries.lastOrNull { it.value == conversationId }?.key
    }

    /** run 结束后注销，返回它归属的会话，供调用方继续处理队列与摘要。 */
    fun release(runId: String): String? = runToConversation.remove(runId)

    fun containsRun(runId: String): Boolean = runToConversation.containsKey(runId)

    /** 当前所有在跑 run 的快照，用于恢复对账。 */
    fun runIds(): Set<String> = runToConversation.keys.toSet()

    /** 某会话是否还有 run 在跑；队列派发按它判据，而不是全局「当前 run」。 */
    fun isConversationRunning(conversationId: String): Boolean =
        runToConversation.containsValue(conversationId)
}
