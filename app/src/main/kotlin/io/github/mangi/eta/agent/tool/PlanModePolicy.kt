package io.github.mangi.eta.agent.tool

/**
 * Plan 模式的工具名契约。
 *
 * 这里曾经做「写类工具目录级硬过滤」，现按需求下放：执行边界由
 * 提示词自约束 + 既有危险动作审查闸门（审批卡）负责。Runtime 不再按工具名
 * 裁剪目录，避免出现「界面看得见、Runtime 里不存在」的模型与工具集合不一致。
 */
internal object PlanModePolicy {
    /** 计划清单工具：plan 阶段产出、执行阶段回写，两种阶段都要在目录里。 */
    const val PLAN_TOOL_NAME = "update_plan"
}
