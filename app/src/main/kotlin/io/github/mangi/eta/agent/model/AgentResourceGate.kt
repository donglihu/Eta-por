package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.ToolDomain
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.EtaTrace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * 资源域闸门：把「一把全局锁」换成「按资源域排队」。
 *
 * 旧实现（[AgentToolGate]）让所有非只读工具走同一把公平锁：灵屏、终端、文件写入、系统设置、
 * 浏览器、MCP 全部互相排队，一个会话在滑屏，另一个会话连写个文件都得等——并发会话的收益被抹平。
 *
 * 这里的串行粒度是**资源**而不是工具：
 * - 屏幕按**灵屏会话**（该会话有活跃灵屏 → `SCREEN:ls:<conversation>`，否则全局 `SCREEN:main`，
 *   见 [screenSlotOf]）。不按参数里的 `display_id` 分槽：全仓没有任何调用方真的传这个参数，
 *   按参数分槽等于所有屏幕操作共用 `SCREEN:main`，两个会话各自一块屏却互相排队；
 * - 终端按会话（同一对话一条 shell）；
 * - 文件写入按父目录（同目录两次写会互相覆盖，不同目录可以并行）；
 * - 设备设置、浏览器各一把全局锁；
 * - MCP 按 server 段；
 * - 其余工具 [ToolDomain.NONE] 直接放行，不进闸门。
 *
 * 票（[DomainTicket]）由 [ticketOf] 从工具名 + 参数算出，[run] 负责排队、打点、超时与释放。
 * [AgentToolGate] 保留：`enabled = false` 时可整体退回旧的全局锁，作为线上兜底开关。
 */
internal object AgentResourceGate {

    /**
     * 总闸门开关。关掉即退回 [AgentToolGate] 的全局锁（行为与改动前一致）。
     *
     * 它是 [Prefs.Keys.AGENT_RESOURCE_GATE_ENABLED] 的内存缓存，不是唯一事实源：
     * - 启动/每次运行开始时由 [refreshEnabledFromPrefs] 从本地 Agent 配置读一次；
     * - 线上回滚不必重出包：把该 key 写成 `false`（设置页写入后调 [refreshEnabledFromPrefs]，
     *   或直接 `Prefs.localAgentPreferences()?.edit()?.putBoolean(key, false)?.apply()`），
     *   下一次刷新即退回旧全局锁；临时排障可以直接赋 `enabled = false`，但那只在进程内有效。
     * - 读失败（Preference 类型被改坏等）一律按 `true` 处理：闸门是默认路径，静默关掉等于退出保护。
     */
    @Volatile
    var enabled: Boolean = true

    /** 从本地 Agent 配置重新加载 [enabled]；设置页写入或运行开始时可调用，读取失败按 `true`。 */
    fun refreshEnabledFromPrefs() {
        val stored = try {
            Prefs.localAgentPreferences()?.getBoolean(Prefs.Keys.AGENT_RESOURCE_GATE_ENABLED, true)
        } catch (error: RuntimeException) {
            // 具名 catch + 留痕：开关读失败必须可见，但不能因此把闸门静默关掉。
            EtaTrace.log(
                "perf.gate.prefs_failed",
                "key=${Prefs.Keys.AGENT_RESOURCE_GATE_ENABLED} err=${error.message}",
            )
            null
        }
        enabled = stored ?: true
    }

    /**
     * 屏幕槽 key 来源：默认全局 `main`；生产侧由 `AgentRuntimeRunExecutor` 注入
     * 「该会话有活跃灵屏 → `ls:<conversationKey>`」。
     *
     * 为什么需要注入：灵屏是**按 owner 多开**的（`VirtualScreenSession.isActive(owner)`），
     * 两个会话各有一块屏。槽位若恒定 `main`，A 会话在滑屏时 B 会话的屏幕动作会排同一个队，
     * 等满 [DEFAULT_WAIT_MS] 后退化成 RESOURCE_BUSY——并发会话的收益被抹平。
     * 注入点必须放在 runtime 包（那边本来就依赖 `agent/display`），model 包不反向依赖显示层。
     */
    @Volatile
    var screenSlotOf: (conversationKey: String) -> String = { "main" }

    init {
        // 对象首次被触达时读一次配置：冷启动即生效，不依赖宿主动手。
        refreshEnabledFromPrefs()
    }

    /** 等锁超过这个时长才记一行；阈值必须小于判据 p95，否则采样不到「等锁」。 */
    const val WARN_WAIT_MS = 50L

    /** 单次申请的最长排队时间：超过就回 RESOURCE_BUSY，让模型换个时机再来。 */
    const val DEFAULT_WAIT_MS = 120_000L

    /** 排队轮询粒度：够细以免小等待被放大成 [POLL_MS]，也够粗以免空转。 */
    private const val POLL_MS = 100L

    /** 可注入时钟（毫秒）：单测用它把「等锁时长」变成确定值。 */
    @Volatile
    var clockMs: () -> Long = { System.nanoTime() / 1_000_000 }

