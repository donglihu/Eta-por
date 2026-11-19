package io.github.mangi.eta.agent.tool

/**
 * 计划闸门的轮次状态。
 *
 * 闸门要回答两个问题：
 *
 * 1. 这一轮能不能动副作用工具——没提交清单之前不能；
 * 2. 什么时候重新要求清单——**每一轮用户输入**，而不是每次 run 一次。
 *
 * 第 2 点是曾经的缺口：闸门按 run 武装，运行中用户插话注入的新轮次不再受约束，
 * 表现就是「Plan 模式开着，多轮对话后不生效」。所以 [armForNewUserTurn] 由循环在注入
 * 用户补充指令时调用，返回值表示「上一轮确实交过清单」——界面据此清空旧清单。
 */
internal class PlanGate(val active: Boolean) {
    private val submitted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 本轮是否已经交过清单（放闸）。 */
    val isReleased: Boolean
        get() = !active || submitted.get()

    /** `update_plan` 真正接收了清单：只有合法清单才放闸，参数错误继续挡。 */
    fun onPlanAccepted() {
        if (active) submitted.set(true)
    }

    /**
     * 新的一轮用户输入到达：重新武装。
     *
     * @return 需要通知界面清空上一轮清单时为 true（即之前确实交过清单）。
     */
    fun armForNewUserTurn(): Boolean {
        if (!active) return false
        return submitted.getAndSet(false)
    }

    /** 工具是否可以在本轮执行：只读工具始终放行，副作用工具要等清单提交。 */
    fun allowsTool(name: String): Boolean =
        !active || submitted.get() || PlanModePolicy.isReadOnlyTool(name)
}
