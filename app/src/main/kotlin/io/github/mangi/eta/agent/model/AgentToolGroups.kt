package io.github.mangi.eta.agent.model

/**
 * 工具分组表：给「按需加载」提供唯一的组定义。
 *
 * 设计取舍（与 `docs/TOOL_GROUP_LOADING.md` 的差异都写在这里，便于 review 对照）：
 *
 * 1. **组表是静态的，不在运行时从 `AgentToolRequirements` 反推**。方案原文要求「唯一事实源是
 *    `AgentToolRequirements` 的域登记」，但域（[io.github.mangi.eta.agent.tool.ToolDomain]）表达的是
 *    并发闸门槽位，不是使用场景：`search_contacts` 与 `get_logcat` 都登记为 `NONE`，
 *    按域分组会把它们塞进同一个「什么都不碰」的组，模型仍要一次背 30 多个 schema。
 *    分组必须按用途切，而用途只有人知道；写成常量表 + 单测断言「每个登记工具恰属一组」，
 *    漏登记是可失败的断言，不是静默漏能力。
 * 2. **`basic` 常驻组放的是「别的工具要用它」与「几乎每轮都要」的工具**：审批（ask_user）、
 *    计划（update_plan）、上下文（get_current_context）、记忆（memory_*）、技能正文（skills_read）、
 *    任务与子代理编排。提示词里直接点名这些工具（`AgentPromptBuilder` 的技能索引段就写着
 *    「先调用 skills_read 读取对应 SKILL.md」），把它们延迟会导致「提示词教模型用、请求里没有」的错位。
 * 3. **MCP 工具不参与分组**。它们是第三方 schema，体积不可控、生命周期随 server，
 *    延迟它们等于给所有 MCP 用户的既有用法断言一次行为变更；本期只对本地目录做 hold。
 */
internal object AgentToolGroups {

    /** 常驻组 id：成员始终随请求下发，不经 `tools_use_group` 打开。 */
    const val BASIC = "basic"

    /** 组索引行里出现的组顺序即这里声明的顺序，保证系统提示字节稳定。 */
    private val definitions: List<Group> = listOf(
        Group(
            id = BASIC,
            summary = "常驻：提问、计划清单、上下文与设备概览、应用搜索与启动、记忆、技能正文读取、自动任务、子代理与专家编排。",
            resident = true,
            toolNames = setOf(
                "ask_user", "update_plan", "get_current_context", "device_status", "network_info",
                "search_apps", "launch_app", "open_uri",
                "memory_get", "memory_write", "character_memory_get", "character_memory_write",
                "skills_list", "skills_list_curated", "skills_read", "skills_read_resource", "skills_manage",
                "tasks_triggers", "tasks_list", "tasks_history",
                "tasks_create", "tasks_update", "tasks_delete", "tasks_run", "tasks_cancel",
                "subagent", "subagent_profile", "expert_manage",
                "wait", "wait_for_text", "wait_for_package",
            ),
        ),
        Group(
            id = "screen",
            summary = "屏幕观察与 GUI 操作：观察节点/截图、点击、长按、滑动、滚动、文本输入、系统按键、剪贴板、灵屏会话。",
            toolNames = setOf(
                "observe_screen", "tap", "tap_area", "tap_smart", "tap_element",
                "long_press", "long_press_element", "swipe", "scroll", "scroll_element",
                "type_text", "press_key", "open_system_panel",
                "get_clipboard", "set_clipboard",
                "virtual_screen", "lingscreen_open_app",
            ),
        ),
        Group(
            id = "file",
            summary = "文件读写与检索：读写/编辑文件、列目录、stat、glob、grep、读图片。",
            toolNames = setOf(
                "read_file", "write_file", "edit_file", "list_directory", "stat_file",
                "glob_files", "grep_files", "read_image",
            ),
        ),
        Group(
            id = "terminal",
            summary = "终端与后台任务：Android/Linux 命令、后台任务与常驻服务、日志读取。",
            toolNames = setOf("terminal", "terminal_job", "terminal_daemon", "get_logcat"),
        ),
        Group(
            id = "browser",
            summary = "网页：本地搜索、静态抓取正文、离屏浏览器（JS/登录/截图）。",
            toolNames = setOf("browser_use", "web_search", "fetch_url"),
        ),
        Group(
            id = "device",
            summary = "设备控制写入：系统设置、网络与开关、亮度与息屏、音量与铃声、免打扰、手电、热点、应用启停、媒体控制、闹钟与计时器。",
            toolNames = setOf(
                "set_setting", "set_device_state", "set_brightness", "set_screen_timeout",
                "set_volume", "set_sound_mode", "set_do_not_disturb",
                "set_flashlight", "set_hotspot", "app_state_control", "media_control",
                "set_alarm", "set_alarm_enabled", "update_alarm_time", "delete_alarm", "set_timer",
            ),
        ),
        Group(
            id = "device_state",
            summary = "设备状态与个人上下文读取：设置项、显示/声音/手电/热点、安装应用、内存与存储占用、闹钟与计时器、通知、位置、使用时长、健康、Wi-Fi、短信验证码。",
            toolNames = setOf(
                "get_setting", "get_device_state", "get_display_state", "get_sound_state",
                "get_flashlight", "get_hotspot", "inspect_app",
                "top_memory_apps", "top_storage_apps", "list_alarms", "list_active_timers",
                "recent_notifications", "recent_app_activity", "app_usage_summary",
                "get_current_location", "get_device_environment", "get_health_summary",
                "wifi_credentials", "read_sms_code",
            ),
        ),
        Group(
            id = "calendar",
            summary = "日历读写：列可写日历、按 event_id 读写、增删改日程与提醒、检索日程与日历待办。",
            toolNames = setOf(
                "list_calendars", "read_calendar_event", "search_calendar_events", "search_calendar_todos",
                "create_calendar_event", "create_calendar_events",
                "update_calendar_event", "delete_calendar_event",
            ),
        ),
        Group(
            id = "personal",
            summary = "个人内容：短信/通话/通讯录/便签/录音/文件与下载/账单与订单/行程/聊天图片/媒体语义检索。",
            toolNames = setOf(
                "create_note", "read_note", "delete_note", "read_personal_item", "summarize_bills",
                "search_notes", "search_system_memories", "search_memory_collections",
                "search_coloros_recordings", "search_recording_summaries", "search_recordings",
                "search_messages", "search_call_history", "search_contacts",
                "search_clipboard_history", "search_notification_history",
                "search_bills", "search_todos", "search_daily_events",
                "search_flights", "search_hotels", "search_trains",
                "search_personal_orders", "search_files", "search_downloads",
                "search_media", "search_audio",
                "search_qq_chat_images", "search_wechat_chat_images", "search_saved_places",
            ),
        ),
        Group(
            id = "skill",
            summary = "技能安装：从公开 GitHub 仓库检查与安装 Skill（读取正文用常驻的 skills_read）。",
            toolNames = setOf("skills_inspect_github", "skills_install_from_github"),
        ),
    )

