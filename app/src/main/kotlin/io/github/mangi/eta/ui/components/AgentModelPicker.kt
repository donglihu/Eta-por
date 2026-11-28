package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.ModelTestResult
import io.github.mangi.eta.agent.model.ModelTestState
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.filterGroups
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import io.github.mangi.eta.ui.model.modelCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型不多时用输入栏上方的轻量弹窗一次列完；模型较多时弹窗既放不下也不好找，
 * 直接打开底部面板，用搜索和服务商筛选定位。
 */
@Composable
internal fun AgentModelPickerButton(
    state: AgentModelPickerUiState,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    modelTestStates: Map<String, ModelTestState> = emptyMap(),
    onTestModel: (AgentModelOptionUi) -> Unit = {},
    onShowTestDetail: (AgentModelOptionUi) -> Unit = {},
) {
    var showPopup by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    // 每次打开面板换一个 key，搜索词、筛选与滚动位置都从头开始。
    var sheetSession by remember { mutableIntStateOf(0) }
    val selected = state.selectedModel
    // 运行中也能切模型：当前这一轮已经用启动时快照的模型在跑，切换只改会话绑定，
    // 从下一轮生效。以前这里跟着 isStreaming 一起禁用，用户想换模型必须干等任务结束。
    val enabled = !state.isChanging && state.providerGroups.isNotEmpty()
    LaunchedEffect(enabled) {
        if (!enabled) {
            showPopup = false
            showSheet = false
        }
    }
    val density = LocalDensity.current
    val popupWindowInsetPx = with(density) { 14.dp.roundToPx() }
    val popupPositionProvider = remember(popupAnchorTopPx, popupWindowInsetPx) {
        InputPopupPositionProvider(
            inputContainerTopPx = popupAnchorTopPx,
            windowHorizontalInsetPx = popupWindowInsetPx,
        )
    }
    val currentModel = selected?.displayName ?: stringResource(R.string.model_not_selected)
    val switchModelDescription = stringResource(R.string.model_switch_current, currentModel)
    Box(modifier = modifier) {
        IconButton(
            onClick = {
                if (state.modelCount <= ModelPopupLimit) {
                    showPopup = true
                } else {
                    sheetSession += 1
                    showSheet = true
                }
            },
            enabled = enabled,
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
            modifier = Modifier.semantics {
                contentDescription = switchModelDescription
            },
        ) {
            ModelBrandMark(
                modelId = selected?.modelId,
                sourceType = selected?.providerSourceType,
                size = ChatInputActionIconSize,
            )
        }

        OverlayListPopup(
            show = showPopup && popupAnchorTopPx > 0,
            popupPositionProvider = popupPositionProvider,
            alignment = PopupPositionProvider.Align.TopEnd,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
            minWidth = ModelPopupWidth,
        ) {
            ModelQuickList(
                state = state,
                onModelSelected = { modelId ->
                    showPopup = false
                    onModelSelected(modelId)
                },
                modelTestStates = modelTestStates,
                onTestModel = onTestModel,
                onShowTestDetail = onShowTestDetail,
            )
        }

        OverlayBottomSheet(
            show = showSheet,
            title = stringResource(R.string.model_picker_title),
            onDismissRequest = { showSheet = false },
            insideMargin = DpSize(0.dp, 0.dp),
        ) {
            key(sheetSession) {
                ModelSheetContent(
                    state = state,
                    onModelSelected = { modelId ->
                        showSheet = false
                        onModelSelected(modelId)
                    },
                    modelTestStates = modelTestStates,
                    onTestModel = onTestModel,
                    onShowTestDetail = onShowTestDetail,
                )
            }
        }
    }
}

@Composable
private fun ModelQuickList(
    state: AgentModelPickerUiState,
    onModelSelected: (String) -> Unit,
    modelTestStates: Map<String, ModelTestState> = emptyMap(),
    onTestModel: (AgentModelOptionUi) -> Unit = {},
    onShowTestDetail: (AgentModelOptionUi) -> Unit = {},
) {
    val select = rememberConfirmedSelection(onModelSelected)
    val selectedId = select.pendingId ?: state.selectedModel?.id
    val showLabels = state.providerGroups.size > 1
    Column(
        modifier = Modifier
            .width(ModelPopupWidth)
            .verticalScroll(rememberScrollState())
            .padding(top = if (showLabels) 0.dp else 6.dp, bottom = 6.dp),
    ) {
        state.providerGroups.forEach { group ->
            if (showLabels) ModelProviderLabel(group.providerName)
            group.models.forEach { model ->
                ModelPickerRow(
                    model = model,
                    selected = model.id == selectedId,
                    onClick = { select(model.id) },
                    testState = modelTestStates[model.id],
                    onTestModel = onTestModel,
                    onShowTestDetail = { onShowTestDetail(model) },
                )
            }
        }
    }
}

