package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.core.CrashRecorder
import io.github.mangi.eta.core.DiagnosticsBundle
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import androidx.compose.material.icons.rounded.Tune
import io.github.mangi.eta.data.model.ProviderModel
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.ModelBrandMark
import io.github.mangi.eta.ui.components.OptionalModelPickerContent
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Eta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 */

@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageContent(context = context, onNavigate = onNavigate, onBack = onBack)
}

@Composable
private fun SettingsPageContent(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    var showTestConsent by remember { mutableStateOf(false) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(isAgentAccessibilityEnabled(context))
    }
    var accessibilityProtectionEnabled by remember {
        mutableStateOf(AccessibilityProtectionClient.isEnabled(context))
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // 关于组：反馈与诊断。
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    // 崩溃记录条数：只给关于页那行入口写摘要；明细都在「诊断与日志」里。
    var crashCount by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = isAgentAccessibilityEnabled(context)
                accessibilityProtectionEnabled =
                    AccessibilityProtectionClient.isEnabled(context)
                val crashes = CrashRecorder.records(context)
                crashCount = crashes.size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / Model 选中状态展示
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(EtaApp.serviceInstance)) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    val effectiveCapabilities = remember(providers, selectedModelId) {
        providers.enabledModel(selectedModelId)?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
    }
    val effortOptions = remember(effectiveCapabilities) {
        effectiveCapabilities?.selectableEfforts?.filter { it != ReasoningEffort.OFF }.orEmpty()
    }
    val providerDefaultLabel = stringResource(R.string.conversation_title_settings_reasoning_provider_default)
    val effortLabels = remember(effortOptions, providerDefaultLabel) {
        effortOptions.map { effort ->
            if (effort == ReasoningEffort.DEFAULT) providerDefaultLabel else effort.displayName
        }
    }
    val defaultThinkingKey = Prefs.Keys.AGENT_THINKING_ENABLED
    val defaultThinkingFallback = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(defaultThinkingKey)
    var defaultThinkingEnabled by remember(agentPrefs) {
        mutableStateOf(
            agentPrefs?.getBoolean(defaultThinkingKey, defaultThinkingFallback)
                ?: enhancementHistory.checked(defaultThinkingKey, defaultThinkingFallback),
        )
    }
    var savedDefaultEffort by remember(agentPrefs) {
        mutableStateOf(Prefs.defaultReasoningEffort(agentPrefs))
    }
    // 会话框里改档位会回写同一份全局偏好；这里跟着刷新，否则设置页还显示旧默认值、下拉也被误禁用。
    DisposableEffect(agentPrefs) {
        val targetPrefs = agentPrefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            when (key) {
                defaultThinkingKey ->
                    defaultThinkingEnabled = changedPrefs.getBoolean(defaultThinkingKey, defaultThinkingFallback)
                Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT ->
                    savedDefaultEffort = Prefs.defaultReasoningEffort(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val effectiveSelectedEffort = remember(effectiveCapabilities, savedDefaultEffort, defaultThinkingEnabled) {
        // 默认启用思考关掉时，默认档位就是 OFF；不要在这里回退成 DEFAULT 让人误以为还在思考。
        if (!defaultThinkingEnabled) ReasoningEffort.OFF
        else effectiveCapabilities?.normalize(savedDefaultEffort) ?: ReasoningEffort.DEFAULT
    }
    val selectedEffortIndex = remember(effortOptions, effectiveSelectedEffort) {
        effortOptions.indexOf(effectiveSelectedEffort).takeIf { it >= 0 } ?: 0
    }
    val defaultEffortTitle = stringResource(R.string.settings_default_reasoning_effort)
    val defaultEffortEnabled = agentPrefs != null && defaultThinkingEnabled && effortOptions.size > 1
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET ||
                key == Prefs.Keys.POWER_KEY_TAKEOVER
            ) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(Unit) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                prefs?.let { connected ->
                    enhancementHistory.captureConnected(connected)
                    hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target ->
        DropdownItem(text = target.displayName(context))
    }

    // 设置项搜索：顶部关键字过滤分组。可搜文本 = 分组标题（按当前语言取文案）+ 手写别名，
    // 索引见文件末尾 SETTINGS_SEARCH_INDEX；这里一次性拼好，过滤与「无结果」判断共用同一口径。
    var settingsQuery by remember { mutableStateOf("") }
    val settingsKeyword = settingsQuery.trim().lowercase()
    val searchHaystacks = rememberSettingsSearchHaystacks()
    val sectionVisible: (String) -> Boolean = { key ->
        settingsKeyword.isEmpty() || (searchHaystacks[key]?.contains(settingsKeyword) ?: true)
    }
    val settingsNoHit = settingsKeyword.isNotEmpty() &&
        searchHaystacks.values.none { it.contains(settingsKeyword) }
    var showToolReviewModelSheet by remember { mutableStateOf(false) }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
        overlay = {
            ToolReviewDecisionModelSheet(
                show = showToolReviewModelSheet,
                onDismiss = { showToolReviewModelSheet = false },
            )
        },
    ) {

            item(key = "settings_search") {
                SettingsSearchBar(
                    query = settingsQuery,
                    onQueryChange = { settingsQuery = it },
                    emptyResult = settingsNoHit,
                )
            }

            item(key = "section_general") {
                if (!sectionVisible("section_general")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_general))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.appearance_title),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Palette,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.speech_settings_title),
                        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Mic, tint = EtaPreferenceColors.Blue) },
                        onClick = { onNavigate(AppRoute.SpeechSettings) },
                    )
                    EtaPreferenceDivider()
                    LanguagePreference(iconTint = EtaPreferenceColors.Blue)

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.data_backup_title),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Description,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DataBackup) },
                    )

                }
            }

            item(key = "section_agent") {
                if (!sectionVisible("section_agent")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_llm_providers))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.ui_model_provider_e8c7f5),
                        summary = providerSummary,
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Cloud,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = Icons.Rounded.Psychology,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    if (effortOptions.size > 1) {
                        EtaDropdownPreference(
                            title = defaultEffortTitle,
                            items = effortLabels.map { DropdownItem(text = it) },
                            selectedIndex = selectedEffortIndex,
                            onSelectedIndexChange = { index ->
                                val chosen = effortOptions.getOrNull(index)
                                    ?: return@EtaDropdownPreference
                                val targetPrefs = agentPrefs ?: return@EtaDropdownPreference
                                if (putStringSync(
                                        prefs = targetPrefs,
                                        key = Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT,
                                        value = chosen.wireValue,
                                    )
                                ) {
                                    savedDefaultEffort = chosen
                                }
                            },
                        )
                    }

                }
            }

            item(key = "section_capabilities") {
                if (!sectionVisible("section_capabilities")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_section_model_capabilities))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.settings_tools_list),
                        startAction = { EtaPreferenceIcon(Icons.Rounded.Dashboard, tint = EtaPreferenceColors.Green) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )

                    EtaPreferenceDivider()
                    ToolReviewModePref()

                    EtaPreferenceDivider()
                    ToolReviewDecisionModelPref(onOpenPicker = { showToolReviewModelSheet = true })

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_web_browsing_tools_8b6b03),
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = Icons.Rounded.Language,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.web_tools_settings_title),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.TravelExplore,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.WebToolsSettings) },
                    )

                    EtaPreferenceDivider()
                    AutoCompactionThresholdPref(prefs = agentPrefs)

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Inventory2,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                }
            }

            item(key = "section_extensions") {
                if (!sectionVisible("section_extensions")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_section_extensions))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.ui_memory_b55ff5),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.AutoMirrored.Rounded.MenuBook,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.conversation_title_settings_title),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.AutoAwesome,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ConversationTitleSettings) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.automation_title),
                        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.FilterAlt, tint = EtaPreferenceColors.Blue) },
                        onClick = { onNavigate(AppRoute.Tasks) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.lingscreen_entry_title),
                        startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Smartphone, tint = EtaPreferenceColors.Blue) },
                        onClick = { onNavigate(AppRoute.VirtualScreen) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.route_subagents),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Hub,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Subagents) },
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.route_skills),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.ImportContacts,
                                tint = EtaPreferenceColors.Green,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Skills) },
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.route_mcp_servers),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.AccountTree,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.McpServers) },
                    )

                }
            }

            item(key = "section_persona") {
                if (!sectionVisible("section_persona")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_section_persona))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.prompt_studio_title),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Psychology,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.PromptStudio) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.sidebar_characters),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.SportsBar,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Characters) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = "专家",
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.AutoAwesome,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ExpertCenter) },
                    )
                }
            }

            item(key = "section_permissions") {
                if (!sectionVisible("section_permissions")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_section_permissions_devices))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.ui_floating_window_permissions_076b77),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Layers,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        endActions = {
                            Text(
                                text = stringResource(
                                    if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (overlayGranted) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    MiuixTheme.colorScheme.error
                                },
                            )
                        },
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.AccessibilityNew,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        endActions = {
                            val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                            Text(
                                text = stringResource(
                                    if (enabled) R.string.status_enabled else R.string.status_disabled,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (enabled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    MiuixTheme.colorScheme.primary
                                },
                            )
                        },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }
                        },
                    )
                    if (AccessibilityProtectionClient.isSupported() && (prefs != null || hasConnectedFramework)) {
                        EtaPreferenceDivider()
                        EtaSwitchPreference(
                            title = stringResource(R.string.ui_enforce_accessibility_55e838),
                            checked = accessibilityProtectionEnabled,
                            onCheckedChange = { enabled ->
                                if (accessibilityProtectionPending) {
                                    return@EtaSwitchPreference
                                }
                                accessibilityProtectionPending = true
                                AccessibilityProtectionClient.setEnabled(
                                    context = context,
                                    enabled = enabled,
                                ) { result ->
                                    accessibilityProtectionPending = false
                                    accessibilityProtectionEnabled = result.enabled
                                    accessibilityGranted = isAgentAccessibilityEnabled(context)
                                    val failureMessage = when (result.status) {
                                        AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                                        AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                                            context.getString(R.string.accessibility_protection_unavailable)
                                        AccessibilityProtectionClient.ControlStatus.REJECTED ->
                                            context.getString(R.string.accessibility_protection_rejected)
                                    }
                                    if (failureMessage != null) {
                                        Toast.makeText(
                                            context.applicationContext,
                                            failureMessage,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.VerifiedUser,
                                    tint = EtaPreferenceColors.Blue,
                                    enabled = prefs != null && !accessibilityProtectionPending,
                                )
                            },
                            enabled = prefs != null && !accessibilityProtectionPending,
                        )
                    }

                    // 流体云属于「通知可见性」范畴，跟权限放一起：常驻与降级是同一类开关。
                    EtaPreferenceDivider()
                    FluidPinnedPref()

                    EtaPreferenceDivider()
                    FluidDowngradePref()
                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                        key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                        icon = Icons.Rounded.Smartphone,
                        iconTint = EtaPreferenceColors.Green,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                        icon = Icons.Rounded.Visibility,
                        iconTint = EtaPreferenceColors.Blue,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                        icon = Icons.Rounded.GppMaybe,
                        iconTint = EtaPreferenceColors.Orange,
                    )

                    EtaPreferenceDivider()
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = Icons.Rounded.Terminal,
                        iconTint = EtaPreferenceColors.Green,
                    )

                }
            }

            item(key = "section_system_integration") {
                if (!sectionVisible("section_system_integration")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_section_system_integration))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.capability_enhancements),
                        startAction = { EtaPreferenceIcon(Icons.Rounded.Security, tint = EtaPreferenceColors.Blue) },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.SupportAgent,
                                tint = EtaPreferenceColors.Green,
                            )
                        },
                        onClick = openAssistantSettings,
                    )
                    EtaPreferenceDivider()
                    if (prefs != null || hasConnectedFramework) {
                        EtaPreferenceDivider()
                        EtaDropdownPreference(
                            title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                            items = powerAssistantItems,
                            selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                            onSelectedIndexChange = { index ->
                                val target = powerAssistantTargets.getOrNull(index)
                                    ?: return@EtaDropdownPreference
                                val targetPrefs = prefs ?: return@EtaDropdownPreference
                                if (putStringSync(
                                        prefs = targetPrefs,
                                        key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                        value = target.persistedValue,
                                    )
                                ) {
                                    powerAssistantTarget = target
                                    enhancementHistory.recordCommittedTarget(target)
                                } else {
                                    Toast.makeText(
                                        context.applicationContext,
                                        context.getString(R.string.settings_write_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.PowerSettingsNew,
                                    tint = EtaPreferenceColors.Yellow,
                                    enabled = prefs != null,
                                )
                            },
                            enabled = prefs != null,
                        )

                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                            key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                            icon = Icons.Rounded.Settings,
                            iconTint = EtaPreferenceColors.Green,
                        )
                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                            key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                            icon = Icons.Rounded.Cloud,
                            iconTint = EtaPreferenceColors.Blue,
                        )
                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                            summary = stringResource(R.string.ui_vendor_assistant_require_prefix_summary),
                            key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                            icon = Icons.Rounded.FilterAlt,
                            iconTint = EtaPreferenceColors.Blue,
                        )
                    }
                }
            }

            item(key = "section_accounts") {
                if (!sectionVisible("section_accounts")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_section_accounts))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.route_github_account),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Code,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.GitHubAccount) },
                    )

                }
            }

            item(key = "section_diagnostics") {
                if (!sectionVisible("section_diagnostics")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.settings_section_diagnostics))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.settings_self_check_title),
                        summary = "一键体检：授权、自动任务依赖、本机数据",
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.VerifiedUser,
                                tint = EtaPreferenceColors.Green,
                            )
                        },
                        onClick = { onNavigate(AppRoute.SelfCheck) },
                    )

                    EtaPreferenceDivider()
                    if (io.github.mangi.eta.BuildConfig.BETA_BUILD) {
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.settings_test_privacy_title),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.VerifiedUser,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { showTestConsent = true },
                    )
                    }

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.settings_diagnostics_title),
                        summary = if (crashCount <= 0) {
                            "诊断包 · 暂无崩溃记录"
                        } else {
                            "诊断包 · 崩溃记录 $crashCount 条"
                        },
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.BugReport,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DiagnosticsCenter) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = "日志",
                        summary = "实时日志 · 等级筛选 · 搜索 · 复制",
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Terminal,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LogViewer) },
                    )
                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.diag_patrol_title),
                        summary = stringResource(R.string.diag_patrol_entry_summary),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.BugReport,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DiagnosticsPatrol) },
                    )
                }
            }

            item(key = "section_about") {
                if (!sectionVisible("section_about")) return@item
                EtaPreferenceGroupTitle(stringResource(R.string.ui_about_bed172))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.usage_stats_title),
                        summary = stringResource(R.string.usage_stats_summary),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.BarChart,
                                tint = EtaPreferenceColors.Green,
                            )
                        },
                        onClick = { onNavigate(AppRoute.TokenUsage) },
                    )

                    EtaPreferenceDivider()
                    EtaArrowPreference(
                        title = stringResource(R.string.data_usage_title),
                        summary = stringResource(R.string.data_usage_summary),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Inventory2,
                                tint = EtaPreferenceColors.Blue,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DataUsage) },
                    )


                }
            }
        }

        if (showTestConsent) {
            io.github.mangi.eta.ui.consent.TestConsentSettingsDialog(
                onDismiss = { showTestConsent = false },
            )
        }


}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * 配置来源由调用方按能力边界传入。Hook 开关仍可能因 LSPosed 未连接而禁用；Agent
 * Runtime 开关始终使用 App 本地配置。
 */
