package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentResourceGate
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.ConversationMemoryPins
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentExecutor
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.model.enabledModelByApiId
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillAuthoringService
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.tool.ToolReviewArbiter
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Root 探测等待上限；超时按「权限未确认」继续，避免整次运行无限挂起。 */
private const val ROOT_PROBE_WAIT_TIMEOUT_MS = 8_000L

/** 超长工具结果的落盘目录（相对 cacheDir）与单个 run 目录的文件数上限。 */
private const val TOOL_OUTPUT_DIR = "agent-tool-output"
private const val TOOL_OUTPUT_MAX_FILES = 64

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
    private val reviewGeneration: Long? = null,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        // 资源闸门的生产侧接线（放在 runtime 包：它本来就依赖 agent/display，model 包不反向依赖）：
        // ① 屏幕槽按「该会话有没有活跃灵屏」分槽——灵屏是按 owner 多开的，两个会话各自的屏
        //    不该挤在 SCREEN:main 里互相排队；② 每次运行开始刷新总开关，改 Prefs 后下一个 run 生效。
        AgentResourceGate.screenSlotOf = { conversation ->
            if (conversation.isNotBlank() && VirtualScreenSession.isActive(conversation)) {
                "ls:$conversation"
            } else {
                "main"
            }
        }
        AgentResourceGate.refreshEnabledFromPrefs()
        val archivedEvents = mutableListOf<AgentEvent>()
        // 归档事件只对「外部入口 handoff」的 run 有消费者（AgentRunArchiveStore → importExternalRun
        // 逐条回放事件补出正文）；其余 run 的归档在 persistArchivedRun 里立即丢弃。
        // 这些 run 不再收文本增量，流式期同一条正文不必在内存里躺两遍。
        val archiveDeltaEvents = request.handoff
            ?.let { handoff -> AgentExternalArchivePayload.from(handoff.payload) } != null
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var roleplayRun = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)
        AgentPerfTrace.trace(
            "perf.run.begin",
            "runId=${request.runId.take(12)} op=${request.operation} " +
                "history=${request.history.size} prompt=${request.prompt.length} " +
                "images=${request.images.size} plan=${request.planMode}",
        )

        /** 准备阶段各节点的单调时钟起点：只看相邻两行的差值就能定位慢在哪一步。 */
        fun stageMark(): Long = AgentPerfTrace.elapsedNow()

        // 失败清理需要在 try 外拿到会话 id。
        var memoryConversationId: String? = null
        val result = try {
            val contextWindow = request.config.knownContextWindow
            var mark = stageMark()
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            timing.stage("checkpoint", mark)
            mark = stageMark()
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            timing.stage("entry_surface", mark)
            mark = stageMark()
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            timing.stage("skills", mark)
            mark = stageMark()
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            memoryConversationId = conversationId
            timing.stage("ui_payload", mark)
            mark = stageMark()
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, contextWindow, memoryEnabled) }
            }
            roleplayRun = roleplayContext != null
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    EtaDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val characterMemoryTools = roleplayContext?.let { roleplay ->
                CharacterMemoryTools(appContext, roleplay.characterId) {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                }
            }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    ConversationMemoryPins.contextFor(
                        conversationId = conversationId,
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            timing.stage("memory_context", mark)
            mark = stageMark()
            if (request.config.terminalTools) {
                // 启动探测由 App 拥有；模型首轮等待其结果，避免把检测中的授权当成普通设备。
                val rootProbeSettled = runBlocking {
                    withTimeoutOrNull(ROOT_PROBE_WAIT_TIMEOUT_MS) {
                        while (io.github.mangi.eta.agent.device.RootAccess.state.value.isChecking) {
                            runController.throwIfCancelled()
                            kotlinx.coroutines.delay(100)
                        }
                        true
                    } ?: false
                }
                if (!rootProbeSettled) {
                    AgentPerfTrace.trace(
                        "perf.prepare.root_probe_timeout",
                        "waited_ms=${ROOT_PROBE_WAIT_TIMEOUT_MS}",
                    )
                    AndroidAgentLogger.warnThrottled("root_probe_wait_timeout") {
                        "Root probe still checking after ${ROOT_PROBE_WAIT_TIMEOUT_MS}ms; continuing with unconfirmed permissions"
                    }
                }
            }
            timing.stage("root_probe", mark)
            mark = stageMark()
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            timing.stage("mcp_snapshot", mark)
            mark = stageMark()
            // 工具调用审查「智能」档的 LLM 裁决器：优先用 toolReviewDecisionModelId 指定的决策模型，
            // 未指定回落主对话模型；裁决失败/超时一律放行，不新增阻断面。
            // 决策模型 ID 有两种历史口径：新值存 Model.id（UUID），旧版本存对外 modelId。
            // 这里先归一到 Model.id 再交给 runtimeConfigFor，既修掉老配置永远匹配不上、
            // 静默回落主对话模型的问题，也保留它内部的 ensureBuiltInsMerged 与默认模型兜底语义。
            val reviewArbiter = ToolReviewArbiter(
                config = runBlocking {
                    val decisionModelId =
                        io.github.mangi.eta.data.datastore.SettingsDataStore.settings().toolReviewDecisionModelId
                    if (decisionModelId.isBlank()) {
                        request.config
                    } else {
                        io.github.mangi.eta.data.repository.ProviderRepository.ensureBuiltInsMerged()
                        val providers = io.github.mangi.eta.data.repository.ProviderRepository.allProviders()
                        val canonicalId = providers.enabledModel(decisionModelId)?.model?.id
                            ?: providers.enabledModelByApiId(decisionModelId)?.model?.id
                            ?: decisionModelId
                        RuntimeConfigRepository.runtimeConfigFor(canonicalId) ?: request.config
                    }
                },
            )
            timing.stage("review_arbiter", mark)
            mark = stageMark()
            val executor = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                virtualScreenOwner = request.virtualScreenOwner,
                isRunCancelled = { runController.isCancelled },
                // 上游 36b1c77f：read_image 等图片摄入按当前渠道预算编码（Anthropic 3.5 MB / 其它 8 MB）。
                imageBudgetProvider = {
                    io.github.mangi.eta.agent.media.ModelImageBudget.forProviderType(request.config.providerType)
                },
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                memoryWritable = roleplayContext == null,
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeToolExecution = { toolName ->
                    val automated =
                        io.github.mangi.eta.agent.automation.AgentTaskScheduler.isRegistered(request.runId)
                    // 自动任务没有前台入口：另建任务与操作主屏都没有依据。
                    // 但 shell、文件、检索、记忆这类不碰界面的工具与"没有前台"无关，不能一起禁掉
                    // （曾把 terminal 混在这条里，导致自动任务里连 `bash 脚本` 都发不出去）。
                    val automationRejection = when {
                        !automated -> null
                        toolName in io.github.mangi.eta.agent.automation.AgentTaskTools.names ->
                            ToolExecutionDecision.Reject(
                                "AUTOMATION_TASK_TOOL_UNAVAILABLE",
                                "自动任务不能创建或修改其他自动任务；本次调用未执行",
                            )
                        !session.mainScreenFallbackApproved.get() &&
                            (AgentToolRequirements.requiresAccessibility(toolName) ||
                                AgentOverlayVisibilityPolicy.isForegroundOperationTool(toolName) ||
                                toolName == "browser_use") ->
                            ToolExecutionDecision.Reject(
                                "AUTOMATION_FOREGROUND_UNAVAILABLE",
                                "自动任务没有前台入口，操作主屏需要用户批准；请改用灵屏工具，或先从前台发起",
                            )
                        else -> null
                    }
                    if (automationRejection != null) {
                        automationRejection
                    } else {
                        val requiresAccessibility =
                            AgentToolRequirements.requiresAccessibility(toolName)
                        if (
                            !requiresAccessibility &&
                            !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                        ) {
                            ToolExecutionDecision.Allow
                        } else {
                            val accessibility = if (requiresAccessibility) {
                                AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                            } else {
                                null
                            }
                            when {
                                accessibility != null && !accessibility.available ->
                                    ToolExecutionDecision.Reject(
                                        code = accessibility.code,
                                        message = accessibility.message,
                                    )
                                entrySurfaceGuard?.dismissOnce() == false ->
                                    ToolExecutionDecision.Reject(
                                        code = "ENTRY_SURFACE_NOT_READY",
                                        message = "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                                    )
                                else -> ToolExecutionDecision.Allow
                            }
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
                skillAuthoringService = SkillAuthoringService(skillIndexService, skillPackageInstaller),
                toolReviewArbiter = reviewArbiter::decide,
                onMainScreenFallback = { session.mainScreenFallbackApproved.set(true) },
                onVirtualTaskCancelled = { session.cancel("用户取消灵屏任务") },
            )
            timing.stage("local_tools", mark)
            mark = stageMark()
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            timing.stage("routing_executor", mark)
            mark = stageMark()
            // 手动压缩复用主会话工具目录以保持 Provider 前缀缓存，必须暴露与 OP_CHAT 一致的 conversation_history。
            val historyTool = if (
                request.operation == AgentRuntimeWire.OP_CHAT ||
                request.operation == AgentRuntimeWire.OP_COMPACT
            ) {
                val id = conversationId
                if (id != null) {
                    ConversationHistoryTool {
                        val checkpoint = runBlocking { EtaDatabase.get(appContext).conversationDao().contextCheckpoint(id) }
                        val journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson)
                            .ifEmpty { AgentConversationCodec.decodeTranscript(checkpoint?.historyJson) }
                        journal + session.transcript
                    }
                } else {
                    val baseHistory = request.history + listOfNotNull(
                        request.prompt.takeIf { it.isNotBlank() || request.images.isNotEmpty() }?.let { prompt ->
                            AgentModelClient.buildUserHistoryMessage(prompt, request.images)
                                .copy(messageId = "user-${request.runId}")
                        },
                    )
                    ConversationHistoryTool { baseHistory + session.transcript }
                }
            } else null
            val subagentAllowed = {
                request.config.subagentTools && currentPermissions().subagentTools
            }
            val subagentExecutor = SubagentExecutor(
                // 子代理会话按「对话」隔离；无对话（外部入口）时退回 runId。
                conversationKey = conversationId ?: request.runId,
                parentController = runController,
                configProvider = {
                    val rawSubagentConfig = runBlocking {
                        RuntimeConfigRepository.subagentRuntimeConfig(request.config)
                    }
                    AgentRuntimePolicy.constrain(rawSubagentConfig, currentPermissions())
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext).copy(planMode = request.planMode) },
                configForOverride = { modelId, effort ->
                    val overridden = RuntimeConfigRepository.subagentRuntimeConfig(
                        request.config, overrideModelId = modelId, overrideEffortWire = effort,
                    )
                    AgentRuntimePolicy.constrain(overridden, currentPermissions())
                },
                baseToolExecutor = routingExecutor,
                skillContext = skillContext,
                memoryContext = memoryContext,
                mcpTools = mcpTools,
                executionContext = executor.terminalExecutionContext,
                onProgress = { progressEvent ->
                    acceptEvent(
                        session,
                        progressEvent,
                        archivedEvents,
                        archiveDeltaEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                },
            )
            val runTools = JSONArray(mcpTools.toString()).also { tools ->
                if (subagentAllowed()) {
                    // 把用户勾选的授权模型写进工具描述，否则主 Agent 根本不知道有哪些可选。
                    val subagentCfg = runCatching {
                        runBlocking { io.github.mangi.eta.data.datastore.SettingsDataStore.subagentSettings() }
                    }.getOrNull()
                    val authorized = if (subagentCfg?.allowModelSelection == true) {
                        subagentCfg.effectiveGrants.mapNotNull { grant ->
                            val resolved = runCatching {
                                runBlocking {
                                    io.github.mangi.eta.data.repository.ProviderRepository.ensureBuiltInsMerged()
                                    val all = io.github.mangi.eta.data.repository.ProviderRepository.allProviders()
                                    all.enabledModelByApiId(grant.modelId) ?: all.enabledModel(grant.modelId)
                                }
                            }.getOrNull() ?: return@mapNotNull null
                            val selectable = resolved.model.reasoningCapabilities
                                ?.selectableEfforts?.map { it.wireValue }.orEmpty()
                            val efforts = if (grant.allowedEfforts.isEmpty()) {
                                selectable
                            } else {
                                selectable.filter { it in grant.allowedEfforts }
                            }
                            // 主 Agent 只认得对外 modelId（如 cn:hy3），不能给它内部 UUID。
                            io.github.mangi.eta.agent.subagent.SubagentModelOption(
                                modelId = resolved.model.modelId,
                                efforts = efforts,
                                contextWindow = resolved.model.contextWindowOverride
                                    ?: resolved.model.contextWindow,
                                supportsThinking = resolved.model.reasoning == true,
                            )
                        }
                    } else {
                        emptyList()
                    }
                    AgentSubagentToolCatalog.appendSchemas(tools, authorizedModels = authorized)
                }
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name in AgentSubagentToolCatalog.names) {
                    if (!subagentAllowed()) {
                        AgentModelClient.ToolResult(
                            JSONObject()
                                .put("ok", false)
                                .put("code", "SUBAGENT_DISABLED")
                                .put("message", "Subagent 权限已关闭")
                                .toString(),
                        )
                    } else if (call.name == AgentSubagentToolCatalog.PROFILE_TOOL_NAME) {
                        subagentExecutor.executeProfileManagement(call)
                    } else {
                        subagentExecutor.execute(call.round, call)
                    }
                } else if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    characterMemoryTools.execute(call)
                } else routingExecutor.execute(call).also { result ->
                    if (call.name == "memory_write") {
                        runCatching { JSONObject(result.content) }.getOrNull()
                            ?.takeIf { it.optBoolean("ok") }
                            ?.let { ConversationMemoryPins.recordOwnWrite(conversationId, it.optString("revision")) }
                    }
                }
            }
            // 专家可以覆盖模型：指定了 provider+model 就用它跑这一轮；
            // 解析失败（模型被删/停用）时退回会话配置，不因为一个过期字段让整轮发不出去。
            val expert = io.github.mangi.eta.agent.expert.ExpertStore.active()
            val runConfig = if (expert != null && !expert.inheritsModel) {
                runBlocking {
                    RuntimeConfigRepository.expertRuntimeConfig(request.config, expert.providerId, expert.modelId)
                }
            } else {
                request.config
            }
            timing.stage("run_tools_and_config", mark)
            AgentPerfTrace.trace(
                "perf.loop.begin",
                "runId=${request.runId.take(12)} prepare_ms=${AgentPerfTrace.since(timing.runStartElapsed)} " +
                    "tools=${runTools.length()} msgs=${request.history.size}",
            )
            val completedResponse = AgentModelClient.complete(
                config = runConfig,
                sessionId = request.effectiveModelSessionId,
                // 并发闸门的会话键与子代理同一个表达式：同对话的工具串行，跨对话按资源并行。
                conversationKey = conversationId ?: request.runId,
                operationId = request.runId,
                initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                roleplayContext = roleplayContext,
                rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                onContextSnapshot = { snapshot ->
                    // 压缩会把历史里的记忆写入摘要掉，下一轮需要重新注入最新记忆。
                    ConversationMemoryPins.forget(conversationId)
                    val committed = snapshot.copy(operationId = request.runId)
                    AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                    session.updateContext(committed)
                },
                onTranscript = { transcript ->
                    AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                    session.updateTranscript(transcript)
                },
                capabilitiesProvider = { executor.capabilitiesForRun(AgentToolCapabilities.capture(appContext).copy(planMode = request.planMode)) },
                prompt = request.prompt,
                assistantScreenContext = request.assistantScreenContext,
                toolExecutor = runToolExecutor,
                images = request.images,
                history = request.history,
                runController = runController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = runTools,
                executionContext = executor.terminalExecutionContext,
                toolResultSpill = { toolName, toolCallId, content ->
                    spillToolResult(appContext, request.runId, toolName, toolCallId, content)
                },
            ) { event ->
                timing.accept(event)
                acceptEvent(
                    session,
                    event,
                    archivedEvents,
                    archiveDeltaEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                )
            }
            response = completedResponse
            runController.throwIfCancelled()
            executor.retainVirtualScreenOnSuccess()
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            // 失败时历史是否完整落盘不确定，放弃固定的记忆快照；记忆未变时重新注入的内容逐字节相同。
            ConversationMemoryPins.forget(memoryConversationId)
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                val causeChain = generateSequence(throwable.cause) { cause -> cause.cause }
                    .take(3)
                    .joinToString("<-") { cause -> cause.safeLogType() }
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}, " +
                        "cause_chain=$causeChain, run_id=${request.runId}"
                )
                io.github.mangi.eta.core.EtaTrace.log(
                    "run.fail",
                    "runId=${request.runId} type=${throwable.safeLogType()} " +
                        "model_code=${requestFailure?.code.orEmpty()} cause=$causeChain",
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        archiveDeltaEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
            // 收尾冲刷：onTranscript 走增量节流，最后一次不足阈值的小尾巴不会落库，
            // 崩溃恢复时就会缺最近这一段。这里用会话里的最终 transcript 强制写一次。
            // 只冲刷、不改变返回值，失败只留痕（收尾阶段不应因持久化问题覆盖真实运行结果）。
            runCatching {
                AgentRunCheckpointStore.saveTranscript(
                    appContext,
                    request.runId,
                    session.transcript,
                    force = true,
                )
            }.onFailure { failure ->
                AndroidAgentLogger.warn(
                    "Agent runtime final transcript flush failed: type=${failure.safeLogType()}, " +
                        "run_id=${request.runId}"
                )
            }
        }

        if (cancelled && session.isTerminal) {
            runCatching {
                persistArtifacts(snapshotRequest(request), result, snapshotArchivedEvents(archivedEvents))
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, result, snapshotArchivedEvents(archivedEvents)) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        val reviewEpoch = reviewGeneration
        if (!committed) {
            // complete() 在会话已被 cancel/替换（state 非 RUNNING）时返回 false：结果不能再发
            // 第二次，也不能把这个 run 投影回运行中；留一条日志供对账。
            AndroidAgentLogger.info(
                "Agent runtime result not published: session already terminal, run_id=${request.runId}"
            )
        }
        if (committed && result.ok && !cancelled && !roleplayRun && reviewEpoch != null) {
            response?.let { completed ->
                runCatching { AgentExperienceReview.submit(appContext, reviewEpoch, completedRequest, completed, archivedEvents.toList()) }
                    .onFailure { error -> AndroidAgentLogger.warn("Experience review submission failed: type=${error.safeLogType()}") }
            }
        }
        return Outcome(
            result = result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    /**
     * 超长工具结果落到应用缓存目录，返回全文绝对路径。
     *
     * 会话里只留头尾分段，全文留在磁盘：这是 2026-11-22 堆爆那条链路的收口点
     * （同一条 `get_logcat` 几万字符会在 messages、transcript、checkpoint、子代理历史里各存一份）。
     * 写失败不静默：节流记一条 warn，循环侧拿不到路径就只在截断说明里写「全文未落盘」。
     */
    private fun spillToolResult(
        context: Context,
        runId: String,
        toolName: String,
        toolCallId: String,
        content: String,
    ): String? {
        val dir = java.io.File(java.io.File(context.cacheDir, TOOL_OUTPUT_DIR), runId.take(48))
        return try {
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                AndroidAgentLogger.warnThrottled("agent_tool_spill_dir_failed") {
                    "Tool result spill directory unavailable: runId=$runId"
                }
                return null
            }
            pruneToolOutput(dir)
            val safeCallId = toolCallId
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(64)
                .ifBlank { "tool" }
            val file = java.io.File(dir, "$safeCallId.txt")
            file.writeText(content, Charsets.UTF_8)
            AndroidAgentLogger.debug {
                "Tool result spilled: name=$toolName chars=${content.length} path=${file.absolutePath}"
            }
            file.absolutePath
        } catch (failure: java.io.IOException) {
            AndroidAgentLogger.warnThrottled("agent_tool_spill_failed") {
                "Tool result spill failed: name=$toolName type=${failure.safeLogType()}"
            }
            null
        }
    }

    /** 单个 run 目录里的落盘文件按数量兜底，避免长会话把缓存目录堆满。 */
    private fun pruneToolOutput(dir: java.io.File) {
        val files = dir.listFiles() ?: return
        if (files.size < TOOL_OUTPUT_MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - TOOL_OUTPUT_MAX_FILES + 1)
            .forEach { stale -> stale.delete() }
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>,
        archiveDeltaEvents: Boolean,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        // 多 Subagent 并发会跨线程回调 acceptEvent；只缓存可落盘事件，避免瞬态进度堆积内存。
        if (archivedEvents != null) {
            event.recoveryProjection()?.let { durable ->
                synchronized(archivedEvents) {
                    if (archiveDeltaEvents || durable !is AgentEvent.AssistantBlockDelta) {
                        archivedEvents.add(durable)
                    }
                }
            }
        }
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }
    private fun snapshotArchivedEvents(archivedEvents: MutableList<AgentEvent>?): List<AgentEvent> {
        if (archivedEvents == null) return emptyList()
        return synchronized(archivedEvents) { archivedEvents.toList() }
    }
}
