package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.tool.AgentToolAvailability
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray

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
        return if (restrictedToolNames == null) {
            policyTools
        } else JSONArray().also { filtered ->
            for (index in 0 until policyTools.length()) {
                val schema = policyTools.getJSONObject(index)
                if (schema.optJSONObject("function")?.optString("name") in restrictedToolNames) filtered.put(schema)
            }
        }
    }
}
