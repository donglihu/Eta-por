package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileReferencePromptCodecTest {
    @Test
    fun formatAndParse_roundTripsFilesDirectoriesAndUnicode() {
        val references = listOf(
            AgentFileReference(
                displayName = "报告 终稿.txt",
                absolutePath = "/storage/emulated/0/Download/报告 终稿.txt",
                kind = AgentFileReferenceKind.File,
            ),
            AgentFileReference(
                displayName = "项目资料",
                absolutePath = "/data/local/tmp/项目资料",
                kind = AgentFileReferenceKind.Directory,
            ),
        )

        val formatted = AgentFileReferencePromptCodec.format("总结这些内容", references)

        assertEquals(
            """# Files mentioned by the user:

## 报告 终稿.txt: /storage/emulated/0/Download/报告 终稿.txt

## 项目资料/: /data/local/tmp/项目资料

## My request:
总结这些内容""",
            formatted,
        )
        assertEquals(
            AgentFileReferencePrompt(request = "总结这些内容", references = references),
            AgentFileReferencePromptCodec.parse(formatted),
        )
    }

    @Test
    fun format_supportsFileOnlyMessageAndDeduplicatesPaths() {
        val reference = AgentFileReference(
            displayName = "archive.zip",
            absolutePath = "/storage/emulated/0/Download/archive.zip",
            kind = AgentFileReferenceKind.File,
        )

        val parsed = AgentFileReferencePromptCodec.parse(
            AgentFileReferencePromptCodec.format("", listOf(reference, reference.copy(displayName = "重复")))
        )

        assertEquals("", parsed.request)
        assertEquals(listOf(reference), parsed.references)
    }

    @Test
    fun parse_preservesOrdinaryOrMalformedUserText() {
        val ordinary = "# Files mentioned by the user:\n\n这只是普通文本"

        assertEquals(
            AgentFileReferencePrompt(request = ordinary, references = emptyList()),
            AgentFileReferencePromptCodec.parse(ordinary),
        )
        assertEquals("原始请求", AgentFileReferencePromptCodec.format("原始请求", emptyList()))
    }

    @Test
    fun policy_requiresTerminalToolsAndUsesFirstFileForEmptyTitle() {
        val reference = AgentFileReference(
            displayName = "device.log",
            absolutePath = "/data/local/tmp/device.log",
            kind = AgentFileReferenceKind.File,
        )

        assertFalse(AgentFileReferencePolicy.canSend(listOf(reference), terminalToolsEnabled = false))
        assertTrue(AgentFileReferencePolicy.canSend(listOf(reference), terminalToolsEnabled = true))
        assertTrue(AgentFileReferencePolicy.canSend(emptyList(), terminalToolsEnabled = false))
        assertEquals("device.log", AgentFileReferencePolicy.titleSource("", listOf(reference)))
        assertEquals("分析日志", AgentFileReferencePolicy.titleSource("分析日志", listOf(reference)))
    }

    // ---- 文件引用 + 技能同发 ----
    // 文件引用头部（`# Files mentioned by the user:` → `## My request:`）把技能块顶到正文段，
    // 而引用块解析器只认「消息开头的连续块」。这条链路上曾经全线失效：胶囊不显示、
    // 气泡里整段 SKILL.md 裸露、编辑后重发把技能悄悄丢掉、历史压缩失效。

    private val reportReference = AgentFileReference(
        displayName = "报告 终稿.txt",
        absolutePath = "/storage/emulated/0/Download/报告 终稿.txt",
        kind = AgentFileReferenceKind.File,
    )

    private fun skillBlock(id: String, name: String, body: String, request: String = "") =
        AgentSkillCommandPromptCodec.format(
            skillId = id,
            skillName = name,
            skillPath = "/skills/$id/SKILL.md",
            body = body,
            truncated = false,
            request = request,
        )

    /** 与 `AgentAppState` 发送时同形：技能块在前、用户那句话在块尾，外面再包一层文件引用头部。 */
    private fun combinedMessage() = AgentFileReferencePromptCodec.format(
        request = skillBlock("pua", "PUA", "PUA 正文第一段", "按技能步骤整理这份报告"),
        references = listOf(reportReference),
    )

    @Test
    fun parse_dropsSkillBodyFromBubbleWhenFileReferencesComeFirst() {
        val parsed = AgentFileReferencePromptCodec.parse(combinedMessage())

        assertEquals(listOf(reportReference), parsed.references)
        assertEquals("按技能步骤整理这份报告", parsed.request)
    }

    @Test
    fun referenceBlocks_findsSkillBlockInsideFileReferenceRequest() {
        val blocks = AgentFileReferencePromptCodec.referenceBlocks(combinedMessage())

        assertEquals(listOf("pua"), blocks.map { it.id })
        assertEquals(AgentSkillCommandPromptCodec.ReferenceBlockKind.Skill, blocks.single().kind)
        // 没有文件引用的老形态仍然按整条消息解析。
        assertEquals(
            listOf("pua"),
            AgentFileReferencePromptCodec.referenceBlocks(skillBlock("pua", "PUA", "正文")).map { it.id },
        )
    }

    @Test
    fun compactForHistory_keepsFileHeaderAndShrinksSkillBody() {
        val compacted = AgentFileReferencePromptCodec.compactForHistory(combinedMessage())

        assertTrue(compacted.startsWith("# Files mentioned by the user:"))
        assertTrue(
            compacted.contains("<skill_command id=\"pua\" name=\"PUA\" path=\"/skills/pua/SKILL.md\" />"),
        )
        assertFalse(compacted.contains("PUA 正文第一段"))
        assertTrue(compacted.contains("按技能步骤整理这份报告"))

        // 压缩产物下一轮还要能解回来：气泡、编辑回填都依赖它。
        val reparsed = AgentFileReferencePromptCodec.parse(compacted)
        assertEquals(listOf(reportReference), reparsed.references)
        assertEquals("按技能步骤整理这份报告", reparsed.request)
        assertEquals(listOf("pua"), AgentFileReferencePromptCodec.referenceBlocks(compacted).map { it.id })
    }

    @Test
    fun stripSkillBlocks_keepsFileHeaderForClipboardButDropsSkillBody() {
        val copied = AgentFileReferencePromptCodec.stripSkillBlocks(combinedMessage())

        assertTrue(copied.startsWith("# Files mentioned by the user:"))
        assertTrue(copied.contains("/storage/emulated/0/Download/报告 终稿.txt"))
        assertFalse(copied.contains("PUA 正文第一段"))
        assertTrue(copied.contains("按技能步骤整理这份报告"))
    }

    @Test
    fun splitRequest_returnsNullForPlainAndMalformedMessages() {
        assertEquals(null, AgentFileReferencePromptCodec.splitRequest("普通消息"))
        assertEquals(
            null,
            AgentFileReferencePromptCodec.splitRequest("# Files mentioned by the user:\n\n只是文本"),
        )
    }

    /**
     * 锁住旧实现的失效形态：技能编解码器只认「消息开头的块」，整条带文件引用的消息喂进去是空转。
     *
     * 这条用例是修复的反证——任何调用点再直接拿整条消息去问 [AgentSkillCommandPromptCodec]，
     * 历史压缩、剥离、编辑回填就会重新静默失效。
     */
    @Test
    fun wrappedMessageIsUntouchedByRawSkillCodecSoCallersMustUnwrapFirst() {
        val content = combinedMessage()

        assertEquals(content, AgentSkillCommandPromptCodec.compactForHistory(content))
        assertEquals(content, AgentSkillCommandPromptCodec.strip(content))
        assertTrue(AgentSkillCommandPromptCodec.parseReferenceBlocks(content).isEmpty())
        assertEquals(emptyList<AgentSkillCommandPromptCodec.SkillCommandHeader>(), AgentSkillCommandPromptCodec.parseHeaders(content))
    }
}
