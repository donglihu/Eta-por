package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划闸门的只读判定：plan 阶段只放行查询，一切会动设备/改文件/发外消息的工具都要等清单先落地。
 */
class PlanModePolicyTest {

    @Test
    fun `plan tool itself is always allowed`() {
        assertTrue(PlanModePolicy.isReadOnlyTool(PlanModePolicy.PLAN_TOOL_NAME))
    }

    @Test
    fun `query tools stay available while planning`() {
        listOf(
            "search_messages",
            "read_file",
            "get_setting",
            "list_directory",
            "observe_screen",
            "grep_files",
            "device_status",
            "network_info",
            "web_search",
            "fetch_url",
            "ask_user",
            "memory_get",
        ).forEach { name ->
            assertTrue("$name 应视为只读", PlanModePolicy.isReadOnlyTool(name))
        }
    }

    @Test
    fun `side effect tools are blocked until the plan is submitted`() {
        listOf(
            "write_file",
            "edit_file",
            "terminal",
            "launch_app",
            "tap",
            "swipe",
            "type_text",
            "set_device_state",
            "set_alarm",
            "memory_write",
            "skills_manage",
            "browser_use",
            "subagent",
            "update_plan_extra",
        ).forEach { name ->
            assertFalse("$name 应被计划闸门拦下", PlanModePolicy.isReadOnlyTool(name))
        }
    }

    @Test
    fun `blocked message points the model back to update_plan`() {
        val message = PlanModePolicy.planRequiredFirstMessage("write_file")
        assertTrue(message.contains("write_file"))
        assertTrue(message.contains(PlanModePolicy.PLAN_TOOL_NAME))
    }
}
