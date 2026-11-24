package io.github.mangi.eta.ui.screens.expert

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.expert.Expert
import io.github.mangi.eta.agent.expert.ExpertCategory
import io.github.mangi.eta.agent.expert.ExpertSource
import io.github.mangi.eta.agent.expert.ExpertStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CardHorizontalPadding = 16.dp
private val CardBottomPadding = 16.dp

/**
 * 专家详情页。对齐截图的详情结构：
 * 启用/置顶开关 → 摘要 → 属性行（分类/来源/模型/工具集/绑定技能/注入/定义位置）→ 提示词正文 → 召唤操作区。
 *
 * 右上角铅笔进编辑；关闭靠返回。
 */
@Composable
internal fun ExpertDetailScreen(
    expertName: String,
    onBack: () -> Unit,
) {
    // 首帧先用进程内缓存渲染，读盘放 IO：ExpertStore 要读 SharedPreferences 再解析 JSON，
    // 写在 remember 初始化里等于在组合阶段（主线程）做磁盘 IO。
    var expert by remember(expertName) { mutableStateOf(ExpertStore.cachedExperts().firstOrNull { it.name == expertName }) }
    var editing by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(expertName) {
        expert = withContext(Dispatchers.IO) { ExpertStore.find(expertName) }
    }

    val current = expert
    if (current == null) {
        // 定义可能刚被删除或改名：不展示半截详情页，直接给出返回入口。
        MiuixScaffoldPage(title = stringResource(R.string.expert_detail_missing_title), onBack = onBack) {
            item(key = "expert-missing") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.expert_detail_missing_body),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    EtaTextButton(
                        text = stringResource(R.string.expert_detail_back_to_list),
                        onClick = onBack,
                    )
                }
            }
        }
        return
    }

    fun refresh() {
        expert = ExpertStore.find(expertName)
    }

    // 召唤状态读进程内缓存：这条路径在组合期执行，不能落盘。
    val isSummoned = ExpertStore.cachedSelection().expertName == current.name

    MiuixScaffoldPage(
        title = current.title,
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { editing = true },
                modifier = Modifier.semantics {
                    contentDescription = "编辑专家"
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
        overlay = {
            if (editing) {
                ExpertEditorSheet(
                    draft = current.toDraft(),
                    onDismiss = { editing = false },
                    onSave = { saved ->
                        ExpertStore.upsert(saved)
                        editing = false
                        refresh()
                    },
                )
            }
        },
    ) {
        item(key = "expert-switches") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                EtaSwitchPreference(
                    title = stringResource(R.string.expert_detail_switch_enable_title),
                    summary = stringResource(R.string.expert_detail_switch_enable_summary),
                    checked = current.enabled,
                    onCheckedChange = { checked ->
                        // 关掉正在生效的专家时顺手取消召唤：否则会出现「已停用但仍在按它的身份作答」。
                        ExpertStore.upsert(current.copy(enabled = checked))
                        if (!checked && ExpertStore.loadSelection().expertName == current.name) {
                            ExpertStore.dismiss()
                        }
                        refresh()
                    },
                )
                EtaPreferenceDivider()
                EtaSwitchPreference(
                    title = stringResource(R.string.expert_detail_switch_pin_title),
                    summary = stringResource(R.string.expert_detail_switch_pin_summary),
                    checked = current.pinned,
                    onCheckedChange = { checked ->
                        ExpertStore.upsert(current.copy(pinned = checked))
                        refresh()
                    },
                )
            }
        }

        item(key = "expert-summary-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.expert_detail_summary_title))
        }
        item(key = "expert-summary") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                Text(
                    text = current.description.ifBlank {
                        stringResource(R.string.expert_detail_summary_empty)
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = if (current.description.isBlank()) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        item(key = "expert-detail") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                ExpertInfoRow(
                    label = stringResource(R.string.expert_detail_header_category),
                    value = current.category.displayName(),
                )
                EtaPreferenceDivider()
                ExpertInfoRow(
                    label = stringResource(R.string.expert_detail_header_source),
                    value = if (current.source == ExpertSource.BUILTIN) {
                        stringResource(R.string.expert_detail_source_builtin)
                    } else {
                        stringResource(R.string.expert_detail_source_user)
                    },
                )
                EtaPreferenceDivider()
                ExpertInfoRow(
                    label = stringResource(R.string.expert_detail_header_model),
                    value = current.modelSummary(),
                )
                EtaPreferenceDivider()
                ExpertInfoRow(
                    label = stringResource(R.string.expert_detail_header_tools),
                    value = current.toolSummary(),
                )
                EtaPreferenceDivider()
                ExpertInfoRow(
                    label = stringResource(R.string.expert_detail_header_skills),
                    value = current.skills.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ?: stringResource(R.string.expert_detail_skills_none),
                )
                EtaPreferenceDivider()
                ExpertInfoRow(
                    label = stringResource(R.string.expert_detail_header_inject),
                    value = current.inject.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ?: stringResource(R.string.expert_detail_inject_none),
                )
                EtaPreferenceDivider()
                ExpertInfoRow(
                    label = stringResource(R.string.expert_detail_header_location),
                    value = stringResource(R.string.expert_detail_location_value),
                )
            }
        }

        item(key = "expert-prompt-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.expert_detail_prompt_title))
        }
        item(key = "expert-prompt") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                val prompt = current.prompt
                if (prompt.isBlank()) {
                    Text(
                        text = stringResource(R.string.expert_detail_prompt_empty),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    // 对齐截图：提示词就是一段普通正文，不再套深色等宽块。
                    Text(
                        text = prompt,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item(key = "expert-summon") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 召唤是本页主操作，占满剩余宽度；删除是次操作，固定窄按钮。
                TextButton(
                    text = if (isSummoned) {
                        stringResource(R.string.expert_detail_action_dismiss)
                    } else {
                        stringResource(R.string.expert_detail_action_summon)
                    },
                    onClick = {
                        if (isSummoned) {
                            ExpertStore.dismiss()
                        } else {
                            ExpertStore.summon(current.name)
                        }
                        refresh()
                    },
                    enabled = current.enabled,
                    modifier = Modifier.weight(1f),
                )
                EtaTextButton(
                    text = stringResource(R.string.expert_detail_action_delete),
                    onClick = { deleteConfirm = true },
                )
            }
        }
    }

    if (deleteConfirm) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.expert_detail_delete_title),
            summary = stringResource(R.string.expert_detail_delete_summary, current.title),
            onDismissRequest = { deleteConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.expert_detail_delete_confirm),
                destructive = true,
                onCancel = { deleteConfirm = false },
                onConfirm = {
                    ExpertStore.delete(current.name)
                    deleteConfirm = false
                    onBack()
                },
            )
        }
    }
}

