package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.data.model.ReasoningEffort

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 系统助手等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        /** 借用小布自身模型：只读旁路小布下行的 StreamTextCard，不改小布行为。 */
        const val AGENT_BORROW_BREENO = "agent_borrow_breeno"
        // 旧版小 V Hook 仍会读取这些 key；与厂商助手公共开关同步，不再单独展示。
        const val VIVO_CUSTOM_MODEL = "vivo_custom_model"
        const val VIVO_REQUIRE_PREFIX = "vivo_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_DEFAULT_REASONING_EFFORT = "agent_default_reasoning_effort"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        /**
         * 自动压缩上下文阈值：上下文窗口占用百分比，0 表示关闭。
         *
         * 取代原来的纯布尔开关：旧键仍保留作为迁移来源与旧 Hook 的兼容读点，
         * 但新 UI 只写这一项，读一律走 [Prefs.autoCompactionTriggerPercent]。
         */
        const val AGENT_AUTO_COMPACTION_TRIGGER_PERCENT = "agent_auto_compaction_trigger_percent"
        /** 阈值可选档位；0 = 关闭（只能手动压缩）。 */
        val AUTO_COMPACTION_TRIGGER_PERCENT_CHOICES: List<Int> = listOf(0, 20, 30, 40, 50, 80, 90)
        /** 默认阈值：模型窗口用掉 80% 时触发自动压缩。 */
        const val AUTO_COMPACTION_TRIGGER_PERCENT_DEFAULT = 80
        /** 上限：留 10% 给摘要请求本身与下一轮回复，避免刚压缩完又立刻超限。 */
        const val AUTO_COMPACTION_TRIGGER_PERCENT_MAX = 90
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        /**
         * 资源域闸门（AgentResourceGate）总开关。
         *
         * 默认 `true`（走按域排队）；写成 `false` 退回旧的全局锁 AgentToolGate，作为线上回滚手段——
         * 不需要重出包。读取点只有 [io.github.mangi.eta.agent.model.AgentResourceGate.refreshEnabledFromPrefs]，
         * 读失败一律按 `true`。本轮不提供设置页开关，手动切法见该类的 KDoc。
         */
        const val AGENT_RESOURCE_GATE_ENABLED = "agent_resource_gate_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_BORROW_BREENO to false,
            VIVO_CUSTOM_MODEL to true,
            VIVO_REQUIRE_PREFIX to true,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_SUBAGENT_ENABLED to true,
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPACTION_ENABLED,
            AGENT_SUBAGENT_ENABLED,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    /**
     * 自动压缩阈值（上下文窗口占用百分比，0 = 关闭）。
     *
     * 迁移规则：新键存在就直接用，并把任意历史值吸附到当前合法档位
     * （旧档位 60/70/85/95 收敛到 20/30/40/50/80/90，不会因为档位改版就静默关闭压缩）；
     * 只有旧布尔开关的存量用户按 true → 80%、false → 0 折算。结果夹到 0..[Keys.AUTO_COMPACTION_TRIGGER_PERCENT_MAX]。
     */
    fun autoCompactionTriggerPercent(
        preferences: SharedPreferences? = localAgent ?: remote,
    ): Int {
        val stored = runCatching {
            preferences?.getInt(Keys.AGENT_AUTO_COMPACTION_TRIGGER_PERCENT, -1)
        }.getOrNull()
        if (stored != null && stored >= 0) {
            return normalizeAutoCompactionPercent(stored)
        }
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.AGENT_AUTO_COMPACTION_ENABLED)
        val legacyEnabled = runCatching {
            preferences?.getBoolean(Keys.AGENT_AUTO_COMPACTION_ENABLED, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return if (legacyEnabled) Keys.AUTO_COMPACTION_TRIGGER_PERCENT_DEFAULT else 0
    }

    /**
     * 把任意百分比吸附到最近的合法档位。
     *
     * 档位只允许 [Keys.AUTO_COMPACTION_TRIGGER_PERCENT_CHOICES] 里的值，0 永远表示关闭；
     * 旧版本写过的 60/70/85/95 与手改值都按最近档位收敛，UI 下拉因此永远能命中一项。
     */
    fun normalizeAutoCompactionPercent(percent: Int): Int {
        if (percent <= 0) return 0
        return Keys.AUTO_COMPACTION_TRIGGER_PERCENT_CHOICES
            .filter { it > 0 }
            .minByOrNull { kotlin.math.abs(it - percent) }
            ?: Keys.AUTO_COMPACTION_TRIGGER_PERCENT_DEFAULT
    }

    /**
     * 写入自动压缩阈值，并同步旧布尔键（0 = 关闭）。
     *
     * 同步旧键的原因：仍在运行的旧 Hook 进程与旧版入口只认布尔键，不回写会让两边口径打架。
     */
    fun putAutoCompactionTriggerPercent(preferences: SharedPreferences, percent: Int): Boolean =
        runCatching {
            val clamped = normalizeAutoCompactionPercent(percent)
            preferences.edit()
                .putInt(Keys.AGENT_AUTO_COMPACTION_TRIGGER_PERCENT, clamped)
                .putBoolean(Keys.AGENT_AUTO_COMPACTION_ENABLED, clamped > 0)
                .commit()
        }.getOrDefault(false)

    fun defaultReasoningEffort(
        preferences: SharedPreferences? = localAgent ?: remote,
    ): ReasoningEffort {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DEFAULT_REASONING_EFFORT, null)
        }.getOrNull()
        val parsed = ReasoningEffort.fromWireValue(raw)
        return if (parsed != null && parsed != ReasoningEffort.OFF) parsed else ReasoningEffort.DEFAULT
    }

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** 合并旧版小 V 设置，并让仍在运行的旧 Hook 同步读取公共开关。 */
    fun reconcileVendorAssistantPreferences(service: XposedService?) {
        val preferences = remotePreferencesForUi(service) ?: return
        runCatching {
            val editor = preferences.edit()
            var changed = false
            vendorAssistantAliases.forEach { (key, legacyKey) ->
                val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
                val value = if (!preferences.contains(key) && preferences.contains(legacyKey)) {
                    preferences.getBoolean(legacyKey, default)
                } else {
                    preferences.getBoolean(key, default)
                }
                listOf(key, legacyKey).forEach { target ->
                    if (!preferences.contains(target) || preferences.getBoolean(target, default) != value) {
                        editor.putBoolean(target, value)
                        changed = true
                    }
                }
            }
            if (changed) editor.commit()
        }
    }

    /** 公共开关与旧版小 V 别名一次提交，兼容尚未重启的助手进程。 */
    fun putBooleanForUi(preferences: SharedPreferences, key: String, value: Boolean): Boolean =
        runCatching {
            val editor = preferences.edit().putBoolean(key, value)
            vendorAssistantAliases[key]?.let { editor.putBoolean(it, value) }
            editor.commit()
        }.getOrDefault(false)

    private val vendorAssistantAliases = mapOf(
        Keys.AGENT_CUSTOM_MODEL to Keys.VIVO_CUSTOM_MODEL,
        Keys.AGENT_REQUIRE_PREFIX to Keys.VIVO_REQUIRE_PREFIX,
    )

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }
}
