package io.github.mangi.eta.agent.model

/**
 * 斜杠命令调用技能时写进用户消息的技能上下文块。
 *
 * 设计取舍：
 * - 正文直接内联，不依赖 `skills_read` —— 省 token 档位没有技能工具，靠工具读正文会拿不到内容；
 * - 块放在消息最前面，模型先看到技能步骤，再看到本次任务；
 * - 展示层用 [strip] 把块剥掉，气泡里只留用户自己写的那句话（与文件引用块同一套处理）。
 */
internal object AgentSkillCommandPromptCodec {
    private const val OPEN_TAG = "<skill_command"
    private const val CLOSE_TAG = "</skill_command>"

    /** MCP 服务器引用块：与技能块共用同一套「开头连续块」解析/剥离逻辑。 */
    private const val MCP_OPEN_TAG = "<mcp_server"
    private const val MCP_CLOSE_TAG = "</mcp_server>"

    /** 所有会被注入到用户消息开头的引用块标签。 */
    private val OPEN_TAGS = listOf(OPEN_TAG, MCP_OPEN_TAG)

    /** [compactForHistory] 写进历史的那行说明；展示层要把它一起剥掉。 */
    private const val HISTORY_NOTE = "（本轮已通过斜杠命令注入该技能正文"

    /** 单次注入的 SKILL.md 正文上限；超出部分只留路径，让模型按需读文件。 */
    const val BodyLimit = 6_000

    fun format(
        skillId: String,
        skillName: String,
        skillPath: String,
        body: String,
        truncated: Boolean,
        request: String,
    ): String = buildString {
        append(OPEN_TAG)
        append(" id=\"").append(skillId.escapeAttribute()).append('"')
        append(" name=\"").append(skillName.escapeAttribute()).append('"')
        append(" path=\"").append(skillPath.escapeAttribute()).append('"')
        appendLine(">")
        appendLine("用户用斜杠命令显式调用了这个技能：先按其契约进入该技能规定的工作模式与输出形态，再完成本次任务。")
        appendLine("不要复述技能正文，但不得省略它声明的强制条款（激活条件、首轮输出形态、验收口径、退出条件）；宿主强制流程（如先提交计划清单）与这些条款要在同一条回复内同时交付。")
        appendLine("需要技能附带的 references/scripts 时按 path 所在目录读取，或调用 skills_read_resource。")
        appendLine()
        appendLine(body.trim())
        if (truncated) {
            appendLine()
            appendLine("（SKILL.md 正文已截断，需要更多细节时按 path 读取完整文件）")
        }
        appendLine(CLOSE_TAG)
        val trimmedRequest = request.trim()
        if (trimmedRequest.isNotEmpty()) {
            appendLine()
            append(trimmedRequest)
        }
    }.trimEnd()

    /** 用户消息里那次技能调用的抬头，供气泡区分普通消息。 */
    internal data class SkillCommandHeader(
        val skillId: String,
        val skillName: String,
    )

    /** 引用块种类：技能块或 MCP 服务器块。 */
    internal enum class ReferenceBlockKind { Skill, McpServer }

    /** 一段块：技能或 MCP 服务器。 */
    internal data class ReferenceBlock(
        val id: String,
        val name: String,
        val kind: ReferenceBlockKind,
        val raw: String,
    )

    fun parseHeader(content: String): SkillCommandHeader? = parseHeaders(content).firstOrNull()

    /**
     * 解析消息开头**连续**的多个技能块。
     *
     * 长按技能栏可以连加多个技能，发送时会拼成多段 `<skill_command>`；只解析第一个会让
     * 后续块的 SKILL.md 正文泄漏成纯文本（实测踩到）。
     */
    fun parseHeaders(content: String): List<SkillCommandHeader> =
        parseReferenceBlocks(content).filter { it.kind == ReferenceBlockKind.Skill }
            .map { SkillCommandHeader(skillId = it.id, skillName = it.name) }

