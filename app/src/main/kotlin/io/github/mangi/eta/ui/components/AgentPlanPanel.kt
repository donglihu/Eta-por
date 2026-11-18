package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.PLAN_STEP_ACTIVE
import io.github.mangi.eta.ui.model.PlanStepUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown

/**
 * 会话框上方的计划清单：一行标题常驻，展开才是完整步骤。
 *
 * 收起态保留进度（3/7），所以即便折起来也知道跑到了哪；
 * 展开态由用户自己按，不跟着 run 状态跳，免得执行中面板自己反复伸缩。
 */
@Composable
internal fun AgentPlanPanel(
    steps: List<PlanStepUi>,
    planMode: Boolean,
    modifier: Modifier = Modifier,
    expandedKey: String? = null,
) {
    if (steps.isEmpty()) return
    var expanded by rememberSaveable(expandedKey) { mutableStateOf(true) }
    val done = steps.count { it.isDone }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .squircleBorder(width = 1.dp, color = MiuixTheme.colorScheme.dividerLine, cornerRadius = 16.dp)
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 16.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (planMode) {
                    stringResource(R.string.chat_plan_title_planning)
                } else {
                    stringResource(R.string.chat_plan_title_executing)
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_plan_progress, done, steps.size),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_plan_collapse else R.string.chat_plan_expand,
                ),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(160)),
            exit = shrinkVertically(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEach { step ->
                    PlanStepRow(step)
                }
            }
        }
    }
}

@Composable
private fun PlanStepRow(step: PlanStepUi) {
    val dotColor = when {
        step.isDone -> MiuixTheme.colorScheme.primary
        step.status == PLAN_STEP_ACTIVE -> MiuixTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> MiuixTheme.colorScheme.dividerLine
    }
    val textColor = when {
        step.isDone -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        step.status == PLAN_STEP_ACTIVE -> MiuixTheme.colorScheme.onSurface
        else -> MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = step.text,
            style = MiuixTheme.textStyles.footnote2,
            color = textColor,
        )
    }
}