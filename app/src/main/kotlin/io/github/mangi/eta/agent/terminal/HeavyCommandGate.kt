package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.runtime.AgentMemoryGate
import io.github.mangi.eta.core.EtaTrace
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 重任务（编译 / 构建 / 解包 / 长命令）互斥闸门。
 *
 * 多开会话同时跑编译是内存峰值的最大来源，光靠内存阈值来得太晚；这里保证：
 * 1. 重命令同一时间只跑一个；
 * 2. 内存紧张（剩余 <1200MB）时不让重命令开跑，避免在峰值上再叠一个峰值；
 * 3. 一开始等不到槽位就**当场**给出"已排队"提示，而不是拖到超时才报错；
 * 4. 槽位用信号量管理，并配一个「只释放一次」的持有者票据：正常结束、异常中断都在
 *    finally 里归还；线程被杀、或持锁超过 10 分钟时，残留自检代为归还。
 *
 * 第 4 条的「只释放一次」是硬要求：早先自检里直接 `slot.release()`，而原持有者结束时
 * 还会在 finally 里再 release 一次，一次超时就把 permits 变成 2，此后两条重命令能并发
 * 进入——正是这道闸门要避免的 OOM（2026-11-26 审核）。
 *
 * 轻命令（ls、cat、grep 之类）完全不受影响。
 */
internal object HeavyCommandGate {

    /** 工具侧等待上限：模型发起的重命令可以排队等，但不无限等。 */
    const val TOOL_WAIT_MS = 120_000L

    /** 用户交互终端只等一小会：等太久会把界面卡住，不如直接告诉他在编译。 */
    const val CONSOLE_WAIT_MS = 5_000L

    private const val POLL_MS = 500L

    /** 持锁超过这个时长就视为残留（正常编译不会跑 10 分钟不释放）。 */
    private const val STALE_HOLD_MS = 10 * 60 * 1000L

    const val TIMEOUT_SLOT_MESSAGE =
        "已有重任务（编译／构建）在运行，本条未执行，等它结束后再试。"

    const val TIMEOUT_MEMORY_MESSAGE =
        "内存紧张，重任务（编译／构建）本轮未执行，等内存回落后再试。"

    private val heavyPatterns = listOf(
        "gradle", "assemble", "javac", "kotlinc", "apktool", "aapt", "aapt2", "d8 ", "r8 ",
        "cmake", "ninja", "make ", " make", "cargo", "npm ", "yarn ", "pnpm", "pip install",
        "python -m", "tar -x", "tar x", "unzip", "zip -r", "7z ", "dd ", "rsync", "git clone",
        "baksmali", "smali", "dex2jar", "jadx", "gcc ", "g++ ", "clang",
    )

    /**
     * 一次成功的占用。
     *
     * [released] 保证「占用 → 归还」严格一一对应：无论是 finally 归还还是残留自检代还，
     * 第二次调用一定是空操作，permits 不会被放大。
     */
    private class Holder(val thread: Thread, val acquiredAt: Long) {
        private val released = AtomicBoolean(false)

        fun releaseOnce(): Boolean {
            if (!released.compareAndSet(false, true)) return false
            slot.release()
            return true
        }

        fun heldTooLong(now: Long): Boolean = now - acquiredAt > STALE_HOLD_MS
    }

    /** 用信号量而不是可重入锁：残留线程已死时，才能由别人代为归还。 */
    private val slot = Semaphore(1, true)

    @Volatile
    private var holder: Holder? = null

    fun isHeavy(command: String): Boolean {
        val text = command.lowercase()
        return heavyPatterns.any { pattern -> text.contains(pattern) }
    }

    /**
     * 残留自检：持有者线程已死、或持锁超过 10 分钟，就代为归还这一次占用的槽位。
     *
     * 只有「确实还没归还过」才会 release（见 [Holder.releaseOnce]），所以原持有者随后
     * 走到 finally 时不会造成第二次归还。
     */
    private fun releaseIfStale() {
        val current = holder ?: return
        val ownerGone = !current.thread.isAlive
        val tooLong = current.heldTooLong(System.currentTimeMillis())
        if (!ownerGone && !tooLong) return
        if (holder !== current) return
        holder = null
        val released = current.releaseOnce()
        EtaTrace.log(
            "heavy.stale-release",
            "检测到重任务槽位残留（owner=${current.thread.name} 已死=$ownerGone 超时=$tooLong），" +
                "已代为归还=$released",
        )
    }

    fun <T> guarded(
        command: String,
        waitMs: Long = TOOL_WAIT_MS,
        onNotice: ((String) -> Unit)? = null,
        timeoutMessage: String = TIMEOUT_SLOT_MESSAGE,
        block: () -> T,
    ): T {
        if (!isHeavy(command)) return block()
        releaseIfStale()
        val deadline = System.currentTimeMillis() + waitMs
        var noticed = false
        var blockedByMemory = false
        while (true) {
            val tight = AgentMemoryGate.tier() >= AgentMemoryGate.TIER_QUEUE
            if (!tight && slot.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)) break
            blockedByMemory = tight
            if (!noticed) {
                noticed = true
                val notice = if (tight) {
                    "内存紧张（${AgentMemoryGate.describe()}），本条重任务已排队等待"
                } else {
                    "当前有编译在跑，本条已排队，结束后自动执行"
                }
                EtaTrace.log("heavy.queue", "$notice：${command.take(60)}")
                onNotice?.invoke(notice)
            }
            if (System.currentTimeMillis() >= deadline) {
                EtaTrace.log(
                    "heavy.reject",
                    "等待 ${waitMs / 1000}s 未获槽位（内存紧张=$blockedByMemory）：${command.take(60)}",
                )
                throw IllegalStateException(
                    if (blockedByMemory) TIMEOUT_MEMORY_MESSAGE else timeoutMessage,
                )
            }
            if (tight) Thread.sleep(POLL_MS)
        }
        val acquired = Holder(Thread.currentThread(), System.currentTimeMillis())
        holder = acquired
        EtaTrace.log("heavy.start", command.take(80))
        return try {
            block()
        } finally {
            // 正常结束和异常中断都走这里；[releaseOnce] 保证即使自检已经代还过，
            // 这里也不会再归还一次（槽位绝不留给自己以外的下一次调用，也绝不多还）。
            if (holder === acquired) holder = null
            acquired.releaseOnce()
            EtaTrace.log("heavy.end", command.take(80))
        }
    }
}
