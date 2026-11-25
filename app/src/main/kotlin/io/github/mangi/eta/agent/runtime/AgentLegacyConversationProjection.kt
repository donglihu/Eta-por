package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅为不认识完整载荷字段的旧客户端提供内联预览；禁止用于存储或新模型请求。 */
internal object AgentLegacyConversationProjection {
    const val DIRECT_CHARS = 96_000
    const val DRAIN_CHARS = 16_000
    private val json = Json { encodeDefaults = false }
    private const val COMPACTION_NOTICE =
        "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]"
    private const val ROLE_TOOL = "tool"

    /**
     * 每条消息在 JSON 里的固定开销（字段名、引号、逗号、role/toolCallId 等）。
     * 估算只服务预算判断，允许偏小；真实超限由 encode() 的一次自适应收缩兜住。
     */
    private const val MESSAGE_JSON_OVERHEAD = 48L

    /**
     * 取 [messages] 尾部不超过 [maxChars] 的切片，保持原顺序。
     *
     * 旧实现在 encode() 的 while 里 bounded.removeAt(0) 加每次全量 json.encodeToString(bounded)：
     * 每丢一条消息就要把整个剩余列表重新编码一次，单次编码 O(n)、循环 O(n) 次，整体 O(n²)；
     * 会话越长、单次分配的中间字符串越大，最终在 AbstractStringBuilder.<init> 处 OutOfMemoryError。
     * 这里改成一次反向扫描：只做整数累加，不产生任何中间字符串，整体 O(n)。
     *
     * 快速路径直接返回入参本身（不复制、不编码），调用方可用引用比较判断是否发生过裁剪。
     */
    fun tail(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): List<AgentModelClient.ConversationMessage> {
        if (messages.isEmpty()) return emptyList()
        val budget = maxChars.coerceAtLeast(1).toLong()

        // 快速路径：整体估算已在预算内，原样返回，不复制列表也不重新编码。
        var total = 0L
        for (index in messages.indices) {
            total += estimateChars(messages[index])
            if (total > budget) break
        }
        if (total <= budget) return messages

        // 反向累加：从最后一条往前吃，直到再加一条就超预算为止。
        // 长度全部来自已有 String 的 length（O(1)），不为量尺寸而 encodeToString 整段。
        var start = messages.size
        var tailUsed = 0L
        while (start > 0) {
            val cost = estimateChars(messages[start - 1])
            if (tailUsed + cost > budget) break
            tailUsed += cost
            start -= 1
        }
        // 最后一条自己就超预算时上面一条都没吃进来，先把它纳入，
        // 否则会返回空切片，比原实现更糟（随后由逐条裁剪压回预算）。
        if (start == messages.size) start = messages.size - 1

        // 切片头部落在 role == "tool" 时，把发起这批 tool 的 assistant 一起往回收（连续 tool 一起收）。
        // 选择「回收」而不是「丢弃孤立 tool」：tool 消息承载工具真实输出，是上下文里信息密度最高的一段；
        // 丢掉它只剩一条没有结果的 assistant(tool_calls)，对旧客户端既不可读，也容易被上游判成非法序列。
        while (start > 0 && messages[start].role == ROLE_TOOL) start -= 1

        // 回收会把预算外的那条 assistant 又拉回来，切片因此可能重新超预算。
        // 这里用纯算术收敛：从头部整条丢弃、并顺带丢掉因此变成孤儿的 tool 消息，
        // 全程只做整数加减，不调用 encodeToString，所以整体仍是 O(n) 而不是回到 O(n²)。
        var reclaimed = 0L
        for (index in start until messages.size) reclaimed += estimateChars(messages[index])
        while (reclaimed > budget && start < messages.size - 1) {
            reclaimed -= estimateChars(messages[start])
            start += 1
            while (start < messages.size - 1 && messages[start].role == ROLE_TOOL) {
                reclaimed -= estimateChars(messages[start])
                start += 1
            }
        }

        // 逐条裁剪：只对「单条自己就超预算」的消息做裁剪（沿用原实现 take(maxChars/4) 的兜底思路），
        // 其余消息原样保留，避免把正常内容也切碎。
        return messages.subList(start, messages.size).map { message ->
            if (estimateChars(message) <= budget) {
                message
            } else {
                val keep = (maxChars / 4).coerceAtLeast(1)
                message.copy(
                    content = message.content.take(keep),
                    contentJson = "",
                    reasoningContent = message.reasoningContent.take(keep),
                    toolCallsJson = "",
                )
            }
        }
    }

