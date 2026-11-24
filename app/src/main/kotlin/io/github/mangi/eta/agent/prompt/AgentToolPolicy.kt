package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** 工具开放范围：全部开放，或只开放白名单里的工具。 */
internal enum class ToolPolicyMode {
    ALLOW_ALL,
    ALLOW_LIST,
}

/**
 * 工具白名单与能力开关的正式配置。
 *
 * 与"界面隐藏"不同，这里同时作用于两处：请求里的 tools 数组按白名单过滤，
 * 执行侧再用 [allows] 兜底拒绝，避免模型从历史或复述里拼出未授权工具调用。
 */
internal data class AgentToolPolicy(
    val mode: ToolPolicyMode = ToolPolicyMode.ALLOW_ALL,
    val allowedTools: Set<String> = emptySet(),
    val version: Int = 0,
) {
    fun allows(toolName: String): Boolean =
        mode == ToolPolicyMode.ALLOW_ALL || toolName in allowedTools || toolName in ALWAYS_ALLOWED

    /**
     * 白名单模式下被「无条件放行」的工具。
     *
     * 这些工具是**编排类**的：它们自己不碰设备、不改文件，只是决定「本轮允许模型做什么」。
     * 提示词里已经按开关声明了它们（见 [io.github.mangi.eta.agent.model.AgentPromptBuilder]
     * 的 subagent 段），如果白名单把它们滤掉，就会出现「提示词教模型用 subagent，
     * 请求里却没有这个工具」的错位——模型一调就是 TOOL_NOT_AVAILABLE，
     * 而用户在设置页的白名单清单里根本找不到它可勾选（2026-11-20 实测）。
     *
     * 真正的边界仍然在执行侧：[io.github.mangi.eta.agent.tool.AgentLocalTools] 的
     * 审批闸门与子代理自身的工具白名单照常生效，这里只是不让编排工具在目录层消失。
     */
    private val ALWAYS_ALLOWED: Set<String> = setOf(
        io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog.TOOL_NAME,
        io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog.PROFILE_TOOL_NAME,
        // 专家管理同样属于编排类：只读写应用内 Prefs（ExpertStore），不碰设备、不改文件，
        // 白名单模式下若被滤掉，就会出现「提示词教模型用 expert_manage，请求里却没有」的错位，
        // 用户说「帮我建个 XX 专家」时模型一调就是 TOOL_NOT_AVAILABLE（2026-11-20 实测）。
        io.github.mangi.eta.agent.model.AgentExpertToolCatalog.TOOL_NAME,
        // ask_user 是模型唯一能主动追问用户并拿到选项答案的入口，属于编排/交互类：
        // 不改设备、不改文件、只暂停本轮等用户选择（风险决策由系统审批卡拦截）。
        // 白名单模式下若被滤掉，模型遇到需要用户拍板的场景就只剩编答案一条路。
        // 与 expert_manage 同样的错位问题：设置页白名单列表里根本没有它可勾选，
        // 必须放入无条件放行集合，避免「提示词教模型问，请求里却没有 ask_user」。
        "ask_user",
    )

    fun filterTools(tools: JSONArray): JSONArray {
        if (mode == ToolPolicyMode.ALLOW_ALL) return tools
        val filtered = JSONArray()
        for (index in 0 until tools.length()) {
            val schema = tools.optJSONObject(index) ?: continue
            val name = schema.optJSONObject("function")?.optString("name").orEmpty()
            if (name.isNotEmpty() && (allows(name) || name in ALWAYS_ALLOWED)) filtered.put(schema)
        }
        return filtered
    }

    fun withMode(mode: ToolPolicyMode): AgentToolPolicy = copy(mode = mode, version = version + 1)

    fun withAllowed(allowedTools: Set<String>): AgentToolPolicy =
        copy(allowedTools = allowedTools, version = version + 1)

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("allowed", JSONArray().also { array -> allowedTools.sorted().forEach(array::put) })
        .put("version", version)

    companion object {
        fun decode(raw: String?): AgentToolPolicy =
            if (raw.isNullOrBlank()) {
                AgentToolPolicy()
            } else {
                runCatching { fromJson(JSONObject(raw)) }.getOrElse { AgentToolPolicy() }
            }

        fun fromJson(json: JSONObject): AgentToolPolicy {
            val allowed = json.optJSONArray("allowed")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
                    .toSet()
            }.orEmpty()
            return AgentToolPolicy(
                mode = runCatching { ToolPolicyMode.valueOf(json.optString("mode")) }
                    .getOrDefault(ToolPolicyMode.ALLOW_ALL),
                allowedTools = allowed,
                version = json.optInt("version", 0),
            )
        }
    }
}

internal object AgentToolPolicyStore {
    private const val KEY = "agent_tool_policy_json"

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedValue: AgentToolPolicy = AgentToolPolicy()

    fun load(): AgentToolPolicy {
        val preferences = Prefs.localAgentPreferences() ?: return AgentToolPolicy()
        val raw = runCatching { preferences.getString(KEY, null) }.getOrNull()
        if (raw == cachedRaw) return cachedValue
        val value = AgentToolPolicy.decode(raw)
        cachedRaw = raw
        cachedValue = value
        return value
    }

    fun save(value: AgentToolPolicy): Boolean {
        val preferences = Prefs.localAgentPreferences() ?: return false
        val raw = value.toJson().toString()
        val written = runCatching { preferences.edit().putString(KEY, raw).commit() }.getOrDefault(false)
        if (written) {
            cachedRaw = raw
            cachedValue = value
        }
        return written
    }
}
