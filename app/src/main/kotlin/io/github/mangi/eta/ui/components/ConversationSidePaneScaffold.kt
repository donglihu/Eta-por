package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

private object ConversationPaneMetrics {
    val PaneMaxWidth = 340.dp
    val PaneWidthFraction = 0.84f
    const val ChatScrimAlpha = 0.16f
    const val SettleDampingRatio = 1f
    const val SettleStiffness = 146f
    const val SettleVisibilityThresholdPx = 0.5f
    const val SettlePositionThresholdFraction = 0.5f
}

@Composable
internal fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onNewConversationInCategory: (String?) -> Unit = { _ -> onNewConversation() },
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onMoveToCategory: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onConversationPin: (ConversationSummaryUi) -> Unit,
    onBatchDelete: (List<ConversationSummaryUi>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRootSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenTasks: () -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
    onDeleteCategoryConfirmed: (String) -> Unit,
    /** 拖放落点：目标分组 id，null 表示移出分组。 */
    onDropConversationToCategory: ((ConversationSummaryUi, String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 当前侧滑目标面：Closed 仅对话 / Open 会话列表 / Workspace 文件面板（三锚点，Git 走全屏页）。 */
    pane: ConversationPaneAnchor = ConversationPaneAnchor.Closed,
    onWorkspaceOpen: () -> Unit = {},
    onWorkspaceDismiss: () -> Unit = {},
    workspaceContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val paneWidth = minOf(maxWidth * ConversationPaneMetrics.PaneWidthFraction, ConversationPaneMetrics.PaneMaxWidth)
        val paneWidthPx = with(density) { paneWidth.toPx() }
        val anchors = remember(paneWidthPx, screenWidthPx) {
            DraggableAnchors {
                ConversationPaneAnchor.Closed at 0f
                ConversationPaneAnchor.Open at paneWidthPx
                // 负向锚点：同一个 anchoredDraggable 向左拖就能到工作区，
                // 不需要第二套手势，也就不会和右滑/系统返回抢事件。
                ConversationPaneAnchor.Workspace at -screenWidthPx
            }
        }
        val paneDragState = remember {
            AnchoredDraggableState(
                initialValue = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed,
                anchors = anchors,
            )
        }
        val settleAnimation = remember {
            spring<Float>(
                dampingRatio = ConversationPaneMetrics.SettleDampingRatio,
                stiffness = ConversationPaneMetrics.SettleStiffness,
                visibilityThreshold = ConversationPaneMetrics.SettleVisibilityThresholdPx,
            )
        }
        val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
            state = paneDragState,
            positionalThreshold = { distance ->
                distance * ConversationPaneMetrics.SettlePositionThresholdFraction
            },
            animationSpec = settleAnimation,
        )
        // 工作区：完全复用同一套状态。吸附到 Workspace 锚点即算打开，回到 Closed 即关闭。
        // 不用第二套手势，也不用自己判方向——拖动方向由锚点位置决定。
        val currentOnWorkspaceOpen by rememberUpdatedState(onWorkspaceOpen)
        val currentOnWorkspaceDismiss by rememberUpdatedState(onWorkspaceDismiss)
        // 高亮判定用「过半」，与吸附阈值一致：往左拉过屏宽一半就算进了工作区，
        // 回程再过半才回到对话区。这样高亮和最终落点不会互相矛盾。
        LaunchedEffect(paneDragState, screenWidthPx) {
            // 根因修复（2026-11-28）：本效果把「实时位移」映射成 Workspace/Closed 并回调
            // onWorkspaceOpen/onWorkspaceDismiss，而这两个回调会写回 pane 状态再驱动 pane-effect 的
            // animateTo——是一个反馈环。丢失 distinctUntilChanged 后，点击触发的 open 动画在最初几帧
            // （offset 仅离开锚点几个像素、还未过半）会被映射成 Closed → onWorkspaceDismiss →
            // pane 翻回 Closed → pane-effect 重启并取消刚起步的 animateTo(Workspace)，动画永远起不来；
            // 反向（点聊天/工作区收面板）同理会在过半点被 onWorkspaceOpen 顶回去。滑动之所以没事，
            // 是因为手势持有 UserInput 优先级的 mutex，effect 的 animateTo(Default) 被拒绝后静默死亡，
            // 不与手指抢状态。恢复 distinctUntilChanged 后，动画中间帧的同值发射被抑制，
            // 只在真正越过半屏阈值的那一帧同步一次状态，点击与滑动重新等价。
            snapshotFlow { paneDragState.offset }
                .filter { !it.isNaN() }
                .map { offset ->
                    when {
                        offset < -screenWidthPx / 2f -> ConversationPaneAnchor.Workspace
                        else -> ConversationPaneAnchor.Closed
                    }
                }
                .distinctUntilChanged()
                .collect { targetPane ->
                    when (targetPane) {
                        ConversationPaneAnchor.Workspace -> currentOnWorkspaceOpen()
                        else -> currentOnWorkspaceDismiss()
                    }
                }
        }
        LaunchedEffect(pane, paneWidthPx) {
            // 缺陷 C 修复（2026-11-28）：原「拖动中」守卫是一次性的——settle 动画进行中外部切面
            // 会被静默丢弃，之后不再有人补发。改为：仅当已 settle 且停在目标面时跳过；
            // 否则先等当前 settle 结束（snapshotFlow 观察 isAnimationRunning），再补一次 animateTo。
            fun settledAt(target: ConversationPaneAnchor): Boolean =
                paneDragState.settledValue == target &&
                    !paneDragState.isAnimationRunning &&
                    abs(paneDragState.offset - paneDragState.anchors.positionOf(target)) < 0.5f
            if (settledAt(pane)) return@LaunchedEffect
            snapshotFlow { paneDragState.isAnimationRunning }.first { !it }
            if (!settledAt(pane)) {
                paneDragState.animateTo(pane, settleAnimation)
            }
        }
        val currentVisible by rememberUpdatedState(visible)
        val currentOnOpen by rememberUpdatedState(onOpen)
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        val hapticFeedback = LocalHapticFeedback.current
        val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
        val dragInteractions = remember { MutableInteractionSource() }
        val isDragging by dragInteractions.collectIsDraggedAsState()
        LaunchedEffect(dragInteractions) {
            dragInteractions.interactions.collect { interaction ->
                if (interaction is DragInteraction.Stop) {
                    currentHapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                }
            }
        }
        val isRevealed by remember(paneDragState) {
            derivedStateOf {
                val offset = paneDragState.offset
                !offset.isNaN() && offset > 0.5f
            }
        }
        val panelListState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val closePane: () -> Unit = {
            currentHapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            focusManager.clearFocus()
            keyboard?.hide()
            if (currentVisible) {
                currentOnDismiss()
            } else {
                scope.launch { paneDragState.animateTo(ConversationPaneAnchor.Closed, settleAnimation) }
            }
        }
        LaunchedEffect(isRevealed) {
            if (isRevealed) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }

        SideEffect {
            paneDragState.updateAnchors(anchors)
        }

        LaunchedEffect(visible, paneWidthPx) {
            if (!visible && isRevealed) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
            // 缺陷 B 修复（2026-11-28）：目标面为工作区时由 pane-effect 独占驱动。
            // visible 翻转与 pane 切面同时重启时，这里无条件 animateTo(Open/Closed) 会压过工作区目标。
            if (pane == ConversationPaneAnchor.Workspace) return@LaunchedEffect
            val target = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed
            if (paneDragState.targetValue != target || paneDragState.settledValue != target) {
                paneDragState.animateTo(target, settleAnimation)
            }
        }

        LaunchedEffect(paneDragState) {
            conversationPaneSettledValues(paneDragState) { isDragging }.collectLatest { settledValue ->
                val settledOpen = settledValue == ConversationPaneAnchor.Open
                if (settledOpen != currentVisible) {
                    if (settledOpen) {
                        currentOnOpen()
                    } else {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        currentOnDismiss()
                    }
                }
            }
        }

        // NavDisplay 的退出条目在转场期间仍会保留组合；仅允许已稳定显示的首页
        // 处理侧栏返回，避免它抢先消费二级页面的第一次返回事件。
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = isRevealed &&
                backHandlerEnabled &&
                sceneLifecycleState == Lifecycle.State.RESUMED,
            onBackCompleted = closePane,
        )

        Box(
            modifier = Modifier.fillMaxSize()
                .clipToBounds()
                .background(conversationPaneContainerColor())
                // 手势由共同容器拥有，侧栏和聊天舞台均可拖动；子级先处理滚动与选择。
                .anchoredDraggable(
                    state = paneDragState,
                    reverseDirection = false,
                    orientation = Orientation.Horizontal,
                    enabled = backHandlerEnabled && sceneLifecycleState == Lifecycle.State.RESUMED,
                    flingBehavior = flingBehavior,
                    interactionSource = dragInteractions,
                ),
            contentAlignment = AbsoluteAlignment.TopLeft,
        ) {
            // 两个槽位并排测量和放置，由同一位移推移；不在聊天内容下面叠放侧栏。
            // 侧栏保留组合，避免每次起手重新创建列表和搜索框。
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    ConversationPanePanel(
                        state = state,
                        width = paneWidth,
                        listState = panelListState,
                        onSearchChange = onSearchChange,
                        onNewConversation = onNewConversation,
                        onNewConversationInCategory = onNewConversationInCategory,
                        onConversationSelected = onConversationSelected,
                        onConversationRename = onConversationRename,
                        onMoveToCategory = onMoveToCategory,
                        onDropConversationToCategory = onDropConversationToCategory,
                        onConversationExport = onConversationExport,
                        onConversationDelete = onConversationDelete,
                        onConversationPin = onConversationPin,
                        onBatchDelete = onBatchDelete,
                        onOpenSettings = onOpenSettings,
                        onOpenRootSettings = onOpenRootSettings,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onOpenModelProviders = onOpenModelProviders,
                        onOpenTools = onOpenTools,
                        onOpenSkills = onOpenSkills,
                        onOpenCharacters = onOpenCharacters,
                        onOpenPermissions = onOpenPermissions,
                        onOpenTasks = onOpenTasks,
                        onCreateCategory = onCreateCategory,
                        onRenameCategory = onRenameCategory,
                        onMoveCategory = onMoveCategory,
                        onDeleteCategoryConfirmed = onDeleteCategoryConfirmed,
                        modifier = Modifier
                            .focusProperties { onEnter = { if (!isRevealed) cancelFocusChange() } }
                            .focusGroup()
                            .then(if (!isRevealed) Modifier.clearAndSetSemantics {} else Modifier),
                    )
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .drawWithContent {
                                drawContent()
                                val progress = conversationPaneMotion(paneDragState.offset, paneWidthPx).progress
                                drawRect(Color.Black, alpha = ConversationPaneMetrics.ChatScrimAlpha * progress)
                            }
                            .background(MiuixTheme.colorScheme.background),
                    ) {
                        Box(
                            modifier = Modifier
                                .focusProperties {
                                    onEnter = { if (isRevealed) cancelFocusChange() }
                                }
                                .focusGroup()
                                .then(if (isRevealed) Modifier.clearAndSetSemantics {} else Modifier),
                        ) {
                            content()
                        }
                        if (isRevealed) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable(
                                    interactionSource = null,
                                    indication = null,
                                    onClickLabel = stringResource(R.string.action_close),
                                    onClick = closePane,
                                ),
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val panel = measurables[0].measure(
                    Constraints.fixed(paneWidthPx.roundToInt(), constraints.maxHeight),
                )
                val page = measurables[1].measure(
                    Constraints.fixed(constraints.maxWidth, constraints.maxHeight),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // 物理左侧是会话区；不随 RTL 改变两个槽位的关系。
                    val motion = conversationPaneMotion(paneDragState.offset, paneWidthPx)
                    val offset = motion.foregroundOffset.roundToInt()
                    panel.placeWithLayer(offset - panel.width, 0)
                    page.placeWithLayer(offset, 0)
                }
            }

            // 工作区层：位置 = 当前位移 + 屏宽，所以 offset 走到 -屏宽时它正好铺满。
            if (workspaceContent != null) {
                val raw = paneDragState.offset
                val wsOffset = if (raw.isNaN()) 0f else raw
                Box(
                    modifier = Modifier
                        .offset { IntOffset((wsOffset + screenWidthPx).roundToInt(), 0) }
                        .fillMaxSize(),
                ) { workspaceContent() }
            }

        }
    }
}