    /**
     * 切出消息开头连续的引用块（技能与 MCP 服务器按出现顺序混排）。
     *
     * 同时兼容两种形态：
     * - 当轮注入：`<skill_command …>正文</skill_command>`
     * - 历史压缩：[compactForHistory] 产出的自闭合 `<skill_command … />` + 一行说明
     */
    fun parseReferenceBlocks(content: String): List<ReferenceBlock> {
        val blocks = mutableListOf<ReferenceBlock>()
        var rest = content
        while (true) {
            val open = OPEN_TAGS.firstOrNull { rest.startsWith(it) } ?: break
            val isMcp = open == MCP_OPEN_TAG
            val closeTag = if (isMcp) MCP_CLOSE_TAG else CLOSE_TAG
            val closeAt = rest.indexOf(closeTag)
            if (closeAt >= 0) {
                val raw = rest.substring(0, closeAt + closeTag.length)
                val id = attributeOf(raw, "id")?.unescapeAttribute()?.takeIf { it.isNotBlank() } ?: break
                blocks += ReferenceBlock(
                    id = id,
                    name = attributeOf(raw, "name")?.unescapeAttribute().orEmpty(),
                    kind = if (isMcp) ReferenceBlockKind.McpServer else ReferenceBlockKind.Skill,
                    raw = raw,
                )
                rest = rest.substring(closeAt + closeTag.length).trimStart()
            } else {
                val selfCloseAt = rest.indexOf("/>")
                if (selfCloseAt < 0) break
                val raw = rest.substring(0, selfCloseAt + 2)
                val id = attributeOf(raw, "id")?.unescapeAttribute()?.takeIf { it.isNotBlank() } ?: break
                blocks += ReferenceBlock(
                    id = id,
                    name = attributeOf(raw, "name")?.unescapeAttribute().orEmpty(),
                    kind = if (isMcp) ReferenceBlockKind.McpServer else ReferenceBlockKind.Skill,
                    raw = raw,
                )
                rest = rest.substring(selfCloseAt + 2).trimStart()
                if (rest.startsWith(HISTORY_NOTE)) rest = rest.substringAfter('\n', "").trimStart()
            }
        }
        return blocks
    }

    /**
     * 剥掉开头全部引用块（正文与历史说明都不进气泡），返回用户真正输入的那句话。
     *
     * 不是引用块、或块不完整时原样返回，避免把用户自己写的 `<` 吃掉。
     */
    fun strip(content: String): String {
        if (OPEN_TAGS.none { content.startsWith(it) }) return content
        val blocks = parseReferenceBlocks(content)
        if (blocks.isEmpty()) return content
        var rest = content
        blocks.forEach { block ->
            val at = rest.indexOf(block.raw)
            if (at < 0) return@forEach
            rest = rest.substring(at + block.raw.length).trimStart()
            if (rest.startsWith(HISTORY_NOTE)) rest = rest.substringAfter('\n', "").trimStart()
        }
        return rest.trim()
    }

    /** MCP 服务器引用块：告诉模型这一轮优先用该服务器的工具。 */
    fun formatMcpServerReference(
        serverId: String,
        serverName: String,
        toolNames: List<String>,
        request: String,
    ): String = buildString {
        append(MCP_OPEN_TAG)
        append(" id=\"").append(serverId.escapeAttribute()).append('"')
        append(" name=\"").append(serverName.escapeAttribute()).append('"')
        appendLine(">")
        appendLine("用户显式引用了这个 MCP 服务器：如需外部能力优先使用它提供的工具，不要复述这段说明。")
        if (toolNames.isNotEmpty()) {
            appendLine("可用工具：" + toolNames.take(MAX_LISTED_TOOLS).joinToString("、"))
        }
        appendLine(MCP_CLOSE_TAG)
        val trimmed = request.trim()
        if (trimmed.isNotEmpty()) {
            appendLine()
            append(trimmed)
        }
    }.trimEnd()

    private const val MAX_LISTED_TOOLS = 20

    /**
     * 历史里只压缩技能正文，MCP 引用块原样保留。
     *
     * SKILL.md 正文只在当轮注入有意义；原样留在历史里会让之后每一轮都重复几 k 字符，所以压成自闭合标记。
     * 但 MCP 块只有服务器名与工具清单、体积极小，压成「已引用」标记会让后续轮次的模型
     * 根本不知道引的是哪台服务器，所以这里不压缩它。
     */
    fun compactForHistory(content: String): String {
        if (OPEN_TAGS.none { content.startsWith(it) }) return content
        val blocks = parseReferenceBlocks(content)
        if (blocks.isEmpty()) return content
        var rest = content
        blocks.forEach { block ->
            val at = rest.indexOf(block.raw)
            if (at >= 0) rest = rest.substring(at + block.raw.length).trimStart()
            if (rest.startsWith(HISTORY_NOTE)) rest = rest.substringAfter('\n', "").trimStart()
        }
        return buildString {
            blocks.forEachIndexed { index, block ->
                if (index > 0) appendLine()
                if (block.kind == ReferenceBlockKind.McpServer) {
                    // MCP：保留完整块（含可用工具），压缩会让后续轮次丢失引用目标。
                    append(block.raw)
                    return@forEachIndexed
                }
                append("<skill_command")
                listOf("id", "name", "path").forEach { key ->
                    attributeOf(block.raw, key)?.let { value ->
                        append(' ').append(key).append("=\"").append(value).append('"')
                    }
                }
                append(" />")
                appendLine()
                append("（本轮已通过斜杠命令注入该技能正文，此处不重复；需要细节时按 path 读取或调用 skills_read）")
            }
            if (rest.isNotEmpty()) {
                appendLine()
                append(rest)
            }
        }
    }

    private fun attributeOf(head: String, key: String): String? =
        Regex(" " + Regex.escape(key) + "=\"([^\"]*)\"").find(head)?.groupValues?.getOrNull(1)

    private fun String.unescapeAttribute(): String =
        replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun String.escapeAttribute(): String =
        replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('\n', ' ')
            .replace('\r', ' ')
}
