package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunMessageProjectorTest {
    @Test
    fun searchSuggestionsBindToExactTextBlockAndDoNotPersistIntoAnotherRun() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta("run", 1, 0, "第一段", emptyList())
        messages = projector.appendTextDelta("run", 1, 3, "带来源的回答", messages)
        val event = AgentEvent.SearchSuggestions(1, 3, "<div>搜索建议</div>")
        val attached = projector.applyEvent("run", event, messages)
        assertTrue((attached[0] as AgentMessageUi).searchSuggestionsHtml.isEmpty())
        assertEquals(listOf(event.html), (attached[1] as AgentMessageUi).searchSuggestionsHtml)
        assertSame(attached, projector.applyEvent("run", event, attached))
        assertSame(attached, projector.applyEvent("other", event, attached))
        assertSame(attached, projector.applyEvent("run", event.copy(index = 7), attached))
        val finalized = projector.finalizeRun("run", attached)
        assertEquals(listOf(event.html), (finalized[1] as AgentMessageUi).searchSuggestionsHtml)
        assertTrue(projector.resetForReplay("run", finalized).isEmpty())
    }

    @Test
    fun searchSuggestionsEnforcePerRoundBudgetAcrossMultipleAnswerBlocks() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta("run", 1, 0, "第一段", emptyList())
        messages = projector.appendTextDelta("run", 1, 1, "第二段", messages)
        repeat(5) { index ->
            messages = projector.applyEvent("run", AgentEvent.SearchSuggestions(1, index % 2, "建议-$index"), messages)
        }
        assertEquals(5, messages.filterIsInstance<AgentMessageUi>().sumOf { it.searchSuggestionsHtml.size })
        assertSame(messages, projector.applyEvent("run", AgentEvent.SearchSuggestions(1, 0, "第六个"), messages))
    }

    @Test
    fun eventsWithoutVisibleChangeReturnSameListSoCallerSkipsTimestamp() {
        val projector = AgentRunMessageProjector { 1_000L }
        val messages = projector.appendTextDelta("run", 1, 0, "回答", emptyList())
        listOf(
            AgentEvent.RoundStarted(1, 2),
            AgentEvent.ProviderRequestStarted(1),
            AgentEvent.AssistantReceived(1, 2, "", emptyList()),
            AgentEvent.UsageReceived(1, AgentTokenUsage(null, null, null, null, null)),
            AgentEvent.UsageReceived(9, AgentTokenUsage(null, 10, 2, null, null)),
            AgentEvent.UserSupplementReceived(0, "补充"),
        ).forEach { event -> assertSame(event.toString(), messages, projector.applyEvent("run", event, messages)) }
    }

    @Test
    fun usageAttachesToRoundWithoutRestartingStreaming() {
        val projector = AgentRunMessageProjector { 1_000L }
        val streaming = projector.appendTextDelta("run", 1, 0, "回答", emptyList())
        val finished = projector.finalizeTextBlock("run", 1, 0, null, streaming)
        val withUsage = projector.applyEvent("run", AgentEvent.UsageReceived(1, AgentTokenUsage(100, 80, 20, null, null)), finished)
        val message = withUsage.filterIsInstance<AgentMessageUi>().single()
        assertEquals(100, message.usage?.contextTokens)
        assertFalse(message.isStreaming)
    }

    @Test
    fun applyResultFillsSingleBlockKeepsMultiBlockAndReplacesWithNotice() {
        val projector = AgentRunMessageProjector { 1_000L }
        val single = projector.appendTextDelta("run", 1, 0, "流式半截", emptyList())
        assertEquals("完整回答", AgentRunMessageProjector.applyResult("run", single, "完整回答", null)
            .filterIsInstance<AgentMessageUi>().single().content)

        val multi = projector.appendTextDelta("run", 1, 1, "第二段", projector.finalizeTextBlock("run", 1, 0, null, single))
        assertEquals(listOf("流式半截", "第二段"), AgentRunMessageProjector.applyResult("run", multi, "合并全文", null)
            .filterIsInstance<AgentMessageUi>().map { it.content })

        val failed = AgentRunMessageProjector.applyResult("run", single, "", SystemNoticeCode.RuntimeFailed, "错误")
        val notice = failed.single() as SystemNoticeMessageUi
        assertEquals(single.single().id, notice.id)
        assertEquals("错误", notice.detail)

        val empty = AgentRunMessageProjector.applyResult("other", emptyList(), "回答", null)
        assertEquals("assistant-other-1", empty.single().id)
    }

    @Test
    fun retryKeepsFailedAttemptSeparateAndReplayClearsItsNotice() {
        val projector = AgentRunMessageProjector { 1_000L }
        val partial = projector.appendTextDelta("retry-run", 2, 0, "半截", emptyList())
        val event = AgentEvent.ModelRetryScheduled(2, 1, 3, 2_000, "MODEL_TIMEOUT")
        val retrying = projector.scheduleModelRetry("retry-run", event, partial)
        assertEquals(-1, AgentRunMessageProjector.resultTargetIndex("retry-run", retrying))
        assertEquals("assistant-retry-run-3-result", AgentRunMessageProjector.resultFallbackId("retry-run", retrying))
        val repeated = projector.scheduleModelRetry("retry-run", event, retrying)
        assertEquals(retrying, repeated)
        val resumed = projector.appendTextDelta("retry-run", 3, 0, "完整回答", retrying)
        assertEquals(listOf("半截", "完整回答"), resumed.filterIsInstance<AgentMessageUi>().map { it.content })
        assertFalse(resumed.filterIsInstance<AgentMessageUi>().first().isStreaming)
        assertEquals(SystemNoticeCode.ModelRetry, resumed.filterIsInstance<SystemNoticeMessageUi>().single().code)
        assertTrue(projector.resetForReplay("retry-run", resumed).isEmpty())
    }

    @Test
    fun runCompletionFinalizesUnclosedBlocksAndUnknownToolWithoutChangingOtherRuns() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-final"
        val knownTools = listOf(
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-success",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                resultSummary = "完成",
            ),
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-failed",
                toolName = "run_command",
                status = ToolActivityStatusUi.Failed,
                argumentsSummary = "{}",
                resultSummary = "执行失败",
            ),
        )
        val otherRunMessages = listOf(
            AgentMessageUi(id = "assistant-run-other-1-0", content = "仍在输出", isStreaming = true),
            ThinkingMessageUi(id = "run-other-thinking-1-0", content = "仍在思考", isStreaming = true),
            ToolActivityMessageUi(
                id = "run-other-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Running,
                argumentsSummary = "{}",
            ),
        )
        val unfinishedTool = ToolActivityMessageUi(
            id = "$runId-tool-1-call-unknown",
            toolName = "run_command",
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "{}",
        )
        val messages = otherRunMessages + knownTools + listOf(
            AgentMessageUi(id = "assistant-$runId", content = "", isStreaming = true),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "回答\n\n", isStreaming = true),
            ThinkingMessageUi(id = "$runId-thinking-1-0", content = "思考", isStreaming = true),
            unfinishedTool,
        )

        val finalized = projector.finalizeRun(runId, messages)

        assertEquals(otherRunMessages + knownTools, finalized.take(otherRunMessages.size + knownTools.size))
        val assistantMessages = finalized.filterIsInstance<AgentMessageUi>()
            .filter { it.id.startsWith("assistant-$runId") }
        assertTrue(assistantMessages.all { !it.isStreaming && it.renderMarkdown })
        assertEquals("回答", assistantMessages.last().content)
        val thinking = finalized.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith(runId) }
        assertFalse(thinking.isStreaming)
        assertTrue(thinking.collapsed)
        assertEquals(unfinishedTool.copy(status = ToolActivityStatusUi.Unknown), finalized.last())
    }

    @Test
    fun replayResetRemovesOnlyRebuildableTraceAndExplicitlyReplayedSupplements() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-replay"
        val user = UserMessageUi(
            id = "user-$runId",
            content = "分析截图",
            images = listOf("image-preview"),
        )
        val legacySupplement = UserMessageUi(id = "user-$runId-supplement-1", content = "原始补充")
        val replayedSupplement = UserMessageUi(id = "user-$runId-supplement-2", content = "继续检查")
        val otherRunMessages = listOf(
            UserMessageUi(id = "user-other-run", content = "之前的问题"),
            AgentMessageUi(id = "assistant-other-run-1-0", content = "之前的回答"),
            ThinkingMessageUi(id = "other-run-thinking-1-0", content = "之前的思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "other-run-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            UserMessageUi(id = "user-other-run-supplement-2", content = "之前的补充"),
        )
        val messages = otherRunMessages + listOf(
            user,
            AgentMessageUi(id = "assistant-$runId", content = "", isStreaming = true),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "半截回答", isStreaming = true),
            ThinkingMessageUi(id = "$runId-thinking-1-fallback", content = "半截思考", isStreaming = true),
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Unknown,
                argumentsSummary = "{}",
            ),
            legacySupplement,
            replayedSupplement,
            SystemNoticeMessageUi(id = "assistant-$runId-2", code = SystemNoticeCode.RuntimeFailed),
            SystemNoticeMessageUi(id = "interrupted-$runId", code = SystemNoticeCode.Interrupted),
        )

        val reset = projector.resetForReplay(runId, messages, replaySupplementIndexes = setOf(2))

        assertEquals(otherRunMessages + listOf(user, legacySupplement), reset)
        assertEquals(reset, projector.resetForReplay(runId, reset, replaySupplementIndexes = setOf(2)))
    }

    @Test
    fun repeatedReplayRebuildsTextReasoningAndToolsWithoutAccumulatingContent() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-repeat"
        val user = UserMessageUi(id = "user-$runId", content = "看屏幕")
        val toolStart = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call-1",
            name = "observe_screen",
            argsPreview = "{}",
        )
        val toolEnd = AgentEvent.ToolFinished(
            round = 1,
            toolCallId = "call-1",
            name = "observe_screen",
            resultSummary = "完成",
            imageCount = 0,
            imageBytes = 0,
            success = true,
        )
        var messages: List<AgentChatMessageUi> = listOf(user)

        repeat(3) {
            messages = projector.resetForReplay(runId, messages)
            messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "先检查", messages)
            messages = projector.finalizeThinking(runId, messages)
            messages = projector.startTool(runId, toolStart, messages)
            messages = projector.finishTool(runId, toolEnd, messages)
            messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "检查", messages)
            messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "完成", messages)
            messages = projector.finalizeText(runId, messages)

            assertEquals(4, messages.size)
            assertEquals(user, messages.first())
            assertEquals("先检查", messages.filterIsInstance<ThinkingMessageUi>().single().content)
            assertEquals("检查完成", messages.filterIsInstance<AgentMessageUi>().single().content)
            assertEquals(ToolActivityStatusUi.Success, messages.filterIsInstance<ToolActivityMessageUi>().single().status)
        }
    }

    @Test
    fun replayResetClearsOnlyThatRunsThinkingClockAndKeepsUnreplayedUserInputs() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val supplement = UserMessageUi(id = "user-run-reset-supplement-1", content = "保留这条补充")
        var messages: List<AgentChatMessageUi> = listOf(supplement)
        messages = projector.appendReasoningDelta("run-reset", round = 1, index = 0, delta = "先前思考", messages)
        messages = projector.appendReasoningDelta("run-other", round = 1, index = 0, delta = "其他思考", messages)
        now = 9_000L

        messages = projector.resetForReplay("run-reset", messages)
        messages = projector.appendReasoningDelta("run-reset", round = 1, index = 0, delta = "恢复思考", messages)
        now = 11_000L
        messages = projector.finalizeThinking("run-reset", messages)
        messages = projector.finalizeThinking("run-other", messages)

        assertTrue(messages.contains(supplement))
        assertEquals(2, messages.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith("run-reset-") }.elapsedSeconds)
        assertEquals(10, messages.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith("run-other-") }.elapsedSeconds)
    }

    @Test
    fun projectsReasoningAndToolsByRoundAndToolCallId() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val runId = "run-1"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "看屏幕"))

        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "先观察", messages)
        now = 4_000L
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_observe_1",
                name = "observe_screen",
                argsPreview = "{}",
            ),
            projector.finalizeThinkingRound(runId, round = 1, messages)
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_observe_1",
                name = "observe_screen",
                resultSummary = "ok=true, chars=10",
                imageCount = 1,
                imageBytes = 200,
            ),
            messages
        )

        now = 5_000L
        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "再确认", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_observe_2",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "pm list packages | head",
            ),
            projector.finalizeThinkingRound(runId, round = 2, messages)
        )

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "$runId-tool-1-call_observe_1",
                "$runId-thinking-2-0",
                "$runId-tool-2-call_observe_2",
            ),
            messages.map { it.id }
        )

        val firstThinking = messages[1] as ThinkingMessageUi
        assertFalse(firstThinking.isStreaming)
        assertEquals(3, firstThinking.elapsedSeconds)

        val firstTool = messages[2] as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Success, firstTool.status)
        assertEquals(1, firstTool.imageCount)

        val secondTool = messages[4] as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Running, secondTool.status)
        assertEquals("执行命令 · Android · root", secondTool.argumentsSummary)
        assertEquals("pm list packages | head", secondTool.command)
    }

    @Test
    fun keepsAssistantTextSeparatedByRound() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-text"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "分析一下"),
            AgentMessageUi(id = "assistant-$runId-1", content = "第一轮", isStreaming = false),
        )

        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "继续推理", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_2",
                name = "observe_screen",
                argsPreview = "{}",
            ),
            projector.finalizeThinkingRound(runId, round = 2, messages)
        )
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "第二轮", messages)
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "回答", messages)

        assertEquals(
            listOf(
                "user-$runId",
                "assistant-$runId-1",
                "$runId-thinking-2-0",
                "$runId-tool-2-call_2",
                "assistant-$runId-2-1",
            ),
            messages.map { it.id }
        )
        val roundTwoAssistant = messages.last() as AgentMessageUi
        assertEquals("第二轮回答", roundTwoAssistant.content)
        assertTrue(roundTwoAssistant.isStreaming)
    }

    @Test
    fun keepsFallbackToolCallIdsDistinctAcrossRounds() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-fallback"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "操作手机"))

        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "tool_call_0",
                name = "search_apps",
                argsPreview = """{"query":"相机"}""",
            ),
            messages
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "tool_call_0",
                name = "search_apps",
                resultSummary = "ok=true",
                imageCount = 0,
                imageBytes = 0,
            ),
            messages
        )
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "tool_call_0",
                name = "observe_screen",
                argsPreview = """{"include_screenshot":true}""",
            ),
            messages
        )

        val tools = messages.filterIsInstance<ToolActivityMessageUi>()
        assertEquals(2, tools.size)
        assertEquals("search_apps", tools[0].toolName)
        assertEquals(ToolActivityStatusUi.Success, tools[0].status)
        assertEquals("observe_screen", tools[1].toolName)
        assertEquals(ToolActivityStatusUi.Running, tools[1].status)
    }

    @Test
    fun toolActivityFollowsAssistantTextStreamedInSameRound() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-order"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "搜一下")
        )

        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "先查找应用", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_1",
                name = "search_apps",
                argsPreview = "{}",
            ),
            messages
        )

        assertEquals(
            listOf(
                "user-$runId",
                "assistant-$runId-1-0",
                "$runId-tool-1-call_1",
            ),
            messages.map { it.id }
        )
    }

    @Test
    fun finalizingTextTrimsTrailingWhitespace() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-trim"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "你好")
        )

        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "回答。\n\n", messages)
        messages = projector.finalizeTextRound(runId, round = 1, messages)

        val assistant = messages.last() as AgentMessageUi
        assertEquals("回答。", assistant.content)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun interruptedToolBecomesUnknownInsteadOfFailed() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-interrupted"
        val running = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "执行命令",
            ),
            listOf(UserMessageUi(id = "user-$runId", content = "重启设备")),
        )

        val interrupted = projector.interruptRunningTools("任务中断", running)
        val tool = interrupted.filterIsInstance<ToolActivityMessageUi>().single()

        assertEquals(ToolActivityStatusUi.Unknown, tool.status)
        assertEquals("任务中断", tool.resultSummary)
    }

    @Test
    fun keepsInterleavedReasoningTextAndHostedToolInEventOrder() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-interleaved"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "搜索最新消息"),
        )

        messages = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 0,
            delta = "先判断需要搜索",
            messages,
        )
        messages = projector.appendTextDelta(
            runId,
            round = 1,
            index = 1,
            delta = "我先查一下。",
            messages,
        )
        messages = projector.startHostedTool(
            runId,
            AgentEvent.HostedToolStarted(round = 1, toolCallId = "ws_1", name = "网页搜索"),
            projector.finalizeTextRound(runId, round = 1, messages),
        )
        messages = projector.finishHostedTool(
            runId,
            AgentEvent.HostedToolFinished(
                round = 1,
                toolCallId = "ws_1",
                name = "网页搜索",
                success = true,
            ),
            messages,
        )
        messages = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 2,
            delta = "整理搜索结果",
            messages,
        )
        messages = projector.appendTextDelta(
            runId,
            round = 1,
            index = 3,
            delta = "这是最终答案。",
            messages,
        )

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "assistant-$runId-1-1",
                "$runId-tool-1-ws_1",
                "$runId-thinking-1-2",
                "assistant-$runId-1-3",
            ),
            messages.map { it.id },
        )
        assertFalse((messages[1] as ThinkingMessageUi).isStreaming)
        assertFalse((messages[2] as AgentMessageUi).isStreaming)
        assertEquals(ToolActivityStatusUi.Success, (messages[3] as ToolActivityMessageUi).status)
        assertFalse((messages[4] as ThinkingMessageUi).isStreaming)
        assertTrue((messages[5] as AgentMessageUi).isStreaming)
    }

    @Test
    fun compactionResultMergesIntoExistingMarkerInsteadOfAppending() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-compact"
        val started = SystemNoticeMessageUi(
            id = "assistant-$runId-compaction-op-1",
            code = SystemNoticeCode.ContextCompaction,
            detail = AgentEvent.ContextCompaction.RUNNING_DETAIL,
            running = true,
        )
        val completed = started.copy(
            detail = "上下文已压缩：约 45,312 → 25,768 tokens",
            contextTokens = 25_768,
            running = false,
        )
        val base = listOf(UserMessageUi(id = "user-$runId", content = "整理上下文"))

        // 成功的结果保留事件标记里的 token 信息，不再追加第二条结果通知。
        val success = AgentRunMessageProjector.mergeCompactionResultNotice(
            runId, base + completed, ok = true, detail = "上下文压缩完成",
        )
        assertEquals(base + completed, success)

        // 失败结果把同一条标记改写为失败原因。
        val failed = AgentRunMessageProjector.mergeCompactionResultNotice(
            runId, base + completed, ok = false, detail = "上下文压缩失败",
        )
        assertEquals("上下文压缩失败", failed.filterIsInstance<SystemNoticeMessageUi>().single().detail)

        // 事件标记缺失时（如断连恢复路径）才补一条结果标记。
        val appended = AgentRunMessageProjector.mergeCompactionResultNotice(
            runId, base, ok = true, detail = "上下文压缩完成",
        )
        assertEquals(
            "assistant-$runId-compaction-result",
            appended.filterIsInstance<SystemNoticeMessageUi>().single().id,
        )

        // 仍在进行中的标记被结果收尾，不停留在运行态。
        val finalized = AgentRunMessageProjector.mergeCompactionResultNotice(
            runId, base + started, ok = true, detail = "上下文压缩完成",
        )
        val finishedNotice = finalized.filterIsInstance<SystemNoticeMessageUi>().single()
        assertEquals("上下文压缩完成", finishedNotice.detail)
        assertFalse(finishedNotice.running)

        // finishContextCompaction 只收尾仍在运行的标记，已完成标记保持 token 信息。
        val interrupted = projector.finishContextCompaction(runId, base + started, "上下文压缩已中断")
        val interruptedNotice = interrupted.filterIsInstance<SystemNoticeMessageUi>().single()
        assertEquals("上下文压缩已中断", interruptedNotice.detail)
        assertFalse(interruptedNotice.running)
        assertEquals(base + completed, projector.finishContextCompaction(runId, base + completed, "上下文压缩已中断"))
    }

    /**
     * 运行计时起点必须写在消息里：切会话时消息会从库里读回来，只有字段跟着消息走，
     * 回来时才不会从 0 重新计时。计时本体走单调钟，墙钟只做落库与纪元判断。
     */
    @Test
    fun runningToolStartIsCarriedByTheMessageAndClearedOnFinish() {
        val wallClock = 1_700_000_000_000L
        val projector = AgentRunMessageProjector(
            nowWallClockMillis = { wallClock },
            nowElapsedRealtime = { 1_000L },
        )
        val runId = "run-stopwatch"
        val started = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_1",
                name = "terminal",
                argsPreview = "执行命令 · Android",
            ),
            emptyList(),
        )
        val running = started.filterIsInstance<ToolActivityMessageUi>().single()
        assertEquals(wallClock, running.startedAtMillis)
        assertEquals(wallClock, ToolRunStopwatch.wallSince(running.id))

        val finished = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_1",
                name = "terminal",
                resultSummary = "ok=true",
                imageCount = 0,
                imageBytes = 0,
            ),
            started,
        )
        val done = finished.filterIsInstance<ToolActivityMessageUi>().single()
        assertNull(done.startedAtMillis)
        assertNull(ToolRunStopwatch.wallSince(done.id))
    }

    /**
     * 进程内兜底：本进程写入的起点被沿用且不回退；上个进程留下的旧起点不采信
     * （允许从 0 重新开始，而不是算出一个越变越大的假耗时）。
     *
     * 两个时钟都显式注入：单测跑在纯 JVM 上，`SystemClock` 未实现，不能靠默认参数取时间。
     */
    @Test
    fun stopwatchKeepsFirstStartAndRejectsStalePersistedValue() {
        val id = "run-stopwatch-keep"
        val staleId = "run-stopwatch-stale"
        val convertedId = "run-stopwatch-converted"
        ToolRunStopwatch.clear(id)
        ToolRunStopwatch.clear(staleId)
        ToolRunStopwatch.clear(convertedId)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = 5_000L
        try {
            // 落库起点等于现在 → 单调起点就是现在，已用 0。
            assertEquals(nowElapsed, ToolRunStopwatch.resolveElapsedStart(id, nowWall, nowWall, nowElapsed))
            // 之后无论传什么、问几次，起点都不再变化（putIfAbsent 幂等）。
            assertEquals(nowElapsed, ToolRunStopwatch.resolveElapsedStart(id, nowWall + 120_000L, nowWall, nowElapsed))
            assertEquals(nowElapsed, ToolRunStopwatch.resolveElapsedStart(id, null, nowWall, nowElapsed))
            assertEquals(nowWall, ToolRunStopwatch.wallSince(id))

            // 已用时间取单调差：与墙钟怎么跳无关，时钟回拨也只归零不出负数。
            assertEquals(0L, ToolRunStopwatch.elapsedSince(id, nowElapsed))
            assertEquals(3_000L, ToolRunStopwatch.elapsedSince(id, nowElapsed + 3_000L))
            assertEquals(0L, ToolRunStopwatch.elapsedSince(id, nowElapsed - 3_000L))

            // 本进程内落库的墙钟起点按「已经过去多久」换算成单调起点，不把计时清零：
            // 起点写在纪元后 10 秒，现在（墙钟）是 40 秒后，于是单调起点比现在早 30 秒。
            val persistedInProcess = ToolRunStopwatch.processStartedAtMillis + 10_000L
            val converted = ToolRunStopwatch.resolveElapsedStart(
                convertedId,
                persistedInProcess,
                persistedInProcess + 30_000L,
                60_000L,
            )
            assertEquals(60_000L - 30_000L, converted)
            assertEquals(30_000L, ToolRunStopwatch.elapsedSince(convertedId, 60_000L))

            // 上个进程留下的起点（早于进程纪元）不采信，从 0 重新开始。
            val stale = ToolRunStopwatch.resolveElapsedStart(staleId, 1L, nowWall, nowElapsed)
            assertEquals(nowElapsed, stale)
            assertTrue(ToolRunStopwatch.wallSince(staleId)!! >= nowWall - 5_000L)
        } finally {
            ToolRunStopwatch.clear(id)
            ToolRunStopwatch.clear(staleId)
            ToolRunStopwatch.clear(convertedId)
        }
    }
}
