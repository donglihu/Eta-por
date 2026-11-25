package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 请求侧的确定性裁剪：只在这份要发给模型的历史上丢掉最老的若干「轮次」。
 * - 不调模型、不落库、不改界面；数据库与界面里的消息一条都不会少。
 * - 只在知道模型上下文窗口时才裁，且至少保留最近 [MIN_KEEP_ROUNDS] 轮。
 * - 以「用户轮次」为边界整轮丢，避免把工具调用与结果拆散。
 *
 * 三个成员的职责分工（互不替代）：
 * - [costOf]：单条 [AgentModelClient.ConversationMessage] 的传输成本估算；纯字段长度累加，
 *   不为单条消息物化任何字符串。
 * - [trimForTransfer]：**传输预算**裁剪。只保证跨进程 Binder / 落盘载荷不超过体积上限，
 *   与模型上下文窗口无关；调用方在把历史交给 `AgentWireText` 之前先裁一次，避免把几 MB 的
 *   完整会话编码成字符串再序列化（入口进程 OOM 的直接来源）。
 * - [trimInPlace]：**上下文窗口预算**裁剪。接收侧拿到组装好的 `messages` 后按模型窗口再裁一次，
 *   这一步才是决定模型能看见多少上下文的权威逻辑。
 */
internal object AgentHistoryTrimmer {
    /** 无论预算多紧，都至少保留最近这么多轮。 */
    const val MIN_KEEP_ROUNDS = 10

    /** 上下文里留给历史的余量比例：其余位置给系统提示、记忆、工具 schema 与当前输入。 */
    private const val HISTORY_SHARE = 0.55

    /**
     * 单条消息的固定字段开销（role 建名、字段分隔符、JSON 括号等）。
     * 只需要量级相近，不追求与真实 wire 长度等值。
     */
    private const val ROLE_FIELD_OVERHEAD = 16

