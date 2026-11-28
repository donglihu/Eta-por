package io.github.mangi.eta.ui.git

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.io.File
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.git.model.GitFileChange
import io.github.mangi.eta.agent.git.model.GitStatus
import io.github.mangi.eta.agent.git.model.GitStash
import io.github.mangi.eta.agent.git.model.GitTab
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Git 全屏页（完全克隆 aicode feature/git/presentation/component/GitScreen.kt + GitStatusTab.kt，2026-11-28）。
 *
 * 自第四锚点层改为 AppRoute.Git 全屏页栈（顶栏胶囊第四段推入）。布局与交互 1:1 对齐 aicode：
 * - 顶栏：返回 + 两行标题（「Git」+ 当前仓库名，点仓库名弹仓库选择弹层）+ Key（凭据页，下一批）+ 刷新（busy 禁用）；
 * - 主体三态：loading 居中 spinner → notARepo（初始化/克隆/选择已有仓库）→ 正常态 HorizontalPager 三 tab
 *   （状态/分支/提交三 tab 均 1:1；分支与提交自 2026-11-28 第二批落地）；
 * - 底部悬浮页签条（[FloatingTabBar]）：状态/分支/提交三个 pill + 渐变蒙版，滚动中弱化；
 * - 状态 tab 1:1：StatusOverview 统计卡片 → 提交大按钮 → 次级操作行 → 冲突/合并横幅 →
 *   已暂存/未暂存/未跟踪三组（长按操作弹层）→ 储藏箱弹层 → 各确认弹窗。
 *
 * ETA 纪律：组件库为 Miuix（无 material3）；异步走 [GitPanelState]（AgentAppState.uiScope），
 * Composable 内无 rememberCoroutineScope——页签切换用 pendingPage + LaunchedEffect 桥接；
 * diff 全屏页本批只留 [onOpenDiff] 回调占位（route AppRoute.GitDiff 已备），下一批接入。
 */
@Composable
internal fun GitScreen(
    stateMachine: GitPanelState,
    /** 打开 diff 全屏页：[filePath] 仓库相对路径，[staged] 是否查看暂存区差异。本批占位，下一批接 AppRoute.GitDiff。 */
    onOpenDiff: (filePath: String, staged: Boolean) -> Unit = { _, _ -> },
    /** 右上角 Key 图标 → 凭据与署名页（对应 aicode git_credentials_and_identity，下一批接入）。 */
    onOpenCredentials: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by stateMachine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 进页触发首次仓库发现（状态机守卫：已加载过则复用快照）。
    LaunchedEffect(Unit) { stateMachine.ensureLoaded() }
    // toast → 系统 Toast 一次性消费。字符串在状态机生成，这里只负责展示与清位。
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            stateMachine.consumeToast()
        }
    }

    // 页面层纯 UI 开关：提交弹窗、拉取脏工作区确认、克隆弹窗。
    // 储藏箱弹层开关在 GitStatusTab 本地；确认弹窗目标（回退/删除/丢弃储藏等）由状态机持有，见 [GitPanelState]。
    var showCommitDialog by remember { mutableStateOf(false) }
    var showPullConfirm by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }

    // 三个 tab 的滚动状态统一提升到页面层，聚合出「是否正在滚动」用于底部 tab 栏滚动弱化。
    val statusListState = rememberLazyListState()
    val branchesListState = rememberLazyListState()
    val logListState = rememberLazyListState()
    val pagerState = rememberPagerState { GitTab.entries.size }
    val tabsScrolling by remember {
        derivedStateOf {
            statusListState.isScrollInProgress || branchesListState.isScrollInProgress || logListState.isScrollInProgress
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回箭头 + 两行标题（Git + 仓库名，行尾 ChevronDown，点仓库名弹选择弹层）+ 右侧图标组。
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixBackButton(onClick = onBack)
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.git_title),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                state.currentRepo?.let { repo ->
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { stateMachine.showRepoPicker() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = repoDisplayName(repo),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = stringResource(R.string.git_switch_repo),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onOpenCredentials) {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = stringResource(R.string.git_credentials_and_identity),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(21.dp),
                )
            }
            IconButton(onClick = { stateMachine.refresh() }, enabled = !state.busy) {
                Icon(
                    imageVector = Icons.Rounded.Replay,
                    contentDescription = stringResource(R.string.git_refresh),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // 局部非委托副本：loadError 才能被智能转换为非空传给错误态。
            val loadError = state.loadError
            when {
                // 仓库发现超时/失败：内容区给「加载失败 + 重试」。
                loadError != null -> LoadErrorContent(
                    message = loadError,
                    onRetry = { stateMachine.refresh() },
                )
                state.loading -> LoadingContent()
                state.notARepo -> NotARepoState(
                    busy = state.busy,
                    onInit = stateMachine::initRepo,
                    onClone = { showCloneDialog = true },
                    onPickExisting = stateMachine::showRepoPicker,
                )
                else -> {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (GitTab.entries[page]) {
                            GitTab.STATUS -> GitStatusTab(
                                state = state,
                                stateMachine = stateMachine,
                                listState = statusListState,
                                onCommit = { showCommitDialog = true },
                                // 拉取前脏工作区确认：有未提交改动时先弹确认（对齐 aicode onPull 分支）。
                                onPull = {
                                    if (state.status?.hasChanges == true) showPullConfirm = true else stateMachine.pull()
                                },
                                onStashOpen = { stateMachine.loadStashes() },
                                onOpenDiff = onOpenDiff,
                            )
                            GitTab.BRANCHES -> GitBranchesTab(
                                state = state,
                                stateMachine = stateMachine,
                                listState = branchesListState,
                            )
                            GitTab.LOG -> GitLogTab(
                                state = state,
                                stateMachine = stateMachine,
                                listState = logListState,
                                // diff 全屏页下一批接入：先按工作区未暂存差异占位转发（commitHash 参数暂不使用）。
                                onOpenCommitFileDiff = { _, filePath -> onOpenDiff(filePath, false) },
                            )
                        }
                    }
                    // 底部渐变蒙版 + 悬浮页签组：内容可滚动到屏幕底部穿过 tab 栏，被渐变遮罩（同主页输入框）。
                    // 仅仓库态显示：加载中/非仓库态时 HorizontalPager 未组合，点击 tab 无内容可切换。
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(88.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, MiuixTheme.colorScheme.background.copy(alpha = 0.92f)),
                                    ),
                                ),
                        )
                        FloatingTabBar(
                            pagerState = pagerState,
                            isScrolling = tabsScrolling,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                        )
                    }
                }
            }
        }
    }

    if (state.repoPickerVisible) {
        RepoPickerSheet(
            entries = state.repoEntries,
            currentRepo = state.currentRepo,
            onSelect = stateMachine::switchRepo,
            onDismiss = stateMachine::hideRepoPicker,
        )
    }
    if (showCloneDialog) {
        CloneRepoDialog(
            workspaceRoot = state.workspaceRoot,
            busy = state.busy,
            onDismiss = { showCloneDialog = false },
            onClone = { url, dir ->
                showCloneDialog = false
                stateMachine.cloneRepo(url, dir)
            },
        )
    }
    if (showPullConfirm) {
        GitConfirmDialog(
            show = true,
            title = stringResource(R.string.git_pull),
            message = stringResource(R.string.git_pull_dirty_confirm),
            confirmText = stringResource(R.string.git_pull_continue),
            destructive = true,
            onConfirm = {
                showPullConfirm = false
                stateMachine.pull()
            },
            onDismiss = { showPullConfirm = false },
        )
    }
    if (showCommitDialog) {
        GitCommitDialog(
            busy = state.busy,
            onDismiss = { showCommitDialog = false },
            onConfirm = { message, amend ->
                showCommitDialog = false
                stateMachine.commit(message, amend)
            },
            // AI 生成提交信息：agent 模型请求通道接线属下一批（GitPanelState 尚无模型入口），先留回调占位。
            onGenerateMessage = null,
        )
    }
}

