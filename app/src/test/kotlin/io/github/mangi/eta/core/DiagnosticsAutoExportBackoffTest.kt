package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动导出失败退避阶梯的纯 JVM 校验。
 *
 * 背景：`DiagnosticsAutoExport.nextDueAt` / `scheduleAfterRun` 都按
 * [DiagnosticsAutoExport.retryDelayMs] 决定下一次闹钟；Robolectric 在 arm64 上被
 * `AssumptionViolatedException` 静默跳过（skipped 不算覆盖），所以退避语义只在这里
 * 用不需要 Context 的纯 JVM 用例钉住：1 天起步、指数翻倍、封顶一个周期（7 天）。
 */
class DiagnosticsAutoExportBackoffTest {

    private val hourMs = 3_600_000L
    private val dayMs = 24 * hourMs
    private val weekMs = 7 * dayMs

    @Test
    fun firstFailureRetriesAfterOneDay() {
        assertEquals(dayMs, DiagnosticsAutoExport.retryDelayMs(1))
    }

    @Test
    fun backoffDoublesUpToWeeklyCap() {
        assertEquals(2 * dayMs, DiagnosticsAutoExport.retryDelayMs(2))
        assertEquals(4 * dayMs, DiagnosticsAutoExport.retryDelayMs(3))
        // 第 4 次起触顶：不再翻倍，最多就是「下次按周来」。
        assertEquals(weekMs, DiagnosticsAutoExport.retryDelayMs(4))
        assertEquals(weekMs, DiagnosticsAutoExport.retryDelayMs(100))
    }

    @Test
    fun nonPositiveFailuresStillGetMinimumDelay() {
        assertEquals(dayMs, DiagnosticsAutoExport.retryDelayMs(0))
        assertEquals(dayMs, DiagnosticsAutoExport.retryDelayMs(-3))
    }
}
