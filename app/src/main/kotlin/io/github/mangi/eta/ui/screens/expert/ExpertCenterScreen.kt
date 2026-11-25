package io.github.mangi.eta.ui.screens.expert

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.expert.Expert
import io.github.mangi.eta.agent.expert.ExpertCategory
import io.github.mangi.eta.agent.expert.ExpertSource
import io.github.mangi.eta.agent.expert.ExpertStore
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelProviderGroupUi
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private val CardHorizontalPadding = 16.dp
private val CardBottomPadding = 16.dp

/**
 * 专家中心：搜索 + 分类筛选 + 专家列表。
 *
 * 点卡片进详情页（启用/置顶开关、摘要、详细信息、提示词），详情页右上角铅笔进编辑；
 * 召唤入口在会话输入栏，不在这里抢位置。对齐 miniAI 的「设置 → 专家」结构。
 */
@Composable
internal fun ExpertCenterScreen(
    onBack: () -> Unit,
    onOpenExpert: (String) -> Unit = {},
    /** 从详情页返回时递增：列表用 remember 缓存，不重读就会停在改之前的样子。 */
    refreshToken: Int = 0,
) {
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<ExpertCategory?>(null) }
    var editing by remember { mutableStateOf<ExpertDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<Expert?>(null) }

    /**
     * 读盘放 IO：`ExpertStore.loadExperts()` 要读 SharedPreferences 再解析 JSON，
     * 直接写在 `remember { mutableStateOf(...) }` 里等于在组合阶段（主线程）做磁盘 IO。
     *
     * 用 [produceState] 驱动而不是 `rememberCoroutineScope()`：后者是 ETA 明令禁止的用法
     * （页面销毁即取消，且协程归属不清）。`refreshToken` 作为 key，从详情页返回时自动重读。
     */
    var experts by remember { mutableStateOf(ExpertStore.cachedExperts()) }
    var selection by remember { mutableStateOf(ExpertStore.cachedSelection()) }
    // produceState 的 key 变化时重启 producer：从详情页返回（refreshToken 递增）就重读一次。
    LaunchedEffect(refreshToken) {
        val disk = withContext(Dispatchers.IO) {
            ExpertStore.loadExperts() to ExpertStore.loadSelection()
        }
        experts = disk.first
        selection = disk.second
    }

    /** 本地改动（保存/删除/召唤）后同步刷新：数据已经在内存缓存里，不必再读盘。 */
    fun refresh() {
        experts = ExpertStore.cachedExperts()
        selection = ExpertStore.cachedSelection()
    }

    val visible = experts.filter { expert ->
        val hitQuery = query.isBlank() ||
            expert.title.contains(query, ignoreCase = true) ||
            expert.name.contains(query, ignoreCase = true) ||
            expert.description.contains(query, ignoreCase = true)
        val hitCategory = categoryFilter == null || expert.category == categoryFilter
        hitQuery && hitCategory
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.expert_center_title),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { editing = ExpertDraft() },
                modifier = Modifier.semantics {
                    contentDescription = "新建专家"
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
        // 编辑面板必须挂在 overlay slot 里：Miuix 的 OverlayBottomSheet 依赖页面骨架
        // 提供的浮层宿主，放在 Scaffold 外侧不会真正弹出（2026-11-20 反馈「无法添加专家」）。
        overlay = {
            editing?.let { draft ->
                ExpertEditorSheet(
                    draft = draft,
                    onDismiss = { editing = null },
                    onSave = { saved ->
                        ExpertStore.upsert(saved)
                        editing = null
                        refresh()
                    },
                )
            }
        },
    ) {
        item(key = "expert-header") {
            ExpertHeaderCard(
                totalCount = experts.size,
                enabledCount = experts.count { it.enabled },
                currentTitle = experts.firstOrNull { it.name == selection.expertName }?.title,
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(top = 4.dp, bottom = 12.dp),
            )
        }
        item(key = "expert-search") {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.expert_center_search_placeholder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = 10.dp),
            )
        }
        item(key = "expert-category") {
            ExpertCategoryChips(
                selected = categoryFilter,
                onSelect = { categoryFilter = it },
                includeAll = true,
                allLabel = stringResource(R.string.expert_center_category_all),
            )
        }
        val current = experts.firstOrNull { it.name == selection.expertName }
        if (current != null) {
            item(key = "expert-current-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.expert_center_current_label))
            }
            item(key = "expert-current") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    EtaPreference(
                        title = current.title,
                        summary = stringResource(R.string.expert_center_current_hint),
                        startAction = {
                            EtaPreferenceIcon(Icons.Rounded.AutoAwesome, tint = EtaPreferenceColors.Blue)
                        },
                        onClick = {
                            ExpertStore.dismiss()
                            refresh()
                        },
                        onClickLabel = stringResource(R.string.expert_center_action_dismiss),
                    )
                }
            }
        }
        if (visible.isNotEmpty()) {
            item(key = "expert-list-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.expert_center_list_title))
            }
            // 专家卡片独立成行（不再挤在同一张分组卡里）：
            // 每张卡片有自己的字标、状态徽章和操作区，视觉层级比一整块列表清楚得多。
            visible.forEachIndexed { index, expert ->
                item(key = "expert-card-${expert.name}-$index") {
                    ExpertCard(
                        expert = expert,
                        isActive = selection.expertName == expert.name,
                        onOpen = { onOpenExpert(expert.name) },
                        onDelete = { deleteTarget = expert },
                        modifier = Modifier
                            .padding(horizontal = CardHorizontalPadding)
                            .padding(bottom = 10.dp),
                    )
                }
            }
        } else {
            item(key = "expert-empty") {
                ListEmptyState(
                    title = if (experts.isEmpty()) {
                        stringResource(R.string.expert_center_empty_title)
                    } else {
                        stringResource(R.string.expert_center_no_match_title)
                    },
                    summary = if (experts.isEmpty()) {
                        stringResource(R.string.expert_center_empty_hint)
                    } else {
                        stringResource(R.string.expert_center_no_match_hint)
                    },
                    icon = Icons.Rounded.AutoAwesome,
                    action = {
                        EtaTextButton(
                            text = stringResource(R.string.expert_center_create),
                            onClick = { editing = ExpertDraft() },
                        )
                    },
                )
            }
        }
    }

    deleteTarget?.let { target ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.expert_center_delete_title),
            summary = stringResource(R.string.expert_center_delete_summary, target.title),
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.expert_center_delete_confirm),
                destructive = true,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    ExpertStore.delete(target.name)
                    deleteTarget = null
                    refresh()
                },
            )
        }
    }
}

