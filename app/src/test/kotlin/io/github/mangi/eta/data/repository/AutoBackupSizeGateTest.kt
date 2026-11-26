package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体积闸门的行为约定。
 *
 * 这组用例守的是 2026-11-26 的闪退事故：恢复旧设置后 `include_linux=true`，
 * 自动备份在广播预算内去打包 16 GB chroot，被系统 ANR 杀掉后 `lastAt` 不推进，
 * 于是「启动 → 备份 → 被杀 → 再启动」无限循环。
 */
class AutoBackupSizeGateTest {

    private val limit = AutoBackupSizeGate.AUTOMATIC_FULL_LIMIT_BYTES

    @Test
    fun jsonOnlyRequestAlwaysPassesRegardlessOfSize() {
        val decision = AutoBackupSizeGate.decide(
            requestedFull = false,
            automatic = true,
            estimatedBytes = 64L * 1024L * 1024L * 1024L,
        )
        assertEquals(AutoBackupSizeGate.Decision.Full, decision)
    }

    @Test
    fun manualFullBackupIsNeverDowngraded() {
        // 用户点「立即备份一次」就是明确要连容器一起备份，体积再大也照做。
        val decision = AutoBackupSizeGate.decide(
            requestedFull = true,
            automatic = false,
            estimatedBytes = 64L * 1024L * 1024L * 1024L,
        )
        assertEquals(AutoBackupSizeGate.Decision.Full, decision)
    }

    @Test
    fun automaticFullBackupUnderLimitKeepsFull() {
        val decision = AutoBackupSizeGate.decide(
            requestedFull = true,
            automatic = true,
            estimatedBytes = limit - 1,
        )
        assertEquals(AutoBackupSizeGate.Decision.Full, decision)
    }

    @Test
    fun automaticFullBackupAtLimitKeepsFull() {
        // 边界取「含等号」：正好等于上限仍放行，避免边界抖动改变行为。
        val decision = AutoBackupSizeGate.decide(
            requestedFull = true,
            automatic = true,
            estimatedBytes = limit,
        )
        assertEquals(AutoBackupSizeGate.Decision.Full, decision)
    }

    @Test
    fun automaticFullBackupOverLimitDegradesToJson() {
        val decision = AutoBackupSizeGate.decide(
            requestedFull = true,
            automatic = true,
            estimatedBytes = limit + 1,
        )
        assertTrue(decision is AutoBackupSizeGate.Decision.DegradeToJson)
        val degraded = decision as AutoBackupSizeGate.Decision.DegradeToJson
        assertEquals(limit + 1, degraded.estimatedBytes)
        assertEquals(limit, degraded.limitBytes)
    }

    @Test
    fun realWorldLinuxContainerSizeDegrades() {
        // 本机实测 files/terminal = 16 GB，必须被判为超限。
        val decision = AutoBackupSizeGate.decide(
            requestedFull = true,
            automatic = true,
            estimatedBytes = 16L * 1024L * 1024L * 1024L,
        )
        assertTrue(decision is AutoBackupSizeGate.Decision.DegradeToJson)
    }

    @Test
    fun realWorldJsonBackupSizeKeepsFull() {
        // 本机 JSON 备份实测 25–58 MB，走全量包也不该被降级。
        val decision = AutoBackupSizeGate.decide(
            requestedFull = true,
            automatic = true,
            estimatedBytes = 58L * 1024L * 1024L,
        )
        assertEquals(AutoBackupSizeGate.Decision.Full, decision)
    }

    @Test
    fun unknownSizeIsTreatedAsAllowed() {
        // 估不出来（0）时放行：不能因为一次目录读失败就把用户的备份意图悄悄降级。
        val decision = AutoBackupSizeGate.decide(
            requestedFull = true,
            automatic = true,
            estimatedBytes = 0L,
        )
        assertEquals(AutoBackupSizeGate.Decision.Full, decision)
    }

    @Test
    fun limitIsTwoHundredFiftySixMegabytes() {
        // 阈值本身就是合同的一部分：改了要同步改这条用例，避免无声漂移。
        assertEquals(268_435_456L, limit)
    }
}
