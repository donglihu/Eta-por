package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.overlay.AgentOverlayRunningSession
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import io.github.mangi.eta.ui.MainActivity
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.VirtualScreenRoutingPolicy
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenNotification
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())
    private lateinit var islandBridge: AgentRuntimeIslandBridge

    /** 进行中的会话，按 runId 索引；同一时刻最多 [MAX_CONCURRENT_RUNS] 个。 */
    private val sessions = linkedMapOf<String, AgentRuntimeSession>()

    private fun sessionFor(runId: String): AgentRuntimeSession? = sessions[runId]

    private data class RunningSessionInfo(val runId: String, val conversationId: String, val title: String)

    /** 运行中会话的展示信息：胶囊展开后点一条就能跳回对应会话。 */
    private val runningSessionInfo = linkedMapOf<String, RunningSessionInfo>()



    /** 按当前 sessions 同步「在跑什么」的状态（执行通知与流体云共用）。 */
    private fun syncRunningSessions() {
        runningSessionInfo.keys.retainAll(sessions.keys.toSet())
        val list = mutableListOf<AgentOverlayRunningSession>()
        sessions.keys.forEach { runId ->
            runningSessionInfo[runId]?.let { info ->
                list += AgentOverlayRunningSession(
                    runId = info.runId,
                    conversationId = info.conversationId,
                    title = info.title,
                    running = true,
                )
            }
        }
        val trimmed = list.take(SESSION_LIST_LIMIT)
        if (state.value.runningSessions == trimmed) return
        state.value = state.value.copy(runningSessions = trimmed)
    }

    /** 任务收尾：只记状态；悬浮胶囊已移除，不再弹任何窗口。 */
    private fun enterFinalState(finalState: AgentOverlayState, keepVisible: Boolean = false) {
        state.value = finalState
        hasExecutedForegroundTool = false
    }

    /** 没有任务在跑就退出服务；并发时不能把别人的 run 一起带走。 */
    private fun dismissAndStop() {
        if (runningSessionCount() > 0) return
        stopSelf()
    }

    /** 仍在运行的会话数（含正在收尾的）。 */
    private fun runningSessionCount(): Int = sessions.values.count { session -> !session.isTerminal }
    private var startRequestGeneration = 0L
    /** 并发准备槽位：每个 run 各占一个，后来的请求不会把前一个顶掉。 */
    private val pendingStartRequests = linkedMapOf<String, PendingStartRequest>()

    private data class PendingStartRequest(
        val generation: Long,
        val incoming: AgentRuntimeWire.IncomingRunRequest,
        val replyTo: Messenger?,
    )


    /** 浮层与执行通知共用同一份运行状态；写入只发生在主线程。 */
    private val state = object {
        private val holder = mutableStateOf(AgentOverlayState.Initial)
        var value: AgentOverlayState
            get() = holder.value
            set(next) {
                holder.value = next
                AgentExecutionService.updateRunStatus(
                    this@AgentRuntimeService,
                    next.status.takeIf { runningSessionCount() > 0 },
                )
            }
    }
    private var hasExecutedForegroundTool = false
    /**
     * 结果卡片"回到 Eta"打开的会话：App 内发起的 run 带会话 ID；系统助手等外部入口
     * 带来源与会话键，由界面导入归档后打开。两者都没有时只回到首页。
     */
    private var resultConversationId: String? = null
    private var resultArchive: Pair<String, String>? = null
    private val supplementsLock = Any()

    /**
     * 运行中补充消息按 run 隔离。
     *
     * 以前是 service 级单例：启动第二个会话的 run 会把第一个 run 的补充清单清空，
     * 而 `withActiveSupplements()` 又会把这份全局清单挂到任意 run 的请求快照上——
     * 多会话并行时 A 会话插的话被写进 B 会话的历史，就是这条链路串的台。
     */
    private val supplementsByRun = mutableMapOf<String, MutableList<AgentUiHandoffPayload.Supplement>>()
    private val supplementIndexByRun = mutableMapOf<String, Int>()
    @Volatile
    private var lastCompletedRunContext: CompletedRunContext? = null
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        islandBridge = AgentRuntimeIslandBridge(this, AndroidAgentLogger, ::cancelRun)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /**
     * 同意守门只对测试包生效。
     *
     * 正式包没有测试协议这回事，若这里不加 BETA_BUILD 判断，
     * 正式用户的服务会因为「从未同意过测试协议」而被拒，表现为「Agent Runtime 服务绑定失败」。
     */
    private fun consentAllowsRuntime(): Boolean =
        !io.github.mangi.eta.BuildConfig.BETA_BUILD ||
            io.github.mangi.eta.data.repository.TestConsentRepository.isAccepted()

    override fun onBind(intent: Intent): IBinder? {
        // 未同意就直接拒绝绑定：外部 App 拿不到 Messenger，绕过界面也起不来。
        if (!consentAllowsRuntime()) {
            AndroidAgentLogger.info("[consent] 未同意测试协议，拒绝绑定运行时服务")
            return null
        }
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!consentAllowsRuntime()) {
            AndroidAgentLogger.info("[consent] 未同意测试协议，运行时服务自停")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE_RUNS -> requestPause(null)
            ACTION_RESUME_RUNS -> requestResume(null)
            ACTION_STOP_RUNS -> sessions.keys.toList().forEach { runId -> cancelRun(runId) }
            ACTION_PAUSE_RUN -> requestPause(intent.getStringExtra(EXTRA_RUN_ID))
            ACTION_RESUME_RUN -> requestResume(intent.getStringExtra(EXTRA_RUN_ID))
            ACTION_STOP_RUN -> cancelRun(intent.getStringExtra(EXTRA_RUN_ID).orEmpty())
            ACTION_REFRESH_FLUID -> RunProgressNotifier.refreshIdle(this, fluidPinned())
            ACTION_SUPPLEMENT_RUN -> requestSupplement(
                intent.getStringExtra(EXTRA_SUPPLEMENT_TEXT).orEmpty(),
                intent.getStringExtra(EXTRA_SUPPLEMENT_CONVERSATION_ID).orEmpty(),
            )
            else -> {
                val keepAlive = intent?.action == ACTION_KEEP_ALIVE && sessions.isNotEmpty()
                // 并发保护：还有会话在跑就别停自己，否则会把并行中的任务一起取消。
                if (!keepAlive && runningSessionCount() == 0) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (runningSessionCount() > 0) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        // 返回桌面/组件销毁不主动取消运行中会话：执行线程在进程内存里继续跑完并发布终态，
        // 若进程被系统回收，checkpoint 现场由 recoverRuntimeRuns 对账恢复为 interrupted。
        // 只有用户显式停止才走 cancelRun（MSG_CANCEL 路径）。
        // 经验复盘由自己的执行引用持有；文字任务关闭入口服务后仍可完成。
        startRequestGeneration++
        pendingStartRequests.values.toList().forEach { pending ->
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, pending.incoming.request.runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = pending.incoming.request.runId,
                    ok = false,
                    content = "",
                    error = "Agent Runtime 服务已停止",
                ),
            )
        }
        pendingStartRequests.clear()
        // 只在销毁时把在途会话的缓冲增量强制落盘，保证恢复能看到最新现场；不 cancel、不清空
        // sessions，执行线程仍能继续投递事件与收尾（onDestroy 后 Handler/Looper 仍存活）。
        runCatching { AgentRunCheckpointRecorder.sealAllActive() }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_destroy_seal_failed") {
                    "Agent runtime destroy checkpoint seal failed: type=${throwable.safeLogType()}"
                }
            }
        islandBridge.close()
        resultIo.shutdownNow()
        // 不移除 mainHandler 回调：onDestroy 后执行线程的事件投递与收尾回调仍需执行，
        // 若全量移除会丢掉尚未执行的 postTerminalOverlay，导致会话卡在 sessions 里。
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                } else if (msg.what == AgentRuntimeWire.MSG_STEER) {
                    AgentWireText.close(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo, msg.sendingUid == android.os.Process.myUid())
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_PAUSE -> requestPause(
                    msg.data?.let(AgentRuntimeWire::runIdFromBundle)?.takeIf { it.isNotBlank() },
                )
                AgentRuntimeWire.MSG_RESUME -> requestResume(
                    msg.data?.let(AgentRuntimeWire::runIdFromBundle)?.takeIf { it.isNotBlank() },
                )
                AgentRuntimeWire.MSG_STEER -> {
                    val data = msg.data
                    val runId = data?.let { AgentRuntimeWire.runIdFromBundle(it) }.orEmpty()
                    if (runId.isNotBlank()) {
                        steerRun(data, msg.replyTo)
                    } else {
                        // 旧格式：浮层按 conversationId 定位的 fire-and-forget steering。
                        val text = data?.getString(AgentRuntimeWire.KEY_PROMPT).orEmpty().trim()
                        val conversationId = data?.getString(AgentRuntimeWire.KEY_CONVERSATION_ID).orEmpty()
                        if (text.isNotBlank()) requestSupplement(text, conversationId)
                    }
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(replyTo, target?.result ?: AgentRuntimeWire.RunResult(
                            runId, false, "", "完整运行结果不可用", contextSnapshotRef = runId,
                        ))
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
        isEtaProcess: Boolean,
    ) {
        val generation = ++startRequestGeneration
        val pending = PendingStartRequest(generation, incoming, replyTo)
        pendingStartRequests[incoming.request.runId] = pending
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                val request = AgentRuntimeRequestConfigResolver.constrainPresentation(
                    AgentRuntimeImageTransfer.materialize(incoming), isEtaProcess,
                )
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        if (AgentRuntimeRequestConfigResolver.usesDefaultRuntimeConfig(request)) {
                            RuntimeConfigRepository.currentRuntimeConfig()
                        } else {
                            RuntimeConfigRepository.oauthRuntimeConfigFor(
                                providerId = request.config.providerId,
                                authMode = request.config.authMode,
                                modelName = request.config.model,
                            )
                        }
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                if (pendingStartRequests[incoming.request.runId] !== pending) return@post
                pendingStartRequests.remove(incoming.request.runId)
                sendRequestIngestedTo(replyTo, incoming.request.runId)
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        startRun(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                            replyTo,
                        )
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Eta 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                        )
                    },
                )
            }
        }
    }

    private data class QueuedRun(
        val request: AgentRuntimeWire.RunRequest,
        val replyTo: Messenger?,
        val queuedAt: Long,
    )

    private val queuedRuns = java.util.ArrayDeque<QueuedRun>()

    private val memoryQueueDrain = object : Runnable {
        override fun run() {
            drainQueuedRuns()
            if (queuedRuns.isNotEmpty()) mainHandler.postDelayed(this, MEMORY_QUEUE_INTERVAL_MS)
        }
    }

    /** 内存回落后放行排队会话：只放到并发上限，且不越过 95% 闸门。 */
    private fun drainQueuedRuns() {
        val now = System.currentTimeMillis()
        while (queuedRuns.isNotEmpty()) {
            val head = queuedRuns.peekFirst()
            if (now - head.queuedAt > MEMORY_QUEUE_TTL_MS) {
                queuedRuns.pollFirst()
                io.github.mangi.eta.core.EtaTrace.log(
                    "run.queue.timeout",
                    "runId=${head.request.runId} 排队超时已放弃",
                )
                sendResultTo(
                    head.replyTo,
                    AgentRuntimeWire.RunResult(
                        runId = head.request.runId,
                        ok = false,
                        content = "",
                        error = "内存长期紧张，这条会话排队超时已放弃。",
                    ),
                )
                continue
            }
            val memoryTier = AgentMemoryGate.tier(this)
            // 95 档只限流：有空位就放行；96 档起连排队也停止放行，只保留正在跑的任务。
            if (memoryTier >= AgentMemoryGate.TIER_LOCKDOWN) {
                io.github.mangi.eta.core.EtaTrace.log(
                    "run.queue.hold",
                    "${AgentMemoryGate.describe(this)} 继续等待",
                )
                break
            }
            if (runningSessionCount() >= AgentMemoryGate.sessionLimit()) break
            queuedRuns.pollFirst()
            io.github.mangi.eta.core.EtaTrace.log("run.queue.release", "runId=${head.request.runId}")
            startRun(head.request, head.replyTo)
        }
    }

    private fun enqueueRun(request: AgentRuntimeWire.RunRequest, replyTo: Messenger?): Boolean {
        if (queuedRuns.size >= MEMORY_QUEUE_MAX) return false
        queuedRuns.addLast(QueuedRun(request, replyTo, System.currentTimeMillis()))
        io.github.mangi.eta.core.EtaTrace.log(
            "run.queue",
            "runId=${request.runId} 入队/位置=${queuedRuns.size} " +
                AgentMemoryGate.describe(this),
        )
        mainHandler.removeCallbacks(memoryQueueDrain)
        mainHandler.postDelayed(memoryQueueDrain, MEMORY_QUEUE_INTERVAL_MS)
        return true
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        val automated = io.github.mangi.eta.agent.automation.AgentTaskScheduler.isRegistered(request.runId)
        // 自动任务已经放开：手动任务在跑时不再拒绝它，只受总并发上限约束。
        val reviewGeneration = AgentExperienceReview.foregroundStarted()
        val memoryMb = AgentMemoryGate.availableMb(this)
        val memoryTier = AgentMemoryGate.tier(this)
        if (memoryTier >= AgentMemoryGate.TIER_LOCKDOWN) {
            val frozen = memoryTier == AgentMemoryGate.TIER_FREEZE
            // 97%：冻结 Agent，先把在途会话的缓冲增量强制落盘，再停止接收新请求。
            val sealed = if (frozen) AgentRunCheckpointRecorder.sealAllActive() else 0
            io.github.mangi.eta.core.EtaTrace.log(
                "run.gate",
                "runId=${request.runId} 拒收 ${AgentMemoryGate.describe(this)} 存档=$sealed",
            )
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    ok = false,
                    content = "",
                    error = if (frozen) {
                        "剩余内存仅 ${memoryMb}MB（已达冻结线），Eta 已冻结并保存全部会话现场；" +
                            "释放内存后再试。"
                    } else {
                        "剩余内存仅 ${memoryMb}MB，Eta 暂停接收新指令，仅保留正在执行的任务。"
                    },
                ),
            )
            return
        }
        if (memoryTier == AgentMemoryGate.TIER_QUEUE) {
            if (enqueueRun(request, replyTo)) return
            io.github.mangi.eta.core.EtaTrace.log(
                "run.gate",
                "runId=${request.runId} 排队已满 ${queuedRuns.size}/$MEMORY_QUEUE_MAX",
            )
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    ok = false,
                    content = "",
                    error = "内存紧张（剩余 ${memoryMb}MB），排队也满了，请稍后再试。",
                ),
            )
            return
        }
        // 顺序固定：先内存三档闸门，再弹性会话上限。上限在这两个节点才重算。
        // sessionLimit() 的上限（AgentMemoryGate.MAX_SESSION_LIMIT = 6）与
        // MAX_CONCURRENT_RUNS 同源，coerceAtMost 实际是 no-op——留着只会让人误以为是
        // 双保险（2026-11-26 审核）。真正生效的是内存档位，这里直接用它算出的值。
        val sessionLimit = AgentMemoryGate.sessionLimit()
        if (runningSessionCount() >= sessionLimit) {
            if (enqueueRun(request, replyTo)) return
            io.github.mangi.eta.core.EtaTrace.log(
                "run.gate",
                "runId=${request.runId} 并发排队已满 ${queuedRuns.size}/$MEMORY_QUEUE_MAX " +
                    AgentMemoryGate.describe(this),
            )
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    ok = false,
                    content = "",
                    error = "当前剩余内存 ${memoryMb}MB，最多同时 ${sessionLimit} 个会话，" +
                        "排队也满了，请稍后再试。",
                ),
            )
            return
        }
        val session = AgentRuntimeSession(
            runId = request.runId,
            operation = request.operation,
            conversationKey = request.effectiveModelSessionId,
            // 标题与 conversationKey 取同一来源（agent_ui handoff），避免两处判定漂移：
            // 会话 key 认的那条 handoff，才是标题该信任的那条。
            conversationTitle = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload).title }
                .orEmpty(),
            eventSink = { event ->
                val current = sessionFor(request.runId)
                if (current != null && event is AgentEvent.ToolStarted &&
                    (event.name in VirtualScreenRoutingPolicy.uiTools || event.name == "virtual_screen") &&
                    SettingsDataStore.cachedVirtualScreenEnabled()
                ) {
                    current.virtualUiRouted.set(true)
                }
                val virtualScreen = current?.let { usesVirtualUi(it) } == true
                RunProgressNotifier.note(request.runId, event)
                islandBridge.update(request.runId, event, virtualScreen)
                sendEventTo(replyTo, event, virtualScreen)
            },
            resultSink = { result ->
                islandBridge.finish(result)
                sendResultTo(replyTo, result)
            },
        )
        session.virtualUiRouted.set(VirtualScreenSession.isOwnedBy(request.virtualScreenOwner))
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted || automated
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { session.controller.cancel() }
        if (!executionHeld && !allowBoundFallback) {
            session.complete(AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = "无法启动后台执行服务，请返回 Eta 后重试",
            )) {}
            return
        }
        sessions[request.runId] = session
        RunProgressNotifier.noteConcurrency(runningSessionCount())
        RunProgressNotifier.start(this)
        RunProgressNotifier.noteSessionLimit(sessionLimit, MAX_CONCURRENT_RUNS)
        if (sessionLimit < MAX_CONCURRENT_RUNS) {
            // 上限被内存压低时留一条：事后能看出「为什么只能同时跑这么少」。
            io.github.mangi.eta.core.EtaTrace.log(
                "run.gate",
                "内存闸门 剩余=${AgentMemoryGate.availableMb()}MB 并发上限=$sessionLimit/" +
                    "$MAX_CONCURRENT_RUNS",
            )
        }
        io.github.mangi.eta.core.EtaTrace.log(
            "run.start",
            "runId=${request.runId} 并发=${runningSessionCount()}/$sessionLimit " +
                " operation=${request.operation} 自动=${automated}",
        )
        val handoffPayload = request.handoff?.payload?.let { raw -> AgentUiHandoffPayload.from(raw) }
        runningSessionInfo[request.runId] = RunningSessionInfo(
            runId = request.runId,
            conversationId = handoffPayload?.conversationId.orEmpty(),
            title = handoffPayload?.title.orEmpty(),
        )
        // 进度通知记住这次会话，流体云／通知被点开时直接落回那次会话。
        RunProgressNotifier.noteConversation(
            request.runId,
            handoffPayload?.conversationId.orEmpty(),
            handoffPayload?.title.orEmpty(),
        )
        RunControl.bindRun(request.runId, handoffPayload?.conversationId.orEmpty())
        syncRunningSessions()
        islandBridge.start(request)
        lastCompletedRunContext = null
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacksAndMessages(hideToken)
        state.value = AgentOverlayState.Initial
        syncRunningSessions()
        hasExecutedForegroundTool = false
        resultConversationId = request.handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
        resultArchive = request.handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { handoff ->
                AgentExternalArchivePayload.from(handoff.payload)
                    ?.conversationKey
                    ?.takeIf(String::isNotBlank)
                    ?.let { key -> handoff.source to key }
            }
        synchronized(supplementsLock) {
            val seeded = mutableListOf<AgentUiHandoffPayload.Supplement>()
            var nextIndex = 1
            if (request.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                val payload = AgentUiHandoffPayload.from(request.handoff.payload)
                seeded += payload.supplements
                nextIndex = payload.lastSupplementIndex + 1
            }
            supplementsByRun[request.runId] = seeded
            supplementIndexByRun[request.runId] = nextIndex
        }

        thread(name = "agent-runtime") {
            try {
                executeRun(session, request, reviewGeneration)
            } finally {
                synchronized(supplementsLock) {
                    // 只清自己这一份：并发 run 的补充清单不能被别人带着消失。
                    supplementsByRun.remove(request.runId)
                    supplementIndexByRun.remove(request.runId)
                }
                AgentExecutionService.updateRunStatus(this, null)
                AgentExecutionService.release("run:${request.runId}")
            }
        }
    }

    private fun executeRun(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
        reviewGeneration: Long,
    ) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            reviewGeneration = reviewGeneration,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = { it.withActiveSupplements() },
            onAcceptedEvent = { event, entrySurfaceGuard ->
                handleAcceptedRunEvent(session, event, entrySurfaceGuard)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(session, request)
        AgentPerfTrace.trace(
            "perf.run.executor_returned",
            "runId=${request.runId.take(12)} should_update_host=${outcome.shouldUpdateHost}",
        )
        if (!outcome.shouldUpdateHost) {
            // 结果没抢到提交点（会话在收尾前已被 cancel 并发布过自己的终态）：这里不能再推
            // 第二个结果，但 Service 侧登记必须注销，否则会话永远留在 sessions 里，
            // 进度卡与并发槽位跟着一起挂住。会话已被同 runId 的新实例替换时不碰它。
            mainHandler.post {
                if (sessionFor(session.runId) === session) retireSession(session)
            }
            return
        }
        postTerminalOverlay(
            session = session,
            result = outcome.result,
            entrySurfaceGuard = outcome.entrySurfaceGuard,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    CompletedRunContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        if (sessionFor(session.runId) !== session) return
        val revealsOperation = AgentOverlayVisibilityPolicy.shouldRevealFor(event)
        val dismissesEntrySurface = AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)
        val virtualScreenEnabled = (revealsOperation || dismissesEntrySurface || event is AgentEvent.ToolStarted) &&
            SettingsDataStore.cachedVirtualScreenEnabled()
        val virtualUi = usesVirtualUi(session, virtualScreenEnabled)
        val revealsForegroundOperation = !virtualUi && revealsOperation
        val requiresEntrySurfaceDismissal = !virtualUi && dismissesEntrySurface
        val entrySurfaceReady = if (requiresEntrySurfaceDismissal && entrySurfaceGuard != null) {
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)
        } else {
            true
        }
        mainHandler.post {
            if (sessionFor(session.runId) !== session) return@post
            if (
                !virtualUi && AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                    event,
                    entrySurfaceReady,
                )
            ) {
                hasExecutedForegroundTool = true
            }
            if (session.isTerminal) return@post
            runCatching {
                state.value = state.value.applyEvent(event)
                syncRunningSessions()
                if (revealsOperation && entrySurfaceReady) {
                    AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.RUN_STARTED)
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    /** 进展卡片与并发记账的收尾：撤这一张卡，按剩余会话数决定整体停通知。主线程调用。 */
    private fun retireSession(session: AgentRuntimeSession) {
        // 按实例条件删除：同 runId 若已被新实例替换，绝不能误摘活会话。只有当前
        // sessions[runId] 仍指向这个收尾实例才移除，否则收尾属于已被接管的旧会话。
        if (sessions[session.runId] === session) sessions.remove(session.runId)
        RunControl.clearRun(session.runId, "runtime.finished")
        if (sessions.isEmpty()) {
            RunProgressNotifier.stop(this, pinned = fluidPinned())
        } else {
            // 多卡：只撤这一个会话的卡片，其余会话的卡继续挂着。
            RunProgressNotifier.removeRun(session.runId, pinned = fluidPinned())
            RunProgressNotifier.noteConcurrency(runningSessionCount())
        }
    }

    private fun postTerminalOverlay(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        entrySurfaceGuard: EntrySurfaceGuard?,
        completedContext: CompletedRunContext? = null,
    ) {
        mainHandler.post {
            // 收尾与「撤进度通知」必须无条件执行：会话被替换或重复收尾时若直接 return，
            // 通知会一直挂在通知栏（实测 start 12 次只 stop 1 次）。
            val needsOverlay = sessionFor(session.runId) === session
            // 结束通知的标题按「会话自带快照 → 卡片 → 运行表」三级回落：
            // 点停止时终态由 cancelRun 当场发布，它已经撤卡（removeRun）并摘掉 runningSessionInfo，
            // 而这一段是事后排进主线程的，进来时卡片和运行表通常都已经空了。
            // 所以主来源必须是派发时随会话带走的 conversationTitle；
            // 卡片（按 runId 反查）与 runningSessionInfo 只在「未撤卡」的完成路径和系统入口上兜底。
            val finishedConversationTitle = RunFinishTitle.resolve(
                sessionTitle = session.conversationTitle,
                cardTitle = RunProgressNotifier.titleOf(session.runId),
                liveTitle = runningSessionInfo[session.runId]?.title.orEmpty(),
            )
            lastCompletedRunContext = completedContext
            retireSession(session)
            // 完成通知必须无条件发：needsOverlay 为假（会话被接管过 / 被替换）时提前 return，
            // 之前它排在那句 return 后面，等于永远不发——实测通知渠道压根没被创建过。
            io.github.mangi.eta.core.EtaTrace.log(
                "completion.route",
                "routed=" + session.virtualUiRouted.get() + " ok=" + result.ok + " runId=" + session.runId,
            )
            // 所有对话跑完都发结束通知：以前只有灵屏路由的 run 才有，
            // 普通对话切走后用户不知道结果。ID 与会话绑定，并发不会互相覆盖。
            TaskCompletionNotifier.show(
                context = this,
                ok = result.ok,
                detail = if (result.ok) result.content.trim() else result.error.orEmpty(),
                stopped = !result.ok && result.error == "已停止",
                conversationId = session.conversationKey,
                conversationTitle = finishedConversationTitle,
                runId = session.runId,
            )
            if (!needsOverlay) return@post
            io.github.mangi.eta.core.EtaTrace.log(
                "run.end",
                "runId=${session.runId} 剩余并发=${runningSessionCount()}/" +
                    "${AgentMemoryGate.sessionLimit()}",
            )
            syncRunningSessions()
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
        virtualScreen: Boolean? = null,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event, virtualScreen)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // 不把传输失败伪装成已交付终态；引用使新客户端保留 outbox，等待完整恢复。
            val fallback = AgentRuntimeWire.RunResult(result.runId, false, "",
                "完整结果传输失败，已保存的历史未删除。请重新打开会话恢复。",
                contextSnapshotRef = result.runId, operation = result.operation)
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            // 多开：把所有在跑的会话都报给 UI。以前只报第一个，其余会被判成"中断"并清掉现场。
            val activeRunIds = sessions.values
                .filter { session -> !session.isTerminal }
                .map { session -> session.runId }
            msg.data = AgentRuntimeWire.activeRunBundle(activeRunIds)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = sessionFor(runId)
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
        if (sessions.isNotEmpty()) return
        enterFinalState(
            AgentOverlayState(
                phase = AgentOverlayPhase.FAILED,
                status = AgentOverlayStatus.RunFailed,
                detailText = message
            )
        )
    }

    private fun requestStop() {
        val session = sessions.values.firstOrNull { it -> !it.isTerminal }
        if (session == null) {
            dismissAndStop()
            return
        }
        cancelRun(session.runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        io.github.mangi.eta.core.EtaTrace.log("run.cancel", "runId=$runId")
        pendingStartRequests.remove(runId)?.let { pending ->
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = "已停止",
                ),
            )
            RunControl.clearRun(runId, "runtime.cancel.pending")
            return
        }
        // 等内存的排队 run 还没有 session：以前这里直接 return，用户点停止完全没反应，
        // 只能等它排上并启动后才被取消（内存紧张时可能几分钟）。
        val queued = queuedRuns.firstOrNull { item -> item.request.runId == runId }
        if (queued != null && queuedRuns.removeIf { item -> item.request.runId == runId }) {
            io.github.mangi.eta.core.EtaTrace.log("run.queue.cancel", "runId=$runId 排队中已撤销")
            sendResultTo(
                queued.replyTo,
                AgentRuntimeWire.RunResult(runId = runId, ok = false, content = "", error = "已停止"),
            )
            RunControl.clearRun(runId, "runtime.cancel.queued")
            return
        }
        val session = sessionFor(runId) ?: return
        if (runId != session.runId) {
            AndroidAgentLogger.debug { "Agent runtime ignored stale cancel request" }
            return
        }
        if (!session.isTerminal) {
            // 用户点停止必须立刻有终态：这里以前只置 controller 的取消位，终态要等执行线程
            // 跑到下一个检查点后由循环收尾回传。执行线程若卡在长阻塞工具上（terminal 命令、
            // 无障碍/子智能体等待），界面就一直显示「运行中」，体感就是点了没反应很久。
            // session.cancel() 在锁内把状态置 TERMINAL 并把结果推给订阅者；执行线程稍后收尾时
            // 会看到「已终态」，只补落盘、不会再推第二个结果，也不会把会话投影回运行中。
            val published = session.cancel("已停止")
            if (!published) {
                // 已经进入提交阶段：提交方马上要发布它自己的结果，这里只打断在途请求，
                // 不抢提交胜者，避免「客户端收到已停止、outbox 却留下成功结果」的分裂状态。
                // 撤卡与终态同样不能在这里做：那会让用户看到「已停止」闪一下随即变已完成，
                // 而且 enterFinalState 会顺带清掉 hasExecutedForegroundTool。
                // 这两件事交给提交方自己的 postTerminalOverlay。
                session.controller.cancel()
                // 提交方若卡死（beforePublish 死锁 / 执行线程丢失），界面会永远停在「停止中」。
                // 长超时兜底：超过窗口仍未终态时强制接管，发布「已停止」并撤卡，避免 UI 卡死。
                scheduleCancelTimeoutFallback(runId, session)
            }
            // 控制位与终态发布无关，两个分支都要清，否则 RunControl 会留着这个 run 的会话归属记录。
            RunControl.clearRun(runId, "runtime.cancel")
            if (published) {
                // 只有「终态由这里发出」时才由这里撤卡：published == false 时终态属于提交方。
                RunProgressNotifier.removeRun(runId, pinned = fluidPinned())
                // 必须在 syncRunningSessions 之前摘掉展示信息：syncRunningSessions 只
                // retainAll(sessions.keys)、不按 isTerminal 过滤，而刚停掉的 run 此刻仍在 sessions 里
                // （isTerminal=true）。不摘就会以 running=true 的幽灵条目留在胶囊会话清单上，
                // 执行线程卡在不可打断工具上时这条一直挂着。
                runningSessionInfo.remove(runId)
                syncRunningSessions()
                if (runningSessionCount() == 0) {
                    // 只剩它自己：胶囊也不用再停在「停止中」等执行线程回来。
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = AgentOverlayStatus.Stopped,
                            detailText = "已停止",
                        )
                    )
                } else {
                    // 还有别的会话在跑：只把自己标成停止中，别把别人的卡片一起带走。
                    state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
                }
            }
        }
    }

    /**
     * 提交阶段（COMMITTING）超时兜底：用户点停止时 session 已进入提交窗口，
     * 正常情况下提交方很快发布终态并撤卡。若提交方卡死（beforePublish 死锁、
     * 执行线程丢失、进程异常），界面会永远停在「停止中」。这里安排一个长超时，
     * 超时后仍未终态则强制接管：取消并发布「已停止」、撤卡、同步运行表。
     */
    private fun scheduleCancelTimeoutFallback(runId: String, session: AgentRuntimeSession) {
        mainHandler.postDelayed({
            if (sessionFor(runId) !== session || session.isTerminal) return@postDelayed
            io.github.mangi.eta.core.EtaTrace.log(
                "run.cancel.timeout",
                "runId=$runId 提交窗口超时，强制接管终态",
            )
            val forced = session.forceTerminal("已停止")
            if (!forced) return@postDelayed
            RunControl.clearRun(runId, "runtime.cancel.timeout")
            RunProgressNotifier.removeRun(runId, pinned = fluidPinned())
            runningSessionInfo.remove(runId)
            syncRunningSessions()
            if (runningSessionCount() == 0) {
                enterFinalState(
                    AgentOverlayState(
                        phase = AgentOverlayPhase.FAILED,
                        status = AgentOverlayStatus.Stopped,
                        detailText = "已停止",
                    )
                )
            } else {
                state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
            }
        }, CANCEL_COMMITTING_TIMEOUT_MS)
    }

    private fun requestPause(runId: String?) {
        val targets = pauseTargets(runId) ?: return
        targets.forEach { session ->
            session.controller.pause()
            RunControl.setPaused(session.runId, true, "runtime.pause")
        }
        RunProgressNotifier.refresh()
        refreshOverlayPhase()
    }

    private fun requestResume(runId: String?) {
        val targets = pauseTargets(runId) ?: return
        targets.forEach { session ->
            session.controller.resume()
            RunControl.setPaused(session.runId, false, "runtime.resume")
        }
        RunProgressNotifier.refresh()
        refreshOverlayPhase()
    }

    /** runId 为空 = 全部；指定了但已结束 = 什么都不做，别误伤别的会话。 */
    private fun pauseTargets(runId: String?): List<AgentRuntimeSession>? {
        if (runId.isNullOrBlank()) return sessions.values.filter { !it.isTerminal }.ifEmpty { null }
        val session = sessions[runId] ?: return null
        if (session.isTerminal) return null
        return listOf(session)
    }

    /**
     * 胶囊是聚合视图：在跑的会话全部暂停才显示暂停态，
     * 只有部分暂停时保持运行态，避免「别的会话在跑、胶囊却说暂停」。
     */
    private fun refreshOverlayPhase() {
        val running = runningSessionCount()
        val paused = RunControl.state.value.pausedRuns.count { sessions.containsKey(it) }
        state.value = state.value.copy(
            phase = if (running > 0 && paused >= running) AgentOverlayPhase.PAUSED
            else AgentOverlayPhase.RUNNING,
            status = if (paused > 0) AgentOverlayStatus.Paused else AgentOverlayStatus.Reasoning,
        )
    }

    /** 某个 run 属于哪个会话；插话靠它做精确匹配。 */
    private fun conversationIdOfRun(runId: String): String =
        runningSessionInfo[runId]?.conversationId.orEmpty()

    /** 「上一次跑完的运行」属于哪个会话；续跑前也要核对，否则会拿别的会话的上下文续跑。 */
    private fun conversationIdOfCompletedRun(completed: CompletedRunContext?): String =
        completed?.request?.handoff
            ?.takeIf { handoff -> handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { handoff -> AgentUiHandoffPayload.from(handoff.payload).conversationId }
            .orEmpty()

    private fun steerRun(data: Bundle?, replyTo: Messenger?) {
        if (data == null) {
            sendSteerResponse("", replyTo, accepted = false)
            return
        }
        var runId = ""
        val text = try {
            runId = AgentRuntimeWire.runIdFromBundle(data)
            AgentRuntimeWire.steerTextFromBundle(data).trim()
        } catch (throwable: Throwable) {
            AndroidAgentLogger.warnThrottled("runtime_invalid_steer_request") {
                "Agent runtime rejected invalid steer request: type=${throwable.safeLogType()}"
            }
            ""
        } finally {
            AgentWireText.close(data)
        }
        val session = runId.takeIf(String::isNotBlank)?.let(sessions::get)
        val event = if (session != null && text.isNotBlank()) {
            session.steer(text) { recordSupplementEvent(session.runId, text) }
        } else {
            null
        }
        if (event != null) {
            AndroidAgentLogger.info(
                "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
            )
            state.value = state.value.applyEvent(event)
            sendSteerResponse(runId, replyTo, accepted = true, supplementIndex = event.index)
        } else {
            sendSteerResponse(runId, replyTo, accepted = false)
        }
    }

    private fun sendSteerResponse(
        runId: String,
        replyTo: Messenger?,
        accepted: Boolean,
        supplementIndex: Int = 0,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_STEER_RESPONSE)
            msg.data = AgentRuntimeWire.steerResponseBundle(runId, accepted, supplementIndex)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_steer_response_delivery_failed") {
                "Agent runtime steer response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun requestSupplement(text: String, conversationId: String = "") {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        val activeSessions = sessions.values.filter { session -> !session.isTerminal }
        val target = if (conversationId.isNotBlank()) {
            // 只投给同会话的 run；以前取「最后一个未结束的 run」，本机在跑长任务时
            // 别的会话发的内容会被吸进来（串回复），所以必须有会话标识才算匹配。
            activeSessions.lastOrNull { session -> conversationIdOfRun(session.runId) == conversationId }
        } else {
            // 调用方没给会话信息（旧客户端）：只有唯一候选时才敢投，避免误伤别的会话。
            activeSessions.singleOrNull()
        }
        if (target != null) {
            val event = target.steer(supplementText) {
                recordSupplementEvent(target.runId, supplementText)
            }
            if (event != null) {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                state.value = state.value.applyEvent(event)
                return
            }
            if (!target.isTerminal) {
                state.value = state.value.copy(
                    status = AgentOverlayStatus.Finishing,
                )
                return
            }
        }

        val completed = lastCompletedRunContext
        if (conversationId.isNotBlank() &&
            conversationIdOfCompletedRun(completed) != conversationId
        ) {
            io.github.mangi.eta.core.EtaTrace.log(
                "run.steer.reject",
                "插话无同会话运行 会话=$conversationId 在跑=${activeSessions.size}",
            )
            return
        }
        if (completed == null) return
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
    }

    /** 补充消息按 run 编号：索引与清单都跟着各自的 run 走，跨会话并行时互不覆盖。 */
    private fun recordSupplementEvent(runId: String, text: String): AgentEvent.UserSupplementReceived {
        val supplement = synchronized(supplementsLock) {
            val nextIndex = supplementIndexByRun[runId] ?: 1
            supplementIndexByRun[runId] = nextIndex + 1
            AgentUiHandoffPayload.Supplement(
                index = nextIndex,
                text = text,
                createdAt = System.currentTimeMillis(),
            ).also { supplementsByRun.getOrPut(runId) { mutableListOf() } += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
        )
    }

    private fun fluidPinned(): Boolean = runCatching {
        runBlocking { SettingsDataStore.settings() }.overlayFluidPinned
    }.getOrDefault(false)

    private fun usesVirtualUi(session: AgentRuntimeSession, virtualScreenEnabled: Boolean = false): Boolean =
        (session.virtualUiRouted.get() || virtualScreenEnabled) && !session.mainScreenFallbackApproved.get()

    private fun finishedNoticeState(finalState: AgentOverlayState): AgentOverlayState {
        val failed = finalState.phase == io.github.mangi.eta.agent.overlay.AgentOverlayPhase.FAILED
        // 完成后保留会话列表：胶囊展开后仍能点回刚跑完的会话。
        return finalState.copy(
            thought = if (failed) "任务失败，点开查看" else "已完成，点开查看结果",

        )
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    private fun AgentRuntimeWire.RunRequest.withActiveSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return this
        // 只取自己这个 run 的补充：全局清单会把别的会话的插话挂进来（串台根因之一）。
        val supplements = synchronized(supplementsLock) {
            supplementsByRun[runId]?.toList().orEmpty()
        }
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    internal companion object {
        /** 同时运行的会话上限：模型推理并行，工具执行在工具层排队。 */
        /** 会话并发的硬上限；实际并发由 AgentMemoryGate.sessionLimit() 按剩余内存决定。 */
        const val MAX_CONCURRENT_RUNS = 6
        /** 排队最长保留时间；超时说明内存一直紧，直接放弃，不让会话永远挂着。 */
        private const val MEMORY_QUEUE_TTL_MS = 5 * 60 * 1000L
        /** 排队巡检间隔。 */
        private const val MEMORY_QUEUE_INTERVAL_MS = 5_000L
        /** 排队上限，避免内存紧张时请求无限堆积。 */
        private const val MEMORY_QUEUE_MAX = 8

        /**
         * 取消进入提交窗口（COMMITTING）后的超时兜底时长：提交方正常应在毫秒级完成
         * beforePublish 并发布终态；超过 30s 仍无终态视为提交方卡死，强制接管发布「已停止」。
         */
        private const val CANCEL_COMMITTING_TIMEOUT_MS = 30_000L

        const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        /** 胶囊展开列表里最多保留几条会话（运行中 + 最近完成）。 */
        const val SESSION_LIST_LIMIT = 3

        const val ACTION_PIN_CAPSULE = "io.github.mangi.eta.agent.runtime.PIN_CAPSULE"
        const val ACTION_UNPIN_CAPSULE = "io.github.mangi.eta.agent.runtime.UNPIN_CAPSULE"
        const val ACTION_CLEAR_READ = "io.github.mangi.eta.agent.runtime.CLEAR_READ"
        /** 单条会话已读：进过哪个会话就减哪个，立刻生效。 */
        const val ACTION_MARK_READ = "io.github.mangi.eta.agent.runtime.MARK_READ"
        /** 流体云卡片上的暂停／继续／停止动作，走 startService 直接落到本服务。 */
        const val ACTION_PAUSE_RUNS = "io.github.mangi.eta.agent.runtime.PAUSE_RUNS"
        const val ACTION_RESUME_RUNS = "io.github.mangi.eta.agent.runtime.RESUME_RUNS"
        const val ACTION_STOP_RUNS = "io.github.mangi.eta.agent.runtime.STOP_RUNS"
        /** 按会话的暂停／恢复／停止：流体云每张卡片与仪表盘都带 runId，互不牵连。 */
        const val ACTION_PAUSE_RUN = "io.github.mangi.eta.agent.runtime.PAUSE_RUN"
        const val ACTION_RESUME_RUN = "io.github.mangi.eta.agent.runtime.RESUME_RUN"
        const val ACTION_STOP_RUN = "io.github.mangi.eta.agent.runtime.STOP_RUN"
        const val EXTRA_RUN_ID = "io.github.mangi.eta.agent.runtime.EXTRA_RUN_ID"
        const val ACTION_SUPPLEMENT_RUN = "io.github.mangi.eta.agent.runtime.SUPPLEMENT_RUN"
        const val EXTRA_SUPPLEMENT_TEXT = "io.github.mangi.eta.agent.runtime.EXTRA_SUPPLEMENT_TEXT"
        const val EXTRA_SUPPLEMENT_CONVERSATION_ID = "io.github.mangi.eta.agent.runtime.EXTRA_SUPPLEMENT_CONVERSATION_ID"
        /** 设置里切换「流体云常驻」后立即刷新空闲条目。 */
        const val ACTION_REFRESH_FLUID = "io.github.mangi.eta.agent.runtime.REFRESH_FLUID"
        const val EXTRA_MARK_READ_CONVERSATION_ID = "io.github.mangi.eta.agent.runtime.MARK_READ_CONVERSATION_ID"
        const val HIDE_DELAY_MS = 2_500L
        const val RESULT_REVIEW_DELAY_MS = 120_000L
        private const val RESULT_OPEN_REQUEST_CODE = 1108

        /** 最近打开过的对话：内部最多记 5 条，面板上最多显示 3 条（多的折叠）。 */
        /** 胶囊允许贴到屏幕边缘（0 = 不留白），展开面板的留白另有处理。 */
        /** 「最新回复」栏最多列几条。 */
        /** 展开面板基础高度：顶栏 + 操作按钮。 */
        /** 每条会话占的高度（紧凑，但整行都能点）。 */
        /** 展开面板高度上限：约占屏幕 1/3，别把屏幕占满。 */
        /** 注入点击只是一个点，目标控件常贴着胶囊边缘，判定范围向外多留一点。 */
        const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
