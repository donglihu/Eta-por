package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯 JVM 用例：不依赖 Android，也就不会被 arm64 上的 Robolectric 假设静默跳过。
 */
class RunFinishTitleTest {
    @Test
    fun sessionTitleWinsOverCardAndLive() {
        assertEquals(
            "会话标题",
            RunFinishTitle.resolve(sessionTitle = "会话标题", cardTitle = "卡片标题", liveTitle = "运行表标题"),
        )
    }

    @Test
    fun fallsBackToCardThenLiveThenEmpty() {
        assertEquals("卡片标题", RunFinishTitle.resolve("", "卡片标题", "运行表标题"))
        assertEquals("运行表标题", RunFinishTitle.resolve("  ", "", "运行表标题"))
        assertEquals("", RunFinishTitle.resolve("", "", ""))
    }
}
