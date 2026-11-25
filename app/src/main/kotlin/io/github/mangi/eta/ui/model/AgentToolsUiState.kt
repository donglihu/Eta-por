package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentToolsUiState(
    val groups: List<ToolGroupUi>,
)

@Immutable
data class ToolGroupUi(
    val id: String,
    val title: String,
    val tools: List<ToolItemUi>,
) {
    /** 组内已启用工具数；分组 chip 与组标题统一读这两个派生值，避免两处各算一遍。 */
    val enabledCount: Int get() = tools.count { it.enabled }

    val totalCount: Int get() = tools.size

    /** 有可开关工具时才显示「全部禁用／全部启用」。 */
    val switchableCount: Int get() = tools.count { it.switchable }
}

@Immutable
data class ToolItemUi(
    val id: String,
    val title: String,
    val summary: String,
    /** 逐工具开关的当前值；不可用的卡片固定为 false。 */
    val enabled: Boolean = true,
    /** 是否允许用户切换：未集成后端的卡片与运行时骨架工具都是 false。 */
    val switchable: Boolean = true,
    /** 不可用的原因；仅用于展示，不参与执行判断。 */
    val unavailableReason: String? = null,
)
