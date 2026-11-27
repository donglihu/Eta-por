package io.github.mangi.eta.agent.display.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 端到端回归：审批卡「跳过」/「自由输入」在 guard 来源下必须落成 `rejected = true`。
 *
 * 对应 2026-11-26 审核 P0-2（fail-open）：
 * `cp /sdcard/x /system/y` 被拦 → 卡上点「跳过」→ 原先 `skip()` 不传 `rejected`，
 * 工具侧读到 `rejected = false` 就落 `approvalGranted = true`，被拦的命令真的执行了。
 *
 * 这里刻意走真实链路（`inspectTool` 判定 → `registerDraft` 挂卡 → `skip()` / `chooseFreeform()`
 * → `awaitVerdict()` 取裁决），不 mock 中间层；`context` 传 null 表示无 UI / 无通知场景，
 * 所有 Android 依赖都是「有就发通知、没有就跳过」的可空分支，不影响裁决本身。
 */
class AgentConfirmGuardSkipTest {

    /**
     * 每例开始前清掉「批准宽限期」。
     *
     * `register()` 命中同一主体的 30 分钟 grant 时会直接返回 null（不再挂卡），
     * 于是「批准」那一例会把后续各例的挂卡全部短路——这是测试隔离问题，不是产品缺陷：
     * 真机上用户批准同一操作后本来就不该被再问一遍。
     */
    @Before
    fun clearGrantWindow() {
        AgentConfirmGuard.clearGrants()
    }

    /** 审查员给的复现场景：写系统目录。 */
    private fun guardToken(toolName: String = "terminal", args: JSONObject): String {
        val draft = ConfirmRiskDetector.inspectTool(toolName, args)
        assertNotNull("这条命令应被判定为需要确认", draft)
        val registered = AgentConfirmGuard.registerDraft(
            context = null,
            draft = draft!!,
            target = args.optString("command").ifBlank { args.optString("path") },
            action = toolName,
            runId = "",
            toolName = toolName,
            toolArgs = args.toString(),
        )
        assertNotNull("挂卡应返回带 token 的草稿", registered)
        return registered!!.token
    }

    private fun systemWriteToken(): String = guardToken(
        args = JSONObject().put("command", "cp /sdcard/x /system/y"),
    )

    /** 挂一张 ask 来源的卡（模型提问），用来验证本次修复没有误伤它。 */
    private fun askToken(): String {
        val token = AgentConfirmGuard.registerAsk(
            context = null,
            question = "要用哪个方案？",
            body = "",
            options = listOf(
                PromptOption("a", "方案 A"),
                PromptOption("b", "方案 B"),
            ),
        )
        assertNotNull("ask 卡应能挂上", token)
        return token!!
    }

    @Test
    fun guardSkipVerdictIsRejected() {
        val token = systemWriteToken()

        val returned = AgentConfirmGuard.skip(null, token)
        // 工具线程仍在 awaitVerdict 上等待时 skip() 返回 null（不需要 UI 兜底新发消息）。
        assertEquals(null, returned)

        val outcome = AgentConfirmGuard.awaitVerdict(token, timeoutMs = 1_000L)
        assertNotNull("skip 之后应能取到裁决", outcome)
        assertTrue("guard 卡上「跳过」必须是拒绝", outcome!!.rejected)
        assertTrue("回灌文案要说清没有执行", outcome.reply.orEmpty().contains("没有执行"))
    }

    @Test
    fun guardSkipNeverExecutes() {
        val token = systemWriteToken()
        AgentConfirmGuard.skip(null, token)
        val outcome = AgentConfirmGuard.awaitVerdict(token, timeoutMs = 1_000L)

        // 工具侧的处置必须不是 EXECUTE——这正是 approvalGranted = true 的唯一入口。
        assertEquals(
            ApprovalVerdictPolicy.Disposition.REJECTED,
            ApprovalVerdictPolicy.dispositionOf(outcome),
        )
        // 失败语义的结果码：模型收到 USER_REJECTED 才会停手，而不是把跳过当成放行。
        val result = JSONObject(
            AgentConfirmGuard.verdictResult(outcome!!.reply.orEmpty(), null, rejected = outcome.rejected),
        )
        assertEquals(false, result.getBoolean("ok"))
        assertEquals("USER_REJECTED", result.getString("code"))
    }

    @Test
    fun guardFreeformWithTextStillRejects() {
        val token = systemWriteToken()

        AgentConfirmGuard.chooseFreeform(null, token, "改成写到 /sdcard/y")
        val outcome = AgentConfirmGuard.awaitVerdict(token, timeoutMs = 1_000L)

        assertNotNull(outcome)
        // 带文本不等于批准：原工具仍然不执行，模型按意见重新规划。
        assertTrue("guard 卡上自由输入必须仍然拒绝", outcome!!.rejected)
        assertEquals(
            ApprovalVerdictPolicy.Disposition.REJECTED,
            ApprovalVerdictPolicy.dispositionOf(outcome),
        )
        assertTrue(outcome.reply.orEmpty().contains("没有执行"))
        assertTrue(outcome.reply.orEmpty().contains("改成写到 /sdcard/y"))
    }

    @Test
    fun askSkipKeepsLegacySemantics() {
        // ask 卡（模型提问）：「跳过」= 这题不答，保持放行，不能被这次修复误伤。
        val token = askToken()

        AgentConfirmGuard.skip(null, token)
        val outcome = AgentConfirmGuard.awaitVerdict(token, timeoutMs = 1_000L)

        assertNotNull(outcome)
        assertEquals(false, outcome!!.rejected)
        assertEquals(
            ApprovalVerdictPolicy.Disposition.EXECUTE,
            ApprovalVerdictPolicy.dispositionOf(outcome),
        )
        assertTrue(outcome.reply.orEmpty().contains("用户跳过了"))
    }

    @Test
    fun askFreeformKeepsLegacySemantics() {
        val token = askToken()

        AgentConfirmGuard.chooseFreeform(null, token, "方案 B")
        val outcome = AgentConfirmGuard.awaitVerdict(token, timeoutMs = 1_000L)

        assertNotNull(outcome)
        assertEquals(false, outcome!!.rejected)
        assertTrue(outcome.reply.orEmpty().contains("请按这个回答继续"))
    }

    @Test
    fun rejectOptionStillRejected() {
        // 既有出口不能被这次改动带偏：显式「拒绝」仍然 rejected。
        val token = systemWriteToken()
        AgentConfirmGuard.reject(null, token)
        val outcome = AgentConfirmGuard.awaitVerdict(token, timeoutMs = 1_000L)
        assertTrue(outcome!!.rejected)
    }

    @Test
    fun approveOptionStillExecutes() {
        // 反向对照：真正批准时 disposition 必须是 EXECUTE，否则修复会变成「一律拒绝」。
        val token = systemWriteToken()
        AgentConfirmGuard.approve(null, token)
        val outcome = AgentConfirmGuard.awaitVerdict(token, timeoutMs = 1_000L)
        assertEquals(false, outcome!!.rejected)
        assertEquals(
            ApprovalVerdictPolicy.Disposition.EXECUTE,
            ApprovalVerdictPolicy.dispositionOf(outcome),
        )
    }
}