    /** 粗略 token 估算：CJK 按 1 字 1 token，其余字符按 4 字符 1 token。 */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code >= 0x2E80) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /**
     * 单条消息的估算：按它的 JSON 体积折算，含 content / tool_calls / 模型思考。
     *
     * 早期实现是 `estimateTokens(message.toString())`，一次裁剪就要为**每条历史消息**
     * 物化一整份 JSON 字符串（还要在「算总量」与「算被丢轮次」两趟里各来一遍）。
     * 一条几万字符的工具结果 × 数百条历史 × 6 个并发 run 就是堆爆的直接来源，
     * 因此改成不分配字符串的逐字段字符计数：[estimateJsonTokens]。
     */
    private fun estimateMessageTokens(message: JSONObject?): Int =
        if (message == null) 0 else estimateJsonTokens(message)

    /**
     * 不物化字符串的 JSON 字符数估算：递归累加键名与值，数字/布尔按字面长度折算。
     * 只用于 token 预算判定，不需要与 `toString()` 完全等长（键名分隔符等固定开销可忽略）。
     */
    fun estimateJsonTokens(value: Any?): Int = when (value) {
        null, JSONObject.NULL -> 0
        is String -> estimateTokens(value)
        is JSONObject -> {
            var total = 0
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                total += key.length
                total += estimateJsonTokens(value.opt(key))
            }
            total
        }
        is JSONArray -> {
            var total = 0
            for (index in 0 until value.length()) total += estimateJsonTokens(value.opt(index))
            total
        }
        else -> estimateTokens(value.toString())
    }

    data class Outcome(
        val omittedMessages: Int = 0,
        val omittedUserRounds: Int = 0,
    )

    /**
     * 就地裁剪 [messages] 中 [systemCount, messages.length()-1) 之间的历史。
     * 最后一条是当前用户输入，永不裁；索引小于 [systemCount] 的系统消息永不裁。
     */
    fun trimInPlace(
        messages: JSONArray,
        systemCount: Int,
        contextWindow: Int?,
        reservedTokens: Int = 0,
        minKeepRounds: Int = MIN_KEEP_ROUNDS,
    ): Outcome {
        val window = contextWindow?.takeIf { it > 0 } ?: return Outcome()
        val historyEnd = messages.length() - 1
        if (historyEnd <= systemCount) return Outcome()
        val budget = (window * HISTORY_SHARE).toInt() - reservedTokens
        if (budget <= 0) return Outcome()

        val roundStarts = (systemCount until historyEnd)
            .filter { isUserTurn(messages.optJSONObject(it)) }
        if (roundStarts.size <= minKeepRounds) return Outcome()

        var used = 0
        for (index in systemCount until historyEnd) used += estimateMessageTokens(messages.optJSONObject(index))

        var droppedRounds = 0
        var dropCount = 0
        while (roundStarts.size - droppedRounds > minKeepRounds && used > budget) {
            val roundEnd = roundStarts.getOrNull(droppedRounds + 1) ?: break
            var dropped = 0
            for (index in systemCount + dropCount until roundEnd) {
                dropped += estimateMessageTokens(messages.optJSONObject(index))
            }
            dropCount = roundEnd - systemCount
            droppedRounds++
            used -= dropped
        }
        if (dropCount == 0) return Outcome()
        repeat(dropCount) { messages.remove(systemCount) }
        return Outcome(omittedMessages = dropCount, omittedUserRounds = droppedRounds)
    }

    private fun isUserTurn(message: JSONObject?): Boolean =
        message != null && message.optString("role") == "user" && !message.has("_eta_observation")

    /**
     * 单条消息的传输成本估算：按字段长度累加折算，口径与 [estimateTokens] 一致（CJK 1 字 1 token，
     * 其余 4 字符 1 token）。
     *
     * 这里刻意**不**复用 [estimateJsonTokens]：那条路要先有 `JSONObject`，而传输侧只有
     * [AgentModelClient.ConversationMessage] 的原始字符串字段。直接对字段 `length` 累加，
     * 既不需要为整条消息物化 `toString()`，也不需要解析 `toolCallsJson`，
     * 因此 [trimForTransfer] 的预算判定在长会话上仍是 O(总字符数)，而不是 O(n²)。
     *
     * 键名、分隔符这类固定开销忽略不计：本函数只服务于「量级判定」，不追求与真实 wire 长度等值。
     */
    fun costOf(message: AgentModelClient.ConversationMessage): Int {
        var total = estimateTokens(message.role) + ROLE_FIELD_OVERHEAD
        total += estimateTokens(message.content)
        total += estimateTokens(message.contentJson)
        total += estimateTokens(message.reasoningContent)
        total += estimateTokens(message.toolCallsJson)
        total += estimateTokens(message.toolCallId)
        return total
    }

    /**
     * **传输预算**裁剪：只为了让这份历史能塞进跨进程 Binder 事务 / 落盘载荷，不是模型上下文裁剪。
     *
     * 调用方（`AgentRuntimeWire.requestBundle`）在编码 `history_json` 之前先用它切一刀，
     * 这样 `AgentConversationCodec.encodeTranscriptForStorage` 面对的就不再是完整会话。
     *
     * 语义与 [trimInPlace] 对齐，避免拆散工具调用链：
     * - 未超预算：**原样返回入参同一个 list 引用**，零复制、零分配；
     * - 超预算：从尾部（最新）往前累计成本，丢弃最老的整轮；轮次边界取 `role == "user"`，
     *   与 [trimInPlace] 的 [isUserTurn] 口径一致；
     * - 至少保留最近 [minKeepRounds] 轮，即使这样仍超预算也不再继续丢——传输上限由
     *   `requireStartRequestWithinBinderBudget` 兜底抛 `PayloadTooLargeException`，
     *   本函数不负责把请求硬压到预算内。
     *
     * 为什么保留量必须 **≥** 接收侧 [trimInPlace] 的保留量：传输裁剪发生在入口进程、上下文裁剪
     * 发生在 Runtime 进程，两者是前后串联的两道闸。若传输侧先丢掉大量历史，接收侧再按模型窗口
     * 裁一次时，被丢的就是「本来还在窗口内」的内容，等于多裁一遍、模型看到的历史比窗口允许的更少。
     * 因此 [maxChars] 应显著宽于模型上下文窗口的字符当量，建议取
     * `AgentLegacyConversationProjection.DIRECT_CHARS` 这一量级（96_000），即与既有 Binder
     * 载荷上限同档；它比任何模型窗口按本文件 CJK 口径折算出的字符数都大得多，
     * 正常情况下 [trimInPlace] 才是先触发的那道闸。
     *
     * @param messages 待传输的完整历史（不会被修改）
     * @param maxChars 传输预算，单位与 [costOf] 一致；调用方必须显式传入，
     *   建议 `AgentLegacyConversationProjection.DIRECT_CHARS`（96_000）
     * @param minKeepRounds 至少保留的最近轮数，与 [trimInPlace] 同口径
     */
    fun trimForTransfer(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
        minKeepRounds: Int = MIN_KEEP_ROUNDS,
    ): List<AgentModelClient.ConversationMessage> {
        if (messages.isEmpty()) return messages
        // 用 Long 累加：极端长会话的总字符数接近 Int 上限时不会因溢出而误判为「未超预算」。
        var used = 0L
        for (message in messages) used += costOf(message)
        val budget = maxChars.toLong()
        if (used <= budget) return messages

        // 前导 system 消息（压缩摘要等）与 [trimInPlace] 的 systemCount 同口径：不参与整轮丢弃。
        val prefixEnd = leadingSystemCount(messages)
        val roundStarts = ArrayList<Int>()
        for (index in prefixEnd until messages.size) {
            if (isUserTurn(messages[index])) roundStarts += index
        }
        if (roundStarts.size <= minKeepRounds) return messages

        // 从最老一轮开始整轮丢；丢到「剩余轮数 == minKeepRounds」或已进入预算为止。
        var droppedRounds = 0
        var start = prefixEnd
        while (roundStarts.size - droppedRounds > minKeepRounds && used > budget) {
            val roundEnd = roundStarts.getOrNull(droppedRounds + 1) ?: break
            for (index in start until roundEnd) used -= costOf(messages[index])
            start = roundEnd
            droppedRounds++
        }
        if (droppedRounds == 0) return messages
        return ArrayList<AgentModelClient.ConversationMessage>(messages.size - start + prefixEnd).apply {
            addAll(messages.subList(0, prefixEnd))
            addAll(messages.subList(start, messages.size))
        }
    }

    /** 前导 role == "system" 消息条数；与 `AgentContextComposition.leadingSystemCount` 同口径。 */
    private fun leadingSystemCount(messages: List<AgentModelClient.ConversationMessage>): Int {
        var count = 0
        while (count < messages.size && messages[count].role == "system") count++
        return count
    }

    /** [isUserTurn] 的 list 版本：传输侧还没有 `JSONObject`，只能看 data class 字段。 */
    private fun isUserTurn(message: AgentModelClient.ConversationMessage): Boolean =
        message.role == "user"
}
