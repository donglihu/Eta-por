package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 一次 Runtime run 的控制权和唯一终态。
 *
 * Service 替换、用户取消和正常完成都必须经过此对象，避免旧 run 向新 reply channel 发消息，
 * 也避免同一 run 发送两个最终结果。
 */
internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    eventSink: ((AgentEvent) -> Unit)? = null,
    resultSink: ((AgentRuntimeWire.RunResult) -> Unit)? = null,
    private val operation: String = AgentRuntimeWire.OP_CHAT,
    /** 这次 run 属于哪条对话；系统入口/子智能体可能为空，结束通知就退回 runId。 */
    val conversationKey: String = "",
    /**
     * 派发那一刻的会话标题快照。
     *
     * 结束通知在主线程排队执行，而停止路径上撤卡（RunProgressNotifier.removeRun）和
     * runningSessionInfo.remove 早就先跑完了：靠卡片或运行表反查标题只能拿到空串，
     * 结束通知就退化成「已停止」，用户看不出是哪条对话。标题是启动时已知的常量，
     * 因此随会话一起带走，不依赖任何会被提前清掉的运行时状态。
     */
    val conversationTitle: String = "",
) {
    val virtualUiRouted = java.util.concurrent.atomic.AtomicBoolean(false)
    val mainScreenFallbackApproved = java.util.concurrent.atomic.AtomicBoolean(false)
    private enum class State {
        RUNNING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    private var latestTranscript: List<AgentModelClient.ConversationMessage> = emptyList()
    val transcript: List<AgentModelClient.ConversationMessage>
        get() = lock.withLock { latestTranscript }

    fun updateTranscript(messages: List<AgentModelClient.ConversationMessage>) = lock.withLock {
        if (state == State.RUNNING) latestTranscript = messages
    }

    private var latestContext: AgentContextSnapshot? = null
    val contextSnapshot: AgentContextSnapshot?
        get() = lock.withLock { latestContext }

    fun updateContext(snapshot: AgentContextSnapshot) = lock.withLock {
        if (state == State.RUNNING) latestContext = snapshot
    }
    private var state = State.RUNNING
    private val replayEvents = mutableListOf<AgentEvent>()
    private val searchSuggestionsBudget = SearchSuggestionsBudget()
    private val subscribers = mutableListOf<Subscriber>()

    private data class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        val kind: SubscriberKind = SubscriberKind.RUN,
        /** 界面重连后由最新订阅者接管事件；旧订阅者只保留结果回执，避免同一增量投递多遍。 */
        var receivingEvents: Boolean = true,
    )

    /** 订阅来源：run 是本次执行的发起方，attach 是界面重连。 */
    private enum class SubscriberKind { RUN, ATTACH }

    init {
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
            )
        }
    }

    val isTerminal: Boolean
        get() = lock.withLock { state == State.TERMINAL }

    fun emit(event: AgentEvent): Boolean =
        lock.withLock {
            if (state != State.RUNNING) return false
            if (event is AgentEvent.SearchSuggestions &&
                !searchSuggestionsBudget.accept(event.round, event.index, event.html)) return false
            recordForReplay(event)
            subscribers.forEach { subscriber ->
                if (subscriber.receivingEvents) subscriber.eventSink(event)
            }
            true
        }

    /**
     * Activity 被移出任务栈后 Runtime 仍可能继续执行。安全历史回放、完成确认和实时订阅
     * 共用同一把锁，保证客户端收到确认前的事件都是历史，新增事件与终态不会越过边界。
     */
    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
    ): Boolean = lock.withLock {
        if (state == State.TERMINAL) return false
        // 界面重连时旧的 attach 订阅者对应的界面已经销毁，留着只会把同一事件投递多遍。
        subscribers.removeAll { it.kind == SubscriberKind.ATTACH }
        subscribers.forEach { it.receivingEvents = false }
        replayEvents.forEach { event ->
            // 文本增量不回放：界面已经画过的内容再追加一遍就是重复；
            // 轮次、工具、结果等结构事件仍按原样补齐。
            if (event is AgentEvent.AssistantBlockDelta) return@forEach
            eventSink(event)
        }
        onReplayComplete()
        subscribers += Subscriber(eventSink, resultSink, SubscriberKind.ATTACH)
        true
    }

    fun steer(text: String): Boolean =
        lock.withLock {
            if (state != State.RUNNING || operation != AgentRuntimeWire.OP_CHAT) return false
            controller.steer(text)
        }

    fun <T : AgentEvent> steer(
        text: String,
        eventFactory: () -> T,
    ): T? =
        lock.withLock {
            if (state != State.RUNNING || operation != AgentRuntimeWire.OP_CHAT || !controller.steer(text)) return null
            eventFactory().also { event ->
                recordForReplay(event)
                subscribers.forEach { subscriber ->
                    if (subscriber.receivingEvents) subscriber.eventSink(event)
                }
            }
        }

    private fun recordForReplay(event: AgentEvent) {
        val projected = event.replayProjection() ?: return
        if (projected is AgentEvent.SubagentProgress) {
            val existingIndex = replayEvents.indexOfLast {
                it is AgentEvent.SubagentProgress &&
                    it.round == projected.round &&
                    it.toolCallId == projected.toolCallId
            }
            if (existingIndex >= 0) {
                replayEvents[existingIndex] = projected
            } else {
                replayEvents += projected
            }
            return
        }
        if (projected !is AgentEvent.AssistantBlockDelta) {
            replayEvents += projected
            return
        }
        val previous = replayEvents.lastOrNull() as? AgentEvent.AssistantBlockDelta
        if (
            previous != null &&
            previous.round == projected.round &&
            previous.kind == projected.kind &&
            previous.index == projected.index
        ) {
            replayEvents[replayEvents.lastIndex] = previous.copy(
                // 回放不投递文本增量：attach 逐条跳过 AssistantBlockDelta，界面文本由终态提供。
                // 这里只累加计数、不再拼接整段增量，省掉每来一片都重拼全文的 O(k²) 复制。
                deltaChars = previous.deltaChars + projected.deltaChars,
                delta = "",
            )
        } else {
            replayEvents += projected
        }
    }

    /**
     * 先原子竞争 COMMITTING，再完成提交前副作用和结果发布。取消与替换不能越过提交胜者，
     * 因而不会出现“客户端收到取消、outbox 却留下成功结果”的分裂状态；耗时 I/O 也不持有锁。
     * [beforePublish] 必须自行吸收非致命持久化异常。
     */
    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: () -> Unit = {},
    ): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
            require(result.runId == runId) { "Result runId does not match the active session" }
            state = State.COMMITTING
        }
        val commitFailure = runCatching(beforePublish).exceptionOrNull()
        lock.withLock {
            state = State.TERMINAL
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
            searchSuggestionsBudget.clear()
        }
        commitFailure?.let { throw it }
        return true
    }

    fun cancel(reason: String): Boolean {
        val result = lock.withLock {
            if (state != State.RUNNING) return false
            state = State.TERMINAL
            AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = reason,
                contextSnapshot = latestContext,
                transcript = AgentToolBatchRecovery.completeInterrupted(latestTranscript),
                operation = operation,
            )
        }
        controller.cancel()
        lock.withLock {
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
            searchSuggestionsBudget.clear()
        }
        return true
    }

    /**
     * 超时兜底的强制终态：仅当提交方在 COMMITTING 窗口卡死（beforePublish 死锁 /
     * 执行线程丢失）时由取消超时路径调用。与 [cancel] 不同，它允许从 COMMITTING
     * 转移，保证 UI 不会永远停在「停止中」。发布前清空 subscribers，使稍后完成的
     * complete() 无法再向订阅者投递第二个结果（其 forEach 因订阅者已清而空转）。
     */
    fun forceTerminal(reason: String): Boolean {
        val result = lock.withLock {
            if (state == State.TERMINAL) return false
            state = State.TERMINAL
            AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = reason,
                contextSnapshot = latestContext,
                transcript = AgentToolBatchRecovery.completeInterrupted(latestTranscript),
                operation = operation,
            )
        }
        controller.cancel()
        lock.withLock {
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
            searchSuggestionsBudget.clear()
        }
        return true
    }
}
