package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.tool.AgentToolAvailability
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录层的逐工具开关：请求里到底声明了哪些工具。
 *
 * 刻意不加 @RunWith：arm64 上 Robolectric 用例会被 AssumptionViolatedException 静默跳过，
 * 落在 Robolectric 上的断言等于没写。[AgentToolDirectory.build] 本身不碰 Android，
 * 与 [AgentToolCatalogTest] 同一套构造方式即可跑成纯 JVM 用例。
 */
class AgentToolDirectoryDisabledToolsTest {

    @Test
    fun `disabled tool disappears from the request directory`() {
        val names = directory(disabledTools = setOf("terminal")).names()
        assertFalse("terminal" in names)
        // 保护工具不受禁用集合影响（目录侧与执行侧同一份判定）。
        assertTrue("update_plan" in names)
        assertTrue("ask_user" in names)
        assertTrue("memory_get" in names)
        // 只删掉被禁用的那一个，同组其它工具照旧声明。
        assertTrue("read_file" in names)
    }

    @Test
    fun `additional tools channel is filtered by name too`() {
        val extra = JSONArray()
            .put(toolSchema("mcp_server_search_012345"))
            .put(toolSchema("terminal"))
            .put(toolSchema("character_memory_get"))
        val names = directory(disabledTools = setOf("terminal", "character_memory_get"), additionalTools = extra).names()
        assertTrue("托管与 MCP 追加的工具照旧声明", "mcp_server_search_012345" in names)
        assertFalse("追加通道不能成为被关掉工具的侧门", "terminal" in names)
        assertFalse("character_memory_get" in names)
    }

    @Test
    fun `non object entries in additional tools are dropped instead of poisoning the array`() {
        val extra = JSONArray().put(JSONObject.NULL).put("garbage").put(toolSchema("mcp_server_search_012345"))
        val tools = directory(disabledTools = emptySet(), additionalTools = extra)
        assertEquals(setOf("mcp_server_search_012345"), tools.names() - baseNames())
    }

    @Test
    fun `protected tools survive an aggressive disabled set`() {
        val names = directory(disabledTools = AgentToolAvailability.PROTECTED_TOOLS).names()
        AgentToolAvailability.PROTECTED_TOOLS.forEach { name -> assertTrue(name, name in names) }
    }

    @Test
    fun `empty disabled set keeps the directory identical to not passing the parameter`() {
        // 不传参数时走的是默认值 emptySet()：两者必须逐字节一致，否则「默认全开」就有两套口径。
        val withoutParameter = AgentToolDirectory.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            memoryWritable = true,
            capabilities = capabilities(),
            localWebSearch = true,
            localProfile = LocalToolProfile.FULL,
            policy = AgentToolPolicy(),
        )
        assertEquals(withoutParameter.toString(), directory(disabledTools = emptySet()).toString())
        assertTrue(withoutParameter.names().isNotEmpty())
    }

    private fun directory(disabledTools: Set<String>, additionalTools: JSONArray? = null): JSONArray =
        AgentToolDirectory.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            memoryWritable = true,
            capabilities = capabilities(),
            localWebSearch = true,
            localProfile = LocalToolProfile.FULL,
            policy = AgentToolPolicy(),
            additionalTools = additionalTools,
            disabledTools = disabledTools,
        )

    private fun baseNames(): Set<String> = directory(disabledTools = emptySet()).names()

    /** planMode=true 才会声明 update_plan；Root 与无障碍全给，避免能力投影提前把工具筛掉。 */
    private fun capabilities(): AgentToolCapabilities =
        AgentToolCapabilities(rootAvailable = true, planMode = true)

    private fun toolSchema(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", name))

    private fun JSONArray.names(): Set<String> = (0 until length())
        .mapNotNull { index ->
            optJSONObject(index)
                ?.optJSONObject("function")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
        }
        .toSet()
}
