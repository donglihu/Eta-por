package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * 标题行图标按钮的触控边长。
 *
 * 取 40dp：既明显大于原来的 26dp（原写法只有 20dp 图标 + 3dp 内边距，
 * 手感上「看得见按不中」），又不至于把标题行撑到 48dp 那么高、破坏设计图的紧凑比例。
 */
private val HeaderIconTouchTarget = 40.dp

/**
 * 展开态步骤列表的最大高度。
 *
 * 面板停靠在会话框输入框的正上方，展开时会长高；步骤一多就把消息区挤没，
 * 表现就是「待办列表超长时看不到后面的步骤」。
 *
 * 这里给展开列表一个高度上限并让它自己滚：超出上限的部分在面板内部滑动，
 * 输入框与面板始终留在屏幕上，步骤一条都不丢。
 */
private val PlanExpandedMaxHeight = 320.dp

/** 展开列表最多占屏幕高度的比例：矮屏上不能把消息区挤没。 */
private const val PlanExpandedMaxScreenRatio = 0.42f

/**
 * 步骤序号的占位宽度。
 *
 * 序号是「1.」这种右对齐文本，个位数比「10.」窄；给一个最小宽度并右对齐，
 * 个位数与两位数的圆点才不会错位，清单排到 10 条以上左缘仍是一条直线。
 */
private val StepIndexMinWidth = 20.dp

/**
 * 会话输入框正上方的任务清单：标题行常驻，展开才是完整步骤。
 *
 * 位置：贴在输入框上方（同一列内、输入栏之上），不是浮在屏幕顶部也不是盖住会话。
 * 用户始终能同时看到「待办」和「输入框」，两者是一条连续的底部区域。
 *
 * 标题固定为「任务待办」，状态不写进标题：
 * 进度胶囊给总账（0/8 完成），行内的「进行中」胶囊给当前那一步，
 * 两处合起来已经说清状态，标题再挂一个「执行中／已结束」只会和二者打架。
 *
 * 折叠态把正在执行的那一步挂在标题行（见 [planFocusStep]），
 * 折起来也知道在干什么；展开态由用户自己按，不跟 run 状态跳。
 *
 * 版式：卡片纵向顺序是「展开列表 → 1dp 分隔线 → 标题行」，标题行坐最下面。
 * 面板底部对齐输入框，因此标题行位置固定，列表变长只向上生长。
 */
