package io.github.mangi.eta.ui.git

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.git.model.GitFileChange
import io.github.mangi.eta.agent.git.model.GitGraph
import io.github.mangi.eta.agent.git.model.GitGraphRef
import io.github.mangi.eta.agent.git.model.GraphCommit
import io.github.mangi.eta.agent.git.model.GraphEdge
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提交（日志）tab（1:1 克隆 aicode feature/git/presentation/component/GitLogTab.kt，2026-11-28 第二批）。
 *
 * 布局与交互对齐 aicode LogTab：
 * - 顶部搜索框（搜索图标 + 清空）：按 message/author/shortHash 本地过滤；搜索态不画泳道连线；
 * - SectionHeader「提交数 (n)」→ 卡片式 LazyColumn：GraphCommitRow 行高 72dp = GraphCanvas 泳道拓扑
 *   （泳道色板循环、跨列贝塞尔曲线、合并提交双圈节点、分段竖线；线性历史 laneWidth 10dp 否则 16dp）
 *   + RefPills（当前分支高亮）+ ChevronRight + 提交信息两行 + shortHash pill（泳道色）+ 作者 + 日期；
 *   行间 0.5dp 分隔线；
 * - 预加载：滚动到剩余 20 项自动 [GitPanelState.loadMoreCommits]（derivedStateOf）；footer
 *   「加载更多(spinner)/没有更多」；
 * - 点击提交行弹 CommitDetailSheet（bottom sheet，不打断列表）：完整信息 + 复制哈希 + 改动文件清单
 *   （点击文件走 [onOpenCommitFileDiff] diff 回调）+ 操作：从此提交建分支/建标签/重置到此提交
 *   （soft/mixed/hard 三选——任务书要求三档，aicode 原版只有 soft/mixed）；
 * - 空态「暂无提交」。
 *
 * Miuix 化差异：material3 HorizontalDivider/RadioButton/FilledTonalButton → 自绘 0.5dp Box、
 * 自绘单选圆点与浅底 pill；aicode Reset 对话框补 hard 档（仓库层 `git reset --hard` 原生支持）。
 */