    /**
     * 旧全局锁的注入点：默认就是 [AgentToolGate]。
     *
     * 抽成接口有两个原因：① `enabled = false` 的分支要在单测里可验证，而 [AgentToolGate]
     * 依赖 `android.os.SystemClock`，纯 JVM 单测里调不动；② 允许宿主替换实现而不改调用点。
     */
    @Volatile
    var legacyGate: LegacyGate = object : LegacyGate {
        override fun <T> withGate(toolName: String, block: () -> T): T =
            AgentToolGate.withGate(toolName, block)
    }

    internal interface LegacyGate {
        fun <T> withGate(toolName: String, block: () -> T): T
    }

    private class Slot {
        /** 公平信号量：先到先得，避免低流量会话被反复插队饿死。 */
        val semaphore = Semaphore(1, true)

        /** 当前持有者（会话键），只用于打点与错误消息，不参与互斥判定。 */
        @Volatile
        var holder: String? = null

        /** 排队人数：必须是原子量，`++/--` 复合写在并发下会丢计数。 */
        val queued = AtomicInteger(0)
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** 当前线程已持有的域：同线程重入会自锁（Semaphore 不可重入），必须显式拒绝。 */
    private val nesting = ThreadLocal.withInitial { mutableSetOf<String>() }

    /** JDK 把 `ThreadLocal.get()` 标成可空；这里带首次初始化兜底，保证整条路径非空。 */
    private fun heldDomains(): MutableSet<String> =
        nesting.get() ?: mutableSetOf<String>().also { nesting.set(it) }

    /** 一次工具调用的资源票：域 + 槽位键；[ToolDomain.NONE] 的键为 null，表示不进闸门。 */
    data class DomainTicket(val domain: ToolDomain, val key: String?)

    class GateTimeout(
        val slotKey: String,
        val holder: String?,
        val waitedMs: Long,
    ) : IllegalStateException(
        "资源 $slotKey 等待 ${waitedMs}ms 仍未释放（持有者 ${holder ?: "未知"}）",
    )

    class GateReentrant(
        val slotKey: String,
        val holder: String?,
    ) : IllegalStateException("域 $slotKey 在同线程内重复申请（持有者 ${holder ?: "本线程"}）")

    class GateCancelled(val slotKey: String) : IllegalStateException("资源 $slotKey 排队期间运行被取消")

    /**
     * 把一次工具调用换算成资源票。
     *
     * @param action `terminal` 的 action；SHELL 域按会话串行，open/close/exec 同槽位（不按 action 分槽，
     *        否则同一会话的两条命令会并行跑进同一个 shell 会话里）。
     */
    fun ticketOf(
        toolName: String,
        args: JSONObject?,
        conversationKey: String,
        action: String? = null,
    ): DomainTicket {
        val domain = AgentToolRequirements.domainOf(toolName)
        if (domain == null) {
            // 未登记工具：不能放行（会绕过所有串行），也不该直接失败，退回保守的设备域并留痕。
            //
            // 槽位键带上工具名：早先所有未登记工具共用 `DEVICE:fallback` 一个槽位，
            // 等于让互不相关的工具互相排队，与「按资源并行」的设计目标相反。
            EtaTrace.log(
                "perf.gate.unregistered",
                "tool=$toolName conv=$conversationKey action=${action ?: "-"}",
            )
            return DomainTicket(ToolDomain.DEVICE, "$DOMAIN_DEVICE_FALLBACK:$toolName")
        }
        return when (domain) {
            ToolDomain.NONE -> DomainTicket(ToolDomain.NONE, null)
            ToolDomain.SCREEN -> DomainTicket(
                ToolDomain.SCREEN,
                "SCREEN:" + screenSlotOf(conversationKey).trim().ifEmpty { "main" },
            )
            ToolDomain.SHELL -> DomainTicket(ToolDomain.SHELL, "SHELL:$conversationKey/shell")
            ToolDomain.FS_WRITE -> DomainTicket(ToolDomain.FS_WRITE, "FS_WRITE:" + fsSlotOf(args))
            ToolDomain.DEVICE -> DomainTicket(ToolDomain.DEVICE, "DEVICE:global")
            ToolDomain.BROWSER -> DomainTicket(ToolDomain.BROWSER, "BROWSER:global")
            ToolDomain.MCP -> DomainTicket(ToolDomain.MCP, "MCP:" + mcpServerOf(toolName))
        }
    }

    /**
     * 单点分派：开启时走域闸门，关闭时原样退回旧的全局锁。
     *
     * [AgentLoop] 只有这一个调用点；`enabled = false` 时不构造票、不占槽位。
     */
    fun <T> dispatch(
        toolName: String,
        args: JSONObject?,
        conversationKey: String,
        action: String? = null,
        isCancelled: () -> Boolean = { false },
        block: () -> T,
    ): T {
        if (!enabled) return legacyGate.withGate(toolName, block)
        val ticket = ticketOf(toolName, args, conversationKey, action)
        return run(ticket, toolName, conversationKey, isCancelled = isCancelled, block = block)
    }

