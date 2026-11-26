package io.github.mangi.eta.hook.xiaoai

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference

internal object XiaoAiTakeoverPolicy {
    private const val AGENT_PREFIX = "/agent "
    private const val AGENT_ENCODED_PREFIX = "/agent%20"
    private const val IMAGE_ONLY_PLACEHOLDER = "blank"
    private const val IMAGE_ONLY_PROMPT = "请分析这张图片"

    /**
     * 已逐锚点核对过的小爱版本。新增版本前先按 XiaoAiHooks 里的类常量核一遍 APK，
     * 混淆锚点（y00.r0 / z10.a / k00.y0 / lh0.s0 / y00.c1）会随版本重排。
     */
    val ADAPTED_VERSION_CODES: Set<Long> = setOf(507013032L, 507013035L)

    /**
     * 跨版本稳定的锚点：都是保留语义的完整类名（小爱自有包名或 com.xiaomi.ai.api），
     * 不含 R8 分配的短名（如 widget.d / card.a / y00.r0），所以能用来判断结构是否同一套。
     */
    val STABLE_ANCHORS: List<String> = listOf(
        "com.xiaomi.voiceassistant.VAApplication",
        "com.xiaomi.voiceassistant.instruction.base.OperationManager",
        "com.xiaomi.voiceassistant.instruction.card.stream.FlowTemplateToastCard",
        "com.xiaomi.voiceassistant.UiManager",
        "com.xiaomi.ai.api.common.Event",
        "com.xiaomi.ai.api.common.Instruction",
    )

    data class Decision(
        val prompt: String,
    )

    fun isSupportedVersion(versionCode: Long): Boolean =
        ADAPTED_VERSION_CODES.contains(versionCode)

    /**
     * 未登记的版本不直接放弃：只要小爱侧仍保留这批稳定锚点（都在 com.xiaomi 命名空间、
     * 不受 R8 改名影响），就允许安装钩子；单个混淆锚点落空由 HookRegistrar.missing 降级，
     * 不会拖垮整条链路。
     */
    fun shouldInstallOnUnknownVersion(anchorPresent: (String) -> Boolean): Boolean =
        STABLE_ANCHORS.all(anchorPresent)

    /**
     * 可接管的出站事件名。
     *
     * 7.13.35 的 dex 里实测存在四个 Nlp 出站变体（Nlp.Request /
     * Nlp.RequestLargeLanguageModelContent / Nlp.RequestSummaryText / Nlp.RequestWeather），
     * 只认 "Nlp.Request" 会在新版走 LLM 直连分支时静默漏接。这里按前缀匹配 Nlp.Request，
     * 排除纯识别事件（SpeechRecognizer.*），保持「文本问答才接管」的语义。
     */
    private const val OUTBOUND_EVENT_PREFIX = "Nlp.Request"

    fun matchesOutboundEvent(fullName: String): Boolean =
        fullName.startsWith(OUTBOUND_EVENT_PREFIX)

    fun decide(
        query: String,
        hasImage: Boolean,
        customModelEnabled: Boolean,
        requirePrefix: Boolean,
    ): Decision? {
        if (!customModelEnabled) return null
        val trimmed = query.trim()
        val prefixed = when {
            trimmed.startsWith(AGENT_PREFIX) ->
                trimmed.removePrefix(AGENT_PREFIX).trim()

            trimmed.startsWith(AGENT_ENCODED_PREFIX) ->
                trimmed.removePrefix(AGENT_ENCODED_PREFIX).trim()

            else -> null
        }
        if (requirePrefix && prefixed == null) return null

        val prompt = (prefixed ?: trimmed).let { candidate ->
            if (
                hasImage &&
                (candidate.isBlank() || candidate.equals(IMAGE_ONLY_PLACEHOLDER, ignoreCase = true))
            ) {
                IMAGE_ONLY_PROMPT
            } else {
                candidate
            }
        }
        return prompt.takeIf(String::isNotBlank)?.let(::Decision)
    }
}

