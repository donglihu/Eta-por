package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolAvailability
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        /** 工具能力页关掉的工具名；空集合表示全开。 */
        disabledTools: Set<String> = emptySet(),
    ): JSONArray {
        val projected = capabilities.project(JSONArray().also { tools ->
            // 工具档只剩「正常」与「纯聊天」：纯聊天在 AgentToolDirectory 里已直接返回空数组，
            // 走到这里的都是正常档，全部工具原样声明，不再按档位删减。
            // 计划清单：只有启用计划模式的会话才声明。
            //
            // 以前无条件追加，模型在非计划模式下也会照着工具描述「先提交计划清单再执行」
            // 去调 update_plan，于是没勾开关也冒出任务待办面板，且执行侧没有任何模式校验
            // 能拦住它（update_plan 登记为 NONE、只读白名单里也放行）。
            // 这不是 2026-11-18「不按 plan 阶段硬过滤」的反悔：那条讲的是**阶段**（同一轮
            // 里交清单前后工具目录不变），这里过滤的是**开关**（本 run 压根不是计划 run）。
            if (capabilities.planMode) AgentPlanToolCatalog.appendTo(tools)
            AgentContextAppToolCatalog.appendTo(tools)
            // 主动提问：模型自己需要用户拍板时用，和系统拦截共用同一张卡。
            AgentAskToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools, includeSearch = localWebSearch)
                AgentBrowserToolCatalog.appendTo(tools)
            }
            // 专家管理：会话里直接建/改/召唤专家，是「创建接口」的落点。
            AgentExpertToolCatalog.appendTo(tools)
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
                authoring = memoryWritable,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            if (memoryWritable) AgentTaskToolCatalog.appendTo(tools)
            if (deviceDirectTools) AgentVirtualScreenToolCatalog.appendTo(tools)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
                AgentFileToolCatalog.appendTo(tools)
            }
        })
        // 逐工具开关放在能力投影之后：先按 Root/无障碍/机型筛掉不可用的，再按用户意愿删减，
        // 顺序反过来会让「关掉的工具」在不可用统计里继续占位。
        if (disabledTools.isEmpty()) return projected
        return JSONArray().also { filtered ->
            for (index in 0 until projected.length()) {
                val tool = projected.optJSONObject(index) ?: continue
                val name = tool.optJSONObject("function")?.optString("name").orEmpty()
                if (AgentToolAvailability.isToolEnabled(name, disabledTools)) filtered.put(tool)
            }
        }
    }
}