/** 非仓库态：文案 + 两行说明 + 「初始化 Git 仓库」「克隆远程仓库」+「选择已有仓库」（成功后自动刷新进仓库态）。 */
@Composable
private fun NotARepoState(busy: Boolean, onInit: () -> Unit, onClone: () -> Unit, onPickExisting: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.git_not_a_repo),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
            )
            Text(
                text = stringResource(R.string.git_init_desc_primary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            Text(
                text = stringResource(R.string.git_init_desc_secondary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            if (busy) {
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator(size = 24.dp)
                Text(
                    text = stringResource(R.string.git_cloning_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalPill(text = stringResource(R.string.git_init_repo), icon = Icons.Rounded.AccountTree, onClick = onInit)
                    FilledTonalPill(text = stringResource(R.string.git_clone_repo), icon = Icons.Rounded.CloudDownload, onClick = onClone)
                }
                OutlinedPill(text = stringResource(R.string.git_pick_existing_repo), icon = Icons.Rounded.Folder, onClick = onPickExisting)
            }
        }
    }
}

/** 非仓库态的主操作胶囊（FilledTonalButton 观感：主题色淡底 + 图标 + 文案）。 */
@Composable
private fun FilledTonalPill(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(text = text, color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
    }
}

/** 非仓库态的次操作胶囊（OutlinedButton 观感：细边框 + 图标 + 文案）。 */
@Composable
private fun OutlinedPill(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
        Text(text = text, color = MiuixTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

/** 加载态：转圈 + 文案居中。 */
@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(size = 22.dp)
        Text(
            text = stringResource(R.string.git_loading),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
        )
    }
}

/** 加载失败态：原因 + 重试按钮（仓库发现超时或快照命令失败都落到这里）。 */
@Composable
private fun LoadErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        EtaTextButton(text = stringResource(R.string.git_retry), onClick = onRetry)
    }
}

/** 悬浮页签条的单个页签项。 */
private data class FloatingTabItem(val icon: ImageVector, val label: String)

/**
 * 底部悬浮页签条：状态/分支/提交三个 pill。
 *
 * 点击翻页不用 rememberCoroutineScope（ETA 纪律）：用 pendingPage + LaunchedEffect 桥接
 * pagerState.animateScrollToPage——只有翻页动画这一个异步动作，无需独立作用域。
 */
@Composable
private fun FloatingTabBar(pagerState: androidx.compose.foundation.pager.PagerState, isScrolling: Boolean, modifier: Modifier = Modifier) {
    val items = listOf(
        FloatingTabItem(Icons.Rounded.Insights, stringResource(R.string.git_tab_status)),
        FloatingTabItem(Icons.Rounded.AccountTree, stringResource(R.string.git_tab_branches)),
        FloatingTabItem(Icons.Rounded.History, stringResource(R.string.git_tab_commits)),
    )
    // 点击翻页的桥接：onClick 只置 pendingPage，LaunchedEffect 消费并翻页。
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingPage) {
        pendingPage?.let {
            pagerState.animateScrollToPage(it)
            pendingPage = null
        }
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = if (isScrolling) 0.55f else 0.94f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, item ->
            val selected = pagerState.currentPage == index
            val tint by animateColorAsState(
                targetValue = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                label = "floating-tab-tint",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { pendingPage = index }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(imageVector = item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(15.dp))
                Text(text = item.label, color = tint, fontSize = 12.sp)
            }
        }
    }
}

