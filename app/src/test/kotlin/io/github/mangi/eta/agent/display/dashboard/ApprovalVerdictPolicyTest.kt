package io.github.mangi.eta.agent.display.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批卡裁决语义表：`跳过` / `自由输入` / `多选` 在 guard 与 ask 两种来源下各自该不该拒绝。
 *
 * 对应 2026-11-26 审核 P0-2：用户点「跳过」本意是「别做这一步」，但 `skip()` 没把
 * `rejected` 传下去，工具侧读到 `rejected=false` 就把被拦的工具执行了。
 */
class ApprovalVerdictPolicyTest {

    // ---- 跳过 ----

    @Test
    fun guardSkipMeansReject() {
        assertTrue(ApprovalVerdictPolicy.skipRejects(PendingApproval.SOURCE_GUARD))
    }

    @Test
    fun askSkipStaysNonReject() {
        // ask 卡上的「跳过」是「这题我不答，你自己往下走」，语义不变。
        assertFalse(ApprovalVerdictPolicy.skipRejects(PendingApproval.SOURCE_ASK))
    }

    @Test
    fun unknownSourceFailsClosed() {
        // 来源缺失 / 旧数据 / 未来新增来源一律按 guard 处理，不许悄悄放行。
        assertTrue(ApprovalVerdictPolicy.skipRejects(""))
        assertTrue(ApprovalVerdictPolicy.skipRejects("future-source"))
    }

    @Test
    fun guardSkipReplyForbidsRetry() {
        val reply = ApprovalVerdictPolicy.skipReply(PendingApproval.SOURCE_GUARD, "确认执行 terminal")
        assertTrue(reply.contains("没有执行"))
        assertTrue(reply.contains("不要换方式重试"))
    }

    @Test
    fun askSkipReplyKeepsOriginalWording() {
        val reply = ApprovalVerdictPolicy.skipReply(PendingApproval.SOURCE_ASK, "要选哪个方案？")
        assertTrue(reply.contains("用户跳过了"))
        assertFalse(reply.contains("不要换方式重试"))
    }

    // ---- 自由输入 ----

    @Test
    fun guardFreeformStillRejects() {
        // 带文本不等于批准：guard 卡上用户写的是修改意见。
        assertTrue(ApprovalVerdictPolicy.freeformRejects(PendingApproval.SOURCE_GUARD))
    }

    @Test
    fun askFreeformStaysNonReject() {
        assertFalse(ApprovalVerdictPolicy.freeformRejects(PendingApproval.SOURCE_ASK))
    }

    @Test
    fun guardFreeformReplySaysOriginalNotExecuted() {
        val reply = ApprovalVerdictPolicy.freeformReply(
            source = PendingApproval.SOURCE_GUARD,
            title = "确认执行 terminal",
            answer = "改成写到 /sdcard/y",
        )
        assertTrue(reply.contains("没有执行"))
        assertTrue(reply.contains("改成写到 /sdcard/y"))
        assertTrue(reply.contains("重新规划"))
    }

    @Test
    fun askFreeformReplyKeepsOriginalWording() {
        val reply = ApprovalVerdictPolicy.freeformReply(
            source = PendingApproval.SOURCE_ASK,
            title = "要选哪个方案？",
            answer = "方案 B",
        )
        assertTrue(reply.contains("请按这个回答继续"))
        assertFalse(reply.contains("没有执行"))
    }

    // ---- 多选 ----

    @Test
    fun guardManyRejectsUnlessApprovePicked() {
        assertTrue(
            ApprovalVerdictPolicy.manyRejects(
                PendingApproval.SOURCE_GUARD,
                listOf(PromptOptionId.REJECT),
            ),
        )
        assertTrue(ApprovalVerdictPolicy.manyRejects(PendingApproval.SOURCE_GUARD, emptyList()))
        assertFalse(
            ApprovalVerdictPolicy.manyRejects(
                PendingApproval.SOURCE_GUARD,
                listOf(PromptOptionId.APPROVE),
            ),
        )
        assertFalse(
            ApprovalVerdictPolicy.manyRejects(
                PendingApproval.SOURCE_GUARD,
                listOf(PromptOptionId.REJECT, PromptOptionId.REMEMBER),
            ),
        )
    }

    @Test
    fun askManyStaysNonReject() {
        assertFalse(
            ApprovalVerdictPolicy.manyRejects(
                PendingApproval.SOURCE_ASK,
                listOf(PromptOptionId.REJECT),
            ),
        )
    }

    // ---- 工具侧处置 ----

    @Test
    fun rejectedVerdictNeverExecutes() {
        // 这是 P0-2 的核心断言：rejected=true 必须映射成 REJECTED，而不是 EXECUTE。
        assertEquals(
            ApprovalVerdictPolicy.Disposition.REJECTED,
            ApprovalVerdictPolicy.dispositionOf(VerdictOutcome("用户拒绝了这一步", rejected = true)),
        )
    }

    @Test
    fun approvedVerdictExecutes() {
        assertEquals(
            ApprovalVerdictPolicy.Disposition.EXECUTE,
            ApprovalVerdictPolicy.dispositionOf(VerdictOutcome("用户已批准", rejected = false)),
        )
    }

    @Test
    fun missingVerdictIsNotApproval() {
        // 超时 / 取消（null）不算批准。超时该不该改成 fail-closed 是 H7，本次不动。
        assertEquals(
            ApprovalVerdictPolicy.Disposition.NO_VERDICT,
            ApprovalVerdictPolicy.dispositionOf(null),
        )
        assertEquals(
            ApprovalVerdictPolicy.Disposition.NO_VERDICT,
            ApprovalVerdictPolicy.dispositionOf(VerdictOutcome(null, rejected = false)),
        )
        assertEquals(
            ApprovalVerdictPolicy.Disposition.NO_VERDICT,
            ApprovalVerdictPolicy.dispositionOf(VerdictOutcome("   ", rejected = false)),
        )
    }

    @Test
    fun rejectedWithEmptyReplyDoesNotExecute() {
        // rejected=true 但没有文案：按「无有效裁决」处理（NEED_CONFIRM），同样不会执行。
        assertEquals(
            ApprovalVerdictPolicy.Disposition.NO_VERDICT,
            ApprovalVerdictPolicy.dispositionOf(VerdictOutcome("", rejected = true)),
        )
    }
}
