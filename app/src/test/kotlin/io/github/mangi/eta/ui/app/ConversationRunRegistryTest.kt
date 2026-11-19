package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * run↔会话登记表：多会话并行时，插话、停止、队列派发都必须落在自己的会话上。
 *
 * 锁住的是串台根因：以前只存单向表，反向查询退化成「最近一次启动的 run」，
 * 于是在 A 会话插的话被投进 B 会话正在跑的任务里。
 */
class ConversationRunRegistryTest {

    @Test
    fun `run id resolves back to its own conversation`() {
        val registry = ConversationRunRegistry()
        registry.bind("run-a", "conv-a")
        registry.bind("run-b", "conv-b")

        assertEquals("conv-a", registry.conversationIdOf("run-a"))
        assertEquals("conv-b", registry.conversationIdOf("run-b"))
    }

    @Test
    fun `steering in one conversation never picks another conversation run`() {
        val registry = ConversationRunRegistry()
        registry.bind("run-a", "conv-a")
        registry.bind("run-b", "conv-b")

        // B 后启动，但 A 的插话必须拿 A 自己的 run。
        assertEquals("run-a", registry.runIdOf("conv-a"))
        assertEquals("run-b", registry.runIdOf("conv-b"))
    }

    @Test
    fun `releasing a run keeps other conversations untouched`() {
        val registry = ConversationRunRegistry()
        registry.bind("run-a", "conv-a")
        registry.bind("run-b", "conv-b")

        assertEquals("conv-a", registry.release("run-a"))
        assertNull(registry.conversationIdOf("run-a"))
        assertEquals("run-b", registry.runIdOf("conv-b"))
        assertFalse(registry.isConversationRunning("conv-a"))
        assertTrue(registry.isConversationRunning("conv-b"))
    }

    @Test
    fun `capacity counts live runs across conversations`() {
        val registry = ConversationRunRegistry()
        assertEquals(0, registry.size)
        registry.bind("run-a", "conv-a")
        registry.bind("run-b", "conv-b")
        assertEquals(2, registry.size)
        registry.release("run-a")
        assertEquals(1, registry.size)
        assertEquals(setOf("run-b"), registry.runIds())
    }

    @Test
    fun `unknown conversation has no run`() {
        val registry = ConversationRunRegistry()
        registry.bind("run-a", "conv-a")
        assertNull(registry.runIdOf("conv-x"))
        assertNull(registry.runIdOf(null))
    }
}
