package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具按需加载：分组表覆盖完整性 + 目录 hold + 转发解包 + 开组状态。
 *
 * 与 [AgentToolDirectoryDisabledToolsTest] 同一套构造方式：刻意不加 `@RunWith`，
 * 这些类都不碰 Android，纯 JVM 才不会被 arm64 上的 Robolectric 静默跳过。
 */
class AgentToolGroupsTest {

    @Test
    fun everyDirectoryToolBelongsToExactlyOneGroup() {
        val names = fullDirectory().names()
        val ungrouped = AgentToolGroups.ungrouped(names)
        assertEquals("目录里每个工具都必须落在某一组里（新工具漏登记会在这里暴露）", emptySet<String>(), ungrouped)
        names.forEach { name ->
            val groups = groupsContaining(name)
            assertEquals("$name 只能属于一个组，实际：$groups", 1, groups.size)
        }
    }

    @Test
    fun residentGroupCoversToolingThePromptNames() {
        // 提示词直接点名这些工具（技能索引段、记忆段、计划段），延迟它们会造成
        // 「提示词教模型用、请求里没有」的错位。
        val mentioned = setOf(
            "ask_user", "update_plan", "get_current_context", "memory_get", "memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_manage",
            "subagent", "subagent_profile", "expert_manage",
        )
        mentioned.forEach { name ->
            assertEquals("$name 必须常驻", AgentToolGroups.BASIC, AgentToolGroups.groupOf(name))
        }
    }

    @Test
    fun deferredGroupsNeverContainEntryOrResidentTools() {
        val deferred = AgentToolGroups.deferredGroups.flatMap { it.toolNames }.toSet()
        AgentToolGroupCatalog.entryToolNames.forEach { name ->
            assertFalse("$name 是机制入口，不能落进延迟组", name in deferred)
        }
        AgentToolGroups.members(AgentToolGroups.BASIC).orEmpty().forEach { name ->
            assertFalse("$name 是常驻工具，不能同时在延迟组", name in deferred)
        }
    }

    @Test
    fun offModeDirectoryKeepsEveryToolAndDeclaresNoEntry() {
        // OFF 档就是改动前的目录：延迟组工具照旧全量声明，两个入口一个都不加。
        val names = fullDirectory().names()
        listOf("tap", "terminal", "read_file", "observe_screen", "set_alarm").forEach { name ->
            assertTrue("$name 在 OFF 档必须与改动前一样声明", name in names)
        }
        AgentToolGroupCatalog.entryToolNames.forEach { name ->
            assertFalse("OFF 档不追加机制入口：$name", name in names)
        }
    }

    @Test
    fun holdIsOnlyEverAnExtraStepOnTopOfTheFullDirectory() {
        // OFF 档 = 全量目录，一个字节都不变；hold 是在它之上再摘一次。
        val full = fullDirectory().names()
        val held = LinkedHashMap<String, JSONObject>()
        val afterHold = AgentToolDirectory.holdDeferredGroups(fullDirectory(), held).names()
        assertTrue("hold 只减不增：$afterHold", afterHold.all { it in full || it in AgentToolGroupCatalog.entryToolNames })
        assertTrue(afterHold.containsAll(full - AgentToolGroups.toolNames))
    }

    @Test
    fun holdRemovesDeferredToolsAndAppendsTheTwoEntries() {
        val held = LinkedHashMap<String, JSONObject>()
        val names = AgentToolDirectory.holdDeferredGroups(fullDirectory(), held).names()

        // 延迟组的工具都从目录里消失，但 schema 被留住（内层参数校验要用）。
        assertFalse("tap" in names)
        assertFalse("terminal" in names)
        assertFalse("read_file" in names)
        assertNotNull(held["tap"])
        assertNotNull(held["terminal"])

        // 常驻工具照旧声明（update_plan 只在 planMode 为真的目录里，见 AgentToolCatalog）。
        assertTrue("skills_read" in names)
        assertTrue("ask_user" in names)
        assertTrue("memory_get" in names)

        // 两个入口必须有，否则机制不可用。
        assertTrue(AgentToolGroupCatalog.USE_GROUP in names)
        assertTrue(AgentToolGroupCatalog.CALL in names)

        // hold 之后目录明显变小：这就是首轮前缀的收益来源。
        assertTrue(
            "hold 后目录应显著变小：${names.size} vs ${fullDirectory().names().size}",
            names.size * 2 < fullDirectory().names().size,
        )
    }

