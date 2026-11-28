package io.github.mangi.eta.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.agent.git.model.GitBranch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分支 tab（1:1 克隆 aicode feature/git/presentation/component/GitBranchesTab.kt，2026-11-28 第二批）。
 *
 * 布局与交互对齐 aicode BranchesTab：
 * - BranchesOverview 卡片：当前分支 monospace + detached 红字 + 本地/远程/标签三色计数块；
 * - HEAD / 本地 (n) / 远程 (n) / 标签 (n) 四个折叠分组（本地组「+」新建分支、标签组「+」新建标签）；
 *   分支名按 `/` 层级建树渲染，文件夹节点可折叠（对齐 aicode buildBranchTree）；
 * - 分支行点击弹 RefActionSheet（本地：切换/合并/重命名/删除；远程：切换/删除；标签：切换/删除）；
 * - 弹窗族：切换确认（本地确认/远程建跟踪分支/标签 detached 警告）、新建分支 sheet（名称 + 基准分支
 *   下拉 + 创建并切换开关）、合并确认（含 --no-ff 开关）、重命名弹窗、删除本地/远程分支与标签确认；
 * - 首次进入自动 [GitPanelState.loadBranches]（状态机防抖，沿 aicode 沿触发语义）。
 *
 * Miuix 化差异：material3 AlertDialog/ExposedDropdownMenuBox → [EtaWindowDialog] 与自绘展开列表；
 * SettingsGroup → 自绘 GroupCard（surfaceContainer 圆角卡片 + 1dp 分隔线，与状态页分组观感一致）。
 */