@Composable
internal fun AgentPlanPanel(
    steps: List<PlanStepUi>,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    expandedKey: String? = null,
    /** 右上角叉号：清空本会话的任务清单。默认非空，保证按钮始终在。 */
    onClear: () -> Unit = {},
) {
    if (steps.isEmpty()) return
    var expanded by rememberSaveable(expandedKey) { mutableStateOf(false) }
    val planScrollState = rememberScrollState()
    // 拦截面板的嵌套滑动：面板可滚时全部自己吃掉；到顶/到底后也不再往下传，
    // 避免会话列表被面板的滑动带着跑。
    // 拦截面板的嵌套滑动，锁住手势不让下层会话列表跟着滚。
    //
    // 写法和项目既有的 EtaVoicePanel 一致（onPreScroll + onPostScroll 双段）：
    // onPreScroll 拦「还没到边界」的部分，onPostScroll 补拦「面板已经吃不动」的剩余位移，
    // 两段合起来才不会出现「滑到尽头后手指一松，会话列表突然跟着跳一下」。
    val planScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val state = planScrollState
                val delta = available.y
                // 已经到对应方向的边界：整段吃掉，下层列表就不动。
                val atTop = state.value <= 0f
                val atBottom = state.value >= state.maxValue
                if ((delta > 0f && atTop) || (delta < 0f && atBottom)) {
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val state = planScrollState
                val remaining = available.y
                if (remaining == 0f) return Offset.Zero
                // 面板已经吃不动了：把剩余位移也吃掉，绝不传给下层的会话列表。
                val atTop = state.value <= 0f
                val atBottom = state.value >= state.maxValue
                if ((remaining > 0f && atTop) || (remaining < 0f && atBottom)) {
                    return Offset(0f, remaining)
                }
                return Offset.Zero
            }
        }
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxExpandedHeight = minOf(PlanExpandedMaxHeight, screenHeight * PlanExpandedMaxScreenRatio)
    val done = steps.count { it.isDone }
    val state = planPanelState(steps, isRunning)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 卡片感：描边 + 浅底色，让它读起来是「贴在输入框上方的卡片」，
            // 而不是浮在会话顶部的一块遮罩。投影已经由外层 Box 提供。
            //
            // 这里刻意不挂 animateContentSize：高度变化已经由内层 AnimatedVisibility 的
            // expand/shrink(120/160ms) 逐帧驱动，外层再叠一层高度动画等于同一段高度被插值两次，
            // 两层曲线不一致时标题行会先被顶开再回落（瞬态位移）。单一动画源也便于排查。
            .squircleBorder(width = 1.dp, color = MiuixTheme.colorScheme.dividerLine, cornerRadius = 18.dp)
            .squircleSurface(color = MiuixTheme.colorScheme.surfaceContainer, cornerRadius = 18.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 顺序：列表在上、标题行在下。
        // 面板停在输入框上沿，整张卡是底部对齐的，标题行作为最后一个子项被钉在原地：
        // 列表长高时只往上顶，标题行不动，视线锚点始终贴着输入框。
        // 反过来（标题在前）一展开就会把标题推离输入框，折叠／展开两次视线都要重新找位置。
        AnimatedVisibility(
            visible = expanded,
            // 面板停在底部（输入框正上方），所以展开要往上长：expandFrom = Bottom 让内容
            // 从下沿往上铺，收起时也从下沿往下缩。默认的 Top 方向在底部停靠的布局里
            // 看起来是「内容往下掉」，和「点击向上展开」的直觉相反。
            //
            // 换成「列表在上、标题在下」后参数不变、语义仍成立，原因：
            // 列表下方只剩分隔线与标题行，两者高度固定且整张卡底部对齐，
            // 所以 AnimatedVisibility 自己的下沿恒等于标题行上沿（屏幕坐标不动）。
            // Bottom 对齐＝把内容钉在这个固定下沿上，高度从 0 长起来时只在顶部开窗，
            // 表现正是「从标题上沿往上铺开」，收起则向标题方向缩回。
            enter = expandVertically(animationSpec = tween(160), expandFrom = Alignment.Bottom),
            exit = shrinkVertically(animationSpec = tween(120), shrinkTowards = Alignment.Bottom),
        ) {
            // 上限 + 内部滚动：步骤再多也只占固定高度，超出的在面板里滑动查看。
            // heightIn 在 verticalScroll 外层：内容短时按内容高度，长时封顶到上限并可滚动。
            // 不用 weight：面板本身是 wrap-content 高度，weight 在这种父容器里拿不到剩余空间。
            //
            // nestedScroll 把滑动**锁在面板内**：面板就贴在输入框上方，
            // 不加这个拦截的话，手指在面板上滑到尽头后会把剩余位移交给上面的 LazyColumn，
            // 表现为「在待办面板上滑动，会话信息也跟着滚」（2026-11-20 用户反馈）。
            Column(
                modifier = Modifier
                    .heightIn(max = maxExpandedHeight)
                    .nestedScroll(planScrollConnection)
                    .verticalScroll(planScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 序号按步骤在计划里的原始位置给（index + 1），已完成的行保留原号、
                // 不重新编号：用户对照上面的消息记录时序号才对得上，不会因为做完一步就跳号。
                steps.forEachIndexed { index, step ->
                    PlanStepRow(
                        index = index + 1,
                        step = step,
                        isRunning = isRunning,
                        state = state,
                    )
                }
            }
        }
        // 列表与标题行同处一张卡，中间补一条 1dp 分隔线把两段读开；
        // 只在展开时画，折叠态不画——列表隐藏后这条线没有对应内容，
        // 会变成标题上方一条莫名其妙的横线。
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(160)),
            // 与上方列表的 shrinkVertically(120ms) 对齐：分隔线自带 1dp 高度 + 6dp 间距 = 7dp，
            // 淡出若仍是 100ms，它会比列表早 20ms 结束、7dp 先瞬间塌掉，而列表还在缩，
            // 末帧下沿就跳一下（本处为底部锚定）。两者同时归零，末帧下沿不动。
            exit = fadeOut(animationSpec = tween(120)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MiuixTheme.colorScheme.dividerLine),
            )
        }
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
                // 标题图标用「空心选中复选框」：方框描边、勾是描边，不填底色。
                // 不用实心 CheckBox——标题行是常驻装饰，实心色块会和右侧的主色进度胶囊抢视觉。
                Icon(
                    imageVector = Icons.Outlined.CheckBox,
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
            PlanHeaderIconButton(
                // 面板停在底部：折叠时箭头朝上（点了向上展开），展开时箭头朝下（点了收回去）。
                icon = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_plan_collapse else R.string.chat_plan_expand,
                ),
                onClick = { expanded = !expanded },
            )
            Spacer(modifier = Modifier.width(2.dp))
            PlanHeaderIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.chat_plan_clear),
                onClick = onClear,
            )
        }
    }
}

/**
 * 标题行右侧的图标按钮：图形本身 20dp，外面套 [HeaderIconTouchTarget] 的触控区。
 *
 * 之前的写法只有 `20dp + padding(3dp)` ≈ 26dp 的点击范围，低于 48dp 最小触控目标，
 * 手感上是「看得见但按不中」；这里把图形与点击范围分开，
 * 图标仍是 20dp 保持设计图的视觉比例，变大的只是那圈可点区域。
 */
@Composable
private fun PlanHeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(HeaderIconTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 进度胶囊：`0/8 完成`，浅底圆角，和行内的「进行中」胶囊同一套视觉。 */@Composable
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
    /** 行首序号，从 1 开始：按步骤在计划里的原始位置给，不随状态重排。 */
    index: Int,
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
        // 序号在前、状态圆点在后；字号跟正文一致（body2），
        // 颜色直接复用 textColor，已完成的步骤整体降为次级色时序号一起退后。
        // 数字右对齐 + 最小宽度：个位数与两位数的圆点不会错位。
        Text(
            text = "$index.",
            modifier = Modifier.widthIn(min = StepIndexMinWidth),
            style = MiuixTheme.textStyles.body2,
            color = textColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(modifier = Modifier.width(6.dp))
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