/** 仓库路径 → 显示名（最后一段路径；根目录用完整路径）。 */
private fun repoDisplayName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }

/**
 * 仓库选择弹层：列出 discoverRepos（maxdepth 4）发现的全部仓库 + 绑定根条目，仓库可点选。
 * 显示名为相对绑定根的相对路径（如 ETA/Eta）；Text 已限 maxLines=1 + Ellipsis + weight(1f)，
 * 深层相对路径过长时安全省略不破行。既用于多仓库切换（顶栏入口），也用于非仓库态「选择已有仓库」。
 */
@Composable
private fun RepoPickerSheet(
    entries: List<GitPanelState.RepoPickerEntry>,
    currentRepo: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(
        show = true,
        title = stringResource(R.string.git_repo_selector_title),
        onDismissRequest = onDismiss,
    ) {
        // 条目（含显示名与 isRepo 缓存判定）由 GitPanelState.buildRepoPickerEntries 在刷新时构建并
        // 缓存进状态：显示名 = 相对绑定根的相对路径、路径 = 真实绝对路径、绑定根条目排首位，
        // 同一路径只出现一次，key 保持原路径且天然唯一（上轮重复 key 闪退的修复语义不变）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPaddingCompat(),
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.git_pick_repo_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        RepoPickerRow(
                            entry = entry,
                            isSelected = entry.path == currentRepo,
                            onClick = { onSelect(entry.path) },
                        )
                    }
                }
            }
        }
    }
}

