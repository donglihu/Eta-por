package io.github.mangi.eta.hook.xiaoai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal object XiaoAiHooks {
    /**
     * 逐锚点核对过的版本基线；真实门禁在 [XiaoAiTakeoverPolicy]：
     * 命中 [XiaoAiTakeoverPolicy.ADAPTED_VERSION_CODES] 直接放行，
     * 未登记版本只要稳定锚点齐全也放行（混淆锚点缺失由 HookRegistrar.missing 降级）。
     */
    const val SUPPORTED_VERSION_CODE = 507013035L

    private const val OPERATION_MANAGER_CLASS =
        "com.xiaomi.voiceassistant.instruction.base.OperationManager"
    private const val APPLICATION_CLASS = "com.xiaomi.voiceassistant.VAApplication"
    private const val EVENT_CLASS = "com.xiaomi.ai.api.common.Event"
    private const val INSTRUCTION_CLASS = "com.xiaomi.ai.api.common.Instruction"
    private const val ENGINE_CLASS = "y00.r0"
    private const val ASR_PROCESSOR_CLASS = "z10.a"
    /** 507013035 实测为 lh0.s0；507013032 为 kh0.s0，按顺序探测。 */
    private val AGENT_ACTION_MANAGER_CLASSES = listOf("lh0.s0", "kh0.s0")
    private const val ASR_RESULT_FULL_NAME = "SpeechRecognizer.RecognizeResult"
    private const val IMAGE_ID_STORE_CLASS = "k00.y0"
    private const val FLOAT_MANAGER_CLASS = "com.xiaomi.voiceassistant.widget.d"
    private const val CARD_BASE_CLASS = "com.xiaomi.voiceassistant.card.a"
    internal const val FLOW_TOAST_CARD_CLASS =
        "com.xiaomi.voiceassistant.instruction.card.stream.FlowTemplateToastCard"
    private const val UI_MANAGER_CLASS = "com.xiaomi.voiceassistant.UiManager"
    /** 507013035 实测为 y00.c1（更早版本是 la0.n1），按顺序探测。 */
    internal val TTS_PLAYER_CLASSES = listOf("y00.c1", "la0.n1")
    private const val EXTRA_IMAGE_FILE_ID = "extra_image_file_id"
    private val DOCUMENT_EXTRA_KEYS = setOf("file_path", "file_name", "file_desc", "query_type")
    private const val BRIDGE_QUEUE_CAPACITY = 4
    private const val ACTIVATION_TIMEOUT_SECONDS = 3L
    private const val PENDING_DRAIN_LIMIT = 8
    /** 认领成功后等最终结果的上限；超过就回收浮窗，避免永久转圈。 */
    private const val RUN_WATCHDOG_SECONDS = 180L

    private val queryCache = XiaoAiQueryCache()
    private val turnTracker = XiaoAiTurnTracker()
    private val claimedDialogIds = XiaoAiRecentIds()
    private val activeRun = XiaoAiRunSlot<ActiveRun>()
    private val latestFloatManager = AtomicReference<Any?>()
    private val rendererSlot = XiaoAiRendererSlot<XiaoAiStreamRenderer>()
    private val bridgeThreadId = AtomicInteger()
    private val businessHooksInstalled = AtomicBoolean(false)
    private val deferredHookHandles = CopyOnWriteArrayList<HookHandle>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(BRIDGE_QUEUE_CAPACITY),
        { runnable ->
            Thread(
                runnable,
                "Eta-XiaoAiBridge-${bridgeThreadId.incrementAndGet()}",
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "XiaoAi")
        return hooks.install {
            hookApplicationBootstrap(
                hooks = this,
                module = module,
                rootLogger = rootLogger,
                classLoader = classLoader,
            )
        }
    }

    private fun hookApplicationBootstrap(
        hooks: HookRegistrar,
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ) {
        val applicationClass = HookSupport.findClassOrNull(classLoader, APPLICATION_CLASS)
        val onCreate = applicationClass?.let { HookSupport.findMethod(it, "onCreate") }
        if (onCreate == null || onCreate.parameterCount != 0) {
            hooks.missing(
                id = "xiaoai.bootstrap",
                description = "VAApplication.onCreate",
                detail = "未找到超级小爱 Application 版本门禁入口",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.bootstrap",
            executable = onCreate,
            description = "XiaoAi VAApplication.onCreate",
        ) { chain ->
            val context = chain.thisObject as? Context
            val versionCode = context?.let(::packageVersionCode) ?: -1L
            val adapted = XiaoAiTakeoverPolicy.isSupportedVersion(versionCode)
            val anchorProbe = XiaoAiTakeoverPolicy.shouldInstallOnUnknownVersion { anchor ->
                HookSupport.findClassOrNull(classLoader, anchor) != null
            }
            if (adapted || anchorProbe) {
                if (!adapted) {
                    hooks.logger.info(
                        "超级小爱未登记版本但稳定锚点齐全，按结构适配安装: versionCode=$versionCode"
                    )
                }
                installBusinessHooksOnce(
                    module = module,
                    rootLogger = rootLogger,
                    classLoader = classLoader,
                )
            } else {
                hooks.logger.warnThrottled("xiaoai_unsupported_version") {
                    "超级小爱版本不在静态适配范围，保持原生行为: versionCode=$versionCode"
                }
            }
            chain.proceed()
        }
    }

    private fun installBusinessHooksOnce(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ) {
        if (!businessHooksInstalled.compareAndSet(false, true)) return
        val hooks = HookRegistrar(module, rootLogger, "XiaoAi")
        val installation = hooks.install {
            hookQueryCapture(this, classLoader)
            hookAsrFinalResult(this, classLoader)
            hookFloatManagerCapture(this, classLoader)
            hookAgentActions(this, classLoader)
            hookOutboundRequest(this, classLoader)
            hookSessionClear(this, classLoader)
            schedulePendingResultDrains(logger, classLoader)
        }
        deferredHookHandles += installation.handles
        rootLogger.scoped("XiaoAi").info(installation.report.summary())
    }

    private fun packageVersionCode(context: Context): Long =
        runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            ).longVersionCode
        }.getOrDefault(-1L)

    private fun hookQueryCapture(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val managerClass = HookSupport.findClassOrNull(classLoader, OPERATION_MANAGER_CLASS)
        if (managerClass == null) {
            hooks.missing(
                id = "xiaoai.query-info",
                description = "OperationManager.setQueryInfo",
                detail = "未找到小爱查询信息入口",
            )
            return
        }
        val method = HookSupport.findMethod(
            managerClass,
            "setQueryInfo",
            String::class.java,
            String::class.java,
            JSONObject::class.java,
        )
        if (method == null) {
            hooks.missing(
                id = "xiaoai.query-info",
                description = "OperationManager.setQueryInfo",
                detail = "未找到 setQueryInfo(String,String,JSONObject)",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.query-info",
            executable = method,
            description = "XiaoAi OperationManager.setQueryInfo",
        ) { chain ->
            val dialogId = chain.args.getOrNull(0) as? String
            val query = chain.args.getOrNull(1) as? String
            val extra = chain.args.getOrNull(2) as? JSONObject
            if (!dialogId.isNullOrBlank() && !query.isNullOrBlank()) {
                val imageFileId = extra?.optString(EXTRA_IMAGE_FILE_ID)
                val documentInput = extra?.let { json ->
                    DOCUMENT_EXTRA_KEYS.any(json::has)
                } == true
                queryCache.put(
                    dialogId = dialogId,
                    query = query,
                    imageFileId = imageFileId,
                    documentInput = documentInput,
                )
                turnTracker.capture(
                    dialogId = dialogId,
                    query = query,
                    hasImage = !imageFileId.isNullOrBlank(),
                    documentInput = documentInput,
                )
                hooks.logger.debug {
                    "捕获超级小爱查询: dialog=${shortId(dialogId)}, queryChars=${query.length}"
                }
            }
            chain.proceed()
        }
    }

    private fun hookAsrFinalResult(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val processorClass = HookSupport.findClassOrNull(classLoader, ASR_PROCESSOR_CLASS)
        val instructionClass = HookSupport.findClassOrNull(classLoader, INSTRUCTION_CLASS)
        if (processorClass == null || instructionClass == null) {
            hooks.missing(
                id = "xiaoai.asr-final",
                description = "ASR final result",
                detail = "未找到超级小爱终态 ASR 处理器",
            )
            return
        }
        val method = HookSupport.findMethod(processorClass, "processed", instructionClass)
        if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            hooks.missing(
                id = "xiaoai.asr-final",
                description = "ASR final result",
                detail = "未找到 z10.a.processed(Instruction): boolean",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.asr-final",
            executable = method,
            description = "XiaoAi final ASR result",
        ) { chain ->
            val instruction = chain.args.firstOrNull()
            if (instruction != null) {
                captureFinalAsr(hooks.logger, instruction)
            }
            chain.proceed()
        }
    }

    private fun captureFinalAsr(
        logger: ModuleLogger,
        instruction: Any,
    ) {
        if (invokeString(instruction, "getFullName") != ASR_RESULT_FULL_NAME) return
        val payload = HookSupport.invokeNoArgs(instruction, "getPayload") ?: return
        if (HookSupport.invokeNoArgs(payload, "isFinal") != true) return
        val results = HookSupport.invokeNoArgs(payload, "getResults") as? Iterable<*> ?: return
        val query = results.joinToString(separator = "") { item ->
            item?.let { invokeString(it, "getText") }.orEmpty()
        }.trim()
        if (query.isBlank()) return

        val dialogId = optionalString(HookSupport.invokeNoArgs(instruction, "getDialogId"))
        turnTracker.capture(dialogId = dialogId, query = query)
        logger.debug {
            "捕获超级小爱终态 ASR: dialog=${shortId(dialogId)}, queryChars=${query.length}"
        }
    }

    private fun hookFloatManagerCapture(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val floatManagerClass = HookSupport.findClassOrNull(classLoader, FLOAT_MANAGER_CLASS)
        val cardClass = HookSupport.findClassOrNull(classLoader, CARD_BASE_CLASS)
        if (floatManagerClass == null || cardClass == null) {
            hooks.missing(
                id = "xiaoai.card-sink",
                description = "FloatManager.addCard",
                detail = "未找到小爱卡片管理器",
            )
            return
        }
        val method = HookSupport.findMethod(floatManagerClass, "addCard", cardClass)
        if (method == null) {
            hooks.missing(
                id = "xiaoai.card-sink",
                description = "FloatManager.addCard",
                detail = "未找到 FloatManager.addCard(Card)",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.card-sink",
            executable = method,
            description = "XiaoAi FloatManager.addCard",
        ) { chain ->
            chain.thisObject?.let(latestFloatManager::set)
            chain.proceed()
        }
    }

    private fun hookOutboundRequest(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val engineClass = HookSupport.findClassOrNull(classLoader, ENGINE_CLASS)
        val eventClass = HookSupport.findClassOrNull(classLoader, EVENT_CLASS)
        if (engineClass == null || eventClass == null) {
            hooks.missing(
                id = "xiaoai.outbound",
                description = "MiSpeechEngine.C0",
                detail = "未找到小爱出站事件边界",
            )
            return
        }
        val method = HookSupport.findMethod(engineClass, "C0", eventClass)
        if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            hooks.missing(
                id = "xiaoai.outbound",
                description = "MiSpeechEngine.C0",
                detail = "未找到 C0(Event): boolean",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.outbound",
            executable = method,
            description = "XiaoAi MiSpeechEngine.C0",
        ) { chain ->
            val event = chain.args.getOrNull(0)
            val claimed = try {
                event != null && maybeClaimRequest(hooks.logger, classLoader, event)
            } catch (exception: Exception) {
                hooks.logger.warnThrottled("xiaoai_claim_failed") {
                    "小爱请求认领失败，保持原生行为: type=${exception.safeLogType()}"
                }
                false
            }
            if (claimed) {
                true
            } else {
                // 诊断：C0 是全链路唯一出站边界，认领失败时把事件名与判定依据打出来，
                // 否则「钩子已装但毫无反应」在现场无法定位（507013035 首轮就卡在这里）。
                if (event != null) {
                    logOutboundSkip(hooks.logger, event)
                }
                chain.proceed()
            }
        }
    }

    /** 认领未命中时输出一行诊断，节流 5s；只打事件名与文本长度，不落用户原文。 */
    private fun logOutboundSkip(logger: ModuleLogger, event: Any) {
        val fullName = runCatching { invokeString(event, "getFullName") }.getOrDefault("")
        // C0 是全局出站边界，SpeechRecognizer.* 等纯识别事件每轮都会经过；
        // 全量打印会淹没真正有用的信息，因此只对 Nlp 系出站事件做明细诊断。
        val nlpOutbound = XiaoAiTakeoverPolicy.matchesOutboundEvent(fullName)
        if (!nlpOutbound) return
        val dialogId = runCatching { invokeString(event, "getId") }.getOrDefault("")
        val query = runCatching {
            HookSupport.invokeNoArgs(event, "getPayload")?.let { invokeString(it, "getQuery") }
        }.getOrNull().orEmpty()
        logger.warnThrottled("xiaoai_outbound_skip", windowMs = 5_000L) {
            "小爱出站未认领: event=${fullName.ifBlank { "-" }} " +
                "dialog=${shortId(dialogId)} queryChars=${query.length} " +
                "cached=${queryCache.size()} turn=${turnTracker.latest()?.query?.length ?: -1} " +
                "customModel=${Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)} " +
                "requirePrefix=${Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX)}"
        }
    }

    /**
     * 总开关关闭时的显式提示。
     *
     * 之前「钩子全装上但小爱毫无变化」在现场无从下手，因为 decide() 因总开关返回 null
     * 属于静默分支。这里把原因直接写进日志，避免再次把「配置没开」误判成「钩子失效」。
     */
    private fun warnIfTakeoverDisabled(logger: ModuleLogger) {
        if (Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) return
        logger.warnThrottled("xiaoai_takeover_disabled", windowMs = 60_000L) {
            "小爱接管未启用：设置里「启用厂商助手自定义模型」当前为关，请求保持原生行为"
        }
    }

    private fun hookAgentActions(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val managerClass = AGENT_ACTION_MANAGER_CLASSES.firstNotNullOfOrNull { name ->
            HookSupport.findClassOrNull(classLoader, name)
        }
        if (managerClass == null) {
            hooks.missing(
                id = "xiaoai.agent-action",
                description = "AgentActionManager",
                detail = "未找到超级小爱 Agent Action 执行器",
            )
            return
        }
        val methods = HookSupport.findDeclaredMethods(managerClass, makeAccessible = true) { method ->
            (method.name == "execute" || method.name == "executeActionsAsync") &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.any { it.name.endsWith("Agent\$Action") }
        }
        if (methods.isEmpty()) {
            hooks.missing(
                id = "xiaoai.agent-action",
                description = "AgentActionManager",
                detail = "未找到 Agent Action boolean 执行入口",
            )
            return
        }
        methods.forEachIndexed { index, method ->
            hooks.intercept(
                id = "xiaoai.agent-action.$index",
                executable = method,
                description = "XiaoAi ${method.name}(Agent.Action)",
            ) { chain ->
                if (shouldOwnLatestTurn()) {
                    hooks.logger.info("已拦截超级小爱原生 Agent Action")
                    true
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun hookSessionClear(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val engineClass = HookSupport.findClassOrNull(classLoader, ENGINE_CLASS)
        val method = engineClass?.let { HookSupport.findMethod(it, "V") }
        if (method == null || method.parameterCount != 0) {
            hooks.missing(
                id = "xiaoai.session-clear",
                description = "MiSpeechEngine session clear",
                detail = "未找到小爱会话清理入口",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.session-clear",
            executable = method,
            description = "XiaoAi MiSpeechEngine session clear",
        ) { chain ->
            // BACK 关闭小爱浮层也会清理 session；这里只分离入口 UI，不能取消仍在执行的 Runtime。
            rendererSlot.detach()?.cancel(classLoader)
            queryCache.clear()
            turnTracker.clear()
            claimedDialogIds.clear()
            chain.proceed()
        }
    }

    private fun maybeClaimRequest(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        event: Any,
    ): Boolean {
        val fullName = invokeString(event, "getFullName")
        if (!XiaoAiTakeoverPolicy.matchesOutboundEvent(fullName)) return false

        val dialogId = invokeString(event, "getId").trim()
        if (dialogId.isBlank()) return false
        if (claimedDialogIds.contains(dialogId)) return true

        // 总开关关闭是最常见的「毫无反应」原因，进入认领逻辑先自检一次。
        warnIfTakeoverDisabled(logger)

        val payload = HookSupport.invokeNoArgs(event, "getPayload")
        val eventQuery = payload?.let { invokeString(it, "getQuery") }.orEmpty()
        val queryInfo = queryCache.takeMatching(dialogId, eventQuery)
        val latestTurn = turnTracker.latest()
        val query = eventQuery.ifBlank { queryInfo?.query ?: latestTurn?.query.orEmpty() }
        if (query.isBlank()) {
            logger.warnThrottled("xiaoai_nlp_query_missing") {
                "超级小爱 Nlp.Request 缺少可接管的查询文本"
            }
            return false
        }
        if (queryInfo == null && query.equals("blank", ignoreCase = true)) {
            logger.warnThrottled("xiaoai_blank_query_uncorrelated") {
                "超级小爱 blank 请求缺少图片上下文，保持原生行为"
            }
            return false
        }
        val sameTrackedQuery = latestTurn?.query?.trim() == query.trim()
        if (queryInfo?.documentInput == true ||
            (queryInfo == null && sameTrackedQuery && latestTurn.documentInput)
        ) {
            return false
        }
        if (queryInfo == null && sameTrackedQuery && latestTurn.hasImage) {
            logger.warnThrottled("xiaoai_image_query_uncorrelated") {
                "超级小爱图片请求无法关联 setQueryInfo，保持原生行为"
            }
            return false
        }
        if (queryInfo != null && queryInfo.dialogId != dialogId) {
            logger.debug {
                "关联超级小爱新 Event ID: queryDialog=${shortId(queryInfo.dialogId)}, " +
                    "event=${shortId(dialogId)}"
            }
        } else if (queryInfo == null) {
            logger.debug {
                "直接从 Nlp.Request 捕获超级小爱查询: event=${shortId(dialogId)}, " +
                    "queryChars=${query.length}"
            }
        }

        val imageResolution = resolveImage(
            logger = logger,
            classLoader = classLoader,
            imageFileId = queryInfo?.imageFileId,
        )
        if (imageResolution is XiaoAiImages.Resolution.Failure) {
            logger.warnThrottled("xiaoai_image_${imageResolution.code.name.lowercase()}") {
                "小爱图片认领前校验失败，保持原生行为: code=${imageResolution.code.name}"
            }
            return false
        }
        val image = (imageResolution as? XiaoAiImages.Resolution.Success)?.image
        val decision = XiaoAiTakeoverPolicy.decide(
            query = query,
            hasImage = image != null,
            customModelEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL),
            requirePrefix = Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX),
        ) ?: return false
        turnTracker.capture(
            dialogId = dialogId,
            query = query,
            hasImage = image != null,
        )
        val context = AgentAppContext.resolve() ?: return false
        val renderer = XiaoAiStreamRenderer(
            context = context,
            logger = logger,
            classLoader = classLoader,
            dialogId = dialogId,
            floatManagerProvider = {
                latestFloatManager.get() ?: resolveFloatManager(classLoader, context)
            },
        )
        val run = ActiveRun(
            runId = UUID.randomUUID().toString(),
            dialogId = dialogId,
            prompt = decision.prompt,
            image = image,
            renderer = renderer,
        )
        val future = try {
            executor.submit {
                executeRun(logger, context, run)
            }
        } catch (_: RejectedExecutionException) {
            return false
        }
        run.future.set(future)
        rendererSlot.attach(renderer)?.cancel()
        val previous = activeRun.replace(run)
        previous?.cancel(logger, classLoader)
        claimedDialogIds.add(dialogId)
        run.activate()
        logger.info("已接管超级小爱请求: images=${if (image == null) 0 else 1}")
        return true
    }

    /**
     * 是否吞掉小爱原生 Agent Action。
     *
     * 只有「ETA 真的接了这一轮」才允许吞：必须存在未取消的 activeRun。
     * 旧实现只看 turnTracker（setQueryInfo/ASR 就能写进去），一旦认领没成功，
     * 原生动作被吞、ETA 又没出答案，小爱浮窗就永远转圈（507013035 现场的卡死）。
     */
    private fun shouldOwnLatestTurn(): Boolean {
        val run = activeRun.get() ?: return false
        if (run.cancelled.get()) return false
        val turn = turnTracker.latest() ?: return true
        if (turn.documentInput) return false
        return XiaoAiTakeoverPolicy.decide(
            query = turn.query,
            hasImage = turn.hasImage,
            customModelEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL),
            requirePrefix = Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX),
        ) != null
    }

    private fun executeRun(
        logger: ModuleLogger,
        context: Context,
        run: ActiveRun,
    ) {
        if (!run.awaitActivation()) {
            logger.warnThrottled("xiaoai_run_activation_timeout") {
                "超级小爱本轮未完成激活，放弃并保持原生行为"
            }
            activeRun.clear(run)
            return
        }
        if (activeRun.get() !== run) return
        scheduleWatchdog(logger, run)
        val client = AgentRuntimeClient(context, logger)
        run.client.set(client)
        var resultRunId: String? = null
        try {
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = run.runId,
                    prompt = run.prompt,
                    config = AgentModelClient.loadConfig(),
                    images = listOfNotNull(run.image),
                    handoff = run.toHandoff(),
                ),
                onEvent = { event ->
                    if (activeRun.get() === run && !run.cancelled.get()) {
                        run.renderer.onEvent(event)
                    }
                },
            )
            resultRunId = result.runId.ifBlank { run.runId }
            if (activeRun.get() !== run || run.cancelled.get()) return
            if (result.ok) {
                run.renderer.complete(result.content)
            } else {
                logger.warnThrottled("xiaoai_runtime_failed") {
                    "超级小爱 Agent Runtime 执行失败"
                }
                run.renderer.fail()
            }
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_bridge_failed") {
                "超级小爱 Agent 执行异常: type=${exception.safeLogType()}"
            }
            if (activeRun.get() === run && !run.cancelled.get()) {
                run.renderer.fail()
            }
        } finally {
            resultRunId?.let { client.ackResult(it) }
            activeRun.clear(run)
        }
    }

    /**
     * 兜底看门狗：认领成功后如果长时间拿不到最终结果（Runtime 无响应、模型挂起、
     * 跨进程丢失），主动把浮窗收成失败卡。否则小爱侧被吞掉的原生请求无法恢复，
     * 浮窗会一直转圈到用户手动关掉。
     */
    private fun scheduleWatchdog(logger: ModuleLogger, run: ActiveRun) {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            if (activeRun.get() !== run || run.cancelled.get()) return@postDelayed
            logger.warnThrottled("xiaoai_watchdog_timeout") {
                "超级小爱本轮 ${RUN_WATCHDOG_SECONDS}s 无最终结果，回收浮窗"
            }
            run.cancelled.set(true)
            run.renderer.fail()
            run.future.get()?.cancel(true)
            activeRun.clear(run)
        }, RUN_WATCHDOG_SECONDS * 1_000L)
    }

    private fun resolveImage(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        imageFileId: String?,
    ): XiaoAiImages.Resolution {
        val normalizedId = imageFileId?.trim().orEmpty()
        if (normalizedId.isBlank()) return XiaoAiImages.Resolution.NoImage
        val storeClass = HookSupport.findClassOrNull(classLoader, IMAGE_ID_STORE_CLASS)
            ?: return XiaoAiImages.missingPath()
        val getString = HookSupport.findMethod(
            storeClass,
            "getString",
            String::class.java,
            String::class.java,
        ) ?: return XiaoAiImages.missingPath()
        val path = try {
            getString.invoke(null, normalizedId, "") as? String
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_image_lookup_failed") {
                "小爱图片映射读取失败: type=${exception.safeLogType()}"
            }
            null
        }
        if (path.isNullOrBlank()) return XiaoAiImages.missingPath()
        return XiaoAiImages.validatePath(path)
    }

    private fun schedulePendingResultDrains(
        logger: ModuleLogger,
        classLoader: ClassLoader,
    ) {
        val handler = Handler(Looper.getMainLooper())
        longArrayOf(1_000L, 5_000L, 15_000L).forEach { delay ->
            handler.postDelayed({
                val context = AgentAppContext.resolve() ?: return@postDelayed
                try {
                    executor.execute {
                        drainPendingResults(logger, classLoader, context)
                    }
                } catch (_: RejectedExecutionException) {
                    logger.warnThrottled("xiaoai_drain_rejected") {
                        "超级小爱结果恢复队列繁忙，跳过本次恢复"
                    }
                }
            }, delay)
        }
    }

    private fun drainPendingResults(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        context: Context,
    ) {
        val client = AgentRuntimeClient(context, logger)
        client.drainCompletedRuns()
            .asSequence()
            .filter { it.handoff.source == XiaoAiHandoff.SOURCE }
            .take(PENDING_DRAIN_LIMIT)
            .forEach { completed ->
                val dialogId = dialogIdFromHandoff(completed.handoff)
                if (
                    completed.result.ok &&
                    dialogId.isNotBlank() &&
                    dialogId == currentDialogId(classLoader)
                ) {
                    val renderer = XiaoAiStreamRenderer(
                        context = context,
                        logger = logger,
                        classLoader = classLoader,
                        dialogId = dialogId,
                        floatManagerProvider = {
                            latestFloatManager.get() ?: resolveFloatManager(classLoader, context)
                        },
                    )
                    rendererSlot.attach(renderer)?.cancel()
                    renderer.complete(completed.result.content)
                }
                client.ackResult(completed.result.runId.ifBlank { completed.handoff.id })
            }
    }

    private fun currentDialogId(classLoader: ClassLoader): String {
        val managerClass = HookSupport.findClassOrNull(classLoader, OPERATION_MANAGER_CLASS)
            ?: return ""
        val instance = runCatching {
            HookSupport.findMethod(managerClass, "getInstance")?.invoke(null)
        }.getOrNull() ?: return ""
        val queryInfo = HookSupport.invokeNoArgs(instance, "getQueryInfo") ?: return ""
        return invokeString(queryInfo, "getDialogId")
    }

    private fun dialogIdFromHandoff(handoff: AgentRuntimeWire.EntryHandoff): String {
        return XiaoAiHandoff.dialogIdFrom(handoff)
    }

    private fun resolveFloatManager(
        classLoader: ClassLoader,
        context: Context,
    ): Any? = runCatching {
        val uiManagerClass = Class.forName(UI_MANAGER_CLASS, false, classLoader)
        val getInstance = uiManagerClass.getMethod("getInstance", Context::class.java)
        val uiManager = getInstance.invoke(null, context) ?: return@runCatching null
        uiManager.javaClass.getMethod("getFloatManager").invoke(uiManager)
    }.getOrNull()?.also(latestFloatManager::set)

    private fun invokeString(target: Any, methodName: String): String =
        (HookSupport.invokeNoArgs(target, methodName) as? String).orEmpty()

    private fun optionalString(optional: Any?): String {
        if (optional == null || HookSupport.invokeNoArgs(optional, "isPresent") != true) return ""
        return HookSupport.invokeNoArgs(optional, "get")?.toString().orEmpty()
    }

    private fun shortId(value: String): String =
        value.trim().let { id ->
            when {
                id.isBlank() -> "-"
                id.length <= 8 -> id
                else -> "${id.take(4)}…${id.takeLast(4)}"
            }
        }

    private data class ActiveRun(
        val runId: String,
        val dialogId: String,
        val prompt: String,
        val image: AgentModelClient.ModelImage?,
        val renderer: XiaoAiStreamRenderer,
        val activation: CountDownLatch = CountDownLatch(1),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val client: AtomicReference<AgentRuntimeClient?> = AtomicReference(),
        val future: AtomicReference<Future<*>?> = AtomicReference(),
    ) {
        fun activate() {
            activation.countDown()
        }

        fun awaitActivation(): Boolean =
            activation.await(ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        fun cancel(logger: ModuleLogger, classLoader: ClassLoader) {
            if (!cancelled.compareAndSet(false, true)) return
            activation.countDown()
            client.get()?.cancelRun(runId)
            future.get()?.cancel(true)
            renderer.cancel(classLoader)
            logger.debug { "超级小爱请求已取消" }
        }

        fun toHandoff(): AgentRuntimeWire.EntryHandoff =
            XiaoAiHandoff.create(
                runId = runId,
                dialogId = dialogId,
                prompt = prompt,
            )
    }
}
