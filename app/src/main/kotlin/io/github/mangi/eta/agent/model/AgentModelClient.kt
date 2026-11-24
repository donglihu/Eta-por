package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.PromptCustomizationStore
import io.github.mangi.eta.agent.prompt.PromptJailbreakStore
import io.github.mangi.eta.agent.expert.ExpertStore
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.PlanModePolicy
import io.github.mangi.eta.core.StableLogType
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    /** 单条计划步骤的字符上限：面板一行显示，超长没有意义，还会把上下文撑起来。 */
    private const val MAX_PLAN_STEP_CHARS = 300
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val traceFormatter = AgentTraceFormatter()

    fun loadConfig(): ModelConfig {
        val runtimeJson = Prefs.getString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
        if (runtimeJson.isNotBlank()) {
            runCatching {
                json.decodeFromString<ModelConfig>(runtimeJson)
            }.getOrNull()?.let { runtime ->
                // 思考是否可用不再由 AGENT_THINKING_ENABLED 硬门控：对话内可随时改档，开关只决定默认档位。
                val effort = runtime.effectiveReasoningEffort
                return runtime.copy(
                    terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                    browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                    deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                    deviceSensitiveReadTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                    deviceSensitiveActionTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                    thinkingEnabled = effort.enablesReasoning,
                    reasoningEffort = effort,
                    autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
                    autoCompactionTriggerPercent = Prefs.autoCompactionTriggerPercent(),
                    subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
                )
            }
        }
        return ModelConfig(
            providerId = "builtin-openai",
            providerName = "OpenAI",
            providerType = ProviderTypes.OPENAI_COMPATIBLE,
            providerSourceType = ProviderSourceRegistry.resolve(
                providerId = "builtin-openai",
                baseUrl = "https://api.openai.com/v1",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-5.5",
            modelDisplayName = "GPT-5.5",
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            autoCompactionTriggerPercent = Prefs.autoCompactionTriggerPercent(),
            subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinkingEnabled = Prefs.defaultReasoningEffort().enablesReasoning,
            reasoningEffort = Prefs.defaultReasoningEffort(),
        )
    }

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        history: List<ConversationMessage> = emptyList(),
        provider: AgentProviderClient = ProviderClientFactory.getClient(config),
        runController: AgentRunController = AgentRunController(),
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        additionalTools: JSONArray = JSONArray(),
        executionContext: TerminalExecutionContext? = null,
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        sessionId: String = java.util.UUID.randomUUID().toString(),
        /** 并发闸门的会话键：默认沿用 sessionId；跨对话入口传对话 id，让同对话的工具串行。 */
        conversationKey: String = sessionId,
        compactOnly: Boolean = false,
        operationId: String = sessionId,
        initialUserMessageId: String = "user-$operationId",
        initialSupplementIndex: Int = 0,
        roleplayContext: RoleplayRunContext? = null,
        rewriteReply: Boolean = false,
        assistantScreenContext: String = "",
        toolFilter: ((JSONArray) -> JSONArray)? = null,
        onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
        onTranscript: (List<ConversationMessage>) -> Unit = {},
        restrictedToolNames: Set<String>? = null,
        /** 超长工具结果的落盘钩子；返回全文绝对路径，null 表示不落盘。 */
        toolResultSpill: ((toolName: String, toolCallId: String, content: String) -> String?)? = null,
        /** `update_plan` 是否可用：只有主会话的计划面板算数，子代理与复盘不得覆盖它。 */
        planToolsEnabled: Boolean = true,
        onEvent: (AgentEvent) -> Unit = {}
    ): ModelResponse.Text {
        config.validate()
        // 自定义提示词与工具白名单是正式配置：每次 run 开始读一次，避免轮内改动让前缀反复失效。
        val customization = PromptCustomizationStore.load()
        val jailbreak = PromptJailbreakStore.load()
        val activeExpert = ExpertStore.active()
        val toolPolicy = AgentToolPolicyStore.load()
        val initialCapabilities = capabilitiesProvider()
        val messages = AgentPromptBuilder.buildInitialMessages(
            config,
            prompt,
            images,
            history,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            roleplayContext = roleplayContext,
            executionContext = executionContext,
            virtualScreenEnabled = initialCapabilities.virtualScreenEnabled,
            virtualScreenFallbackEnabled = initialCapabilities.virtualScreenFallbackEnabled,
            customization = customization,
            planMode = initialCapabilities.planMode,
            jailbreak = jailbreak,
            expert = activeExpert,
        )
        if (rewriteReply) {
            messages.put(messages.length() - 1, AgentConversationCodec.userTextMessage(
                "请只改写下面这条角色回复，保持已有事实与实际工具结果，以当前角色设定改善表达。" +
                    "这不是重新执行任务；不得调用任何工具、重读设备、更新记忆或编造缺失证据。只输出替代正文。\n" +
                    "<reply_to_rewrite>\n$prompt\n</reply_to_rewrite>",
            ))
        } else if (!compactOnly) {
            messages.getJSONObject(messages.length() - 1).put("_eta_message_id", initialUserMessageId)
            AssistantScreenContextProjection.attach(messages.getJSONObject(messages.length() - 1), assistantScreenContext)
        }
        if (!compactOnly && config.providerType == ProviderTypes.GEMINI) {
            GeminiEphemeralState.markRunStart(messages.getJSONObject(messages.length() - 1))
        }
        if (compactOnly) messages.remove(messages.length() - 1)
        val transcript = JSONArray()
        // 旧 history 中的无效消息可能在组装时被跳过，系统边界不能由 history 条数倒推。
        val systemCount = AgentPromptBuilder.buildSystemMessages(
            config, skillContext, memoryContext, initialCapabilities.rootAvailable, roleplayContext, executionContext, initialCapabilities.virtualScreenEnabled, initialCapabilities.virtualScreenFallbackEnabled, customization, initialCapabilities.planMode, jailbreak, activeExpert,
        ).length()
        // MCP 工具只在 run 开始时压一次：每轮重复解析这 190KB JSON 纯属浪费。
        val remoteToolNames = (0 until additionalTools.length()).mapNotNull { index ->
            additionalTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
                ?.takeIf { it.isNotBlank() }
        }.toSet()
        val compactedAdditionalTools = if (additionalTools.length() == 0) {
            additionalTools
        } else {
            JSONArray(additionalTools.toString()).also { AgentToolSchemaCompactor.compactMcp(it) }

        }
        fun toolsFor(capabilities: AgentToolCapabilities): JSONArray {
            val built = AgentToolDirectory.build(
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                deviceDirectTools = config.deviceDirectTools,
                deviceSensitiveReadTools = config.deviceSensitiveReadTools,
                deviceSensitiveActionTools = config.deviceSensitiveActionTools,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = memoryContext.enabled,
                memoryWritable = roleplayContext == null,
                capabilities = capabilities,
                localWebSearch = !config.usesHostedWebSearch,
                localProfile = LocalToolProfileStore.current(config.providerId, config.model),
                policy = toolPolicy,
                additionalTools = compactedAdditionalTools,
                restrictedToolNames = restrictedToolNames,
                rewriteReply = rewriteReply,
            )
            // 子代理用 toolFilter 在父工具目录上裁剪；主运行不传，走原样。
            // 不做 plan 阶段的目录过滤：原生只读边界已下放到审批闸门 + 提示词，
            // 目录少一个工具并不等于拦住写入，反而让 Runtime 与界面的模型见过的工具不一致。
            val filtered = toolFilter?.invoke(built) ?: built
            // 专家工具集：白名单为空表示继承全量，黑名单优先；声明与执行用同一个函数，
            // 避免「目录里没了但真调用还能跑」的两套口径。
            return activeExpert?.let { expert -> expertFilterTools(expert, filtered) } ?: filtered
        }
        val tools = toolsFor(initialCapabilities)
        // 只在请求侧丢最老轮次：数据库与界面一条不删；系统提示、记忆与当前输入永不裁。
        val trimOutcome = if (rewriteReply || compactOnly) AgentHistoryTrimmer.Outcome() else
            AgentHistoryTrimmer.trimInPlace(
                messages = messages,
                systemCount = systemCount,
                contextWindow = config.knownContextWindow,
                reservedTokens = AgentHistoryTrimmer.estimateTokens(tools.toString()),
            )
        if (trimOutcome.omittedMessages > 0) {
            onEvent(AgentEvent.HistoryTrimmed(trimOutcome.omittedMessages, trimOutcome.omittedUserRounds))
        }
        onEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        // 工具目录与系统提示都是请求前缀，按轮重算会让一次瞬时状态抖动废掉整段缓存。
        // Root 授权真正变化时整体重建；其余能力只增不减：短暂不可用的工具仍保留，执行时自行报告错误码。
        var promptRootAvailable = initialCapabilities.rootAvailable
        var publishedTools = tools
        var promptVirtualScreenEnabled = initialCapabilities.virtualScreenEnabled
        var promptVirtualScreenFallbackEnabled = initialCapabilities.virtualScreenFallbackEnabled
        fun toolsForRound(): JSONArray {
            val capabilities = capabilitiesProvider()
            if (capabilities.rootAvailable != promptRootAvailable ||
                capabilities.virtualScreenEnabled != promptVirtualScreenEnabled ||
                capabilities.virtualScreenFallbackEnabled != promptVirtualScreenFallbackEnabled) {
                val systemMessages = AgentPromptBuilder.buildSystemMessages(
                    config, skillContext, memoryContext, capabilities.rootAvailable, roleplayContext, executionContext, capabilities.virtualScreenEnabled, capabilities.virtualScreenFallbackEnabled, customization, capabilities.planMode, jailbreak, activeExpert,
                )
                for (index in 0 until systemMessages.length()) {
                    messages.put(index, systemMessages.getJSONObject(index))
                }
                promptRootAvailable = capabilities.rootAvailable
                promptVirtualScreenEnabled = capabilities.virtualScreenEnabled
                promptVirtualScreenFallbackEnabled = capabilities.virtualScreenFallbackEnabled
                publishedTools = toolsFor(capabilities)
                return publishedTools
            }
            val published = toolNames(publishedTools)
            val added = toolsFor(capabilities).let { fresh ->
                (0 until fresh.length()).map(fresh::getJSONObject).filter { toolName(it) !in published }
            }
            if (added.isNotEmpty()) {
                publishedTools = JSONArray(publishedTools.toString()).also { merged -> added.forEach(merged::put) }
            }
            return publishedTools
        }
        // 计划闸门：Plan 模式下，本轮提交计划清单之前不许动副作用工具。
        // 只在「plan 模式 + 主会话 + 正常对话」时生效；重写回复、压缩、子代理都不参与。
        // 它不裁剪工具目录——工具照常声明，只是把「先计划」从口头约定变成确定性行为。
        val planGateActive = initialCapabilities.planMode && planToolsEnabled && !rewriteReply && !compactOnly
        val planGate = io.github.mangi.eta.agent.tool.PlanGate(active = planGateActive)
        // 运行中用户补发指令＝新的一轮：重新武装闸门。
        // 以前闸门一个 run 只武装一次，第二轮起的插话都会绕过「先计划」直接动手，
        // 表现就是「开着 Plan 模式，多轮对话后不生效」。
        //
        // 2026-11-20 用户反馈「任务待办列表没有显示」：这里原来还会下发
        // AgentEvent.PlanUpdated("") 把面板清空——执行阶段每插一句话，
        // 用户正在看的执行清单就被抹掉，模型不重新交清单就一直空着。
        // 闸门重新武装是安全语义（副作用工具仍要等新清单），保留；
        // 清 UI 是纯装饰，删掉：面板上的旧清单等新一轮 update_plan 自然替换，
        // 新 run 的清空仍由 launchConversationRun 的 planSteps = emptyList() 负责。
        fun armPlanForNewUserTurn() {
            planGate.armForNewUserTurn()
        }
        val loop = AgentLoop(
            transcript = transcript,
            systemCount = systemCount,
            operationId = operationId,
            onContextSnapshot = if (rewriteReply) ({ _ -> }) else onContextSnapshot,
            onTranscript = onTranscript,
            sessionId = sessionId,
            conversationKey = conversationKey,
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = ToolExecutor { call ->
                if (call.name == PlanModePolicy.PLAN_TOOL_NAME) {
                    // 两道门：① 子代理/复盘不得覆盖主会话面板；② 本 run 压根没开计划模式。
                    // ② 是 2026-11-22 补的：工具目录已经不再声明 update_plan，但模型可能凭历史
                    // 记忆或 MCP 同名工具硬调，这里必须同样拒绝，否则非计划会话仍会冒出计划面板。
                    if (!planToolsEnabled) {
                        ToolResult(
                            JSONObject().put("ok", false).put("code", "PLAN_TOOL_DISABLED")
                                .put("message", "计划清单只由主会话维护，本次调用未生效。").toString(),
                        )
                    } else if (!initialCapabilities.planMode) {
                        ToolResult(
                            JSONObject().put("ok", false).put("code", "PLAN_MODE_OFF")
                                .put("message", "本次运行未启用计划模式，${PlanModePolicy.PLAN_TOOL_NAME} 未执行；" +
                                    "直接按用户要求推进任务，不要输出计划清单，也不要用文字代替清单。").toString(),
                        )
                    } else {
                        val published = publishPlan(call, onEvent)
                        // 只有真的收下计划才放闸；参数不合法要让模型继续被挡，先修好清单。
                        val accepted = runCatching { JSONObject(published.content) }
                            .getOrNull()?.optBoolean("ok") == true
                        if (accepted) planGate.onPlanAccepted()
                        published
                    }
                } else if (!planGate.allowsTool(call.name)) {
                    // Plan 模式首步：先给清单，别先动手。拒绝理由写清楚，模型下一轮就会改走 update_plan。
                    ToolResult(
                        JSONObject().put("ok", false)
                            .put("code", PlanModePolicy.CODE_PLAN_REQUIRED_FIRST)
                            .put("message", PlanModePolicy.planRequiredFirstMessage(call.name)).toString(),
                    )
                } else if (activeExpert != null && !activeExpert.toolAllowed(call.name)) {
                    ToolResult(JSONObject().put("ok", false).put("code", "TOOL_NOT_ALLOWED_BY_EXPERT")
                        .put("message", "该工具不在当前专家的工具集内，未执行；要放宽请改专家定义或取消召唤").toString())
                } else if (!toolPolicy.allows(call.name)) {
                    ToolResult(JSONObject().put("ok", false).put("code", "TOOL_NOT_ALLOWED_BY_POLICY")
                        .put("message", "该工具不在本次工具白名单中，未执行").toString())
                } else if (restrictedToolNames != null && call.name !in restrictedToolNames) {
                    ToolResult(JSONObject().put("ok", false).put("code", "REVIEW_TOOL_DENIED")
                        .put("message", "此工具不属于自动复盘能力").toString())
                } else {
                    val unavailable = AgentToolRequirements.find(call.name)
                        ?.let { capabilitiesProvider().unavailableCode(call.name) }
                    if (unavailable == null) {
                        toolExecutor.execute(call)
                    } else {
                        ToolResult(
                            JSONObject().put("ok", false).put("code", unavailable)
                                .put("message", "该工具当前不可用（$unavailable），本次未执行").toString(),
                        )
                    }
                }
            },
            runController = runController,
            traceFormatter = traceFormatter,
            onEvent = onEvent,
            purpose = if (rewriteReply) ProviderRequestPurpose.REPLY_REWRITE else ProviderRequestPurpose.CHAT,
            roleplayContext = roleplayContext,
            initialSupplementIndex = initialSupplementIndex,
            onUserTurnAppended = ::armPlanForNewUserTurn,
            spillToolResult = toolResultSpill,
            remoteToolNames = remoteToolNames,
            toolsForRound = ::toolsForRound,
        )
        val result = try {
            if (compactOnly) loop.compactOnly() else loop.run()
        } catch (throwable: Throwable) {
            throw AgentModelExecutionException(
                cause = throwable,
                contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
                reasoningContent = loop.reasoningSnapshot(),
                transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.transcript(
                    transcript,
                    0,
                    loop.sensitiveToolCallIdsSnapshot(),
                )),
            )
        }
        return ModelResponse.Text(
            content = result.content,
            contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
            reasoningContent = result.reasoningContent,
            transcript = AgentConversationCodec.transcript(
                transcript,
                0,
                result.sensitiveToolCallIds,
            ),
        )
    }

    private fun toolName(tool: JSONObject): String? = tool.optJSONObject("function")?.optString("name")

    /**
     * 专家工具集过滤。
     *
     * 白名单非空（且不只是 `*`）时只保留白名单；黑名单永远优先。
     * 与执行侧的 [io.github.mangi.eta.agent.expert.Expert.toolAllowed] 共用同一语义。
     */
    private fun expertFilterTools(
        expert: io.github.mangi.eta.agent.expert.Expert,
        tools: JSONArray,
    ): JSONArray {
        if (!expert.restrictsTools && expert.disallowedTools.isEmpty()) return tools
        return JSONArray().also { kept ->
            for (index in 0 until tools.length()) {
                val schema = tools.optJSONObject(index) ?: continue
                val name = schema.optJSONObject("function")?.optString("name").orEmpty()
                if (expert.toolAllowed(name)) kept.put(schema)
            }
        }
    }

    /**
     * `update_plan` 的执行体：只做校验、归一与广播，不落库。
     *
     * 落库由界面进程统一负责（[AgentEvent.PlanUpdated] → 会话状态 → 会话行），
     * Runtime 不碰 Room，避免两个进程抢同一份数据库。
     */
    private fun publishPlan(call: ToolCall, onEvent: (AgentEvent) -> Unit): ToolResult {
        val root = runCatching { JSONObject(call.argumentsJson) }.getOrNull()
        if (root == null) {
            return ToolResult(
                JSONObject().put("ok", false).put("code", "PLAN_ARGS_INVALID")
                    .put("message", "计划参数不是合法 JSON，计划未更新").toString(),
            )
        }
        val steps = root.optJSONArray("steps")
            ?: root.optJSONObject("plan")?.optJSONArray("steps")
            ?: root.optJSONArray("plan")
        if (steps == null) {
            return ToolResult(
                JSONObject().put("ok", false).put("code", "PLAN_STEPS_MISSING")
                    .put("message", "缺少 steps 数组，计划未更新").toString(),
            )
        }
        val normalized = JSONArray()
        for (index in 0 until steps.length()) {
            val item = steps.optJSONObject(index) ?: continue
            val text = item.optString("text").ifBlank { item.optString("step") }.trim()
            if (text.isEmpty()) continue
            normalized.put(
                JSONObject()
                    .put("text", text.take(MAX_PLAN_STEP_CHARS))
                    .put("status", item.optString("status")),
            )
        }
        if (normalized.length() == 0) {
            return ToolResult(
                JSONObject().put("ok", false).put("code", "PLAN_STEPS_EMPTY")
                    .put("message", "计划步骤为空，计划未更新").toString(),
            )
        }
        val payload = normalized.toString()
        onEvent(AgentEvent.PlanUpdated(payload))
        return ToolResult(
            JSONObject().put("ok", true).put("count", normalized.length())
                .put("steps", normalized)
                .put("message", "计划已更新并显示在会话框上方；按计划清单继续推进。")
                .toString(),
        )
    }

    private fun toolNames(tools: JSONArray): Set<String> =
        (0 until tools.length()).mapNotNullTo(mutableSetOf()) { toolName(tools.getJSONObject(it)) }

    private fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "请先配置 API 地址" }
        when {
            ProviderAuthModes.isBorrowedVendor(authMode) -> Unit
            authMode == ProviderAuthModes.API_KEY -> require(
                apiKey.isNotBlank(),
            ) { "请先配置 API Key（本地地址可留空）" }
            ProviderAuthModes.isOAuth(authMode) -> {
                require(providerId.isNotBlank()) { "账号登录缺少服务商标识，请重新选择服务商" }
                require(apiKey.isEmpty()) { "账号登录配置不能包含 API Key" }
            }
            else -> error("不支持的服务商认证方式")
        }
        require(model.isNotBlank()) { "请先配置模型名" }
        require(
            reasoningCapabilities?.mandatory != true ||
                effectiveReasoningEffort != ReasoningEffort.OFF
        ) { "当前模型强制启用思考，不能选择 Off 或禁用思考权限" }
        if (extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(extraBodyJson) }
                .getOrElse { throwable ->
                    error("额外请求体 JSON 无效：${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
    }

    fun buildUserHistoryMessage(
        text: String,
        images: List<ModelImage>,
    ): ConversationMessage =
        AgentConversationCodec.durableMessage(AgentConversationCodec.userMessage(text, images))

    internal fun summarizeOpenUriArguments(argumentsJson: String): String =
        traceFormatter.summarizeOpenUriArguments(argumentsJson)

    internal fun summarizeBrowserToolArguments(argumentsJson: String): String =
        traceFormatter.summarizeBrowserArguments(argumentsJson)

    internal fun summarizeToolResult(toolName: String, result: ToolResult): String =
        traceFormatter.summarizeResult(toolName, result)

    @Serializable
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = ProviderTypes.OPENAI_COMPATIBLE,
        val providerSourceType: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val modelDisplayName: String = "",
        val contextWindow: Int? = null,
        val systemPrompt: String,
        val anthropicVersion: String = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        val openAiEndpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        val hostedWebSearchEnabled: Boolean = false,
        val supportsSearchSuggestions: Boolean = false,
        val terminalTools: Boolean = false,
        val browserTools: Boolean = true,
        val deviceDirectTools: Boolean = true,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        val extraBodyJson: String = "",
        val customHeaders: List<CustomHeader> = emptyList(),
        val customBody: List<CustomBody> = emptyList(),
        val autoCompactionEnabled: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
        /** 自动压缩触发阈值（窗口占用百分比，0 = 关闭）；手动压缩不受它影响。 */
        val autoCompactionTriggerPercent: Int = Prefs.autoCompactionTriggerPercent(),
        val subagentTools: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
        val authMode: String = ProviderAuthModes.API_KEY,
    ) {
        val usesHostedWebSearch: Boolean
            get() = hostedWebSearchEnabled && when (providerType) {
                ProviderTypes.OPENAI_COMPATIBLE -> openAiEndpointMode == OpenAiEndpointMode.RESPONSES
                ProviderTypes.GEMINI -> supportsSearchSuggestions && GeminiModelCapabilities.supportsHostedSearch(model)
                else -> false
            }

        val hostedWebSearchToolName: String
            get() = if (providerType == ProviderTypes.GEMINI) "google_search" else "web_search"

        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)

        /** 未知窗口不阻断对话：只关闭依赖窗口的自动压缩，手动压缩与服务商溢出错误照常可用。 */
        val knownContextWindow: Int?
            get() = contextWindow?.takeIf { it > 0 }

        /**
         * 自动压缩实际触发比例（0.0 = 不自动压缩）。
         *
         * 阈值为唯一事实源：1..95 才换算成比例，0 就是关闭。
         * 不再「0 时按布尔开关兜回 85%」——那会让用户选「关闭」后被老默认值偷偷压一次；
         * 存量配置的迁移在 [Prefs.autoCompactionTriggerPercent] 里一次做完（旧 true→85、旧 false→0）。
         * 布尔开关仍是运行时授权位：它关着就不压，哪怕阈值给了正数。
         */
        val autoCompactionTriggerRatio: Double
            get() = if (autoCompactionEnabled && autoCompactionTriggerPercent in 1..Prefs.Keys.AUTO_COMPACTION_TRIGGER_PERCENT_MAX) {
                autoCompactionTriggerPercent / 100.0
            } else 0.0
    }

    @Serializable
    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val toolCallsJson: String = "",
        val contextSummary: Boolean = false,
        val compactedUserTurns: Int = 0,
        val summaryThroughUserTurn: Int = 0,
        val messageId: String = "",
        val compactionArchiveId: String = "",
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
        val round: Int = 1,
    )

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList(),
        /**
         * 敏感结果仍会供当前 Agent loop 使用，但工具参数与原始结果不会进入持久会话。
         * 最终 assistant 自己组织的答复不受此标记影响。
         */
        val sensitive: Boolean = false,
        val command: String? = null,
    )

    /** 图片引用：入口侧可为本地 URI/路径，进入模型协议前必须解析为远程 URL 或 data URL。 */
    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown",
        /** 截图已具有可上传编码，跨进程物化时保留字节，不走附件转码。 */
        val preserveOriginal: Boolean = false,
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
            val contextSnapshot: AgentContextSnapshot? = null,
        ) : ModelResponse
    }

}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
    val contextSnapshot: AgentContextSnapshot? = null,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause), StableLogType {

    /** R8 会重命名类名，日志类型走固定 token，避免线上出现 `type=sp`。 */
    override val stableLogType: String get() = "AgentModelExecutionException"
}
