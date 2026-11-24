package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRequirementsTest {
    @Test
    fun everyRegisteredToolHasExactlyOneRequirement() {
        // 子智能体与角色记忆工具由 Runtime 侧追加（见 AgentRuntimeRunExecutor.runTools），
        // 不在 AgentToolCatalog 里，这里一并补上再比对，否则两份目录永远对不上。
        val tools = catalog(root = true).also {
            CharacterMemoryTools.appendSchemas(it)
            AgentSubagentToolCatalog.appendSchemas(it)
        }
        val executionOnly = setOf("input_text", "replace_text", "clear_text", "paste_text")
        assertEquals(AgentToolRequirements.toolNames - executionOnly, tools.names())
        executionOnly.forEach { name -> assertFalse("$name 仅保留执行兼容，不再向模型暴露", name in tools.names()) }
        assertEquals(tools.length(), tools.names().size)
        assertFalse(tools.toString().contains("rootRequirement"))
    }

    /**
     * 子智能体派发工具必须登记在能力目录里。
     *
     * 不登记的后果：设置页「工具白名单」列不出它，
     * 而白名单一旦是 ALLOW_LIST 模式，AgentToolPolicy.filterTools() 会把它从请求的
     * tools 数组里滤掉 —— 模型一调就回 TOOL_NOT_AVAILABLE（2026-11-20 实测）。
     */
    @Test
    fun subagentToolsAreRegisteredSoTheyCanBeWhitelisted() {
        assertTrue("subagent" in AgentToolRequirements.toolNames)
        assertTrue("subagent_profile" in AgentToolRequirements.toolNames)
        assertEquals(RootRequirement.NONE, AgentToolRequirements.rootRequirement("subagent"))
        assertEquals(RootRequirement.NONE, AgentToolRequirements.rootRequirement("subagent_profile"))
        // 不需要 Root：缺 Root 时也不该被 project() 滤掉。
        // 子智能体工具由 Runtime 侧追加（additionalTools），这里照样追加一份再投影。
        val withSubagent = catalog(root = false).also { AgentSubagentToolCatalog.appendSchemas(it) }
        val projected = AgentToolRequirements.project(withSubagent, rootAvailable = false)
        assertTrue("subagent" in projected.names())
        assertTrue("subagent_profile" in projected.names())
    }

    @Test
    fun expertManageIsRegisteredSoEveryRunCanBuildTools() {
        // 不登记的后果：AgentToolRequirements.project() 对每个声明工具调 rootRequirement()，
        // 抛 "Missing tool requirements: expert_manage"，整轮运行在拼工具阶段就报错（2026-11-20 实测）。
        assertTrue("expert_manage" in AgentToolRequirements.toolNames)
        assertEquals(RootRequirement.NONE, AgentToolRequirements.rootRequirement("expert_manage"))
        // 不需要 Root：缺 Root 时也不该被 project() 滤掉，也不该被改写成受限身份。
        val projected = AgentToolRequirements.project(catalog(root = false), rootAvailable = false)
        assertTrue("expert_manage" in projected.names())
    }

    @Test
    fun ordinaryCatalogDoesNotExposeCharacterMemoryTools() {
        assertTrue(catalog(root = true).names().none { it in CharacterMemoryTools.NAMES })
    }

    @Test
    fun unregisteredToolIsDroppedAndReportedInsteadOfFailingTheWholeRun() {
        // 2026-11-20 实测：expert_manage 漏登记时 project() 直接抛 IllegalArgumentException，
        // 界面变成「运行出错，请重试。（代码：Missing tool requirements: expert_manage）」，
        // 普通对话和手动压缩一起不可用。漏登记只能影响那一个工具，不能拖垮整轮。
        val tools = JSONArray()
            .put(JSONObject().put("function", JSONObject().put("name", "new_tool")))
            .put(JSONObject().put("function", JSONObject().put("name", "memory_get")))
        val projected = AgentToolRequirements.project(tools, rootAvailable = true)
        assertEquals(setOf("memory_get"), projected.names())
        assertEquals(setOf("new_tool"), AgentToolRequirements.lastSkippedTools)
        // 执行侧同样不能抛：未登记工具一律按「需要 Root」拒绝执行。
        assertTrue(AgentToolRequirements.rootDenied("new_tool", JSONObject(), rootAvailable = false))
        assertFalse(AgentToolRequirements.rootDenied("new_tool", JSONObject(), rootAvailable = true))
    }

    @Test
    fun rootlessProjectionRemovesPrivilegedToolsAndNarrowsMixedSchemasWithoutMutatingSource() {
        val original = catalog(root = true)
        val projected = AgentToolRequirements.project(original, rootAvailable = false)
        val required = AgentToolRequirements.toolNames.filter {
            AgentToolRequirements.rootRequirement(it) == RootRequirement.REQUIRED
        }
        assertTrue(required.isNotEmpty())
        assertTrue(required.none { it in projected.names() })
        assertTrue(setOf("terminal", "terminal_job", "terminal_daemon", "read_file", "read_image", "observe_screen", "browser_use").all {
            it in projected.names()
        })
        assertEquals("[\"user\"]", projected.properties("terminal").getJSONObject("identity").getJSONArray("enum").toString())
        assertEquals(2, original.properties("terminal").getJSONObject("identity").getJSONArray("enum").length())
        assertFalse(projected.properties("press_key").getJSONObject("button").getJSONArray("enum").toString().contains("PASTE"))
        assertFalse(projected.toString().contains("/data/local/tmp/eta"))
        assertFalse(projected.properties("read_image").getJSONObject("path").toString().contains("Root"))
    }

    @Test
    fun ordinaryAuthorizationIsIndependentFromRootAndForegroundIntentsDoNotNeedAccessibility() {
        val restricted = AgentToolCapabilities(
            rootAvailable = false, accessibilityAvailable = false,
            notificationsAllowed = false, usageAllowed = false, locationAllowed = false, colorOs = false,
        )
        val names = restricted.project(catalog(root = true)).names()
        assertTrue(setOf("launch_app", "open_uri", "terminal", "browser_use").all { it in names })
        assertTrue(setOf("observe_screen", "wait_for_text", "wait_for_package", "recent_notifications", "app_usage_summary", "get_current_location").none { it in names })
        assertEquals("ROOT_REQUIRED", restricted.unavailableCode("search_coloros_notes"))
        assertEquals("DEVICE_UNSUPPORTED", restricted.copy(rootAvailable = true).unavailableCode("search_coloros_notes"))
        assertEquals(null, restricted.copy(notificationsAllowed = true).unavailableCode("recent_notifications"))
        assertEquals("NOTIFICATION_ACCESS_REQUIRED", restricted.copy(rootAvailable = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(rootAvailable = true, colorOs = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(accessibilityAvailable = true).unavailableCode("observe_screen"))
        assertEquals(null, restricted.copy(accessibilityRecoveryAvailable = true).unavailableCode("observe_screen"))
    }

    @Test
    fun rootOnlyToolsAndPrivilegedKeysAreDeniedWithoutRoot() {
        assertTrue(AgentToolRequirements.rootDenied("press_key", JSONObject().put("button", "PASTE"), false))
        assertTrue(AgentToolRequirements.rootDenied("set_setting", JSONObject(), false))
        assertFalse(AgentToolRequirements.rootDenied("set_setting", JSONObject(), true))
    }

    @Test
    fun frameworkConnectionDoesNotGrantRootAndRootSnapshotDoesNotRequireFramework() {
        assertEquals(LsposedRequirement.OPTIONAL, AgentToolRequirements.find("search_coloros_memories")?.lsposedRequirement)
        assertEquals("ROOT_REQUIRED", AgentToolCapabilities(rootAvailable = false, lsposedAvailable = true)
            .unavailableCode("search_coloros_memories"))
        assertEquals(null, AgentToolCapabilities(rootAvailable = true, lsposedAvailable = false)
            .unavailableCode("search_coloros_memories"))
    }

    @Test
    fun indexedSearchNeedsRootAndColorOsButDoesNotRequireAnXposedConnection() {
        assertEquals("ROOT_REQUIRED", AgentToolCapabilities(rootAvailable = false, colorOs = true)
            .unavailableCode("search_bills"))
        assertEquals("DEVICE_UNSUPPORTED", AgentToolCapabilities(rootAvailable = true, colorOs = false)
            .unavailableCode("search_bills"))
        assertEquals(null, AgentToolCapabilities(rootAvailable = true, colorOs = true, lsposedAvailable = false)
            .unavailableCode("search_bills"))
    }

    @Test
    fun vivoExposesOnlyAdaptedPersonalSourcesAndStillRequiresRoot() {
        val vivo = AgentToolCapabilities(rootAvailable = true, colorOs = false, vivo = true)
        for (name in listOf("search_notes", "search_todos", "read_personal_item")) {
            assertEquals(null, vivo.unavailableCode(name))
            assertEquals("ROOT_REQUIRED", vivo.copy(rootAvailable = false).unavailableCode(name))
        }
        for (name in listOf("search_bills", "search_system_memories", "search_coloros_recordings", "create_note", "delete_note")) {
            assertEquals("DEVICE_UNSUPPORTED", vivo.unavailableCode(name))
        }
    }

    private fun catalog(root: Boolean) = AgentToolCatalog.build(
        terminalTools = true, browserTools = true, deviceDirectTools = true,
        deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
        capabilities = AgentToolCapabilities(rootAvailable = root),
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.properties(name: String): JSONObject = (0 until length())
        .map { getJSONObject(it).getJSONObject("function") }
        .single { it.getString("name") == name }.getJSONObject("parameters").getJSONObject("properties")
}
