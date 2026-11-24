package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.ToolDomain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 资源域闸门的纯 JVM 单测（不用 Robolectric）。
 *
 * [io.github.mangi.eta.core.EtaTrace.log] 在没有 appContext 时直接 return，所以打点调用在单测里是安全的；
 * `org.json` 是真实测试依赖，不必桩化。
 */
class AgentResourceGateTest {

    private fun ticket(
        toolName: String,
        args: JSONObject = JSONObject(),
        conversationKey: String = "conv",
    ) = AgentResourceGate.ticketOf(toolName, args, conversationKey)

    /**
     * ① 全表快照：登记表里的每个工具都必须**显式声明**资源域。
     *
     * 这里刻意不判 `DEVICE:fallback`——已登记工具的域由 `declaredDomain()` 兜底，永远不会落 fallback，
     * 断言 fallback 恒真等于零防线。真正的漏填方向是「静默降级成 NONE」，所以直接做集合差：
     * `toolNames` 里任一工具不在 `declaredDomainNames` 中，就是有人忘了声明。
     */
    @Test
    fun everyRegisteredToolHasAnExplicitDomainDeclaration() {
        val declared = AgentToolRequirements.declaredDomainNames
        val missing = (AgentToolRequirements.toolNames - declared).sorted()
        assertEquals(
            "这些工具没有显式声明资源域（漏填会被静默降级成 NONE、绕开域闸门）: $missing",
            emptyList<String>(),
            missing,
        )
    }

    /** 分类口径逐条钉住：口径改了必须同步改这条断言，否则并发语义会悄悄变。 */
    @Test
    fun domainClassificationMatchesSpecification() {
        assertEquals(ToolDomain.SCREEN, AgentToolRequirements.domainOf("tap"))
        assertEquals(ToolDomain.SCREEN, AgentToolRequirements.domainOf("observe_screen"))
        assertEquals(ToolDomain.SCREEN, AgentToolRequirements.domainOf("press_key"))
        assertEquals(ToolDomain.SCREEN, AgentToolRequirements.domainOf("virtual_screen"))
        assertEquals(ToolDomain.SHELL, AgentToolRequirements.domainOf("terminal"))
        assertEquals(ToolDomain.FS_WRITE, AgentToolRequirements.domainOf("write_file"))
        assertEquals(ToolDomain.FS_WRITE, AgentToolRequirements.domainOf("skills_manage"))
        assertEquals(ToolDomain.DEVICE, AgentToolRequirements.domainOf("set_alarm"))
        assertEquals(ToolDomain.DEVICE, AgentToolRequirements.domainOf("create_calendar_event"))
        assertEquals(ToolDomain.DEVICE, AgentToolRequirements.domainOf("set_brightness"))
        assertEquals(ToolDomain.DEVICE, AgentToolRequirements.domainOf("memory_write"))
        assertEquals(ToolDomain.BROWSER, AgentToolRequirements.domainOf("browser_use"))
        // 有写副作用的工具不许落 NONE：落 NONE 等于退回「无串行」。
        // 子代理模板写工作区 agents/ → 文件写域；专家表与任务表是整表读改写 → 设备域。
        assertEquals(ToolDomain.FS_WRITE, AgentToolRequirements.domainOf("subagent_profile"))
        assertEquals(ToolDomain.DEVICE, AgentToolRequirements.domainOf("expert_manage"))
        assertEquals(ToolDomain.DEVICE, AgentToolRequirements.domainOf("tasks_create"))
        assertEquals(ToolDomain.NONE, AgentToolRequirements.domainOf("tasks_list"))
        // MCP 远端工具不进登记表，只按工具名前缀判定。
        assertEquals(ToolDomain.MCP, AgentToolRequirements.domainOf("mcp_srv_tool_deadbeef"))
        // 纯读取/检索/派发类一律不进闸门。
        assertEquals(ToolDomain.NONE, AgentToolRequirements.domainOf("web_search"))
        assertEquals(ToolDomain.NONE, AgentToolRequirements.domainOf("memory_get"))
        assertEquals(ToolDomain.NONE, AgentToolRequirements.domainOf("terminal_job"))
        assertEquals(ToolDomain.NONE, AgentToolRequirements.domainOf("subagent"))
        assertNull(AgentToolRequirements.domainOf("not_a_tool"))
    }