/** 仓库选择弹层的单行：仓库可点选（选中态主题色），非仓库灰显 + 「非仓库」标注；显示名直接用条目缓存值。 */
@Composable
private fun RepoPickerRow(entry: GitPanelState.RepoPickerEntry, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (entry.isRepo) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.displayName,
            color = when {
                isSelected -> MiuixTheme.colorScheme.primary
                entry.isRepo -> MiuixTheme.colorScheme.onSurface
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isRepo) {
            Text(
                text = stringResource(R.string.git_repo_not_repo),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 克隆对话框：远程 URL + 目标子目录（落在绑定根下）。
 * aicode 只输 URL（其仓库层自动推导子目录）；ETA 仓库绑定根语义下子目录必填（cloneRepo 前置校验），保留双输入。
 */
@Composable
private fun CloneRepoDialog(workspaceRoot: String, busy: Boolean, onDismiss: () -> Unit, onClone: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    // 默认目标 = 当前项目目录名：克隆进同名兄弟目录是最高频场景；用户可改。
    var dir by remember { mutableStateOf(workspaceRoot.substringAfterLast('/').ifBlank { "repo" }) }
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.git_clone_dialog_title),
        summary = stringResource(R.string.git_clone_desc),
        onDismissRequest = onDismiss,
    ) {
        DialogTextField(value = url, hint = stringResource(R.string.git_clone_url_hint), onValueChange = { url = it })
        Spacer(Modifier.height(8.dp))
        DialogTextField(value = dir, hint = stringResource(R.string.git_clone_dir_hint), onValueChange = { dir = it })
        Spacer(Modifier.height(8.dp))
        MiuixDialogActions(
            confirmText = stringResource(R.string.git_action_clone),
            confirmEnabled = !busy && url.isNotBlank() && dir.isNotBlank(),
            onCancel = onDismiss,
            onConfirm = { onClone(url.trim(), dir.trim()) },
        )
    }
}

/** 通用确认弹窗：标题 + 说明 + 取消/确认（destructive 时确认键用 error 配色）。 */
@Composable
private fun GitConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
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

/** 弹窗输入框：surfaceContainer 底 + 圆角，placeholder 用叠加文本实现（BasicTextField 无 hint 参数）。 */
@Composable
private fun DialogTextField(value: String, hint: String, onValueChange: (String) -> Unit, minLines: Int = 1) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(text = hint, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
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

/** 弹层底部导航条内边距（无 WindowInsets 兼容助手时的等价处理）。 */
private fun Modifier.navigationBarsPaddingCompat(): Modifier = this.padding(bottom = 12.dp)

/**
 * Git 状态徽章统一色彩映射（自 aicode core/theme/AIEditorTheme.GitStatusColors 移植）。
 * 与多数 Git 客户端约定一致：新增=绿、修改=琥珀、删除=红、重命名/复制=蓝、未跟踪=灰、
 * 冲突=紫红、类型变更=青。
 */
private object GitStatusColors {
    val Added = Color(0xFF16A34A)
    val Modified = Color(0xFFD97706)
    val Deleted = Color(0xFFDC2626)
    val Renamed = Color(0xFF2563EB)
    val Untracked = Color(0xFF94A3B8)
    val Conflict = Color(0xFF9333EA)
    val TypeChanged = Color(0xFF0891B2)
    val Default = Color(0xFF64748B)
}

/** 状态码 → 配色（容器色 + 前景色）。前景色根据背景亮度自适应，避免高明度背景配白字对比度不足。 */
private fun statusColor(code: String): Pair<Color, Color> {
    val bg = when (code.firstOrNull()) {
        'A' -> GitStatusColors.Added
        'M' -> GitStatusColors.Modified
        'D' -> GitStatusColors.Deleted
        'R', 'C' -> GitStatusColors.Renamed
        '?' -> GitStatusColors.Untracked
        'U' -> GitStatusColors.Conflict
        'T' -> GitStatusColors.TypeChanged
        else -> GitStatusColors.Default
    }
    val fg = if (bg.luminance() > 0.45f) Color(0xFF0F172A) else Color.White
    return bg to fg
}

/**
 * 状态页（1:1 对齐 aicode GitStatusTab.kt，自上而下）：
 * StatusOverview 统计卡片 → 提交大按钮 → 次级操作行（暂存全部/储藏/拉取/推送）→
 * 冲突组/合并横幅 → 已暂存组 → 未暂存组 → 未跟踪组（目录折叠）→ 各确认弹窗与储藏箱弹层。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GitStatusTab(
    state: GitPanelState.GitUiState,
    stateMachine: GitPanelState,
    listState: LazyListState,
    onCommit: () -> Unit,
    onPull: () -> Unit,
    onStashOpen: () -> Unit,
    onOpenDiff: (filePath: String, staged: Boolean) -> Unit,
) {
    val s = state.status
    val context = LocalContext.current
    val clean = s == null || (s.staged.isEmpty() && s.unstaged.isEmpty() && s.untracked.isEmpty() && s.conflicted.isEmpty())
    val hasChanges = !clean

    // 长按文件行弹出的操作菜单；null 表示未打开。
    var actionSheet by remember { mutableStateOf<FileMenu?>(null) }
    // 储藏箱弹层开关（页面层传入的 onStashOpen 只负责拉取储藏列表）。
    var showStashSheet by remember { mutableStateOf(false) }

    // 复制路径到系统剪贴板并 toast（对齐 aicode onCopyPath）。
    fun copyPath(path: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("git-path", path))
        Toast.makeText(context, context.getString(R.string.git_toast_path_copied), Toast.LENGTH_SHORT).show()
    }
    // 菜单文案在组合期解析成只读快照（stringResource 只能在 @Composable 里调用；
    // 菜单构造是普通局部函数，会在 onLongClick 这类非组合回调里执行，不能直接调 stringResource）。
    val viewDiffLabel = stringResource(R.string.git_action_view_diff)
    val stageLabel = stringResource(R.string.git_stage)
    val revertLabel = stringResource(R.string.git_action_revert)
    val restoreFileLabel = stringResource(R.string.git_action_restore_file)
    val copyPathLabel = stringResource(R.string.git_action_copy_path)
    val deleteFileLabel = stringResource(R.string.git_action_delete_file)
    val deleteDirLabel = stringResource(R.string.git_action_delete_dir)
    val ignoreFileLabel = stringResource(R.string.git_ignore_file)
    val ignoreDirLabel = stringResource(R.string.git_ignore_dir)
    val ignoreExtLabel = stringResource(R.string.git_ignore_extension)

    fun conflictedMenu(file: GitFileChange) = FileMenu(
        path = file.path,
        actions = listOf(
            FileAction(viewDiffLabel, Icons.Rounded.Description, false) { onOpenDiff(file.path, false) },
            FileAction(stageLabel, Icons.Rounded.Add, false) { stateMachine.stage(file.path) },
            FileAction(copyPathLabel, Icons.Rounded.ContentCopy, false) { copyPath(file.path) },
        ),
    )

    fun stagedMenu(file: GitFileChange) = FileMenu(
        path = file.path,
        actions = listOf(
            FileAction(viewDiffLabel, Icons.Rounded.Description, false) { onOpenDiff(file.path, true) },
            FileAction(revertLabel, Icons.Rounded.Replay, true) { stateMachine.requestRevert(file.path, staged = true) },
            FileAction(copyPathLabel, Icons.Rounded.ContentCopy, false) { copyPath(file.path) },
        ),
    )

    fun unstagedMenu(file: GitFileChange): FileMenu {
        val deleted = file.statusCode.firstOrNull() == 'D'
        return FileMenu(
            path = file.path,
            actions = listOf(
                FileAction(viewDiffLabel, Icons.Rounded.Description, false) { onOpenDiff(file.path, false) },
                if (deleted) {
                    FileAction(restoreFileLabel, Icons.Rounded.Replay, false) { stateMachine.requestRevert(file.path, staged = false, deleted = true) }
                } else {
                    FileAction(revertLabel, Icons.Rounded.Replay, true) { stateMachine.requestRevert(file.path, staged = false) }
                },
                FileAction(copyPathLabel, Icons.Rounded.ContentCopy, false) { copyPath(file.path) },
            ),
        )
    }

    fun untrackedFileMenu(path: String): FileMenu {
        val ext = path.substringAfterLast('.', "").takeIf { it.isNotBlank() && !path.endsWith("/") }
        return FileMenu(
            path = path,
            actions = buildList {
                add(FileAction(viewDiffLabel, Icons.Rounded.Description, false) { onOpenDiff(path, false) })
                add(
                    FileAction(
                        ignoreFileLabel.format(path.substringAfterLast('/')),
                        Icons.Rounded.VisibilityOff,
                        false,
                    ) { stateMachine.addToGitignore("/$path") },
                )
                if (ext != null) {
                    add(
                        FileAction(
                            ignoreExtLabel.format(ext),
                            Icons.Rounded.VisibilityOff,
                            false,
                        ) { stateMachine.addToGitignore("*.$ext") },
                    )
                }
                add(FileAction(deleteFileLabel, Icons.Rounded.Delete, true) { stateMachine.requestDeleteUntracked(path) })
                add(FileAction(copyPathLabel, Icons.Rounded.ContentCopy, false) { copyPath(path) })
            },
        )
    }

    fun untrackedDirMenu(path: String) = FileMenu(
        path = path,
        actions = listOf(
            FileAction(
                ignoreDirLabel.format(path.removeSuffix("/").substringAfterLast('/')),
                Icons.Rounded.VisibilityOff,
                false,
            ) { stateMachine.addToGitignore("/${path.removeSuffix("/")}/") },
            FileAction(deleteDirLabel, Icons.Rounded.Delete, true) { stateMachine.requestDeleteUntracked(path) },
            FileAction(copyPathLabel, Icons.Rounded.ContentCopy, false) { copyPath(path) },
        ),
    )

    // 状态页可能包含上千个文件行：外层必须是懒加载列表，否则首帧会一次性组合全部行把主线程卡死。
    // 分组卡片外观靠逐行拼接实现（见 [FileGroupRow]），未跟踪目录的子文件展平进主列表——
    // LazyColumn 内嵌纵向 LazyColumn 会因无限高约束直接抛异常。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 70.dp),
    ) {
        item(key = "overview") { StatusOverview(status = s, clean = clean) }

        // 主操作：提交。有已暂存改动且已配置署名才可用。
        val commitEnabled = !state.busy && (s?.staged?.isNotEmpty() == true) && state.hasIdentity
        item(key = "commit") {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (commitEnabled) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.50f)
                        )
                        .clickable(enabled = commitEnabled) { onCommit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = if (commitEnabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f),
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            text = stringResource(R.string.git_commit_changes),
                            color = if (commitEnabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!state.hasIdentity) {
                    // 禁用原因提示：用户不知道按钮为什么不可点时给出指引。
                    Text(
                        text = stringResource(R.string.git_commit_needs_identity),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }

        // 次级操作：暂存全部 / 储藏 / 拉取 / 推送。
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    label = stringResource(R.string.git_stage_all),
                    icon = Icons.Rounded.Add,
                    enabled = !state.busy && hasChanges,
                    onClick = { stateMachine.stageAll() },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = stringResource(R.string.git_tab_stash),
                    icon = Icons.Rounded.Inventory2,
                    enabled = !state.busy,
                    onClick = {
                        onStashOpen()
                        showStashSheet = true
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = stringResource(R.string.git_pull),
                    icon = Icons.Rounded.CloudDownload,
                    enabled = !state.busy && state.hasRemote,
                    onClick = onPull,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = stringResource(R.string.git_push),
                    icon = Icons.Rounded.CloudUpload,
                    enabled = !state.busy && state.hasRemote,
                    onClick = { stateMachine.push() },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (s == null || clean) {
            item(key = "clean") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 96.dp, bottom = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.git_status_clean),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
            }
        } else {
            val ss = s
            // 存在冲突时高亮提示并列出冲突文件；无冲突但在合并中给横幅。
            if (ss.conflicted.isNotEmpty()) {
                item(key = "conflicted-header") {
                    GroupHeaderWithAction(
                        title = stringResource(R.string.git_conflicted_count, ss.conflicted.size),
                        actionLabel = if (ss.isMerging) stringResource(R.string.git_action_abort_merge) else "",
                        actionEnabled = !state.busy,
                        onAction = { stateMachine.requestAbortMergeConfirm() },
                    )
                }
                itemsIndexed(ss.conflicted, key = { _, f -> "conflicted:${f.path}" }) { index, f ->
                    FileGroupRow(index = index, count = ss.conflicted.size) {
                        FileRow(
                            file = f,
                            actionIcon = Icons.Rounded.Add,
                            actionDesc = stringResource(R.string.git_stage),
                            onAction = { stateMachine.stage(f.path) },
                            enabled = !state.busy,
                            onClick = { onOpenDiff(f.path, false) },
                            onLongClick = { actionSheet = conflictedMenu(f) },
                        )
                    }
                }
            } else if (ss.isMerging) {
                item(key = "merging-banner") {
                    MergingBanner(busy = state.busy, onAbort = { stateMachine.requestAbortMergeConfirm() })
                }
            }

            if (ss.staged.isNotEmpty()) {
                item(key = "staged-header") {
                    GroupHeaderWithAction(
                        title = stringResource(R.string.git_staged_count, ss.staged.size),
                        actionLabel = stringResource(R.string.git_action_unstage_all),
                        actionEnabled = !state.busy,
                        onAction = { stateMachine.requestUnstageAllConfirm() },
                    )
                }
                itemsIndexed(ss.staged, key = { _, f -> "staged:${f.path}" }) { index, f ->
                    FileGroupRow(index = index, count = ss.staged.size) {
                        FileRow(
                            file = f,
                            actionIcon = Icons.Rounded.Remove,
                            actionDesc = stringResource(R.string.git_unstage),
                            onAction = { stateMachine.unstage(f.path) },
                            enabled = !state.busy,
                            onClick = { onOpenDiff(f.path, true) },
                            onLongClick = { actionSheet = stagedMenu(f) },
                        )
                    }
                }
            }
            if (ss.unstaged.isNotEmpty()) {
                item(key = "unstaged-header") {
                    GroupHeaderWithAction(
                        title = stringResource(R.string.git_modified_count, ss.unstaged.size),
                        actionLabel = stringResource(R.string.git_action_revert_all),
                        actionEnabled = !state.busy,
                        onAction = { stateMachine.requestRevertAllConfirm() },
                    )
                }
                itemsIndexed(ss.unstaged, key = { _, f -> "unstaged:${f.path}" }) { index, f ->
                    FileGroupRow(index = index, count = ss.unstaged.size) {
                        FileRow(
                            file = f,
                            actionIcon = Icons.Rounded.Add,
                            actionDesc = stringResource(R.string.git_stage),
                            onAction = { stateMachine.stage(f.path) },
                            enabled = !state.busy,
                            onClick = { onOpenDiff(f.path, false) },
                            onLongClick = { actionSheet = unstagedMenu(f) },
                        )
                    }
                }
            }
            if (ss.untracked.isNotEmpty()) {
                // 未跟踪项展平：目录行 + 展开后的子文件行，整组共用一张卡片（按首/中/末行取圆角）。
                val untrackedItems = buildList {
                    ss.untracked.forEach { path ->
                        if (path.endsWith("/")) {
                            add(UntrackedItem.Dir(path))
                            state.untrackedDirFiles[path]?.forEach { child -> add(UntrackedItem.Child(child, indent = true)) }
                        } else {
                            add(UntrackedItem.Child(path, indent = false))
                        }
                    }
                }
                item(key = "untracked-header") {
                    SectionHeader(stringResource(R.string.git_untracked_count, ss.untracked.size))
                }
                itemsIndexed(
                    untrackedItems,
                    key = { _, item ->
                        when (item) {
                            is UntrackedItem.Dir -> "untracked-dir:${item.path}"
                            is UntrackedItem.Child -> "untracked-file:${item.path}"
                        }
                    },
                ) { index, item ->
                    FileGroupRow(index = index, count = untrackedItems.size) {
                        when (item) {
                            is UntrackedItem.Dir -> {
                                val children = state.untrackedDirFiles[item.path]
                                UntrackedDirRow(
                                    path = item.path,
                                    childCount = children?.size,
                                    expanded = children != null,
                                    loading = state.untrackedDirLoading == item.path,
                                    enabled = !state.busy,
                                    onClick = { stateMachine.toggleUntrackedDir(item.path) },
                                    onLongClick = { actionSheet = untrackedDirMenu(item.path) },
                                    onStage = { stateMachine.stage(item.path) },
                                )
                            }
                            is UntrackedItem.Child -> FileRow(
                                file = GitFileChange(item.path, "?", staged = false),
                                actionIcon = Icons.Rounded.Add,
                                actionDesc = stringResource(R.string.git_stage),
                                onAction = { stateMachine.stage(item.path) },
                                enabled = !state.busy,
                                indent = if (item.indent) 16.dp else 0.dp,
                                onClick = { onOpenDiff(item.path, false) },
                                onLongClick = { actionSheet = untrackedFileMenu(item.path) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 弹层与确认弹窗（目标/开关由状态机持有） ----------

    if (showStashSheet) {
        StashBottomSheet(
            stashes = state.stashes,
            loading = state.stashesLoading,
            hasChanges = hasChanges,
            busy = state.busy,
            onDismiss = { showStashSheet = false },
            onSave = { msg, untracked -> stateMachine.stashSave(msg, untracked) },
            onPop = { stateMachine.stashPop(it) },
            onApply = { stateMachine.stashApply(it) },
            onDrop = { stateMachine.requestDropStash(it) },
            onClear = { stateMachine.requestClearStashConfirm() },
        )
    }

    if (state.showAbortMergeConfirm) {
        GitConfirmDialog(
            show = true,
            title = stringResource(R.string.git_action_abort_merge),
            message = stringResource(R.string.git_abort_merge_confirm),
            confirmText = stringResource(R.string.git_action_abort_merge),
            destructive = true,
            onConfirm = {
                stateMachine.dismissAbortMergeConfirm()
                stateMachine.abortMerge()
            },
            onDismiss = { stateMachine.dismissAbortMergeConfirm() },
        )
    }

    state.pendingDropStash?.let { index ->
        GitConfirmDialog(
            show = true,
            title = stringResource(R.string.git_stash_drop),
            message = stringResource(R.string.git_stash_drop_confirm, index),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                stateMachine.dismissDropStash()
                stateMachine.stashDrop(index)
            },
            onDismiss = { stateMachine.dismissDropStash() },
        )
    }

    if (state.showClearStashConfirm) {
        GitConfirmDialog(
            show = true,
            title = stringResource(R.string.git_stash_clear),
            message = stringResource(R.string.git_stash_clear_confirm),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                stateMachine.dismissClearStashConfirm()
                stateMachine.stashClear()
            },
            onDismiss = { stateMachine.dismissClearStashConfirm() },
        )
    }

    actionSheet?.let { menu ->
        FileActionSheet(menu = menu, onDismiss = { actionSheet = null })
    }

    state.pendingRevert?.let { target ->
        val titleRes = if (target.deleted) R.string.git_action_restore_file else R.string.git_action_revert
        GitConfirmDialog(
            show = true,
            title = stringResource(titleRes),
            message = when {
                target.deleted -> stringResource(R.string.git_restore_file_confirm, target.path)
                target.staged -> stringResource(R.string.git_revert_confirm_head, target.path)
                else -> stringResource(R.string.git_revert_confirm_worktree, target.path)
            },
            confirmText = stringResource(titleRes),
            destructive = !target.deleted,
            onConfirm = {
                stateMachine.dismissRevert()
                stateMachine.revertFile(target.path, target.staged)
            },
            onDismiss = { stateMachine.dismissRevert() },
        )
    }

    state.pendingDeleteUntracked?.let { path ->
        GitConfirmDialog(
            show = true,
            title = stringResource(if (path.endsWith("/")) R.string.git_action_delete_dir else R.string.git_action_delete_file),
            message = stringResource(R.string.git_delete_untracked_confirm, path),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                stateMachine.dismissDeleteUntracked()
                stateMachine.deleteUntracked(path)
            },
            onDismiss = { stateMachine.dismissDeleteUntracked() },
        )
    }

    if (state.showRevertAllConfirm) {
        GitConfirmDialog(
            show = true,
            title = stringResource(R.string.git_action_revert_all),
            message = stringResource(R.string.git_revert_all_confirm, state.status?.unstaged?.size ?: 0),
            confirmText = stringResource(R.string.git_action_revert_all),
            destructive = true,
            onConfirm = {
                stateMachine.dismissRevertAllConfirm()
                stateMachine.revertAllUnstaged()
            },
            onDismiss = { stateMachine.dismissRevertAllConfirm() },
        )
    }

    if (state.showUnstageAllConfirm) {
        GitConfirmDialog(
            show = true,
            title = stringResource(R.string.git_action_unstage_all),
            message = stringResource(R.string.git_unstage_all_confirm),
            confirmText = stringResource(R.string.git_action_unstage_all),
            onConfirm = {
                stateMachine.dismissUnstageAllConfirm()
                stateMachine.unstageAll()
            },
            onDismiss = { stateMachine.dismissUnstageAllConfirm() },
        )
    }

    // master 首推三选弹窗：重命名为 main 并推送 / 保留 master 推送 / 取消。
    if (state.showPushMasterConfirm) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.git_push_master_confirm_title),
            summary = stringResource(R.string.git_push_master_confirm_desc),
            onDismissRequest = { stateMachine.dismissPushMasterConfirm() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EtaTextButton(
                    text = stringResource(R.string.git_push_rename_to_main),
                    onClick = {
                        stateMachine.dismissPushMasterConfirm()
                        stateMachine.pushRenameMasterToMain()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = stringResource(R.string.git_push_keep_master),
                    onClick = {
                        stateMachine.dismissPushMasterConfirm()
                        stateMachine.pushDirectly()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { stateMachine.dismissPushMasterConfirm() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 状态页未跟踪分组的展平项：目录行，或（展开目录后的）子文件行。[indent] 控制子文件缩进。 */
private sealed interface UntrackedItem {
    val path: String
    data class Dir(override val path: String) : UntrackedItem
    data class Child(override val path: String, val indent: Boolean) : UntrackedItem
}

/** 长按文件行弹出的菜单：标题路径 + 可执行的操作项。 */
private data class FileMenu(val path: String, val actions: List<FileAction>)

/** 文件行可执行的操作项（label 在菜单构建处用 stringResource 解析好）。 */
private class FileAction(val label: String, val icon: ImageVector, val isDestructive: Boolean, val onClick: () -> Unit)

/** 统计卡片：分支小标 + 分支名 monospace + detached 红字/上游/无上游 + SyncPill + 四彩块 StatusMetric。 */
@Composable
private fun StatusOverview(status: GitStatus?, clean: Boolean) {
    val staged = status?.staged?.size ?: 0
    val modified = status?.unstaged?.size ?: 0
    val untracked = status?.untracked?.size ?: 0
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
                    text = if (clean) stringResource(R.string.git_clean) else stringResource(R.string.git_has_changes),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                )
                Text(
                    text = status?.branch ?: stringResource(R.string.git_no_branch),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status?.isDetached == true) {
                    Text(
                        text = stringResource(R.string.git_detached_head_label),
                        color = MiuixTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                } else if (status?.upstream != null) {
                    Text(
                        text = stringResource(R.string.git_tracking_branch, status.upstream),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (status != null) {
                    Text(
                        text = stringResource(R.string.git_no_upstream),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                    )
                }
            }
            if (status != null && (status.ahead > 0 || status.behind > 0)) {
                Spacer(Modifier.width(8.dp))
                SyncPill(ahead = status.ahead, behind = status.behind)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status != null && status.conflicted.isNotEmpty()) {
                StatusMetric(stringResource(R.string.git_conflicted_count, "").trim(), status.conflicted.size, MiuixTheme.colorScheme.error, Modifier.weight(1f))
            }
            StatusMetric(stringResource(R.string.git_staged_label), staged, GitStatusColors.Added, Modifier.weight(1f))
            StatusMetric(stringResource(R.string.git_modified_label), modified, GitStatusColors.Modified, Modifier.weight(1f))
            StatusMetric(stringResource(R.string.git_untracked_label), untracked, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.weight(1f))
        }
    }
}

/** 领先/落后远程的同步状态胶囊。 */
@Composable
private fun SyncPill(ahead: Int, behind: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = buildString {
                if (ahead > 0) append(stringResource(R.string.git_ahead_count, ahead))
                if (behind > 0) {
                    if (isNotEmpty()) append("  ")
                    append(stringResource(R.string.git_behind_count, behind))
                }
            },
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 统计卡片中的单块计数：数值（彩色 monospace）+ 标签。 */
@Composable
private fun StatusMetric(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
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

/** 分组小标题 + 右侧操作（已暂存组的「全部取消暂存」/未暂存组的「全部回退」）。 */
@Composable
private fun GroupHeaderWithAction(title: String, actionLabel: String, actionEnabled: Boolean, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel.isNotBlank()) {
            EtaTextButton(
                text = actionLabel,
                onClick = onAction,
                enabled = actionEnabled,
                minHeight = 32.dp,
                minWidth = 0.dp,
            )
        }
    }
}

/** 简单分组小标题（未跟踪组用）。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 4.dp),
    )
}

/** 合并中横幅：标题 + 说明 + 「中止合并」（对齐 aicode merging-banner）。 */
@Composable
private fun MergingBanner(busy: Boolean, onAbort: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MiuixTheme.colorScheme.error.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.error.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.git_merge_pending_title),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.git_merge_pending_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
            EtaTextButton(
                text = stringResource(R.string.git_action_abort_merge),
                onClick = onAbort,
                enabled = !busy,
                minHeight = 32.dp,
                minWidth = 0.dp,
            )
        }
    }
}

