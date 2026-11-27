package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.tool.AgentToolAvailability
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具目录的唯一构建入口。
 *
 * 请求侧（[AgentModelClient]）与「当前生效提示词」预览共用这一份，
 * 避免两处各写一份 flag 导致"预览 19 个、实际发 34 个"这类对不上。
 */
internal object AgentToolDirectory {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean,
        deviceSensitiveReadTools: Boolean,
        deviceSensitiveActionTools: Boolean,
        skillGitHubDiscovery: Boolean,
        skillGitHubInstall: Boolean,
        memoryTools: Boolean,
        memoryWritable: Boolean,
        capabilities: AgentToolCapabilities,
        localWebSearch: Boolean,
        localProfile: LocalToolProfile,
        policy: AgentToolPolicy,
        additionalTools: JSONArray? = null,
        restrictedToolNames: Set<String>? = null,
        rewriteReply: Boolean = false,
        /** 工具能力页关掉的工具名；默认空集合（= 全开），调用方按需传 Prefs.disabledTools()。 */
        disabledTools: Set<String> = emptySet(),
    ): JSONArray {
        if (rewriteReply) return JSONArray()
        // 纯聊天档不声明任何工具，请求里也不会带 tools 字段。
        if (localProfile == LocalToolProfile.CHAT) return JSONArray()
        val tools = AgentToolCatalog.build(
            terminalTools = terminalTools,
            browserTools = browserTools,
            deviceDirectTools = deviceDirectTools,
            deviceSensitiveReadTools = deviceSensitiveReadTools,
            deviceSensitiveActionTools = deviceSensitiveActionTools,
            skillGitHubDiscovery = skillGitHubDiscovery,
            skillGitHubInstall = skillGitHubInstall,
            memoryTools = memoryTools,
            memoryWritable = memoryWritable,
            capabilities = capabilities,
            localWebSearch = localWebSearch,
            disabledTools = disabledTools,
        )
        if (additionalTools != null) {
            // additionalTools（子智能体、角色记忆、MCP 与宿主托管工具）同样要过逐工具开关：
            // 之前这里无条件 put，等于给被关掉的工具留了一条从目录直通请求的侧门。
            for (index in 0 until additionalTools.length()) {
                val tool = additionalTools.optJSONObject(index) ?: continue
                val name = tool.optJSONObject("function")?.optString("name").orEmpty()
                if (AgentToolAvailability.isToolEnabled(name, disabledTools)) tools.put(tool)
            }
        }
        val policyTools = policy.filterTools(tools)
        val selected = if (restrictedToolNames == null) {
            policyTools
        } else JSONArray().also { filtered ->
            for (index in 0 until policyTools.length()) {
                val schema = policyTools.getJSONObject(index)
                if (schema.optJSONObject("function")?.optString("name") in restrictedToolNames) filtered.put(schema)
            }
        }
        return selected
    }

    /**
     * 把延迟组里的工具从目录摘下，并把两个入口补进目录。
     *
     * **必须在所有裁剪之后调用**（能力投影 → 逐工具开关 → 工具白名单 → 子代理/专家裁剪）：
     *
     * - 顺序反过来会让「本来就不可能出现的工具」也进 hold 清单，`tool_call` 转发过来
     *   才在参数校验阶段报「不在目录里」，错误码与真实原因（被关掉/不可用）对不上；
     * - 两个入口按 [AgentToolGroupCatalog.entryToolNames] 无条件补回，因为它们是
     *   机制自身的入口：被白名单/专家裁掉就等于「目录摘了但没法调用」+「只能直调，
     *   而直调的名字又不在目录里」。安全性不靠这里兜：执行侧闸门看到的始终是解包后的真名。
     *
     * 摘下的 schema 写进 [heldSchemas]：内层参数校验要用它，否则 `tool_call` 转发过来的
     * 真名会因为没有 schema 被判成「不在目录里」。
     */
    fun holdDeferredGroups(
        tools: JSONArray,
        heldSchemas: MutableMap<String, JSONObject>,
        disabledTools: Set<String> = emptySet(),
    ): JSONArray {
        val entryNames = AgentToolGroupCatalog.entryToolNames
        val kept = JSONArray()
        for (index in 0 until tools.length()) {
            val schema = tools.optJSONObject(index) ?: continue
            val function = schema.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank() || name in entryNames) continue
            val group = AgentToolGroups.groupOf(name)
            // 常驻组、未分组工具、MCP 与宿主追加工具一律保留：它们要么每轮都要，
            // 要么不在分组表里（第三方 schema 体积不可控，不参与 hold）。
            if (group == null || group == AgentToolGroups.BASIC) {
                kept.put(schema)
                continue
            }
            heldSchemas[name] = function.optJSONObject("parameters") ?: JSONObject()
        }
        // 两个入口也要过逐工具开关：用户在能力页把它们关掉后仍声明，
        // 「关了还在跑」的侧门就又开了一条（与 additionalTools 那一路同一个理由）。
        AgentToolGroupCatalog.appendSchemas(
            kept,
            includeUseGroup = AgentToolAvailability.isToolEnabled(AgentToolGroupCatalog.USE_GROUP, disabledTools),
            includeCall = AgentToolAvailability.isToolEnabled(AgentToolGroupCatalog.CALL, disabledTools),
        )
        return kept
    }
}
