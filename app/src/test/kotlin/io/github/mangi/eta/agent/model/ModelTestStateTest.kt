package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测速状态机：行内反馈的全部判断都在这里，所以不依赖 UI 也能验证。
 *
 * 对应 bug 现场（2026-11-20 反馈）：测速只弹一次全局弹窗、行上没有任何结果，
 * 用户既看不出哪个模型通过，也点不到失败原因。
 */
class ModelTestStateTest {

    private fun ok(latency: Long) = ModelTestState(
        running = false,
        result = ModelTestResult(success = true, latencyMs = latency),
    )

    private fun failed(detail: String = "HTTP 401") = ModelTestState(
        running = false,
        result = ModelTestResult(success = false, latencyMs = 0, errorDetail = detail),
    )

    @Test
    fun `running state reports neither success nor failure`() {
        val running = ModelTestState(running = true)
        assertNull(running.successLatencyMs)
        assertFalse(running.failed)
    }

    @Test
    fun `successful test exposes latency for the green label`() {
        assertEquals(120L, ok(120L).successLatencyMs)
        assertFalse(ok(120L).failed)
    }

    @Test
    fun `successful test without measured latency falls back to phase total`() {
        val state = ModelTestState(
            result = ModelTestResult(success = true, latencyMs = 0, timings = NetworkPhaseTimings(totalMs = 340L)),
        )
        assertEquals(340L, state.successLatencyMs)
    }

    @Test
    fun `failed test exposes no latency so no fake number is shown`() {
        val state = failed()
        assertNull(state.successLatencyMs)
        assertTrue(state.failed)
    }

    @Test
    fun `failed test keeps error detail for the tap-to-inspect dialog`() {
        assertEquals("HTTP 401", failed("HTTP 401").result?.errorDetail)
    }
}