/**
 * 懒加载列表中的分组行：把多行拼成一张卡片——首/末行保留圆角、中间行直角，非首行顶部补分隔线。
 * 每行是独立 LazyColumn item，组内上千行时也只组合可见部分。
 */
@Composable
private fun FileGroupRow(index: Int, count: Int, content: @Composable () -> Unit) {
    val shape = when {
        count <= 1 -> RoundedCornerShape(12.dp)
        index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        index == count - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RectangleShape
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceContainer),
    ) {
        if (index > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(MiuixTheme.colorScheme.background),
            )
        }
        content()
    }
}

/** 分组内文件行：状态徽标 + 文件名/目录两行 monospace + 行尾暂存操作；点击看差异，长按开操作菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: GitFileChange,
    actionIcon: ImageVector,
    actionDesc: String,
    onAction: () -> Unit,
    enabled: Boolean,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val fileName = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', missingDelimiterValue = "")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (onClick == null && onLongClick == null) it
                else it.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
            }
            .padding(start = 16.dp + indent, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(file.statusCode)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (directory.isNotEmpty()) {
                Text(
                    text = directory,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onAction, enabled = enabled) {
            Icon(
                imageVector = actionIcon,
                contentDescription = actionDesc,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 状态码配色 pill：32x20 圆角，monospace 状态码。 */