internal fun Expert.toDraft(): ExpertDraft = ExpertDraft(
    name = name,
    displayName = displayName,
    description = description,
    category = category,
    prompt = prompt,
    skills = skills,
    enabled = enabled,
    pinned = pinned,
    source = source,
    providerId = providerId,
    modelId = modelId,
    tools = tools,
    disallowedTools = disallowedTools,
    inject = inject,
)

/**
 * 页面顶部的概览卡。
 *
 * 原来这一屏只有「搜索 + 分类 + 一列列表」，用户进来第一眼不知道「专家」是干什么的、
 * 手上到底有几位、现在生效的是谁。概览卡把这三件事一次说清：
 * 标题与副标题交代定位，右上角给出计数，中间一行显示当前生效的专家。
 */
@Composable
private fun ExpertHeaderCard(
    totalCount: Int,
    enabledCount: Int,
    currentTitle: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    EtaCard(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = CardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurface,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.expert_center_title),
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.expert_center_subtitle),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            ExpertCountBadge(totalCount = totalCount, enabledCount = enabledCount)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Spacer(modifier = Modifier.height(12.dp))
        // 当前生效区：和下面的专家卡片区隔开，避免和「全部专家」混成一片。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .squircleBackground(colors.surfaceContainerHigh, 14.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (currentTitle != null) colors.primary else colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.expert_center_current_label),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
                Text(
                    text = currentTitle ?: stringResource(R.string.expert_center_current_none),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = if (currentTitle != null) colors.onSurface else colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 右上角计数徽章：总数 + 可用数。
 *
 * 数字用大号 primary 色放在上面，下面一行小字补「可用 / 停用」；
 * 不再额外重复总数，否则「8」和「8 位专家」会在同一处出现两次。
 */
@Composable
private fun ExpertCountBadge(totalCount: Int, enabledCount: Int) {
    val colors = MiuixTheme.colorScheme
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = totalCount.toString(),
            style = MiuixTheme.textStyles.headline1,
            fontWeight = FontWeight.Bold,
            color = colors.primary,
        )
        Text(
            text = stringResource(R.string.expert_center_count, enabledCount, totalCount - enabledCount),
            style = MiuixTheme.textStyles.footnote2,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/**
 * 单张专家卡片。
 *
 * 与列表行的区别：卡片自带字标（首字母 + 名字派生的渐变底色，一眼区分不同专家）、
 * 状态徽章（生效中 / 置顶 / 已停用）和独立的操作区，信息密度比一行文字高，
 * 但仍然是「一个专家一块」的结构，扫视时不会串行。
 */
@Composable
private fun ExpertCard(
    expert: Expert,
    isActive: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    EtaCard(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Rounded.Group,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (expert.enabled) colors.primary else colors.disabledOnSurface,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = expert.title,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        color = if (expert.enabled) colors.onSurface else colors.disabledOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ExpertBadge(
                            text = stringResource(R.string.expert_center_badge_active),
                            container = colors.primary.copy(alpha = 0.14f),
                            content = colors.primary,
                        )
                    }
                    if (expert.pinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = stringResource(R.string.expert_center_badge_pinned),
                            modifier = Modifier.size(16.dp),
                            tint = colors.primary,
                        )
                    }
                    if (!expert.enabled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        ExpertBadge(
                            text = stringResource(R.string.expert_center_badge_disabled),
                            container = colors.surfaceContainerHighest,
                            content = colors.disabledOnSurface,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (expert.source == ExpertSource.BUILTIN) {
                            stringResource(R.string.expert_detail_source_builtin)
                        } else {
                            stringResource(R.string.expert_detail_source_user)
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = expert.description.ifBlank { expert.category.displayName() },
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = expert.category.displayName(),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            EtaTextButton(
                text = stringResource(R.string.expert_center_action_delete),
                onClick = onDelete,
            )
        }
    }
}

/** 小胶囊徽章：状态标签。 */
@Composable
private fun ExpertBadge(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .squircleBackground(container, 8.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = content,
        )
    }
}

/**
 * 专家编辑面板（新建 / 编辑共用）。
 *
 * 表单字段多，必须自己能滚动、保存按钮固定在面板底部——否则小屏上「保存」会被键盘
 * 和输入框挤出可视区，用户看到的就是「点了没反应、加不了专家」。
 */
@Composable
internal fun ExpertEditorSheet(draft: ExpertDraft, onDismiss: () -> Unit, onSave: (Expert) -> Unit) {
    var name by remember { mutableStateOf(draft.name) }
    var displayName by remember { mutableStateOf(draft.displayName) }
    var description by remember { mutableStateOf(draft.description) }
    var category by remember { mutableStateOf(draft.category) }
    var prompt by remember { mutableStateOf(draft.prompt) }
    var skills by remember { mutableStateOf(draft.skills) }
    var tools by remember { mutableStateOf(draft.tools) }
    var disallowedTools by remember { mutableStateOf(draft.disallowedTools) }
    var injectText by remember { mutableStateOf(draft.inject.joinToString(", ")) }
    var providerId by remember { mutableStateOf(draft.providerId) }
    var modelId by remember { mutableStateOf(draft.modelId) }
    var pinned by remember { mutableStateOf(draft.pinned) }
    var enabled by remember { mutableStateOf(draft.enabled) }
    var picker by remember { mutableStateOf<ExpertPickerSheet?>(null) }

    // 模型列表：与主界面模型选择器同一份投影，只列已启用且有凭据的厂商。
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val modelGroups = remember(providers) {
        AgentModelPickerProjector.project(providers, null, null).providerGroups
    }
    val selectedModelLabel = remember(modelGroups, providerId, modelId) {
        modelGroups.asSequence()
            .flatMap { it.models.asSequence() }
            .firstOrNull { it.providerId == providerId && it.modelId == modelId }
            ?.displayName
            .orEmpty()
    }
    // 工具池：全量旗标建目录取名字，与运行时工具目录同一来源，避免白名单里出现运行时不存在的名字。
    val toolOptions = remember {
        runCatching {
            val array = AgentToolCatalog.build(
                terminalTools = true,
                browserTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = true,
                memoryWritable = true,
                capabilities = AgentToolCapabilities(rootAvailable = true),
                localWebSearch = true,
                // 与运行时同一口径：能力页关掉的工具不进白名单池，避免给专家配上永远跑不了的工具。
                disabledTools = Prefs.disabledTools(),
            )
            (0 until array.length()).mapNotNull { index ->
                val function = array.optJSONObject(index)?.optJSONObject("function")
                    ?: return@mapNotNull null
                val toolName = function.optString("name")
                if (toolName.isBlank()) null else ExpertPickOption(toolName, toolName)
            }
        }.getOrDefault(emptyList())
    }
    // 技能列表：已安装技能索引，IO 线程读，失败给空列表（仍可手填的场景走 expert_manage）。
    val appContext = LocalContext.current
    val skillOptions = produceState(initialValue = emptyList<ExpertPickOption>(), appContext) {
        val entries = withContext(Dispatchers.IO) {
            runCatching {
                SkillRuntime.createIndexService(appContext).listInstalledSkills()
            }.getOrDefault(emptyList())
        }
        value = entries.filter { it.installed }.map { entry ->
            ExpertPickOption(
                id = entry.id,
                label = entry.name,
                subtitle = entry.description.takeIf { it.isNotBlank() },
            )
        }
    }

    val isNew = draft.name.isBlank()
    val canSave = name.isNotBlank() && prompt.isNotBlank()

    OverlayBottomSheet(
        show = true,
        title = if (isNew) "新建专家" else "编辑专家",
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (isNew) {
                    InputField(
                        query = name,
                        onQueryChange = { name = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = "名称（唯一标识，如 code-reviewer）",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
                    )
                }
                InputField(
                    query = displayName,
                    onQueryChange = { displayName = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = "显示名（如「代码评审官」）",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
                )
                InputField(
                    query = description,
                    onQueryChange = { description = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = "摘要（一句话说清能力与适用场景）",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
                )
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    items(ExpertCategory.entries, key = { it.key }) { entry ->
                        ExpertCategoryChip(
                            text = entry.shortLabel(),
                            selected = category == entry,
                            onClick = { category = entry },
                        )
                    }
                }
                InputField(
                    query = prompt,
                    onQueryChange = { prompt = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = "专家提示词（是谁 + 背景、擅长与不做的边界、工作流程、输出格式）",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
                )
                ExpertPickerRow(
                    label = "模型",
                    value = selectedModelLabel,
                    placeholder = "继承父会话",
                    onClick = { picker = ExpertPickerSheet.MODEL },
                )
                ExpertPickerRow(
                    label = "工具白名单",
                    value = tools.joinToString("、"),
                    placeholder = "继承全量工具",
                    onClick = { picker = ExpertPickerSheet.TOOLS },
                )
                ExpertPickerRow(
                    label = "工具黑名单",
                    value = disallowedTools.joinToString("、"),
                    placeholder = "无排除项",
                    onClick = { picker = ExpertPickerSheet.DISALLOWED },
                )
                ExpertPickerRow(
                    label = "绑定技能",
                    value = skills.joinToString("、"),
                    placeholder = "不绑定技能",
                    onClick = { picker = ExpertPickerSheet.SKILLS },
                )
                InputField(
                    query = injectText,
                    onQueryChange = { injectText = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = "注入片段（逗号分隔：mainRules / projectRules / skills / memory）",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EtaTextButton(
                        text = if (pinned) "已置顶" else "置顶",
                        onClick = { pinned = !pinned },
                        modifier = Modifier.width(96.dp),
                    )
                    EtaTextButton(
                        text = if (enabled) "已启用" else "已停用",
                        onClick = { enabled = !enabled },
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
            TextButton(
                text = "保存",
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isNotBlank()) {
                        onSave(
                            Expert(
                                name = trimmedName,
                                displayName = displayName.trim(),
                                description = description.trim(),
                                category = category,
                                skills = skills,
                                prompt = prompt.trim().take(Expert.MAX_PROMPT_CHARS),
                                enabled = enabled,
                                pinned = pinned,
                                source = draft.source,
                                providerId = providerId.trim(),
                                modelId = modelId.trim(),
                                tools = tools,
                                disallowedTools = disallowedTools,
                                inject = injectText.splitList(),
                            )
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 16.dp),
            )
        }
    }

    when (picker) {
        ExpertPickerSheet.MODEL -> ExpertModelPickerDialog(
            groups = modelGroups,
            currentProviderId = providerId,
            currentModelId = modelId,
            onSelect = { newProvider, newModel ->
                providerId = newProvider
                modelId = newModel
                picker = null
            },
            onDismiss = { picker = null },
        )
        ExpertPickerSheet.TOOLS -> ExpertMultiPickDialog(
            title = "工具白名单",
            summary = "留空表示继承全量工具；黑名单优先于白名单",
            options = toolOptions,
            selected = tools,
            onConfirm = {
                tools = it
                picker = null
            },
            onDismiss = { picker = null },
        )
        ExpertPickerSheet.DISALLOWED -> ExpertMultiPickDialog(
            title = "工具黑名单",
            summary = "选中的工具将被排除，优先于白名单生效",
            options = toolOptions,
            selected = disallowedTools,
            onConfirm = {
                disallowedTools = it
                picker = null
            },
            onDismiss = { picker = null },
        )
        ExpertPickerSheet.SKILLS -> ExpertMultiPickDialog(
            title = "绑定技能",
            summary = "召唤该专家时自动加载选中技能的正文",
            options = skillOptions.value,
            selected = skills,
            onConfirm = {
                skills = it
                picker = null
            },
            onDismiss = { picker = null },
        )
        null -> {}
    }
}

/** 逗号/顿号/空格都当分隔符，用户从别处粘一串 id 时不用先手工整理。 */
private fun String.splitList(): List<String> =
    split(',', '，', '、', ' ', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

internal data class ExpertDraft(
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val category: ExpertCategory = ExpertCategory.OTHER,
    val prompt: String = "",
    val skills: List<String> = emptyList(),
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val source: ExpertSource = ExpertSource.USER,
    val providerId: String = "",
    val modelId: String = "",
    val tools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val inject: List<String> = emptyList(),
)

/** 编辑面板里的列表选择入口行：左标签、右当前值，点击弹选择器。 */
@Composable
private fun ExpertPickerRow(label: String, value: String, placeholder: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value.ifBlank { placeholder },
            style = MiuixTheme.textStyles.body2,
            color = if (value.isBlank()) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.primary
            },
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** 单选模型弹窗：第一项固定「继承父会话」，与运行时继承语义一致。 */
@Composable
private fun ExpertModelPickerDialog(
    groups: List<AgentModelProviderGroupUi>,
    currentProviderId: String,
    currentModelId: String,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    EtaWindowDialog(show = true, title = "选择模型", onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ExpertPickOptionRow(
                label = "继承父会话",
                subtitle = "不指定模型，召唤时使用会话当前模型",
                selected = currentProviderId.isBlank() || currentModelId.isBlank(),
                onClick = { onSelect("", "") },
            )
            groups.forEach { group ->
                Text(
                    text = group.providerName,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                )
                group.models.forEach { model ->
                    ExpertPickOptionRow(
                        label = model.displayName,
                        subtitle = model.modelId,
                        selected = currentProviderId == model.providerId && currentModelId == model.modelId,
                        onClick = { onSelect(model.providerId, model.modelId) },
                    )
                }
            }
            if (groups.isEmpty()) {
                Text(
                    text = "还没有可用模型：先到「设置 → 模型服务商」里添加并启用。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

/** 多选弹窗：工具白名单 / 黑名单 / 绑定技能共用。 */
@Composable
private fun ExpertMultiPickDialog(
    title: String,
    summary: String,
    options: List<ExpertPickOption>,
    selected: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(selected) { mutableStateOf(selected) }
    EtaWindowDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (options.isEmpty()) {
                Text(
                    text = "暂无可选项。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(20.dp),
                )
            }
            options.forEach { option ->
                ExpertPickOptionRow(
                    label = option.label,
                    subtitle = option.subtitle,
                    selected = option.id in draft,
                    onClick = {
                        draft = if (option.id in draft) draft - option.id else draft + option.id
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        MiuixDialogActions(
            confirmText = "完成",
            onCancel = onDismiss,
            onConfirm = { onConfirm(draft) },
        )
    }
}

/** 选择器里的一行可选项：选中时右侧打勾。 */
@Composable
private fun ExpertPickOptionRow(label: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 可选项：id 是存进专家定义的值，label / subtitle 给人看。 */
internal data class ExpertPickOption(val id: String, val label: String, val subtitle: String? = null)

/** 编辑面板当前打开的选择器。 */
private enum class ExpertPickerSheet { MODEL, TOOLS, DISALLOWED, SKILLS }