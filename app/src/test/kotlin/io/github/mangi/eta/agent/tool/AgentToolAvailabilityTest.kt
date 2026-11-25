package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 逐工具禁用集合的纯逻辑单测：不依赖 Android、不依赖 org.json，arm64 上不会被跳过。 */
class AgentToolAvailabilityTest {

    @Test
    fun `default is fully enabled`() {
        assertTrue(AgentToolAvailability.isToolEnabled("terminal", emptySet()))
    }

    @Test
    fun `disabled tool is filtered out`() {
        val disabled = setOf("terminal", "read_file")
        assertFalse(AgentToolAvailability.isToolEnabled("terminal", disabled))
        assertEquals(listOf("write_file"), AgentToolAvailability.filterEnabled(listOf("terminal", "write_file"), disabled))
    }

    @Test
    fun `protected tools survive the disabled set`() {
        val disabled = AgentToolAvailability.PROTECTED_TOOLS
        AgentToolAvailability.PROTECTED_TOOLS.forEach { name ->
            assertTrue(name, AgentToolAvailability.isToolEnabled(name, disabled))
        }
        assertEquals(
            AgentToolAvailability.PROTECTED_TOOLS.toList(),
            AgentToolAvailability.filterEnabled(AgentToolAvailability.PROTECTED_TOOLS.toList(), disabled),
        )
    }

    @Test
    fun `filter keeps original order and blank names are dropped`() {
        val names = listOf("terminal", "", "read_file", "memory_get")
        assertEquals(
            listOf("read_file", "memory_get"),
            AgentToolAvailability.filterEnabled(names, setOf("terminal")),
        )
    }

    @Test
    fun `custom protected set overrides the default one`() {
        assertFalse(AgentToolAvailability.isToolEnabled("terminal", setOf("terminal"), protectedTools = emptySet()))
        assertTrue(AgentToolAvailability.isToolEnabled("terminal", setOf("terminal"), protectedTools = setOf("terminal")))
    }
}
