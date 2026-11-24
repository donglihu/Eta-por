package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPolicyTest {

    private fun schema(name: String): JSONObject =
        JSONObject().put("function", JSONObject().put("name", name))

    private fun namesOf(tools: JSONArray): Set<String> =
        (0 until tools.length()).mapTo(linkedSetOf()) {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }

    @Test
    fun allowAllKeepsEveryTool() {
        val tools = JSONArray().put(schema("terminal")).put(schema("subagent"))
        assertEquals(setOf("terminal", "subagent"), namesOf(AgentToolPolicy().filterTools(tools)))
    }

    @Test
    fun allowListKeepsOnlyWhitelistedTools() {
        val tools = JSONArray().put(schema("terminal")).put(schema("web_search"))
        val policy = AgentToolPolicy(
            mode = ToolPolicyMode.ALLOW_LIST,
            allowedTools = setOf("terminal"),
        )
        assertEquals(setOf("terminal"), namesOf(policy.filterTools(tools)))
        assertFalse(policy.allows("web_search"))
    }

    /**
     * 白名单模式下子智能体派发工具不能被滤掉。
     *
     * 2026-11-20 实测：设备上工具策略是 ALLOW_LIST，白名单里没有 subagent，
     * 于是 AgentToolPolicy.filterTools() 把它从请求的 tools 数组里拿掉——
     * 提示词里却写着「可调用 subagent 派发子智能体」，模型一调就是 TOOL_NOT_AVAILABLE。
     * 编排类工具不碰设备，真正的边界在执行侧的审批闸门与子代理自身白名单。
     */
    @Test
    fun subagentToolsSurviveAllowListFiltering() {
        val tools = JSONArray()
            .put(schema("terminal"))
            .put(schema(AgentSubagentToolCatalog.TOOL_NAME))
            .put(schema(AgentSubagentToolCatalog.PROFILE_TOOL_NAME))
        val policy = AgentToolPolicy(
            mode = ToolPolicyMode.ALLOW_LIST,
            allowedTools = setOf("terminal"),
        )
        val filtered = namesOf(policy.filterTools(tools))
        assertTrue(AgentSubagentToolCatalog.TOOL_NAME in filtered)
        assertTrue(AgentSubagentToolCatalog.PROFILE_TOOL_NAME in filtered)
        assertEquals(setOf("terminal"), filtered - AgentSubagentToolCatalog.names)
    }

    /**
     * 声明放行与执行放行必须一致。
     *
     * 执行侧 AgentModelClient 用 toolPolicy.allows() 兜底拒绝未授权工具；
     * 如果只有 filterTools 放行、allows 拒绝，就会出现「模型看得见、调不动」。
     */
    /**
     * 白名单模式下专家管理与 ask_user 不能被滤掉。
     *
     * 2026-11-20 实测：设备上工具策略是 ALLOW_LIST，白名单里没有 expert_manage，
     * 于是 AgentToolPolicy.filterTools() 把它从请求的 tools 数组里拿掉——
     * 用户说「帮我建个 XX 专家」，模型一调 expert_manage 就是 TOOL_NOT_AVAILABLE，
     * 专家永远建不出来。ask_user 同理：它是模型唯一能主动追问用户并拿到
     * 选项答案的入口，被滤掉后遇到需要用户拍板的场景只能编答案。
     * 两者都是编排/交互类工具，不碰设备不改文件，真正的边界在执行侧。
     */
    @Test
    fun expertAndAskUserSurviveAllowListFiltering() {
        val tools = JSONArray()
            .put(schema("terminal"))
            .put(schema(io.github.mangi.eta.agent.model.AgentExpertToolCatalog.TOOL_NAME))
            .put(schema("ask_user"))
        val policy = AgentToolPolicy(
            mode = ToolPolicyMode.ALLOW_LIST,
            allowedTools = setOf("terminal"),
        )
        val filtered = namesOf(policy.filterTools(tools))
        assertTrue(io.github.mangi.eta.agent.model.AgentExpertToolCatalog.TOOL_NAME in filtered)
        assertTrue("ask_user" in filtered)
        assertEquals(
            setOf("terminal"),
            filtered - setOf(
                io.github.mangi.eta.agent.model.AgentExpertToolCatalog.TOOL_NAME,
                "ask_user",
            ),
        )
        // 声明放行与执行放行必须一致：执行侧 AgentModelClient 用 allows() 兜底。
        assertTrue(policy.allows(io.github.mangi.eta.agent.model.AgentExpertToolCatalog.TOOL_NAME))
        assertTrue(policy.allows("ask_user"))
    }
}
