package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.voice.SpeechDictationButton
import io.github.mangi.eta.ui.voice.SpeechInputFeedback
import io.github.mangi.eta.ui.voice.rememberSpeechInput
import androidx.compose.ui.text.TextRange
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.subagent.SubagentRunRegistry
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.data.model.ReasoningEffort
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.ui.app.ChatDraftStore
import io.github.mangi.eta.ui.model.AgentCommand
import io.github.mangi.eta.ui.model.AgentPaletteEntry
import io.github.mangi.eta.ui.model.CommandPaletteUi
import io.github.mangi.eta.ui.model.commandPaletteEntries
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.agent.model.ModelTestState
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.SkillReferenceUi
import io.github.mangi.eta.ui.model.MessageReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.filterAgentCommands
import io.github.mangi.eta.ui.model.ToolToggleItem
import io.github.mangi.eta.ui.model.ToolToggleKind
import io.github.mangi.eta.ui.model.ToolAccessUiState
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.window.WindowListPopup

private val SendButtonVisualSize = ChatInputActionIconSize
private val SendIconSize = 16.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
private val InputContainerShape = RoundedCornerShape(20.dp)

/** 速度胶囊低于这个宽度就整枚收起，宁可没有，也不去挤右边的发送按钮。 */
private val SpeedCapsuleMinWidth = 72.dp

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AgentChatInputBar(
    input: String,
    /** 草稿键（会话 id）：未发送的输入按它单独存，切走再回来文字还在。 */
    draftKey: String? = null,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    reasoningCapabilities: ModelReasoningCapabilities?,
    /** Plan 模式开关：显示在思考图标右侧，开着时本轮只读、只产出计划。 */
    planMode: Boolean = false,
    onPlanModeChange: (Boolean) -> Unit = {},
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    pendingSkillRefs: List<SkillReferenceUi> = emptyList(),
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    inputSyncVersion: Int = 0,
    isEditingMessage: Boolean,
    editHasLaterTurns: Boolean,
    preserveFollowingMessages: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onTestModel: (AgentModelOptionUi) -> Unit = {},
    /** 每个模型的测速状态：直接回写在模型行上，不弹窗。 */
    modelTestStates: Map<String, ModelTestState> = emptyMap(),
    onShowTestDetail: (AgentModelOptionUi) -> Unit = {},
    /** 召唤/取消专家后通知上层刷新，让提示与下一轮请求看到新状态。 */
    onExpertSelectionChanged: () -> Unit = {},
    /** 外部（专家中心）改过召唤状态时递增，用来让按钮重读。 */
    expertRefreshToken: Int = 0,
    onSubmit: (String) -> Unit,    onInterruptAndSubmit: (String) -> Unit = onSubmit,
    onQueueMessage: (String) -> Unit = {},
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStop: () -> Unit,
    isPaused: Boolean = false,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    /** 未接入模型时，提示条上的「去接入」：跳到模型服务页。 */
    onOpenModelProviders: () -> Unit = {},
    onRemoveFileReference: (String) -> Unit,
    onRemoveSkillReference: (String) -> Unit = {},
    onCancelMessageEdit: () -> Unit,
    /** 可用的斜杠命令；为空时输入框不弹命令面板。 */
    commands: List<AgentCommand> = emptyList(),
    /** 命令主动展开的面板（/help、/skills、/model）；非空时无视输入前缀一直显示。 */
    pinnedPalette: CommandPaletteUi? = null,
    onPickPaletteEntry: (AgentPaletteEntry) -> Unit = {},
    onClosePalette: () -> Unit = {},
    /** 长按条目看完整描述。 */
    onLongPressPaletteEntry: (AgentPaletteEntry) -> Unit = {},
    /** 有结果卡在显示时收起面板，避免两块浮层摞在一起。 */
    suppressPalette: Boolean = false,
    /** 命令执行后由业务状态递增，用来清掉本地输入框里的命令原文。 */
    inputResetToken: Long = 0,
    /** @ 引用：可选的历史消息与已选中的引用。 */
    referenceCandidates: List<MessageReferenceUi> = emptyList(),
    pendingMessageRefs: List<MessageReferenceUi>,
    onAttachReference: (MessageReferenceUi) -> Unit,
    onRemoveReference: (String) -> Unit,
    /** MCP 与技能的开关状态；为空时不显示这两个入口。 */
    toolAccess: ToolAccessUiState,
    onRefreshToolAccess: () -> Unit,
    onToggleTool: (ToolToggleItem, Boolean) -> Unit,
    onAttachSkillReference: (skillId: String, name: String) -> Unit = { _, _ -> },
    onAttachMcpReference: (serverId: String, name: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val textFieldState = rememberTextFieldState(
        initialText = input.ifEmpty { draftKey?.let(AgentInputDrafts::get).orEmpty() },
    )
    // 命令面板只看本地输入：把每个字符推回业务状态会拖着聊天舞台重组，这里用派生状态解耦。
    LaunchedEffect(inputResetToken) {
        if (inputResetToken > 0) textFieldState.clearText()
    }
    val typedText by remember { derivedStateOf { textFieldState.text.toString() } }
    val commandMatches = remember(typedText, commands) { filterAgentCommands(typedText, commands) }
    val prefixEntries = remember(commandMatches) { commandPaletteEntries(commandMatches) }
    val paletteEntries = pinnedPalette?.entries ?: prefixEntries
    val paletteVisible = !suppressPalette && (
        (pinnedPalette?.entries?.isNotEmpty() == true) ||
            (typedText.startsWith("/") && prefixEntries.isNotEmpty())
        )
    // 主动展开的面板遇到普通输入（不是新的斜杠命令）就让位，别挡着打字。
    LaunchedEffect(pinnedPalette, typedText) {
        if (pinnedPalette != null && typedText.isNotBlank() && !typedText.startsWith("/")) {
            onClosePalette()
        }
    }
    // 行首或空格后的 @ 才当引用触发，避免邮箱、@用户名 之类误伤。
    val mentionQuery = remember(typedText) {
        Regex("(^|\\s)@([^\\s@]*)$").find(typedText)?.groupValues?.get(2)
    }
    fun clearMentionToken() {
        val current = textFieldState.text.toString()
        val at = current.lastIndexOf('@')
        if (at >= 0) {
            textFieldState.edit {
                replace(at, length, "")
                selection = TextRange(at)
            }
        }
    }
    val mentionMatches = remember(mentionQuery, referenceCandidates) {
        val query = mentionQuery?.trim().orEmpty()
        if (query.isEmpty()) {
            referenceCandidates.takeLast(MentionPanelLimit).reversed()
        } else {
            referenceCandidates
                .filter { candidate ->
                    // 用全文匹配：预览只有 60 字，靠它搜不到想找的那句原话。
                    candidate.searchText.contains(query, ignoreCase = true) ||
                        candidate.preview.contains(query, ignoreCase = true)
                }
                .takeLast(MentionPanelLimit)
                .reversed()
        }
    }
    // 内存百分比从顶栏搬到这里：顶栏太挤，输入框右上角更顺手，点开就是内存面板。
    val memoryContext = LocalContext.current
    var memoryUsage by remember { mutableStateOf(readMemoryUsage(memoryContext)) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var showTokenSheet by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }
    var showToolSheet by remember { mutableStateOf(false) }
    LaunchedEffect(memoryContext) {
        while (true) {
            memoryUsage = readMemoryUsage(memoryContext)
            kotlinx.coroutines.delay(5_000)
        }
    }
    val draftContext = LocalContext.current
    if (draftKey != null) {
        // 只记录到草稿仓库，不把每个字符推回业务状态，聊天舞台不会被拖着重组成。
        // 同时落盘一份：内存草稿活不过进程回收，切会话/重启后就贴不回来了。
        LaunchedEffect(textFieldState, draftKey) {
            snapshotFlow { textFieldState.text.toString() }
                .collect { text ->
                    AgentInputDrafts.set(draftKey, text)
                    ChatDraftStore.save(draftContext, draftKey, text)
                }
        }
    }
    // 外部塞进来的文字（分享到 Eta 等）：直接灌进当前文本框，不依赖草稿键。
    val inputInjectVersion = AgentInputDrafts.injectVersion
    LaunchedEffect(inputInjectVersion) {
        if (inputInjectVersion == 0) return@LaunchedEffect
        val injected = AgentInputDrafts.takeInjection() ?: return@LaunchedEffect
        if (injected == textFieldState.text.toString()) return@LaunchedEffect
        textFieldState.edit {
            val range = selection
            replace(range.min, range.max, injected)
            selection = TextRange(range.min + injected.length)
        }
    }
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    var previewImageIndex by remember { mutableStateOf<Int?>(null) }
    val recognitionEnabled = speechSettings?.recognitionEnabled == true
    val dictation = rememberSpeechInput { recognized ->
        textFieldState.edit {
            val range = selection
            replace(range.min, range.max, recognized)
            selection = TextRange(range.min + recognized.length)
        }
    }
    LaunchedEffect(isEditingMessage, recognitionEnabled) { dictation.cancel() }
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    var syncedVersion by remember { mutableIntStateOf(inputSyncVersion) }
    // 没有可用模型时把发送键压灰：放行一个必然失败的动作，比直接拦住更伤。
    // 为什么用户能知道原因：输入框上方有一条常驻 InlineAlert，不需要点按钮才知道。
    val modelReady = modelPickerState.hasUsableModel
    val canSend = modelReady && (
        textFieldState.text.isNotBlank() ||
            pendingImages.isNotEmpty() ||
            pendingFileReferences.isNotEmpty() ||
            pendingSkillRefs.isNotEmpty()
        )
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var inputContainerTopPx by remember { mutableIntStateOf(0) }
    val thinkingPopupMaxHeight = with(density) {
        (inputContainerTopPx - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    LaunchedEffect(isEditingMessage, inputSyncVersion) {
        // 编辑态与排队消息撤回由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        val versionChanged = inputSyncVersion != syncedVersion
        if (isEditingMessage || wasEditingMessage || versionChanged) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (isEditingMessage || (versionChanged && input.isNotEmpty())) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        wasEditingMessage = isEditingMessage
        syncedVersion = inputSyncVersion
    }

    // 点缩略图放大预览（与会话内图片预览同一套浮层）。
    previewImageIndex?.let { index ->
        ImagePreviewOverlay(
            images = pendingImages.map { it.dataUrl },
            initialIndex = index,
            onDismiss = { previewImageIndex = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        if (recognitionEnabled) SpeechInputFeedback(dictation)
        if (!modelReady) {
            // 常驻，不自动消失：等用户真的接入了模型，hasUsableModel 变 true 才从这里移除。
            InlineAlert(
                text = "还没有可用的模型，消息发不出去",
                actionLabel = "去接入",
                onAction = onOpenModelProviders,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = queuedMessages.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(150)),
        ) {
            EtaQueuedMessageStrip(
                items = queuedMessages,
                onSendNow = onSteerQueuedMessage,
                onRemove = onRemoveQueuedMessage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingSkillRefs.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingSkillReferenceStrip(
                references = pendingSkillRefs,
                onRemoveReference = onRemoveSkillReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                onPreviewImage = { index -> previewImageIndex = index },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = if (preserveFollowingMessages) {
                    stringResource(R.string.chat_edit_preserve_following)
                } else if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 与输入框内正文同一起点（容器 10dp + 文本 8dp）。
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
            )
        }

        if (pendingMessageRefs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pendingMessageRefs.forEach { reference ->
                    ReferenceChip(reference = reference, onRemove = { onRemoveReference(reference.messageId) })
                }
            }
        }

        AnimatedVisibility(
            visible = mentionQuery != null && referenceCandidates.isNotEmpty(),
            enter = fadeIn(tween(140)) + expandVertically(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            AgentMentionPanel(
                query = mentionQuery.orEmpty(),
                onQueryChange = { next ->
                    // 面板里的输入直接回写正文的 @ 片段，保持单一文本来源。
                    val current = textFieldState.text.toString()
                    val at = current.lastIndexOf('@')
                    if (at >= 0) {
                        textFieldState.edit {
                            replace(at + 1, current.length, next)
                            selection = TextRange(at + 1 + next.length)
                        }
                    }
                },
                candidates = mentionMatches,
                selectedIds = pendingMessageRefs.map { it.messageId }.toSet(),
                onClose = { clearMentionToken() },
                onPick = { reference ->
                    val current = textFieldState.text.toString()
                    val at = current.lastIndexOf('@')
                    if (at >= 0) {
                        textFieldState.edit {
                            replace(0, length, current.substring(0, at))
                            selection = TextRange(at)
                        }
                    }
                    onAttachReference(reference)
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = paletteVisible,
            enter = fadeIn(tween(140)) + expandVertically(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            AgentCommandPalette(
                entries = paletteEntries,
                onPick = { entry -> onPickPaletteEntry(entry) },
                title = pinnedPalette?.title,
                onClose = pinnedPalette?.let { onClosePalette },
                // 与 @ 面板同思路：固定上限。用「输入框上沿」测出来的空间当上限会自我正反馈，
                // 面板越高、输入框越往上、量到的空间越大，最后把输入框挤到键盘后面。
                maxHeight = CommandPaletteHeightLimit,
                onLongPressEntry = onLongPressPaletteEntry,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // 有子智能体在跑时，输入框上方出现「发给」目标；选中后发送只投给它，不进父对话流。
        var liveSubagentIds by remember { mutableStateOf<List<String>>(emptyList()) }
        var selectedSubagent by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                liveSubagentIds = SubagentRunRegistry.liveIds(draftKey)
                delay(1000)
            }
        }

        fun submitToTarget(text: String) {
            val target = selectedSubagent?.takeIf { it in liveSubagentIds }
            if (target != null) {
                SubagentRunRegistry.steer(target, text)
            } else {
                onSubmit(text)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    inputContainerTopPx = coordinates.positionInWindow().y.roundToInt()
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dropShadow(
                        shape = InputContainerShape,
                        shadow = Shadow(
                            radius = 8.dp,
                            color = Color.Black,
                            alpha = 0.08f,
                        ),
                    )
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = 20.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        cornerRadius = 20.dp,
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (liveSubagentIds.isNotEmpty()) {
                    val current = selectedSubagent?.takeIf { it in liveSubagentIds }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val options = buildList {
                            add(null to "父 Agent")
                            liveSubagentIds.take(3).forEach { id -> add(id to id.takeLast(6)) }
                        }
                        options.forEach { (id, label) ->
                            val active = current == id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (active) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.surfaceContainerHigh
                                        },
                                    )
                                    .clickable { selectedSubagent = id }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = if (active) {
                                        MiuixTheme.colorScheme.onPrimary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                )
                            }
                        }
                    }
                }
                // @ 引用面板 / 命令面板打开时收起这行状态，别让它压住输入内容。
                val mentionPanelOpen = mentionQuery != null && referenceCandidates.isNotEmpty()
                if (!mentionPanelOpen && !paletteVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左上角：最近一次响应的平均输出速度（和内存百分比同一行，靠最左）。
                    // 点开就是 Token 用量面板：缓存命中率、缓存/未缓存/输出，以及整轮的耗时与速率。
                    contextUsage.averageTokensPerSecond?.let { speed ->
                        val speedText = java.text.NumberFormat.getNumberInstance()
                            .apply { maximumFractionDigits = 1 }
                            .format(speed)
                        Text(
                            text = "$speedText tok/s",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .clickable { showTokenSheet = true }
                                .padding(start = 4.dp, end = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 入口常驻：数据还没加载完时也要能点开看，否则会像“功能没生效”。
                    run {
                        Text(
                            text = if (toolAccess.mcpItems.isEmpty()) {
                                stringResource(R.string.tool_access_mcp_chip_plain)
                            } else if (toolAccess.mcpEnabled == 0) {
                                stringResource(R.string.tool_access_mcp_chip_off)
                            } else {
                                stringResource(
                                    R.string.tool_access_mcp_chip,
                                    toolAccess.mcpEnabled,
                                    toolAccess.mcpItems.size,
                                )
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .clickable { showToolSheet = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Text(
                            text = if (toolAccess.skillItems.isEmpty()) {
                                stringResource(R.string.tool_access_skill_chip_plain)
                            } else {
                                stringResource(R.string.tool_access_skill_chip, toolAccess.skillEnabled)
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .clickable { showToolSheet = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.memory_inline_label, memoryUsage.usedPercent),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .clickable { showMemorySheet = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                }
                MemoryUsageSheet(show = showMemorySheet, onDismiss = { showMemorySheet = false })
                ContextUsageSheet(
                    show = showContextSheet,
                    usage = contextUsage,
                    canCompact = canCompactContext && !isStreaming,
                    onCompact = onCompactContext,
                    onDismiss = { showContextSheet = false },
                )
                TokenUsageSheet(
                    show = showTokenSheet,
                    usage = contextUsage.latestUsage,
                    onDismiss = { showTokenSheet = false },
                )
                ToolAccessSheet(
                    show = showToolSheet,
                    state = toolAccess,
                    onRefresh = onRefreshToolAccess,
                    onToggle = onToggleTool,
                    onDismiss = { showToolSheet = false },
                    onAttachReference = { item ->
                        when (item.kind) {
                            ToolToggleKind.Skill -> onAttachSkillReference(item.id, item.title)
                            ToolToggleKind.Mcp -> onAttachMcpReference(item.id, item.title)
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 40.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    // 占位文字与输入正文共用同一行高，首字输入时基线不跳动。
                    val inputTextStyle = MiuixTheme.textStyles.body1.copy(lineHeight = 22.sp)
                    val character = LocalRoleplayChat.current
                    if (textFieldState.text.isBlank()) {
                        Text(
                            text = when {
                                // 角色对话的占位文字跟随角色，避免“交给 Eta”打断沉浸感。
                                character != null && isStreaming ->
                                    stringResource(R.string.chat_character_typing, character.characterName)
                                character != null -> stringResource(R.string.chat_character_input_hint, character.characterName)
                                isStreaming && isPaused -> stringResource(R.string.chat_eta_paused)
                                isStreaming -> stringResource(R.string.chat_eta_working)
                                else -> stringResource(R.string.chat_input_hint)
                            },
                            style = inputTextStyle,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        textStyle = inputTextStyle.copy(color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(
                            minHeightInLines = 1,
                            maxHeightInLines = 6,
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧组：附件、输出速度、思考档位。整组只吃「右侧组排完之后剩下的」宽度，
                    // 于是右侧的上下文环 / 模型 / 暂停 / 停止 / 发送永远守得住自己的位置。
                    Row(
                        modifier = Modifier.weight(1f, fill = true),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isEditingMessage) {
                            IconButton(
                                onClick = onCancelMessageEdit,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                                    modifier = Modifier.size(ChatInputActionIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        } else {
                            AgentAttachmentPickerButton(
                                popupAnchorTopPx = inputContainerTopPx,
                                popupMaxHeight = thinkingPopupMaxHeight,
                                onAttachImage = onAttachImage,
                                onAttachFiles = onAttachFiles,
                                onAttachFolder = onAttachFolder,
                                onAttachFilePath = onAttachFilePath,
                            )


                            if (availableReasoningEfforts.isNotEmpty()) {
                                ThinkingEffortChip(
                                    effort = reasoningEffort,
                                    options = availableReasoningEfforts,
                                    capabilities = reasoningCapabilities,
                                    enabled = !isStreaming,
                                    onEffortChange = onReasoningEffortChange,
                                )
                            }

                            // Plan 开关紧跟思考图标：两者都是「这一轮怎么跑」的设置，放一起最好找。
                            // 运行中也允许切换：改的是下一轮的策略，不必等当前任务结束。
                            PlanModeChip(
                                active = planMode,
                                enabled = true,
                                onClick = { onPlanModeChange(!planMode) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                        if (showContextUsage) {
                            AgentContextUsageButton(
                                usage = contextUsage,
                                onOpenPanel = { showContextSheet = true },
                                onCompact = onCompactContext,
                                canCompact = canCompactContext && !isStreaming,
                            )

                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        if (recognitionEnabled) {
                            SpeechDictationButton(dictation, enabled = !isCompacting)
                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        AgentModelPickerButton(
                            state = modelPickerState,
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onModelSelected = onModelSelected,
                            modelTestStates = modelTestStates,
                            onTestModel = onTestModel,
                            onShowTestDetail = onShowTestDetail,
                        )

                        // 专家入口在模型按钮右侧（对齐 miniAI）：先定「谁」，再定「用哪个模型」。
                        // 运行中收起：右侧组要腾位置给「暂停 / 停止 / 发送」，留着它会把发送按钮挤出屏幕。
                        if (!isStreaming) {
                            ExpertPickerButton(
                                popupAnchorTopPx = inputContainerTopPx,
                                popupMaxHeight = thinkingPopupMaxHeight,
                                refreshToken = expertRefreshToken,
                                onSelectionChanged = onExpertSelectionChanged,
                            )
                        }

                        if (isStreaming) {
                            // 运行中一变三：暂停/恢复、停止、插话发送；插话走 steering，不打断当前运行。
                            IconButton(
                                onClick = if (isPaused) onResume else onPause,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = if (isPaused) {
                                        stringResource(R.string.overlay_resume)
                                    } else {
                                        stringResource(R.string.overlay_pause)
                                    },
                                    modifier = Modifier.size(ChatInputActionIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = onStop,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = stringResource(R.string.chat_stop),
                                    modifier = Modifier.size(ChatInputActionIconSize),
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(ChatInputActionSize)
                                    .combinedClickable(
                                        enabled = canSend,
                                        onClick = {
                                            if (canSend) {
                                                dictation.cancel()
                                                val submittedText = textFieldState.text.toString()
                                                textFieldState.clearText()
                                                submitToTarget(submittedText)
                                            }
                                        },
                                        onLongClick = {
                                            if (canSend) {
                                                dictation.cancel()
                                                val submittedText = textFieldState.text.toString()
                                                textFieldState.clearText()
                                                onQueueMessage(submittedText)
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(SendButtonVisualSize)
                                        .clip(CircleShape)
                                        .background(if (canSend) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowUpward,
                                        contentDescription = stringResource(R.string.chat_send),
                                        modifier = Modifier.size(SendIconSize),
                                        tint = if (canSend) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (canSend) {
                                        dictation.cancel()
                                        val submittedText = textFieldState.text.toString()
                                        textFieldState.clearText()
                                        submitToTarget(submittedText)
                                    }
                                },
                                enabled = canSend,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(SendButtonVisualSize)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowUpward,
                                        contentDescription = stringResource(R.string.chat_send),
                                        modifier = Modifier.size(SendIconSize),
                                        tint = MiuixTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

}

/**
 * 思考强度入口，布局对齐 aicode ReasoningEffortSelector：闪电图标 + 当前档位短名，
 * 整块可点，点开底部弹层（思考总开关 + 生效提示 + 档位列表 + 默认标记）。
 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    capabilities: ModelReasoningCapabilities?,
    enabled: Boolean,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val menuEnabled = enabled && options.isNotEmpty()
    LaunchedEffect(menuEnabled) {
        if (!menuEnabled) showSheet = false
    }
    // 图标颜色即状态：开着蓝色、关闭灰色（老大定稿：不显示档位文字）。
    val contentColor by animateColorAsState(
        targetValue = when {
            !menuEnabled -> MiuixTheme.colorScheme.disabledOnSurface
            effort != ReasoningEffort.OFF -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_content",
    )
    Box(modifier = modifier) {
        IconButton(
            onClick = { showSheet = true },
            enabled = menuEnabled,
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = stringResource(R.string.chat_reasoning_effort, effort.displayName),
                modifier = Modifier.size(ThinkingIconSize),
                tint = contentColor,
            )
        }
    }
    if (showSheet && menuEnabled) {
        OverlayBottomSheet(
            show = true,
            title = stringResource(R.string.chat_reasoning_sheet_title),
            onDismissRequest = { showSheet = false },
        ) {
            ThinkingEffortSheetContent(
                effort = effort,
                options = options,
                capabilities = capabilities,
                onEffortChange = onEffortChange,
                onDismiss = { showSheet = false },
            )
        }
    }
}

/** 思考强度弹层，对齐 aicode：标题行 + 思考总开关 + 生效提示 + 档位列表（默认档打标、选中打勾）。 */
@Composable
private fun ThinkingEffortSheetContent(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    capabilities: ModelReasoningCapabilities?,
    onEffortChange: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit,
) {
    val mandatory = capabilities?.mandatory == true
    // 能力未知（无 capabilities 或只有 DEFAULT）也给出开关：交给用户试，弹层会提示「可能不生效」。
    val showToggle = !mandatory
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.chat_reasoning_toggle),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (showToggle) {
                EtaSwitch(
                    checked = effort != ReasoningEffort.OFF,
                    onCheckedChange = { on ->
                        onEffortChange(
                            if (on) {
                                // 打开思考回到哪一档：优先模型默认档，其次「中」，再取最高可用档；都没有就跟随默认。
                                capabilities?.defaultEffort?.takeIf { it != ReasoningEffort.OFF && it in options }
                                    ?: options.firstOrNull { it == ReasoningEffort.MEDIUM }
                                    ?: options.lastOrNull { it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT }
                                    ?: ReasoningEffort.DEFAULT
                            } else {
                                ReasoningEffort.OFF
                            },
                        )
                        onDismiss()
                    },
                )
            }
        }
        ThinkingEffortHints(effort, capabilities)
        options.forEach { option ->
            val selected = option == effort
            val isDefault = capabilities?.defaultEffort == option
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        onEffortChange(option)
                        onDismiss()
                    }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = reasoningEffortLabel(option),
                    style = MiuixTheme.textStyles.body2,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isDefault) {
                    Text(
                        text = stringResource(R.string.chat_reasoning_default_marker),
                        style = MiuixTheme.textStyles.footnote2.copy(fontSize = 10.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        modifier = Modifier
                            .squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceVariant,
                                cornerRadius = 50.dp,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 弹层提示行，对齐 aicode ReasoningHintLines：思考恒开、能力未知、档位被自动降级三种情形。 */
@Composable
private fun ThinkingEffortHints(
    effort: ReasoningEffort,
    capabilities: ModelReasoningCapabilities?,
) {
    val mandatoryHint = stringResource(R.string.chat_reasoning_hint_mandatory)
    val unknownHint = stringResource(R.string.chat_reasoning_hint_unknown)
    val normalized = capabilities?.normalize(effort)
    val clampedHint = if (normalized != null && effort != ReasoningEffort.OFF && normalized != effort) {
        stringResource(R.string.chat_reasoning_hint_clamped, reasoningEffortLabel(normalized))
    } else {
        null
    }
    val hints = buildList {
        if (capabilities?.mandatory == true) add(mandatoryHint)
        if (capabilities == null || capabilities.supportedEfforts.isEmpty()) add(unknownHint)
        clampedHint?.let(::add)
    }
    hints.forEach { hint ->
        Text(
            text = hint,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 档位短名（对齐 aicode 档位标签）：关闭/默认/极简/低/中/高/极高/最高。 */
@Composable
private fun reasoningEffortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.OFF -> R.string.chat_reasoning_level_off
        ReasoningEffort.DEFAULT -> R.string.chat_reasoning_level_default
        ReasoningEffort.MINIMAL -> R.string.chat_reasoning_level_minimal
        ReasoningEffort.LOW -> R.string.chat_reasoning_level_low
        ReasoningEffort.MEDIUM -> R.string.chat_reasoning_level_medium
        ReasoningEffort.HIGH -> R.string.chat_reasoning_level_high
        ReasoningEffort.XHIGH -> R.string.chat_reasoning_level_xhigh
        ReasoningEffort.MAX -> R.string.chat_reasoning_level_max
    }
)

/** Plan 开关：一枚小胶囊，选中才填浅底；宽度压在 30dp 内，右侧按钮的位置不会被它挤走。 */
@Composable
private fun PlanModeChip(
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (active) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "plan_chip_container",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
            active -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = tween(durationMillis = 160),
        label = "plan_chip_content",
    )
    val border by animateColorAsState(
        targetValue = if (active) Color.Transparent else MiuixTheme.colorScheme.dividerLine,
        animationSpec = tween(durationMillis = 160),
        label = "plan_chip_border",
    )
    Text(
        text = stringResource(R.string.chat_plan_mode_chip),
        style = MiuixTheme.textStyles.footnote1,
        color = content,
        maxLines = 1,
        modifier = modifier
            .squircleBorder(width = 1.dp, color = border, cornerRadius = 14.dp)
            .squircleSurface(color = container, cornerRadius = 14.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    onPreviewImage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEachIndexed { index, image ->
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .clickable { onPreviewImage(index) },
            ) {
                // 60dp 小方框只按 256 长边解码：不传 maxPx 会全量解码，8 张待发图最坏 66MB 常驻输入条。
                rememberDataUrlBitmap(image.dataUrl, 256)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // 可见圆点保持小巧，触控区放大到右上角 32dp，避免误触缩略图。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .clickable(interactionSource = null, indication = null) { onRemoveImage(image.id) },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.58f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.ui_remove_image_089db3),
                            modifier = Modifier.size(11.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** 引用面板一次最多显示多少条候选。 */
private const val MentionPanelLimit = 20

/** 输入框上的引用 chip：显示序号、角色与删除。 */
@Composable
private fun ReferenceChip(reference: MessageReferenceUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#" + reference.sequence + (if (reference.fromUser) " 我" else " Eta"),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "×",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(horizontal = 4.dp),
        )
    }
}

/** 命令面板的高度上限：约 4 条（标题 + 两行描述），配合收起状态行后输入框始终可完整显示。 */
private val CommandPaletteHeightLimit = 200.dp
