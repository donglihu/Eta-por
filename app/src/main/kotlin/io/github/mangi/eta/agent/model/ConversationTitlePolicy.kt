package io.github.mangi.eta.agent.model

/**
 * 会话标题派生：用户消息里可能内联了文件引用块与斜杠命令注入的技能块，
 * 标题只能取用户真正写的那句话。
 *
 * 真机踩到的问题：`/pua 帮我写周报` 发出的消息首行是 `<skill_command id="pua" …>`，
 * 旧实现直接对原始 prompt 取首行再 `take(24)`，列表里就显示成 `<skill_command id="pua"` 这种残片。
 *
 * 三条入口（发送即命名、回复落盘兜底重试、编辑首轮重算）都走这里，避免只修一处。
 */
internal object ConversationTitlePolicy {
    /** 与旧实现一致的标题长度上限。 */
    const val MAX_TITLE_CHARS = 24

    /** 残留的引用块标签；只匹配 Eta 自己写入的两种块，不动用户正文里的 `<`。 */
    private val INJECTED_TAG = Regex("<\\/?(?:skill_command|mcp_server)\\b[^>]*>?")

    /** 未闭合的标签残片：`<skill_command id="pua"` 这类截断产物。 */
    private val UNCLOSED_TAG = Regex("^<[A-Za-z_][A-Za-z0-9_-]*\\b")

    /**
     * 这个标题是不是「注入块残片」——即历史库里已经存脏的标题。
     *
     * 老版本直接对原始 prompt 取首行再 `take(24)`，落库的标题就长成
     * `<skill_command id="pua"` / `<mcp_server id="x"` 这类东西。启动加载时用它识别，
     * 命中就用首条用户消息重算并回写，不让脏标题一直留在列表里。
     */
    fun looksLikeInjectedTitle(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || !trimmed.contains('<')) return false
        if (INJECTED_TAG.containsMatchIn(trimmed)) return true
        return UNCLOSED_TAG.containsMatchIn(trimmed)
    }

    /** 剥掉正文里残留的引用块标签；块不完整时返回空串。供标题摘要等非标题场景复用。 */
    fun cleanInjectedTags(request: String): String = cleanRequest(request)

    /** 消息原文（可能含文件引用块与技能块）→ 标题。 */
    fun fromMessage(content: String): String {
        val parsed = AgentFileReferencePromptCodec.parse(content)
        return fromParsed(parsed.request, parsed.references)
    }

    /** 已拼好的运行时 prompt（可能含技能块）→ 标题；[references] 是调用方已知的文件引用。 */
    fun fromPrompt(
        prompt: String,
        references: List<AgentFileReference> = emptyList(),
    ): String {
        val parsed = AgentFileReferencePromptCodec.parse(prompt)
        return fromParsed(
            request = parsed.request,
            references = parsed.references.ifEmpty { references },
        )
    }

    /** 已经解析过的请求正文 → 标题。 */
    fun fromParsed(
        request: String,
        references: List<AgentFileReference> = emptyList(),
    ): String = limitTitle(
        AgentFileReferencePolicy.titleSource(cleanRequest(request), references),
    )

    /** 只做首行 + 长度收敛，用于已经在展示层剥过块的文本。 */
    fun limitTitle(text: String): String =
        text.lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS)

    /**
     * 剥掉正文里残留的引用块标签。
     *
     * 块不完整（例如历史里被截断、或用户手工粘进来的半截标签）时 [AgentSkillCommandPromptCodec.strip]
     * 会原样返回，这里补一刀；剥完仍然是个未闭合标签就当没有可用正文，让调用方回退到文件名。
     */
    private fun cleanRequest(request: String): String {
        val firstLine = request.lineSequence().firstOrNull().orEmpty()
        if (!firstLine.contains('<')) return request
        val withoutTags = INJECTED_TAG.replace(firstLine, "").trim()
        val looksLikeUnclosedTag = !withoutTags.contains('>') && UNCLOSED_TAG.containsMatchIn(withoutTags)
        if (looksLikeUnclosedTag) return ""
        if (withoutTags == firstLine.trim()) return request
        val rest = request.lineSequence().drop(1).joinToString("\n")
        return if (rest.isBlank()) withoutTags else (withoutTags + "\n" + rest).trim()
    }
}
