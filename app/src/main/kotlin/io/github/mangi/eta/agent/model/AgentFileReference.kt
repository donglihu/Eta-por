package io.github.mangi.eta.agent.model

enum class AgentFileReferenceKind {
    File,
    Directory,
}

data class AgentFileReference(
    val displayName: String,
    val absolutePath: String,
    val kind: AgentFileReferenceKind,
)

internal data class AgentFileReferencePrompt(
    val request: String,
    val references: List<AgentFileReference>,
)

internal object AgentFileReferencePolicy {
    fun canSend(
        references: List<AgentFileReference>,
        terminalToolsEnabled: Boolean,
    ): Boolean = references.isEmpty() || terminalToolsEnabled

    fun titleSource(
        request: String,
        references: List<AgentFileReference>,
    ): String = request.ifBlank { references.firstOrNull()?.displayName.orEmpty() }
}

/** 生成并解析 Eta 自己写入用户消息的本地路径上下文。 */
internal object AgentFileReferencePromptCodec {
    private const val FILES_HEADER = "# Files mentioned by the user:"
    private const val REQUEST_HEADER = "## My request:"
    private const val ENTRY_PREFIX = "## "

    fun format(
        request: String,
        references: List<AgentFileReference>,
    ): String {
        val uniqueReferences = references.distinctBy { it.absolutePath }
        if (uniqueReferences.isEmpty()) return request

        return buildString {
            appendLine(FILES_HEADER)
            appendLine()
            uniqueReferences.forEachIndexed { index, reference ->
                if (index > 0) appendLine()
                append(ENTRY_PREFIX)
                append(reference.displayLabel)
                append(": ")
                appendLine(reference.absolutePath)
            }
            appendLine()
            append(REQUEST_HEADER)
            if (request.isNotEmpty()) {
                appendLine()
                append(request)
            }
        }
    }

    /**
     * 文件引用包装的切片：`prefix` 是消息开头到 `## My request:` 那一行的原样字节，`request` 是其后正文。
     *
     * 保留 `prefix` 原样，是为了「解包装后还能原样拼回去」——历史压缩、剥离技能块只改正文段，
     * 头部一个字节都不动，不会把用户文件名里的空格、冒号、Unicode 重写坏。
     */
    internal data class Split(
        val prefix: String,
        val request: String,
        val references: List<AgentFileReference>,
    )

    /** 拆出文件引用头部与正文；不是合法文件引用包装时返回 null（普通消息按原样处理）。 */
    fun splitRequest(content: String): Split? {
        val prefix = "$FILES_HEADER\n\n"
        if (!content.startsWith(prefix)) return null
        val requestDelimiter = "\n\n$REQUEST_HEADER"
        val requestHeaderIndex = content.indexOf(requestDelimiter, startIndex = prefix.length)
        if (requestHeaderIndex < 0) return null

        val entries = content.substring(prefix.length, requestHeaderIndex).split("\n\n")
        val references = entries.mapNotNull(::parseReference)
        if (references.isEmpty() || references.size != entries.size) return null

        val requestStart = requestHeaderIndex + requestDelimiter.length
        val bodyStart = when {
            requestStart == content.length -> requestStart
            content.getOrNull(requestStart) == '\n' -> requestStart + 1
            else -> return null
        }
        return Split(
            prefix = content.substring(0, bodyStart),
            request = content.substring(bodyStart),
            references = references.distinctBy { it.absolutePath },
        )
    }

    /**
     * 解析 Eta 自己写进用户消息的结构化块：先拆文件引用，再剥掉斜杠命令注入的技能块。
     *
     * 展示、搜索、导出都走这里，所以技能块只需要在这一处剥，气泡里就不会出现整段 SKILL.md。
     */
    fun parse(content: String): AgentFileReferencePrompt {
        val split = splitRequest(content)
        return AgentFileReferencePrompt(
            request = AgentSkillCommandPromptCodec.strip(split?.request ?: content),
            references = split?.references ?: emptyList(),
        )
    }

    /**
     * 整条消息里的技能 / MCP 引用块。
     *
     * 文件引用按设计顶在消息最前面（`# Files mentioned by the user:` → `## My request:`），
     * 技能块因此被挤到 `## My request:` 之后，而 [AgentSkillCommandPromptCodec.parseReferenceBlocks]
     * 只认「消息开头的连续块」。直接拿整条消息去问只会得到空列表，实测表现就是
     * 文件 + 技能同发时胶囊不显示、气泡里整段 SKILL.md 裸露、编辑后重发把技能悄悄丢掉。
     */
    fun referenceBlocks(content: String): List<AgentSkillCommandPromptCodec.ReferenceBlock> =
        AgentSkillCommandPromptCodec.parseReferenceBlocks(splitRequest(content)?.request ?: content)

    /**
     * 历史压缩：文件引用头部原样保留，只压 `## My request:` 段里的技能正文。
     *
     * 直接对整条消息调用 [AgentSkillCommandPromptCodec.compactForHistory] 会因块不在开头而原样返回，
     * 于是之后每一轮都把几 k 字符的 SKILL.md 正文重新塞进历史。
     */
    fun compactForHistory(content: String): String {
        val split = splitRequest(content) ?: return AgentSkillCommandPromptCodec.compactForHistory(content)
        return split.prefix + AgentSkillCommandPromptCodec.compactForHistory(split.request)
    }

    /** 剥掉技能块但保留文件引用头部：复制到剪贴板时不留 SKILL.md 正文，也不丢掉引用了哪些文件。 */
    fun stripSkillBlocks(content: String): String {
        val split = splitRequest(content) ?: return AgentSkillCommandPromptCodec.strip(content)
        return split.prefix + AgentSkillCommandPromptCodec.strip(split.request)
    }

    private fun parseReference(line: String): AgentFileReference? {
        if (!line.startsWith(ENTRY_PREFIX) || line.contains('\n')) return null
        val body = line.removePrefix(ENTRY_PREFIX)
        val delimiterIndex = body.lastIndexOf(": /")
        if (delimiterIndex <= 0) return null
        val rawLabel = body.substring(0, delimiterIndex)
        val absolutePath = body.substring(delimiterIndex + 2)
        val isDirectory = rawLabel.endsWith('/')
        val displayName = rawLabel.removeSuffix("/")
        if (
            displayName.isBlank() ||
            displayName.hasUnsupportedControlCharacter() ||
            !absolutePath.startsWith('/') ||
            absolutePath.hasUnsupportedControlCharacter()
        ) {
            return null
        }
        return AgentFileReference(
            displayName = displayName,
            absolutePath = absolutePath,
            kind = if (isDirectory) AgentFileReferenceKind.Directory else AgentFileReferenceKind.File,
        )
    }

    private val AgentFileReference.displayLabel: String
        get() = displayName + if (kind == AgentFileReferenceKind.Directory) "/" else ""
}

internal fun String.hasUnsupportedControlCharacter(): Boolean =
    any { it == '\u0000' || it == '\r' || it == '\n' || it.isISOControl() }
