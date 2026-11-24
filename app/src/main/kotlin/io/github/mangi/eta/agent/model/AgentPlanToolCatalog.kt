package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.PlanModePolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * `update_plan`：把当前计划步骤清单交给界面。
 *
 * plan 阶段用它产出计划；执行阶段用它回写每一步的状态。
 * 工具本身不改文件、不动设备，因此在 plan 的只读边界里是唯一允许的状态写入口。
 * 执行方（[AgentModelClient]）只做校验与转发，不落库——落库由界面进程统一负责。
 */
internal object AgentPlanToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = PlanModePolicy.PLAN_TOOL_NAME,
                description = "维护当前任务的计划清单（会话框上方的计划面板）。" +
                    "只有本轮启用计划模式时才使用它。" +
                    "plan 阶段：先调用它提交完整步骤，再输出方案说明，随后直接进入执行。" +
                    "执行阶段：每完成或开始一步就调用它回写状态，让用户看到进度。" +
                    "用户在运行中补充新指令＝新的一轮：先更新清单再动手，旧清单不再适用。" +
                    "只描述步骤文本与状态，不要写工具名、路径或内部推理。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "steps",
                            JSONObject()
                                .put("type", "array")
                                .put("description", "完整计划清单，顺序即执行顺序；每次都提交全量，不要只提交增量。")
                                .put(
                                    "items",
                                    JSONObject()
                                        .put("type", "object")
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put(
                                                    "text",
                                                    JSONObject().put("type", "string")
                                                        .put("description", "一步一句话，写清交付结果，不要写抽象口号。"),
                                                )
                                                .put(
                                                    "status",
                                                    JSONObject().put("type", "string")
                                                        .put("enum", JSONArray().put("pending").put("in_progress").put("done"))
                                                        .put("description", "pending 未开始；in_progress 进行中；done 已完成。"),
                                                ),
                                        )
                                        .put("required", JSONArray().put("text")),
                                ),
                        ),
                    )
                    .put("required", JSONArray().put("steps")),
            ),
        )
    }
}