@Composable
internal fun GitLogTab(
    state: GitPanelState.GitUiState,
    stateMachine: GitPanelState,
    listState: LazyListState,
    /** 打开某提交某文件的 diff（diff 全屏页下一批接入，当前由页面层占位转发 onOpenDiff）。 */
    onOpenCommitFileDiff: (commitHash: String, filePath: String) -> Unit,
) {
    val graph = state.graph
    if (graph.commits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.git_no_commits),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
            )
        }
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val commits = remember(graph.commits, searchQuery) {
        if (searchQuery.isBlank()) graph.commits
        else {
            val q = searchQuery.trim().lowercase()
            graph.commits.filter {
                it.message.lowercase().contains(q) ||
                    it.author.lowercase().contains(q) ||
                    it.shortHash.lowercase().contains(q)
            }
        }
    }

    // 泳道调色板：按列号循环取色，分支越多颜色越丰富。
    val laneColors = rememberLaneColors(graph.maxLane + 1)
    // 每个提交到其父提交的边列表（按提交索引分组），供 Canvas 绘制连线。
    val edgesByCommit = remember(graph) { groupEdgesByCommit(graph) }
    // Canvas 宽度：每个泳道一列，加左右内边距。线性历史（无分叉/合并）时收紧泳道宽度，
    // 让出更多空间给提交信息；有分叉时每泳道 16dp。节点在泳道内自动居中，无需改绘制。
    val laneWidth = if (graph.maxLane == 0) 10.dp else 16.dp
    val canvasWidth = laneWidth * (graph.maxLane + 1) + 4.dp * 2
    // 每行高度，用于计算连线纵向跨度（节点居中）。
    val rowHeight = 72.dp
    // 预拉取下一页：列表滑动至接近底部（剩余 20 项）且还有更多提交时提前发起后台拉取，消除滑到底部等待停顿。
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 20
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && graph.hasMore && !state.graphLoadingMore && searchQuery.isBlank()) {
            stateMachine.loadMoreCommits()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部搜索框：搜索图标 + 清空（有输入时显示）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = stringResource(R.string.git_search_commits_hint),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        LogSectionHeader(stringResource(R.string.git_commit_count, commits.size))
        // 滚动容器占满整屏（与状态/分支页一致）：白色卡片是滚动内容的一部分——
        // 内容少时卡片收缩在顶部；滚动时整卡可滚过 tab 被渐变蒙版渐隐，
        // 滚到底卡片底部停在 tab 上方（底部 70dp 留白）。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            state = listState,
            contentPadding = PaddingValues(bottom = 70.dp),
        ) {
            itemsIndexed(commits, key = { _, c -> c.hash }) { index, c ->
                val shape = when {
                    index == 0 && commits.size == 1 && (!graph.hasMore || searchQuery.isNotBlank()) -> RoundedCornerShape(12.dp)
                    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    index == commits.lastIndex && (!graph.hasMore || searchQuery.isNotBlank()) -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                    else -> RectangleShape
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(MiuixTheme.colorScheme.surfaceContainer),
                ) {
                    if (index > 0) {
                        // 行间 0.5dp 分隔线：起点让过泳道区，只分隔提交信息部分。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = canvasWidth + 12.dp)
                                .height(0.5.dp)
                                .background(MiuixTheme.colorScheme.dividerLine),
                        )
                    }
                    val origIndex = graph.commits.indexOfFirst { it.hash == c.hash }
                    GraphCommitRow(
                        commit = c,
                        lane = graph.lanes[c.hash] ?: 0,
                        edges = if (searchQuery.isBlank()) edgesByCommit[origIndex].orEmpty() else emptyList(),
                        activeTopLanes = if (searchQuery.isBlank()) graph.activeTopLanes[c.hash].orEmpty() else emptyList(),
                        activeBottomLanes = if (searchQuery.isBlank()) graph.activeBottomLanes[c.hash].orEmpty() else emptyList(),
                        laneColors = laneColors,
                        canvasWidth = if (searchQuery.isBlank()) canvasWidth else 0.dp,
                        laneWidth = if (searchQuery.isBlank()) laneWidth else 0.dp,
                        rowHeight = rowHeight,
                        refs = graph.refs[c.hash].orEmpty(),
                        isTopTerminal = origIndex == 0,
                        onOpen = { stateMachine.openCommitDetail(c.hash) },
                    )
                }
            }

            if (searchQuery.isBlank()) {
                item(key = "footer") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainer),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (graph.hasMore) {
                                if (state.graphLoadingMore) {
                                    CircularProgressIndicator(size = 24.dp)
                                } else {
                                    Text(
                                        text = stringResource(R.string.git_load_more_commits),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        fontSize = 11.sp,
                                    )
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.git_no_more_commits),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 详情弹层打开时懒加载该提交的改动文件清单（状态机缓存 + 防抖）。
    LaunchedEffect(state.detailCommitHash) {
        state.detailCommitHash?.let { stateMachine.loadCommitFiles(it) }
    }

    // 提交详情弹层：不打断列表布局，泳道保持完整。
    state.detailCommitHash?.let { hash ->
        val commit = graph.commits.firstOrNull { it.hash == hash }
        if (commit != null) {
            CommitDetailSheet(
                commit = commit,
                files = state.commitFiles[hash],
                loading = state.commitFilesLoading == hash && state.commitFiles[hash] == null,
                onDismiss = stateMachine::closeCommitDetail,
                onFileDiff = onOpenCommitFileDiff,
                onCreateBranch = { name -> stateMachine.createBranch(name, commit.hash, checkout = false) },
                onCreateTag = { name -> stateMachine.createTag(name, commit.hash) },
                onReset = { mode -> stateMachine.resetToCommit(commit.hash, mode) },
            )
        }
    }
}

/** 简单分组小标题（与状态页 SectionHeader 同观感）。 */
@Composable
private fun LogSectionHeader(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 4.dp),
    )
}