internal class XiaoAiRunSlot<T : Any> {
    private val current = AtomicReference<T?>()

    fun get(): T? = current.get()

    fun replace(value: T): T? = current.getAndSet(value)

    fun clear(value: T): Boolean = current.compareAndSet(value, null)
}

internal class XiaoAiRendererSlot<T : Any> {
    private val attached = AtomicReference<T?>()

    fun attach(value: T): T? = attached.getAndSet(value)

    fun detach(): T? = attached.getAndSet(null)
}

internal class XiaoAiQueryCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class QueryInfo(
        val dialogId: String,
        val query: String,
        val imageFileId: String?,
        val documentInput: Boolean,
        val capturedAt: Long,
    )

    private val entries = LinkedHashMap<String, QueryInfo>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun put(
        dialogId: String,
        query: String,
        imageFileId: String?,
        documentInput: Boolean = false,
    ): Boolean {
        val normalizedId = dialogId.trim()
        if (normalizedId.isBlank()) return false
        val now = clock()
        pruneExpired(now)
        entries.remove(normalizedId)
        entries[normalizedId] = QueryInfo(
            dialogId = normalizedId,
            query = query,
            imageFileId = imageFileId?.trim()?.takeIf(String::isNotBlank),
            documentInput = documentInput,
            capturedAt = now,
        )
        trimToCapacity()
        return true
    }

    @Synchronized
    fun take(dialogId: String): QueryInfo? {
        val now = clock()
        pruneExpired(now)
        return entries.remove(dialogId.trim())
    }

    /**
     * Nlp.Request 的 Event ID 由小爱在出站时重新生成，不能假设它等于 setQueryInfo 的
     * dialogId。优先按 ID 取值，再按同一轮查询文本关联；关联成功后同样消费一次。
     */
    @Synchronized
    fun takeMatching(
        eventId: String,
        eventQuery: String,
    ): QueryInfo? {
        val now = clock()
        pruneExpired(now)
        entries.remove(eventId.trim())?.let { return it }

        val normalizedQuery = eventQuery.trim()
        if (normalizedQuery.isBlank()) return null
        val matchingKey = entries.entries
            .lastOrNull { (_, value) -> value.query.trim() == normalizedQuery }
            ?.key
            ?: return null
        return entries.remove(matchingKey)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int {
        pruneExpired(clock())
        return entries.size
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { (_, value) -> now - value.capturedAt > ttlMillis }
    }

    private fun trimToCapacity() {
        while (entries.size > capacity) {
            val oldest = entries.entries.firstOrNull()?.key ?: return
            entries.remove(oldest)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}

internal class XiaoAiTurnTracker(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Turn(
        val dialogId: String,
        val query: String,
        val hasImage: Boolean,
        val documentInput: Boolean,
        val capturedAt: Long,
    )

    private var current: Turn? = null

    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun capture(
        dialogId: String,
        query: String,
        hasImage: Boolean = false,
        documentInput: Boolean = false,
    ) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        current = Turn(
            dialogId = dialogId.trim(),
            query = normalizedQuery,
            hasImage = hasImage,
            documentInput = documentInput,
            capturedAt = clock(),
        )
    }

    @Synchronized
    fun latest(): Turn? {
        val turn = current ?: return null
        if (clock() - turn.capturedAt > ttlMillis) {
            current = null
            return null
        }
        return turn
    }

    @Synchronized
    fun clear() {
        current = null
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 15_000L
    }
}

internal class XiaoAiRecentIds(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, Long>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun add(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        val now = clock()
        pruneExpired(now)
        entries.remove(normalized)
        entries[normalized] = now
        while (entries.size > capacity) {
            entries.remove(entries.entries.firstOrNull()?.key ?: break)
        }
    }

    @Synchronized
    fun contains(id: String): Boolean {
        val now = clock()
        pruneExpired(now)
        return entries.containsKey(id.trim())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { (_, createdAt) -> now - createdAt > ttlMillis }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}
