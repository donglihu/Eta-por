package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.LocalToolRequirement
import io.github.mangi.eta.agent.tool.RootRequirement

/** 浏览器的几个说明卡展示同一工具的不同操作，要求仍来自实际执行工具。 */
internal fun toolCardRequirement(id: String): LocalToolRequirement {
    return requireNotNull(toolCardRequirementOrNull(id)) { "Unknown tool card: $id" }
}

/**
 * 工具卡的执行要求；未登记的工具卡（例如本版本还没集成后端的 Frida）返回 null。
 *
 * 有单独的实现而不是让调用方吃异常：能力页是「列出全部能力」的地方，一张还没接上后端的
 * 卡不该把整页投影打崩。
 */
internal fun toolCardRequirementOrNull(id: String): LocalToolRequirement? =
    AgentToolRequirements.find(actualToolName(id))

/**
 * 观察与注入合并后的 Frida 卡。
 *
 * 后端（gadget/agent.js）尚未移植到本版本，所以它没有执行要求登记，也不会出现在模型目录里；
 * 但能力页必须让用户看到「这张卡存在、当前不可用」，否则合并结果等于凭空消失。
 */
internal const val FRIDA_TOOL_CARD_ID = "frida"

internal fun visibleOnCurrentDevice(id: String, rootGranted: Boolean, colorOs: Boolean): Boolean {
    // 合并卡没有后端可跑，本机永远可展示（只是不可开关），不走能力口径过滤。
    if (id == FRIDA_TOOL_CARD_ID) return true
    // 查不到执行要求的卡片无法判断本机能不能用，一律只在「全部能力」里出现。
    val requirement = toolCardRequirementOrNull(id) ?: return false
    return (rootGranted || requirement.rootRequirement != RootRequirement.REQUIRED) &&
        (colorOs || !requirement.colorOs)
}

internal fun actualToolName(id: String): String = when (id) {
    "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
    else -> id
}

internal fun projectToolGroups(groups: List<ToolGroupUi>, showAll: Boolean, rootGranted: Boolean, colorOs: Boolean): List<ToolGroupUi> =
    groups.map { group ->
        group.copy(tools = group.tools.filter { showAll || visibleOnCurrentDevice(it.id, rootGranted, colorOs) })
    }.filter { it.tools.isNotEmpty() }

/** 普通权限的缺失优先给出可执行的授权入口，查看增强说明本身不会请求 Root。 */
internal fun toolCardAction(id: String, capabilities: AgentToolCapabilities): AgentToolsAction? {
    val requirement = toolCardRequirementOrNull(id) ?: return null
    return when (capabilities.unavailableCode(actualToolName(id))) {
        "ROOT_REQUIRED", "DEVICE_UNSUPPORTED" -> AgentToolsAction.OpenEnhancements
        null -> when {
            id == "web_search" || id == "fetch_url" -> AgentToolsAction.OpenWebToolsSettings
            id.startsWith("browser_") -> AgentToolsAction.OpenBrowser
            !capabilities.rootAvailable && requirement.rootRequirement == RootRequirement.PARTIAL ->
                AgentToolsAction.OpenEnhancements
            else -> null
        }
        else -> AgentToolsAction.OpenPermissions
    }
}