/** 单条提交行：左侧泳道拓扑 Canvas + 右侧信息区（RefPills / 提交信息两行 / shortHash pill / 作者 / 日期）。 */
@Composable
private fun GraphCommitRow(
    commit: GraphCommit,
    lane: Int,
    edges: List<GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    laneColors: List<Color>,
    canvasWidth: Dp,
    laneWidth: Dp,
    rowHeight: Dp,
    refs: List<GitGraphRef>,
    /** 列表第一行（最新提交）：上方无更多提交，不画向上延伸的竖线。 */
    isTopTerminal: Boolean,
    onOpen: () -> Unit,
) {
    val isMerge = commit.isMerge
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = rowHeight)
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧拓扑图区域：Canvas 绘制节点 + 上下连线。
            GraphCanvas(
                edges = edges,
                activeTopLanes = activeTopLanes,
                activeBottomLanes = activeBottomLanes,
                lane = lane,
                isMerge = isMerge,
                laneColors = laneColors,
                canvasWidth = canvasWidth,
                laneWidth = laneWidth,
                suppressTopLane = isTopTerminal,
                modifier = Modifier
                    .width(canvasWidth)
                    .fillMaxHeight(),
            )
            // 右侧提交信息。上下留白只作用于内容，Canvas 撑满整行保证泳道跨行连续。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .padding(end = 16.dp),
            ) {
                // 引用标签行（分支/标签 pill）。
                if (refs.isNotEmpty()) {
                    RefPills(refs = refs)
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = stringResource(R.string.git_common_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = commit.message,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(nodeColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = commit.shortHash,
                                    color = nodeColor,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = commit.author,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = commit.date,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 拓扑图 Canvas：绘制当前提交的节点圆点 + 分支连线。
 *
 * 绘制顺序（后绘制覆盖先绘制，保证节点压在线上）：
 * 1. 分段竖线：结合 [activeTopLanes] 与 [activeBottomLanes]，对每个泳道按需画全长（0→height）、
 *    上半段（0→centerY）或下半段（centerY→height）竖线，避免在分叉/合并节点处竖线悬空延伸；
 * 2. 跨列连线：画从节点中心到下半段目标列的贝塞尔曲线；
 * 3. 节点圆点：合并提交画环形双圈，普通提交画实心圆。
 */
@Composable
private fun GraphCanvas(
    edges: List<GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    lane: Int,
    isMerge: Boolean,
    laneColors: List<Color>,
    canvasWidth: Dp,
    laneWidth: Dp,
    /** 第一行（最新提交）：不画上半段竖线，避免节点上方悬空延伸。 */
    suppressTopLane: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Canvas(modifier = modifier) {
        val lanePx = laneWidth.toPx()
        val padPx = 4.dp.toPx()
        val centerX = lane * lanePx + lanePx / 2f + padPx
        val centerY = size.height / 2f
        val stroke = 2.5.dp.toPx()

        // 所有相关泳道（包含 top 和 bottom 的并集）。
        val allLanes = (activeTopLanes + activeBottomLanes + lane).toSet()

        // 跨列边（出边与入边）均在下半段绘制贝塞尔曲线。
        // curveBotLanes 记录被曲线接替下半段竖线的列：仅当该列上半段无竖线（非贯穿通道）时才排除，
        // 避免孤立半段；若该列上半段已有竖线（主干穿过），则保留下半段竖线使其贯穿，曲线叠画在上方。
        val crossEdgeToLanes = edges.filter { it.fromLane != it.toLane }.map { it.toLane }.toSet()
        val curveBotLanes = crossEdgeToLanes.filterNot { it in activeTopLanes }.toSet()

        // 1. 分段竖线绘制：精细控制 0->centerY 与 centerY->height。
        for (l in allLanes) {
            val inTop = l in activeTopLanes
            val inBot = l in activeBottomLanes && l !in curveBotLanes
            val color = laneColors.getOrElse(l) { Color.Gray }
            val x = l * lanePx + lanePx / 2f + padPx

            if (inTop && inBot) {
                // 贯穿整行；第一行不画上半段（上方无提交）。
                if (suppressTopLane) {
                    drawLine(
                        color = color,
                        start = Offset(x, centerY),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            } else if (inTop) {
                // 仅上半段（到达节点终止，或下半段转为斜曲线）；第一行不画。
                if (!suppressTopLane) {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, centerY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            } else if (inBot) {
                // 仅下半段（从该节点新分出/延伸）。
                drawLine(
                    color = color,
                    start = Offset(x, centerY),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }

        // 2. 跨列连线（均在下半段）：出边从节点中心→目标列底部，入边从目标列底部→节点中心。
        for (edge in edges) {
            if (edge.fromLane == edge.toLane) continue
            val color = laneColors.getOrElse(edge.lane) { Color.Gray }
            val fromX = edge.fromLane * lanePx + lanePx / 2f + padPx
            val toX = edge.toLane * lanePx + lanePx / 2f + padPx
            val midY = centerY + (size.height - centerY) * 0.5f
            val path = Path().apply {
                if (edge.isMergeIn) {
                    // 合并入边：父支线从目标列底部弯入本节点中心。
                    moveTo(toX, size.height)
                    cubicTo(
                        toX, midY,
                        fromX, midY,
                        fromX, centerY,
                    )
                } else {
                    // 出边：本节点从中心向目标列底部分叉。
                    moveTo(fromX, centerY)
                    cubicTo(
                        fromX, midY,
                        toX, midY,
                        toX, size.height,
                    )
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        // 3. 节点圆点：合并提交画环形双圈，普通提交画实心圆。
        val nodeRadius = if (isMerge) 7.dp.toPx() else 5.dp.toPx()
        if (isMerge) {
            drawCircle(
                color = nodeColor,
                radius = nodeRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.5.dp.toPx()),
            )
            drawCircle(
                color = nodeColor,
                radius = nodeRadius / 2f,
                center = Offset(centerX, centerY),
            )
        } else {
            drawCircle(
                color = nodeColor,
                radius = nodeRadius,
                center = Offset(centerX, centerY),
            )
        }
    }
}

/** 提交引用标签行：当前分支高亮 primary，分支用 secondaryContainer、标签用 tertiaryContainer。 */
@Composable
private fun RefPills(refs: List<GitGraphRef>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        refs.forEach { ref ->
            val isCurrent = ref.isCurrent
            val bg = when {
                isCurrent -> MiuixTheme.colorScheme.primary
                ref.isBranch -> MiuixTheme.colorScheme.secondaryContainer
                else -> MiuixTheme.colorScheme.tertiaryContainer
            }
            val fg = when {
                isCurrent -> MiuixTheme.colorScheme.onPrimary
                ref.isBranch -> MiuixTheme.colorScheme.onSecondaryContainer
                else -> MiuixTheme.colorScheme.onTertiaryContainer
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (ref.isRemote) Icons.Rounded.Cloud else if (ref.isBranch) Icons.Rounded.AccountTree else Icons.Rounded.Tag,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = ref.name,
                    color = fg,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 泳道调色板：按列数生成一组区分度高的颜色，循环复用。
 * 颜色取自常见 IDE 分支配色，深浅适中以适配明暗主题（值取自 aicode core/theme GitLanePalette）。
 */
@Composable
private fun rememberLaneColors(count: Int): List<Color> {
    return remember(count) {
        (0 until count).map { GIT_LANE_PALETTE[it % GIT_LANE_PALETTE.size] }
    }
}

/** 泳道色板常量（对齐 aicode GitLanePalette.colors）。 */
private val GIT_LANE_PALETTE = listOf(
    Color(0xFF2563EB), // 1. 经典蓝
    Color(0xFF16A34A), // 2. 翠绿
    Color(0xFFF59E0B), // 3. 琥珀
    Color(0xFF8B5CF6), // 4. 优雅紫
    Color(0xFF06B6D4), // 5. 青蓝
    Color(0xFFEF4444), // 6. 珊瑚红
    Color(0xFFEC4899), // 7. 亮粉
    Color(0xFFEAB308), // 8. 柠檬金
)

/**
 * 把 [GitGraph.edges] 按来源提交索引分组。[GitGraphBuilder] 按提交顺序为每个提交
 * 生成 `parents.size` 条边（根提交 0 条），故 edges 是扁平有序的，按父数累积分组即可重建对应。
 * 返回 `Map<提交索引, List<GraphEdge>>`，供 [GraphCommitRow] 绘制本行连线。
 */
private fun groupEdgesByCommit(graph: GitGraph): Map<Int, List<GraphEdge>> {
    val result = mutableMapOf<Int, List<GraphEdge>>()
    var edgeIdx = 0
    graph.commits.forEachIndexed { commitIdx, commit ->
        // 根提交无边；非根提交有 parents.size 条边。
        val n = if (commit.parents.isEmpty()) 0 else commit.parents.size
        val list = mutableListOf<GraphEdge>()
        repeat(n) {
            if (edgeIdx < graph.edges.size) {
                list.add(graph.edges[edgeIdx++])
            }
        }
        result[commitIdx] = list
    }
    return result
}

/**
 * 提交详情底部弹层：完整信息 + 复制哈希到剪贴板 + 改动文件清单（点击文件进 diff）+
 * 深度操作（从此提交建分支/建标签/重置到此提交）。弹层不替代列表布局，泳道保持完整。
 */
@Composable
private fun CommitDetailSheet(
    commit: GraphCommit,
    files: List<GitFileChange>?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onFileDiff: (String, String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onCreateTag: (String) -> Unit,
    onReset: (String) -> Unit,
) {
    val context = LocalContext.current
    var showCreateBranchDialog by remember { mutableStateOf(false) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    fun copyHash(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, context.getString(R.string.git_toast_hash_copied), Toast.LENGTH_SHORT).show()
    }

    // 从此提交建分支对话框。
    if (showCreateBranchDialog) {
        var newBranchName by remember { mutableStateOf("") }
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.git_create_branch_from_commit),
            onDismissRequest = { showCreateBranchDialog = false },
        ) {
            GitDialogField(
                value = newBranchName,
                hint = stringResource(R.string.git_branch_name),
                onValueChange = { newBranchName = it },
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.git_action_create_branch),
                confirmEnabled = newBranchName.isNotBlank(),
                onCancel = { showCreateBranchDialog = false },
                onConfirm = {
                    val name = newBranchName.trim()
                    if (name.isNotBlank()) {
                        showCreateBranchDialog = false
                        onCreateBranch(name)
                        onDismiss()
                    }
                },
            )
        }
    }

    // 从此提交建标签对话框。
    if (showCreateTagDialog) {
        var newTagName by remember { mutableStateOf("") }
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.git_create_tag_at_commit),
            onDismissRequest = { showCreateTagDialog = false },
        ) {
            GitDialogField(
                value = newTagName,
                hint = stringResource(R.string.git_tag_name),
                onValueChange = { newTagName = it },
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.git_action_create_tag),
                confirmEnabled = newTagName.isNotBlank(),
                onCancel = { showCreateTagDialog = false },
                onConfirm = {
                    val name = newTagName.trim()
                    if (name.isNotBlank()) {
                        showCreateTagDialog = false
                        onCreateTag(name)
                    }
                },
            )
        }
    }

    // 重置确认：soft/mixed/hard 三选（aicode 只有 soft/mixed，任务书要求补 hard 档）。
    if (showResetDialog) {
        var resetMode by remember { mutableStateOf("mixed") }
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.git_reset_to_commit),
            summary = stringResource(R.string.git_reset_confirm, commit.shortHash),
            onDismissRequest = { showResetDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ResetModeRow(
                    label = stringResource(R.string.git_reset_soft),
                    selected = resetMode == "soft",
                    onClick = { resetMode = "soft" },
                )
                ResetModeRow(
                    label = stringResource(R.string.git_reset_mixed),
                    selected = resetMode == "mixed",
                    onClick = { resetMode = "mixed" },
                )
                ResetModeRow(
                    label = stringResource(R.string.git_reset_hard),
                    selected = resetMode == "hard",
                    onClick = { resetMode = "hard" },
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.git_reset_to_commit),
                destructive = true,
                onCancel = { showResetDialog = false },
                onConfirm = {
                    showResetDialog = false
                    onReset(resetMode)
                    onDismiss()
                },
            )
        }
    }

    OverlayBottomSheet(
        show = true,
        title = commit.shortHash,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = commit.message,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (commit.body.isNotBlank()) {
                Text(
                    text = commit.body.trim(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = "${commit.author} · ${commit.date}",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            // 完整哈希 + 复制短哈希/完整哈希。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = commit.hash,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.git_copy_short_hash),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { copyHash("commit-short", commit.shortHash) },
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.git_copy_full_hash),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { copyHash("commit-full", commit.hash) },
                )
            }

            // 深度操作按钮行：基于此提交建分支 / 打标签 / 重置。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LogActionPill(
                    label = stringResource(R.string.git_tab_branches),
                    icon = Icons.Rounded.AccountTree,
                    onClick = { showCreateBranchDialog = true },
                    modifier = Modifier.weight(1f),
                )
                LogActionPill(
                    label = stringResource(R.string.git_tags),
                    icon = Icons.Rounded.Tag,
                    onClick = { showCreateTagDialog = true },
                    modifier = Modifier.weight(1f),
                )
                LogActionPill(
                    label = stringResource(R.string.git_reset_to_commit),
                    icon = Icons.Rounded.Replay,
                    onClick = { showResetDialog = true },
                    modifier = Modifier.weight(1f),
                )
            }

            if (commit.isMerge) {
                Text(
                    text = stringResource(R.string.git_merge_commit),
                    color = Color(0xFF8B5CF6),
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            when {
                loading -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.git_loading_files),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                    )
                }
                files == null -> Unit
                files.isEmpty() -> Text(
                    text = stringResource(R.string.git_no_files_changed),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                )
                else -> {
                    Text(
                        text = stringResource(R.string.git_files_changed, files.size),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                    )
                    files.forEach { file ->
                        CommitFileRow(file = file, onClick = { onFileDiff(commit.hash, file.path) })
                    }
                }
            }
        }
    }
}

/** 重置模式单选行：自绘选中圆点（material3 RadioButton 禁用，Miuix 无对应组件）。 */
@Composable
private fun ResetModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (selected) Modifier
                    else Modifier.border(
                        1.dp,
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                        RoundedCornerShape(999.dp),
                    )
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
        )
    }
}

