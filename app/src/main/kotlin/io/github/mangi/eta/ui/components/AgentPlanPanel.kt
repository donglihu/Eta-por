package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.PlanPanelState
import io.github.mangi.eta.ui.model.PlanStepUi
import io.github.mangi.eta.ui.model.planFocusStep
import io.github.mangi.eta.ui.model.planPanelState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话框上方的任务清单：标题行常驻，展开才是完整步骤。
 *
 * 标题固定为「任务待办」，状态不写进标题：
 * 进度胶囊给总账（0/8 完成），行内的「进行中」胶囊给当前那一步，
 * 两处合起来已经说清状态，标题再挂一个「执行中／已结束」只会和二者打架。
 *
 * 折叠态把正在执行的那一步挂在标题行（见 [planFocusStep]），
 * 折起来也知道在干什么；展开态由用户自己按，不跟 run 状态跳。
 */
@Composable
internal fun AgentPlanPanel(
    steps: List<PlanStepUi>,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    expandedKey: String? = null,
    onClear: (() -> Unit)? = null,
) {
    if (steps.isEmpty()) return
    var expanded by rememberSaveable(expandedKey) { mutableStateOf(false) }
    val done = steps.count { it.isDone }
    val state = planPanelState(steps, isRunning)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .squircleBorder(width = 1.dp, color = MiuixTheme.colorScheme.dividerLine, cornerRadius = 18.dp)
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 18.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标与标题一起当作展开／收起开关，和图示一致；右侧箭头同理。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_plan_title_tasks),
                    style = MiuixTheme.textStyles.headline2,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(10.dp))
                ProgressBadge(done = done, total = steps.size)
                if (!expanded) {
                    // 折叠态挂当前步骤摘要：吃掉剩余宽度，超出用省略号收尾。
                    planFocusStep(steps)?.let { focus ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = focus.text,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_plan_collapse else R.string.chat_plan_expand,
                ),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { expanded = !expanded }
                    .padding(3.dp)
                    .size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (onClear != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.chat_plan_clear),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onClear)
                        .padding(3.dp)
                        .size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(160)),
            exit = shrinkVertically(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEach { step ->
                    PlanStepRow(step, isRunning = isRunning, state = state)
                }
            }
        }
    }
}

/** 进度胶囊：`0/8 完成`，浅底圆角，和行内的「进行中」胶囊同一套视觉。 */
@Composable
private fun ProgressBadge(done: Int, total: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_plan_progress_done, done, total),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 一行步骤。
 *
 * 三种状态的视觉分工（对应 `PlanPanelState`）：
 * - 已完成：实心圆点，文字降为次级色
 * - 进行中：空心圆环 + 主色，右侧挂「进行中」胶囊
 * - 未开始：空心圆环 + 分隔线色
 *
 * 全部步骤完成（[PlanPanelState.DONE]）时不再补画「进行中」胶囊，
 * 避免模型没把最后一步标成 done 时，面板一边写「已完成」一边挂「进行中」。
 */
@Composable
private fun PlanStepRow(
    step: PlanStepUi,
    isRunning: Boolean,
    state: PlanPanelState,
) {
    val finished = state == PlanPanelState.DONE
    val showActiveBadge = step.isActive && isRunning && !finished
    val markerColor = when {
        step.isDone -> MiuixTheme.colorScheme.primary
        step.isActive -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    }
    val textColor = when {
        step.isDone -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // 已完成用实心、其余用空心环：形状本身就区分了「做完」和「没做完」。
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .then(
                    if (step.isDone) {
                        Modifier.background(markerColor)
                    } else {
                        Modifier.squircleBorder(
                            width = 1.5.dp,
                            color = markerColor,
                            cornerRadius = 5.dp,
                        )
                    },
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = step.text,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body2,
            color = textColor,
        )
        if (showActiveBadge) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_plan_step_active),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}