@Composable
internal fun GitBranchesTab(
    state: GitPanelState.GitUiState,
    stateMachine: GitPanelState,
    listState: LazyListState,
) {
    // 首次进入自动加载全量分支/标签；状态机内部防抖（已加载或正在加载时直接返回）。
    LaunchedEffect(Unit) { stateMachine.loadBranches() }

    val branches = state.branches
    val tags = state.tags

    if (state.branchesLoading && branches.isEmpty() && tags.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(size = 32.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.git_loading_branches),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }
    if (branches.isEmpty() && tags.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.git_no_branches),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
            )
        }
        return
    }

    val currentBranch = branches.firstOrNull { it.current }?.name
        ?: stringResource(R.string.git_no_checked_out_branch)
    // 无当前分支即 detached HEAD（HEAD 卡片只展示参考信息，此时显示提示而非分支名）。
    val hasCurrentBranch = branches.any { it.current }
    val localBranches = remember(branches) { branches.filter { !it.remote } }
    val remoteBranches = remember(branches) { branches.filter { it.remote } }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun isExpanded(key: String): Boolean = expanded[key] ?: true

    // 弹窗族开关（纯 UI 状态，对齐 aicode 的 pending* 局部状态；确认动作才进状态机）。
    var pendingCheckout by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingRename by remember { mutableStateOf<String?>(null) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var pendingDeleteTag by remember { mutableStateOf<String?>(null) }
    var pendingMerge by remember { mutableStateOf<String?>(null) }
    var mergeNoFf by remember { mutableStateOf(false) }

    // ---------- 切换确认：本地分支确认文案 / 远程分支建跟踪分支 / 标签 detached 警告 ----------
    pendingCheckout?.let { (ref, isRemote) ->
        val isTag = tags.any { it == ref }
        GitRefConfirmDialog(
            title = stringResource(R.string.git_switch_branch),
            message = when {
                isTag -> stringResource(R.string.git_detached_head_warning, ref)
                isRemote -> stringResource(R.string.git_create_tracking_branch, ref)
                else -> stringResource(R.string.git_switch_confirm, ref)
            },
            confirmText = stringResource(R.string.git_common_switch),
            destructive = false,
            onConfirm = {
                pendingCheckout = null
                stateMachine.checkoutBranch(ref, isRemote)
            },
            onDismiss = { pendingCheckout = null },
        )
    }

    // ---------- 新建分支 sheet：名称 + 基准分支下拉（本地分支列表）+ 创建并切换开关 ----------
    if (showCreateDialog) {
        val localBranchNames = localBranches.map { it.name }
        var newName by remember { mutableStateOf("") }
        var startPoint by remember { mutableStateOf(currentBranch) }
        var checkout by remember { mutableStateOf(true) }
        var dropdownExpanded by remember { mutableStateOf(false) }
        OverlayBottomSheet(
            show = true,
            title = stringResource(R.string.git_new_branch),
            onDismissRequest = { showCreateDialog = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GitDialogField(
                    value = newName,
                    hint = stringResource(R.string.git_branch_name),
                    onValueChange = { newName = it },
                )
                // 基准分支下拉：自绘展开列表（Miuix 无 ExposedDropdownMenuBox，material3 禁用）。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dropdownExpanded = !dropdownExpanded }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.git_base_branch),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 10.sp,
                            )
                            Text(
                                text = startPoint,
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = if (dropdownExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (dropdownExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            localBranchNames.forEach { name ->
                                Text(
                                    text = name,
                                    color = if (name == startPoint) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            startPoint = name
                                            dropdownExpanded = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.git_create_and_switch),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    )
                    EtaSwitch(checked = checkout, onCheckedChange = { checkout = it })
                }
                // 创建按钮：名称非空才可用；成功后状态机自动刷新快照与分支列表。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (newName.isNotBlank()) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                        )
                        .clickable(enabled = newName.isNotBlank()) {
                            showCreateDialog = false
                            stateMachine.createBranch(newName.trim(), startPoint, checkout)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.git_common_create),
                        color = if (newName.isNotBlank()) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    // ---------- 删除本地/远程分支确认 ----------
    pendingDelete?.let { (name, isRemote) ->
        GitRefConfirmDialog(
            title = stringResource(if (isRemote) R.string.git_delete_remote_branch else R.string.git_delete_branch),
            message = if (isRemote) {
                stringResource(R.string.git_delete_remote_branch_confirm, name)
            } else {
                stringResource(R.string.git_delete_local_branch_confirm, name)
            },
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                pendingDelete = null
                if (isRemote) stateMachine.deleteRemoteBranch(name) else stateMachine.deleteBranch(name)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // ---------- 重命名弹窗 ----------
    pendingRename?.let { oldName ->
        var newName by remember(oldName) { mutableStateOf(oldName) }
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.git_rename_branch),
            onDismissRequest = { pendingRename = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.git_rename_to, oldName),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
                GitDialogField(
                    value = newName,
                    hint = stringResource(R.string.git_new_branch_name),
                    onValueChange = { newName = it },
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_rename),
                confirmEnabled = newName.isNotBlank() && newName.trim() != oldName,
                onCancel = { pendingRename = null },
                onConfirm = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotBlank() && trimmed != oldName) {
                        pendingRename = null
                        stateMachine.renameBranch(oldName, trimmed)
                    }
                },
            )
        }
    }

    // ---------- 新建标签 sheet：名称输入，指向当前 HEAD（对齐 aicode onCreateTag(name)） ----------
    if (showCreateTagDialog) {
        var tagName by remember { mutableStateOf("") }
        OverlayBottomSheet(
            show = true,
            title = stringResource(R.string.git_create_tag_title),
            onDismissRequest = { showCreateTagDialog = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.git_create_tag_desc, currentBranch),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
                GitDialogField(
                    value = tagName,
                    hint = stringResource(R.string.git_tag_name),
                    onValueChange = { tagName = it },
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.git_common_create),
                    confirmEnabled = tagName.isNotBlank(),
                    onCancel = { showCreateTagDialog = false },
                    onConfirm = {
                        val trimmed = tagName.trim()
                        if (trimmed.isNotBlank()) {
                            showCreateTagDialog = false
                            stateMachine.createTag(trimmed)
                        }
                    },
                )
            }
        }
    }

    // ---------- 删除标签确认 ----------
    pendingDeleteTag?.let { name ->
        GitRefConfirmDialog(
            title = stringResource(R.string.git_delete_tag),
            message = stringResource(R.string.git_delete_tag_confirm, name),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                pendingDeleteTag = null
                stateMachine.deleteTag(name)
            },
            onDismiss = { pendingDeleteTag = null },
        )
    }

    // ---------- 合并确认（含 --no-ff 开关） ----------
    pendingMerge?.let { targetBranch ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.git_action_merge),
            onDismissRequest = { pendingMerge = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.git_merge_confirm, targetBranch, currentBranch),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mergeNoFf = !mergeNoFf },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.git_merge_no_ff),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    )
                    EtaSwitch(checked = mergeNoFf, onCheckedChange = { mergeNoFf = it })
                }
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.git_action_merge),
                confirmEnabled = !state.busy,
                onCancel = { pendingMerge = null },
                onConfirm = {
                    val b = targetBranch
                    pendingMerge = null
                    stateMachine.mergeBranch(b, mergeNoFf)
                },
            )
        }
    }

    val localTree = remember(localBranches) { buildBranchTree(localBranches) }
    val remoteTree = remember(remoteBranches) { buildBranchTree(remoteBranches) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 70.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "overview") {
            BranchesOverview(
                currentBranch = currentBranch,
                localCount = localBranches.size,
                remoteCount = remoteBranches.size,
                tagCount = tags.size,
                hasCurrentBranch = hasCurrentBranch,
            )
        }
        item(key = "head-header") {
            RefSectionHeader(
                title = "HEAD",
                isExpanded = isExpanded("head"),
                onToggle = { expanded["head"] = !isExpanded("head") },
            )
        }
        if (isExpanded("head")) {
            item(key = "head-group") {
                GroupCard {
                    RefRow(
                        name = currentBranch,
                        subtitle = stringResource(R.string.git_checked_out),
                        icon = Icons.Rounded.History,
                        isCurrent = true,
                    )
                }
            }
        }
        if (localBranches.isNotEmpty()) {
            item(key = "local-header") {
                RefSectionHeader(
                    title = "${stringResource(R.string.git_common_local)} (${localBranches.size})",
                    isExpanded = isExpanded("local"),
                    onToggle = { expanded["local"] = !isExpanded("local") },
                    onAdd = { showCreateDialog = true },
                )
            }
            if (isExpanded("local")) {
                item(key = "local-group") {
                    GroupCard {
                        renderBranchTree(
                            nodes = localTree,
                            depth = 1,
                            expanded = expanded,
                            isRemote = false,
                            checkoutLoading = state.checkoutLoading,
                            onCheckout = { ref, remote -> pendingCheckout = ref to remote },
                            onMergeBranch = { pendingMerge = it },
                            onRenameBranch = { pendingRename = it },
                            onDeleteBranch = { pendingDelete = it to false },
                            onDeleteRemoteBranch = {},
                        )
                    }
                }
            }
        }
        if (remoteBranches.isNotEmpty()) {
            item(key = "remote-header") {
                RefSectionHeader(
                    title = "${stringResource(R.string.git_remote)} (${remoteBranches.size})",
                    isExpanded = isExpanded("remote"),
                    onToggle = { expanded["remote"] = !isExpanded("remote") },
                )
            }
            if (isExpanded("remote")) {
                item(key = "remote-group") {
                    GroupCard {
                        renderBranchTree(
                            nodes = remoteTree,
                            depth = 1,
                            expanded = expanded,
                            isRemote = true,
                            checkoutLoading = state.checkoutLoading,
                            onCheckout = { ref, remote -> pendingCheckout = ref to remote },
                            onMergeBranch = { pendingMerge = it },
                            onRenameBranch = {},
                            onDeleteBranch = {},
                            onDeleteRemoteBranch = { pendingDelete = it to true },
                        )
                    }
                }
            }
        }
        if (tags.isNotEmpty()) {
            item(key = "tags-header") {
                RefSectionHeader(
                    title = "${stringResource(R.string.git_tags)} (${tags.size})",
                    isExpanded = isExpanded("tags"),
                    onToggle = { expanded["tags"] = !isExpanded("tags") },
                    onAdd = { showCreateTagDialog = true },
                )
            }
            if (isExpanded("tags")) {
                item(key = "tags-group") {
                    GroupCard {
                        tags.forEachIndexed { index, t ->
                            if (index > 0) GroupDivider()
                            RefRow(
                                name = t,
                                subtitle = "",
                                icon = Icons.Rounded.Tag,
                                isCurrent = false,
                                isLoading = state.checkoutLoading == t,
                                actions = listOf(
                                    RefAction.switch(stringResource(R.string.git_common_switch)) { pendingCheckout = t to false },
                                    RefAction.delete(stringResource(R.string.action_delete)) { pendingDeleteTag = t },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分支页顶部概览卡片：当前分支 monospace + detached 红字 + 本地/远程/标签三色计数块
 * （本地=主色、远程=次色、标签=三色，对齐 aicode StatusMetric 配色语义）。
 */
@Composable
private fun BranchesOverview(currentBranch: String, localCount: Int, remoteCount: Int, tagCount: Int, hasCurrentBranch: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.AccountTree,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.git_current_branch),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                )
                Text(
                    text = currentBranch,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hasCurrentBranch) {
                    Text(
                        text = stringResource(R.string.git_detached_head_label),
                        color = MiuixTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RefStatusMetric(stringResource(R.string.git_common_local), localCount, MiuixTheme.colorScheme.primary, Modifier.weight(1f))
            RefStatusMetric(stringResource(R.string.git_remote), remoteCount, Color(0xFF2563EB), Modifier.weight(1f))
            RefStatusMetric(stringResource(R.string.git_tags), tagCount, Color(0xFF8B5CF6), Modifier.weight(1f))
        }
    }
}

/** 概览卡片中的单块计数：数值（彩色 monospace）+ 标签（与状态页 StatusMetric 同构）。 */
@Composable
private fun RefStatusMetric(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(text = count.toString(), color = color, fontSize = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(text = label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 10.sp, maxLines = 1)
    }
}

/** 折叠分组标题：右下箭头展开/收起 + 可选「+」按钮（本地组新建分支、标签组新建标签）。 */
@Composable
private fun RefSectionHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAdd: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            // 展开态箭头朝下、收起态朝右（对齐 aicode ExpandableChevronIcon RIGHT_DOWN 语义）。
            imageVector = if (isExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
            contentDescription = if (isExpanded) stringResource(R.string.git_common_collapse) else stringResource(R.string.git_common_expand),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (onAdd != null) {
            IconButton(onClick = onAdd, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.git_new),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 分支行副标题：当前分支标记 + 跟踪关系（upstream + ahead/behind 或「已同步」）+ 无上游提示。 */
@Composable
private fun branchSubtitle(branch: GitBranch): String {
    val parts = mutableListOf<String>()
    if (branch.current) parts.add(stringResource(R.string.git_checked_out))
    val upstream = branch.upstream
    if (upstream != null) {
        val sync = buildString {
            if (branch.ahead > 0) append(stringResource(R.string.git_ahead_count, branch.ahead))
            if (branch.behind > 0) {
                if (isNotEmpty()) append(" · ")
                append(stringResource(R.string.git_behind_count, branch.behind))
            }
        }
        parts.add(
            if (sync.isNotEmpty()) "$upstream $sync"
            else "$upstream ${stringResource(R.string.git_branch_synced)}"
        )
    } else {
        parts.add(stringResource(R.string.git_no_upstream))
    }
    return parts.joinToString(" · ")
}

/**
 * 分支/标签行可执行的操作项：label 由构建处（Composable 上下文）用 stringResource 解析好传入，
 * 渲染期不再查表——与状态页 FileAction 的解析时机一致。
 */
private class RefAction(
    val label: String,
    val icon: ImageVector,
    val isDestructive: Boolean,
    val onClick: () -> Unit,
) {
    companion object {
        /** 各操作项工厂（在 Composable 中调用以完成字符串解析）。 */
        fun switch(label: String, onClick: () -> Unit) = RefAction(label, Icons.Rounded.Check, false, onClick)
        fun merge(label: String, onClick: () -> Unit) = RefAction(label, Icons.AutoMirrored.Rounded.CallMerge, false, onClick)
        fun rename(label: String, onClick: () -> Unit) = RefAction(label, Icons.Rounded.Edit, false, onClick)
        fun delete(label: String, onClick: () -> Unit) = RefAction(label, Icons.Rounded.Delete, true, onClick)
    }
}

/** 分支/标签行：图标 + 名称 monospace + 副标题；当前分支高亮 primaryContainer；点击弹操作弹层。 */
@Composable
private fun RefRow(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    isCurrent: Boolean,
    isLoading: Boolean = false,
    indent: Int = 0,
    actions: List<RefAction> = emptyList(),
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val contentColor = if (isCurrent) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (isLoading || actions.isEmpty()) it
                else it.clickable { sheetOpen = true }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp + (indent * 16).dp, end = 16.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLoading) {
                CircularProgressIndicator(size = 18.dp)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isCurrent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = contentColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = if (isCurrent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (sheetOpen) {
        RefActionSheet(refName = name, actions = actions, onDismiss = { sheetOpen = false })
    }
}

/** 分支/标签操作弹层：标题 ref 名 + 操作项列表（破坏性操作 error 配色，对齐状态页 FileActionSheet 观感）。 */
@Composable
private fun RefActionSheet(refName: String, actions: List<RefAction>, onDismiss: () -> Unit) {
    OverlayBottomSheet(
        show = true,
        title = refName,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            actions.forEach { action ->
                val tint = if (action.isDestructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(imageVector = action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(text = action.label, color = tint, fontSize = 15.sp)
                }
            }
        }
    }
}

/** 分支树节点：中间段为虚拟文件夹（branch 可能为空），叶子段承载 [GitBranch]。 */
private data class BranchNode(
    val segment: String,
    val fullPath: String,
    var branch: GitBranch? = null,
    val children: MutableList<BranchNode> = mutableListOf(),
)

/** 按 `/` 切分分支名构建层级树；同层按段名字典序排序（对齐 aicode buildBranchTree）。 */
private fun buildBranchTree(branches: List<GitBranch>): List<BranchNode> {
    val root = BranchNode("", "", null)
    for (b in branches.sortedBy { it.name }) {
        val parts = b.name.split('/')
        var cur = root
        val path = StringBuilder()
        parts.forEachIndexed { i, part ->
            if (path.isNotEmpty()) path.append('/')
            path.append(part)
            val isLeaf = i == parts.lastIndex
            val existing = cur.children.firstOrNull { it.segment == part }
            cur = if (existing == null) {
                BranchNode(part, path.toString(), if (isLeaf) b else null).also { cur.children.add(it) }
            } else {
                if (isLeaf) existing.branch = b
                existing
            }
        }
    }
    sortBranchTree(root)
    return root.children
}

/** 同层排序：按段名（忽略大小写）字典序。 */
private fun sortBranchTree(node: BranchNode) {
    node.children.sortBy { it.segment.lowercase() }
    node.children.forEach(::sortBranchTree)
}

/** 递归向分组卡片注入分支树节点：文件夹节点可折叠，行间用分隔线。 */
@Composable
private fun ColumnScope.renderBranchTree(
    nodes: List<BranchNode>,
    depth: Int,
    expanded: MutableMap<String, Boolean>,
    isRemote: Boolean,
    checkoutLoading: String?,
    onCheckout: (String, Boolean) -> Unit,
    onMergeBranch: (String) -> Unit,
    onRenameBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onDeleteRemoteBranch: (String) -> Unit,
) {
    nodes.forEachIndexed { index, node ->
        val isFolder = node.children.isNotEmpty()
        val isOpen = expanded[node.fullPath] ?: true
        if (isFolder) {
            if (index > 0) GroupDivider()
            RefSectionHeader(
                title = node.segment,
                isExpanded = isOpen,
                onToggle = { expanded[node.fullPath] = !isOpen },
            )
        } else {
            node.branch?.let { b ->
                if (index > 0) GroupDivider()
                val actions = if (isRemote) {
                    listOf(
                        RefAction.switch(stringResource(R.string.git_common_switch)) { onCheckout(b.name, true) },
                        RefAction.merge(stringResource(R.string.git_action_merge)) { onMergeBranch(b.name) },
                        RefAction.delete(stringResource(R.string.action_delete)) { onDeleteRemoteBranch(b.name) },
                    )
                } else {
                    buildList {
                        if (!b.current) {
                            add(RefAction.switch(stringResource(R.string.git_common_switch)) { onCheckout(b.name, false) })
                            add(RefAction.merge(stringResource(R.string.git_action_merge)) { onMergeBranch(b.name) })
                        }
                        add(RefAction.rename(stringResource(R.string.action_rename)) { onRenameBranch(b.name) })
                        if (!b.current) add(RefAction.delete(stringResource(R.string.action_delete)) { onDeleteBranch(b.name) })
                    }
                }
                RefRow(
                    name = node.segment,
                    subtitle = if (isRemote) null else branchSubtitle(b),
                    icon = if (isRemote) Icons.Rounded.Cloud else Icons.Rounded.AccountTree,
                    isCurrent = b.current,
                    isLoading = checkoutLoading == b.name,
                    indent = depth,
                    actions = actions,
                )
            }
        }
        if (isFolder && isOpen) {
            renderBranchTree(node.children, depth + 1, expanded, isRemote, checkoutLoading, onCheckout, onMergeBranch, onRenameBranch, onDeleteBranch, onDeleteRemoteBranch)
        }
    }
}

/** 分组卡片容器：surfaceContainer 圆角卡片（与状态页分组观感一致）。 */
@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

/** 分组卡片内的行分隔线（1dp 背景色，与状态页 FileGroupRow 一致）。 */
@Composable
private fun GroupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.background),
    )
}

/** 分支页通用确认弹窗（切换/删除分支/删除标签）。 */
@Composable
private fun GitRefConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    EtaWindowDialog(
        show = true,
        title = title,
        summary = message,
        onDismissRequest = onDismiss,
    ) {
        MiuixDialogActions(
            confirmText = confirmText,
            destructive = destructive,
            onCancel = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

/** 弹窗/弹层输入框：surfaceContainer 底 + 圆角，placeholder 用叠加文本实现（BasicTextField 无 hint 参数）。 */
@Composable
internal fun GitDialogField(value: String, hint: String, onValueChange: (String) -> Unit, minLines: Int = 1) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = hint,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            minLines = minLines,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