/** 流体云常驻：与灵屏解耦，属于全局流体云行为。 */
@Composable
private fun FluidPinnedPref() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = null)
    val current = settings ?: return
    EtaSwitchPreference(
        title = stringResource(R.string.lingscreen_fluid_pinned),
        summary = stringResource(R.string.lingscreen_fluid_pinned_summary),
        checked = current.overlayFluidPinned,
        onCheckedChange = { value ->
            scope.launch {
                SettingsDataStore.updateSettings { it.copy(overlayFluidPinned = value) }
                runCatching {
                    context.startService(
                        Intent(context, io.github.mangi.eta.agent.runtime.AgentRuntimeService::class.java)
                            .setAction(io.github.mangi.eta.agent.runtime.AgentRuntimeService.ACTION_REFRESH_FLUID)
                    )
                }
            }
        },
        startAction = { EtaPreferenceIcon(Icons.Rounded.Cloud, tint = EtaPreferenceColors.Green) },
    )
}

/** 工具调用审查档位：只读 / 严格 / 标准 / 智能 / 自动。 */
@Composable
private fun ToolReviewModePref() {
    val scope = rememberCoroutineScope()
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = null)
    val current = settings ?: return
    val labels = listOf(
        "只读（不改动任何状态）",
        "严格（非只读一律先问）",
        "标准（只拦高风险）",
        "智能（由决策模型逐次裁决）",
        "自动（全部放行）",
    )
    val keys = listOf("read_only", "strict", "standard", "smart", "auto")
    val selected = keys.indexOf(io.github.mangi.eta.agent.tool.ToolReviewMode.normalized(current.toolReviewMode))
        .coerceAtLeast(0)
    EtaDropdownPreference(
        title = "工具调用审查",
        summary = "危险或改状态的操作在什么档位下先征求你确认",
        items = labels.map { DropdownItem(text = it) },
        selectedIndex = selected,
        onSelectedIndexChange = { index ->
            val key = keys.getOrNull(index) ?: return@EtaDropdownPreference
            scope.launch {
                SettingsDataStore.updateSettings { it.copy(toolReviewMode = key) }
            }
        },
        startAction = { EtaPreferenceIcon(Icons.Rounded.GppMaybe, tint = EtaPreferenceColors.Green) },
    )
}

