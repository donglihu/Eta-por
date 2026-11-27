package io.github.mangi.eta.agent.display

import android.app.KeyguardManager
import android.content.Context
import android.util.Base64
import android.os.SystemClock
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.dashboard.AgentInjectionGate
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.core.MemoryPressure
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 灵屏会话表：**每个会话（owner）一块屏**，可以多开。
 *
 * - [sessions] 以 owner（会话 id）为键；同一个会话跨 run 复用同一块屏；
 * - 并发上限 [MAX_SESSIONS]，超限时先回收别的会话里空闲（无 run 租约）的屏；
 * - 工具全部按 owner 路由，A 会话的操作不会落到 B 的屏上；
 * - [state] 只镜像「焦点屏」的视图状态，供悬浮窗/查看页显示；切焦点时把该屏的快照推出去。
 *
 * 宿主面（直通出帧）按 owner 分槽：焦点屏拿得到 Surface 就走直通，其余屏自动回落到
 * 整帧 JPEG 管线，画面不会互相抢占。
 */
internal object VirtualScreenSession {
    /**
     * 状态锁：只保护 [sessions]、[focusedOwner]、[viewerState] 镜像这几处极短临界区。
     *
     * 早前这里是一把全局锁，从 [execute] 头一路包到 helper 15s 响应读完：
     * 多开时 A 建屏/读帧会把 B 的取帧、关闭、状态查询全部堵住，锁内还有
     * `runBlocking { RootAccess.request }` 与读盘。现在按 owner 拆锁（见 [ownerLock]），
     * 本锁只在改表和镜像焦点时进出，毫秒级。
     */
    private val stateLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * owner → 该屏的串行锁。
     *
     * 同一块屏仍严格串行（helper 的行协议不能并发读写），不同屏互不阻塞 ——
     * 这就是「多开互不打架」与「一块屏卡住不拖死全进程」的分界。
     */
    private val ownerLocks =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()

    private fun ownerLock(owner: String): java.util.concurrent.locks.ReentrantLock =
        ownerLocks.computeIfAbsent(owner) { java.util.concurrent.locks.ReentrantLock() }

    /** 纯读状态的短等上限：主线程最多被拖这么久，远低于输入分发 5s 的 ANR 线。 */
    private const val MAIN_READ_WAIT_MS = 120L

    /** 等一条 `ETA_DISPLAY_RESULT:` 时最多容忍多少行（含 helper 夹带的诊断输出）。 */
    private const val MAX_RESPONSE_LINES = 64

    /**
     * 纯读状态用：允许短等 [MAIN_READ_WAIT_MS]，超时给兜底值，避免锁竞争时读到错误结论。
     */
    private inline fun <T> readLocked(busy: () -> T, block: () -> T): T {
        if (!stateLock.tryLock(MAIN_READ_WAIT_MS, TimeUnit.MILLISECONDS)) return busy()
        try {
            return block()
        } finally {
            stateLock.unlock()
        }
    }

