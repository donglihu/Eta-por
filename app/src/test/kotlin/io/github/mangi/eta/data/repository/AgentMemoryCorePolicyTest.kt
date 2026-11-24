package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 非核心分流的判据回归：这层决定「缺省 scope 的追加」到底落 MEMORY.md 还是 NOTES.md。
 *
 * 判据错了有两种代价：把日志塞进核心（预算被挤爆），或把长期约束挡在门外（记忆丢内容）。
 * 所以正反两面都钉用例。
 */
class AgentMemoryCorePolicyTest {

    @Test
    fun progressAndLogTextIsNonCore() {
        assertNotNull(AgentMemoryCorePolicy.nonCoreReason("本轮构建已通过，出包 BUILD SUCCESSFUL"))
        assertNotNull(AgentMemoryCorePolicy.nonCoreReason("待办：补巡检脚本的 prune"))
        assertNotNull(AgentMemoryCorePolicy.nonCoreReason("sha256=aae86dcc…（终态包）"))
    }

    @Test
    fun datedBulletListCountsAsLog() {
        val log = """
            - 2026-11-21 修好预算闸门
            - 2026-11-21 修好巡检脚本
        """.trimIndent()

        assertNotNull(AgentMemoryCorePolicy.nonCoreReason(log))
    }

    @Test
    fun durableFactsStayCore() {
        assertNull(AgentMemoryCorePolicy.nonCoreReason("老大称呼：老大的偏好是简体中文、直接给结论"))
        assertNull(AgentMemoryCorePolicy.nonCoreReason("工作区边界：只写 /data/local/tmp/eta 内部"))
        assertNull(AgentMemoryCorePolicy.nonCoreReason("用户不说，就不要 commit 或 push"))
    }

    @Test
    fun emptyContentHasNoVerdict() {
        assertNull(AgentMemoryCorePolicy.nonCoreReason("   "))
        assertEquals(null, AgentMemoryCorePolicy.nonCoreReason(""))
    }
}
