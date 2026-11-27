package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.context.PersonalSearchTools

internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION, CALENDAR_READ, CALENDAR_WRITE }

/**
 * 工具独占的资源域（多会话并发的串行粒度）。
 *
 * 手机只有一个灵屏、一套终端环境、一套系统设置：过去用一把全局锁把所有工具串起来，
 * 连「读一个文件」都要排队。这里改成按域排队——同域串行、跨域并行。
 *
 * - [SCREEN] 屏幕/无障碍动作，同一 display 一把锁；
 * - [SHELL] 终端命令，同一对话一条会话串行；
 * - [FS_WRITE] 文件写入，按父目录串行（同目录的两个写不能并行）；
 * - [DEVICE] 系统全局设置（闹钟、音量、亮度、剪贴板、记忆写入…），单例资源；
 * - [BROWSER] 共享离屏浏览器，单例；
 * - [MCP] 远端 MCP 服务器，按 server 段串行；
 * - [NONE] 不碰共享资源，不进闸门（查询、检索、等待、子代理派发等）。
 */
internal enum class ToolDomain { SCREEN, SHELL, FS_WRITE, DEVICE, BROWSER, MCP, NONE }

internal data class LocalToolRequirement(
    val rootRequirement: RootRequirement,
    val lsposedRequirement: LsposedRequirement = LsposedRequirement.NONE,
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
    val colorOs: Boolean = false,
    val vivoAlternative: Boolean = false,
    /**
     * 资源域：**没有默认值**，每个登记点必须显式给出。
     *
     * 之前它有 `= ToolDomain.NONE` 默认值，加上 `register(root, vararg names)` 无域重载，
     * 漏填等于「静默绕闸门」——漏登记和「确实不需要串行」在代码上长得一模一样，
     * 任何防回归断言也就恒真。去掉默认值后漏填是编译错误，不碰共享资源的工具必须写明
     * `ToolDomain.NONE`，并在登记处一行注释说明为什么不需要串行。
     */
    val domain: ToolDomain,
)

/** 展示、模型目录与执行边界共同使用的本地工具能力合同。未登记的工具不能发布（project 时直接跳过并留痕）。 */
internal object AgentToolRequirements {
    /** MCP 远端工具名前缀：`mcp_<serverPart>_<tool>_<digest>`，见 McpRunContext。 */
    private const val MCP_TOOL_PREFIX = "mcp_"

    /**
     * 域登记记录：`register(root, domain, ...)` 与 `declareDomain(domain, ...)` 写它。
     *
     * [declaredDomainNames] 是它的只读视图：单测拿 `toolNames - declaredDomainNames` 做集合差，
     * 于是「新增工具忘了声明域」是可失败的断言，而不是永远为真的兜底检查。
     */
    private val domainOfRegistered = HashMap<String, ToolDomain>()

    /** 已经**显式**声明过域的工具名（含显式 `ToolDomain.NONE`）。供单测做集合差断言。 */
    internal val declaredDomainNames: Set<String> get() = domainOfRegistered.keys

    private fun declaredDomain(name: String): ToolDomain =
        domainOfRegistered[name] ?: ToolDomain.NONE