    /** NONE 域不进闸门：键为 null，[AgentResourceGate.run] 直接执行 block。 */
    @Test
    fun noneDomainBypassesGate() {
        val computed = ticket("web_search")
        assertEquals(ToolDomain.NONE, computed.domain)
        assertNull(computed.key)
        assertEquals("ok", AgentResourceGate.run(computed, "web_search", "conv") { "ok" })
    }

    /** ② 同一目录的五种写法必须落到同一个写入槽位（否则同目录两次写会并行）。 */
    @Test
    fun pathAliasesShareOneWriteSlot() {
        val aliases = listOf("/a/b", "/a/b/", "/a/./b", "/a/x/../b", "/a//b")
        val keys = aliases.map { path ->
            AgentResourceGate.ticketOf("write_file", JSONObject().put("path", path), "conv").key
        }
        assertEquals("五种别名的键不一致: $keys", 1, keys.toSet().size)
        assertEquals("FS_WRITE:/a", keys.first())
    }

    /** 无路径的写入（skills_manage 等）改的是共享技能库：按全局串行，最保守。 */
    @Test
    fun writeWithoutPathFallsBackToGlobalSlot() {
        assertEquals("FS_WRITE:workspace", AgentResourceGate.ticketOf("skills_manage", JSONObject(), "conv").key)
    }

    /**
     * 屏幕槽按会话拆分：注入「该会话有活跃灵屏」的判定后，两个各自持有灵屏的会话不许挤同一个槽。
     *
     * 这是灵屏多开的核心收益；默认实现（全部 main）是保守口径，不能反过来当成断言。
     */
    @Test
    fun screenSlotSplitsByConversationWhenInjected() {
        val original = AgentResourceGate.screenSlotOf
        try {
            AgentResourceGate.screenSlotOf = { conv ->
                if (conv == "conv-a" || conv == "conv-b") "ls:$conv" else "main"
            }
            val a = AgentResourceGate.ticketOf("tap", JSONObject(), "conv-a").key
            val b = AgentResourceGate.ticketOf("tap", JSONObject(), "conv-b").key
            val c = AgentResourceGate.ticketOf("tap", JSONObject(), "conv-c").key
            assertEquals("SCREEN:ls:conv-a", a)
            assertEquals("SCREEN:ls:conv-b", b)
            assertEquals("SCREEN:main", c)
            // 反向对照：默认实现（未注入活跃判定）必须共用 main —— 「默认不分槽」是保守口径，
            // 不能反过来被当成收益；两条一起才锁住「分槽来自注入，而不是键本来就不同」。
            AgentResourceGate.screenSlotOf = { "main" }
            val d1 = AgentResourceGate.ticketOf("tap", JSONObject(), "conv-a").key
            val d2 = AgentResourceGate.ticketOf("tap", JSONObject(), "conv-b").key
            assertEquals("SCREEN:main", d1)
            assertEquals("默认实现下两个会话共用一个槽", d1, d2)
        } finally {
            AgentResourceGate.screenSlotOf = original
        }
    }

    /** ③ 同线程重复申请同一个域会自锁（Semaphore 不可重入），必须显式拒绝。 */
    @Test
    fun sameThreadReentryIsRejected() {
        val computed = ticket("terminal", conversationKey = "conv-reentrant")
        var caught: AgentResourceGate.GateReentrant? = null
        AgentResourceGate.run(computed, "terminal", "conv-reentrant") {
            try {
                AgentResourceGate.run(computed, "terminal", "conv-reentrant") {
                    fail("重入不应该进入内层 block")
                }
            } catch (error: AgentResourceGate.GateReentrant) {
                caught = error
            }
        }
        assertNotNull("同线程重入没有抛 GateReentrant", caught)
        assertEquals("SHELL:conv-reentrant/shell", caught!!.slotKey)
        // 外层 finally 要摘掉线程标记：同一线程再申请一次必须还能拿到。
        assertEquals("ok", AgentResourceGate.run(computed, "terminal", "conv-reentrant") { "ok" })
    }

