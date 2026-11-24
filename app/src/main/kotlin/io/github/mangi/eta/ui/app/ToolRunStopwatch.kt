package io.github.mangi.eta.ui.app

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具执行计时的进程内基准表。
 *
 * 每次工具调用记两个起点：墙钟（可持久化、可跨进程比对）与单调钟（只在本进程有效，
 * 但不随校时跳变）。已用时间一律由单调钟差值算出；墙钟只在「起点来自落库」这条路径上
 * 用来判断纪元、换算偏移，不参与计时本体。
 *
 * 续算范围（如实）：进程内续算 —— 切会话、UI 重建、attachRun 重连都沿用同一份起点，
 * 不会从 0 重新计时；跨进程不续算 —— 进程被杀后本表清零、单调钟起点丢失，计时从 0
 * 重新开始。消息里落库的 `startedAtMillis` 只是给将来的 resume 预留，当前 UI 不消费它：
 * `toToolStatus()` 会把恢复出来的 Running 降级成 Unknown，而计时入口首行就是
 * `if (status != Running) return null`。
 *
 * 写入只发生在两处：投影器的 ToolStarted（一次写入墙钟 + 单调钟），以及渲染侧发现没有
 * 记录时的一次性注册。两处都走 putIfAbsent，重复调用不会把起点往后推。
 */
internal object ToolRunStopwatch {

    /**
     * 本进程创建时的墙钟时刻，作为「落库起点是否属于本进程」的纪元判据。
     *
     * 早于它的落库起点一律不采信：进程重启后 Runtime 的 run 已经断了，拿旧起点算
     * 「已用时」会得出一个越变越大的假秒数。
     */
    private val processStartMillis = System.currentTimeMillis()

    /** 计时纪元（墙钟）。单测用它构造「本进程内写入的落库起点」，不依赖真实运行时长。 */
    internal val processStartedAtMillis: Long get() = processStartMillis

    /** 一次工具调用的起点；墙钟用于纪元判断与偏移换算，单调钟用于算已用时。 */
    private data class Mark(val wallMillis: Long, val elapsedMillis: Long)

    private val marks = ConcurrentHashMap<String, Mark>()

    /**
     * 记录起点，一次写入两个时钟。
     *
     * [wallMillis] 为空或非正时取当前墙钟；[elapsedMillis] 由调用方给出（投影器用自己
     * 注入的单调钟，便于测试）。已记录过的 id 不会被覆盖。
     */
    fun mark(id: String, wallMillis: Long?, elapsedMillis: Long) {
        if (id.isBlank()) return
        val wall = wallMillis?.takeIf { it > 0L } ?: System.currentTimeMillis()
        marks.putIfAbsent(id, Mark(wall, elapsedMillis))
    }

    /**
     * 该次调用的进程内起点墙钟；没有记录（进程重启、归档恢复）时返回 null。
     *
     * 生产代码不读它：渲染侧一律用 [resolveElapsedStart] / [elapsedSince] 走单调钟，
     * 只有单测需要断言「落库起点有没有被采信」。
     */
    @VisibleForTesting
    fun wallSince(id: String): Long? = if (id.isBlank()) null else marks[id]?.wallMillis

    /**
     * 已用毫秒：单调钟差值。没有进程内起点（进程重启、归档恢复）时返回 null。
     *
     * 单调钟不受 NTP 校时影响，不会出现秒数跳变，也不会「卡在 0 不动」；
     * 差值统一 coerceAtLeast(0)，注入的测试时钟回拨也不会出现负值。
     */
    fun elapsedSince(id: String, nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Long? {
        val mark = marks[id] ?: return null
        return (nowElapsedMillis - mark.elapsedMillis).coerceAtLeast(0L)
    }

    /**
     * 渲染侧取单调起点：进程内已有记录直接沿用，否则补注册一次后返回。
     *
     * 补注册只接受「本进程启动之后写入的落库墙钟起点」（[processStartMillis] 纪元判据），
     * 并按墙钟差换算出对应的单调起点 —— 这是全表唯一允许用墙钟差的地方。换算结果截断在
     * [0, nowElapsed] 内：不回绕成负数，也不会因为墙钟跳变算出荒谬的已用时。
     * 落库值不可信（跨进程残留、时钟回拨）或缺失时，以当前单调钟为起点，从 0 重新开始。
     *
     * 返回单调钟毫秒，与 [elapsedSince] 同一时钟域，调用方不需要自己判断用哪个钟。
     */
    fun resolveElapsedStart(
        id: String,
        persistedWallMillis: Long?,
        nowWallMillis: Long = System.currentTimeMillis(),
        nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
    ): Long {
        marks[id]?.let { return it.elapsedMillis }
        val persisted = persistedWallMillis?.takeIf { it > 0L && it in processStartMillis..nowWallMillis }
        val elapsed = if (persisted != null) {
            (nowElapsedMillis - (nowWallMillis - persisted)).coerceIn(0L, nowElapsedMillis)
        } else {
            nowElapsedMillis
        }
        mark(id, persisted, elapsed)
        return marks[id]?.elapsedMillis ?: elapsed
    }

    fun clear(id: String) {
        if (id.isNotBlank()) marks.remove(id)
    }

    /**
     * 按 run 前缀批量清理该 run 名下所有调用的起点。
     *
     * run 被丢弃时（Runtime 进程死亡、会话删除）收不到 finish/fail/interrupt 事件，单条
     * [clear] 没有机会执行，它的工具条记录会一直留在表里跟着进程活下去。前缀带上分隔符
     * `"$runId-tool-"`，与工具 id 的拼法（`"$runId-tool-$round-$toolCallId"`）严格对齐：
     * runId 互为前缀的两个 run（`abc` 与 `abcd`）不会互相误删。
     *
     * 先取快照再逐个删除，不在迭代中改表；并发新增只可能来自仍在跑的同一 run，此时它
     * 的起点留着也是对的。
     */
    fun clearRun(runId: String) {
        if (runId.isBlank()) return
        val prefix = "$runId-tool-"
        marks.keys.filter { it.startsWith(prefix) }.forEach { marks.remove(it) }
    }
}
