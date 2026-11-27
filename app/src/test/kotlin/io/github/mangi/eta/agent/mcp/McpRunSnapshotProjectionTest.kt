package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 工具按需投影的契约测试。
 *
 * 覆盖三件容易改坏的事：
 * 1. 组 id 与 modelToolName 用同一个 `serverGroupPart` 派生——写歪一侧就会出现「开组成功但工具永不出现」；
 * 2. `appendModelTools` 只在传入 openedGroupIds 时裁剪，且裁剪不影响 resolve（执行侧仍走全量快照）；
 * 3. 超限被丢弃的工具数必须能显式上报，不能静默消失。
 */
class McpRunSnapshotProjectionTest {

    private fun server(id: String, name: String) = McpServerSetting(id = id, name = name, url = "http://localhost/mcp")

    private fun tool(server: McpServerSetting, modelName: String, toolName: String) = McpRunTool(
        modelName = modelName,
        server = server,
        definition = McpToolDefinition(name = toolName, inputSchemaJson = "{\"type\":\"object\"}"),
        bearerToken = null,
    )

    private fun names(array: JSONArray): List<String> = (0 until array.length()).map {
        array.getJSONObject(it).getJSONObject("function").getString("name")
    }

    @Test
    fun groupIdUsesTheSameServerPartAsModelToolName() {
        val alpha = server("alpha-server-01", "Alpha")
        val beta = server("beta server 02", "Beta")
        val snapshot = McpRunSnapshot(
            listOf(
                tool(alpha, "mcp_alphaser_echo_00000000", "echo"),
                tool(beta, "mcp_betaserv_echo_11111111", "echo"),
            ),
        )

        // serverPart 是「字母数字前 8 位」：这是 modelToolName 与组 id 的唯一共同来源，字面量钉住它防止被改。
        assertEquals("alphaser", McpRunSnapshot.serverGroupPart("alpha-server-01"))
        assertEquals("betaserv", McpRunSnapshot.serverGroupPart("beta server 02"))

        val groups = snapshot.serverGroups()
        assertEquals(setOf("mcp:alphaser", "mcp:betaserv"), groups.keys)
        assertEquals(setOf("mcp_alphaser_echo_00000000"), groups["mcp:alphaser"])
        // 组 id 必须能从工具名反推出来，否则 AgentModelClient 侧的 serverPart 派生会漂移。
        snapshot.tools.forEach {
            assertTrue(it.modelName.startsWith("mcp_${McpRunSnapshot.serverGroupPart(it.server.id)}_"))
        }
    }

    @Test
    fun appendModelToolsDeclaresEverythingWhenNoProjectionIsRequested() {
        val alpha = server("alpha", "Alpha")
        val beta = server("beta", "Beta")
        val snapshot = McpRunSnapshot(
            listOf(
                tool(alpha, "mcp_alpha_echo_1", "echo"),
                tool(beta, "mcp_beta_list_2", "list"),
            ),
        )

        val destination = JSONArray()
        snapshot.appendModelTools(destination)
        assertEquals(listOf("mcp_alpha_echo_1", "mcp_beta_list_2"), names(destination))
    }

    @Test
    fun appendModelToolsOnlyDeclaresOpenedServerGroups() {
        val alpha = server("alpha", "Alpha")
        val beta = server("beta", "Beta")
        val snapshot = McpRunSnapshot(
            listOf(
                tool(alpha, "mcp_alpha_echo_1", "echo"),
                tool(beta, "mcp_beta_list_2", "list"),
            ),
        )

        val destination = JSONArray()
        snapshot.appendModelTools(destination, openedGroupIds = setOf("mcp:alpha"))
        assertEquals(listOf("mcp_alpha_echo_1"), names(destination))

        val none = JSONArray()
        snapshot.appendModelTools(none, openedGroupIds = emptySet())
        assertEquals(0, none.length())
    }

    @Test
    fun projectionDoesNotAffectExecutionLookup() {
        val alpha = server("alpha", "Alpha")
        val snapshot = McpRunSnapshot(listOf(tool(alpha, "mcp_alpha_echo_1", "echo")))

        val declared = JSONArray()
        snapshot.appendModelTools(declared, openedGroupIds = emptySet())
        assertEquals(0, declared.length())
        // 未开组时不声明，但执行侧 resolve 仍然能找到：开组之前模型也调不到，靠的是目录而不是执行拒绝。
        assertNotNull(snapshot.resolve("mcp_alpha_echo_1"))
    }

    @Test
    fun summariesCarryServerNameAndTruncatedToolCount() {
        val alpha = server("alpha", "Alpha")
        val tools = (1..8).map { tool(alpha, "mcp_alpha_tool_$it", "tool_$it") }
        val snapshot = McpRunSnapshot(tools)

        val summary = snapshot.serverGroupSummaries().getValue("mcp:alpha")
        assertTrue(summary.contains("Alpha"))
        assertTrue(summary.contains("tool_1"))
        // 只列前 6 个，后面用「等 N 个工具」收口。
        assertTrue(summary.endsWith("等 8 个工具"))
    }

    @Test
    fun truncationNoticeReportsOmittedToolsPerServer() {
        assertNull(McpRunSnapshot(listOf(tool(server("alpha", "Alpha"), "mcp_alpha_echo_1", "echo"))).truncationNotice())

        val notice = McpRunSnapshot(
            tools = emptyList(),
            omittedToolsByServer = mapOf("alpha" to 3, "beta" to 1),
        ).truncationNotice()

        assertNotNull(notice)
        assertTrue(notice!!.contains("alpha"))
        assertTrue(notice.contains("3"))
        assertTrue(notice.contains("；"))
    }

    @Test
    fun emptySnapshotDeclaresNothing() {
        val destination = JSONArray()
        McpRunSnapshot.EMPTY.appendModelTools(destination)
        assertEquals(0, destination.length())
        assertNull(McpRunSnapshot.EMPTY.truncationNotice())
        assertTrue(McpRunSnapshot.EMPTY.serverGroups().isEmpty())
    }
}
