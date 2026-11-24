package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/** 工作过程卡片内的一个时间线节点：单条消息，或连续调用同一工具形成的分组。 */
@Immutable
internal sealed interface AgentWorkStep {
    val key: String

    data class Single(val message: AgentChatMessageUi) : AgentWorkStep {
        override val key: String = message.id
    }

    data class ToolGroup(
        val toolName: String,
        val calls: List<ToolActivityMessageUi>,
    ) : AgentWorkStep {
        override val key: String = "group-${calls.first().id}"
        val running: Boolean get() = calls.any { it.status == ToolActivityStatusUi.Running }
        val failedCount: Int get() = calls.count { it.status == ToolActivityStatusUi.Failed }
    }
}

/**
 * Agent 常连续读取多个文件或多次观察屏幕；同名工具连续出现时合并为一个节点，
 * 时间线只保留“做了什么”的层级，细节按需展开。浏览器条目携带实时预览，保持独立。
 */
internal fun List<AgentChatMessageUi>.toWorkSteps(): List<AgentWorkStep> = buildList {
    val run = mutableListOf<ToolActivityMessageUi>()

    fun flushRun() {
        when (run.size) {
            0 -> Unit
            1 -> add(AgentWorkStep.Single(run.single()))
            else -> add(AgentWorkStep.ToolGroup(toolName = run.first().toolName, calls = run.toList()))
        }
        run.clear()
    }

    this@toWorkSteps.forEach { message ->
        val groupable = message is ToolActivityMessageUi && message.toolName !in UNGROUPED_TOOLS
        if (groupable && run.isNotEmpty() && run.first().toolName != (message as ToolActivityMessageUi).toolName) {
            flushRun()
        }
        if (groupable) {
            run += message as ToolActivityMessageUi
        } else {
            flushRun()
            add(AgentWorkStep.Single(message))
        }
    }
    flushRun()
}

private val UNGROUPED_TOOLS = setOf("browser_use", "subagent")

/**
 * 折叠行「第 N 步」+ 正在跑的那次调用，一次遍历同时得出。
 *
 * 序号取「本轮已出现的工具调用条数」，不取「最后一条正在运行的调用在本轮切片里的序号」：
 * 并发批次 T1/T2/T3 一起运行时三者都已存在，谁先完成就把「最后一个 Running」往前挪，
 * 按它算出来的序号会从 3 掉回 2、1，看起来像步骤被重排。本轮消息只追加、不改序，
 * 条数单调不减，因此序号只增不减；串行执行时最后一条 Running 就是最后一条工具，
 * 两种算法结果完全一致。
 *
 * 序号的作用域是「一张工作过程卡片」，不是模型的单次请求：调用方传的是单个工作过程条目
 * 自己的消息（entry.messages），而条目只在遇到非工作过程消息（助手正文、系统提示、用户消息）
 * 时才切出来（见 AgentChatBody.toTimelineEntries 的 isWorkProcessMessage）。所以只有被这类
 * 消息打断时才会出现新卡片、序号从 1 重新开始；连续的工具调用轮之间没有正文，会并入同一张
 * 卡片，序号继续累加 —— 这里的「第 N 步」与真实请求轮次不是一一对应。
 */
@Immutable
internal data class RunningStepSummary(
    val running: ToolActivityMessageUi?,
    val ordinal: Int,
)

internal fun List<AgentChatMessageUi>.runningStepSummary(): RunningStepSummary {
    var running: ToolActivityMessageUi? = null
    var toolCount = 0
    for (message in this) {
        if (message !is ToolActivityMessageUi) continue
        toolCount++
        if (message.status == ToolActivityStatusUi.Running) running = message
    }
    return RunningStepSummary(running = running, ordinal = if (running == null) 0 else toolCount)
}

/** 折叠后的工作过程摘要所需计数。 */
internal data class AgentWorkSummary(
    val thinkingCount: Int,
    val toolCount: Int,
    val failedCount: Int,
)

internal fun List<AgentChatMessageUi>.workSummary(): AgentWorkSummary = AgentWorkSummary(
    thinkingCount = count { it is ThinkingMessageUi },
    toolCount = count { it is ToolActivityMessageUi },
    failedCount = count { it is ToolActivityMessageUi && it.status == ToolActivityStatusUi.Failed },
)

/**
 * 折叠行下方的一行结果。成功的常态结果（如“完成”）不占行；终端输出取最后一行，
 * 因为命令的结论通常在末尾；失败去掉重复的“失败”前缀与日志用的 code= 尾巴。
 */
internal fun ToolActivityMessageUi.outcomeLine(): String? {
    val summary = resultSummary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = summary.lines().map(String::trim).filter(String::isNotEmpty)
    val head = lines.firstOrNull() ?: return null
    return when (status) {
        ToolActivityStatusUi.Running -> null
        ToolActivityStatusUi.Failed, ToolActivityStatusUi.Unknown -> {
            val detail = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            head.removePrefix(FAILURE_PREFIX)
                .substringBefore(FAILURE_CODE_SEPARATOR)
                .takeIf { it.isNotBlank() && it != FAILURE_LABEL }
                ?: detail
        }
        ToolActivityStatusUi.Success -> {
            val output = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            when {
                output != null -> output
                head in TRIVIAL_SUCCESS -> null
                else -> head.removePrefix(SUCCESS_PREFIX).takeIf { it.isNotBlank() }
            }
        }
    }
}

// 与 Runtime 侧结果摘要的固定格式对应。
private const val FAILURE_LABEL = "失败"
private const val FAILURE_PREFIX = "失败 · "
private const val FAILURE_CODE_SEPARATOR = " · code="
private const val SUCCESS_PREFIX = "完成 · "
private const val TRUNCATION_MARK = "…"
private val TRIVIAL_SUCCESS = setOf("完成", "执行完成")