/**
 * 智能档的决策模型：默认跟随对话模型，可指定别的模型专门做审查裁决。
 *
 * 交互与「模型提供商」那行保持一致：普通设置行 + 模型品牌图标 + 右侧当前选择，
 * 点开走标准模型选择面板（搜索 / 服务商筛选 / 分组列表），
 * 不再把所有服务商的模型拍平成一个几十项的长下拉。
 */
@Composable
private fun ToolReviewDecisionModelPref(onOpenPicker: () -> Unit) {
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = null)
    val current = settings ?: return
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val resolved = remember(providers, current.toolReviewDecisionModelId) {
        resolveToolReviewModel(providers, current.toolReviewDecisionModelId)
    }
    val summary = resolved?.let { (provider, model) ->
        "${provider.name} / ${model.displayName.ifBlank { model.modelId }}"
    } ?: "跟随对话模型"
    EtaArrowPreference(
        title = "审查决策模型",
        summary = summary,
        startAction = {
            ModelBrandMark(
                modelId = resolved?.model?.modelId ?: pickerState.selectedModel?.modelId,
                sourceType = resolved?.let { (provider, _) -> ProviderSourceRegistry.resolve(provider) }
                    ?: pickerState.selectedModel?.providerSourceType,
                size = 24.dp,
            )
        },
        onClick = onOpenPicker,
    )
}

