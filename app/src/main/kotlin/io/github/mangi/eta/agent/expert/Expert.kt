package io.github.mangi.eta.agent.expert

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 专家分类。与 miniAI 的 `ExpertCategory` 枚举对齐：
 * 写作 / 数据 / 开发 / 设计 / 商务 / 办公 / 生活 / 情感 / 其他。
 */
enum class ExpertCategory(val key: String) {
    WRITING("writing"),
    DATA("data"),
    DEV("dev"),
    DESIGN("design"),
    BUSINESS("business"),
    OFFICE("office"),
    LIFE("life"),
    EMOTION("emotion"),
    OTHER("other");

    companion object {
        fun fromKey(key: String?): ExpertCategory =
            entries.firstOrNull { it.key == key } ?: OTHER

        fun fromLabel(label: String?): ExpertCategory {
            val text = label?.trim().orEmpty()
            if (text.isEmpty()) return OTHER
            entries.firstOrNull { it.key.equals(text, ignoreCase = true) }?.let { return it }
            return when (text) {
                "写作", "写作与内容", "内容", "文案" -> WRITING
                "数据", "数据与分析", "分析" -> DATA
                "开发", "研发", "编程", "工程" -> DEV
                "设计", "UI", "视觉" -> DESIGN
                "商务", "商业", "业务" -> BUSINESS
                "办公", "文档", "效率" -> OFFICE
                "生活", "日常" -> LIFE
                "情感", "心理", "关系" -> EMOTION
                else -> OTHER
            }
        }
    }
}

/** 专家定义来源：内置（随应用发布）或用户自建。 */
enum class ExpertSource(val key: String) {
    BUILTIN("builtin"),
    USER("user");

    companion object {
        fun fromKey(key: String?): ExpertSource =
            entries.firstOrNull { it.key == key } ?: USER
    }
}

/**
 * 一个可召唤的「专家」：命名的人设 + 方法论 + 可选的模型/工具/技能/注入配置。
 *
 * 对齐 miniAI 的专家定义（`experts/<name>.md` 的 YAML 头）：
 * 分类、模型覆盖、工具集、绑定技能、注入片段、提示词正文都在这里。
 * Eta 不用文件系统存专家（跨进程读 Prefs 更稳），字段语义保持一致。
 */
@Immutable
data class Expert(
    /** 唯一标识，也是保存键。 */
    val name: String,
    val displayName: String = "",
    /** 摘要：一句话说清能力与适用场景，列表与详情页都用它。 */
    val description: String = "",
    val category: ExpertCategory = ExpertCategory.OTHER,
    /** 召唤时自动加载的技能 id 列表（对应已安装技能的 id）。 */
    val skills: List<String> = emptyList(),
    /** 专家提示词正文：人设 + 方法论 + 输出格式。 */
    val prompt: String = "",
    val enabled: Boolean = true,
    /** 置顶：排在召唤列表前部。 */
    val pinned: Boolean = false,
    val source: ExpertSource = ExpertSource.USER,
    /** 模型覆盖：providerId 为空表示继承会话当前模型。 */
    val providerId: String = "",
    val modelId: String = "",
    /**
     * 工具集白名单；空表示继承全量工具。
     *
     * 只写 `*` 也视为继承（与 miniAI 的 `tools: [*]` 一致）。
     */
    val tools: List<String> = emptyList(),
    /** 工具黑名单：优先于白名单，用于在继承全量时排除个别高危工具。 */
    val disallowedTools: List<String> = emptyList(),
    /** 注入片段：mainRules / projectRules / skills / memory 等系统上下文段。 */
    val inject: List<String> = emptyList(),
) {
    val title: String get() = displayName.ifBlank { name }

    val promptCharCount: Int get() = prompt.length
    /** 是否显式限定了工具集（空或只含 `*` 都算继承全量）。 */
    val restrictsTools: Boolean
        get() = tools.any { it.isNotBlank() && it != "*" }

    val inheritsModel: Boolean get() = providerId.isBlank() || modelId.isBlank()

    fun toolSummary(): String = when {
        restrictsTools -> tools.filter { it != "*" }.joinToString("、")
        else -> "继承全量工具" + if (disallowedTools.isNotEmpty()) "（不含 ${disallowedTools.joinToString("、")}）" else ""
    }

    fun modelSummary(): String = if (inheritsModel) "继承父会话" else "$providerId / $modelId"

    /**
     * 某个工具名是否允许该专家使用。
     *
     * 黑名单优先；白名单为空或只含 `*` 表示继承全量。
     * 声明侧（工具目录裁剪）与执行侧（拒绝调用）都调它，避免两套口径。
     */
    fun toolAllowed(toolName: String): Boolean {
        if (toolName.isBlank()) return false
        if (toolName in disallowedTools) return false
        if (!restrictsTools) return true
        return toolName in tools
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("displayName", displayName)
        put("description", description)
        put("category", category.key)
        put("skills", JSONArray(skills))
        put("prompt", prompt)
        put("enabled", enabled)
        put("pinned", pinned)
        put("source", source.key)
        put("providerId", providerId)
        put("modelId", modelId)
        put("tools", JSONArray(tools))
        put("disallowedTools", JSONArray(disallowedTools))
        put("inject", JSONArray(inject))
    }

    companion object {
        /**
         * 专家提示词长度上限，唯一事实源。
         *
         * 工具写入侧（`expert_manage`）与编辑面板必须共用这个值：早先只在工具侧截断，
         * 用户在 UI 里能存超长提示词，再经工具写一次会被静默截断，两次结果不一致。
         */
        const val MAX_PROMPT_CHARS = 8_000

        /** 从 JSON 还原；缺字段一律回退默认值，保证旧版本保存的专家仍能读。 */
        fun fromJson(json: JSONObject): Expert = Expert(
            name = json.optString("name"),
            displayName = json.optString("displayName"),
            description = json.optString("description"),
            category = ExpertCategory.fromKey(json.optString("category")),
            skills = json.stringList("skills"),
            prompt = json.optString("prompt"),
            enabled = json.optBoolean("enabled", true),
            pinned = json.optBoolean("pinned", false),
            source = ExpertSource.fromKey(json.optString("source", ExpertSource.USER.key)),
            providerId = json.optString("providerId"),
            modelId = json.optString("modelId"),
            tools = json.stringList("tools"),
            disallowedTools = json.stringList("disallowedTools"),
            inject = json.stringList("inject"),
        )

        private fun JSONObject.stringList(key: String): List<String> =
            optJSONArray(key)?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf(String::isNotBlank)
                }
            } ?: emptyList()
    }
}