    @Test
    fun holdNeverTouchesMcpOrHostTools() {
        val extra = JSONArray().put(toolSchema("mcp_server_search_012345"))
        val full = directory(additionalTools = extra)
        val names = AgentToolDirectory.holdDeferredGroups(full, LinkedHashMap()).names()
        assertTrue("第三方工具体积不可控、生命周期随 server，不参与 hold", "mcp_server_search_012345" in names)
    }

    @Test
    fun entryToolsFollowThePerToolSwitch() {
        val full = fullDirectory()
        // 能力页把某个入口关掉后不能继续声明它：否则「关了还在跑」的侧门又开一条。
        val names = AgentToolDirectory.holdDeferredGroups(
            full,
            LinkedHashMap(),
            disabledTools = setOf(AgentToolGroupCatalog.CALL),
        ).names()
        assertFalse(AgentToolGroupCatalog.CALL in names)
        assertTrue(AgentToolGroupCatalog.USE_GROUP in names)

        val both = AgentToolDirectory.holdDeferredGroups(
            full,
            LinkedHashMap(),
            disabledTools = setOf(AgentToolGroupCatalog.USE_GROUP, AgentToolGroupCatalog.CALL),
        ).names()
        assertFalse(AgentToolGroupCatalog.USE_GROUP in both)
        assertFalse(AgentToolGroupCatalog.CALL in both)
    }

    @Test
    fun firstRoundPayloadDropIsMeasuredNotGuessed() {
        val full = fullDirectory()
        val held = AgentToolDirectory.holdDeferredGroups(fullDirectory(), LinkedHashMap())
        val fullChars = full.toString().length
        val heldChars = held.toString().length
        val ratio = heldChars.toDouble() / fullChars
        println("tools_budget full=$fullChars chars (${full.names().size} tools) " +
            "group=$heldChars chars (${held.names().size} tools) ratio=${"%.3f".format(ratio)}")
        // 验收线：无 MCP 目录下首轮相对 OFF 档下降 ≥ 50%。
        assertTrue(
            "首轮 tools 前缀应下降一半以上，实际 $heldChars / $fullChars = ${"%.3f".format(ratio)}",
            ratio <= 0.5,
        )
    }

    @Test
    fun forwardedCallTakesTheInnerToolResourceDomain() {
        val inner = (AgentToolGroupCatalog.resolve(
            toolCall(AgentToolGroupCatalog.CALL, """{"tool":"terminal","arguments":"{}"}"""),
            openedGroups = setOf("terminal"),
        ) as AgentToolGroupCatalog.EffectiveCall.Forwarded).inner
        // 资源闸门必须按真名算槽位：用 tool_call 算会落到 DEVICE:fallback，
        // 让屏幕操作与文件读写互相排队。
        val ticket = AgentResourceGate.ticketOf(inner.name, JSONObject(), "conv")
        assertEquals(io.github.mangi.eta.agent.tool.ToolDomain.SHELL, ticket.domain)
        assertTrue("槽位应按真名算：${ticket.key}", ticket.key.orEmpty().startsWith("SHELL:"))
    }

    // —— 转发解包 ——

    @Test
    fun directCallsPassThroughUntouched() {
        val call = toolCall("observe_screen", "{}")
        val resolved = AgentToolGroupCatalog.resolve(call, openedGroups = emptySet())
        assertEquals(AgentToolGroupCatalog.EffectiveCall.Direct(call), resolved)
    }

    @Test
    fun forwardingRequiresTheGroupToBeOpen() {
        val call = toolCall(
            AgentToolGroupCatalog.CALL,
            """{"tool":"tap","arguments":"{\"x\":1,\"y\":2}"}""",
        )
        val closed = AgentToolGroupCatalog.resolve(call, openedGroups = emptySet())
        assertTrue(closed is AgentToolGroupCatalog.EffectiveCall.Rejected)
        assertEquals("TOOL_GROUP_NOT_OPEN", (closed as AgentToolGroupCatalog.EffectiveCall.Rejected).code)

        val opened = AgentToolGroupCatalog.resolve(call, openedGroups = setOf("screen"))
        assertTrue(opened is AgentToolGroupCatalog.EffectiveCall.Forwarded)
        opened as AgentToolGroupCatalog.EffectiveCall.Forwarded
        assertEquals("tap", opened.inner.name)
        assertEquals("""{"x":1,"y":2}""", opened.inner.argumentsJson)
        assertEquals("screen", opened.group)
        // 内层沿用 outer 的 id：会话与界面都按 tool_call_id 配对结果。
        assertEquals(call.id, opened.inner.id)
    }

