package io.github.mangi.eta.agent.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重任务互斥闸门的「只归还一次」语义。
 *
 * 早先 `releaseIfStale()` 在持锁超时时自己 `slot.release()`，而原持有者的 finally
 * 还会再 release 一次 → permits 变 2，此后两条重命令能并发进入（2026-11-26 审核）。
 */
class HeavyCommandGateTest {

    private val heavy = "gradlew assembleDebug"

    @Test
    fun lightCommandsNeverOccupyTheSlot() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = CountDownLatch(1)

        val a = Thread {
            HeavyCommandGate.guarded("ls -la", waitMs = 200) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        a.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // 轻命令不受闸门约束：重命令还应能立刻进入。
        val b = Thread {
            HeavyCommandGate.guarded(heavy, waitMs = 2_000) { second.countDown() }
        }
        b.start()
        assertTrue("轻命令不该占着槽位", second.await(5, TimeUnit.SECONDS))
        release.countDown()
        a.join(5_000)
        b.join(5_000)
    }

    @Test
    fun heavyCommandsAreSerialized() {
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val done = CountDownLatch(2)
        val body: () -> Unit = {
            val now = inside.incrementAndGet()
            maxInside.updateAndGet { maxOf(it, now) }
            Thread.sleep(120)
            inside.decrementAndGet()
            done.countDown()
        }
        val t1 = Thread { HeavyCommandGate.guarded(heavy, waitMs = 10_000) { body() } }
        val t2 = Thread { HeavyCommandGate.guarded(heavy, waitMs = 10_000) { body() } }
        t1.start()
        t2.start()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        t1.join(5_000)
        t2.join(5_000)
        assertEquals("同一时间只能有一个重命令在跑", 1, maxInside.get())
    }

    @Test
    fun exceptionStillReturnsTheSlot() {
        val failed = runCatching {
            HeavyCommandGate.guarded(heavy, waitMs = 5_000) { error("boom") }
        }
        assertTrue(failed.isFailure)

        // 槽位必须已经归还：紧接着的重命令要能立刻进入。
        val entered = CountDownLatch(1)
        val t = Thread {
            HeavyCommandGate.guarded(heavy, waitMs = 5_000) { entered.countDown() }
        }
        t.start()
        assertTrue("异常后槽位必须归还", entered.await(5, TimeUnit.SECONDS))
        t.join(5_000)
    }

    @Test
    fun repeatedRunsKeepExactlyOnePermit() {
        // 反复进出不能把 permits 攒起来：攒到 2 就说明有重复归还。
        repeat(5) {
            HeavyCommandGate.guarded(heavy, waitMs = 5_000) { Thread.sleep(5) }
        }
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val done = CountDownLatch(2)
        val body: () -> Unit = {
            maxInside.updateAndGet { maxOf(it, inside.incrementAndGet()) }
            Thread.sleep(80)
            inside.decrementAndGet()
            done.countDown()
        }
        val t1 = Thread { HeavyCommandGate.guarded(heavy, waitMs = 10_000) { body() } }
        val t2 = Thread { HeavyCommandGate.guarded(heavy, waitMs = 10_000) { body() } }
        t1.start()
        t2.start()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        t1.join(5_000)
        t2.join(5_000)
        assertEquals("重复进出后仍必须互斥", 1, maxInside.get())
    }

    @Test
    fun isHeavyRecognizesBuildCommands() {
        listOf("gradlew assembleDebug", "kotlinc Foo.kt", "tar -xzf a.tgz", "npm install").forEach {
            assertTrue(it, HeavyCommandGate.isHeavy(it))
        }
        // 注意：`cat build.gradle.kts` 会因子串 gradle 命中，这是既有行为（宁可多排队也不误放并发），
        // 本测试不覆盖它，避免把「粗糙匹配」当成规格钉住。
        listOf("ls -la", "cat README.md", "grep -r foo .").forEach {
            assertFalse(it, HeavyCommandGate.isHeavy(it))
        }
    }
}
