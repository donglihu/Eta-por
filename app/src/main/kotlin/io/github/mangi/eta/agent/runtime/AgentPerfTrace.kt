package io.github.mangi.eta.agent.runtime

import android.os.SystemClock

/**
 * 计时工具。
 *
 * 2026-11-22 引入的 `perf.*` 诊断打点已于 2026-11-23 关闭：
 * [trace] 为空实现，调用点保持原样；[at] / [since] / [elapsedNow] 保留，
 * 供仍需要对齐同一时间口径的调用方使用（它们只读时钟，不写日志）。
 */
internal object AgentPerfTrace {

    /** 进程纪元：进程重启后重新对齐，不拿上一进程的时间硬减。 */
    val epochMillis: Long = System.currentTimeMillis()
    private val epochElapsed: Long = SystemClock.elapsedRealtime()

    /** 进程启动以来的毫秒数（墙钟口径，跨线程可比较）。 */
    fun at(): Long = epochMillis + (SystemClock.elapsedRealtime() - epochElapsed)

    /** 诊断打点已关闭：空实现。 */
    @Suppress("UNUSED_PARAMETER")
    fun trace(stage: String, detail: String = "") = Unit

    /** 只问「截止到现在隔了多久」。 */
    fun since(markElapsed: Long): Long = SystemClock.elapsedRealtime() - markElapsed

    fun elapsedNow(): Long = SystemClock.elapsedRealtime()
}