    @Test
    fun forwardingRejectsResidentUnknownAndMalformedCalls() {
        fun reject(tool: String, arguments: String): String {
            val call = toolCall(
                AgentToolGroupCatalog.CALL,
                """{"tool":"$tool","arguments":"$arguments"}""",
            )
            val resolved = AgentToolGroupCatalog.resolve(call, openedGroups = setOf("screen", "file"))
            assertTrue("$tool 应被拒绝：$resolved", resolved is AgentToolGroupCatalog.EffectiveCall.Rejected)
            return (resolved as AgentToolGroupCatalog.EffectiveCall.Rejected).code
        }
        assertEquals("TOOL_NOT_DEFERRED", reject("update_plan", "{}"))
        assertEquals("UNKNOWN_TOOL", reject("make_coffee", "{}"))
        // 内层参数是不是合法 JSON 不在这里判：解包后由 AgentLoop 的校验器与二次解析负责，
        // 这里只保证「组开没开、工具认不认识」。

        val broken = AgentToolGroupCatalog.resolve(
            toolCall(AgentToolGroupCatalog.CALL, "not-json"),
            openedGroups = setOf("screen"),
        )
        assertTrue(broken is AgentToolGroupCatalog.EffectiveCall.Rejected)
        assertEquals("INVALID_TOOL_ARGUMENTS", (broken as AgentToolGroupCatalog.EffectiveCall.Rejected).code)
    }

    @Test
    fun disabledToolIsRejectedWithTheSameCodeAsOffModeDirectCalls() {
        // OFF 档直调被关掉的工具 → AgentToolGuard.CODE_TOOL_DISABLED；
        // GROUP 档经 tool_call 转发同一个工具必须是同一个码，否则模型会读到两套说法。
        val rejected = AgentToolGroupCatalog.resolve(
            toolCall(AgentToolGroupCatalog.CALL, """{"tool":"tap","arguments":"{}"}"""),
            openedGroups = setOf("screen"),
            disabledTools = setOf("tap"),
        )
        assertEquals(
            io.github.mangi.eta.agent.tool.AgentToolGuard.CODE_TOOL_DISABLED,
            (rejected as AgentToolGroupCatalog.EffectiveCall.Rejected).code,
        )
        // 没被关的工具照常转发。
        val allowed = AgentToolGroupCatalog.resolve(
            toolCall(AgentToolGroupCatalog.CALL, """{"tool":"tap","arguments":"{}"}"""),
            openedGroups = setOf("screen"),
            disabledTools = setOf("terminal"),
        )
        assertTrue(allowed is AgentToolGroupCatalog.EffectiveCall.Forwarded)
    }

    @Test
    fun innerArgumentsAcceptObjectAndMissingForms() {
        val asObject = AgentToolGroupCatalog.resolve(
            toolCall(AgentToolGroupCatalog.CALL, """{"tool":"tap","arguments":{"x":1}}"""),
            openedGroups = setOf("screen"),
        )
        assertEquals(
            """{"x":1}""",
            (asObject as AgentToolGroupCatalog.EffectiveCall.Forwarded).inner.argumentsJson,
        )

        val missing = AgentToolGroupCatalog.resolve(
            toolCall(AgentToolGroupCatalog.CALL, """{"tool":"tap"}"""),
            openedGroups = setOf("screen"),
        )
        assertEquals(
            "{}",
            (missing as AgentToolGroupCatalog.EffectiveCall.Forwarded).inner.argumentsJson,
        )
    }

    // —— 开组状态 ——

    @Test
    fun openingIsIdempotentAndCappedPerRun() {
        val session = AgentToolGroupSession()
        assertNull(session.open("screen"))
        assertNull("重复打开同一组幂等，不占配额", session.open("screen"))
        assertNull(session.open("file"))
        assertNull(session.open("terminal"))
        assertTrue(session.openedGroups().containsAll(setOf("screen", "file", "terminal")))

        val over = session.open("device")
        assertNotNull(over)
        assertTrue(over!!.contains("TOOL_GROUP_OPEN_LIMIT"))
        assertEquals("超限的组不能被悄悄记成已打开", setOf("screen", "file", "terminal"), session.openedGroups())
    }