/**
 * 当前召唤状态：哪个专家生效、是否启用。
 *
 * 与 miniAI 的 `ExpertEntry`（expert + scope + pinned + disabled）对应，
 * Eta 简化为「全局一个当前专家」——作用域（全局/项目）由 Eta 的项目上下文天然区分。
 */
@Immutable
data class ExpertSelection(
    val expertName: String? = null,
    val enabled: Boolean = false,
) {
    val active: Boolean get() = enabled && expertName != null
}

/**
 * 专家的持久化与「当前召唤专家」状态。
 *
 * 与 [io.github.mangi.eta.agent.prompt.PromptJailbreakStore] 同款 Prefs 方案：
 * 专家列表存 JSON 数组；当前专家单独存 id + 开关，键缺失回退「未召唤」。
 */
object ExpertStore {
    private const val KEY_EXPERTS = "agent_experts"
    private const val KEY_ACTIVE_NAME = "agent_active_expert_name"
    private const val KEY_ACTIVE_ENABLED = "agent_active_expert_enabled"

    @Volatile
    private var cachedExperts: List<Expert> = emptyList()

    @Volatile
    private var cachedSelection: ExpertSelection = ExpertSelection()

    private fun preferences() = runCatching { Prefs.localAgentPreferences() }
        .onFailure { failure ->
            AndroidAgentLogger.warnThrottled("expert_prefs_unavailable") {
                "Expert prefs unavailable: type=${failure.safeLogType()}"
            }
        }
        .getOrNull()

