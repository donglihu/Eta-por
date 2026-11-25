package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirementOrNull
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单个工具一行：左侧图标，中间标题 + 摘要 + 状态徽标，右侧开关与说明入口。
 *
 * 早先是 150dp 自适应网格里的方形卡片（一屏两列小卡），信息被压缩成两行截断文本、
 * 开关挤在卡片右上角，扫一列工具要来回跳视线；改成一列一行后每个工具的开关位置固定，
 * 组内用分割线串成设置页式的清单。
 */
@Composable
internal fun ToolRow(
    tool: ToolItemUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDescription by remember(tool.id) { mutableStateOf(false) }
    val description = if (!rootGranted && tool.id == "terminal") {
        stringResource(R.string.capability_terminal_ordinary_summary)
    } else {
        tool.summary
    }
    val requirementText = toolRequirementText(tool.id, rootGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionText = when (action) {
        AgentToolsAction.OpenWebToolsSettings -> stringResource(R.string.web_tools_configure_action)
        AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
        AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
        AgentToolsAction.OpenEnhancements -> stringResource(R.string.tools_view_enhancements)
        else -> stringResource(R.string.ui_view_description)
    }
    // 不可用原因优先：它比权限要求更根本，两个都显示会把一行挤成三行。
    val badgeText = tool.unavailableReason ?: requirementText
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { if (action != null) onAction(action) else showDescription = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EtaPreferenceIcon(
            icon = iconForTool(tool.id),
            tint = EtaPreferenceColors.Green,
            enabled = tool.enabled,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = tool.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            badgeText?.let {
                Spacer(Modifier.height(6.dp))
                ToolBadge(it)
            }
            if (action != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = actionText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 逐工具开关：不可用（后端未集成）或运行时骨架工具置灰，但仍展示当前真实状态。
        EtaSwitch(
            checked = tool.enabled,
            onCheckedChange = { onAction(AgentToolsAction.ToggleTool(tool.id, it)) },
            enabled = tool.switchable,
        )
        IconButton(onClick = { showDescription = true }) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = stringResource(R.string.ui_description_named, tool.title),
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
    if (showDescription) {
        ItemDescriptionDialog(
            title = tool.title,
            description = listOfNotNull(description, tool.unavailableReason, requirementText).joinToString("\n\n"),
            onDismiss = { showDescription = false },
        )
    }
}

/** 行内的状态徽标：圆角灰底小字，比正文弱一档，避免和摘要抢注意力。 */
@Composable
private fun ToolBadge(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .squircleSurface(color = MiuixTheme.colorScheme.surfaceVariant, cornerRadius = 8.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun toolRequirementText(id: String, rootGranted: Boolean, capabilities: AgentToolCapabilities): String? {
    // 未登记执行要求的卡片（例如还没接后端的 Frida）没有权限可讲，交给 unavailableReason 说明。
    val requirement = toolCardRequirementOrNull(id) ?: return null
    val unavailableCode = capabilities.unavailableCode(actualToolName(id))
    return when {
        !rootGranted && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
        !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
        unavailableCode == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
        unavailableCode == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
        unavailableCode == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
        requirement.colorOs -> stringResource(R.string.capability_coloros_required)
        !rootGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
        else -> null
    }
}