package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeInFlightEventEntity
import io.github.mangi.eta.data.db.RuntimeInFlightRunEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 在途 run 的 UI 事件、完整脱敏 transcript 与模型上下文快照。
 * 三者用途独立；配置和密钥不落盘，敏感工具原始数据由会话 codec 过滤。
 */
internal object AgentRunCheckpointStore {
    /** 单次检查点写入超过这个时长才记一行；实测正常在 1 位数毫秒。 */
    private const val SLOW_APPEND_MS = 150L

    /**
     * transcript 增量节流阈值（字符）。
     *
     * onTranscript 每轮都会回调一次，而 saveTranscript 每次都要全量编码 + 全量写库；
     * 长会话下这等于每轮都重新分配一整份 JSON。累积增量没到这个量级就先不落库，
     * run 结束/取消时用 force = true 冲刷最后一次。
     */
    private const val CHECKPOINT_MIN_DELTA_CHARS = 400_000L

    /** 每个 run 的父行副本：并发路径把父行清掉时用它补写，避免在途事件外键失败。 */
    private val seededRuns =
        java.util.concurrent.ConcurrentHashMap<String, RuntimeInFlightRunEntity>()

    /** 每个 runId 上次真正写库时的 transcript 总字符数（节流基准）。 */
    private val writtenTranscriptChars =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 每个 runId 上次真正写库时的 transcript 条数：条数变少说明压缩/回滚，必须无条件落库。 */
    private val writtenTranscriptCounts =
        java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 只按字符数估算 transcript 体积，不物化任何新字符串。
     * 与 codec 实际序列化体积不要求相等，只要同向单调即可用于节流判断。
     */
    private fun measureTranscriptChars(transcript: List<AgentModelClient.ConversationMessage>): Long {
        var total = 0L
        for (message in transcript) {
            total += message.content.length.toLong()
            total += message.contentJson.length.toLong()
            total += message.reasoningContent.length.toLong()
            total += message.toolCallsJson.length.toLong()
            total += message.toolCallId.length.toLong()
        }
        return total
    }

    data class Checkpoint(
        val runId: String,
        val ownerInstanceId: String,
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val createdAt: Long,
        val updatedAt: Long,
        val contextSnapshot: AgentContextSnapshot? = null,
        val operation: String = AgentRuntimeWire.OP_CHAT,
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
        val rewriteTargetMessageId: String? = null,
    )