    @Test
    fun openingRejectsUnknownAndResidentGroups() {
        val session = AgentToolGroupSession()
        assertTrue(session.open("no_such_group")!!.contains("UNKNOWN_TOOL_GROUP"))
        assertTrue(session.open(AgentToolGroups.BASIC)!!.contains("UNKNOWN_TOOL_GROUP"))
        assertTrue(session.openedGroups().isEmpty())
    }

    @Test
    fun openingReceiptListsMembersAndCallVia() {
        val receipt = JSONObject(AgentToolGroupCatalog.openedReceipt("terminal", setOf("terminal", "get_logcat")))
        assertTrue(receipt.getBoolean("ok"))
        assertEquals("terminal", receipt.getString("group"))
        assertEquals(listOf("get_logcat", "terminal"), receipt.getJSONArray("tools").toStringList())
        assertEquals(AgentToolGroupCatalog.CALL, receipt.getString("call_via"))
    }

    @Test
    fun directCallToADeferredToolTellsTheModelWhichGroupToOpen() {
        val (code, message) = AgentToolGroupCatalog.notAvailableMessage("tap")
        assertEquals("TOOL_GROUP_NOT_OPEN", code)
        assertTrue(message.contains(AgentToolGroupCatalog.USE_GROUP))
        assertTrue(message.contains(AgentToolGroupCatalog.CALL))
        assertTrue(message.contains("screen"))

        // 常驻或未登记的工具仍走原来的通用文案。
        assertEquals(
            "TOOL_NOT_AVAILABLE",
            AgentToolGroupCatalog.notAvailableMessage("update_plan").first,
        )
        assertEquals(
            "TOOL_NOT_AVAILABLE",
            AgentToolGroupCatalog.notAvailableMessage("make_coffee").first,
        )
    }

    @Test
    fun indexMessageListsOnlyDeferredGroups() {
        val index = AgentToolGroups.indexMessage()
        AgentToolGroups.deferredGroups.forEach { group ->
            assertTrue("索引缺少组 ${group.id}", index.contains(group.id))
        }
        // 常驻组不该出现在索引里：它没有「需要打开」这一步。
        assertFalse(index.contains("- ${AgentToolGroups.BASIC}："))
        assertTrue(index.contains(AgentToolGroupCatalog.USE_GROUP))
        assertTrue(index.contains(AgentToolGroupCatalog.CALL))
    }

    @Test
    fun loadingPolicyFallsBackToProviderGrayscale() {
        assertEquals(
            false,
            AgentToolGroupLoadingPolicy.enabled("anthropic", io.github.mangi.eta.config.Prefs.Keys.TOOL_GROUP_LOADING_OFF),
        )
        assertEquals(
            true,
            AgentToolGroupLoadingPolicy.enabled("custom", io.github.mangi.eta.config.Prefs.Keys.TOOL_GROUP_LOADING_GROUP),
        )
        // 未写配置时按 providerType 灰度：custom 覆盖各类自建中转，先不纳入。
        assertEquals(false, AgentToolGroupLoadingPolicy.enabled("custom", "unknown-mode"))
        assertEquals(true, AgentToolGroupLoadingPolicy.enabled("anthropic", "unknown-mode"))
    }

    // —— 工具构造 ——

    private fun groupsContaining(name: String): List<String> = AgentToolGroups.deferredGroups
        .filter { name in it.toolNames }
        .map { it.id } + listOfNotNull(
        AgentToolGroups.members(AgentToolGroups.BASIC)?.takeIf { name in it }?.let { AgentToolGroups.BASIC },
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }

    private fun toolCall(name: String, arguments: String) = AgentModelClient.ToolCall(
        id = "call-$name",
        name = name,
        argumentsJson = arguments,
    )

    private fun toolSchema(name: String) = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", name)
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())),
        )

    private fun fullDirectory(): JSONArray = directory()

    private fun directory(
        disabledTools: Set<String> = emptySet(),
        additionalTools: JSONArray? = null,
    ): JSONArray = AgentToolDirectory
        .build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            memoryWritable = true,
            capabilities = AgentToolCapabilities(rootAvailable = true),
            localWebSearch = true,
            localProfile = LocalToolProfile.FULL,
            policy = AgentToolPolicy(),
            additionalTools = additionalTools,
            disabledTools = disabledTools,
        )
        .also { tools ->
            // 子智能体与角色记忆工具由 Runtime 侧追加（不在 AgentToolCatalog 里），
            // 分组表覆盖断言必须把它们算进去，否则它俩永远是「未分组」。
            CharacterMemoryTools.appendSchemas(tools)
            AgentSubagentToolCatalog.appendSchemas(tools)
        }
}
