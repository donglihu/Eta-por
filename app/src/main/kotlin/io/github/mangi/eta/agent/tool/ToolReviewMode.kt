package io.github.mangi.eta.agent.tool

import org.json.JSONObject

/**
 * 工具调用审查模式：取代旧的布尔开关，把「拦 / 不拦」拆成五档。
 *
 * - [READ_ONLY] 只读：任何会改状态 / 改文件 / 发外消息的工具一律拒绝执行，只放行查询类。
 * - [STRICT]    严格：所有非只读工具都要先弹审批卡，用户批准才执行。
 * - [STANDARD]  标准：只拦截高风险（资金 / 隐私 / 不可撤销），即旧布尔开关的行为。
 * - [SMART]     智能：由「决策模型」逐次裁决每一跳（默认跟随对话模型，可单独指定）。
 * - [AUTO]      自动：全部自动执行，不弹审批（系统级硬安全仍保留）。
 *
 * 持久化用 [key]；缺省回退到 [STANDARD]，与旧行为一致。
 */
enum class ToolReviewMode(val key: String, val labelRes: Int? = null) {
    READ_ONLY("read_only"),
    STRICT("strict"),
    STANDARD("standard"),
    SMART("smart"),
    AUTO("auto");

    companion object {
        const val DEFAULT = "standard"

        fun fromKey(key: String?): ToolReviewMode =
            entries.firstOrNull { it.key == key } ?: STANDARD

        fun normalized(key: String?): String = fromKey(key).key
    }
}

/** 审查模式对单次工具调用给出的裁决。 */
internal sealed interface ToolReviewOutcome {
    /** 放行，继续原有执行链路（含标准风险拦截）。 */
    data object Delegate : ToolReviewOutcome

    /** 直接拒绝，不执行，返回错误结果给模型。 */
    data class Reject(val code: String, val message: String) : ToolReviewOutcome

    /**
     * 弹审批卡，等用户裁决。
     *
     * [draft] 由策略层直接给出：标准档 / 智能档用规则引擎判出的风险草稿，
     * 严格档没有规则依据，就用统一构造的「确认执行 <工具名>」草稿。
     * 调用方拿这份草稿去 [io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard] 挂卡，
     * **不要**再跑一遍判定——跑两遍会出现「策略判了要问，登记时又判成不危险」的空转。
     */
    data class Ask(
        val draft: io.github.mangi.eta.agent.display.dashboard.ConfirmDraft,
    ) : ToolReviewOutcome
}

/**
 * 审查策略：把五档翻译成「这一跳该不该执行」。
 *
 * 这是**唯一**的策略入口：[io.github.mangi.eta.agent.tool.AgentLocalTools] 必须走这里，
 * 不允许在调用点再手写一份 when 分支——两份口径一定会漂移
 * （2026-11-20 审查实测：策略对象写好了却零调用点，执行链路是另一套手写分支）。
 *
 * 智能档只调「决策模型」一次裁决：
 * 1. 决策模型先判（自定义模型，默认跟随对话模型）；
 * 2. 模型判定有风险就拦下来问用户，判定安全 / 超时 / 失败都放行。
 *
 * 规则判定（[io.github.mangi.eta.agent.display.dashboard.ConfirmRiskDetector]）仍是标准档的边界，
 * 智能档不再叠加第二跳 LLM，避免「同一跳调两次模型」抬高延迟与成本。
 */
internal object AgentToolReviewPolicy {

    /** 只读工具白名单：复用 Plan 模式的同一份前缀约定，避免两处口径分裂。 */
    fun isReadOnly(toolName: String): Boolean = PlanModePolicy.isReadOnlyTool(toolName)