@Composable
private fun StatusChip(text: String) {
    val (bg, fg) = statusColor(text)
    Box(
        modifier = Modifier
            .size(width = 32.dp, height = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.take(2),
            color = fg,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 次级操作按钮：柔和背景微卡片按钮，12dp 圆角（暂存全部/储藏/拉取/推送）。 */
@Composable
private fun ActionButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (enabled) MiuixTheme.colorScheme.surfaceContainer else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
    val content = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.40f)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = label, color = content, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 未跟踪目录行（git status 把新目录折叠成 dir/ 一行）：点击展开/收起其中的未跟踪文件，
 * 长按开操作菜单，行尾「+」暂存整个目录。[childCount] 为 null 表示尚未展开，此时箭头左侧不显示数量。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UntrackedDirRow(
    path: String,
    childCount: Int?,
    expanded: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip("?")
        Spacer(Modifier.width(12.dp))
        Text(
            text = path,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (loading) {
            CircularProgressIndicator(size = 16.dp)
        } else {
            if (childCount != null) {
                Text(
                    text = childCount.toString(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                )
            }
            Icon(
                // 展开态箭头朝下、收起态朝右（对齐 aicode ExpandableChevronIcon RIGHT_DOWN 语义）。
                imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onStage, enabled = enabled) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.git_stage),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 长按文件行弹出的操作弹层：标题路径 + 操作项列表（破坏性操作 error 配色）。 */
@Composable
private fun FileActionSheet(menu: FileMenu, onDismiss: () -> Unit) {
    OverlayBottomSheet(
        show = true,
        title = menu.path,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            menu.actions.forEach { action ->
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

/** 储藏箱弹层：说明输入 + 包含未跟踪开关 + 保存；储藏列表行 pop/apply/drop。 */
@Composable
private fun StashBottomSheet(
    stashes: List<GitStash>,
    loading: Boolean,
    hasChanges: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?, Boolean) -> Unit,
    onPop: (String) -> Unit,
    onApply: (String) -> Unit,
    onDrop: (String) -> Unit,
    onClear: () -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var includeUntracked by remember { mutableStateOf(false) }
    OverlayBottomSheet(
        show = true,
        title = stringResource(R.string.git_stash_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.git_stash_count, stashes.size),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                )
                if (stashes.isNotEmpty()) {
                    EtaTextButton(
                        text = stringResource(R.string.git_stash_clear),
                        onClick = onClear,
                        enabled = !busy,
                        minHeight = 32.dp,
                        minWidth = 0.dp,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialogTextField(
                    value = message,
                    hint = stringResource(R.string.git_stash_message_hint),
                    onValueChange = { message = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { includeUntracked = !includeUntracked },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.git_stash_include_untracked),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                    EtaSwitch(checked = includeUntracked, onCheckedChange = { includeUntracked = it })
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (!busy && hasChanges) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainer
                        )
                        .clickable(enabled = !busy && hasChanges) {
                            val msg = message.trim().ifBlank { null }
                            message = ""
                            onSave(msg, includeUntracked)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Inventory2,
                        contentDescription = null,
                        tint = if (!busy && hasChanges) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.git_action_stash),
                        color = if (!busy && hasChanges) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                }
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(size = 18.dp)
                }
            } else if (stashes.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.git_stash_empty),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(stashes, key = { it.index }) { item ->
                        StashItemRow(
                            stash = item,
                            busy = busy,
                            onPop = { onPop(item.index) },
                            onApply = { onApply(item.index) },
                            onDrop = { onDrop(item.index) },
                        )
                    }
                }
            }
        }
    }
}

/** 储藏列表行：说明 + 日期 + 引用名（monospace）+ pop/apply/drop。 */
@Composable
private fun StashItemRow(stash: GitStash, busy: Boolean, onPop: () -> Unit, onApply: () -> Unit, onDrop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stash.message.ifBlank { stash.index },
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stash.date,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stash.index,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            EtaTextButton(text = stringResource(R.string.git_stash_pop), onClick = onPop, enabled = !busy, minHeight = 30.dp, minWidth = 0.dp)
            Spacer(Modifier.width(4.dp))
            EtaTextButton(text = stringResource(R.string.git_stash_apply), onClick = onApply, enabled = !busy, minHeight = 30.dp, minWidth = 0.dp)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDrop, enabled = !busy) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.git_stash_drop),
                    tint = MiuixTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