    /** 置顶的排前面，其余按名字稳定排序，召唤列表与专家中心共用这个顺序。 */
    fun loadExperts(): List<Expert> {
        val prefs = preferences() ?: return cachedExperts
        val raw = runCatching { prefs.getString(KEY_EXPERTS, null) }
            .onFailure { failure ->
                AndroidAgentLogger.warnThrottled("expert_read_failed") {
                    "Expert read failed: type=${failure.safeLogType()}"
                }
            }
            .getOrNull()
        val parsed = raw?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { index ->
                    runCatching { Expert.fromJson(array.getJSONObject(index)) }
                        .onFailure { failure ->
                            // 单条损坏只跳过这一条，但必须留痕：否则用户看到「专家少了一个」
                            // 却没有任何线索指向哪一条、为什么。
                            AndroidAgentLogger.warnThrottled("expert_parse_failed") {
                                "Expert parse failed idx=$index: type=${failure.safeLogType()}"
                            }
                        }
                        .getOrNull()
                }
            }.onFailure { failure ->
                AndroidAgentLogger.warnThrottled("expert_json_invalid") {
                    "Expert JSON invalid: type=${failure.safeLogType()} chars=${text.length}"
                }
            }.getOrNull()
        } ?: emptyList()
        val sorted = parsed.sortedWith(compareByDescending<Expert> { it.pinned }.thenBy { it.title })
        cachedExperts = sorted
        return sorted
    }

    /**
     * 写入专家表。
     *
     * 返回 false 时调用方必须当失败处理：早先这里只返回布尔、调用方不看，
     * 界面照样刷新成「已保存」，而磁盘上什么都没变。
     */
    fun saveExperts(experts: List<Expert>): Boolean {
        val prefs = preferences() ?: return false
        val array = JSONArray()
        experts.forEach { array.put(it.toJson()) }
        val written = runCatching {
            prefs.edit().putString(KEY_EXPERTS, array.toString()).commit()
        }.onFailure { failure ->
            AndroidAgentLogger.warnThrottled("expert_save_failed") {
                "Expert save failed: type=${failure.safeLogType()}"
            }
        }.getOrDefault(false)
        if (written) {
            cachedExperts = experts
        } else {
            AndroidAgentLogger.warnThrottled("expert_save_rejected") {
                "Expert save rejected: count=${experts.size}"
            }
        }
        return written
    }

    fun find(name: String): Expert? = loadExperts().firstOrNull { it.name == name }

    fun upsert(expert: Expert): List<Expert> {
        val current = loadExperts()
        val index = current.indexOfFirst { it.name == expert.name }
        // 编辑已有专家要留在原位：追加到末尾会让它在「全部专家」里突然跳到最后一个。
        val next = if (index >= 0) {
            current.toMutableList().apply { set(index, expert) }
        } else {
            current + expert
        }
        saveExperts(next)
        return next
    }

    fun delete(name: String): List<Expert> {
        val current = loadExperts()
        val next = current.filterNot { it.name == name }
        saveExperts(next)
        if (loadSelection().expertName == name) {
            dismiss()
        }
        return next
    }

    fun loadSelection(): ExpertSelection {
        val prefs = preferences() ?: return cachedSelection
        val name = runCatching { prefs.getString(KEY_ACTIVE_NAME, null) }
            .onFailure { failure ->
                AndroidAgentLogger.warnThrottled("expert_selection_read_failed") {
                    "Expert selection read failed: type=${failure.safeLogType()}"
                }
            }
            .getOrNull()
        val enabled = runCatching { prefs.getBoolean(KEY_ACTIVE_ENABLED, false) }
            .onFailure { failure ->
                AndroidAgentLogger.warnThrottled("expert_selection_read_failed") {
                    "Expert selection flag read failed: type=${failure.safeLogType()}"
                }
            }
            .getOrDefault(false)
        val value = ExpertSelection(expertName = name, enabled = enabled)
        cachedSelection = value
        return value
    }

    fun setSelection(name: String?, enabled: Boolean): Boolean {
        val prefs = preferences() ?: return false
        val written = runCatching {
            prefs.edit()
                .putString(KEY_ACTIVE_NAME, name)
                .putBoolean(KEY_ACTIVE_ENABLED, enabled)
                .commit()
        }.onFailure { failure ->
            AndroidAgentLogger.warnThrottled("expert_selection_save_failed") {
                "Expert selection save failed: type=${failure.safeLogType()}"
            }
        }.getOrDefault(false)
        if (written) {
            cachedSelection = ExpertSelection(expertName = name, enabled = enabled)
        } else {
            AndroidAgentLogger.warnThrottled("expert_selection_save_rejected") {
                "Expert selection save rejected: name=${name ?: "null"} enabled=$enabled"
            }
        }
        return written
    }

    fun setEnabled(enabled: Boolean): Boolean =
        setSelection(loadSelection().expertName, enabled)

    fun summon(name: String): Boolean = setSelection(name, true)

    fun dismiss(): Boolean = setSelection(null, false)

    /** 当前生效的专家；未召唤或未启用时为 null。 */
    fun active(): Expert? {
        val selection = loadSelection()
        if (!selection.active) return null
        val name = selection.expertName ?: return null
        return loadExperts().firstOrNull { it.name == name && it.enabled }
    }

    /** 供预览与测试：不读磁盘，交回最近一次内存值。 */
    fun cachedExperts(): List<Expert> = cachedExperts
    fun cachedSelection(): ExpertSelection = cachedSelection
}