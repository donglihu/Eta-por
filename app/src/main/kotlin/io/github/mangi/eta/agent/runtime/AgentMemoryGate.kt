package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.core.MemoryPressure
import java.io.File

/**
 * 多开会话的内存闸门（系统剩余内存绝对值为主，进程堆水位为辅）＋ 弹性会话上限。
 *
 * 不用系统可用内存的占用百分比：Android 把可回收缓存也算进可用内存，百分比既虚又抖，小内存机上
 * 还可能比绝对值触发得更晚，留着只会让人误以为它在起作用。
 *
 * 堆占用比例是上面那条规则的例外：堆是本进程自己的，不含任何可回收缓存，比例直接反映「再申请一次
 * 就贴顶」，所以它值得作为独立判据参与抬档——但只抬档、不降档，系统侧仍只认 MemAvailable 绝对值。
 *
 * 闸门分工（系统侧绝对值部分保持不变）：
 * - 剩余 <1200MB：新会话、新任务进排队队列；回落到阈值以上就放行；运行中的继续跑完
 * - 剩余 <800MB：队列停止放行，不再接收新任务入队，已在执行的继续跑完
 * - 剩余 <500MB：冻结 Agent，强制保存会话现场（只冲刷已有缓冲，不重建快照），停止一切新增请求
 *
 * 补充判据来自 [io.github.mangi.eta.core.MemoryPressure]：全项目的水位阈值只写在那边，这里不另立
 * 一套。`MemoryPressure.level()` 为 Critical（堆 ≥82% 或系统可用 ≤512MB）时至少抬到 [TIER_QUEUE]，
 * 并把会话上限收敛到 [MIN_SESSION_LIMIT]；Low / Normal 不动档位，避免正常波动误伤并发。档位取
 * 「系统侧基础档」与「抬档后档」中更严重的一个（maxOf），因此堆水位永远不会把档位降下来。
 *
 * 弹性上限只回答"最多能同时开几个轻会话"，闸门回答"现在要不要收"；顺序固定：先闸门、后上限。
 * 读不到内存数据时按正常放行 + 默认上限，不因统计失败把所有任务卡死。
 */
internal object AgentMemoryGate {

    const val TIER_NORMAL = 0
    const val TIER_QUEUE = 1
    const val TIER_LOCKDOWN = 2
    const val TIER_FREEZE = 3

    const val QUEUE_AVAIL_MB = 1200L
    const val LOCKDOWN_AVAIL_MB = 800L
    const val FREEZE_AVAIL_MB = 500L

    /** 系统预留：扣掉它以后剩下的才算能分给会话的内存。 */
    const val SESSION_RESERVE_MB = 1500L

    /** 单会话基础预算：含模型响应缓冲、历史与工具输出。 */
    const val SESSION_BUDGET_MB = 500L

    const val MIN_SESSION_LIMIT = 1
    const val MAX_SESSION_LIMIT = 6

    /** 内存读数拿不到时的默认上限，取中间值，既不放飞也不卡死。 */
    const val DEFAULT_SESSION_LIMIT = 3

    /** 可用内存（MB）：取 /proc/meminfo 的 MemAvailable（与系统判定一致），读不到返回 -1。 */
    fun availableMb(): Long = runCatching {
        val lines = File("/proc/meminfo").readLines()
        fun read(key: String): Long? = lines
            .firstOrNull { line -> line.startsWith(key) }
            ?.filter { char -> char.isDigit() }
            ?.toLongOrNull()
        val kb = read("MemAvailable:") ?: read("MemFree:")
        if (kb == null) -1L else kb / 1024L
    }.getOrDefault(-1L)

    /**
     * 档位 = maxOf(系统侧基础档, 堆水位抬档后的档)。
     *
     * 系统侧基础档仍只认 MemAvailable 绝对值；堆水位只在 [MemoryPressure.Level.Critical] 时抬到
     * [TIER_QUEUE]，不直接抬 LOCKDOWN / FREEZE——堆高只说明本进程没有余量，不等于系统要饿死，
     * 直接冻结属于过度阻塞。取 maxOf 保证抬档只升不降。
     */
    fun tier(): Int {
        val available = availableMb()
        val baseTier = when {
            available in 0..FREEZE_AVAIL_MB -> TIER_FREEZE
            available in 0..LOCKDOWN_AVAIL_MB -> TIER_LOCKDOWN
            available in 0..QUEUE_AVAIL_MB -> TIER_QUEUE
            else -> TIER_NORMAL
        }
        return maxOf(baseTier, heapTier())
    }

    /**
     * 堆水位对应的最低档位。Low 与 Normal 都返回 [TIER_NORMAL]，即不参与抬档：正常波动不该误伤并发。
     */
    private fun heapTier(): Int = when (MemoryPressure.level()) {
        MemoryPressure.Level.Critical -> TIER_QUEUE
        MemoryPressure.Level.Low -> TIER_NORMAL
        MemoryPressure.Level.Normal -> TIER_NORMAL
    }

    /**
     * 弹性会话上限 = clamp(floor((可用内存 - 1500MB) / 500MB), 1, 6)。
     * 只在新建会话与会话结束两个节点调用，运行中不重算，避免内存抖动把会话搅乱。
     */
    fun sessionLimit(): Int {
        // 堆水位 Critical 时把上限收敛到最小：此时再多开一个会话就是往已贴顶的堆里塞对象。
        if (MemoryPressure.level() == MemoryPressure.Level.Critical) return MIN_SESSION_LIMIT
        val available = availableMb()
        if (available < 0) return DEFAULT_SESSION_LIMIT
        val raw = (available - SESSION_RESERVE_MB) / SESSION_BUDGET_MB
        return raw.coerceIn(MIN_SESSION_LIMIT.toLong(), MAX_SESSION_LIMIT.toLong()).toInt()
    }

    fun availableMb(context: Context): Long = availableMb()

    fun tier(context: Context): Int = tier()

    /** 一行摘要，写进自留日志，事后能看出是哪一档先触发。 */
    fun describe(): String =
        "剩余=${availableMb()}MB tier=${tier()} 上限=${sessionLimit()} 堆=${MemoryPressure.describe()}"

    fun describe(context: Context): String = describe()
}
