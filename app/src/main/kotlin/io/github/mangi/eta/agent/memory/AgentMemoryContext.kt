package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryCoreSection
import io.github.mangi.eta.data.repository.AgentMemorySnapshot

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

/**
 * 记忆注入预算：滑杆自定义值优先，0 表示跟随上下文窗口自动计算。
 * 值由 SettingsDataStore 读到设置后同步进来，运行期只读这份缓存，避免注入路径再走 IO。
 */
internal object MemoryBudgetPolicy {
    @Volatile
    var coreChars: Int = 0

    @Volatile
    var headingChars: Int = 0

    const val MIN_CORE = 4_000
    const val MAX_CORE = 16_000
    const val STEP_CORE = 1_000
    const val MIN_HEADING = 500
    const val MAX_HEADING = 4_000
    const val STEP_HEADING = 100

    fun normalizeCore(value: Int): Int = value.takeIf { it > 0 }?.coerceIn(MIN_CORE, MAX_CORE) ?: 0

    fun normalizeHeading(value: Int): Int =
        value.takeIf { it > 0 }?.coerceIn(MIN_HEADING, MAX_HEADING) ?: 0

    fun apply(core: Int, heading: Int) {
        coreChars = normalizeCore(core)
        headingChars = normalizeHeading(heading)
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
        coreBudgetOverride: Int = MemoryBudgetPolicy.coreChars,
        headingBudgetOverride: Int = MemoryBudgetPolicy.headingChars,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow, coreBudgetOverride)
        val headingBudget = headingBudgetChars(headingBudgetOverride)
        // 钉住章节：标题带 ⛔ / 「硬约束」的段落无论写在哪都必须进注入，
        // 否则文件一长就会滑出预算，规则等于没写（2026-10-26 踩过）。
        // 钉住章节 + 核心段：与写入闸门共用 AgentMemoryCoreSection.injected，一把尺子量到底。
        // 否则「文件一长规则滑出预算」和「往 ⛔ 段追加绕过预算」两种漏法都会复现。
        val core = AgentMemoryCoreSection.injected(snapshot.content)
        val lines = snapshot.content.lineSequence().filter { it.isNotBlank() }.toList()
        val pinnedHeadings = lines.filter { line -> PIN_HEADING.containsMatchIn(line) }
        val headings = (pinnedHeadings + lines.filter { line -> HEADING.matches(line.trimEnd()) })
            .distinct()
            .joinToString("\n")
            .take(headingBudget)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = core.take(coreBudget),
            coreTruncated = core.length > coreBudget,
            headingIndex = headings,
            coreBudgetChars = coreBudget,
        )
    }

    /** 滑杆值优先；0 表示按上下文窗口比例自动计算。 */
    fun coreBudgetChars(
        contextWindow: Int?,
        overrideChars: Int = MemoryBudgetPolicy.coreChars,
    ): Int {
        val custom = MemoryBudgetPolicy.normalizeCore(overrideChars)
        if (custom > 0) return custom
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    /** 标题索引预算：滑杆值优先，0 表示用默认上限。 */
    fun headingBudgetChars(overrideChars: Int = MemoryBudgetPolicy.headingChars): Int {
        val custom = MemoryBudgetPolicy.normalizeHeading(overrideChars)
        return if (custom > 0) custom else MAX_HEADING_INDEX_CHARS
    }

    // 钉住段与核心段的切分已收敛到 AgentMemoryCoreSection（injected / content）：
    // 注入侧与写入闸门共用同一实现，这里不再保留第二份拷贝。

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    /** 窗口未知时用于按比例分配记忆、世界书等注入预算的保守基准，不参与压缩判定。 */
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000

    /** 自动模式下的上限：超出部分交给 memory_get 按需读取，不再整段注入。 */
    private const val MAX_CORE_CHARS = 8_000
    private const val MAX_HEADING_INDEX_CHARS = 1_500
    private val PIN_HEADING = Regex("^#{1,3}\\s*(⛔|硬约束|最高优先级)")
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