    internal data class Group(
        val id: String,
        val summary: String,
        val toolNames: Set<String>,
        val resident: Boolean = false,
    )

    private val byId: Map<String, Group> = definitions.associateBy(Group::id)

    private val byTool: Map<String, Group> = buildMap {
        definitions.forEach { group -> group.toolNames.forEach { name -> put(name, group) } }
    }

    /** 已在执行侧登记、但只做旧 transcript 兼容、不再出现在模型目录的工具。 */
    private val executionOnly: Set<String> =
        setOf("input_text", "replace_text", "clear_text", "paste_text")

    /** 分组表覆盖的全部工具名（含常驻组）。 */
    val toolNames: Set<String> get() = byTool.keys

    /** `basic` 之外的组：只有这些组需要模型显式打开。 */
    val deferredGroups: List<Group> get() = definitions.filterNot(Group::resident)

    /** 工具所属组；未分组（含 executionOnly 与 MCP 工具）返回 null。 */
    fun groupOf(toolName: String): String? = byTool[toolName]?.id

    fun group(id: String): Group? = byId[id]

    /** 组内成员；组不存在返回 null。 */
    fun members(id: String): Set<String>? = byId[id]?.toolNames

    /**
     * 系统提示里的组索引段。
     *
     * 内容**只依赖可选组集合**，不依赖「已打开哪些组」：否则每开一次组就要重写 system，
     * 把「一次前缀重付」变成两次（tools + system 双改）。
     */
    fun indexMessage(): String = buildString {
        appendLine("延迟加载的工具组（schema 未随本轮请求下发，调用前先打开）：")
        deferredGroups.forEach { group ->
            appendLine("- ").append(group.id).append("：").append(group.summary)
        }
        append(
            "需要某组时先调用 ${AgentToolGroupCatalog.USE_GROUP}(group, reason) 打开整组；" +
                "打开后本轮即可用 ${AgentToolGroupCatalog.CALL}(tool, arguments) 调用组内工具。" +
                "常驻组（$BASIC）的工具直接调用，不要经 ${AgentToolGroupCatalog.CALL} 转发。" +
                "一次 run 最多打开 ${AgentToolGroupSession.MAX_OPENS_PER_RUN} 个组，只开当前任务真正要用的组。",
        )
    }

    /** 供单测断言「分组表恰好覆盖目录里的工具」。 */
    internal fun ungrouped(names: Set<String>): Set<String> =
        names.filterNot { it in toolNames || it in executionOnly || it.startsWith("mcp_") }.toSet()
}