    /**
     * 在票对应的域里独占执行 [block]。
     *
     * 排队循环每轮先看取消（用户点了停止不该再等两分钟），再按 [POLL_MS] 粒度拿信号量；
     * 超过 [waitMs] 回 [GateTimeout]，取消回 [GateCancelled]，同线程重入回 [GateReentrant]。
     */
    fun <T> run(
        ticket: DomainTicket,
        toolName: String,
        conversationKey: String,
        waitMs: Long = DEFAULT_WAIT_MS,
        isCancelled: () -> Boolean = { false },
        block: () -> T,
    ): T {
        val key = ticket.key ?: return block()
        if (ticket.domain == ToolDomain.NONE) return block()

        val slot = slots.computeIfAbsent(key) { Slot() }
        val held = heldDomains()
        if (key in held) {
            val previous = slot.holder ?: Thread.currentThread().name
            EtaTrace.log("perf.gate.reentrant", "domain=$key tool=$toolName holder=$previous conv=$conversationKey")
            throw GateReentrant(key, previous)
        }

        slot.queued.incrementAndGet()
        var acquired = false
        val startedAt = clockMs()
        try {
            while (true) {
                if (isCancelled()) {
                    EtaTrace.log("perf.gate.cancelled", "domain=$key tool=$toolName conv=$conversationKey")
                    throw GateCancelled(key)
                }
                val remaining = waitMs - (clockMs() - startedAt)
                if (remaining <= 0) {
                    val waitedMs = clockMs() - startedAt
                    val holder = slot.holder
                    EtaTrace.log(
                        "perf.gate.timeout",
                        "domain=$key tool=$toolName waited_ms=$waitedMs holder=${holder ?: "无"} " +
                            "queued=${slot.queued.get()} conv=$conversationKey",
                    )
                    throw GateTimeout(key, holder, waitedMs)
                }
                if (slot.semaphore.tryAcquire(minOf(POLL_MS, remaining), TimeUnit.MILLISECONDS)) {
                    acquired = true
                    break
                }
            }
            val waited = clockMs() - startedAt
            held.add(key)
            val previous = slot.holder
            slot.holder = conversationKey
            if (waited >= WARN_WAIT_MS) {
                EtaTrace.log(
                    "perf.gate.wait",
                    "domain=$key tool=$toolName waited_ms=$waited holder=${previous ?: "无"} " +
                        "queued=${slot.queued.get()} conv=$conversationKey",
                )
            }
            return block()
        } finally {
            // 顺序不能换：先摘掉线程级标记（否则同线程永远被判重入），再清持有者，最后归还信号量。
            // 没拿到信号量时归还会凭空多出一个许可，等于把互斥打穿，所以只有 acquired 才释放。
            held.remove(key)
            if (acquired) {
                slot.holder = null
                slot.semaphore.release()
            }
            slot.queued.decrementAndGet()
        }
    }

    /** 某个域当前排队人数（含持有者），给错误消息与排查用。 */
    fun queuedCount(key: String): Int = slots[key]?.queued?.get() ?: 0

    /** 文件写入槽位：父目录归一化路径。不拼 owner / 子代理 id —— 同目录的两次写必须串行。 */
    private fun fsSlotOf(args: JSONObject?): String {
        val raw = args?.optString("path").orEmpty().trim()
        // 无路径的写入（skills_manage 等）改的是共享技能库，按全局串行最保守。
        if (raw.isEmpty()) return "workspace"
        val normalized = normalizePath(raw)
        if (!normalized.startsWith("/")) return "relative"
        val parent = normalized.substringBeforeLast('/', "")
        return parent.ifEmpty { "/" }
    }

    /**
     * 纯字符串路径归一化：折叠 `//`、`.`、`..`，输出绝对路径（相对路径保持相对）。
     *
     * 不走 `File.canonicalPath`：它会碰文件系统（不存在就报错、会解析符号链接），
     * 而这里只需要「同一目标目录算出同一个键」。
     */
    private fun normalizePath(raw: String): String {
        val absolute = raw.startsWith("/")
        val parts = ArrayDeque<String>()
        for (segment in raw.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> {
                    if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast()
                    else if (!absolute) parts.addLast("..")
                }
                else -> parts.addLast(segment)
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    /** MCP 工具名形如 `mcp_<serverPart>_<tool>_<digest>`：取 `mcp_` 之后、下划线之前的 server 段。 */
    private fun mcpServerOf(toolName: String): String {
        val rest = toolName.removePrefix(MCP_PREFIX)
        val server = rest.substringBefore('_')
        return server.ifEmpty { "unknown" }
    }

    private const val MCP_PREFIX = "mcp_"
    private const val DOMAIN_DEVICE_FALLBACK = "DEVICE:fallback"
}