/**
 * 详情页的键值行：左侧项名，右侧是值（右对齐），值过长时折行而不是被裁掉。
 * 与截图一致：不再配图标，靠左右分栏区分项名与内容。
 */
@Composable
private fun ExpertInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun ExpertCategory.displayName(): String = when (this) {
    ExpertCategory.WRITING -> "写作与内容"
    ExpertCategory.DATA -> "数据与分析"
    ExpertCategory.DEV -> "开发"
    ExpertCategory.DESIGN -> "设计"
    ExpertCategory.BUSINESS -> "商业"
    ExpertCategory.OFFICE -> "办公"
    ExpertCategory.LIFE -> "生活"
    ExpertCategory.EMOTION -> "情感"
    ExpertCategory.OTHER -> "其它"
}

/** 分类筛选条上的短标签，与详情页的完整分类名分开。 */
internal fun ExpertCategory.shortLabel(): String = when (this) {
    ExpertCategory.WRITING -> "写作"
    ExpertCategory.DATA -> "数据"
    ExpertCategory.BUSINESS -> "商务"
    else -> displayName()
}

/**
 * 编辑器共用的分类胶囊条；详情页与专家中心共享同一套视觉。
 */
@Composable
internal fun ExpertCategoryChips(
    selected: ExpertCategory?,
    onSelect: (ExpertCategory?) -> Unit,
    includeAll: Boolean,
    allLabel: String = "全部",
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = CardHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        if (includeAll) {
            item(key = "all") {
                ExpertCategoryChip(
                    text = allLabel,
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
            }
        }
        items(ExpertCategory.entries, key = { it.key }) { category ->
            ExpertCategoryChip(
                text = category.shortLabel(),
                selected = selected == category,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
internal fun ExpertCategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
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

/** 列表行用于召唤入口的一行说明。 */
internal fun Expert.listSummary(): String = listOfNotNull(
    category.displayName(),
    description.takeIf { it.isNotBlank() },
).joinToString(" · ").ifBlank { "暂无描述" }
