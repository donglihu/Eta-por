package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.projectToolGroups
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AgentToolsScreen(state: AgentToolsUiState, onAction: (AgentToolsAction) -> Unit, modifier: Modifier = Modifier) {
    val capabilities = rememberDeviceCapabilities()
    var showAll by rememberSaveable { mutableStateOf(false) }
    // 选中的分组 id；空串表示「全部」。用空串而不是 null，是为了让 rememberSaveable 只存 String。
    var selectedGroupId by rememberSaveable { mutableStateOf("") }
    // 两个 tab 各存一份滚动位置，来回切换不会把阅读进度冲掉。
    val currentListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    val groups = projectToolGroups(state.groups, showAll, capabilities.root.isGranted, capabilities.tools.colorOs)
    // 分组被「当前设备」隐藏掉后过滤结果会为空，这时回退到全部，避免整页空白。
    // chip 行也要跟着回退：否则「全部」显示未选中、而列表其实列的是全部，选中态与内容对不上。
    val chipSelection = selectedGroupId.takeIf { id -> groups.any { it.id == id } }.orEmpty()
    val visibleGroups = groups.filter { chipSelection.isEmpty() || it.id == chipSelection }.ifEmpty { groups }
    val enabledTotal = groups.sumOf { it.enabledCount }
    val toolTotal = groups.sumOf { it.totalCount }
    MiuixScaffold(title = stringResource(R.string.ui_tool_ability_9f0f80),
        onBack = { onAction(AgentToolsAction.NavigateBack) }, modifier = modifier) { padding, scrollBehavior, sidePadding ->
        // 单列长列表：每个工具独占一行，开关位置固定成一竖列，扫读时视线不用在两列卡片间来回跳。
        LazyColumn(
            state = if (showAll) allListState else currentListState,
            modifier = Modifier.fillMaxSize().horizontalCutoutPadding().navigationBarsPadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = sidePadding + 16.dp, end = sidePadding + 16.dp,
                top = padding.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "capability-view") {
                TabRow(tabs = listOf(stringResource(R.string.capability_current_device), stringResource(R.string.capability_all)),
                    selectedTabIndex = if (showAll) 1 else 0, onTabSelected = { showAll = it == 1 })
            }
            item(key = "group-chips") {
                ToolGroupChips(
                    groups = groups,
                    selectedGroupId = chipSelection,
                    allLabel = stringResource(R.string.tool_chip_all),
                    allEnabledCount = enabledTotal,
                    allTotalCount = toolTotal,
                    onSelect = { selectedGroupId = it },
                )
            }
            item(key = "toggle-hint") {
                Text(
                    text = stringResource(R.string.tool_toggle_next_turn),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            item(key = "capability-discovery") {
                EtaCard {
                    EtaArrowPreference(title = stringResource(R.string.capability_enhancements),
                        summary = stringResource(R.string.capability_enhancements_summary),
                        onClick = { onAction(AgentToolsAction.OpenEnhancements) })
                }
            }
            visibleGroups.forEach { group ->
                item(key = "group-" + group.id) {
                    ToolGroupHeader(group, onAction)
                }
                item(key = "group-list-" + group.id) {
                    // 一个分组的工具包在一张卡里，行间用分割线串成设置页式清单。
                    EtaPreferenceGroup(modifier = Modifier) {
                        group.tools.forEachIndexed { index, tool ->
                            ToolRow(
                                tool = tool,
                                rootGranted = capabilities.root.isGranted,
                                capabilities = capabilities.tools,
                                onAction = onAction,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            if (index != group.tools.lastIndex) {
                                EtaPreferenceDivider(hasLeading = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 组标题行：左边组名与「已启用/总数」，右边是该组的批量开关。 */
@Composable
private fun ToolGroupHeader(group: ToolGroupUi, onAction: (AgentToolsAction) -> Unit) {
    val switchable = group.tools.filter { it.switchable }
    val allEnabled = switchable.isNotEmpty() && switchable.all { it.enabled }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = group.enabledCount.toString() + "/" + group.totalCount,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        Spacer(Modifier.weight(1f))
        if (switchable.isNotEmpty()) {
            Text(
                text = stringResource(
                    if (allEnabled) R.string.tool_group_disable_all else R.string.tool_group_enable_all,
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onAction(AgentToolsAction.ToggleGroup(group.id, !allEnabled)) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 分组 chip 条：「全部 e/t」+ 每组「组名 e/t」。
 *
 * 计数取投影后的工具，和下面真的列出来的行一致；用未投影的数字会出现「chip 说 6/6、
 * 列表里只有 3 行」的错位。
 */
@Composable
private fun ToolGroupChips(
    groups: List<ToolGroupUi>,
    selectedGroupId: String,
    allLabel: String,
    allEnabledCount: Int,
    allTotalCount: Int,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "all") {
            ToolGroupChip(
                text = allLabel + " " + allEnabledCount + "/" + allTotalCount,
                selected = selectedGroupId.isEmpty(),
                onClick = { onSelect("") },
            )
        }
        groups.forEach { group ->
            item(key = group.id) {
                ToolGroupChip(
                    text = group.title + " " + group.enabledCount + "/" + group.totalCount,
                    selected = selectedGroupId == group.id,
                    onClick = { onSelect(group.id) },
                )
            }
        }
    }
}

@Composable
private fun ToolGroupChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .squircleSurface(
                color = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surfaceVariant,
                cornerRadius = 16.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}