/**
 * 决策模型选择面板：与「对话标题模型」「子智能体模型」共用同一套选择器，
 * 只把「跟随对话模型」换成本场景的文案。
 */
@Composable
private fun ToolReviewDecisionModelSheet(show: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = null)
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val storedId = settings?.toolReviewDecisionModelId.orEmpty()
    val resolved = remember(providers, storedId) { resolveToolReviewModel(providers, storedId) }
    val primaryName = pickerState.selectedModel?.displayName
    val followSubtitle = if (primaryName.isNullOrBlank()) {
        "智能档用当前对话的模型做裁决"
    } else {
        "智能档用 $primaryName 做裁决"
    }
    OverlayBottomSheet(
        show = show,
        title = "审查决策模型",
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        OptionalModelPickerContent(
            pickerState = pickerState,
            selectedSecondaryModelId = resolved?.model?.id,
            followTitle = "跟随对话模型",
            followSubtitle = followSubtitle,
            onSelectModel = { modelId ->
                onDismiss()
                // 存内部 UUID：运行时 runtimeConfigFor 走 enabledModel()，按 Model.id 匹配。
                scope.launch {
                    SettingsDataStore.updateSettings { it.copy(toolReviewDecisionModelId = modelId.orEmpty()) }
                }
            },
        )
    }
}

