package io.github.mangi.eta.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.ConversationTitleSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.WebToolsSettings
import kotlinx.serialization.json.Json
import io.github.mangi.eta.data.model.Settings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal object SettingsDataStore {
    private val SPEECH_SETTINGS = stringPreferencesKey("speech_settings_v1")
    private val CONVERSATION_TITLE_SETTINGS = stringPreferencesKey("conversation_title_settings_v1")
    private val WEB_TOOLS_SETTINGS = stringPreferencesKey("web_tools_settings_v1")
    private val SUBAGENT_SETTINGS = stringPreferencesKey("subagent_settings_v1")
    private val speechJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun speechSettingsFlow(): Flow<SpeechSettings> {
        ensureInitialized()
        return dataStore.data.map { prefs ->
            prefs[SPEECH_SETTINGS]?.let { speechJson.decodeFromString<SpeechSettings>(it) }
                ?: SpeechSettings()
        }
    }

    suspend fun speechSettings() = speechSettingsFlow().first()

    suspend fun setSpeechSettings(settings: SpeechSettings) {
        ensureInitialized()
        dataStore.edit { it[SPEECH_SETTINGS] = speechJson.encodeToString(settings) }
    }

    fun webToolsSettingsFlow(): Flow<WebToolsSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { prefs ->
                prefs[WEB_TOOLS_SETTINGS]?.let { raw ->
                    runCatching { speechJson.decodeFromString<WebToolsSettings>(raw) }.getOrNull()
                } ?: WebToolsSettings()
            }
    }

    suspend fun webToolsSettings(): WebToolsSettings = webToolsSettingsFlow().first()

    suspend fun setWebToolsSettings(settings: WebToolsSettings) {
        ensureInitialized()
        dataStore.edit { it[WEB_TOOLS_SETTINGS] = speechJson.encodeToString(settings) }
    }

    fun subagentSettingsFlow(): Flow<SubagentSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { prefs ->
                prefs[SUBAGENT_SETTINGS]?.let { raw ->
                    runCatching { speechJson.decodeFromString<SubagentSettings>(raw) }.getOrNull()
                } ?: SubagentSettings()
            }
    }

    suspend fun subagentSettings(): SubagentSettings = subagentSettingsFlow().first()

    suspend fun setSubagentSettings(settings: SubagentSettings) {
        ensureInitialized()
        dataStore.edit { it[SUBAGENT_SETTINGS] = speechJson.encodeToString(settings) }
    }

    private const val STORE_NAME = "eta_settings"

    private val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
    private val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
    private val OFFICIAL_MODEL_CATALOG_REVISION = intPreferencesKey("official_model_catalog_revision")
    private val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
    private val AUTO_MEMORY_ENABLED = booleanPreferencesKey("auto_memory_enabled")
    private val MEMORY_AUTO_COMPACT_ENABLED = booleanPreferencesKey("memory_auto_compact_enabled")
    private val MEMORY_CORE_BUDGET_CHARS = intPreferencesKey("memory_core_budget_chars")
    private val MEMORY_HEADING_BUDGET_CHARS = intPreferencesKey("memory_heading_budget_chars")
    private val AUTO_SKILLS_ENABLED = booleanPreferencesKey("auto_skills_enabled")
    private val VIRTUAL_SCREEN_ENABLED = booleanPreferencesKey("virtual_screen_enabled")
    private val VIRTUAL_SCREEN_OFF_ENABLED = booleanPreferencesKey("virtual_screen_off_enabled")
    private val VIRTUAL_SCREEN_FALLBACK_ENABLED = booleanPreferencesKey("virtual_screen_fallback_enabled")
    private val VIRTUAL_SCREEN_AUTO_RESTART_APPS = booleanPreferencesKey("virtual_screen_auto_restart_apps")
    private val VIRTUAL_SCREEN_IDLE_TIMEOUT = intPreferencesKey("virtual_screen_idle_timeout_minutes")
    private val VIRTUAL_SCREEN_FAVORITE_APPS = stringSetPreferencesKey("virtual_screen_favorite_apps")
    private val VIRTUAL_SCREEN_FLOAT_WINDOW = booleanPreferencesKey("virtual_screen_float_window")
    private val VIRTUAL_SCREEN_FLOAT_EDGE_COLLAPSE = booleanPreferencesKey("virtual_screen_float_edge_collapse")
    private val VIRTUAL_SCREEN_FLOAT_WIDTH = intPreferencesKey("virtual_screen_float_width_dp")
    private val VIRTUAL_SCREEN_FLOAT_HEIGHT = intPreferencesKey("virtual_screen_float_height_dp")
    private val VIRTUAL_SCREEN_FLOAT_ALPHA = floatPreferencesKey("virtual_screen_float_alpha")
    private val VIRTUAL_SCREEN_FLOAT_POS_X = intPreferencesKey("virtual_screen_float_pos_x")
    private val VIRTUAL_SCREEN_FLOAT_POS_Y = intPreferencesKey("virtual_screen_float_pos_y")
    private val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    /** 可选后台保活开关：默认关闭，只有用户主动打开才常驻前台服务。 */
    private val KEEPALIVE_ENABLED = booleanPreferencesKey("keepalive_enabled")
    private val OVERLAY_FLUID_PINNED = booleanPreferencesKey("overlay_fluid_pinned")
    /** 流体云降级：通知不再申请持续提升，退化为普通通知。 */
    private val OVERLAY_FLUID_DOWNGRADE = booleanPreferencesKey("overlay_fluid_downgrade")
    /** 新手引导进度：JSON（包 id → 已看版本）；JSON 存是因为要 id→版本的映射，stringSet 存不下值。 */
    private val SEEN_TOUR_PACKS = stringPreferencesKey("seen_tour_packs_v1")
    private val ONBOARDING_ENABLED = booleanPreferencesKey("onboarding_enabled")
    private val RECENT_OPENED_IDS = stringPreferencesKey("recent_opened_conversation_ids")
    private val AUTO_BACKUP_INTERVAL = intPreferencesKey("auto_backup_interval_hours")
    /** 周期（分钟）：与老的小时键并存，分钟 > 0 时优先。 */
    private val AUTO_BACKUP_INTERVAL_MINUTES = intPreferencesKey("auto_backup_interval_minutes")
    private val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep_count")
    private val AUTO_BACKUP_LAST_AT = longPreferencesKey("auto_backup_last_at")
    private val AUTO_BACKUP_FINGERPRINT = stringPreferencesKey("auto_backup_fingerprint")
    private val AUTO_BACKUP_LAST_FILE = stringPreferencesKey("auto_backup_last_file")
    /** 上一次自动备份的失败原因与时间：成功时清空，供界面直接显示「为什么没备份」。 */
    private val AUTO_BACKUP_LAST_ERROR = stringPreferencesKey("auto_backup_last_error")
    private val AUTO_BACKUP_LAST_ERROR_AT = longPreferencesKey("auto_backup_last_error_at")
    private val AUTO_BACKUP_DIRECTORY = stringPreferencesKey("auto_backup_directory")
    /** SAF 授权的备份文件夹（tree URI），优先级高于手输路径。 */
    private val AUTO_BACKUP_TREE_URI = stringPreferencesKey("auto_backup_tree_uri")
    private val AUTO_BACKUP_TREE_LABEL = stringPreferencesKey("auto_backup_tree_label")
    /** 自动备份格式：json / full（全量压缩包）。 */
    private val AUTO_BACKUP_FORMAT = stringPreferencesKey("auto_backup_format")
    /** 全量备份时连同 Linux 容器一起打包。 */
    private val AUTO_BACKUP_INCLUDE_LINUX = booleanPreferencesKey("auto_backup_include_linux")
    /** 工具调用审查档位：read_only / strict / standard / smart / auto。 */
    private val TOOL_REVIEW_MODE = stringPreferencesKey("tool_review_mode")
    /** 智能档决策模型 ID：空串跟随主对话模型。 */
    private val TOOL_REVIEW_DECISION_MODEL_ID = stringPreferencesKey("tool_review_decision_model_id")
    private val DIAG_PATROL_ENABLED = booleanPreferencesKey("diag_patrol_enabled")
    private val DIAG_PATROL_INTERVAL = intPreferencesKey("diag_patrol_interval_minutes")
    private val DIAG_PATROL_KEEP = intPreferencesKey("diag_patrol_keep_count")
    private val DIAG_PATROL_ONLY_ABNORMAL = booleanPreferencesKey("diag_patrol_only_abnormal")
    private val DIAG_PATROL_LAST_AT = longPreferencesKey("diag_patrol_last_at")
    private val DIAG_PATROL_LAST_FILE = stringPreferencesKey("diag_patrol_last_file")
    private val DIAG_PATROL_LAST_LEVEL = stringPreferencesKey("diag_patrol_last_level")
    private val DIAG_PATROL_LAST_SUMMARY = stringPreferencesKey("diag_patrol_last_summary")
    private val LINUX_DISTRIBUTION = stringPreferencesKey("linux_distribution")
    private val APPEARANCE_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
    private val APPEARANCE_MONET_ENABLED = booleanPreferencesKey("appearance_monet_enabled")
    private val APPEARANCE_PALETTE_STYLE = stringPreferencesKey("appearance_palette_style")
    private val APPEARANCE_ACCENT_COLOR = stringPreferencesKey("appearance_accent_color")
    private val APPEARANCE_PURE_BLACK_ENABLED = booleanPreferencesKey("appearance_pure_black_enabled")
    private val APPEARANCE_BLUR_ENABLED = booleanPreferencesKey("appearance_blur_enabled")
    private val APPEARANCE_TOP_BAR_BLUR_STYLE = stringPreferencesKey("appearance_top_bar_blur_style")
    private val APPEARANCE_SWIPE_DISMISS_ENABLED =
        booleanPreferencesKey("appearance_swipe_dismiss_enabled")
    private val APPEARANCE_PREDICTIVE_BACK_ENABLED =
        booleanPreferencesKey("appearance_predictive_back_enabled")
    private val APPEARANCE_INTERFACE_SCALE = floatPreferencesKey("appearance_interface_scale")
    private const val SELECTED_MODEL_BY_PROVIDER_PREFIX = "selected_model_id_by_provider."

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

    @Volatile
    private lateinit var dataStore: DataStore<Preferences>

    /** 自动备份目录的进程内缓存：供非挂起调用（目录解析、界面展示）读取。 */
    @Volatile
    private var autoBackupDirectoryCache: String = ""

    /** SAF 授权目录的进程内缓存：非挂起调用判断「有没有授权文件夹」用。 */
    @Volatile
    private var autoBackupTreeUriCache: String = ""

    /**
     * 工具调用审查档位的进程内缓存。
     *
     * 工具执行链是同步的（`ToolExecutor.execute`），每次调用都 `runBlocking { settings() }`
     * 等于每个工具多一次 DataStore 读盘 + 协程调度。这里与 [MemoryBudgetPolicy] 同一口径：
     * 设置流每次发射时同步进来，运行期只读这份缓存，零 IO。
     */
    @Volatile
    private var toolReviewModeCache: String = "standard"

    /**
     * 灵屏开关的进程内缓存。
     *
     * 事件热路径（每个 ToolStarted、每个 overlay 事件）都要判这一个布尔值，早先每判一次就
     * `runBlocking { settings() }` 读一遍 DataStore：I/O 抖动会直接拖慢事件链，异常还会
     * 打断事件处理（2026-11-26 审核）。与工具审查档位同一口径：设置流发射时同步进来，
     * 运行期只读内存。
     */
    @Volatile
    private var virtualScreenEnabledCache: Boolean = false

    /** 应用上下文：仅用于解析应用专属目录，避免在无 Context 的调用点猜路径。 */
    @Volatile
    private var appContext: Context? = null

    /** 缓存预热订阅：进程内唯一一条，重复 init 不重复起。 */
    @Volatile
    private var warmupJob: Job? = null

    fun cachedAutoBackupDirectory(): String = autoBackupDirectoryCache

    fun cachedAutoBackupTreeUri(): String = autoBackupTreeUriCache

    /**
     * 工具调用审查档位的同步读取入口：工具执行链在同步上下文里，不能挂起。
     *
     * 缓存由 [settingsFlow] 每次发射时刷新，冷启动第一次读取前返回缺省 `standard`
     * （与 `Settings.toolReviewMode` 的缺省值一致，不会因为「还没读到」而放宽成 auto）。
     */
    fun cachedToolReviewMode(): String = toolReviewModeCache

    /**
     * 灵屏开关的同步读取入口：事件流在热路径上判它，不能每次挂起读盘。
     *
     * 缺省 false 与 `Settings.virtualScreenEnabled` 的缺省值一致，不会因为「还没读到」
     * 就把界面切到灵屏。
     */
    fun cachedVirtualScreenEnabled(): Boolean = virtualScreenEnabledCache

    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            appContext = context.applicationContext
            dataStore = context.applicationContext.dataStore
        }
        startCacheWarmup()
    }

    /**
     * 常驻预热进程内缓存。
     *
     * [cachedVirtualScreenEnabled] 这类同步读取入口必须在「还没有任何界面订阅设置流」时
     * 也能拿到真实值。早先缓存只在有人 collect [settingsFlow] 时刷新，而灵屏判定发生在
     * AgentRuntimeService 的事件热路径上——它可能先于任何设置页启动，缓存还是缺省 false，
     * 灵屏就会被误判成关闭（2026-11-26 自查发现，本次修复引入的回归）。
     *
     * 这里在 init 阶段就起一条只读订阅，把缓存灌满并跟随变更；用独立作用域，
     * 与任何界面生命周期无关。
     */
    private fun startCacheWarmup() {
        if (warmupJob != null) return
        warmupJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsFlow().collect { /* 缓存由 settingsFlow 的 map 段写入，这里只需保持订阅 */ }
        }
    }

    /** 应用上下文：给备份目录解析等非挂起调用用（不持有 Activity，避免泄漏）。 */
    fun appContextOrNull(): Context? = appContext

    fun settingsFlow(): Flow<Settings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { preferences ->
                preferences.toSettings().also { settings ->
                    autoBackupDirectoryCache = settings.autoBackupDirectory
                    autoBackupTreeUriCache = settings.autoBackupTreeUri
                    // 工具调用审查档位同样缓存到进程内：工具执行链同步读，不能每次都落盘。
                    toolReviewModeCache = io.github.mangi.eta.agent.tool.ToolReviewMode
                        .normalized(settings.toolReviewMode)
                    // 灵屏开关也进缓存：事件热路径每来一个 ToolStarted 都要判，不能读盘。
                    virtualScreenEnabledCache = settings.virtualScreenEnabled
                    // 预算滑杆值随设置一起同步到注入侧缓存，运行期读取零 IO。
                    io.github.mangi.eta.agent.memory.MemoryBudgetPolicy.apply(
                        settings.memoryCoreBudgetChars,
                        settings.memoryHeadingBudgetChars,
                    )
                }
            }
    }

    suspend fun settings(): Settings = settingsFlow().first()

    /** 灵屏应用菜单的收藏（按包名保存，跨会话保留）。 */
    suspend fun favoriteApps(): Set<String> {
        ensureInitialized()
        return dataStore.data.first()[VIRTUAL_SCREEN_FAVORITE_APPS] ?: emptySet()
    }

    fun favoriteAppsFlow(): Flow<Set<String>> {
        ensureInitialized()
        return dataStore.data.map { it[VIRTUAL_SCREEN_FAVORITE_APPS] ?: emptySet() }
    }

    suspend fun setFavoriteApps(packages: Set<String>) {
        ensureInitialized()
        dataStore.edit { prefs ->
            if (packages.isEmpty()) prefs.remove(VIRTUAL_SCREEN_FAVORITE_APPS)
            else prefs[VIRTUAL_SCREEN_FAVORITE_APPS] = packages
        }
    }

    suspend fun officialModelCatalogRevision(): Int {
        ensureInitialized()
        return dataStore.data.first()[OFFICIAL_MODEL_CATALOG_REVISION] ?: 0
    }

    suspend fun setOfficialModelCatalogRevision(revision: Int) {
        ensureInitialized()
        dataStore.edit { preferences ->
            preferences[OFFICIAL_MODEL_CATALOG_REVISION] = revision
        }
    }

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        ensureInitialized()
        // 预算缓存必须在这里同步，不能只靠 settingsFlow() 的 map：
        // 收集者不在时 map 不会执行，注入侧会一直用旧预算，
        // 表现是「设置里改了数字，实际注入还是老值」。
        dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val updated = transform(current)
            io.github.mangi.eta.agent.memory.MemoryBudgetPolicy.apply(
                updated.memoryCoreBudgetChars,
                updated.memoryHeadingBudgetChars,
            )
            prefs.putOrRemove(SELECTED_PROVIDER_ID, updated.selectedProviderId)
            prefs.putOrRemove(SELECTED_MODEL_ID, updated.selectedModelId)
            prefs[MEMORY_ENABLED] = updated.memoryEnabled
            prefs[AUTO_MEMORY_ENABLED] = updated.autoMemoryEnabled
            prefs[MEMORY_AUTO_COMPACT_ENABLED] = updated.memoryAutoCompactEnabled
            prefs[MEMORY_CORE_BUDGET_CHARS] = io.github.mangi.eta.agent.memory.MemoryBudgetPolicy
                .normalizeCore(updated.memoryCoreBudgetChars)
            prefs[MEMORY_HEADING_BUDGET_CHARS] = io.github.mangi.eta.agent.memory.MemoryBudgetPolicy
                .normalizeHeading(updated.memoryHeadingBudgetChars)
            prefs[AUTO_SKILLS_ENABLED] = updated.autoSkillsEnabled
            prefs[VIRTUAL_SCREEN_ENABLED] = updated.virtualScreenEnabled
            prefs[VIRTUAL_SCREEN_OFF_ENABLED] = updated.virtualScreenOffEnabled
            prefs[VIRTUAL_SCREEN_FALLBACK_ENABLED] = updated.virtualScreenFallbackEnabled
            prefs[VIRTUAL_SCREEN_AUTO_RESTART_APPS] = updated.virtualScreenAutoRestartApps
            prefs[VIRTUAL_SCREEN_IDLE_TIMEOUT] = io.github.mangi.eta.data.model.VirtualScreenIdleTimeout.normalize(updated.virtualScreenIdleTimeoutMinutes)
            prefs[VIRTUAL_SCREEN_FLOAT_WINDOW] = updated.virtualScreenFloatWindowEnabled
            prefs[VIRTUAL_SCREEN_FLOAT_EDGE_COLLAPSE] = updated.virtualScreenFloatEdgeCollapseEnabled
            prefs[VIRTUAL_SCREEN_FLOAT_WIDTH] = updated.virtualScreenFloatWindowWidthDp
            prefs[VIRTUAL_SCREEN_FLOAT_HEIGHT] = updated.virtualScreenFloatWindowHeightDp
            prefs[VIRTUAL_SCREEN_FLOAT_ALPHA] = updated.virtualScreenFloatWindowAlpha
            prefs[VIRTUAL_SCREEN_FLOAT_POS_X] = updated.virtualScreenFloatWindowPosX
            prefs[VIRTUAL_SCREEN_FLOAT_POS_Y] = updated.virtualScreenFloatWindowPosY
            prefs[AUTO_BACKUP_ENABLED] = updated.autoBackupEnabled
            prefs[KEEPALIVE_ENABLED] = updated.keepaliveEnabled
            prefs[OVERLAY_FLUID_PINNED] = updated.overlayFluidPinned
            prefs[OVERLAY_FLUID_DOWNGRADE] = updated.overlayFluidDowngrade
            prefs[SEEN_TOUR_PACKS] = speechJson.encodeToString(updated.seenTourPacks)
            prefs[ONBOARDING_ENABLED] = updated.onboardingEnabled
            prefs[RECENT_OPENED_IDS] = updated.recentOpenedConversationIds
            prefs[AUTO_BACKUP_INTERVAL] = updated.autoBackupIntervalHours
                .coerceIn(1, io.github.mangi.eta.data.model.AutoBackupInterval.MAX_MINUTES / 60)
            prefs[AUTO_BACKUP_INTERVAL_MINUTES] =
                io.github.mangi.eta.data.model.AutoBackupInterval.normalize(updated.autoBackupIntervalMinutes)
            prefs[AUTO_BACKUP_KEEP] = io.github.mangi.eta.data.model.AutoBackupKeepCount.normalize(updated.autoBackupKeepCount)
            // 备份的运行态（上次时间 / 指纹 / 文件名 / 失败原因）只由 setAutoBackupState 单独写，
            // 这里不再回写：设置页的快照通常早于本次备份完成，回写会把刚跑完的状态又冲成旧值。
            prefs.putOrRemove(AUTO_BACKUP_DIRECTORY, updated.autoBackupDirectory)
            prefs.putOrRemove(AUTO_BACKUP_TREE_URI, updated.autoBackupTreeUri)
            prefs.putOrRemove(AUTO_BACKUP_TREE_LABEL, updated.autoBackupTreeLabel)
            prefs[AUTO_BACKUP_FORMAT] = io.github.mangi.eta.data.model.AutoBackupFormat.normalize(updated.autoBackupFormat)
            prefs[AUTO_BACKUP_INCLUDE_LINUX] = updated.autoBackupIncludeLinux
            prefs[TOOL_REVIEW_MODE] = io.github.mangi.eta.agent.tool.ToolReviewMode.normalized(updated.toolReviewMode)
            prefs.putOrRemove(TOOL_REVIEW_DECISION_MODEL_ID, updated.toolReviewDecisionModelId)
            prefs[DIAG_PATROL_ENABLED] = updated.diagPatrolEnabled
            prefs[DIAG_PATROL_INTERVAL] = io.github.mangi.eta.core.DiagnosticsPatrolInterval
                .normalize(updated.diagPatrolIntervalMinutes)
            prefs[DIAG_PATROL_KEEP] = updated.diagPatrolKeepCount.coerceIn(1, 60)
            prefs[DIAG_PATROL_ONLY_ABNORMAL] = updated.diagPatrolNotifyOnlyAbnormal
            prefs[DIAG_PATROL_LAST_AT] = updated.diagPatrolLastAt
            prefs.putOrRemove(DIAG_PATROL_LAST_FILE, updated.diagPatrolLastFile)
            prefs.putOrRemove(DIAG_PATROL_LAST_LEVEL, updated.diagPatrolLastLevel)
            prefs.putOrRemove(DIAG_PATROL_LAST_SUMMARY, updated.diagPatrolLastSummary)
            prefs.remove(stringPreferencesKey("vivo_atomic_scene"))
            prefs.putAppearance(updated.appearance.normalized())
        }
    }

    /** 保活开关的独立流：设置页只订阅这一位，不必跟着整份 Settings 重绘。 */
    fun keepaliveEnabledFlow(): Flow<Boolean> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { prefs -> prefs[KEEPALIVE_ENABLED] ?: false }
    }

    suspend fun setKeepaliveEnabled(enabled: Boolean) {
        ensureInitialized()
        dataStore.edit { prefs -> prefs[KEEPALIVE_ENABLED] = enabled }
    }

    suspend fun autoBackupLastAt(): Long = settings().autoBackupLastAt

    /** 备份状态单独写，走独立 edit，避免与设置页的表单改动互相覆盖。 */
    suspend fun setAutoBackupState(
        lastAt: Long,
        fingerprint: String,
        fileName: String,
        error: String = "",
        errorAt: Long = 0L,
    ) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs[AUTO_BACKUP_LAST_AT] = lastAt
            if (fingerprint.isBlank()) prefs.remove(AUTO_BACKUP_FINGERPRINT)
            else prefs[AUTO_BACKUP_FINGERPRINT] = fingerprint
            if (fileName.isBlank()) prefs.remove(AUTO_BACKUP_LAST_FILE)
            else prefs[AUTO_BACKUP_LAST_FILE] = fileName
            if (error.isBlank()) {
                prefs.remove(AUTO_BACKUP_LAST_ERROR)
                prefs.remove(AUTO_BACKUP_LAST_ERROR_AT)
            } else {
                prefs[AUTO_BACKUP_LAST_ERROR] = error
                prefs[AUTO_BACKUP_LAST_ERROR_AT] = errorAt
            }
        }
    }

    fun selectedProviderIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedProviderId }

    fun selectedModelIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedModelId }

    suspend fun selectedModelIdForProvider(providerId: String): String? {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> prefs[selectedModelByProviderKey(providerId)] }
            .first()
    }

    fun memoryAutoCompactEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.memoryAutoCompactEnabled }

    fun memoryEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.memoryEnabled }

    fun linuxDistributionFlow(): Flow<String?> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { preferences -> preferences[LINUX_DISTRIBUTION] }
    }

    fun linuxBackendFlow(distribution: String): Flow<String?> {
        ensureInitialized()
        return dataStore.data.catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }.map { it[stringPreferencesKey("linux_backend.$distribution")] }
    }

    suspend fun setLinuxBackend(distribution: String, backend: String?) {
        ensureInitialized()
        dataStore.edit { preferences ->
            val key = stringPreferencesKey("linux_backend.$distribution")
            if (backend == null) preferences.remove(key) else preferences[key] = backend
        }
    }

    fun appearanceSettingsFlow(): Flow<AppearanceSettings> =
        settingsFlow().map { it.appearance }

    suspend fun setSelectedProviderId(id: String?) {
        updateSettings { it.copy(selectedProviderId = id) }
    }

    suspend fun setSelectedModelId(id: String?) {
        updateSettings { it.copy(selectedModelId = id) }
    }

    suspend fun setSelection(providerId: String?, modelId: String?) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val previousProviderId = prefs[SELECTED_PROVIDER_ID]
            val previousModelId = prefs[SELECTED_MODEL_ID]
            if (previousProviderId != null && previousModelId != null) {
                prefs[selectedModelByProviderKey(previousProviderId)] = previousModelId
            }

            prefs.putOrRemove(SELECTED_PROVIDER_ID, providerId)
            prefs.putOrRemove(SELECTED_MODEL_ID, modelId)
            if (providerId != null && modelId != null) {
                prefs[selectedModelByProviderKey(providerId)] = modelId
            }
        }
    }

    suspend fun clearSelectedModelIdForProvider(providerId: String) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs.remove(selectedModelByProviderKey(providerId))
        }
    }

    suspend fun setMemoryAutoCompactEnabled(enabled: Boolean) {
        updateSettings { it.copy(memoryAutoCompactEnabled = enabled) }
    }

    /** 自定义核心记忆注入预算；0 表示恢复自动。写入后立即同步注入侧缓存。 */
    suspend fun setMemoryCoreBudgetChars(chars: Int) {
        updateSettings {
            it.copy(
                memoryCoreBudgetChars =
                io.github.mangi.eta.agent.memory.MemoryBudgetPolicy.normalizeCore(chars),
            )
        }
        settings()
    }

    /** 自定义标题索引注入预算；0 表示恢复自动。 */
    suspend fun setMemoryHeadingBudgetChars(chars: Int) {
        updateSettings {
            it.copy(
                memoryHeadingBudgetChars =
                io.github.mangi.eta.agent.memory.MemoryBudgetPolicy.normalizeHeading(chars),
            )
        }
        settings()
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        updateSettings { it.copy(memoryEnabled = enabled) }
    }

    suspend fun setLinuxDistribution(value: String?) {
        ensureInitialized()
        dataStore.edit { preferences -> preferences.putOrRemove(LINUX_DISTRIBUTION, value) }
    }

    suspend fun setAppearanceSettings(settings: AppearanceSettings) {
        updateSettings { it.copy(appearance = settings.normalized()) }
    }

    suspend fun updateAppearanceSettings(transform: (AppearanceSettings) -> AppearanceSettings) {
        updateSettings { settings ->
            settings.copy(appearance = transform(settings.appearance).normalized())
        }
    }

    private fun ensureInitialized() {
        check(::dataStore.isInitialized) {
            "SettingsDataStore.init(context) must be called in Application.onCreate()"
        }
    }

    private fun selectedModelByProviderKey(providerId: String): Preferences.Key<String> =
        stringPreferencesKey("$SELECTED_MODEL_BY_PROVIDER_PREFIX$providerId")

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }

    private fun Preferences.toSettings(): Settings = Settings(
        selectedProviderId = this[SELECTED_PROVIDER_ID],
        selectedModelId = this[SELECTED_MODEL_ID],
        memoryEnabled = this[MEMORY_ENABLED] ?: true,
        autoMemoryEnabled = this[AUTO_MEMORY_ENABLED] ?: true,
        memoryAutoCompactEnabled = this[MEMORY_AUTO_COMPACT_ENABLED] ?: true,
        memoryCoreBudgetChars = this[MEMORY_CORE_BUDGET_CHARS] ?: 0,
        memoryHeadingBudgetChars = this[MEMORY_HEADING_BUDGET_CHARS] ?: 0,
        autoSkillsEnabled = this[AUTO_SKILLS_ENABLED] ?: true,
        virtualScreenEnabled = this[VIRTUAL_SCREEN_ENABLED] ?: false,
        virtualScreenOffEnabled = this[VIRTUAL_SCREEN_OFF_ENABLED] ?: false,
        virtualScreenFallbackEnabled = this[VIRTUAL_SCREEN_FALLBACK_ENABLED] ?: false,
        virtualScreenAutoRestartApps = this[VIRTUAL_SCREEN_AUTO_RESTART_APPS] ?: false,
        virtualScreenIdleTimeoutMinutes = io.github.mangi.eta.data.model.VirtualScreenIdleTimeout.normalize(this[VIRTUAL_SCREEN_IDLE_TIMEOUT] ?: 20),
        virtualScreenFloatWindowEnabled = this[VIRTUAL_SCREEN_FLOAT_WINDOW] ?: false,
        virtualScreenFloatEdgeCollapseEnabled = this[VIRTUAL_SCREEN_FLOAT_EDGE_COLLAPSE] ?: true,
        virtualScreenFloatWindowWidthDp = this[VIRTUAL_SCREEN_FLOAT_WIDTH] ?: 0,
        virtualScreenFloatWindowHeightDp = this[VIRTUAL_SCREEN_FLOAT_HEIGHT] ?: 0,
        virtualScreenFloatWindowAlpha = this[VIRTUAL_SCREEN_FLOAT_ALPHA] ?: 0.85f,
        virtualScreenFloatWindowPosX = this[VIRTUAL_SCREEN_FLOAT_POS_X] ?: 16,
        virtualScreenFloatWindowPosY = this[VIRTUAL_SCREEN_FLOAT_POS_Y] ?: 120,
        autoBackupEnabled = this[AUTO_BACKUP_ENABLED] ?: false,
        keepaliveEnabled = this[KEEPALIVE_ENABLED] ?: false,
        overlayFluidPinned = this[OVERLAY_FLUID_PINNED] ?: false,
        overlayFluidDowngrade = this[OVERLAY_FLUID_DOWNGRADE] ?: false,
        seenTourPacks = this[SEEN_TOUR_PACKS]?.let { raw ->
            runCatching { speechJson.decodeFromString<Map<String, Int>>(raw) }.getOrNull()
        }.orEmpty(),
        onboardingEnabled = this[ONBOARDING_ENABLED] ?: true,
        recentOpenedConversationIds = this[RECENT_OPENED_IDS] ?: "",
        autoBackupIntervalMinutes = io.github.mangi.eta.data.model.AutoBackupInterval.effectiveMinutes(
            this[AUTO_BACKUP_INTERVAL_MINUTES] ?: 0,
            this[AUTO_BACKUP_INTERVAL] ?: 24,
        ),
        autoBackupIntervalHours = io.github.mangi.eta.data.model.AutoBackupInterval.fromLegacyHours(
            this[AUTO_BACKUP_INTERVAL] ?: 24,
        ) / 60,
        autoBackupKeepCount = io.github.mangi.eta.data.model.AutoBackupKeepCount.normalize(this[AUTO_BACKUP_KEEP] ?: 5),
        autoBackupLastAt = this[AUTO_BACKUP_LAST_AT] ?: 0L,
        autoBackupFingerprint = this[AUTO_BACKUP_FINGERPRINT].orEmpty(),
        autoBackupLastFileName = this[AUTO_BACKUP_LAST_FILE].orEmpty(),
        autoBackupLastError = this[AUTO_BACKUP_LAST_ERROR].orEmpty(),
        autoBackupLastErrorAt = this[AUTO_BACKUP_LAST_ERROR_AT] ?: 0L,
        autoBackupDirectory = this[AUTO_BACKUP_DIRECTORY].orEmpty(),
        autoBackupTreeUri = this[AUTO_BACKUP_TREE_URI].orEmpty(),
        autoBackupTreeLabel = this[AUTO_BACKUP_TREE_LABEL].orEmpty(),
        autoBackupFormat = io.github.mangi.eta.data.model.AutoBackupFormat.normalize(
            this[AUTO_BACKUP_FORMAT].orEmpty(),
        ),
        autoBackupIncludeLinux = this[AUTO_BACKUP_INCLUDE_LINUX] ?: false,
        toolReviewMode = io.github.mangi.eta.agent.tool.ToolReviewMode.normalized(
            this[TOOL_REVIEW_MODE],
        ),
        toolReviewDecisionModelId = this[TOOL_REVIEW_DECISION_MODEL_ID].orEmpty(),
        diagPatrolEnabled = this[DIAG_PATROL_ENABLED] ?: false,
        diagPatrolIntervalMinutes = io.github.mangi.eta.core.DiagnosticsPatrolInterval.normalize(
            this[DIAG_PATROL_INTERVAL] ?: io.github.mangi.eta.core.DiagnosticsPatrolInterval.DEFAULT_MINUTES,
        ),
        diagPatrolKeepCount = (this[DIAG_PATROL_KEEP] ?: 30).coerceIn(1, 60),
        diagPatrolNotifyOnlyAbnormal = this[DIAG_PATROL_ONLY_ABNORMAL] ?: true,
        diagPatrolLastAt = this[DIAG_PATROL_LAST_AT] ?: 0L,
        diagPatrolLastFile = this[DIAG_PATROL_LAST_FILE].orEmpty(),
        diagPatrolLastLevel = this[DIAG_PATROL_LAST_LEVEL].orEmpty(),
        diagPatrolLastSummary = this[DIAG_PATROL_LAST_SUMMARY].orEmpty(),
        appearance = AppearanceSettings(
            themeMode = AppearanceThemeMode.fromPersistedValue(this[APPEARANCE_THEME_MODE]),
            monetEnabled = this[APPEARANCE_MONET_ENABLED] ?: false,
            paletteStyle = AppearancePaletteStyle.fromPersistedValue(this[APPEARANCE_PALETTE_STYLE]),
            accentColor = AppearanceAccentColor.fromPersistedValue(this[APPEARANCE_ACCENT_COLOR]),
            pureBlackEnabled = this[APPEARANCE_PURE_BLACK_ENABLED] ?: false,
            blurEnabled = this[APPEARANCE_BLUR_ENABLED] ?: true,
            topBarBlurStyle = AppearanceTopBarBlurStyle.fromPersistedValue(
                this[APPEARANCE_TOP_BAR_BLUR_STYLE],
            ),
            swipeDismissEnabled = this[APPEARANCE_SWIPE_DISMISS_ENABLED] ?: true,
            predictiveBackEnabled = this[APPEARANCE_PREDICTIVE_BACK_ENABLED] ?: true,
            interfaceScale = this[APPEARANCE_INTERFACE_SCALE] ?: 1f,
        ).normalized(),
    )

    private fun MutablePreferences.putAppearance(settings: AppearanceSettings) {
        this[APPEARANCE_THEME_MODE] = settings.themeMode.persistedValue
        this[APPEARANCE_MONET_ENABLED] = settings.monetEnabled
        this[APPEARANCE_PALETTE_STYLE] = settings.paletteStyle.persistedValue
        this[APPEARANCE_ACCENT_COLOR] = settings.accentColor.persistedValue
        this[APPEARANCE_PURE_BLACK_ENABLED] = settings.pureBlackEnabled
        this[APPEARANCE_BLUR_ENABLED] = settings.blurEnabled
        this[APPEARANCE_TOP_BAR_BLUR_STYLE] = settings.topBarBlurStyle.persistedValue
        this[APPEARANCE_SWIPE_DISMISS_ENABLED] = settings.swipeDismissEnabled
        this[APPEARANCE_PREDICTIVE_BACK_ENABLED] = settings.predictiveBackEnabled
        this[APPEARANCE_INTERFACE_SCALE] = settings.interfaceScale
    }

    fun conversationTitleSettingsFlow(): Flow<ConversationTitleSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { prefs ->
                prefs[CONVERSATION_TITLE_SETTINGS]?.let { raw ->
                    runCatching { speechJson.decodeFromString<ConversationTitleSettings>(raw) }.getOrNull()
                } ?: ConversationTitleSettings()
            }
    }

    suspend fun conversationTitleSettings(): ConversationTitleSettings = conversationTitleSettingsFlow().first()

    suspend fun setConversationTitleSettings(settings: ConversationTitleSettings) {
        ensureInitialized()
        dataStore.edit { it[CONVERSATION_TITLE_SETTINGS] = speechJson.encodeToString(settings) }
    }
}