private sealed interface ModelSheetEntry {
    val key: String

    data class Header(val providerId: String, val name: String) : ModelSheetEntry {
        override val key: String get() = "header:$providerId"
    }

    data class Option(val model: AgentModelOptionUi) : ModelSheetEntry {
        override val key: String get() = "model:${model.providerId}:${model.id}"
    }
}

@Composable
private fun ModelSheetContent(
    state: AgentModelPickerUiState,
    onModelSelected: (String) -> Unit,
    modelTestStates: Map<String, ModelTestState> = emptyMap(),
    onTestModel: (AgentModelOptionUi) -> Unit = {},
    onShowTestDetail: (AgentModelOptionUi) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    val entries = remember(state.providerGroups, query, providerFilter) {
        state.filterGroups(query)
            .filter { providerFilter == null || it.providerId == providerFilter }
            .flatMap { group ->
                // 只看一个服务商时，筛选标签已经说明了归属，分组标题只会重复。
                val header = if (providerFilter == null) {
                    listOf(ModelSheetEntry.Header(group.providerId, group.providerName))
                } else {
                    emptyList()
                }
                header + group.models.map(ModelSheetEntry::Option)
            }
    }
    val selectedIndex = entries.indexOfFirst {
        it is ModelSheetEntry.Option && it.model.id == state.selectedModel?.id
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    LaunchedEffect(listState) {
        snapshotFlow { query to providerFilter }.drop(1).collect { listState.scrollToItem(0) }
    }
    val select = rememberConfirmedSelection(onModelSelected)
    val selectedId = select.pendingId ?: state.selectedModel?.id
    val density = LocalDensity.current
    // 面板高度固定，不随搜索结果伸缩；键盘弹出时由面板的约束自然压缩列表区域。
    val sheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.68f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .navigationBarsPadding(),
    ) {
        InputField(
            query = query,
            onQueryChange = { query = it },
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            label = stringResource(R.string.ui_search_model_df5586),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 8.dp),
        )
        if (state.providerGroups.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                item(key = "all") {
                    EtaChoiceChip(
                        text = stringResource(R.string.model_picker_filter_all),
                        selected = providerFilter == null,
                        onClick = { providerFilter = null },
                    )
                }
                items(state.providerGroups, key = { it.providerId }) { group ->
                    EtaChoiceChip(
                        text = group.providerName,
                        selected = providerFilter == group.providerId,
                        onClick = { providerFilter = group.providerId },
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.page_no_matching_model_found_ae7e96),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 48.dp),
                    )
                }
            }
            items(entries, key = ModelSheetEntry::key, contentType = { it::class }) { entry ->
                when (entry) {
                    is ModelSheetEntry.Header -> ModelProviderLabel(entry.name, horizontalPadding = 24.dp)
                    is ModelSheetEntry.Option -> ModelPickerRow(
                        model = entry.model,
                        selected = entry.model.id == selectedId,
                        onClick = { select(entry.model.id) },
                        horizontalPadding = 12.dp,
                        testState = modelTestStates[entry.model.id],
                        onTestModel = onTestModel,
                        onShowTestDetail = { onShowTestDetail(entry.model) },
                    )
                }
            }
        }
    }
}

/**
 * 点选后先把对勾移到新模型上，停留片刻再收起弹层，让人看清选中了谁。
 * 弹层收起会让作用域随组合一起离开，未完成的确认随之取消，不会回写已关闭的界面。
 */
private class ConfirmedSelection(
    private val onConfirm: (String) -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    var pendingId by mutableStateOf<String?>(null)
        private set

    operator fun invoke(modelId: String) {
        if (pendingId != null) return
        pendingId = modelId
        launch {
            delay(SelectionConfirmMs)
            onConfirm(modelId)
        }
    }
}

@Composable
private fun rememberConfirmedSelection(onConfirm: (String) -> Unit): ConfirmedSelection {
    val scope = rememberCoroutineScope()
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    return remember { ConfirmedSelection({ currentOnConfirm(it) }) { block -> scope.launch { block() } } }
}

@Composable
private fun ModelProviderLabel(name: String, horizontalPadding: Dp = 20.dp) {
    Text(
        text = name,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 14.dp, bottom = 4.dp),
    )
}

