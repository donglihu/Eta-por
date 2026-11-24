package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具结果上限：短结果原样通过，长结果保留头尾并写明原始长度与落盘路径。 */
class AgentToolResultBudgetTest {

    @Test
    fun shortContentPassesThroughUnchanged() {
        val content = "{\"ok\":true,\"value\":\"短结果\"}"
        assertEquals(content, AgentToolResultBudget.bounded("terminal", content))
    }

    @Test
    fun exactlyAtLimitPassesThrough() {
        val content = "x".repeat(AgentToolResultBudget.MAX_CHARS)
        assertEquals(content, AgentToolResultBudget.bounded("read_file", content))
    }

    @Test
    fun oversizedContentKeepsHeadAndTailWithMarker() {
        val head = "H".repeat(AgentToolResultBudget.MAX_CHARS)
        val tail = "T".repeat(5_000)
        val content = head + "M".repeat(200_000) + tail
        val spill = "/data/user/0/io.github.mangi.eta/cache/agent-tool-output/run-1/call-1.txt"

        val bounded = AgentToolResultBudget.bounded("get_logcat", content, spill)

        assertTrue(bounded.length < content.length)
        assertTrue(bounded.startsWith("H".repeat(1_000)))
        assertTrue(bounded.contains("工具结果已截断"))
        assertTrue(bounded.contains("原始 ${content.length} 字符"))
        assertTrue(bounded.contains("get_logcat"))
        assertTrue(bounded.contains(spill))
        assertTrue(bounded.trimEnd().endsWith("T".repeat(1_000)))
    }

    @Test
    fun oversizedContentWithoutSpillSaysSoExplicitly() {
        val content = "A".repeat(AgentToolResultBudget.MAX_CHARS + 1)

        val bounded = AgentToolResultBudget.bounded("terminal", content, spillPath = null)

        assertTrue(bounded.contains("全文未落盘"))
        assertTrue(bounded.contains("缩小范围重取"))
    }

    /** 截断点不能落在一对代理对中间，否则会产出半个字符。 */
    @Test
    fun truncationNeverSplitsSurrogatePair() {
        val emoji = "\uD83D\uDE00"
        val content = "a".repeat(17_999) + emoji + "b".repeat(40_000)

        val bounded = AgentToolResultBudget.bounded("read_file", content)

        // 不变量是「不产出半个字符」，不是「emoji 必须留在输出里」：
        // 这个 emoji 落在 17_999，正好处在头部边界外、属于被省略的中间区，
        // 按「只留头尾」的设计本就不该出现。早先这里断言 contains(emoji)，
        // 与设计自相矛盾，必然失败。
        val headEnd = bounded.indexOf("\n\n…[")
        assertTrue(headEnd > 0)
        val previous = bounded[headEnd - 1]
        assertTrue(!previous.isHighSurrogate())
        // 整个输出里都不许出现孤立代理对（半个字符）。
        assertTrue("输出含孤立代理对", !hasLoneSurrogate(bounded))
    }

    /** 是否存在孤立的高/低代理（即半个字符）。 */
    private fun hasLoneSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val current = text[index]
            if (current.isHighSurrogate()) {
                val next = text.getOrNull(index + 1)
                if (next == null || !next.isLowSurrogate()) return true
                index += 2
                continue
            }
            if (current.isLowSurrogate()) return true
            index += 1
        }
        return false
    }
}