    /**
     * 按模式裁决一次工具调用。
     *
     * 返回值：
     * - [ToolReviewOutcome.Delegate] 放行，继续原有执行链路（含标准风险拦截）。
     * - [ToolReviewOutcome.Reject]   直接拒绝，返回错误给模型。
     * - [ToolReviewOutcome.Ask]      需要先问用户，由调用方拿 [ToolReviewOutcome.Ask.draft] 挂审批卡。
     *
     * [riskDetector] 由调用方注入（规则引擎），返回 null 表示规则没命中。
     * [arbiter] 由调用方注入（决策模型裁决），返回 true = 拦下问用户，false / null = 放行；
     * 传 null 表示未接入决策模型，此时智能档退回规则判定。
     */
    fun decide(
        mode: ToolReviewMode,
        toolName: String,
        args: JSONObject,
        riskDetector: () -> io.github.mangi.eta.agent.display.dashboard.ConfirmDraft?,
        arbiter: ((toolName: String, args: JSONObject) -> Boolean?)? = null,
    ): ToolReviewOutcome = when (mode) {
        ToolReviewMode.AUTO -> ToolReviewOutcome.Delegate

        ToolReviewMode.READ_ONLY -> {
            if (isReadOnly(toolName)) {
                ToolReviewOutcome.Delegate
            } else {
                ToolReviewOutcome.Reject(
                    code = "TOOL_REVIEW_READ_ONLY",
                    message = "工具调用审查处于「只读」档：$toolName 会改动状态，已拒绝执行。" +
                        "如需执行，请切换到标准 / 智能 / 自动档，或只调用查询类工具。",
                )
            }
        }

        ToolReviewMode.STRICT -> {
            if (isReadOnly(toolName)) {
                ToolReviewOutcome.Delegate
            } else {
                ToolReviewOutcome.Ask(strictDraft(toolName))
            }
        }

        ToolReviewMode.STANDARD -> {
            // 标准档：规则命中高风险才拦，没命中就放行。
            val draft = riskDetector()
            if (draft != null) ToolReviewOutcome.Ask(draft) else ToolReviewOutcome.Delegate
        }

        ToolReviewMode.SMART -> {
            // 只读工具不必再花一次模型调用。
            if (isReadOnly(toolName)) {
                ToolReviewOutcome.Delegate
            } else {
                // 规则优先：规则能判定的高风险直接拦，不再白等一次模型（最长 10s）。
                // 规则引擎（资金／隐私／不可撤销）的判据是确定性的，比模型更可靠也更快；
                // 只有「规则没命中」的灰色地带才交给决策模型裁决。
                val draft = riskDetector()
                if (draft != null) {
                    ToolReviewOutcome.Ask(draft)
                } else if (arbiter == null) {
                    // 未配置决策模型：规则也没命中，放行（与标准档一致，不空转模型请求）。
                    ToolReviewOutcome.Delegate
                } else {
                    when (arbiter.invoke(toolName, args)) {
                        true -> ToolReviewOutcome.Ask(arbiterDraft(toolName))
                        else -> ToolReviewOutcome.Delegate
                    }
                }
            }
        }
    }

    /** 严格档草稿：不跑风险判定（用户选了「严格」就默认全部可疑），统一按不可撤销登记。 */
    private fun strictDraft(toolName: String): io.github.mangi.eta.agent.display.dashboard.ConfirmDraft =
        io.github.mangi.eta.agent.display.dashboard.ConfirmDraft(
            risk = io.github.mangi.eta.agent.display.dashboard.ConfirmRisk.IRREVERSIBLE,
            title = "确认执行 $toolName",
            fields = listOf(
                io.github.mangi.eta.agent.display.dashboard.ConfirmField("工具", toolName),
            ),
        )

    /** 智能档草稿：决策模型判了「有风险」，但说不出具体是哪一类，按不可撤销登记。 */
    private fun arbiterDraft(toolName: String): io.github.mangi.eta.agent.display.dashboard.ConfirmDraft =
        io.github.mangi.eta.agent.display.dashboard.ConfirmDraft(
            risk = io.github.mangi.eta.agent.display.dashboard.ConfirmRisk.IRREVERSIBLE,
            title = "确认执行 $toolName",
            fields = listOf(
                io.github.mangi.eta.agent.display.dashboard.ConfirmField("工具", toolName),
            ),
        )
}
