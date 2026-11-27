package io.github.mangi.eta.core

import java.io.File

/**
 * 进程与系统的统一内存水位判据。
 *
 * 为什么不再让各处自己定阈值：灵屏拉帧、图片跨进程传输、截图编码三处原本各判各的，
 * 口径不一致就会出现「一边认为还够、另一边已经贴顶」——2026-11-25 的三次 OOM 都是
 * `Failed to allocate a 16..24 byte allocation` 打在 512MiB 贴顶之后，属于「已经没余量
 * 还在继续申请」而不是单点大对象。判据集中在这里，调用方只按档位决定动作。
 *
 * [classify] 是纯函数，可以脱离 Android 环境做 JVM 断言；[snapshot] 才依赖运行时。
 */
internal object MemoryPressure {

    /**
     * 堆占用比例档位。
     *
     * 不等到 100% 才反应：一次整帧 JPEG 解码（1080×2400 的 RGB_565 约 5MiB）加一次
     * Base64 往返，瞬时就要再吃几十 MiB，所以留 18% 余量。
     */
    private const val CRITICAL_HEAP_RATIO = 0.82
    private const val LOW_HEAP_RATIO = 0.68

    /** 系统可用内存（MemAvailable）门槛；读不到（-1）时只看堆水位。 */
    private const val CRITICAL_AVAILABLE_BYTES = 512L * 1024 * 1024
    private const val LOW_AVAILABLE_BYTES = 1024L * 1024 * 1024

    enum class Level { Normal, Low, Critical }

    /**
     * @param availableBytes 系统 MemAvailable，读不到时给 -1，不参与判定。
     */
    data class Snapshot(
        val heapUsedBytes: Long,
        val heapMaxBytes: Long,
        val availableBytes: Long,
    ) {
        val usedRatio: Double
            get() = if (heapMaxBytes <= 0L) 0.0 else heapUsedBytes.toDouble() / heapMaxBytes.toDouble()
    }

    fun snapshot(): Snapshot {
        val runtime = Runtime.getRuntime()
        return Snapshot(
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            availableBytes = availableSystemBytes(),
        )
    }

    fun level(): Level = classify(snapshot())

    /** 纯函数：同样的输入永远给同样的档位，便于单测与复现。 */
    fun classify(value: Snapshot): Level {
        if (value.heapMaxBytes <= 0L) return Level.Normal
        val heap = value.usedRatio
        val available = value.availableBytes
        val heapCritical = heap >= CRITICAL_HEAP_RATIO
        val heapLow = heap >= LOW_HEAP_RATIO
        val systemCritical = available > 0L && available <= CRITICAL_AVAILABLE_BYTES
        val systemLow = available > 0L && available <= LOW_AVAILABLE_BYTES
        return when {
            heapCritical || systemCritical -> Level.Critical
            heapLow || systemLow -> Level.Low
            else -> Level.Normal
        }
    }

    /**
     * 额外占用 [bytes] 的准入判断：给「还能不能再开一块屏」这类硬门槛用。
     *
     * 两层口径叠一起，缺一层就会漏：
     * 1. 档位已经是 [Level.Critical] 直接拒 —— 这时任何新增分配都可能打在已贴顶的堆上
     *    （2026-11-25 三次 OOM 都是贴顶后连 16 字节分配都失败，不是单个大对象）；
     * 2. 预算不够 [bytes] 也拒。
     *
     * 系统水位读不到（-1）时只用堆水位判：不凭空放行，也不凭空拦。早前灵屏那边自己解析
     * `/proc/meminfo`，读失败还 `?: true` 双重 fail-open，等于「看不见就当够」。
     */
    fun admit(bytes: Long): Boolean = admits(snapshot(), bytes)

    /** 纯函数版准入，便于 JVM 断言。 */
    fun admits(value: Snapshot, bytes: Long): Boolean {
        if (classify(value) == Level.Critical) return false
        val available = value.availableBytes
        return available < 0L || available >= bytes
    }

    /**
     * 读 `/proc/meminfo` 的 MemAvailable。
     *
     * 失败返回 -1 而不是 0：0 会被判定成「系统几乎没内存」，从而把正常设备误降到 Critical。
     * 这条判据只做降档、不做唯一准入依据，所以失败只留痕。
     */
    fun availableSystemBytes(): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024L)
        }
    }.onFailure { error ->
        AndroidAgentLogger.warn("Memory pressure probe failed: type=${error.safeLogType()}")
    }.getOrNull() ?: -1L

    /** 供调用方做节流日志：只在档位变化时记一条，避免拉帧循环刷爆日志。 */
    fun describe(value: Snapshot = snapshot()): String {
        val available = if (value.availableBytes < 0L) {
            "unknown"
        } else {
            "${value.availableBytes / 1024 / 1024}MiB"
        }
        return "level=${classify(value)} heap=${value.heapUsedBytes / 1024 / 1024}/${value.heapMaxBytes / 1024 / 1024}MiB " +
            "available=$available"
    }
}