    private val definitions = buildMap {
        fun register(root: RootRequirement, domain: ToolDomain, vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement(root, domain = domain)) == null) {
                    "Duplicate tool: $name"
                }
                domainOfRegistered[name] = domain
            }
        }

        /**
         * 只声明域，不登记清单：手机目录（[AgentPhoneToolCatalog]）里的工具 root 由目录给出，
         * 这里只补域，避免它们悄悄落进 [ToolDomain.NONE] 绕过闸门。
         */
        fun declareDomain(domain: ToolDomain, vararg names: String) {
            names.forEach { name -> domainOfRegistered[name] = domain }
        }
        register(
            RootRequirement.NONE, ToolDomain.NONE,
            "get_current_context", "search_apps", "web_search", "fetch_url",
            "ask_user",
            // 计划清单：纯本地状态写入口，不需要 Root / 无障碍 / 系统权限。
            "update_plan",
            // 按需加载的两个入口：只改 run 内开组状态、或把调用转发给真实工具，
            // 自身不碰设备也不碰文件 → NONE（不进资源闸门，不占串行槽）。
            // 不登记的后果：project() 会把它们静默跳过（目录里没有 → 机制不可用），
            // 且 ToolSelectionItems 按 rootRequirement 分组时直接抛 Missing tool requirements。
            io.github.mangi.eta.agent.model.AgentToolGroupCatalog.USE_GROUP,
            io.github.mangi.eta.agent.model.AgentToolGroupCatalog.CALL,
            // 文本输入类（input_text / replace_text / clear_text / paste_text）是 **executionOnly**：
            // 它们已不在模型目录里（AgentToolCatalog 不再声明，单测 executionOnly 断言钉住），
            // 只留作旧 transcript 重放兼容。因此不占 SCREEN 槽——真正的屏幕动作由调用方的
            // type_text / press_key 持有 SCREEN，这里再抢同一个槽就是自锁（GateReentrant）。
            "input_text",
            "replace_text", "clear_text", "get_clipboard", "paste_text",
            "wait",
            "device_status", "inspect_app",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get",
            "character_memory_get",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github",
            // 任务的只读面：查询与事件契约，不改状态。
            "tasks_triggers", "tasks_list", "tasks_history",
            // 子智能体派发：跑在应用内，不碰设备也不碰 Root，只用模型与本地文件。
            // 不登记的后果：设置页「工具白名单」里根本列不出它，
            // 而白名单一旦是 ALLOW_LIST 模式，AgentToolPolicy.filterTools() 会把它从
            // 请求的 tools 数组里滤掉 —— 模型一调就回 TOOL_NOT_AVAILABLE（2026-11-20 实测）。
            "subagent",
            // 注：subagent_profile（写工作区 agents/）、expert_manage（Prefs 整表读改写）、
            // tasks_create/tasks_update/tasks_delete/tasks_run/tasks_cancel（全局任务表）
            // 都有写副作用，域在下面「资源域登记」区单独给出；留在这里等于退回无串行。
            // 漏登记的后果（按现状写，别沿用旧说法）：早先是 project() 在拼工具阶段直接抛
            // "Missing tool requirements" 把整轮运行打断（2026-11-20 expert_manage 实测）；
            // 现在 project() 改为「跳过 + lastSkippedTools 留痕」（见本文件 project()），
            // 所以整轮不会崩，代价是该工具从模型目录里静默消失 —— 目录一致性只由单测
            // （toolNames - declaredDomainNames）守。仍在抛的是 UI 分组：ToolSelectionItems 按
            // rootRequirement(name) 分组，漏登记在那里会直接抛。
        )
        // —— 资源域登记：domain 决定并发闸门的串行槽位（见 AgentResourceGate），必须显式给出 ——
        register(
            RootRequirement.NONE, ToolDomain.SCREEN,
            "observe_screen", "tap", "tap_area", "tap_smart", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "type_text",
            "open_uri", "launch_app", "lingscreen_open_app", "wait_for_text", "wait_for_package",
            // 展开通知栏/系统面板会改变屏幕内容，与 press_key 同级：屏幕槽，不是 NONE。
            "open_system_panel",
        )
        register(RootRequirement.NONE, ToolDomain.BROWSER, "browser_use")
        register(
            RootRequirement.NONE, ToolDomain.DEVICE,
            "set_alarm", "set_timer", "set_volume", "set_clipboard", "memory_write",
            // 改全局播放状态：设备单例资源，与 set_volume 同级。
            "media_control",
            // 与 memory_write 同口径：写的是全局人设记忆，不是会话内临时状态。
            "character_memory_write",
        )
        register(
            RootRequirement.NONE, ToolDomain.FS_WRITE,
            "skills_manage", "skills_install_from_github",
        )
        // 子智能体模板的 save/delete 写工作区 agents/ 目录：与文件写同域，
        // 免得两个子代理同时改同一份模板（同名覆盖 → 后写的赢、前一次静默丢失）。
        register(RootRequirement.NONE, ToolDomain.FS_WRITE, "subagent_profile")
        // 专家管理与任务编排：Prefs 的 agent_experts 整表读改写、全局任务表读改写，
        // 与 memory_write 同口径按设备单例串行（并发读改写会丢更新）。
        register(
            RootRequirement.NONE, ToolDomain.DEVICE,
            "expert_manage", "tasks_create", "tasks_update", "tasks_delete", "tasks_run", "tasks_cancel",
        )
        /**
         * `terminal_job` / `terminal_daemon` 显式登记为 [ToolDomain.NONE]（已知取舍，不是漏填）：
         * `terminal_job start` 只是提交任务，命令跑在独立后台进程里，域闸门保护不到真正的命令执行；
         * 把 start/list/read/stop 这些毫秒级动作串进同一个 SHELL 域，只是把它们拉长成跨会话等待，
         * 属于安全剧场。真正的命令串行由 `terminal`（SHELL 域）负责。
         */
        register(
            RootRequirement.PARTIAL, ToolDomain.NONE,
            "network_info", "get_setting", "recent_notifications",
            "search_personal_orders", "terminal_job", "terminal_daemon", "read_file",
            "list_directory", "read_image", "stat_file", "glob_files", "grep_files",
        )
        register(RootRequirement.PARTIAL, ToolDomain.SCREEN, "press_key")
        register(RootRequirement.PARTIAL, ToolDomain.SHELL, "terminal")
        register(RootRequirement.PARTIAL, ToolDomain.FS_WRITE, "write_file", "edit_file")
        register(
            RootRequirement.REQUIRED, ToolDomain.NONE,
            "top_memory_apps", "top_storage_apps", "wifi_credentials", "read_sms_code",
            "get_logcat",
            "list_alarms", "list_active_timers", "get_health_summary", "search_clipboard_history",
            "search_media", "search_audio", "search_recordings", "search_files",
            "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
            "search_downloads", "search_notes", "search_coloros_recordings",
            "search_recording_summaries", "search_system_memories", "search_saved_places",
            "search_qq_chat_images", "search_wechat_chat_images",
        )
        register(RootRequirement.REQUIRED, ToolDomain.SCREEN, "virtual_screen")
        register(
            RootRequirement.REQUIRED, ToolDomain.DEVICE,
            "set_setting", "set_device_state", "app_state_control",
        )
        // 手机目录里的系统开关与日程写操作：root 由目录给出，域在这里声明为设备域。
        declareDomain(
            ToolDomain.DEVICE,
            "set_brightness", "set_sound_mode", "set_do_not_disturb", "set_hotspot",
            "set_screen_timeout", "set_flashlight",
            "create_calendar_event", "create_calendar_events", "update_calendar_event",
            "delete_calendar_event",
            // 便签与闹钟：写的是系统数据库，与日程写同口径 → 设备域，不许并发交叉改。
            "create_note", "delete_note", "delete_alarm", "set_alarm_enabled", "update_alarm_time",
        )
        // 手机目录里的只读查询：不改任何共享状态，显式登记 NONE（不是漏填）。
        declareDomain(
            ToolDomain.NONE,
            "get_device_state", "get_display_state", "get_flashlight", "get_hotspot",
            "get_sound_state", "list_calendars", "read_calendar_event", "read_note",
        )
        // 个人检索（外卖、订单、便签、账单…）：纯只读查询，不碰共享资源 → 显式登记为 NONE。
        // declareDomain 与构造两处都要写：只写构造会让它们不在 declaredDomainNames 里，
        // everyRegisteredToolHasAnExplicitDomainDeclaration 立刻把它们报出来（那条断言因此可失败）。
        declareDomain(ToolDomain.NONE, *PersonalSearchTools.names.toTypedArray())
        PersonalSearchTools.names.forEach {
            put(it, LocalToolRequirement(RootRequirement.REQUIRED, colorOs = true, domain = ToolDomain.NONE))
        }
        AgentPhoneToolCatalog.entries.forEach { entry ->
            // 手机目录是批量登记：域从上面的登记记录里取，避免「新增工具默认落进 NONE」。
            put(entry.name, LocalToolRequirement(entry.root, colorOs = entry.colorOs,
                domain = declaredDomain(entry.name),
                systemAccess = if (entry.name.contains("calendar")) {
                    if (entry.write) ToolSystemAccess.CALENDAR_WRITE else ToolSystemAccess.CALENDAR_READ
                } else ToolSystemAccess.NONE))
        }
        put("search_calendar_events", LocalToolRequirement(RootRequirement.PARTIAL,
            systemAccess = ToolSystemAccess.CALENDAR_READ,
            domain = declaredDomain("search_calendar_events")))
        listOf(
            "observe_screen", "tap", "tap_area", "tap_smart", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "type_text", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "search_personal_orders" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
        listOf(
            "search_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_system_memories", "search_saved_places",
        ).forEach { name -> put(name, getValue(name).copy(colorOs = true)) }
        io.github.mangi.eta.agent.context.VivoPersonalContextQuery.tools.forEach { name ->
            put(name, getValue(name).copy(vivoAlternative = true))
        }
        // 系统记忆优先使用 Hook 桥接，框架失联时仍有独立的 Root 快照来源。
        listOf("search_system_memories", "search_saved_places", "search_personal_orders").forEach { name ->
            put(name, getValue(name).copy(lsposedRequirement = LsposedRequirement.OPTIONAL))
        }
        // 兜底校准：上面每一处 copy(...) 都必须带着域走；这里按登记记录再对一次，
        // 防止后续新增的批量 put 把域悄悄改回 NONE（域一旦丢失，工具就会绕过并发闸门）。
        domainOfRegistered.forEach { (name, domain) ->
            get(name)?.let { put(name, it.copy(domain = domain)) }
        }
    }

    /** 工具的独占资源域；未登记返回 null（调用方据此留痕并退回保守域）。MCP 远端工具按前缀判定。 */
    fun domainOf(name: String): ToolDomain? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(MCP_TOOL_PREFIX)) return ToolDomain.MCP
        return definitions[PersonalSearchTools.canonical(trimmed)]?.domain
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[PersonalSearchTools.canonical(name)]

    fun rootRequirement(name: String): RootRequirement =
        requireNotNull(find(name)) { "Missing tool requirements: $name" }.rootRequirement

    fun requiresAccessibility(name: String): Boolean = find(name)?.accessibility == true

    /**
     * 最近一次 [project] 里被过滤掉的未登记工具名。
     *
     * 只是诊断留痕：漏登记不再表现为「整轮运行/整次压缩直接报 Missing tool requirements」，
     * 而是这一条工具不出现；真正的防线是单测 everyRegisteredToolHasExactlyOneRequirement。
     * 多会话并行时它只反映最后一次投影（共享单例、无会话维度），仅用于排查，不参与任何判断。
     */
    @Volatile
    var lastSkippedTools: Set<String> = emptySet()
        private set

    fun rootDenied(name: String, arguments: JSONObject, rootAvailable: Boolean): Boolean {
        if (rootAvailable) return false
        // 未登记工具按「需要 Root」拒绝执行：宁可回 ROOT_REQUIRED，也不让一次调用把运行期打崩。
        val requirement = find(name) ?: return true
        if (requirement.rootRequirement == RootRequirement.REQUIRED) return true
        return when (name) {
            "press_key" -> arguments.optString("button").equals("PASTE", ignoreCase = true)
            else -> false
        }
    }

    /** 复制后收窄，不能修改下一轮或另一个 run 共用的原始 Schema。 */
    fun project(tools: JSONArray, rootAvailable: Boolean): JSONArray {
        val skipped = linkedSetOf<String>()
        val result = JSONArray()
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
            val name = original.getJSONObject("function").getString("name")
            val requirement = find(name)
            if (requirement == null) {
                // 未登记的工具绝不能进模型目录；但也不能让一次漏登记把整轮运行或整次压缩打断，
                // 所以这里是「跳过 + 留痕」而不是抛异常（2026-11-20 手动压缩即因 expert_manage
                // 漏登记在拼工具阶段整轮失败）。漏登记的真正防线是单测目录比对。
                skipped += name
                continue
            }
            if (!rootAvailable && requirement.rootRequirement == RootRequirement.REQUIRED) continue
            val tool = JSONObject(original.toString())
            if (!rootAvailable) projectUnprivileged(tool.getJSONObject("function"))
            result.put(tool)
        }
        lastSkippedTools = skipped
        return result
    }

    private fun projectUnprivileged(function: JSONObject) {
        val properties = function.getJSONObject("parameters").optJSONObject("properties")
        if (function.getString("name") in AgentFileToolCatalog.names + AgentTerminalToolCatalog.names) {
            properties?.optJSONObject("identity")?.put("enum", JSONArray().put("user"))
                ?.put("description", "显式选择宿主身份；当前可用 user（Eta App UID）。省略时沿用本次运行的默认身份。")
        }
        when (function.getString("name")) {
            "read_image" -> properties?.getJSONObject("path")?.put("description",
                "当前应用有权读取的绝对图片路径、file URI 或已授权的 content URI。")
            "press_key" -> properties?.getJSONObject("button")?.let { button ->
                val values = button.getJSONArray("enum")
                button.put("enum", JSONArray().also { allowed ->
                    for (i in 0 until values.length()) {
                        val value = values.getString(i)
                        if (!value.equals("PASTE", ignoreCase = true)) allowed.put(value)
                    }
                })
                button.put("description", "无障碍支持的系统按键；输入文本请使用 type_text。")
            }
            "search_personal_orders" -> function.put("description",
                "从用户已授权保存的通知历史检索外卖、购物、快递、票券和出行订单。")
        }
    }
}