/** 详情弹层深度操作 pill：浅底微卡片按钮（图标 + 文案，对齐状态页 ActionButton 观感）。 */
@Composable
private fun LogActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = label, color = MiuixTheme.colorScheme.onSurface, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 提交改动文件行：状态徽标 + 文件名/目录两行 monospace；点击进 diff。 */
@Composable
private fun CommitFileRow(file: GitFileChange, onClick: () -> Unit) {
    val fileName = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', missingDelimiterValue = "")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogStatusChip(file.statusCode)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (directory.isNotEmpty()) {
                    Text(
                        text = directory,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.dividerLine),
        )
    }
}

/**
 * 状态码配色 pill（与状态页 StatusChip 同构；GitScreen 内为 private，这里按同规格复制）：
 * 新增=绿、修改=琥珀、删除=红、重命名/复制=蓝、未跟踪=灰、冲突=紫红、类型变更=青。
 */
@Composable
private fun LogStatusChip(text: String) {
    val bg = when (text.firstOrNull()) {
        'A' -> Color(0xFF16A34A)
        'M' -> Color(0xFFD97706)
        'D' -> Color(0xFFDC2626)
        'R', 'C' -> Color(0xFF2563EB)
        '?' -> Color(0xFF94A3B8)
        'U' -> Color(0xFF9333EA)
        'T' -> Color(0xFF0891B2)
        else -> Color(0xFF64748B)
    }
    val fg = if (bg.luminance() > 0.45f) Color(0xFF0F172A) else Color.White
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
