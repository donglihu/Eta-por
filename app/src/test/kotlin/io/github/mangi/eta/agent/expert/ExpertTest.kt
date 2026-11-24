package io.github.mangi.eta.agent.expert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertTest {

    @Test
    fun `category fromKey falls back to OTHER for unknown key`() {
        assertEquals(ExpertCategory.WRITING, ExpertCategory.fromKey("writing"))
        assertEquals(ExpertCategory.DEV, ExpertCategory.fromKey("dev"))
        assertEquals(ExpertCategory.OTHER, ExpertCategory.fromKey(null))
        assertEquals(ExpertCategory.OTHER, ExpertCategory.fromKey("nonsense"))
    }

    @Test
    fun `title prefers displayName then name`() {
        assertEquals("代码评审官", Expert(name = "reviewer", displayName = "代码评审官").title)
        assertEquals("reviewer", Expert(name = "reviewer").title)
    }

    @Test
    fun `json round trip preserves all fields`() {
        val expert = Expert(
            name = "reviewer",
            displayName = "代码评审官",
            description = "审查代码质量",
            category = ExpertCategory.DEV,
            skills = listOf("code-reviewer", "docs-generator"),
            prompt = "你是资深代码评审官，按规范审查。",
            enabled = true,
        )
        val restored = Expert.fromJson(expert.toJson())
        assertEquals(expert, restored)
    }

    @Test
    fun `json round trip handles empty skills and blank fields`() {
        val expert = Expert(name = "minimal", enabled = false)
        val restored = Expert.fromJson(expert.toJson())
        assertEquals(expert, restored)
        assertEquals(emptyList<String>(), restored.skills)
        assertFalse(restored.enabled)
    }

    @Test
    fun `selection active requires both enabled and expertName`() {
        assertFalse(ExpertSelection().active)
        assertFalse(ExpertSelection(expertName = "reviewer", enabled = false).active)
        assertFalse(ExpertSelection(expertName = null, enabled = true).active)
        assertTrue(ExpertSelection(expertName = "reviewer", enabled = true).active)
    }

    @Test
    fun `active expert is null when not summoned`() {
        assertNull(ExpertStore.cachedSelection().expertName)
    }

    @Test
    fun `json round trip preserves model tool and inject fields`() {
        val expert = Expert(
            name = "code-reviewer",
            displayName = "代码评审官",
            description = "只看 diff，先给结论再给证据",
            category = ExpertCategory.DEV,
            prompt = "你是代码评审官……",
            pinned = true,
            source = ExpertSource.BUILTIN,
            providerId = "p1",
            modelId = "m1",
            tools = listOf("read_file", "terminal"),
            disallowedTools = listOf("write_file"),
            inject = listOf("mainRules", "memory"),
        )
        assertEquals(expert, Expert.fromJson(expert.toJson()))
    }

    @Test
    fun `legacy json without new fields falls back to defaults`() {
        // 旧版本保存的专家只有这几个字段；升级后必须还能读出来。
        val legacy = org.json.JSONObject()
            .put("name", "old-expert")
            .put("displayName", "老专家")
            .put("category", "dev")
            .put("prompt", "老提示词")
        val restored = Expert.fromJson(legacy)
        assertEquals("old-expert", restored.name)
        assertEquals(false, restored.pinned)
        assertEquals(ExpertSource.USER, restored.source)
        assertEquals(emptyList<String>(), restored.tools)
        assertTrue(restored.inheritsModel)
        assertFalse(restored.restrictsTools)
    }

    @Test
    fun `tool summary distinguishes inherit from explicit allowlist`() {
        val inherit = Expert(name = "a")
        assertEquals("继承全量工具", inherit.toolSummary())
        assertEquals("继承全量工具（不含 write_file）", Expert(name = "b", disallowedTools = listOf("write_file")).toolSummary())
        assertEquals("read_file、terminal", Expert(name = "c", tools = listOf("read_file", "terminal")).toolSummary())
        // 只写 "*" 等价于继承，不能被当成「只允许一个叫 * 的工具」。
        assertFalse(Expert(name = "d", tools = listOf("*")).restrictsTools)
    }

    @Test
    fun `model summary falls back to parent session when unset`() {
        assertEquals("继承父会话", Expert(name = "a").modelSummary())
        assertEquals("p1 / m1", Expert(name = "a", providerId = "p1", modelId = "m1").modelSummary())
        // 只填一半也算没指定：provider 与 model 必须成对。
        assertEquals("继承父会话", Expert(name = "a", providerId = "p1").modelSummary())
    }

    @Test
    fun `category accepts chinese labels from tool arguments`() {
        assertEquals(ExpertCategory.DEV, ExpertCategory.fromLabel("开发"))
        assertEquals(ExpertCategory.DATA, ExpertCategory.fromLabel("数据与分析"))
        assertEquals(ExpertCategory.BUSINESS, ExpertCategory.fromLabel("商业"))
        assertEquals(ExpertCategory.WRITING, ExpertCategory.fromLabel("写作与内容"))
        assertEquals(ExpertCategory.DEV, ExpertCategory.fromLabel("dev"))
        assertEquals(ExpertCategory.OTHER, ExpertCategory.fromLabel("不存在"))
    }

    @Test
    fun `toolAllowed lets everything through when tools are inherited`() {
        val expert = Expert(name = "a")
        assertTrue(expert.toolAllowed("terminal"))
        assertTrue(expert.toolAllowed("tap"))
    }

    @Test
    fun `toolAllowed honors allowlist and blacklist`() {
        val allow = Expert(name = "a", tools = listOf("read_file", "terminal"))
        assertTrue(allow.toolAllowed("read_file"))
        assertFalse(allow.toolAllowed("tap"))

        val deny = Expert(name = "b", disallowedTools = listOf("write_file"))
        assertTrue(deny.toolAllowed("read_file"))
        assertFalse(deny.toolAllowed("write_file"))

        // 黑名单优先于白名单：即便在白名单里，被拉黑就不给用。
        val both = Expert(name = "c", tools = listOf("read_file", "write_file"), disallowedTools = listOf("write_file"))
        assertTrue(both.toolAllowed("read_file"))
        assertFalse(both.toolAllowed("write_file"))
    }

    @Test
    fun `toolAllowed treats star as inherit`() {
        val expert = Expert(name = "a", tools = listOf("*"))
        assertTrue(expert.toolAllowed("anything"))
    }
}
