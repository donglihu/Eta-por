package io.github.mangi.eta.agent.tool

/**
 * Plan 模式的工具契约。
 *
 * 这里曾经做「写类工具目录级硬过滤」，现按需求下放：工具目录照常声明、照常可见，
 * 执行边界由两层负责：
 *
 * 1. 提示词自约束（`AgentPromptBuilder.PLAN_MODE_INSTRUCTION`）；
 * 2. **计划闸门**：本轮还没提交过计划清单时，除只读工具外的调用一律拒绝执行，
 *    返回 [CODE_PLAN_REQUIRED_FIRST]。这一层不是裁剪工具目录，只是把「先计划」变成
 *    确定行为——否则模型心情好就先规划、心情不好直接开工，表现为「开了开关偶尔不生效」。
 *
 * 计划一旦提交，闸门放开，本轮余下的执行路径与普通模式完全一致。
 */
internal object PlanModePolicy {
    /** 计划清单工具：plan 阶段产出、执行阶段回写，两种阶段都要在目录里。 */
    const val PLAN_TOOL_NAME = "update_plan"

    /** 计划未提交就想动手时的错误码，模型据此自我纠正。 */
    const val CODE_PLAN_REQUIRED_FIRST = "PLAN_REQUIRED_FIRST"

    /**
     * 按前缀识别的只读工具。
     *
     * 只读是「不动设备、不改文件、不发外消息」：查询类工具全部按动词前缀归类，
     * 新增工具只要命名守约定就自动落在白名单里，不必每加一个工具回来登记。
     */
    private val READ_ONLY_PREFIXES = listOf(
        "get_", "search_", "read_", "list_", "stat_", "glob_", "grep_",
        "observe_", "inspect_", "check_", "query_", "find_",
    )

    /**
     * 命名不在前缀约定内、但计划阶段确实只读的工具。
     *
     * `browser_use` 不在这里：它能点击、能提交表单，计划阶段只该用 `fetch_url` 读正文。
     */
    private val READ_ONLY_TOOLS = setOf(
        PLAN_TOOL_NAME,
        "device_status",
        "network_info",
        "web_search",
        "fetch_url",
        // 规划阶段本来就要问清条件，问一句不算动手。
        "ask_user",
        "wait",
        "wait_for_text",
        "wait_for_package",
        "skills_list",
        "skills_read",
        "skills_list_curated",
        "skills_inspect_github",
        "memory_get",
        "character_memory_get",
        "conversation_history",
        "tasks_list",
        "tasks_triggers",
        "tasks_history",
    )

    fun isReadOnlyTool(name: String): Boolean =
        name in READ_ONLY_TOOLS || READ_ONLY_PREFIXES.any { prefix -> name.startsWith(prefix) }

    /** 计划未提交时被拦下的工具统一回复，文案要能直接指导模型下一步。 */
    fun planRequiredFirstMessage(toolName: String): String =
        "Plan 模式：本轮还没提交计划清单，$toolName 未执行。" +
            "先用 $PLAN_TOOL_NAME 提交完整计划清单（一步一句话，写清交付结果与验收点），" +
            "再按清单逐项执行并回写状态。"
}
