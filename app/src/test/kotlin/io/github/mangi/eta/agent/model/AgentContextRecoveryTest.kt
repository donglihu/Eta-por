package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextRecoveryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = 900_000,
    )

    @Test
    fun unknownWindowStillRunsButNeverAutoCompacts() {
        for (window in listOf(null, 0, -1)) {
            var summaries = 0
            val result = AgentModelClient.complete(config.copy(contextWindow = window), "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("摘要")
                    } else {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10_000_000)))
                        response("完成")
                    }
                })
            assertEquals("完成", result.content)
            assertEquals(0, summaries)
        }
    }

    @Test
    fun thresholdSettingDrivesAutomaticCompactionBoundary() {
        // 阈值来自设置（0=关闭，其余为窗口占用百分比），不再是写死的 85%。
        fun summariesAt(percent: Int, input: Int): Pair<Int, Boolean> {
            var summaries = 0
            val session = AgentContextSession(
                config.copy(autoCompactionTriggerPercent = percent), messages(), 1, "operation",
                provider { _, _ -> summaries++; response("历史已完成。") },
                AgentRunController(), { emptySet() }, {}, {},
            )
            session.observeInputTokens(input)
            session.compact()
            return summaries to (session.snapshot() != null)
        }
        // 900_000 窗口：60% → 540_000 触发；90% → 810_000 触发。
        assertEquals(0 to false, summariesAt(60, 539_999))
        assertEquals(1 to true, summariesAt(60, 540_000))
        assertEquals(0 to false, summariesAt(90, 809_999))
        assertEquals(1 to true, summariesAt(90, 810_000))
        // 0 = 关闭：用量再高也不自动压缩。
        assertEquals(0 to false, summariesAt(0, 890_000))
    }

    @Test
    fun failedAutomaticSummaryKeepsTheRoundAndOriginalHistory() {
        // 自动压缩是后台优化：摘要请求失败只能「这次没压成」，不能把整轮回答打成运行错误。
        val messages = messages()
        val original = messages.toString()
        var compactionRequests = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { request, _ ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                compactionRequests++
                throw AgentModelFailure("MODEL_TIMEOUT", true, "fixture")
            }
            response("不应走到这里")
        }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交") })
        session.observeInputTokens(765_000)
        session.compact()
        assertEquals(1, compactionRequests)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun failedAutomaticSummaryBacksOffBeforeRetrying() {
        // 失败重试要按用量退避（5% 窗口），否则长任务每个 round 都重打一次注定失败的摘要。
        val messages = messages()
        var compactionRequests = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { request, _ ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                compactionRequests++
                throw AgentModelFailure("MODEL_TIMEOUT", true, "fixture")
            }
            response("不应走到这里")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.observeInputTokens(765_000)
        session.compact()
        session.compact()
        assertEquals(1, compactionRequests)
        // 900_000 窗口的 5% = 45_000：涨满才允许再试。
        session.observeInputTokens(765_000 + 44_999)
        session.compact()
        assertEquals(1, compactionRequests)
        session.observeInputTokens(765_000 + 45_000)
        session.compact()
        assertEquals(2, compactionRequests)
    }

    @Test
    fun automaticCompactionUsesOnlyActualInputUsageAndConfiguredWindow() {
        // 阈值显式给 80%（当前默认档），边界 = 900_000 × 80% = 720_000；
        // 不再依赖 ModelConfig 的默认值，避免默认档位调整时这条用例跟着漂。
        val thresholdConfig = config.copy(autoCompactionTriggerPercent = 80)
        for (input in listOf(null, 300_000, 719_999, 720_000)) {
            var summaries = 0
            val events = mutableListOf<AgentEvent>()
            val result = AgentModelClient.complete(thresholdConfig, "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), onEvent = events::add, provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("此前任务已完成。")
                    } else {
                        assertTrue(request.messages.toString().contains("长".repeat(100)))
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = input, outputTokens = 800_000)))
                        response("完成")
                    }
                })
            assertEquals(if (input == 720_000) 1 else 0, summaries)
            assertEquals("完成", result.content)
            val completed = events.filterIsInstance<AgentEvent.ContextCompaction>().lastOrNull()
            if (input == 720_000) {
                assertEquals(720_000, completed!!.tokensBefore)
                assertNull(completed.tokensAfter)
            } else assertNull(completed)
        }
    }

    @Test
    fun disabledAutomaticCompactionStillAllowsManualCompaction() {
        val disabled = config.copy(autoCompactionEnabled = false)
        var summaries = 0
        val events = mutableListOf<AgentEvent>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                response("此前任务已完成。")
            } else {
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                response("完成")
            }
        }
        val answer = AgentModelClient.complete(disabled, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, onEvent = events::add)
        assertEquals("完成", answer.content)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.ContextCompaction })
        assertNull(answer.contextSnapshot)

        val manual = AgentModelClient.complete(disabled, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, compactOnly = true)
        assertEquals(1, summaries)
        assertNotNull(manual.contextSnapshot)
        assertTrue(manual.transcript.isEmpty())
    }

    @Test
    fun compactionDoesNotRepeatUntilNewUsageArrives() {
        val messages = messages()
        var summaries = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            summaries++
            response("历史已完成。")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.observeInputTokens(765_000)
        session.compact()
        session.compact()
        assertEquals(1, summaries)
        assertNotNull(session.snapshot())
    }

    @Test
    fun summaryFailureDoesNotSplitOrRetryAndKeepsOriginalHistory() {
        for (code in listOf("CONTEXT_OVERFLOW", "MODEL_TIMEOUT")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                throw AgentModelFailure(code, true, "fixture")
            }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals(code, failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun invalidSummaryDoesNotRetryAndKeepsOriginalHistory() {
        for ((content, stop) in listOf("" to "stop", "半截" to "length", "长".repeat(12_001) to "stop")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                response(content, stop)
            }, AgentRunController(), { emptySet() }, {}, { fail("不应提交无效摘要") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
        }
    }

    @Test
    fun emptyLengthResponseIsNotReplayedEvenWhenInputIsNearWindow() {
        var requests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    response("", "length")
                })
        }
        assertEquals("MODEL_OUTPUT_LIMIT", (failure.cause as AgentModelFailure).code)
        assertEquals(1, requests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun failedChatAttemptUsageCannotTriggerCompactionAfterRetry() {
        var requests = 0
        val loop = AgentLoop(
            config, messages(), JSONArray(), provider { request, emit ->
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                if (++requests == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    throw AgentModelFailure.incompleteStream("fixture")
                }
                response("完成")
            }, AgentModelClient.ToolExecutor { error("不应执行工具") }, AgentRunController(), AgentTraceFormatter(), {},
            modelRetry = AgentModelRetry { _, _ -> }, systemCount = 1,
        )
        assertEquals("完成", loop.run().content)
        assertEquals(2, requests)
        assertNull(loop.contextSnapshot())
    }

    @Test
    fun summaryCancellationKeepsOriginalContext() {
        val messages = messages()
        val original = messages.toString()
        val controller = AgentRunController()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            controller.cancel()
            response("不应提交")
        }, controller, { emptySet() }, {}, { fail("取消时不能提交") })
        assertThrows(Exception::class.java) { session.compact(force = true) }
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun onlySummaryRequestsHaveAnOverallTimeout() {
        assertEquals(300_000, AgentHttpClient.modelClient(ProviderRequestPurpose.COMPACTION).callTimeoutMillis)
        assertEquals(0, AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT).callTimeoutMillis)
    }

    private fun history() = listOf(
        AgentModelClient.ConversationMessage("user", "旧任务"),
        AgentModelClient.ConversationMessage("assistant", "长".repeat(780_000)),
    )

    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束")).also { source ->
        history().forEach { source.put(AgentConversationCodec.toJsonObject(it)) }
    }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}
