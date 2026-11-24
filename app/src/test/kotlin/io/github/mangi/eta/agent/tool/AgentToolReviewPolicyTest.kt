package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.display.dashboard.ConfirmDraft
import io.github.mangi.eta.agent.display.dashboard.ConfirmRisk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 工具调用审查策略的纯 JVM 用例（不依赖 Android / Robolectric）。
 *
 * 重点钉住两件事：
 * 1. 智能档必须「规则优先」——规则能判定的高风险不再白等一次模型调用（最长 10s）；
 * 2. 智能档只在规则未命中时才调裁决模型，且模型结论仍能拦住。
 */
class AgentToolReviewPolicyTest {

    private fun draft(): ConfirmDraft = ConfirmDraft(
        risk = ConfirmRisk.IRREVERSIBLE,
        title = "确认",
        fields = emptyList(),
    )

    private fun decide(
        mode: ToolReviewMode,
        toolName: String,
        riskDraft: ConfirmDraft? = null,
        arbiterAnswer: Boolean? = null,
    ): Pair<ToolReviewOutcome, Int> {
        var arbiterCalls = 0
        val outcome = AgentToolReviewPolicy.decide(
            mode = mode,
            toolName = toolName,
            args = JSONObject(),
            riskDetector = { riskDraft },
            arbiter = { _, _ ->
                arbiterCalls += 1
                arbiterAnswer
            },
        )
        return outcome to arbiterCalls
    }

    /** 智能档：规则命中就直接拦，不再调模型。 */
    @Test
    fun smartModePrefersRuleHitAndSkipsModel() {
        val (outcome, calls) = decide(
            mode = ToolReviewMode.SMART,
            toolName = "terminal",
            riskDraft = draft(),
            arbiterAnswer = false,
        )
        assertEquals("规则命中时必须直接 Ask", true, outcome is ToolReviewOutcome.Ask)
        assertEquals("规则命中时不应再调裁决模型", 0, calls)
    }

    /** 智能档：规则未命中才调模型；模型说拦就拦。 */
    @Test
    fun smartModeFallsBackToModelWhenRulesMiss() {
        val (outcome, calls) = decide(
            mode = ToolReviewMode.SMART,
            toolName = "terminal",
            riskDraft = null,
            arbiterAnswer = true,
        )
        assertEquals(1, calls)
        assertEquals(true, outcome is ToolReviewOutcome.Ask)
    }

    /** 智能档：规则未命中且模型放行 → 放行。 */
    @Test
    fun smartModeDelegatesWhenModelAllows() {
        val (outcome, calls) = decide(
            mode = ToolReviewMode.SMART,
            toolName = "terminal",
            riskDraft = null,
            arbiterAnswer = false,
        )
        assertEquals(1, calls)
        assertEquals(ToolReviewOutcome.Delegate, outcome)
    }

    /** 智能档：只读工具既不查规则也不调模型。 */
    @Test
    fun smartModeNeverCallsModelForReadOnlyTools() {
        val (outcome, calls) = decide(
            mode = ToolReviewMode.SMART,
            toolName = "read_file",
            riskDraft = draft(),
            arbiterAnswer = true,
        )
        assertEquals(0, calls)
        assertEquals(ToolReviewOutcome.Delegate, outcome)
    }

    /** 未接入裁决模型时，智能档退回规则判定，不空转。 */
    @Test
    fun smartModeWithoutArbiterUsesRulesOnly() {
        val outcome = AgentToolReviewPolicy.decide(
            mode = ToolReviewMode.SMART,
            toolName = "terminal",
            args = JSONObject(),
            riskDetector = { null },
            arbiter = null,
        )
        assertEquals(ToolReviewOutcome.Delegate, outcome)
    }

    /** 自动档一律放行，连规则都不查。 */
    @Test
    fun autoModeAlwaysDelegates() {
        val (outcome, calls) = decide(
            mode = ToolReviewMode.AUTO,
            toolName = "terminal",
            riskDraft = draft(),
            arbiterAnswer = true,
        )
        assertEquals(ToolReviewOutcome.Delegate, outcome)
        assertEquals(0, calls)
    }

    /** 只读档：只读放行，改状态的直接拒绝。 */
    @Test
    fun readOnlyModeRejectsMutatingTools() {
        val readOnly = AgentToolReviewPolicy.decide(
            mode = ToolReviewMode.READ_ONLY,
            toolName = "read_file",
            args = JSONObject(),
            riskDetector = { null },
        )
        assertEquals(ToolReviewOutcome.Delegate, readOnly)

        val mutating = AgentToolReviewPolicy.decide(
            mode = ToolReviewMode.READ_ONLY,
            toolName = "terminal",
            args = JSONObject(),
            riskDetector = { null },
        )
        assertEquals(true, mutating is ToolReviewOutcome.Reject)
        assertEquals("TOOL_REVIEW_READ_ONLY", (mutating as ToolReviewOutcome.Reject).code)
    }

    /** 严格档：非只读一律先问，且不跑规则（避免两套口径）。 */
    @Test
    fun strictModeAsksForEveryMutatingTool() {
        val (outcome, calls) = decide(
            mode = ToolReviewMode.STRICT,
            toolName = "write_file",
            riskDraft = null,
            arbiterAnswer = false,
        )
        assertEquals(true, outcome is ToolReviewOutcome.Ask)
        assertEquals("严格档不该调模型", 0, calls)
    }

    /** 档位字符串归一：未知值回落 standard，不会误放宽成 auto。 */
    @Test
    fun unknownModeNormalizesToStandard() {
        assertEquals(ToolReviewMode.STANDARD, ToolReviewMode.fromKey("bogus"))
        assertEquals(ToolReviewMode.STANDARD, ToolReviewMode.fromKey(""))
        assertNull(null)
    }
}