/**
 * 模型行，布局对齐 aicode：品牌 logo + 名称 + 能力徽章（Image/Tools/上下文/输出）+ 名称下方测速结果行，
 * 行尾是「测速」按钮（测速中转圈）。徽章与结果行只在有数据时出现，避免空行撑高列表。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelPickerRow(
    model: AgentModelOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp = 8.dp,
    testState: ModelTestState? = null,
    onTestModel: (AgentModelOptionUi) -> Unit = {},
    onShowTestDetail: (() -> Unit)? = null,
) {
    val contentColor = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .squircleSurface(color = Color.Transparent, cornerRadius = 12.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelBrandMark(
            modelId = model.modelId,
            sourceType = model.providerSourceType,
            size = 20.dp,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MiuixTheme.textStyles.body2,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ModelBadgesRow(model)
            testState?.result?.let { result ->
                ModelTestResultLine(
                    result = result,
                    onShowDetail = onShowTestDetail,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        ModelTestButton(
            state = testState,
            onTest = { onTestModel(model) },
            modifier = Modifier.padding(start = 8.dp),
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.ui_current_model_a0af8f),
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
        }
    }
}

/** 能力徽章：Image（高亮）｜Tools｜↑ 上下文上限｜↓ 输出上限；四类都没数据时整行不占位。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelBadgesRow(model: AgentModelOptionUi) {
    val hasBadges = model.supportsVision || model.supportsTools ||
        (model.contextWindow ?: 0) > 0 || (model.outputTokens ?: 0) > 0
    if (!hasBadges) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
    ) {
        if (model.supportsVision) {
            ModelBadge(text = stringResource(R.string.model_picker_badge_image), highlight = true)
        }
        if (model.supportsTools) {
            ModelBadge(text = stringResource(R.string.model_picker_badge_tools))
        }
        model.contextWindow?.takeIf { it > 0 }?.let { context ->
            ModelBadge(
                text = formatCompactTokenCount(context),
                icon = Icons.Rounded.ArrowUpward,
            )
        }
        model.outputTokens?.takeIf { it > 0 }?.let { output ->
            ModelBadge(
                text = formatCompactTokenCount(output),
                icon = Icons.Rounded.ArrowDownward,
            )
        }
    }
}

/** 圆角 50 的 pill 徽章，与 aicode ModelTag 同形：高亮蓝底给 Image，其余中性灰底。 */
@Composable
private fun ModelBadge(
    text: String,
    highlight: Boolean = false,
    icon: ImageVector? = null,
) {
    val background = if (highlight) {
        MiuixTheme.colorScheme.primaryContainer
    } else {
        MiuixTheme.colorScheme.surfaceVariant
    }
    val content = if (highlight) {
        MiuixTheme.colorScheme.onPrimaryContainer
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .squircleSurface(color = background, cornerRadius = 50.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = content,
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2.copy(fontSize = 10.sp),
            color = content,
        )
    }
}

/** 行尾「测试」入口，几何对齐 aicode：56×32dp，测速中转圈，空闲时文字按钮；结果行显示在名称下方。 */
@Composable
private fun ModelTestButton(
    state: ModelTestState?,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.width(56.dp).height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state?.running == true) {
            InfiniteProgressIndicator(size = 16.dp)
        } else {
            Text(
                text = stringResource(R.string.model_test_action),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onTest)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 测速结果行，完全对齐 aicode ModelTestResultLine：
 * 成功：绿色对勾 + 「连接正常 · {ms}ms」；失败：红色圆叹号 + HTTP/code 片段（超 20 字取 20 字加 "..."）。
 * 行尾恒显右箭头，整行可点开测速详情。
 */
@Composable
private fun ModelTestResultLine(
    result: ModelTestResult,
    onShowDetail: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val success = result.success
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = onShowDetail != null) { onShowDetail?.invoke() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = if (success) Icons.Rounded.Check else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (success) ModelTestSuccessColor else MiuixTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (success) {
                stringResource(R.string.model_test_success_line, result.latencyMs)
            } else {
                modelTestFailureSnippet(result.message)
            },
            style = MiuixTheme.textStyles.footnote1,
            color = if (success) ModelTestSuccessColor else MiuixTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
        )
    }
}

/** 失败摘要，与 aicode 同规则：正则抓 HTTP 状态码 / code 片段，抓不到取首行前 20 字加 "..."。 */
private fun modelTestFailureSnippet(message: String): String {
    val codeMatch = Regex("""(?i)(HTTP\s*\d{3}|code[:\s]+[a-zA-Z0-9_]+)""").find(message)
    if (codeMatch != null) return codeMatch.value
    val firstLine = message.lines().firstOrNull()
    return when {
        firstLine == null -> "Error"
        firstLine.length > 20 -> firstLine.take(20) + "..."
        else -> firstLine
    }
}

@Composable
internal fun ModelBrandMark(
    modelId: String?,
    sourceType: String?,
    size: Dp,
) {
    val logo = modelOrProviderBrandLogoRes(modelId, sourceType)
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MiuixTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                modifier = Modifier.size(size * 0.56f),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private val ModelPopupWidth = 268.dp

/** 测速成功的绿色：与主题 success 语义一致，失败沿用 colorScheme.error；详情弹层共用。 */
internal val ModelTestSuccessColor = Color(0xFF2BA24C)

/** 不超过这个数量时弹窗一眼就能看完，不需要搜索。 */
private const val ModelPopupLimit = 8

private const val SelectionConfirmMs = 140L