    /** ④ 超时抛 GateTimeout，且 finally 把槽位还回去（下一个调用能拿到）。 */
    @Test
    fun timeoutReleasesSlotForTheNextCaller() {
        val computed = ticket("terminal", conversationKey = "conv-timeout")
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            AgentResourceGate.run(computed, "terminal", "conv-timeout") {
                holding.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue("持有线程没有进入 block", holding.await(5, TimeUnit.SECONDS))

        var timedOut: AgentResourceGate.GateTimeout? = null
        try {
            AgentResourceGate.run(computed, "terminal", "conv-timeout", waitMs = 200) {
                fail("槽位被占用时不应该拿到")
            }
        } catch (error: AgentResourceGate.GateTimeout) {
            timedOut = error
        }
        val timeout = requireNotNull(timedOut) { "等锁超时没有抛 GateTimeout" }
        assertEquals(computed.key, timeout.slotKey)
        assertNotNull("超时消息里应该带上持有者", timeout.holder)

        release.countDown()
        holder.join(5_000)
        // 持有者释放后（超时那次没有拿到锁，绝不能 release），下一次必须立刻可执行。
        assertEquals("ok", AgentResourceGate.run(computed, "terminal", "conv-timeout", waitMs = 2_000) { "ok" })
    }

    /** ⑤ 排队期间被取消抛 GateCancelled，且不占着槽位。 */
    @Test
    fun cancellationDuringWaitThrowsGateCancelled() {
        val computed = ticket("terminal", conversationKey = "conv-cancel")
        var cancelled: AgentResourceGate.GateCancelled? = null
        try {
            AgentResourceGate.run(computed, "terminal", "conv-cancel", isCancelled = { true }) {
                fail("已取消时不应该执行")
            }
        } catch (error: AgentResourceGate.GateCancelled) {
            cancelled = error
        }
        assertNotNull("取消没有抛 GateCancelled", cancelled)
        assertEquals(computed.key, cancelled!!.slotKey)
        assertEquals("ok", AgentResourceGate.run(computed, "terminal", "conv-cancel", waitMs = 1_000) { "ok" })
    }

    /**
     * ⑥ 两个线程互斥 + 等锁打点。
     *
     * 断言等待时长 >= [AgentResourceGate.WARN_WAIT_MS]：这正是 `perf.gate.wait` 的打点门槛，
     * 也就是「采得到等锁样本」的可验证版本（单测里 EtaTrace 没有 appContext，写不出日志文件）。
     */
    @Test
    fun secondCallerWaitsPastWarningThreshold() {
        val computed = ticket("terminal", conversationKey = "conv-mutex")
        val holding = CountDownLatch(1)
        val holder = Thread {
            AgentResourceGate.run(computed, "terminal", "conv-mutex") {
                holding.countDown()
                Thread.sleep(300)
            }
        }
        holder.start()
        assertTrue("持有线程没有进入 block", holding.await(5, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        val result = AgentResourceGate.run(computed, "terminal", "conv-mutex", waitMs = 5_000) { "second" }
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
        holder.join(5_000)

        assertEquals("second", result)
        assertTrue(
            "等待 ${waitedMs}ms 没到打点阈值 ${AgentResourceGate.WARN_WAIT_MS}ms，并发没有真正互斥",
            waitedMs >= AgentResourceGate.WARN_WAIT_MS,
        )
    }

    /** ⑦ 总开关关掉时退回旧全局锁：不构造票、不占域槽位。 */
    @Test
    fun disabledFallsBackToLegacyGate() {
        val originalGate = AgentResourceGate.legacyGate
        val originalEnabled = AgentResourceGate.enabled
        val seenTool = AtomicReference<String?>(null)
        try {
            AgentResourceGate.enabled = false
            AgentResourceGate.legacyGate = object : AgentResourceGate.LegacyGate {
                override fun <T> withGate(toolName: String, block: () -> T): T {
                    seenTool.set(toolName)
                    return block()
                }
            }
            val result = AgentResourceGate.dispatch(
                toolName = "write_file",
                args = JSONObject().put("path", "/a/b"),
                conversationKey = "conv",
            ) { "ok" }
            assertEquals("ok", result)
            assertEquals("write_file", seenTool.get())
            assertEquals("关闭闸门后不应该占用域槽位", 0, AgentResourceGate.queuedCount("FS_WRITE:/a"))
        } finally {
            AgentResourceGate.enabled = originalEnabled
            AgentResourceGate.legacyGate = originalGate
        }
    }
}