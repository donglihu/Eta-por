package io.github.mangi.eta.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixBackButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Git diff 全屏查看器（自 aicode feature/git/presentation/component/DiffViewer.kt 移植，2026-11-28）。
 *
 * 布局：顶栏（返回 + 文件名/完整路径两行标题 + 「在编辑器打开」图标）→ DiffStatsBar（+n/−n）→
 * 横向滚动一个实例包住整列：LazyColumn 宽度取「最宽行实测宽 + 双 gutter + 前缀列」，
 * 整列同步左右滚动，行内 [GitDiffLineRow] 渲染双 gutter + +/− 前缀 + 行内容 + 行背景色。
 * SelectionContainer 包住整列，长按即可整段复制。
 *
 * 特殊态：计算中 / 加载失败（重试）/ 二进制文件 / 超大文件（+desc）/ 无改动。
 * ETA 纪律：组件库 Miuix（无 material3）；异步全在 [GitDiffState]，本页无副作用协程。
 */
@Composable
internal fun GitDiffScreen(
    stateMachine: GitDiffState,
    onBack: () -> Unit,
    /** 「在编辑器打开」回调：参数为仓库相对路径；null 时右上角图标隐藏（route 接线方负责换算绝对路径）。 */
    onOpenInEditor: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by stateMachine.state.collectAsStateWithLifecycle()

    // 进页触发一次计算；状态机无内部守卫依赖，重复进入 entry 只会在 remember 重建时重跑。
    LaunchedEffect(Unit) { stateMachine.load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回箭头 + 两行标题（文件名 + 完整仓库相对路径）+ 可选「在编辑器打开」。
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixBackButton(onClick = onBack)
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stateMachine.filePath.substringAfterLast('/'),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stateMachine.filePath,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onOpenInEditor != null) {
                IconButton(onClick = { onOpenInEditor(stateMachine.filePath) }) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.git_open_in_editor),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }

        val error = state.error
        when {
            state.loading -> DiffCenteredHint {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.diff_computing),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
            }
            error != null -> DiffCenteredHint {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = stringResource(R.string.diff_load_failed),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.diff_retry),
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { stateMachine.load() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            state.isBinary -> DiffCenteredHint {
                Text(
                    text = stringResource(R.string.diff_binary_file),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                )
            }
            state.isLarge -> DiffCenteredHint {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = stringResource(R.string.diff_file_too_large),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.diff_file_too_large_desc),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                }
            }
            state.rows.isEmpty() -> DiffCenteredHint {
                Text(
                    text = stringResource(R.string.diff_no_changes),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                )
            }
            else -> DiffContent(state = state)
        }
    }
}

/** 特殊态的整页居中容器。 */
@Composable
private fun DiffCenteredHint(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * diff 主体：统计条 + 横向滚动一个实例的行列表。
 * 外层 BoxWithConstraints 持有唯一横向滚动；LazyColumn 宽度取最宽行的实测宽（monospace 下按
 * 最长行字符数测一次即可，不必逐行测量）与视口宽取大者——窄于视口时铺满，宽于视口时整列同步滚。
 */
@Composable
private fun DiffContent(state: GitDiffState.DiffUiState) {
    val maxLineNum = state.rows.maxOfOrNull { maxOf(it.oldLineNum ?: 0, it.newLineNum ?: 0) } ?: 0
    val gutterWidth = remember(maxLineNum) {
        val chars = maxOf(3, maxLineNum.toString().length)
        (chars * 8 + 8).dp
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val contentStyle = remember { TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
    val contentWidth = remember(state.rows, textMeasurer) {
        val longest = state.rows.maxByOrNull { it.text.length }?.text.orEmpty()
        val maxTextPx = if (longest.isEmpty()) 0 else textMeasurer.measure(longest, contentStyle).size.width
        with(density) { (maxTextPx + 24).toDp() } + gutterWidth * 2 + 1.dp + 14.dp
    }
    val hScroll = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 统计条：+n 绿 / −n 红，monospace。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "+${state.addedCount}",
                color = GitDiffColors.Add,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "-${state.removedCount}",
                color = GitDiffColors.Remove,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        SelectionContainer {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .width(maxOf(contentWidth, maxWidth))
                        .fillMaxHeight(),
                    state = rememberLazyListState(),
                ) {
                    items(count = state.rows.size, key = { "diff-row-$it" }) { index ->
                        GitDiffLineRow(row = state.rows[index], gutterWidth = gutterWidth)
                    }
                }
            }
        }
    }
}

/**
 * 单行 diff：旧行号 gutter → 1dp 竖线 → 新行号 gutter → +/−/空格 前缀（14dp）→ 行内容；
 * 增/删行铺淡色背景。gutter 半透明底色与行号右对齐（照 aicode DiffLineRow 版式）。
 */
@Composable
private fun GitDiffLineRow(row: GitDiffState.DiffLineRow, gutterWidth: androidx.compose.ui.unit.Dp) {
    val (bgColor, prefix, prefixColor) = when (row.type) {
        GitDiffState.LineType.ADD -> Triple(GitDiffColors.AddBg, "+", GitDiffColors.Add)
        GitDiffState.LineType.REMOVE -> Triple(GitDiffColors.RemoveBg, "-", GitDiffColors.Remove)
        GitDiffState.LineType.CONTEXT -> Triple(Color.Transparent, " ", MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    val gutterBg = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
    val gutterTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
    val gutterStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(bgColor),
    ) {
        Text(
            text = row.oldLineNum?.toString() ?: "",
            style = gutterStyle,
            color = gutterTextColor,
            textAlign = TextAlign.End,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier
                .width(gutterWidth)
                .fillMaxHeight()
                .background(gutterBg)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)),
        )
        Text(
            text = row.newLineNum?.toString() ?: "",
            style = gutterStyle,
            color = gutterTextColor,
            textAlign = TextAlign.End,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier
                .width(gutterWidth)
                .fillMaxHeight()
                .background(gutterBg)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            text = prefix,
            style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            color = prefixColor,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 4.dp, top = 2.dp)
                .width(14.dp),
        )
        Text(
            text = row.text,
            style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            color = MiuixTheme.colorScheme.onSurface,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier
                .padding(end = 16.dp, top = 2.dp, bottom = 2.dp),
        )
    }
}

/** diff 配色（照 aicode semanticColors.diffAdd/diffRemove 语义，取值与 GitScreen.GitStatusColors 同族）。 */
private object GitDiffColors {
    /** 新增行前景：绿。 */
    val Add = Color(0xFF16A34A)

    /** 删除行前景：红。 */
    val Remove = Color(0xFFDC2626)

    /** 新增行背景：同色低透明铺底。 */
    val AddBg = Color(0xFF16A34A).copy(alpha = 0.12f)

    /** 删除行背景：同色低透明铺底。 */
    val RemoveBg = Color(0xFFDC2626).copy(alpha = 0.12f)
}
