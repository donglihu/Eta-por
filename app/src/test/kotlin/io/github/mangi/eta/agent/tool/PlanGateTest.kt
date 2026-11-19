package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划闸门的轮次语义：一个 run 里每来一轮用户输入都要重新「先交清单」。
 *
 * 这里锁住的是曾经缺失的行为：闸门按 run 武装一次，运行中插话注入的新轮次不受约束，
 * 表现为「Plan 模式开着，多轮对话后不生效」。
 */
class PlanGateTest {

    @Test
    fun `side effect tools are blocked before the plan is submitted`() {
        val gate = PlanGate(active = true)
        assertFalse(gate.allowsTool("write_file"))
        assertFalse(gate.allowsTool("terminal"))
    }

    @Test
    fun `read only tools stay available before the plan is submitted`() {
        val gate = PlanGate(active = true)
        assertTrue(gate.allowsTool("read_file"))
        assertTrue(gate.allowsTool(PlanModePolicy.PLAN_TOOL_NAME))
    }

    @Test
    fun `accepted plan releases the gate for this turn`() {
        val gate = PlanGate(active = true)
        gate.onPlanAccepted()
        assertTrue(gate.allowsTool("write_file"))
        assertTrue(gate.isReleased)
    }

    @Test
    fun `new user turn re-arms the gate and asks for a panel clear`() {
        val gate = PlanGate(active = true)
        gate.onPlanAccepted()
        assertTrue("上一轮交过清单，新一轮要点清空", gate.armForNewUserTurn())
        assertFalse("新一轮必须先交清单", gate.allowsTool("write_file"))
    }

    @Test
    fun `re-arming without a submitted plan does not clear the panel`() {
        val gate = PlanGate(active = true)
        assertFalse(gate.armForNewUserTurn())
    }

    @Test
    fun `plan mode off never blocks anything`() {
        val gate = PlanGate(active = false)
        assertTrue(gate.allowsTool("write_file"))
        assertFalse(gate.armForNewUserTurn())
        assertTrue(gate.isReleased)
    }
}