    /**
     * 旧客户端的字符串投影。改为基于 [tail] 的「取尾切片 + 编码」：
     * 不再在循环里反复全量重编码（O(n²) → O(n)），预算逻辑最多只做两次 encodeToString。
     */
    fun encode(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        if (messages.isEmpty()) return "[]"

        val slice = tail(messages, maxChars)
        // tail() 快速路径返回入参本身；引用不同即代表发生过裁剪（丢消息或裁剪了单条）。
        val trimmed = slice !== messages
        val notice = AgentModelClient.ConversationMessage(
            role = "system",
            content = COMPACTION_NOTICE,
        )
        val payload = if (trimmed) listOf(notice) + sanitizeSlice(slice) else sanitizeSlice(slice)

        // 第一次编码：按尾部切片整体编码，只扫一遍；不再对全量历史做 encode + decode 往返。
        val first = json.encodeToString(payload)
        if (first.length <= maxChars) return first

        // 估算只累加字段长度，不计 JSON 转义（引号、换行、反斜杠都会膨胀），所以需要一次收缩。
        // 用「第一次实际长度 / 估算长度」得到真实膨胀比，再按它反推还能装多少估算预算；
        // 这样既守住「预算逻辑最多两次编码」的硬约束，又不会像固定减半那样把可用历史白白砍掉。
        val estimate = payload.sumOf { estimateChars(it) }.coerceAtLeast(1L)
        val noticeCost = COMPACTION_NOTICE.length + MESSAGE_JSON_OVERHEAD
        val room = (maxChars.toLong() - noticeCost).coerceAtLeast(1L)
        val ratio = first.length.toDouble() / estimate.toDouble()
        val scaledBudget = (room / ratio).toLong().coerceIn(1L, room)

        // 第二次（也是预算逻辑的最后一次）编码。
        val second = json.encodeToString(listOf(notice) + sanitizeSlice(tail(messages, scaledBudget.toInt())))
        if (second.length <= maxChars) return second

        // 极端兜底：自适应收缩后仍塞不下就交空数组，保留原函数返回 "[]" 的语义。
        return "[]"
    }

    /**
     * 对尾部切片逐条归一化，规模上限是 maxChars 而不是整段历史。
     *
     * 旧实现先对「全量 messages」做 encodeTranscriptForStorage + decodeTranscript 往返，
     * 那一步本身就要物化数 MB 的历史 JSON，是 OOM 的另一半来源；这里把同一往返限制在切片上。
     * 必须沿用这对公开方法：它们内部走私有的 sanitizeMessage，会把 contentJson 里的
     * image_url/source 替换成占位文本；直接用 toJsonObject/fromJsonObject 往返不会剥图片，
     * 会把图片引用漏给旧客户端。
     */
    private fun sanitizeSlice(
        slice: List<AgentModelClient.ConversationMessage>,
    ): List<AgentModelClient.ConversationMessage> {
        if (slice.isEmpty()) return slice
        val sanitized = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(slice),
        )
        // 解码侧对损坏 JSON 会降级成空列表；条数对不上就退回未归一化切片，宁可多带字段也不丢消息。
        return if (sanitized.size == slice.size) sanitized else slice
    }

    /** 不分配新字符串的尺寸估算：只累加已有字段长度。 */
    private fun estimateChars(message: AgentModelClient.ConversationMessage): Long =
        message.content.length.toLong() +
            message.contentJson.length +
            message.reasoningContent.length +
            message.toolCallsJson.length +
            message.role.length +
            message.toolCallId.length +
            MESSAGE_JSON_OVERHEAD
}
