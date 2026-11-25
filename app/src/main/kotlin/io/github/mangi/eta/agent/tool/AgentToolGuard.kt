package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import org.json.JSONObject

/**
 * 执行侧的逐工具开关守卫。
 *
 * 目录侧（[io.github.mangi.eta.agent.model.AgentToolDirectory]）已经不声明被关掉的工具，
 * 但「不声明」不等于「执行不了」：历史消息里留着的调用、专家白名单、子智能体的 sub 调用、
 * MCP 同名工具、以及模型凭记忆硬调，都会绕过目录直接落到执行链上。
 *
 * 所以每个 ToolExecutor 入口的第一步都过这一关，且拒绝码/文案只在这里定义一份，
 * 避免几处各写一遍之后口径漂移（UI 与提示词都按 code 判读）。
 */
internal object AgentToolGuard {

    const val CODE_TOOL_DISABLED = "TOOL_DISABLED"

    const val MESSAGE_TOOL_DISABLED =
        "该工具已在工具能力页关闭，本次未执行；需要时在「工具能力」页重新打开，下一轮对话生效"

    /** 被关掉时返回拒绝结果；可用时返回 null，调用方继续走原分支。 */
    fun rejectionOrNull(
        toolName: String,
        disabledTools: Set<String> = Prefs.disabledTools(),
    ): AgentModelClient.ToolResult? =
        if (AgentToolAvailability.isToolEnabled(toolName, disabledTools)) null else failure()

    /** 统一的拒绝结果：与工具能力页的开关语义一一对应。 */
    fun failure(): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
        JSONObject()
            .put("ok", false)
            .put("code", CODE_TOOL_DISABLED)
            .put("message", MESSAGE_TOOL_DISABLED)
            .toString(),
    )
}
