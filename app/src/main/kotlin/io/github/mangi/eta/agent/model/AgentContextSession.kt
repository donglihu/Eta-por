package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    private val mainToolsProvider: () -> JSONArray = { JSONArray() },
    private val mainSessionId: String = operationId,
    private val messageProjector: ((JSONArray) -> JSONArray)? = null,
    private val enableCacheReuse: Boolean = false,
) {
    private val contextWindow = config.knownContextWindow
    private var inputTokens: Int? = null
    /**
     * 自动压缩失败后的重试点（绝对输入用量）。
     *
     * 失败一次就记下「当前用量 + 5% 窗口」，只有用量涨过这条线才允许再试；
     * 否则一个长任务里每个 round 都会重打一次注定失败的摘要（手动压缩不受影响，
     * 它是用户点了就要立刻出结果的路径）。
     */
    private var autoRetryAtTokens: Int? = null
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun observeInputTokens(tokens: Int?) {
        inputTokens = tokens?.takeIf { it >= 0 }
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false) {
        val before = inputTokens
        if (SignedToolContext.hasPendingResponse(messages)) {
            if (force) {
                throw SignedToolContext.compactionFailure(messages)
            }
            return
        }
        // 触发条件 = 「允许自动压缩」且「阈值百分比 > 0」且「实际输入用量 >= 窗口 × 阈值」。
        // 0 或布尔关闭都表示不自动压缩，交给用户在需要时手动压缩。
        // 另外：上一次自动压缩失败后要等到用量涨过 5% 窗口才重试，避免每轮都重打一次。
        val triggerRatio = config.autoCompactionTriggerRatio
        val waitingForRetry = !force && autoRetryAtTokens?.let { before == null || before < it } == true
        if (!force && (waitingForRetry || !config.autoCompactionEnabled || triggerRatio <= 0.0 ||
                contextWindow == null || before == null || before < contextWindow * triggerRatio)
        ) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before))
        try {
            val outcome = AgentContextCompactor(
                config = config,
                provider = provider,
                controller = runController,
                roleplay = roleplay,
                mainTools = mainToolsProvider(),
                mainSessionId = mainSessionId,
                messageProjector = messageProjector,
                enableCacheReuse = enableCacheReuse,
            ).compactWithOutcome(
                messages, systemCount, sensitiveIds(),
            )
            val candidate = outcome.messages
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            inputTokens = null
            autoRetryAtTokens = null
            onEvent(
                AgentEvent.ContextCompaction(
                    operationId = operation,
                    phase = AgentEvent.ContextCompaction.PHASE_COMPLETED,
                    tokensBefore = before,
                    usage = outcome.usage,
                    archiveId = outcome.archiveId,
                ),
            )
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            // 自动压缩失败：记下 5% 窗口的退试点，别让长任务每个 round 都重打一次注定失败的摘要。
            if (!force) {
                autoRetryAtTokens = contextWindow?.takeIf { it > 0 }?.let { window ->
                    before?.plus(window / 20)
                }
            }
            // 手动压缩（force）与收尾快照必须显式失败：用户点了压缩就要看到真实结果。
            // 自动压缩只是后台优化，失败的代价只能是「这一轮没压成」——原始上下文完整保留，
            // 下一轮用量继续上涨会再触发；绝不因为一次摘要失败把整轮回答打成运行错误。
            if (force || final) {
                if (!final) throw failure
                // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
                committedSnapshot = createSnapshot(messages)
            }
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }
}