    fun start(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return false
        val runId = request.runId.takeIf(String::isNotBlank) ?: return false
        val entity = RuntimeInFlightRunEntity(
            runId = runId,
            ownerInstanceId = ownerInstanceId,
            operation = request.operation,
            rewriteTargetMessageId = request.rewriteTargetMessageId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            createdAt = now,
            updatedAt = now,
        )
        seededRuns[runId] = entity
        // start 会用 transcriptJson = "[]" 重建父行，等于把库里的 transcript 清空；
        // 上一轮同 runId 残留的节流基准必须一起清掉，否则新一轮首写会被误判为「增量不足」而跳过。
        writtenTranscriptChars.remove(runId)
        writtenTranscriptCounts.remove(runId)
        io.github.mangi.eta.core.EtaTrace.log(
            "ckpt.start",
            "runId=$runId operation=${request.operation}",
        )
        val startedAt = android.os.SystemClock.elapsedRealtime()
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().replaceInFlightRun(entity)
        }
        val spent = android.os.SystemClock.elapsedRealtime() - startedAt
        if (spent >= SLOW_APPEND_MS) {
            io.github.mangi.eta.core.EtaTrace.log(
                "perf.ckpt.slow_start",
                "runId=$runId ms=$spent",
            )
        }
        return true
    }

    fun append(
        context: Context,
        runId: String,
        sortIndex: Int,
        event: AgentEvent,
        now: Long = System.currentTimeMillis(),
    ) {
        val durableEvent = event.recoveryProjection() ?: return
        val dao = EtaDatabase.get(context.applicationContext).runtimeRunDao()
        val row = RuntimeInFlightEventEntity(
            runId = runId,
            sortIndex = sortIndex,
            eventJson = AgentEventJsonCodec.encode(durableEvent),
        )
        val writeStartedAt = android.os.SystemClock.elapsedRealtime()
        try {
            runBlocking(Dispatchers.IO) { dao.appendInFlightEvent(row, now) }
        } catch (constraint: android.database.sqlite.SQLiteConstraintException) {
            // 并发会话下父行可能被其它路径清掉；外键失败会让失败现场整条丢失，
            // 这里用单事务「补写父行 + 追加事件」，避免 upsert 与 append 之间再次被
            // remove 竞态切开。seededRuns 副本若已被 remove 清掉，先确认 DB 里是否
            // 还有父行：有就直接重试，确实没有才抛。
            io.github.mangi.eta.core.EtaTrace.log(
                "ckpt.recover",
                "runId=$runId sortIndex=$sortIndex 父行缺失已补写：${constraint.message}",
            )
            val seed = seededRuns[runId]
            if (seed != null) {
                runBlocking(Dispatchers.IO) {
                    dao.recoverAppendInFlightEvent(seed, row, now)
                }
            } else {
                val parentStillPresent = runBlocking(Dispatchers.IO) {
                    dao.hasInFlightRun(runId)
                }
                if (parentStillPresent) {
                    runBlocking(Dispatchers.IO) { dao.appendInFlightEvent(row, now) }
                } else {
                    throw constraint
                }
            }
        } finally {
            // 检查点写入是每次事件同步落库，属主线程/执行线程上的阻塞点；
            // 只有明显慢的才记，避免每个 delta 刷一行。
            val spent = android.os.SystemClock.elapsedRealtime() - writeStartedAt
            if (spent >= SLOW_APPEND_MS) {
                io.github.mangi.eta.core.EtaTrace.log(
                    "perf.ckpt.slow_append",
                    "runId=$runId sortIndex=$sortIndex ms=$spent kind=${durableEvent::class.simpleName}",
                )
            }
        }
    }

    /** 返回所有未确认 run；是否 active 或已完成由恢复协调器结合 Runtime 状态判断。 */
    fun list(context: Context): List<Checkpoint> =
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .inFlightRuns()
                .asSequence()
                .map { stored ->
                    Checkpoint(
                        runId = stored.run.runId,
                        ownerInstanceId = stored.run.ownerInstanceId,
                        contextSnapshot = AgentContextSnapshot.decode(stored.run.contextSnapshotJson),
                        operation = stored.run.operation,
                        rewriteTargetMessageId = stored.run.rewriteTargetMessageId,
                        transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.decodeTranscript(stored.run.transcriptJson)),
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = stored.run.handoffId,
                            source = stored.run.handoffSource,
                            payload = stored.run.handoffPayload,
                            dismissEntrySurfaceOnForegroundOperation =
                                stored.run.dismissEntrySurface,
                        ),
                        events = stored.events
                            .sortedBy { it.sortIndex }
                            .mapNotNull { AgentEventJsonCodec.decode(it.eventJson) },
                        createdAt = stored.run.createdAt,
                        updatedAt = stored.run.updatedAt,
                    )
                }
                .toList()
        }

    fun saveTranscript(
        context: Context,
        runId: String,
        transcript: List<AgentModelClient.ConversationMessage>,
        /** run 结束/取消时冲刷最后一次：跳过增量节流直接写库。 */
        force: Boolean = false,
    ) {
        val currentChars = measureTranscriptChars(transcript)
        val previousChars = writtenTranscriptChars[runId]
        if (!force && previousChars != null) {
            val previousCount = writtenTranscriptCounts[runId]
            // 压缩/回滚会让 transcript 变短：条数或字符数下降都必须无条件落库。
            // 否则库里留着更长的旧版本，恢复时会把已被压缩掉的消息重新读回来（幽灵消息）。
            val shrunk = (previousCount != null && transcript.size < previousCount) ||
                currentChars < previousChars
            // 增量不到阈值就跳过写库；节流跳过不记日志，避免每轮刷屏。
            if (!shrunk && currentChars - previousChars < CHECKPOINT_MIN_DELTA_CHARS) return
        }
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao()
                .updateTranscript(runId, AgentConversationCodec.encodeTranscriptForStorage(transcript))
        }
        writtenTranscriptChars[runId] = currentChars
        writtenTranscriptCounts[runId] = transcript.size
    }

    fun saveContext(context: Context, runId: String, snapshot: AgentContextSnapshot) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().updateContextSnapshot(runId, snapshot.encode())
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        io.github.mangi.eta.core.EtaTrace.log(
            "ckpt.remove",
            "runId=$runId 调用方=${io.github.mangi.eta.core.EtaTrace.callerHint()}",
        )
        // seededRuns 副本与 DB 行必须同步清理：否则 remove 后残留的旧 seed 会在下次
        // append 触发 FK 恢复时，把已被清掉的 run 的旧父行（甚至同 runId 新会话的父行）
        // 用过期数据顶回来，造成事件错挂旧 run。
        seededRuns.remove(runId)
        // 节流基准与 DB 行同样必须同步清理：否则同 runId 的新会话会继承旧基准，
        // 首轮写入被误判为「增量不足」而跳过，库里直接缺一段 transcript。
        writtenTranscriptChars.remove(runId)
        writtenTranscriptCounts.remove(runId)
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .deleteInFlightRun(runId)
        }
    }
}
