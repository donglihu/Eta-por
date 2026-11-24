package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * `expert_manage`：让模型在会话里直接创建 / 修改 / 删除 / 列举专家。
 *
 * 为什么需要它：用户说「帮我建一个代码评审专家，按这个风格来」，
 * 以前模型没有任何入口，只能回一句「请到设置里手动新建」——这就是「没有被识别、不会快速创建」。
 * 现在模型可以一次性把分类、模型、工具集、绑定技能、注入项和提示词正文写进去，落地即可召唤。
 */
internal object AgentExpertToolCatalog {
    const val TOOL_NAME = "expert_manage"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = TOOL_NAME,
                description = "创建、修改、删除或列举可召唤的专家（会话角色）。" +
                    "用户说「帮我建一个 XX 专家」「按这个要求写个人设」「改一下刚才那个专家的提示词」时用它，" +
                    "不要只回复让用户去设置里手动新建。创建后可以立即用 action=summon 让当前会话以该专家身份作答。" +
                    "提示词正文要写清：是谁 + 背景、擅长什么与不做什么、工作流程、交付格式。" +
                    "写完后用返回的 summary 向用户说明创建结果，不要复述整段提示词。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject().put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("list")
                                            .put("create")
                                            .put("update")
                                            .put("delete")
                                            .put("summon")
                                            .put("dismiss"),
                                    )
                                    .put("description", "list 列举；create 新建（同名会覆盖）；update 修改已有；delete 删除；summon 召唤为当前会话专家；dismiss 取消召唤。"),
                            )
                            .put(
                                "name",
                                JSONObject().put("type", "string")
                                    .put("description", "唯一标识，英文小写短横线，如 code-reviewer。create 必填；update/delete/summon 用它定位。"),
                            )
                            .put(
                                "display_name",
                                JSONObject().put("type", "string")
                                    .put("description", "展示名，如「代码评审官」。"),
                            )
                            .put(
                                "description",
                                JSONObject().put("type", "string")
                                    .put("description", "一句话摘要：能力与适用场景，列表和详情页都显示它。"),
                            )
                            .put(
                                "category",
                                JSONObject().put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("writing")
                                            .put("data")
                                            .put("dev")
                                            .put("design")
                                            .put("business")
                                            .put("office")
                                            .put("life")
                                            .put("emotion")
                                            .put("other"),
                                    )
                                    .put("description", "分类：写作/数据/开发/设计/商务/办公/生活/情感/其他。省略按 other。"),
                            )
                            .put(
                                "prompt",
                                JSONObject().put("type", "string")
                                    .put("description", "专家提示词正文（人设 + 方法论 + 输出格式）。这是效果好坏的关键。"),
                            )
                            .put(
                                "skills",
                                JSONObject().put("type", "array")
                                    .put("items", JSONObject().put("type", "string"))
                                    .put("description", "召唤时自动加载的技能 id 列表；不确定就先省略。"),
                            )
                            .put(
                                "provider_id",
                                JSONObject().put("type", "string")
                                    .put("description", "模型服务商 id；留空表示继承会话当前模型。"),
                            )
                            .put(
                                "model_id",
                                JSONObject().put("type", "string")
                                    .put("description", "模型 id；留空表示继承会话当前模型。"),
                            )
                            .put(
                                "tools",
                                JSONObject().put("type", "array")
                                    .put("items", JSONObject().put("type", "string"))
                                    .put("description", "工具白名单；留空或 [\"*\"] 表示继承全量工具。"),
                            )
                            .put(
                                "disallowed_tools",
                                JSONObject().put("type", "array")
                                    .put("items", JSONObject().put("type", "string"))
                                    .put("description", "工具黑名单，优先于白名单；用于继承全量时排除个别工具。"),
                            )
                            .put(
                                "inject",
                                JSONObject().put("type", "array")
                                    .put("items", JSONObject().put("type", "string"))
                                    .put("description", "注入片段：mainRules / projectRules / skills / memory 等系统上下文段。"),
                            )
                            .put(
                                "pinned",
                                JSONObject().put("type", "boolean")
                                    .put("description", "是否置顶（排在召唤列表前部）。"),
                            )
                            .put(
                                "enabled",
                                JSONObject().put("type", "boolean")
                                    .put("description", "是否启用；关闭后不出现在召唤列表。"),
                            )
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}