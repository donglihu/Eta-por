package io.github.mangi.eta.data.repository

/**
 * 「这段内容该进 MEMORY.md 还是 NOTES.md」的确定性判定。
 *
 * 为什么要有它：提示词只能劝，劝不住模型就是没拦住——2026-11-21 反馈的
 * 「非核心内容也会无脑写到 memory」，靠 AgentPromptBuilder 里那行说明不解决问题。
 * 所以在工具层做一次分流：缺省 scope 的追加内容一旦带进展／日志特征，直接改投 NOTES.md，
 * 内容一个字不丢，但它不占核心注入预算、也不会被注入到系统提示。
 *
 * 判据刻意保守：只认确定的日志式特征（进展动词、日志词、日期+列表条目），
 * 拿不准一律当核心放行，避免把真正的长期约束挡在门外。
 */
internal object AgentMemoryCorePolicy {

    /** 日期字面量，形如 2026-11-21。 */
    private val DATE = Regex("""(?:^|[^0-9])20\d{2}-\d{2}-\d{2}(?:[^0-9]|$)""")

    /** 行首列表条目，日志式记录的典型形状。 */
    private val BULLET = Regex("""(?m)^\s*[-*]\s+""")

    /** 进展／日志标记词：出现即按非核心处理。 */
    private val PROGRESS_MARKERS = listOf(
        "已完成",
        "已修复",
        "已通过",
        "已同步",
        "已验证",
        "已落地",
        "待办",
        "下一步",
        "本轮",
        "本次",
        "日志",
        "退出码",
        "BUILD SUCCESSFUL",
        "sha256",
        "TODO",
        "FIXME",
    )

    /**
     * 判定为非核心的原因；返回 null 表示可以进核心记忆。
     *
     * 只回答「是不是日志式内容」，不判断长短、不判断重要性——
     * 长短由核心注入预算管，重要性只能由写入方显式声明（scope=core）。
     */
    fun nonCoreReason(content: String): String? {
        val text = content.trim()
        if (text.isEmpty()) return null
        PROGRESS_MARKERS.firstOrNull(text::contains)?.let { marker ->
            return "含进展／日志标记「$marker」"
        }
        if (DATE.containsMatchIn(text) && BULLET.containsMatchIn(text)) {
            return "日期 + 列表条目，属日志式记录"
        }
        return null
    }
}
