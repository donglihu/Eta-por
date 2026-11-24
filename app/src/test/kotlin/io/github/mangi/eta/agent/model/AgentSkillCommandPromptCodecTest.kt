package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能 / MCP 引用块的编解码回归。
 *
 * 这组用例锁的是三个真机踩到的问题：
 * 1. 历史压缩后是自闭合标签，原实现只认 `</skill_command>`，导致 SKILL.md 正文泄漏成纯文本；
 * 2. 长按可连加多个技能，原实现只剥第一块，后面的块原样显示；
 * 3. MCP 服务器引用块必须和技能块走同一套剥离，否则正文同样泄漏。
 */
class AgentSkillCommandPromptCodecTest {

    private fun skillBlock(id: String, name: String, body: String, request: String = "") =
        AgentSkillCommandPromptCodec.format(
            skillId = id,
            skillName = name,
            skillPath = "/skills/$id/SKILL.md",
            body = body,
            truncated = false,
            request = request,
        )

    @Test
    fun stripRemovesSingleInjectedBlock() {
        val content = skillBlock("demo", "Demo", "步骤一\n步骤二", "帮我做件事")
        assertEquals("", AgentSkillCommandPromptCodec.strip(content).replace("帮我做件事", "").trim())
        assertTrue(AgentSkillCommandPromptCodec.strip(content).contains("帮我做件事"))
        assertTrue(!AgentSkillCommandPromptCodec.strip(content).contains("步骤一"))
    }

    @Test
    fun stripRemovesMultipleBlocksSoSecondBodyNeverLeaks() {
        val content = skillBlock("a", "A", "A 的正文") + "\n\n" +
            skillBlock("b", "B", "B 的正文") + "\n\n用户真正说的话"
        val stripped = AgentSkillCommandPromptCodec.strip(content)
        assertEquals("用户真正说的话", stripped)
        assertTrue(!stripped.contains("A 的正文"))
        assertTrue(!stripped.contains("B 的正文"))
    }

    @Test
    fun parseHeadersReturnsEverySkillSoBubblesCanRenderAllChips() {
        val content = skillBlock("a", "A", "正文a") + "\n\n" + skillBlock("b", "B", "正文b")
        val headers = AgentSkillCommandPromptCodec.parseHeaders(content)
        assertEquals(listOf("a", "b"), headers.map { it.skillId })
        assertEquals(listOf("A", "B"), headers.map { it.skillName })
    }

    @Test
    fun stripHandlesSelfClosingHistoryFormWithoutLeakingNote() {
        // compactForHistory 的真实产物形态。
        val history = "<skill_command id=\"demo\" name=\"Demo\" path=\"/skills/demo/SKILL.md\" />\n" +
            "（本轮已通过斜杠命令注入该技能正文，此处不重复；需要细节时按 path 读取或调用 skills_read）\n" +
            "\n用户的问题"
        assertEquals("用户的问题", AgentSkillCommandPromptCodec.strip(history))
        assertEquals(listOf("demo"), AgentSkillCommandPromptCodec.parseHeaders(history).map { it.skillId })
    }

    @Test
    fun compactForHistoryCompactsEveryBlockNotJustTheFirst() {
        val content = skillBlock("a", "A", "A 正文") + "\n\n" + skillBlock("b", "B", "B 正文") + "\n\n问题"
        val compacted = AgentSkillCommandPromptCodec.compactForHistory(content)
        assertTrue("第一块没被压缩：$compacted", compacted.contains("id=\"a\""))
        assertTrue("第二块没被压缩：$compacted", compacted.contains("id=\"b\""))
        // 压缩后不能再留任何正文。
        assertTrue("正文泄漏：$compacted", !compacted.contains("A 正文"))
        assertTrue("正文泄漏：$compacted", !compacted.contains("B 正文"))
        // 压缩产物再解一遍仍然干净。
        assertEquals("问题", AgentSkillCommandPromptCodec.strip(compacted))
    }

    @Test
    fun mcpServerBlockIsStrippedAndParsedAlongsideSkills() {
        val mcp = AgentSkillCommandPromptCodec.formatMcpServerReference(
            serverId = "srv",
            serverName = "Srv",
            toolNames = listOf("search", "fetch"),
            request = "",
        )
        val content = skillBlock("skill", "Skill", "正文") + "\n\n" + mcp + "\n\n最终问题"
        val stripped = AgentSkillCommandPromptCodec.strip(content)
        assertEquals("最终问题", stripped)
        assertTrue(!stripped.contains("正文"))
        assertTrue(!stripped.contains("可用工具"))

        val blocks = AgentSkillCommandPromptCodec.parseReferenceBlocks(content)
        assertEquals(2, blocks.size)
        assertEquals(AgentSkillCommandPromptCodec.ReferenceBlockKind.Skill, blocks[0].kind)
        assertEquals(AgentSkillCommandPromptCodec.ReferenceBlockKind.McpServer, blocks[1].kind)
        assertEquals("srv", blocks[1].id)
        // MCP 块不能被当成技能胶囊。
        assertEquals(listOf("skill"), AgentSkillCommandPromptCodec.parseHeaders(content).map { it.skillId })
    }

    @Test
    fun compactForHistoryKeepsMcpBlockSoLaterTurnsKnowTheTarget() {
        val mcp = AgentSkillCommandPromptCodec.formatMcpServerReference(
            serverId = "srv",
            serverName = "Srv",
            toolNames = listOf("search", "fetch"),
            request = "",
        )
        val content = skillBlock("a", "A", "A 正文") + "\n\n" + mcp + "\n\n问题"
        val compacted = AgentSkillCommandPromptCodec.compactForHistory(content)
        // MCP 块必须原样保留（含工具清单），否则后续轮次模型不知道引的是哪台服务器。
        assertTrue("MCP 块被压缩了：$compacted", compacted.contains("可用工具：search、fetch"))
        // 技能正文仍要压掉。
        assertTrue("技能正文泄漏：$compacted", !compacted.contains("A 正文"))
        // 压缩结果再渲染一遍，引用块全部不出现在正文里。
        assertEquals("问题", AgentSkillCommandPromptCodec.strip(compacted))
    }

    @Test
    fun plainUserTextIsNeverMutated() {
        val plain = "普通一句话，含 < > 符号也不算块"
        assertEquals(plain, AgentSkillCommandPromptCodec.strip(plain))
        assertTrue(AgentSkillCommandPromptCodec.parseHeaders(plain).isEmpty())
    }

    @Test
    fun incompleteBlockFallsBackToOriginalText() {
        // 只有开标签没有闭标签：宁可原样显示，也不能把用户内容吃掉。
        val broken = "<skill_command id=\"x\" name=\"X\">没写完"
        assertEquals(broken, AgentSkillCommandPromptCodec.strip(broken))
    }
}
