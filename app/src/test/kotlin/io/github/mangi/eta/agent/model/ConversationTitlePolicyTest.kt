package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话标题派生回归。
 *
 * 真机踩到的问题：`/pua 帮我写周报` 发出去后，列表里标题变成 `<skill_command id="pua"`。
 * 根因是标题直接取「原始 prompt 的首行」再截 24 个字符，而原始 prompt 的第一行正是
 * 斜杠命令注入的技能块开标签。这组用例锁死三条入口（发送即命名、回复落盘兜底、编辑首轮重算）
 * 都不许再把注入块当成用户说的话。
 */
class ConversationTitlePolicyTest {

    private fun skillBlock(id: String, name: String, body: String, request: String = "") =
        AgentSkillCommandPromptCodec.format(
            skillId = id,
            skillName = name,
            skillPath = "/skills/$id/SKILL.md",
            body = body,
            truncated = false,
            request = request,
        )

    private fun mcpBlock(request: String = "") =
        AgentSkillCommandPromptCodec.formatMcpServerReference(
            serverId = "srv",
            serverName = "Srv",
            toolNames = listOf("search", "fetch"),
            request = request,
        )

    private fun fileBlock(request: String, references: List<AgentFileReference>) =
        AgentFileReferencePromptCodec.format(request, references)

    private fun ref(name: String) = AgentFileReference(
        displayName = name,
        absolutePath = "/sdcard/Download/$name",
        kind = AgentFileReferenceKind.File,
    )

    @Test
    fun skillBlockIsNotPartOfTitle() {
        val prompt = skillBlock("pua", "Pua", "步骤一\n步骤二", "帮我写周报")
        val title = ConversationTitlePolicy.fromPrompt(prompt)
        assertEquals("帮我写周报", title)
        assertFalse("标题里残留了注入块：$title", title.contains("<"))
        assertFalse("标题里残留了技能正文：$title", title.contains("步骤一"))
    }

    @Test
    fun truncatedOpenTagNeverBecomesTitle() {
        // 旧实现 take(24) 的真实产物：开标签被切成半截。
        val broken = "<skill_command id=\"pua\""
        val title = ConversationTitlePolicy.fromPrompt(broken)
        assertEquals("", title)
        assertFalse("标题里残留了标签残片：$title", title.contains("skill_command"))
    }

    @Test
    fun titleIsBoundedByMaxTitleChars() {
        val long = "一".repeat(ConversationTitlePolicy.MAX_TITLE_CHARS + 20)
        val title = ConversationTitlePolicy.fromPrompt(skillBlock("a", "A", "正文", long))
        assertEquals(ConversationTitlePolicy.MAX_TITLE_CHARS, title.length)
        assertTrue(long.startsWith(title))
    }

    @Test
    fun multipleSkillBlocksAllDisappear() {
        val prompt = skillBlock("a", "A", "A 正文") + "\n\n" +
            skillBlock("b", "B", "B 正文") + "\n\n真的任务描述"
        assertEquals("真的任务描述", ConversationTitlePolicy.fromPrompt(prompt))
    }

    @Test
    fun mcpBlockIsNotPartOfTitle() {
        val prompt = skillBlock("a", "A", "A 正文") + "\n\n" + mcpBlock() + "\n\n查一下天气"
        assertEquals("查一下天气", ConversationTitlePolicy.fromPrompt(prompt))
    }

    @Test
    fun selfClosingHistoryFormIsNotPartOfTitle() {
        val history = "<skill_command id=\"demo\" name=\"Demo\" path=\"/skills/demo/SKILL.md\" />\n" +
            "（本轮已通过斜杠命令注入该技能正文，此处不重复；需要细节时按 path 读取或调用 skills_read）\n" +
            "\n用户的问题"
        assertEquals("用户的问题", ConversationTitlePolicy.fromPrompt(history))
    }

    @Test
    fun emptyAfterStripFallsBackToReferenceName() {
        val prompt = skillBlock("a", "A", "A 正文")
        val title = ConversationTitlePolicy.fromPrompt(prompt, listOf(ref("device.log")))
        assertEquals("device.log", title)
    }

    @Test
    fun emptyAfterStripWithoutReferenceStaysEmpty() {
        assertEquals("", ConversationTitlePolicy.fromPrompt(skillBlock("a", "A", "A 正文")))
    }

    @Test
    fun fileReferenceBlockYieldsTheRequestAsTitle() {
        val references = listOf(ref("device.log"))
        val content = fileBlock(skillBlock("a", "A", "A 正文", "看看这个日志"), references)
        assertEquals("看看这个日志", ConversationTitlePolicy.fromPrompt(content))
    }

    @Test
    fun messageEntryPointMatchesPromptEntryPoint() {
        // 落盘兜底与编辑重算都走 fromMessage，必须和发送时算出的标题一致。
        val prompt = skillBlock("pua", "Pua", "正文", "帮我写周报")
        val stored = fileBlock(prompt, listOf(ref("device.log")))
        assertEquals(
            ConversationTitlePolicy.fromPrompt(prompt),
            ConversationTitlePolicy.fromMessage(stored),
        )
    }

    @Test
    fun plainUserTextIsNotMutated() {
        assertEquals("普通一句话", ConversationTitlePolicy.fromPrompt("普通一句话"))
        // 用户自己写的尖括号不能被当成注入块吃掉。
        assertEquals("用 <div> 包一层", ConversationTitlePolicy.fromPrompt("用 <div> 包一层"))
    }

    @Test
    fun multiLineTitleTakesFirstLineOnly() {
        assertEquals("第一行", ConversationTitlePolicy.fromPrompt("第一行\n第二行"))
    }

    @Test
    fun storedTagFragmentIsDetectedForSelfHealing() {
        // 旧库真机产物：take(24) 把开标签切成半截，已经写进 conversations.title。
        assertTrue(
            ConversationTitlePolicy.looksLikeInjectedTitle("<skill_command id=\"pua\""),
        )
        assertTrue(
            ConversationTitlePolicy.looksLikeInjectedTitle("<mcp_server id=\"srv\""),
        )
        // 完整块与闭合块也算脏标题。
        assertTrue(
            ConversationTitlePolicy.looksLikeInjectedTitle("<skill_command id=\"pua\" name=\"Pua\">"),
        )
        assertTrue(
            ConversationTitlePolicy.looksLikeInjectedTitle("</skill_command>"),
        )
    }

    @Test
    fun normalTitleIsNotTreatedAsTagFragment() {
        assertFalse(ConversationTitlePolicy.looksLikeInjectedTitle("帮我写周报"))
        assertFalse(ConversationTitlePolicy.looksLikeInjectedTitle(""))
        // 用户自己写进消息里的尖括号，不能进自愈名单。
        assertFalse(ConversationTitlePolicy.looksLikeInjectedTitle("用 <div> 包一层"))
        assertFalse(ConversationTitlePolicy.looksLikeInjectedTitle("<3 早安"))
    }

    @Test
    fun selfHealingRecomputesDirtyTitleFromFirstUserMessage() {
        val content = fileBlock(skillBlock("pua", "Pua", "正文", "帮我写周报"), listOf(ref("device.log")))
        val repaired = ConversationTitlePolicy.fromMessage(content)
        assertEquals("帮我写周报", repaired)
        assertFalse(ConversationTitlePolicy.looksLikeInjectedTitle(repaired))
    }
}
