package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.expert.ExpertStore
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ExpertPopupWidth = 236.dp

/** 重叠小人图标：两个 Person 错位叠放，一眼区分于单人头像。 */
@Composable
fun OverlappingExpertIcon(
    size: Dp,
    tint: Color,
) {
    Box(modifier = Modifier.size(size)) {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            modifier = Modifier
                .size(size * 0.72f)
                .offset(x = size * 0.28f, y = 0.dp),
            tint = tint.copy(alpha = 0.55f),
        )
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            modifier = Modifier
                .size(size * 0.78f)
                .offset(x = 0.dp, y = size * 0.2f),
            tint = tint,
        )
    }
}

/**
 * 会话输入栏的专家入口。
 *
 * 对齐 miniAI：模型按钮旁的人形图标，点开就是专家列表，选中即以该专家身份作答，
 * 再点「不使用专家」取消。图标用两个重叠小人表示「可召唤的角色」。
 */
@Composable
internal fun ExpertPickerButton(
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    modifier: Modifier = Modifier,
    /** 外部召唤状态变化的信号（专家中心里改过、或别处召唤过）；变化时重读一次。 */
    refreshToken: Int = 0,
    onSelectionChanged: () -> Unit = {},
) {
    var showPopup by remember { mutableStateOf(false) }
    var experts by remember { mutableStateOf(ExpertStore.loadExperts()) }
    var selection by remember { mutableStateOf(ExpertStore.loadSelection()) }

    // 在专家中心新建/删除/召唤后回到会话，重读一次，避免入口停在旧数据。
    LaunchedEffect(refreshToken) {
        experts = ExpertStore.loadExperts()
        selection = ExpertStore.loadSelection()
    }
    // 每次展开面板也重读：面板里看到的就是刚落盘的状态。
    LaunchedEffect(showPopup) {
        if (showPopup) {
            experts = ExpertStore.loadExperts()
            selection = ExpertStore.loadSelection()
        }
    }

    val activeExpert = experts.firstOrNull { it.name == selection.expertName && selection.enabled }
    val tint = if (activeExpert != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    val description = activeExpert?.let { "当前专家：${it.title}" } ?: "召唤专家"

    Box(modifier = modifier) {
        IconButton(
            onClick = { showPopup = true },
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            OverlappingExpertIcon(size = ChatInputActionIconSize, tint = tint)
        }

        OverlayListPopup(
            show = showPopup && popupAnchorTopPx > 0,
            popupPositionProvider = InputPopupPositionProvider(
                inputContainerTopPx = popupAnchorTopPx,
                windowHorizontalInsetPx = null,
            ),
            alignment = PopupPositionProvider.Align.TopEnd,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
            minWidth = ExpertPopupWidth,
        ) {
            Column(
                modifier = Modifier
                    .width(ExpertPopupWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                ExpertRow(
                    title = "不使用专家",
                    summary = null,
                    selected = activeExpert == null,
                    onClick = {
                        ExpertStore.dismiss()
                        selection = ExpertStore.loadSelection()
                        onSelectionChanged()
                        showPopup = false
                    },
                )
                if (experts.isEmpty()) {
                    Text(
                        text = "还没有专家。到「设置 → 专家」新建一个。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    experts.forEach { expert ->
                        ExpertRow(
                            title = expert.title,
                            summary = expert.description.takeIf { it.isNotBlank() },
                            selected = activeExpert?.name == expert.name,
                            enabled = expert.enabled,
                            onClick = {
                                if (!expert.enabled) return@ExpertRow
                                if (activeExpert?.name == expert.name) {
                                    ExpertStore.dismiss()
                                } else {
                                    ExpertStore.summon(expert.name)
                                }
                                selection = ExpertStore.loadSelection()
                                onSelectionChanged()
                                showPopup = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpertRow(
    title: String,
    summary: String?,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        selected -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlappingExpertIcon(size = 20.dp, tint = contentColor)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
