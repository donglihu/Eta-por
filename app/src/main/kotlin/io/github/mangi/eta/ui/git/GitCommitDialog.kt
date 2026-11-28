package io.github.mangi.eta.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提交对话框（1:1 对齐 aicode CommitDialog，Miuix 化，2026-11-28 第二批）。
 *
 * 由状态页「提交更改」大按钮触发；确认走 [GitPanelState.commit]（信息非空由确认键禁用挡住）：
 * - 类型 chip 行（feat/fix/docs/style/refactor/test/chore 横滑选择）：选中类型拼进 message 前缀
 *   （`type: `；message 已含 `: ` 时只替换前缀，保留正文）；
 * - message 多行输入 + amend 开关（`git commit --amend`，仓库层原生支持）；
 * - 「AI 生成提交信息」按钮：调 agent 侧模型请求通道生成 conventional message。
 *
 * 接线说明：[onGenerateMessage] 为 agent 模型通道回调；当前 GitPanelState 尚无模型请求入口
 * （模型通道挂在会话运行时，与本状态机不同生命周期），本批先留回调占位——传 null 时按钮置灰，
 * 下一批在 AgentAppState 侧补一次性补全入口后传入。[generating] 为 true 时按钮转圈并禁用。
 */
@Composable
internal fun GitCommitDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (message: String, amend: Boolean) -> Unit,
    /** AI 生成提交信息回调（选中类型也作为上下文传给生成器）；null 表示尚未接线，按钮置灰。 */
    onGenerateMessage: ((currentType: String) -> Unit)? = null,
    /** AI 正在生成：按钮转圈并禁用，生成结果由状态机回填 message。 */
    generating: Boolean = false,
    /** 外部（AI 生成结果）回填的提交信息；非空时覆盖本地输入初值。 */
    generatedMessage: String = "",
) {
    var message by remember(generatedMessage) { mutableStateOf(generatedMessage) }
    var amend by remember { mutableStateOf(false) }
    // 任务书指定的七类 conventional commit 前缀（aicode 原版多一个 perf，按任务书收敛为七类）。
    val commitTypes = remember { listOf("feat", "fix", "docs", "style", "refactor", "test", "chore") }

    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.git_commit_dialog_title),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 快捷前缀 Chip 胶囊列表（横滑；命中当前前缀时高亮）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                commitTypes.forEach { type ->
                    val isCurrentType = message.startsWith("$type:") || message.startsWith("$type(")
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isCurrentType) MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
                            )
                            .clickable {
                                val prefix = "$type: "
                                message = if (message.contains(": ")) prefix + message.substringAfter(": ") else prefix + message
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = type,
                            color = if (isCurrentType) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isCurrentType) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            // 提交信息多行输入。
            GitDialogField(
                value = message,
                hint = stringResource(R.string.git_commit_message),
                onValueChange = { message = it },
                minLines = 3,
            )
            // AI 生成提交信息按钮：回调未接线时置灰（接线说明见 KDoc）。
            val aiEnabled = onGenerateMessage != null && !generating
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (aiEnabled) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                    )
                    .clickable(enabled = aiEnabled) { onGenerateMessage?.invoke(currentType(commitTypes, message)) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (generating) {
                    CircularProgressIndicator(size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = if (aiEnabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f),
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(if (generating) R.string.git_commit_ai_generating else R.string.git_commit_ai_generate),
                    color = if (aiEnabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            // amend 开关行。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
                    .clickable { amend = !amend }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.git_commit_amend),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                )
                EtaSwitch(checked = amend, onCheckedChange = { amend = it })
            }
        }
        Spacer(Modifier.height(8.dp))
        MiuixDialogActions(
            confirmText = stringResource(R.string.git_action_commit),
            confirmEnabled = !busy && message.isNotBlank(),
            onCancel = onDismiss,
            onConfirm = { onConfirm(message.trim(), amend) },
        )
    }
}

/** 当前 message 已选中的类型前缀（如 "feat"）；未命中时返回空串，供 AI 生成回调感知上下文。 */
private fun currentType(commitTypes: List<String>, message: String): String =
    commitTypes.firstOrNull { message.startsWith("$it:") || message.startsWith("$it(") } ?: ""
