package io.github.mangi.eta.agent.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 用例：保活的两条判定语义。
 *
 * 这两条以前内联在 Service 生命周期里，改错只会表现成「保活开着但过一会儿就没了」，
 * 既难复现也难回滚。抽成纯函数后语义可以被锁住。
 */
class KeepalivePolicyTest {

    @Test
    fun stickyRebuildNeverSelfStops() {
        // intent 为 null（START_STICKY 重建）+ 没常驻 + 没会话：仍然不能自停，否则重建即退出。
        assertFalse(KeepalivePolicy.shouldStopAfterCommand(false, persistent = false, sessionCount = 0))
    }

    @Test
    fun explicitStopSessionStopsOnlyWhenNotPersistent() {
        assertTrue(KeepalivePolicy.shouldStopAfterCommand(true, persistent = false, sessionCount = 0))
        assertFalse(KeepalivePolicy.shouldStopAfterCommand(true, persistent = true, sessionCount = 0))
        assertFalse(KeepalivePolicy.shouldStopAfterCommand(true, persistent = false, sessionCount = 1))
    }

    @Test
    fun taskRemovedStopsOnlyWhenKeepaliveDisabled() {
        // 用户显式开了常驻：划掉任务也继续活着。
        assertFalse(KeepalivePolicy.shouldStopOnTaskRemoved(true))
        // 没开常驻：保持原语义，划掉就是明确要它停。
        assertTrue(KeepalivePolicy.shouldStopOnTaskRemoved(false))
    }
}