package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 计划面板状态判定与步骤状态归一。
 *
 * 这里锁的是此前真实出过的两个显示错误：
 * 3/3 全部完成仍显示「执行中」、7/7 全部完成仍显示「规划中」。
 * 判定只看完成度与 run 存活，不看输入框的 planMode 开关。
 */
class PlanPanelStateTest {
    @Test
    fun allDoneShowsDoneEvenWhileStillRunning() {
        // 模型把最后一步标成 done，但这一轮还在收尾：不能停在「执行中」。
        assertEquals(
            PlanPanelState.DONE,
            planPanelState(steps = allDone(3), isRunning = true),
        )
        assertEquals(
            PlanPanelState.DONE,
            planPanelState(steps = allDone(7), isRunning = false),
        )
    }

    @Test
    fun allPendingWhileRunningIsPlanning() {
        assertEquals(
            PlanPanelState.PLANNING,
            planPanelState(steps = step(PLAN_STEP_PENDING, count = 3), isRunning = true),
        )
    }

    @Test
    fun anyStartedStepWhileRunningIsExecuting() {
        assertEquals(
            PlanPanelState.EXECUTING,
            planPanelState(
                steps = listOf(
                    PlanStepUi("a", PLAN_STEP_DONE),
                    PlanStepUi("b", PLAN_STEP_ACTIVE),
                    PlanStepUi("c", PLAN_STEP_PENDING),
                ),
                isRunning = true,
            ),
        )
    }

    @Test
    fun finishedRunWithUnfinishedStepsIsEnded() {
        // 中断、出错，或模型没把最后一步标成 done：既不是执行中，也不是已完成。
        assertEquals(
            PlanPanelState.ENDED,
            planPanelState(
                steps = listOf(PlanStepUi("a", PLAN_STEP_DONE), PlanStepUi("b", PLAN_STEP_PENDING)),
                isRunning = false,
            ),
        )
    }

    @Test
    fun singleStepPlanIsDoneOnceCompleted() {
        assertEquals(
            PlanPanelState.DONE,
            planPanelState(steps = allDone(1), isRunning = false),
        )
    }

    @Test
    fun stepStatusSynonymsNormalizeToOneOfThreeValues() {
        val done = listOf("done", "DONE", "completed", "complete", "finished", "success", "ok", "1")
        val active = listOf("in_progress", "in-progress", "active", "doing", "running", "current", "2")
        val pending = listOf("pending", "todo", "", "   ", "unknown", "3", null)

        done.forEach { assertEquals("done -> $it", PLAN_STEP_DONE, normalizePlanStepStatus(it)) }
        active.forEach { assertEquals("active -> $it", PLAN_STEP_ACTIVE, normalizePlanStepStatus(it)) }
        pending.forEach { assertEquals("pending -> $it", PLAN_STEP_PENDING, normalizePlanStepStatus(it)) }
    }

    @Test
    fun codecRoundTripKeepsNormalizedStatus() {
        val steps = listOf(
            PlanStepUi("a", PLAN_STEP_DONE),
            PlanStepUi("b", PLAN_STEP_ACTIVE),
            PlanStepUi("c", PLAN_STEP_PENDING),
        )
        assertEquals(steps, PlanStepsCodec.decode(PlanStepsCodec.encode(steps)))
    }

    @Test
    fun codecDropsBlankStepsAndUnknownStatusBecomesPending() {
        val decoded = PlanStepsCodec.decode(
            """[{"text":"a","status":"weird"},{"text":"  "},{"step":"b"}]""",
        )
        assertEquals(
            listOf(PlanStepUi("a", PLAN_STEP_PENDING), PlanStepUi("b", PLAN_STEP_PENDING)),
            decoded,
        )
    }

    @Test
    fun codecToleratesBrokenJson() {
        assertEquals(emptyList<PlanStepUi>(), PlanStepsCodec.decode("not json"))
        assertEquals(emptyList<PlanStepUi>(), PlanStepsCodec.decode(""))
        assertEquals(emptyList<PlanStepUi>(), PlanStepsCodec.decode(null))
    }

    private fun allDone(count: Int) = step(PLAN_STEP_DONE, count)

    private fun step(status: String, count: Int) = List(count) { index ->
        PlanStepUi("step-$index", status)
    }
}
