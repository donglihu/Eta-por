package io.github.mangi.eta.core

import io.github.mangi.eta.data.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 巡检「错过周期补跑」的判定用例（纯 JVM）。
 *
 * 背景：巡检闹钟只有「跑完重排」这一条自续链路，进程被杀或闹钟被清后没有补偿，
 * 曾经连着 4 个周期点没触发。修复方式是在启动 / 回前台时按 [DiagnosticsPatrol.shouldCatchUp]
 * 判断是否错过周期并补跑一次——这里验证该判定的边界。
 */
class DiagnosticsPatrolCatchUpTest {

    private val dayMs = 24 * 60 * 60 * 1000L

    @Test
    fun disabledOrMissingSettingsNeverCatchUp() {
        val now = 1_000_000L
        assertFalse(DiagnosticsPatrol.shouldCatchUp(null, now))
        assertFalse(
            DiagnosticsPatrol.shouldCatchUp(
                Settings(diagPatrolEnabled = false, diagPatrolLastAt = 0L),
                now,
            ),
        )
    }

    @Test
    fun enabledWithoutLastAtCountsAsDue() {
        val now = 1_000_000L
        assertTrue(
            DiagnosticsPatrol.shouldCatchUp(
                Settings(diagPatrolEnabled = true, diagPatrolLastAt = 0L),
                now,
            ),
        )
    }

    @Test
    fun withinIntervalSkipsExactlyAtBoundaryRuns() {
        val lastAt = 10 * dayMs
        val intervalMinutes = 1440 // 一天
        val settings = Settings(
            diagPatrolEnabled = true,
            diagPatrolIntervalMinutes = intervalMinutes,
            diagPatrolLastAt = lastAt,
        )
        // 差 1ms 满一个周期：不补。
        assertFalse(DiagnosticsPatrol.shouldCatchUp(settings, lastAt + dayMs - 1))
        // 恰好满一个周期：补（与 refresh() 的到期口径一致）。
        assertTrue(DiagnosticsPatrol.shouldCatchUp(settings, lastAt + dayMs))
        // 超过一个周期（巡检报告里「间隔 3 天 15 小时」的场景）：补。
        assertTrue(DiagnosticsPatrol.shouldCatchUp(settings, lastAt + 3 * dayMs + 15 * 60 * 60 * 1000L))
    }

    @Test
    fun intervalBelowMinimumIsNormalizedBeforeComparing() {
        val lastAt = 10 * 60 * 1000L
        val settings = Settings(
            diagPatrolEnabled = true,
            diagPatrolIntervalMinutes = 1, // 低于 5 分钟下限，会被归一化
            diagPatrolLastAt = lastAt,
        )
        val minIntervalMs = DiagnosticsPatrolInterval.millis(1) // 5 分钟
        assertEquals(5 * 60 * 1000L, minIntervalMs)
        // 距上次不到归一化后的最短周期：不补。
        assertFalse(DiagnosticsPatrol.shouldCatchUp(settings, lastAt + minIntervalMs - 1))
        // 满归一化后的最短周期：补。
        assertTrue(DiagnosticsPatrol.shouldCatchUp(settings, lastAt + minIntervalMs))
    }
}
