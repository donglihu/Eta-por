package io.github.mangi.eta.agent.runtime

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** Runtime 自己裁决可选能力，不能把入口进程提交的布尔值当作授权。 */
internal object AgentRuntimePolicy {
    data class Permissions(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val deviceDirectTools: Boolean = false,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val autoCompaction: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
        /** 运行时允许的自动压缩阈值（0 = 未授权/不允许）；与布尔开关一起构成授权。 */
        val autoCompactionTriggerPercent: Int = 0,
        val subagentTools: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
    )

    fun permissions(preferences: SharedPreferences?): Permissions =
        Permissions(
            terminalTools = preferences.allowed(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = preferences.allowed(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = preferences.allowed(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                preferences.allowed(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                preferences.allowed(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            autoCompaction = preferences.allowed(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            autoCompactionTriggerPercent = preferences.allowedAutoCompactionPercent(),
            subagentTools = preferences.allowed(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
        )

    fun constrain(
        config: AgentModelClient.ModelConfig,
        permissions: Permissions,
    ): AgentModelClient.ModelConfig {
        // 思考档位不再由 AGENT_THINKING_ENABLED 硬门控：对话内可随时改档，开关只决定默认档位。
        val effectiveEffort = config.effectiveReasoningEffort
        val thinkingEnabled = effectiveEffort.enablesReasoning
        val constrained = config.copy(
            terminalTools = config.terminalTools && permissions.terminalTools,
            browserTools = config.browserTools && permissions.browserTools,
            deviceDirectTools = config.deviceDirectTools && permissions.deviceDirectTools,
            deviceSensitiveReadTools =
                config.deviceSensitiveReadTools && permissions.deviceSensitiveReadTools,
            deviceSensitiveActionTools =
                config.deviceSensitiveActionTools && permissions.deviceSensitiveActionTools,
            thinkingEnabled = thinkingEnabled,
            reasoningEffort = effectiveEffort,
            autoCompactionEnabled = config.autoCompactionEnabled && permissions.autoCompaction,
            autoCompactionTriggerPercent = if (config.autoCompactionEnabled && permissions.autoCompaction) {
                // 入口进程提交的阈值不能越过运行时授权：两边都给了正数就取小，只有一边给就听那一边。
                val authorized = permissions.autoCompactionTriggerPercent
                if (config.autoCompactionTriggerPercent > 0 && authorized > 0) {
                    minOf(config.autoCompactionTriggerPercent, authorized)
                } else {
                    config.autoCompactionTriggerPercent
                }
            } else 0,
            subagentTools = config.subagentTools && permissions.subagentTools,
        )
        if (thinkingEnabled) return constrained
        return constrained.copy(
            extraBodyJson = stripThinkingOverrides(constrained.extraBodyJson),
            customBody = constrained.customBody.mapNotNull { body ->
                if (body.key.isThinkingKey()) return@mapNotNull null
                body.copy(value = body.value.stripThinkingOverrides())
            },
        )
    }

    private fun SharedPreferences?.allowed(key: String): Boolean {
        if (this == null) return false
        val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: false
        return runCatching { getBoolean(key, default) }.getOrDefault(false)
    }

    /**
     * 读取运行时可授权的自动压缩阈值（0 = 不允许）。
     *
     * 读失败一律按 0 处理（fail closed）：远程 preferences 不可用时不能默认替用户打开自动压缩。
     * 没有新阈值键的存量配置按旧布尔开关折算，避免升级后静默改变行为；
     * 读到旧档位（60/70/85/95）时吸附到当前合法档位，UI 与运行时口径保持一致。
     */
    private fun SharedPreferences?.allowedAutoCompactionPercent(): Int {
        if (this == null) return 0
        return runCatching {
            if (contains(Prefs.Keys.AGENT_AUTO_COMPACTION_TRIGGER_PERCENT)) {
                getInt(Prefs.Keys.AGENT_AUTO_COMPACTION_TRIGGER_PERCENT, 0)
            } else if (getBoolean(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED, false)) {
                Prefs.Keys.AUTO_COMPACTION_TRIGGER_PERCENT_DEFAULT
            } else 0
        }.getOrDefault(0).let(Prefs::normalizeAutoCompactionPercent)
    }

    private fun stripThinkingOverrides(raw: String): String {
        if (raw.isBlank()) return raw
        return runCatching {
            JSONObject(raw).also { it.stripThinkingOverrides() }.toString()
        }.getOrDefault(raw)
    }

    private fun JSONObject.stripThinkingOverrides() {
        val keys = keys().asSequence().toList()
        keys.forEach { key ->
            if (key.isThinkingKey()) {
                remove(key)
                return@forEach
            }
            when (val child = opt(key)) {
                is JSONObject -> child.stripThinkingOverrides()
                is org.json.JSONArray -> {
                    for (index in 0 until child.length()) {
                        (child.opt(index) as? JSONObject)?.stripThinkingOverrides()
                    }
                }
            }
        }
    }

    private fun JsonElement.stripThinkingOverrides(): JsonElement =
        when (this) {
            is JsonObject -> JsonObject(
                entries
                    .filterNot { (key, _) -> key.isThinkingKey() }
                    .associate { (key, value) -> key to value.stripThinkingOverrides() }
            )
            is JsonArray -> JsonArray(map { it.stripThinkingOverrides() })
            else -> this
        }

    private fun String.isThinkingKey(): Boolean =
        lowercase().replace('-', '_') in THINKING_OVERRIDE_KEYS

    private val THINKING_OVERRIDE_KEYS = setOf(
        "thinking",
        "thinking_budget",
        "thinking_level",
        "thinking_summaries",
        "reasoning",
        "reasoning_effort",
        "reasoning_budget",
        "reasoning_max_tokens",
        "enable_thinking",
    )
}
