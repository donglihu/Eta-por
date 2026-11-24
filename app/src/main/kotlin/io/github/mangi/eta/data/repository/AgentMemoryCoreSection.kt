package io.github.mangi.eta.data.repository

/**
 * 核心记忆段落的统一切分口径。
 *
 * 注入侧（AgentMemoryContextBuilder）与写入侧（AgentMemoryStore）必须用同一把尺子，
 * 否则会出现「按 8,000 字符截断注入，但文件里还能无限追加」的错位——
 * 2026-11-19 反馈的「预算设 8,000 还是照写」就是这条错位。
 */
internal object AgentMemoryCoreSection {
    /** 核心段起始标题；旧文件没有它时按文件开头兜底。 */
    const val HEADING = "# 核心记忆"

    /** 没有核心标题时取文件前多少行作为核心段，与注入侧保持一致。 */
    private const val FALLBACK_LINES = 260

    /**
     * 取出核心记忆正文：从「# 核心记忆」到下一个一级标题（不含）。
     *
     * 标题用前缀匹配而不是全等：实际文件头是 `# 核心记忆 P0`，
     * 早先全等比较（旧 `trim() == "# 核心记忆"`）永远匹配不上，
     * 于是每次量核心段都掉进「取前 260 行」的兜底分支——预算数字看着正常，量到的却不是同一段。
     */
    fun content(markdown: String): String {
        if (markdown.isEmpty()) return ""
        val lines = markdown.split('\n')
        val start = lines.indexOfFirst { it.trim().startsWith(HEADING) }
        if (start < 0) return lines.take(FALLBACK_LINES).joinToString("\n")
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    /** 核心段字符数，与注入预算（core_budget_chars）同单位。 */
    fun chars(markdown: String): Int = content(markdown).length

    /** 钉住章节的标题特征：⛔ / 硬约束 / 最高优先级，与注入侧同一个正则。 */
    private val PIN_HEADING = Regex("^#{1,3}\\s*(⛔|硬约束|最高优先级)")

    /**
     * 真正进注入的核心正文＝钉住章节 + 核心段，与注入侧同源。
     *
     * 写入闸门必须量这一段：钉住章节可以写在文件任意位置，只量 `# 核心记忆` 段会漏掉
     * 「往 ⛔ 段里追加，把注入撑爆」这条路径——预算拦不住，等于没预算。
     */
    fun injected(markdown: String): String {
        val pinned = pinnedSections(markdown)
        val core = content(markdown)
        if (pinned.isBlank()) return core
        // 钉住段本来就落在核心段里时不重复计数
        if (core.contains(pinned)) return core
        return listOf(pinned, core).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** 注入口径字符数：写入闸门与注入截断共用。 */
    fun injectedChars(markdown: String): Int = injected(markdown).length

    /** 按标题抽取钉住章节；行首 `#` 之间即一段。 */
    private fun pinnedSections(markdown: String): String {
        if (markdown.isEmpty()) return ""
        val lines = markdown.split('\n')
        val out = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            if (PIN_HEADING.containsMatchIn(lines[index])) {
                val start = index
                var end = index + 1
                while (end < lines.size && !lines[end].startsWith("#")) end++
                if (out.isNotEmpty()) out += ""
                out += lines.subList(start, end)
                index = end
            } else {
                index++
            }
        }
        return out.joinToString("\n").trim()
    }
}
