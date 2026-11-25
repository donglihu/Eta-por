package io.github.mangi.eta.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 执行侧闸门的纯 JVM 单测（不加 @RunWith，arm64 上不会被 assume 跳过）。
 *
 * [AgentToolGuard.rejectionOrNull] 的 disabledTools 是显式参数，这里直接喂集合，
 * 不去读 SharedPreferences，所以断言的是「判定 + 拒绝结果」本身而不是读盘行为。
 */
class AgentToolGuardTest {

    @Test
    fun `enabled tool passes through`() {
        assertNull(AgentToolGuard.rejectionOrNull("terminal", emptySet()))
        assertNull(AgentToolGuard.rejectionOrNull("terminal", setOf("read_file")))
    }

    @Test
    fun `disabled tool is rejected with a single shared code and message`() {
        val result = AgentToolGuard.rejectionOrNull("terminal", setOf("terminal"))
        assertNotNull(result)
        val payload = JSONObject(checkNotNull(result).content)
        assertFalse(payload.optBoolean("ok"))
        assertEquals("TOOL_DISABLED", payload.optString("code"))
        assertEquals(AgentToolGuard.CODE_TOOL_DISABLED, payload.optString("code"))
        assertEquals(AgentToolGuard.MESSAGE_TOOL_DISABLED, payload.optString("message"))
        assertEquals("TOOL_DISABLED", AgentToolGuard.failure().let { JSONObject(it.content).optString("code") })
    }

    @Test
    fun `protected tools are never rejected`() {
        AgentToolAvailability.PROTECTED_TOOLS.forEach { name ->
            assertNull(name, AgentToolGuard.rejectionOrNull(name, AgentToolAvailability.PROTECTED_TOOLS))
        }
    }

    @Test
    fun `unknown blank tool name is rejected instead of reaching the executor`() {
        assertNull(AgentToolGuard.rejectionOrNull("anything", emptySet()))
        assertNotNull(AgentToolGuard.rejectionOrNull("", emptySet()))
    }
}