/**
 * 解析已保存的决策模型。
 *
 * 新值存 Model.id（UUID）；旧版本写的是对外 modelId，两种口径都认，
 * 否则升级后老配置会静默回落到主对话模型。
 */
private fun resolveToolReviewModel(
    providers: List<ProviderSetting>,
    storedId: String,
): ProviderModel? {
    if (storedId.isBlank()) return null
    providers.enabledModel(storedId)?.let { return it }
    return providers.asSequence()
        .filter { it.isEnabled }
        .flatMap { provider ->
            provider.models.asSequence()
                .filter { it.isEnabled }
                .map { model -> ProviderModel(provider, model) }
        }
        .firstOrNull { it.model.modelId == storedId }
}

/**
 * 流体云通知降级：把进度卡、锁屏胶囊这类通知退化成普通通知。
 *
 * 原理是这些通知不再申请 promoted ongoing（[android.app.Notification.Builder.setRequestPromotedOngoing]），
 * 系统不给提升，ColorOS 就不会把它放进流体云，只在通知栏按常驻通知显示；
 * 标题、正文、暂停／停止／插入这些按钮都保留。
 */
@Composable
private fun FluidDowngradePref() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = null)
    val current = settings ?: return
    EtaSwitchPreference(
        title = stringResource(R.string.lingscreen_fluid_downgrade),
        summary = stringResource(R.string.lingscreen_fluid_downgrade_summary),
        checked = current.overlayFluidDowngrade,
        onCheckedChange = { value ->
            // 先同步进程内策略，避免刷新时通知仍按旧值构建。
            io.github.mangi.eta.agent.runtime.FluidNotificationPolicy.set(value)
            scope.launch {
                SettingsDataStore.updateSettings { it.copy(overlayFluidDowngrade = value) }
                runCatching {
                    context.startService(
                        Intent(context, io.github.mangi.eta.agent.runtime.AgentRuntimeService::class.java)
                            .setAction(io.github.mangi.eta.agent.runtime.AgentRuntimeService.ACTION_REFRESH_FLUID)
                    )
                }
            }
        },
        startAction = { EtaPreferenceIcon(Icons.Rounded.Notifications, tint = EtaPreferenceColors.Orange) },
    )
}