    /**
     * 只锁「自己那块屏」做事，拿不到立刻回兜底值。
     *
     * ANR 现场（`/data/anr/anr_2026-11-25_09-13-00-580`）：`agent-runtime` 在 [execute] 里持锁，
     * 锁内又等无障碍主线程（`CountDownLatch` 最长 3s）；同一时刻主线程在悬浮窗协程里想进同一把锁
     * → 互等 → Input dispatch 等满 5s → 进程被判 ANR 杀掉 → helper 连带消失（「灵屏闪退」）。
     *
     * 所以查看页/浮窗入口一律走这里：只锁目标屏，锁被占用就放弃本次，下一帧自然重试；
     * 也不会再因为「别的屏在忙」而误把自己判忙（旧全局锁的问题）。
     */
    private inline fun <T> tryOwnerLocked(owner: String, busy: () -> T, block: () -> T): T {
        val lock = ownerLock(owner)
        if (!lock.tryLock()) return busy()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * [sessions] 只在 [stateLock] 内读写；下面几个小口子让调用点不必到处加锁。
     *
     * 仍是 LinkedHashMap（保持创建顺序，回收先挑最早的），并发安全由 [stateLock] 保证，
     * 注意**不要在持 [stateLock] 时去拿 [ownerLock]**（锁序统一为 ownerLock → stateLock）。
     */
    private fun sessionOf(owner: String): Session? = stateLock.withLock { sessions[owner] }

    private fun liveSessionOf(owner: String): Session? = stateLock.withLock { aliveSessionLocked(owner) }

    private fun sessionsSnapshot(): List<Session> = stateLock.withLock { sessions.values.toList() }

    private fun sessionCount(): Int = stateLock.withLock { sessions.size }

    private fun putSession(session: Session) = stateLock.withLock { sessions[session.owner] = session }

    private fun dropSession(session: Session) = stateLock.withLock {
        if (sessions[session.owner] === session) sessions.remove(session.owner)
        Unit
    }

    private fun aliveSessionLocked(owner: String): Session? = sessions[owner]?.takeIf {
        !it.closed.get() && it.process.isAlive
    }

    /** 锁被占用时的可重试失败：调用方按「忙，稍后重试」处理，不当成灵屏异常。 */
    private fun busyResult() = failure("VIRTUAL_SCREEN_BUSY")

    /** 设置快照 TTL：兼顾「设置页切完立刻生效」与「25fps 路径不读盘」。 */
    private const val SETTINGS_CACHE_TTL_MS = 1_500L

    /**
     * 手机是否处于锁屏（亮屏锁屏也算）。
     *
     * helper 是裸 app_process，Android 17 上它自己问不到 KeyguardManager（构造会碰
     * WindowManagerGlobal → ApplicationSharedMemory），所以由主进程算好随 create / lifecycle 下发。
     */
    internal fun mainScreenLocked(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true }
            .getOrDefault(false)

    private val deadlines = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "eta-display-deadline").apply { isDaemon = true }
    }

    /** owner → 屏。LinkedHashMap 保持创建顺序，回收时先尝试最早的。 */
    private val sessions = LinkedHashMap<String, Session>()

    /**
     * 设置快照缓存。
     *
     * [execute] 被浮窗按 25fps 调用，原来每次都在全局锁里 `runBlocking` 读 DataStore：
     * 等于每秒几十次阻塞读盘，还全部压在锁内，主线程一进来就是 ANR 的候选。
     * 这里给 1.5 秒 TTL——设置页的一次切换仍有亚秒级生效，浮窗路径的读盘次数降两个数量级。
     */
    @Volatile
    private var settingsCache: Settings? = null

    @Volatile
    private var settingsCacheAt = 0L

    /** 非预期退出后的重建配额：同一 owner 10 分钟内最多 3 次，防重建风暴。 */
    private val rebuildHistory = mutableMapOf<String, MutableList<Long>>()
    private const val REBUILD_WINDOW_MS = 10 * 60 * 1000L
    private const val REBUILD_MAX_ATTEMPTS = 3

    /** 焦点屏镜像：浮窗与查看页只订阅它。 */
    private val viewerState = MutableStateFlow(VirtualScreenViewerState())
    val state = viewerState.asStateFlow()

    @Volatile
    private var focusedOwner: String? = null

    /**
     * 灵屏数量不再设数字上限，改由内存把关。
     *
     * 每块屏常驻约 80MB（1440x3168 全尺寸采集缓冲 + 复用位图），
     * 硬性限个数没有意义：内存够就多开，不够就拒绝，判据用 [hasMemoryForNewSession]。
     */
    private const val MIN_FREE_MB_FOR_NEW_SESSION = 1200L

    /**
     * 剩余内存够不够再开一块屏。
     *
     * 判据统一走 [MemoryPressure]：早前这里自己解析 `/proc/meminfo`，和拉帧、图片跨进程传输
     * 各判各的，出现「一边认为还够、另一边已经贴顶」；读失败还 `?: true` 双重 fail-open，
     * 等于看不见就当够。现在读不到系统水位时只按堆水位判（Critical 直接拒）。
     */
    private fun hasMemoryForNewSession(): Boolean =
        MemoryPressure.admit(MIN_FREE_MB_FOR_NEW_SESSION * 1024L * 1024L)

    private val gestureIds = AtomicLong()

    /** 编码长边上限：q100 下的性能红线（见 create 里的注释）。 */
    private const val MAX_ENCODE_LONG_SIDE = 1760

    /**
     * 出帧档位。只在没走直通（JPEG 回落管线）时生效；直通建立后由合成器直出，与档位无关。
     *
     * - [FLOAT]：浮窗展开可见，25fps / q80，跟手优先；
     * - [FLOAT_COLLAPSED]：浮窗贴边收起成气泡或渐变条，画面已隐藏，5fps 只为偶尔瞄一眼；
     * - [TAKEOVER]：接管页在前台，帧间隔对齐真机屏幕刷新率，质量 q90。
     */
    internal enum class WatchProfile(val intervalMs: Int, val quality: Int, val label: String) {
        /** 浮窗展开：25fps。 */
        FLOAT(40, 80, "浮窗"),

        /** 浮窗收起：5fps。 */
        FLOAT_COLLAPSED(200, 80, "浮窗收起"),

        /** 接管页：intervalMs 留 0，下发时按真机刷新率换算。 */
        TAKEOVER(0, 90, "接管"),
    }

    /**
     * 读屏档（无人在看）：5fps / q80，与浮窗收起同档。
     * 区别只在 helper 侧：读屏档还要满足「有客户端在取帧」才编码，纯挂机时不烧 CPU。
     */
    private const val READ_SCREEN_INTERVAL_MS = 200
    private const val READ_SCREEN_QUALITY = 80

    /** 灵屏合成刷新率上限：再高对「Agent 读屏 + 人工接管」没有收益，只多耗电。 */
    private const val MAX_TARGET_HZ = 90

    /** 可用的刷新率档位，从高到低匹配设备能力。 */
    private val REFRESH_TIERS = listOf(MAX_TARGET_HZ, 60)

    /** 各来源当前申请的档位（float / takeover），全部释放后回落到读屏档。 */
    private val watchProfiles = LinkedHashMap<String, WatchProfile>()

    /** 合并后的当前档位；null = 读屏档（自适应 1–3fps + 用户设置的质量）。 */
    @Volatile
    private var watchProfile: WatchProfile? = null

    /** 最近一次 execute 的 App 上下文：给刷新率查询用，避免为了一个数字去抢 [lock]。 */
    @Volatile
    private var appContext: android.content.Context? = null

    /**
     * 屏幕刷新率档位：取「设备主屏支持的最高刷新率」与 [MAX_TARGET_HZ] 的较小值，
     * 对齐到 60 / 90 / 120 三档。
     *
     * 绝不能无条件报 120：设备只有 60Hz 时虚屏声明 120Hz，App 会按 8.3ms 排帧、
     * 实际 vsync 只有 16.6ms，动画反而比自己声明 60Hz 更卡。
     */
    private fun targetRefreshHz(): Int {
        val maxHz = runCatching {
            val ctx = appContext ?: return 60
            val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?: return 60
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return 60
            display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        }.getOrDefault(60f)
        return REFRESH_TIERS.firstOrNull { it <= maxHz + 0.5f } ?: 60
    }

    /** 接管档据此对齐屏刷新率；与 [targetRefreshHz] 同源，不再单独读 display.refreshRate。 */
    private fun mainRefreshHz(): Float = targetRefreshHz().toFloat()

    private val operationIds = AtomicLong()

    /** 会被写入灵屏的输入类动作；接管态下一律要过注入门。 */
    private val AGENT_INPUT_ACTIONS = setOf("tap", "swipe", "touch", "long_press", "text", "key", "back")

    private val cleanup = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eta-display-cleanup").apply { isDaemon = true }
    }

    private class Session(
        val context: Context,
        val owner: String,
        val process: java.lang.Process,
        val input: BufferedWriter,
        val output: BufferedReader,
        var width: Int,
        var height: Int,
        /** 建屏时的密度：helper 非预期退出后重建要还原同一档，否则灵屏布局会跳一次。 */
        val density: Int,
        val allowOff: Boolean,
        /** 建这块屏时绑定的 run；helper 意外退出重建时要沿用同一个 run 归属。 */
        val runId: String?,
    ) {
        val id = UUID.randomUUID().toString()
        var displayId: Int = -1
        val closed = AtomicBoolean()
        val runLease = VirtualScreenRunLease(runId)

        /** 这块屏自己的视图状态；只有它是焦点时才抄到外层的 [viewerState]。 */
        val viewer = MutableStateFlow(
            VirtualScreenViewerState(
                taskPhase = if (runId == null) VirtualScreenTaskPhase.IDLE else VirtualScreenTaskPhase.RUNNING,
                activeRunId = runId,
            ),
        )

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            val idle = stateLock.withLock {
                if (sessions[owner] === this) sessions.remove(owner)
                if (focusedOwner == owner) {
                    focusedOwner = sessions.keys.lastOrNull()
                }
                // 无条件重算：早前只在「自己是 focused」时更新，导致非 focus 的会话关闭后
                // 对外状态仍指向已销毁的 display，悬浮窗就定格在最后一帧（实测卡死）。
                viewerState.value = focusedOwner?.let { sessions[it]?.viewer?.value } ?: VirtualScreenViewerState()
                sessions.values.none { !it.closed.get() }
            }
            viewer.update { it.copy(display = null, gesture = null, activeRunId = null) }
            VirtualScreenNotification.cancel(context, id)
            AgentExecutionService.release("virtual-screen-$id")
            // 不等待 execute 持有的锁或正在读取的帧；主线程取消必须立即返回。
            process.destroy()
            cleanup.execute {
                runCatching { process.outputStream.close() }
                runCatching { input.close() }
                runCatching { output.close() }
            }
            // 所有关屏路径（工具 close、租约不保留、致命错误、closeOwner）都会走到这里：
            // 唤醒锁按「表里还有没有活屏」统一释放，避免某条路径漏放——漏放就是屏关了
            // 主屏还常亮到 4 小时兜底。
            if (idle) releaseWakeLockIfIdle("session-close")
        }
    }

    /**
     * 设置快照：1.5 秒 TTL。
     *
     * 读盘失败不缓存、不吞：直接把异常抛给 [execute] 的 catch，由它按 VIRTUAL_SCREEN_FAILED
     * 回给调用方——「读不到设置」和「设置关着」在语义上必须分开。
     */
    private fun currentSettings(): Settings {
        val now = SystemClock.elapsedRealtime()
        settingsCache?.let { cached ->
            if (now - settingsCacheAt < SETTINGS_CACHE_TTL_MS) return cached
        }
        val fresh = runBlocking { SettingsDataStore.settings() }
        settingsCache = fresh
        settingsCacheAt = now
        return fresh
    }

    /**
     * helper 非预期退出后的重建。
     *
     * 为什么不直接在退出线程里建：退出线程要立刻返回，而 create 会走 [execute] 的全局锁；
     * 排到 cleanup 线程既避免在 `waitFor` 回调里做重活，也让同 owner 的重建串行。
     * 参数沿用建屏时的 capture 尺寸与密度，保证重建后布局不跳档（否则灵屏会按默认档重排一次）。
     */
    private fun scheduleRebuild(dead: Session, exitCode: Int) {
        val now = SystemClock.elapsedRealtime()
        val allowed = stateLock.withLock {
            val history = rebuildHistory.getOrPut(dead.owner) { mutableListOf() }
            history.removeAll { now - it > REBUILD_WINDOW_MS }
            if (history.size >= REBUILD_MAX_ATTEMPTS) {
                false
            } else {
                history += now
                true
            }
        }
        if (!allowed) {
            VscrDiag.line("灵屏重建放弃 owner=" + dead.owner.take(8) + " 原因=配额用尽 exitCode=" + exitCode)
            return
        }
        VscrDiag.line("灵屏重建排入 owner=" + dead.owner.take(8) + " exitCode=" + exitCode)
        cleanup.execute {
            runCatching {
                // 让 helper 释放 display 落定：立刻重开容易拿到同一 displayId 的残影。
                Thread.sleep(800L)
                val request = JSONObject()
                    .put("action", "create")
                    .put("width", dead.width)
                    .put("height", dead.height)
                    .put("density", dead.density)
                val result = execute(dead.context, dead.owner, request, runId = dead.runId)
                val ok = runCatching { JSONObject(result.content).optBoolean("ok") }.getOrDefault(false)
                VscrDiag.line("灵屏重建 owner=" + dead.owner.take(8) + " ok=" + ok)
                if (!ok) {
                    // 重建失败必须留原因，否则「灵屏老是没」永远无从查起。
                    VscrDiag.line(
                        "灵屏重建失败 owner=" + dead.owner.take(8) + " 原样=" + result.content.take(160)
                    )
                }
                Unit
            }.onFailure { error ->
                VscrDiag.line(
                    "灵屏重建异常 owner=" + dead.owner.take(8) +
                        " 原因=" + (error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    /** 把某块屏的状态抄到外层镜像（仅当它是焦点）。 */
    private fun publish(session: Session) = stateLock.withLock {
        if (focusedOwner == session.owner) viewerState.value = session.viewer.value
        Unit
    }

    /** 切焦点：浮窗/查看页立刻换成这块屏。 */
    private fun focus(session: Session) {
        val switched = stateLock.withLock {
            if (focusedOwner == session.owner) return
            VscrDiag.line("灵屏焦点 " + (focusedOwner?.take(8) ?: "无") + " → " + session.owner.take(8))
            focusedOwner = session.owner
            viewerState.value = session.viewer.value
            true
        }
        // 出锁再回调：宿主面那边可能立刻反向调用本对象，持锁调用等于给死锁留门。
        if (switched) VirtualScreenHostSurfaces.setFocusOwner(session.owner)
    }

    fun isActive(): Boolean = readLocked({ true }) {
        sessions.values.any { !it.closed.get() && it.process.isAlive }
    }

    fun isActive(owner: String): Boolean = readLocked({ true }) {
        sessions[owner]?.let { !it.closed.get() && it.process.isAlive } == true
    }

    private fun mainScreenWidth(context: android.content.Context): Int =
        context.resources.displayMetrics.widthPixels

    private fun mainScreenHeight(context: android.content.Context): Int =
        context.resources.displayMetrics.heightPixels

    private fun mainScreenDensity(context: android.content.Context): Int =
        context.resources.displayMetrics.densityDpi

    /** 当前是否有任何一块灵屏活着（用于让 UI 工具自动跟随路由）。 */
    fun hasActiveSession(): Boolean = readLocked({ false }) {
        sessions.values.any { !it.closed.get() && it.process.isAlive }
    }

    fun isOwnedBy(owner: String): Boolean = isActive(owner)

    fun currentOwner(): String? = readLocked({ null }) { focusedOwner }

    fun activeOwners(): List<String> = readLocked({ emptyList() }) {
        sessions.values.filter { !it.closed.get() && it.process.isAlive }.map { it.owner }
    }

    private fun aliveSession(owner: String): Session? = liveSessionOf(owner)

    /**
     * [prepareForRun] 的结果。
     *
     * 以前是 Boolean，`false` 把「同一个 run 占着」和「内存不够开新屏」压成一个码，
     * 调用方只能统一回 DISPLAY_BUSY —— 用户看到「灵屏正被其他任务使用」，其实是他自己的任务
     * 在用同一块屏，或者只是手机内存紧张。
     */
    internal enum class PrepareOutcome {
        /** 屏已就绪，或内存允许新建。 */
        OK,

        /** 同一块屏正被另一个 run 持有租约。 */
        RUN_BUSY,

        /** 可用内存不够再开一块屏。 */
        NO_MEMORY,
    }

    /**
     * 进入工具前准备：本会话已有屏就走租约；没有屏则按内存允许新建。
     * 别的会话的屏不再挡住本会话（多开），只有到上限时才尝试回收对方空闲的屏。
     *
     * 只锁本 owner：别的屏在建/在读帧不会再让这里返回「正被其他任务使用」。
     */
    fun prepareForRun(owner: String, runId: String): PrepareOutcome = ownerLock(owner).withLock {
        sessionOf(owner)?.takeIf { it.closed.get() || !it.process.isAlive }?.let { stale ->
            dropSession(stale)
            stale.close()
        }

        val current = liveSessionOf(owner)
        if (current != null) {
            val acquired = current.runLease.acquire(runId)
            if (acquired) {
                // 只有当前没有可用的聚焦会话时才切画面。
                // 早前这里无条件 focus：B 会话一开跑就把 A 的浮窗画面顶掉，
                // 用户看到的是 B 的屏、A 的操作却在 A 的屏生效 —— 就是「串台」。
                val focused = stateLock.withLock { focusedOwner?.let { sessions[it] } }
                if (focused == null || focused.closed.get()) focus(current)
                if (current.viewer.value.activeRunId != runId) {
                    current.viewer.update { it.beginRun(runId) }
                    publish(current)
                    updateIdleTimeout(owner)
                }
            }
            return@withLock if (acquired) PrepareOutcome.OK else PrepareOutcome.RUN_BUSY
        }
        // 数量不设限，只看内存：够就再开一块，不够就明确告诉调用方，而不是悄悄关掉别人的屏。
        if (hasMemoryForNewSession()) return@withLock PrepareOutcome.OK
        VscrDiag.line(
            "灵屏 内存不足，拒绝新建 现有=" + sessionCount() + " " + MemoryPressure.describe()
        )
        val holder = sessionsSnapshot().firstOrNull { it.owner != owner }
        VscrDiag.line(
            "灵屏占用 busy holder=" + (holder?.owner?.take(8) ?: "无") + " 请求=" + owner.take(8)
        )
        PrepareOutcome.NO_MEMORY
    }

    fun releaseRun(owner: String, runId: String, retain: Boolean, cancelled: Boolean = false) =
        ownerLock(owner).withLock {
            val current = liveSessionOf(owner) ?: sessionOf(owner) ?: return@withLock
            if (!current.runLease.release(runId, retain)) return@withLock
            current.viewer.update { it.finishRun(runId, retain, cancelled) }
            publish(current)
            if (!retain) current.close() else updateIdleTimeout(owner)
        }

    /**
     * 把当前出帧参数/idle 阈值下发给屏。
     *
     * 派到 [cleanup] 线程执行：它要对每块屏各发一次 lifecycle，如果在调用方仍持有 owner 锁时
     * 同步做，A 屏线程等 B 屏锁、B 屏线程等 A 屏锁就是锁序反转的死锁。真正的下发见
     * [pushIdleTimeout]，按 owner 逐个排队，慢也只慢自己。
     */
    fun updateIdleTimeout(owner: String? = null) {
        cleanup.execute {
            runCatching { pushIdleTimeout(owner) }.onFailure { error ->
                VscrDiag.line("出帧参数下发失败 owner=" + (owner?.take(8) ?: "全部") + " 原因=" + (error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun pushIdleTimeout(owner: String?) {
        val targets = stateLock.withLock {
            if (owner != null) listOfNotNull(sessions[owner]) else sessions.values.toList()
        }
        if (targets.isEmpty()) return
        val settings = runCatching { runBlocking { SettingsDataStore.settings() } }.getOrNull() ?: return
        val profile = watchProfile
        val interval = when {
            profile == null -> READ_SCREEN_INTERVAL_MS
            profile == WatchProfile.TAKEOVER ->
                (1000f / mainRefreshHz()).toInt().coerceIn(4, 200)
            else -> profile.intervalMs
        }
        val quality = profile?.quality ?: READ_SCREEN_QUALITY
        targets.forEach { current ->
            if (current.closed.get()) return@forEach
            execute(
                current.context,
                current.owner,
                JSONObject().put("action", "lifecycle")
                    .put("idleTimeoutMinutes", settings.virtualScreenIdleTimeoutMinutes)
                    /*
                     * 有人看着这块屏（浮窗展开／贴边折叠／接管页）就算「在用」。
                     *
                     * 真机踩到：任务结束保留浮窗后，App 只按 activeRunId 判活，于是 helper 收到
                     * activeRun=false，20 分钟空闲阈值一到就被自己 SIGKILL（vscr.log：
                     * 22:58:01 保留浮窗 → 23:18:02 Killed → exitCode=137），用户留在屏上的浮窗
                     * 瞬间只剩一个带图标的气泡壳，看着像「只剩胶囊」。
                     * 浮窗还在 = 用户随时可能点进去，不能按空闲回收。
                     */
                    .put("activeRun", current.viewer.value.activeRunId != null || watchProfile != null)
                    .put("mainScreenLocked", mainScreenLocked(current.context))
                    .put("frameIntervalMs", interval)
                    .put("jpegQuality", quality)
                    .put("watchMode", profile != null),
            )
        }
    }

    /**
     * 申请/释放某个来源的出帧档位。多来源各占一个名额，取最激进的那个生效：
     * 接管页开着时浮窗即使收起也不会把帧率拖到 5fps。
     * 传 null 表示该来源释放。无会话时只记档位，下次建屏随 create 生效。
     */
    fun setWatchProfile(source: String, profile: WatchProfile?): Boolean {
        val snapshot: List<WatchProfile>
        val detail: String
        synchronized(watchProfiles) {
            if (profile == null) watchProfiles.remove(source) else watchProfiles[source] = profile
            snapshot = watchProfiles.values.toList()
            detail = watchProfiles.entries.joinToString(",") { it.key + "=" + it.value.label }
        }
        // 最激进 = 间隔最小（接管档按真机刷新率算）。放在锁外：resolveInterval 可能去问 DisplayManager。
        val merged = snapshot.minByOrNull { resolveInterval(it) }
        if (watchProfile == merged) return false
        watchProfile = merged
        VscrDiag.line(
            "出帧档位=" + (merged?.label ?: "读屏") +
                (if (merged == null) "" else " interval=" + resolveInterval(merged) + "ms q=" + merged.quality) +
                " 来源=[" + detail + "]"
        )
        runCatching { updateIdleTimeout() }
        return true
    }

    private fun resolveInterval(profile: WatchProfile): Int =
        if (profile == WatchProfile.TAKEOVER) (1000f / mainRefreshHz()).toInt().coerceIn(4, 200)
        else profile.intervalMs

    fun currentWatchProfile(): WatchProfile? = watchProfile

    /** 当前生效的出帧间隔（ms）。浮窗的拉帧节奏与 helper 出帧共用同一口径。 */
    fun currentFrameIntervalMs(): Int =
        watchProfile?.let { resolveInterval(it) } ?: READ_SCREEN_INTERVAL_MS

    fun recordOperation(owner: String, runId: String?, name: String, success: Boolean, manual: Boolean = false) {
        val current = readLocked({ null }) { sessions[owner]?.takeIf { !it.closed.get() } } ?: return
        val operation = VirtualScreenOperation(operationIds.incrementAndGet(), name, System.currentTimeMillis(), success, manual)
        current.viewer.update {
            if (runId != null && it.activeRunId != runId) it else it.record(operation)
        }
        publish(current)
    }

    /**
     * [execute] 的锁外预热：设置快照要读盘、Root 授权要等 IPC。
     *
     * 早前这两步压在全局锁内，浮窗 25fps 的每一次调用都把别的屏堵在后面；
     * 现在先在这里预热（这里失败不抛，锁内该报的错照旧报），锁内只读 TTL 缓存。
     */
    private fun prewarmBeforeExecute(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        runCatching { currentSettings() }
        if (!RootAccess.isGranted) {
            VscrDiag.line("RootAccess 未授权，主动申请一次")
            runCatching { runBlocking { runCatching { RootAccess.request(context).join() } } }
            VscrDiag.line("RootAccess 申请结果 granted=" + RootAccess.isGranted)
        }
    }

    fun execute(
        context: Context,
        owner: String,
        args: JSONObject,
        isCancelled: () -> Boolean = { false },
        runId: String? = null,
        appRestartApproved: Boolean = false,
        /** true = 用户在悬浮窗/查看页里的手动输入；不参与注入门控。 */
        manual: Boolean = false,
    ): AgentModelClient.ToolResult {
        // Root 授权与设置在锁外预热：首次进屏不该把 IPC 与读盘带进这块屏的锁。
        prewarmBeforeExecute(context)
        return ownerLock(owner).withLock {
            executeLocked(context, owner, args, isCancelled, runId, appRestartApproved, manual)
        }
    }

    /**
     * [execute] 的锁内实现：调用方必须已持有该 owner 的 [ownerLock]，本函数不再自行加锁。
     *
     * 单独拆出来是为了让「同一块屏串行、不同屏并行」只体现在 [execute] 的一行包装上，
     * 业务分支保持原样，改动面只有锁的边界。
     */
    private fun executeLocked(
        context: Context,
        owner: String,
        args: JSONObject,
        isCancelled: () -> Boolean,
        runId: String?,
        appRestartApproved: Boolean,
        manual: Boolean,
    ): AgentModelClient.ToolResult {
        return try {
            if (appContext == null) appContext = context.applicationContext
            check(!isCancelled()) { "DISPLAY_CANCELLED" }
            require(android.os.Build.VERSION.SDK_INT >= 34) { "DEVICE_UNSUPPORTED" }
            // 25fps 路径上不能每次读盘：走 1.5s TTL 快照（见 [currentSettings]）。
            val settings = currentSettings()
            require(settings.virtualScreenEnabled) { "VIRTUAL_SCREEN_DISABLED" }
            // Root 授权已在锁外预热（见 [prewarmBeforeExecute]），这里只判定，不在锁内等 IPC。
            require(RootAccess.isGranted) { "ROOT_REQUIRED" }
            val action = args.getString("action")
            // 用户接管期间，模型不许再往屏里塞手势/按键；用户自己的输入（manual）不受影响。
            if (!manual && action in AGENT_INPUT_ACTIONS) {
                require(AgentInjectionGate.isOpen()) { "USER_TAKEOVER" }
            }
            if (action == "launch" && args.optBoolean("restartApp")) {
                require(appRestartApproved || settings.virtualScreenAutoRestartApps) { "APP_RESTART_PERMISSION_REQUIRED" }
            }
            if (action == "create") {
                require(owner.isNotBlank()) { "DISPLAY_OWNER_REQUIRED" }
                sessionOf(owner)?.takeIf { it.closed.get() || !it.process.isAlive }?.let { stale ->
                    dropSession(stale)
                    stale.close()
                }
                /*
                 * 已经有活着的屏就复用，不再硬失败。
                 *
                 * 真机复现两次（vscr.log 11-18 07:38:09、11-19 12:45:49 `create 失败 DISPLAY_ALREADY_ACTIVE`）：
                 * 上一轮任务刚结束、浮窗还留着，下一条消息的 UI 工具照样会走「无屏则建屏」，
                 * 结果 `require(sessions[owner] == null)` 直接把它判死，用户看到的就是「灵屏启动失败」。
                 * 复用口径与新建一致：绑 run 租约、切焦点、把这块屏回传给调用方。
                 */
                aliveSession(owner)?.let { existing ->
                    val reused = existing.viewer.value.display
                    if (reused != null) {
                        VscrDiag.line(
                            "create 复用现有灵屏 displayId=" + existing.displayId +
                                " owner=" + owner.take(8) + " runId=" + runId,
                        )
                        if (runId != null) {
                            existing.runLease.acquire(runId)
                            existing.viewer.update { it.beginRun(runId) }
                        }
                        focus(existing)
                        publish(existing)
                        VirtualScreenWakeLock.sync(context, settings.virtualScreenKeepScreenOn)
                        updateIdleTimeout(owner)
                        return AgentModelClient.ToolResult(
                            JSONObject().put("ok", true)
                                .put("displayId", existing.displayId)
                                .put("width", reused.width).put("height", reused.height)
                                .put("density", reused.density)
                                .put("captureWidth", reused.captureWidth)
                                .put("captureHeight", reused.captureHeight)
                                .put("independentFocus", true)
                                .put("reused", true)
                                .toString()
                        )
                    }
                    // 屏活着但 display 还没就绪（create 正在收尾）：关掉重来，避免卡在两不管状态。
                    dropSession(existing)
                    existing.close()
                }
                if (!hasMemoryForNewSession()) {
                    VscrDiag.line(
                        "灵屏 内存不足，拒绝建屏 现有=" + sessionCount() + " " + MemoryPressure.describe()
                    )
                    error("INSUFFICIENT_MEMORY")
                }
                val allowOff = args.optBoolean("allowScreenOff", false)
                require(!allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
                val profile = VirtualScreenProfile.from(context)
                var width = bounded(args, "width", 320, VirtualScreenProfile.MAX_WIDTH, profile.width)
                var height = bounded(args, "height", 480, VirtualScreenProfile.MAX_HEIGHT, profile.height)
                // JPEG 质量固定 100 后，原生 1440x3168 单帧编码要几百毫秒，回落管线直接黑屏。
                // 统一把长边压到 1760 以内：q100 下编码 ~120ms，3fps 能跑满，文字依旧清晰。
                var density = bounded(args, "density", 120, VirtualScreenProfile.MAX_DENSITY, profile.density)
                val longSide = maxOf(width, height)
                if (longSide > MAX_ENCODE_LONG_SIDE) {
                    val scale = MAX_ENCODE_LONG_SIDE.toFloat() / longSide
                    width = ((width * scale).roundToInt() / 2 * 2).coerceAtLeast(320)
                    height = ((height * scale).roundToInt() / 2 * 2).coerceAtLeast(480)
                    // 关键：像素缩小后 density 必须同比缩，否则布局宽度从 360dp 掉到 200dp，
                    // 应用会以为屏幕变窄（实测酷安 viewport=200x440，字巨大、卡片竖排）。
                    density = (density * scale).roundToInt()
                        .coerceIn(120, VirtualScreenProfile.MAX_DENSITY)
                }
                io.github.mangi.eta.core.EtaTrace.log(
                    "vscr",
                    "helper 启动 runId=$runId owner=$owner ${width}x$height" +
                        " density=$density allowOff=$allowOff 多开=${sessionCount() + 1} 块",
                )
                HelperExitLog.started()
                VscrDiag.line("helper 启动 " + width + "x" + height + " d=" + density + " owner=" + owner)
                val apk = context.applicationInfo.sourceDir
                fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
                val process = ProcessBuilder(
                    "su",
                    "-c",
                    "CLASSPATH=${quote(apk)} app_process /system/bin io.github.mangi.eta.agent.display.RootDisplayCommandMain"
                ).start()
                VscrDiag.line("helper 进程已创建")
                Thread {
                    runCatching {
                        // 持续排空，同时把诊断文本留给 HelperExitLog；它只收短行，
                        // 超长行与疑似 base64 数据一律丢弃，不会带出画面或私有数据。
                        process.errorStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                HelperExitLog.stderr(line)
                                VscrDiag.line("helper stderr: " + line)
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val input = process.outputStream.bufferedWriter()
                val output = process.inputStream.bufferedReader()
                val current = Session(context.applicationContext, owner, process, input, output, width, height, density, allowOff, runId)
                putSession(current)
                focus(current)
                VirtualScreenWakeLock.sync(context, settings.virtualScreenKeepScreenOn)
                Thread {
                    val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                    // 退出码是判断 native 崩溃还是被系统回收的唯一线索，必须留下。
                    // unexpected=true 表示不是我们自己 close 掉的：这才是「灵屏突然没了」的那一类。
                    val unexpected = !current.closed.get()
                    io.github.mangi.eta.core.EtaTrace.log(
                        "vscr",
                        "helper 退出 owner=$owner exitCode=$exitCode unexpected=$unexpected"
                    )
                    HelperExitLog.finished(exitCode)
                    VscrDiag.line(
                        "helper 退出 owner=" + owner + " exitCode=" + exitCode + " unexpected=" + unexpected
                    )
                    current.close()
                    // 早前这里只 close 就结束：屏直接消失，用户看到的是「灵屏里的应用闪退」，
                    // 而且没有任何自动恢复，只能自己重开一次。非预期退出必须尝试重建。
                    if (unexpected) scheduleRebuild(current, exitCode)
                }.apply { name = "eta-display-exit"; isDaemon = true; start() }
                if (!AgentExecutionService.acquire(context, "virtual-screen-${current.id}", onStop = current::close)) {
                    current.close(); error("DISPLAY_LIFECYCLE_UNAVAILABLE")
                }
                if (isCancelled() || current.closed.get()) {
                    AgentExecutionService.release("virtual-screen-${current.id}")
                    current.close(); error("DISPLAY_CANCELLED")
                }
                val deadline = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
                val response = try {
                    input.write(
                        JSONObject().put("action", "create").put("width", width)
                            .put("height", height).put("density", density)
                            // 显示尺寸由 App 下发：helper 是 app_process 起的，
                            // 部分机型上 getRealMetrics 拿不到主屏，dispW 会退化成采集尺寸（800x1760），
                            // 于是所有坐标都被钳在半屏范围内。
                            .put("displayWidth", mainScreenWidth(context))
                            .put("displayHeight", mainScreenHeight(context))
                            .put("displayDensity", mainScreenDensity(context))
                            .put("sessionId", current.id)
                            .put("owner", owner)
                            .put("selfPackage", context.packageName)
                            .put("surfaceToken", VirtualScreenHostSurfaces.token)
                            .put("idleTimeoutMinutes", settings.virtualScreenIdleTimeoutMinutes)
                            // 虚拟屏自身的合成刷新率：按设备实际支持的最高档取（≤90Hz），
                            // 与出帧档位无关（档位只决定往 App 传图的频率）。
                            .put("refreshRate", targetRefreshHz())
                            .put("frameIntervalMs", READ_SCREEN_INTERVAL_MS)
                            .put("jpegQuality", READ_SCREEN_QUALITY)
                            .put("watchMode", watchProfile != null)
                            .put("activeRun", runId != null || watchProfile != null)
                            .put("allowScreenOff", allowOff)
                            .put("mainScreenLocked", mainScreenLocked(context)).toString()
                    ); input.newLine(); input.flush()
                    VscrDiag.line("create 已发送，等待 helper 响应")
                    readResponse(output)
                } catch (error: Exception) {
                    VscrDiag.line("create 等待失败: " + error.message)
                    current.close(); throw error
                } finally {
                    deadline.cancel(false)
                }
                VscrDiag.line("helper 响应: " + response.toString().take(300))
                if (!response.optBoolean("ok") || response.optInt("displayId") <= 0 || current.closed.get()) {
                    current.close()
                    error(response.optString("code", "ROOT_DISPLAY_UNAVAILABLE"))
                }
                current.displayId = response.getInt("displayId")
                // helper 用宿主面建屏成功才允许界面切直通；否则保持整帧管线。
                VirtualScreenHostSurfaces.setLive(owner, response.optBoolean("direct"))
                current.viewer.value = VirtualScreenViewerState(
                    // width/height 取 helper 回传的 display 坐标系（与主屏一致），
                    // 触摸注入就按它算；采集尺寸更小，仅用于画面等比显示。
                    display = VirtualDisplayInfo(
                        current.id, owner, current.displayId,
                        response.optInt("width", width),
                        response.optInt("height", height),
                        density = response.optInt("density", density),
                        captureWidth = response.optInt("captureWidth", width),
                        captureHeight = response.optInt("captureHeight", height),
                    ),
                    lastAction = "create",
                    taskPhase = if (runId == null) VirtualScreenTaskPhase.IDLE else VirtualScreenTaskPhase.RUNNING,
                    activeRunId = runId,
                )
                publish(current)
                VirtualScreenNotification.show(context, current.id)
                if (current.closed.get()) {
                    current.viewer.update { VirtualScreenViewerState() }
                    publish(current)
                    VirtualScreenNotification.cancel(context, current.id)
                    error("DISPLAY_CANCELLED")
                }
                return AgentModelClient.ToolResult(response.toString())
            }
            val current = sessionOf(owner) ?: error("NO_VIRTUAL_SCREEN")
            require(!current.closed.get() && current.process.isAlive) { "DISPLAY_GONE" }
            require(!current.allowOff || settings.virtualScreenOffEnabled) { "SCREEN_OFF_PERMISSION_REQUIRED" }
            focus(current)
            val timeout = deadlines.schedule({ current.close() }, 15, TimeUnit.SECONDS)
            val result = try {
                publishGesture(current, args)
                current.input.write(args.toString()); current.input.newLine(); current.input.flush()
                readResponse(current.output)
            } finally {
                timeout.cancel(false)
            }
            if (result.optBoolean("ok")) {
                // 横屏应用会让 Root 进程把 display 换成横屏尺寸，这里同步尺寸供小窗、坐标系与截图共用。
                val nextWidth = result.optInt("width", 0)
                val nextHeight = result.optInt("height", 0)
                if (nextWidth > 0 && nextHeight > 0) {
                    current.width = nextWidth
                    current.height = nextHeight
                }
                if (action in setOf("observe", "probe", "launch", "switch_task")) {
                    current.viewer.update { state -> state.copy(display = state.display?.let { info ->
                        info.copy(
                            width = current.width,
                            height = current.height,
                            focusedPackage = when (action) {
                                "probe" -> result.optString("packageName")
                                "launch", "switch_task" -> ""
                                else -> info.focusedPackage
                            },
                        )
                    }) }
                    publish(current)
                }
            }
            if (action !in setOf("observe", "probe")) {
                current.viewer.update { it.copy(lastAction = action) }
                publish(current)
            }
            if (action == "close") {
                current.close(); dropSession(current)
            }
            val encoded = result.optString("image")
            result.remove("image")
            val images = if (encoded.isNotBlank() && result.optBoolean("ok")) listOf(
                AgentModelClient.ModelImage(
                    "data:image/jpeg;base64,$encoded",
                    "image/jpeg",
                    encoded.length * 3 / 4,
                    current.width,
                    current.height,
                    "virtual_display",
                    true
                )
            ) else emptyList()
            AgentModelClient.ToolResult(result.toString(), images, sensitive = true)
        } catch (error: Exception) {
            VscrDiag.line("execute 失败 action=" + args.optString("action") + " 原因=" + error.message)
            sessionOf(owner)?.let { current ->
                /*
                 * 哪些错误要连屏一起销毁：
                 *  - 进程没了／已被关闭：屏确实不存在了；
                 *  - ROOT_REQUIRED／VIRTUAL_SCREEN_DISABLED：配置或授权变了，留着也没用；
                 *  - DISPLAY_GONE／ROOT_DISPLAY_DISCONNECTED：helper 那边 display 已经没了。
                 *
                 * SCREEN_OFF_PERMISSION_REQUIRED 不在其中：它只是「主屏现在熄着」的瞬时状态，
                 * 屏本身是好的。早前把它当致命错误直接 close()，于是熄屏一次就整块灵屏报废，
                 * 用户侧表现就是「灵屏经常失败、要重新开」。
                 */
                val fatal = current.closed.get() || !current.process.isAlive ||
                    error.message in setOf(
                        "ROOT_REQUIRED", "VIRTUAL_SCREEN_DISABLED",
                        "DISPLAY_GONE", "ROOT_DISPLAY_DISCONNECTED",
                    )
                if (fatal) {
                    current.close()
                    dropSession(current)
                }
            }
            AgentModelClient.ToolResult(
                JSONObject().put("ok", false).put(
                    "code",
                    if (error is IllegalArgumentException || error is IllegalStateException) error.message
                        ?: "VIRTUAL_SCREEN_FAILED" else "VIRTUAL_SCREEN_FAILED"
                )
                    .put(
                        "message",
                        if (error.message == "SCREEN_OFF_PERMISSION_REQUIRED") {
                            "主屏熄屏或锁屏中，这个动作需要屏幕亮着；亮屏后再试一次（灵屏没有被关闭）。"
                        } else {
                            "灵屏操作未执行；不会回退到主屏。请确认 Root、灵屏开关和设备接口。"
                        }
                    ).toString()
            )
        }
    }

    /** A′ 直通：宿主面变了（查看页/小窗重建、互切），让 helper 立刻重新取面。 */
    fun notifyHostSurface(context: Context) {
        val targets = sessionsSnapshot()
        if (targets.isEmpty()) VscrDiag.line("直通 notify 无会话")
        targets.forEach { current ->
            if (current.closed.get()) return@forEach
            // 逐屏各锁各的：某块屏正在建/读帧就只跳过它，不连累其它屏换面。
            val lock = ownerLock(current.owner)
            if (!lock.tryLock()) {
                VscrDiag.line("直通 notify 跳过：该屏正忙 owner=" + current.owner.take(8))
                return@forEach
            }
            val result = try {
                execute(context, current.owner, JSONObject().put("action", "host_surface"))
            } finally {
                lock.unlock()
            }
            val payload = runCatching { JSONObject(result.content) }.getOrNull()
            val direct = payload?.optBoolean("direct") == true
            val info = payload?.optString("info").orEmpty()
            VirtualScreenHostSurfaces.setLive(current.owner, direct)
            VscrDiag.line(
                "直通 notify owner=" + current.owner.take(8) +
                    " direct=" + direct +
                    (if (info.isEmpty()) "" else " info=" + info) +
                    (if (payload?.optBoolean("ok") == true) ""
                    else " 原样=" + result.content.take(160))
            )
        }
    }

    /** 顶层 Activity 最近一次回传的方向诉求：true=横屏，false=竖屏，null=未知/跟随。 */
    @Volatile
    var lastLandscape: Boolean? = null
        private set

    /** 只探尺寸与前台包，不拉整帧：直通模式下小窗靠它跟上灵屏的横竖屏切换。 */
    fun refreshForViewer(context: Context, owner: String? = null): AgentModelClient.ToolResult {
        val target = chooseSession(owner) ?: return failure("NO_VIRTUAL_SCREEN")
        return tryOwnerLocked(target.owner, { busyResult() }) {
            execute(context, target.owner, JSONObject().put("action", "probe"))
        }
    }

    fun observeForViewer(context: Context, afterFrameId: Long = 0, owner: String? = null): AgentModelClient.ToolResult {
        val target = chooseSession(owner) ?: return failure("NO_VIRTUAL_SCREEN")
        return tryOwnerLocked(target.owner, { busyResult() }) {
            execute(context, target.owner, JSONObject().put("action", "observe").put("afterFrameId", afterFrameId))
        }
    }

    fun inputForViewer(context: Context, sessionId: String, args: JSONObject): AgentModelClient.ToolResult {
        val current = stateLock.withLock { sessions.values.firstOrNull { it.id == sessionId } }
        if (current == null || args.optString("action") !in setOf("tap", "swipe", "long_press", "back", "key", "switch_task", "text", "touch", "ime")) {
            return failure("STALE_VIRTUAL_SCREEN")
        }
        return tryOwnerLocked(current.owner, { busyResult() }) {
            // 直通拖动每秒上百个事件：只有按下和抬起值得记一条历史。
            val liveMove = args.optString("action") == "touch" && args.optString("phase") in setOf("down", "move")
            if (!liveMove) {
                current.viewer.update { state -> state.copy(display = state.display?.let {
                    it.copy(manualInputGeneration = it.manualInputGeneration + 1)
                }) }
                publish(current)
            }
            execute(context, current.owner, args, manual = true).also { result ->
                if (!liveMove) {
                    val name = if (args.optString("action") == "key") args.optString("button").lowercase() else args.optString("action")
                    recordOperation(current.owner, null, name, JSONObject(result.content).optBoolean("ok"), manual = true)
                }
            }
        }
    }

    fun tasksForViewer(context: Context, sessionId: String): AgentModelClient.ToolResult {
        val current = stateLock.withLock { sessions.values.firstOrNull { it.id == sessionId } }
            ?: return failure("STALE_VIRTUAL_SCREEN")
        return tryOwnerLocked(current.owner, { busyResult() }) {
            execute(context, current.owner, JSONObject().put("action", "tasks"))
        }
    }

    /** 手动在小窗里点开某个应用：只在 display 内启动，绝不把主屏任务迁进来。 */
    fun launchForViewer(context: Context, sessionId: String, component: String): AgentModelClient.ToolResult {
        val current = stateLock.withLock { sessions.values.firstOrNull { it.id == sessionId } }
            ?: return failure("STALE_VIRTUAL_SCREEN")
        return tryOwnerLocked(current.owner, { busyResult() }) {
            execute(context, current.owner, JSONObject().put("action", "launch").put("component", component)).also { result ->
                recordOperation(current.owner, null, "launch", JSONObject(result.content).optBoolean("ok"), manual = true)
            }
        }
    }

    /** 在 display 内打开 Eta 自带导航页；mode 用 HOME / MENU / APPS。 */
    fun homeForViewer(context: Context, sessionId: String, mode: String): AgentModelClient.ToolResult {
        val current = stateLock.withLock { sessions.values.firstOrNull { it.id == sessionId } }
            ?: return failure("STALE_VIRTUAL_SCREEN")
        val action = mode.takeIf { it in setOf("HOME", "MENU", "APPS") } ?: return failure("VIRTUAL_ACTION_UNSUPPORTED")
        return tryOwnerLocked(current.owner, { busyResult() }) {
            execute(context, current.owner, JSONObject().put("action", "key").put("button", action))
        }
    }

    /** 探一次 Root 进程并锁住这块屏，让调用方在同一把屏锁里做完一整套动作。 */
    fun withDisplay(
        context: Context,
        owner: String,
        isCancelled: () -> Boolean = { false },
        block: (VirtualDisplayInfo) -> AgentModelClient.ToolResult,
    ): AgentModelClient.ToolResult = ownerLock(owner).withLock {
        val probe = execute(context, owner, JSONObject().put("action", "probe"), isCancelled)
        if (!JSONObject(probe.content).optBoolean("ok")) return@withLock probe
        val info = stateLock.withLock { sessions[owner]?.viewer?.value?.display }
            ?: return@withLock failure("NO_VIRTUAL_SCREEN")
        block(info.copy(focusedPackage = JSONObject(probe.content).optString("packageName")))
    }

    fun showNodeGesture(info: VirtualDisplayInfo, action: String, x: Int, y: Int, durationMs: Int = 500, endX: Int = x, endY: Int = y) {
        val current = readLocked({ null }) { sessions.values.firstOrNull { it.id == info.sessionId } } ?: return
        publishGesture(current, JSONObject().put("action", action).put("x", x).put("y", y)
            .put("x1", x).put("y1", y).put("x2", endX).put("y2", endY).put("durationMs", durationMs))
    }

    private fun publishGesture(current: Session, args: JSONObject) {
        val action = args.optString("action")
        if (action !in setOf("tap", "long_press", "swipe")) return
        val x = args.optInt(if (action == "swipe") "x1" else "x", -1)
        val y = args.optInt(if (action == "swipe") "y1" else "y", -1)
        val endX = if (action == "swipe") args.optInt("x2", -1) else x
        val endY = if (action == "swipe") args.optInt("y2", -1) else y
        if (x !in 0 until current.width || y !in 0 until current.height || endX !in 0 until current.width || endY !in 0 until current.height) return
        current.viewer.update { state -> state.copy(gesture = VirtualScreenGesture(
            gestureIds.incrementAndGet(), current.id, action, x, y, endX, endY,
            args.optInt("durationMs", 500), SystemClock.elapsedRealtime(),
        )) }
        publish(current)
    }

    private fun chooseSession(owner: String? = null): Session? = stateLock.withLock {
        val target = if (owner != null) sessions[owner] else sessions[focusedOwner.orEmpty()]
        if (target == null) {
            // 指定 owner 找不到时仍回落到焦点的/最后一个屏（查看页只传 sessionId 之外的场景依赖它），
            // 但必须留痕：这是「操作落到另一块屏」的唯一可能来源，排查串台时先看这行。
            VscrDiag.line(
                "灵屏路由回落 请求=" + (owner?.take(8) ?: "焦点") +
                    " 焦点=" + (focusedOwner?.take(8) ?: "无") + " 屏数=" + sessions.size
            )
        }
        target ?: sessions.values.lastOrNull { !it.closed.get() }
    }

    private fun failure(code: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).toString(), sensitive = true,
    )

    /**
     * 关闭某个会话的屏。
     *
     * 早前这里是 `readLocked`：锁被占（别的屏正在读帧/建屏）就只写一行
     * 「关闭会话跳过：全局锁忙」直接返回 —— 调用方以为关掉了，屏还在，唤醒锁一并滞留到
     * 4 小时兜底才松，主屏跟着常亮耗电。
     * 现在不再「忙就跳过」：[Session.close] 的 CAS 保证幂等，关闭动作本身不需要等任何在途读取，
     * 只有唤醒锁要等表真的空了才放（见 [releaseWakeLockIfIdle]）。
     */
    fun closeOwner(owner: String) {
        val current = sessionOf(owner)
        if (current == null) {
            VscrDiag.line("关闭会话：无活屏 owner=" + owner.take(8))
        } else {
            current.close()
            VscrDiag.line("关闭会话 owner=" + owner.take(8) + " 剩余=" + sessionCount())
        }
        releaseWakeLockIfIdle("closeOwner")
    }

    /**
     * 唤醒锁只在「一块屏都不剩」时放开，并在放开/保留时各留一行可核对日志。
     *
     * 唤醒锁是按「还有屏在用」申请的：多开时任何一条关闭路径提前放开都会让屏熄掉，
     * 反过来漏放就是主屏常亮耗电。这里以表内实际剩余为准做自校验，不再依赖调用点各写一遍判断。
     */
    private fun releaseWakeLockIfIdle(reason: String) {
        val owners = stateLock.withLock { sessions.values.filter { !it.closed.get() }.map { it.owner } }
        if (owners.isEmpty()) {
            VirtualScreenWakeLock.release()
            VscrDiag.line("唤醒锁 released 原因=" + reason)
        } else {
            VscrDiag.line("唤醒锁保留 原因=" + reason + " 屏=" + owners.size)
        }
    }

    fun revokePermission() {
        val all = stateLock.withLock {
            val snapshot = sessions.values.toList()
            sessions.clear()
            focusedOwner = null
            viewerState.value = VirtualScreenViewerState()
            snapshot
        }
        all.forEach { it.close() }
        VirtualScreenWakeLock.release()
        VscrDiag.line("已撤销灵屏权限 关闭屏=" + all.size + " 唤醒锁 released")
    }

    /**
     * 读一行协议结果。
     *
     * helper 的 stdout 除了结果行还可能夹诊断输出，原来只扫 20 行就判
     * DISPLAY_OUTPUT_INVALID：一次偶发的多行日志就让整次调用失败（用户看到「灵屏操作未执行」）。
     * 现在放宽到 [MAX_RESPONSE_LINES] 行、容忍空行；超限时先把「读了多少行」写进诊断再报错，
     * 错误码字面量保持不变（catch 里按字面量匹配致命集合）。
     */
    private fun readResponse(output: BufferedReader): JSONObject {
        var seen = 0
        while (seen < MAX_RESPONSE_LINES) {
            val line = output.readLine() ?: error("ROOT_DISPLAY_DISCONNECTED")
            seen++
            if (line.isNotBlank()) require(line.length <= 2_800_000) { "DISPLAY_OUTPUT_TOO_LARGE" }
            if (line.startsWith("ETA_DISPLAY_RESULT:")) return JSONObject(line.removePrefix("ETA_DISPLAY_RESULT:"))
        }
        VscrDiag.line("等待结果超行数 lines=$seen")
        error("DISPLAY_OUTPUT_INVALID")
    }

    private fun bounded(args: JSONObject, name: String, min: Int, max: Int, default: Int): Int {
        if (!args.has(name)) return default
        val value = args.get(name)
        require(
            value is Number && value.toDouble() == value.toInt()
                .toDouble() && value.toInt() in min..max
        ) { "INVALID_DISPLAY_DIMENSIONS" }
        return value.toInt()
    }
}
