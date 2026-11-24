package io.github.mangi.eta.agent.model

/**
 * 工具结果的单条上限。
 *
 * 为什么需要：工具结果是 run 内增长最快的载荷（`get_logcat`、`read_file`、整段命令输出
 * 都能到几万甚至几十万字符），它会同时进入请求消息数组、transcript、checkpoint 事件与
 * 子代理会话缓存。一次 23,090 字符的 `get_logcat` 只是常态量级，真正致命的是
 * 「6 个并发 run × 每条工具的全文 × 每轮重序列化」——2026-11-22 的 512MB 堆爆就是这条链路。
 *
 * 语义：超过 [MAX_CHARS] 时保留头 [HEAD_CHARS] 字符与尾 [TAIL_CHARS] 字符，中间换成
 * 一句带原始长度、工具名与落盘路径的说明。不静默丢：模型能从说明里知道「还有多少内容、
 * 去哪读」，并可据此缩小范围重取。
 */
internal object AgentToolResultBudget {
    /** 单条工具结果进入会话的长度上限（字符）。 */
    const val MAX_CHARS = 24_000

    /** 截断后保留的头部长度。 */
    private const val HEAD_CHARS = 18_000

    /** 截断后保留的尾部长度：报错、退出码与总结通常在末尾。 */
    private const val TAIL_CHARS = 4_000

    /**
     * 需要落盘全文的最小长度：与 [MAX_CHARS] 对齐——超过上限就落盘。
     *
     * 早先取 `MAX_CHARS * 4`（96k），意味着 24k–96k 的结果被截断却不落盘，
     * 模型拿到的是「全文未落盘」但中段永久丢失（`get_logcat` 这类不保证分页的工具无法重取）。
     * 落盘成本远低于丢数据，故这里直接对齐上限。
     */
    const val SPILL_MIN_CHARS = MAX_CHARS

    /**
     * @param toolName 产生这条结果的工具名，写进截断说明便于模型定位
     * @param content 原始结果正文
     * @param spillPath 全文落盘的绝对路径；null 表示本次没有落盘
     */
    fun bounded(toolName: String, content: String, spillPath: String? = null): String {
        if (content.length <= MAX_CHARS) return content
        val headEnd = safeCut(content, HEAD_CHARS)
        val tailStart = safeCut(content, content.length - TAIL_CHARS)
        val omitted = (tailStart - headEnd).coerceAtLeast(0)
        return buildString(minOf(content.length, MAX_CHARS) + 320) {
            append(content, 0, headEnd)
            append("\n\n…[工具结果已截断：原始 ")
            append(content.length)
            append(" 字符，省略中间 ")
            append(omitted)
            append(" 字符；工具 ")
            append(toolName)
            if (spillPath.isNullOrBlank()) {
                append("；全文未落盘")
            } else {
                append("；全文已落盘 ")
                append(spillPath)
            }
            append("。需要中间部分时缩小范围重取")
            append("（read_file 用 offset_bytes/max_bytes，命令加 grep、head、tail 过滤），")
            append("不要假设被省略的内容不存在。]\n\n")
            append(content, tailStart, content.length)
        }
    }

    /** 不把代理对（emoji 等）从中间切开。 */
    private fun safeCut(text: String, index: Int): Int {
        if (index <= 0) return 0
        if (index >= text.length) return text.length
        val previous = text[index - 1]
        val current = text[index]
        return if (previous.isHighSurrogate() && current.isLowSurrogate()) index - 1 else index
    }
}
