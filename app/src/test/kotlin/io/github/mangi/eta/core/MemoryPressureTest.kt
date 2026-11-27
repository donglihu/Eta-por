package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 用例：内存水位判据必须可断言。
 *
 * 为什么只测 [MemoryPressure.classify] 与 [MemoryPressure.admits]：真实水位要读运行时的
 * `/proc/meminfo` 与 ART 堆，在 arm64 的 Robolectric 上跑不了（native runtime 没有
 * linux-aarch64 产物，整类会被静默跳过）。把判定做成纯函数后，这些边界能被真实执行到，
 * 不再依赖「跑一次真机看看」。
 */
class MemoryPressureTest {

    private fun snapshot(usedMb: Long, maxMb: Long, availableMb: Long) = MemoryPressure.Snapshot(
        heapUsedBytes = usedMb * 1024 * 1024,
        heapMaxBytes = maxMb * 1024 * 1024,
        availableBytes = availableMb * 1024 * 1024,
    )

    @Test
    fun normalWhenHeapAndSystemComfortable() {
        assertEquals(MemoryPressure.Level.Normal, MemoryPressure.classify(snapshot(90, 512, 4096)))
    }

    @Test
    fun lowWhenHeapCrossesSixtyEightPercent() {
        assertEquals(MemoryPressure.Level.Low, MemoryPressure.classify(snapshot(350, 512, 4096)))
    }

    @Test
    fun criticalWhenHeapCrossesEightyTwoPercent() {
        // 511/512 正是 2026-11-25 崩溃前的实测水位：贴顶后连 16 字节分配都失败。
        assertEquals(MemoryPressure.Level.Critical, MemoryPressure.classify(snapshot(511, 512, 4096)))
    }

    @Test
    fun systemAvailableCanLiftLevelWithoutHeapPressure() {
        // 堆只用了 10%，但整机只剩 300MB：这时候再叠一次整帧解码就是压死骆驼的最后一根稻草。
        assertEquals(MemoryPressure.Level.Critical, MemoryPressure.classify(snapshot(50, 512, 300)))
        assertEquals(MemoryPressure.Level.Low, MemoryPressure.classify(snapshot(50, 512, 900)))
    }

    @Test
    fun unknownAvailableDoesNotForceCritical() {
        // 读不到 MemAvailable 时给 -1：不能被当成「系统几乎没内存」而误降档。
        assertEquals(MemoryPressure.Level.Normal, MemoryPressure.classify(snapshot(90, 512, -1)))
    }

    @Test
    fun zeroMaxHeapFallsBackToNormal() {
        // 极端兜底：拿不到 maxMemory 时不做任何降档，避免把设备永久锁在低档。
        assertEquals(MemoryPressure.Level.Normal, MemoryPressure.classify(MemoryPressure.Snapshot(1, 0, 0)))
    }

    // ---- 准入（灵屏开新屏那道硬门槛）----

    private val screenBudget = 1200L * 1024 * 1024

    @Test
    fun admissionAllowsWhenComfortable() {
        assertTrue(MemoryPressure.admits(snapshot(90, 512, 4096), screenBudget))
    }

    @Test
    fun admissionRejectsWhenBudgetExceeded() {
        // 整机只剩 1.1GB，但每块屏常驻约 80MB 且拉帧要瞬时几十 MB：宁可让调用方明确失败。
        assertFalse(MemoryPressure.admits(snapshot(90, 512, 1100), screenBudget))
    }

    @Test
    fun admissionRejectsAtCriticalHeapEvenWithPlentySystemMemory() {
        // 堆贴顶就是 2026-11-25 那三次 OOM 的现场：系统内存再多也拦不住 ART 里的分配失败。
        assertFalse(MemoryPressure.admits(snapshot(500, 512, 4096), screenBudget))
    }

    @Test
    fun admissionKeepsBudgetCheckWhenAvailableUnknown() {
        // 读不到 MemAvailable 时退化成只看堆水位：不凭空放行、也不凭空拦。
        assertTrue(MemoryPressure.admits(snapshot(90, 512, -1), screenBudget))
        assertFalse(MemoryPressure.admits(snapshot(500, 512, -1), screenBudget))
    }
}