/**
 * 自动压缩上下文阈值：用百分比档位替代原来的布尔开关。
 *
 * 0 = 关闭，只保留手动压缩；其余档位表示「上下文用掉这么多就自动摘要一次」。
 * 写盘走 [Prefs.putAutoCompactionTriggerPercent]，同一次提交同步旧布尔键，
 * 让仍在运行的旧 Hook 进程与新 Runtime 读到同一个口径。
 */
@Composable
private fun AutoCompactionThresholdPref(prefs: SharedPreferences?) {
    val context = LocalContext.current
    val choices = Prefs.Keys.AUTO_COMPACTION_TRIGGER_PERCENT_CHOICES
    val defaultPercent = Prefs.Keys.AUTO_COMPACTION_TRIGGER_PERCENT_DEFAULT
    var percent by remember(prefs) {
        mutableStateOf(
            prefs?.let { Prefs.autoCompactionTriggerPercent(it) } ?: Prefs.autoCompactionTriggerPercent(),
        )
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == Prefs.Keys.AGENT_AUTO_COMPACTION_TRIGGER_PERCENT) {
                percent = Prefs.autoCompactionTriggerPercent(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val labels = choices.map { choice -> if (choice == 0) "关闭" else "$choice%" }
    val selected = choices.indexOf(percent).takeIf { it >= 0 } ?: choices.indexOf(defaultPercent)
    EtaDropdownPreference(
        title = stringResource(R.string.settings_auto_compaction),
        items = labels.map { label -> DropdownItem(text = label) },
        selectedIndex = selected,
        onSelectedIndexChange = { index ->
            val target = choices.getOrNull(index) ?: return@EtaDropdownPreference
            val targetPrefs = prefs ?: return@EtaDropdownPreference
            if (Prefs.putAutoCompactionTriggerPercent(targetPrefs, target)) {
                percent = target
                if (Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            EtaPreferenceIcon(
                icon = Icons.Rounded.Layers,
                enabled = prefs != null,
                tint = EtaPreferenceColors.Blue,
            )
        },
        enabled = prefs != null,
    )
}

@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    iconTint: Color,
    /** 与本项互斥的开关：对方开启时本项置灰；本项开启时先把对方关掉。 */
    exclusiveKey: String? = null,
) {
    val writable = prefs != null
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    val exclusiveDefault = exclusiveKey?.let { Prefs.Keys.BOOLEAN_DEFAULTS[it] ?: true } ?: false
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    var exclusiveChecked by remember(prefs, exclusiveKey) {
        mutableStateOf(
            exclusiveKey != null && (prefs?.getBoolean(exclusiveKey, exclusiveDefault) ?: false),
        )
    }
    DisposableEffect(prefs, key, exclusiveKey) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
            if (exclusiveKey != null && changedKey == exclusiveKey) {
                exclusiveChecked = changedPrefs.getBoolean(exclusiveKey, exclusiveDefault)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = writable && !exclusiveChecked
    EtaSwitchPreference(
        title = title,
        summary = if (exclusiveChecked) {
            context.getString(R.string.ui_vendor_assistant_exclusive_summary)
        } else {
            summary
        },
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@EtaSwitchPreference
            // 互斥：开启本项前先关掉对方，避免两边同时接管同一条 query。
            if (value && exclusiveKey != null && !putBooleanSync(targetPrefs, exclusiveKey, false)) {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                return@EtaSwitchPreference
            }
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                if (exclusiveKey != null && value) {
                    exclusiveChecked = false
                }
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint)
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    Prefs.putBooleanForUi(prefs, key, value)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "Eta"
    }

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java
    ).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}


/**
 * 设置项搜索索引：一个分组一条。
 *
 * 可搜文本由三部分组成，全部小写后比对：
 * 1. [labels]：该分组在设置页上真实显示的标题（字符串资源）。按当前语言取文案，
 *    所以「页面上看得见的标题」一定搜得到，不会再出现「页面上有、搜索里没有」；
 * 2. [labelsRaw]：少数没走字符串资源的硬编码标题，照抄文案；
 * 3. [keywords]：别名、同义词，以及二级页里的功能名词——搜具体功能也能落到入口分组。
 *
 * 新增分组时同步加一条；漏加也不会被搜索藏起来（未登记的分组默认可见）。
 */
private data class SettingsSearchEntry(
    val sectionKey: String,
    val labels: List<Int> = emptyList(),
    val labelsRaw: List<String> = emptyList(),
    val keywords: String = "",
)

private val SETTINGS_SEARCH_INDEX = listOf(
    SettingsSearchEntry(
        sectionKey = "section_general",
        labels = listOf(
            R.string.settings_general,
            R.string.appearance_title,
            R.string.speech_settings_title,
            R.string.data_backup_title,
        ),
        keywords = "通用 外观 主题 深色 浅色 夜间 跟随系统 monet 取色 色彩 强调色 纯黑 模糊 顶栏 横移 返回 手势 预测性返回 界面缩放 字号 语言 语音 朗读 播报 tts 音色 语速 音调 发音人 语音识别 自动播报 备份 导入 导出 恢复 自动备份 周期 保留份数 备份格式 备份文件夹",
    ),
    SettingsSearchEntry(
        sectionKey = "section_agent",
        labels = listOf(
            R.string.settings_llm_providers,
            R.string.ui_model_provider_e8c7f5,
            R.string.ui_deep_thinking_enabled_by_default_c032d6,
        ),
        keywords = "llm 提供商 供应商 模型 服务商 api 密钥 key 接口 地址 baseurl 思考 深度思考 推理 reasoning 默认启用思考 温度 上下文长度",
    ),
    SettingsSearchEntry(
        sectionKey = "section_capabilities",
        labels = listOf(
            R.string.settings_section_model_capabilities,
            R.string.settings_tools_list,
            R.string.ui_enable_web_browsing_tools_8b6b03,
            R.string.web_tools_settings_title,
            R.string.settings_auto_compaction,
            R.string.ui_linux_tool_environment_314d22,
        ),
        labelsRaw = listOf("工具", "工具能力"),
        keywords = "工具 工具能力 工具档 精简 standard 完整 全部 mcp 网页 搜索 读取 浏览器 browser 抓取 fetch 自动压缩 压缩 上下文长度 审查 工具调用审查 决策模型 审查决策模型 智能档 只读 严格 自动放行 linux 发行版 debian 环境 apk 分析 工作区 共享文件夹",
    ),
    SettingsSearchEntry(
        sectionKey = "section_extensions",
        labels = listOf(
            R.string.settings_section_extensions,
            R.string.ui_memory_b55ff5,
            R.string.automation_title,
            R.string.lingscreen_entry_title,
            R.string.route_subagents,
            R.string.route_skills,
            R.string.route_mcp_servers,
        ),
        keywords = "扩展 自动化 上下文 记忆 memory 记忆注入 自动任务 定时 计划 触发 automation 灵屏 屏幕 小窗 子智能体 子代理 subagent skills 技能 mcp 服务器 server",
    ),
    SettingsSearchEntry(
        sectionKey = "section_persona",
        labels = listOf(
            R.string.settings_section_persona,
            R.string.prompt_studio_section,
            R.string.prompt_studio_title,
            R.string.sidebar_characters,
        ),
        keywords = "人格 提示词 提示词工坊 工坊 工具 边界 系统提示词 自定义提示词 版本 历史 快照 请求快照 预设 导入 导出 完整性 当前生效 注入 角色 角色卡 人设 对比",
    ),
    SettingsSearchEntry(
        sectionKey = "section_permissions",
        labels = listOf(
            R.string.settings_section_permissions_devices,
            R.string.ui_floating_window_permissions_076b77,
            R.string.ui_accessibility_enhancement_tools_8fd257,
            R.string.ui_enforce_accessibility_55e838,
            R.string.lingscreen_fluid_pinned,
            R.string.lingscreen_fluid_downgrade,
            R.string.ui_enable_device_direct_tools_e2d595,
            R.string.ui_allow_reading_of_sensitive_device_information_feaec0,
            R.string.ui_allow_sensitive_device_operation_3d42ea,
            R.string.ui_enable_terminal_file_tools_18bb43,
        ),
        keywords = "权限 授权 设备能力 无障碍 增强工具 强制 保持 悬浮窗 悬浮 通知 提醒 流体云 常驻 降级 胶囊 状态栏 实时活动 live activity 后台 自启动 电量 设备 直达 敏感 设备信息 设备操作 终端 shell 命令行 命令 文件 file",
    ),
    SettingsSearchEntry(
        sectionKey = "section_system_integration",
        labels = listOf(
            R.string.settings_section_system_integration,
            R.string.capability_enhancements,
            R.string.ui_system_assistant_takes_over_f46043,
            R.string.ui_eta_system_assistant_003e9b,
            R.string.ui_long_press_the_power_button_1958d0,
            R.string.ui_automatically_set_default_assistant_f86963,
            R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a,
            R.string.ui_enable_vendor_assistant_custom_models_c8e465,
            R.string.ui_only_take_over_with_agent_prefix_d17556,
        ),
        keywords = "系统集成 系统增强 能力增强 root 授权 超级用户 权限 magisk kernelsu ksu 框架 lsposed xposed 模块 作用域 语音唤醒 系统化 系统应用 gemini 一圈即搜 无障碍保护 受保护数据 系统助手 接管 助手 assistant 数字助理 默认助理 电源键 长按 语音助手 唤醒 唤醒词 默认应用 拨号 厂商助手 兼容 入口 小布 小爱 超级小爱 vivo 蓝心 蓝心小v oppo 自定义模型 前缀 agent 前缀 语音入口 侧键",
    ),
    SettingsSearchEntry(
        sectionKey = "section_accounts",
        labels = listOf(
            R.string.settings_section_accounts,
            R.string.route_github_account,
        ),
        keywords = "账号 连接 登录 github 账号 仓库 令牌 token oauth 凭据 授权 断开",
    ),
    SettingsSearchEntry(
        sectionKey = "section_diagnostics",
        labels = listOf(
            R.string.settings_section_diagnostics,
            R.string.settings_self_check_title,
            R.string.settings_diagnostics_title,
            R.string.settings_test_privacy_title,
            R.string.diag_patrol_group_title,
            R.string.diag_patrol_title,
        ),
        keywords = "诊断 维护 自检 体检 一致性 诊断与日志 日志 崩溃 诊断包 导出 分享 极致自动排查 自动排查 巡检 排查 定时巡检 周期 保留 份数 提醒 异常 报告 测试 隐私 同意 训练 撤回",
    ),
    SettingsSearchEntry(
        sectionKey = "section_about",
        labels = listOf(
            R.string.ui_about_bed172,
            R.string.usage_stats_title,
            R.string.data_usage_title,
        ),
        keywords = "关于 版本 版本号 更新 开源 许可 协议全文 用量 统计 token 消耗 花费 数据占用 清理 数据库 缓存 大小 存储",
    ),
)

/** 把每个分组「能搜到的文本」预拼成小写串；标题按当前语言取，随语言切换重建。 */
@Composable
private fun rememberSettingsSearchHaystacks(): Map<String, String> {
    val context = LocalContext.current
    return remember(context) {
        SETTINGS_SEARCH_INDEX.associate { entry ->
            entry.sectionKey to buildString {
                append(entry.keywords.lowercase())
                entry.labels.forEach { append('\u0000').append(context.getString(it).lowercase()) }
                entry.labelsRaw.forEach { append('\u0000').append(it.lowercase()) }
            }
        }
    }
}

/** 设置页顶部搜索框：占位提示 + 清除按钮 + 无结果提示。 */
@Composable
private fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    emptyResult: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_search_hint),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "×",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.clickable { onQueryChange("") },
                )
            }
        }
        if (emptyResult) {
            Text(
                text = stringResource(R.string.settings_search_empty),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

