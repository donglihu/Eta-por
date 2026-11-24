package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `perf.tool.finished` 失败行的原因码提取。
 *
 * 纯 JVM 用例（不碰 Robolectric / android.os.SystemClock），arm64 上不会被静默跳过。
 * 线上缺陷：`perf.tool.finished round=1 name=subagent ms=1409304 ok=false images=0`
 * 跑了 23.5 分钟失败却没有原因码。
 */
class ToolFailureLogCodeTest {

    @Test
    fun readsCodeMarkerFromHumanizedFailureSummary() {
        val summary = "失败 · 目标页面不可达 · code=TOOL_TARGET_UNREACHABLE"
        assertEquals("TOOL_TARGET_UNREACHABLE", toolFailureLogCode(summary, success = false))
    }

    @Test
    fun readsCodeFromStructuredJsonSummary() {
        val summary = """{"ok":false,"code":"FILE_TOO_LARGE","path":"/sdcard/private"}"""
        assertEquals("FILE_TOO_LARGE", toolFailureLogCode(summary, success = false))
    }

    @Test
    fun readsCodeFromJsonErrorObject() {
        val summary = """{"ok":false,"error":{"code":"HTTP_502","message":"bad gateway"}}"""
        assertEquals("HTTP_502", toolFailureLogCode(summary, success = false))
    }

    @Test
    fun successNeverEmitsCodeField() {
        assertNull(toolFailureLogCode("ok=true · chars=1820", success = true))
        assertNull(toolFailureLogCode("""{"ok":true,"code":"IGNORED"}""", success = true))
    }

    @Test
    fun failureWithoutParseableCodeEmitsEmptyValue() {
        assertEquals("", toolFailureLogCode("失败 · 已停止", success = false))
        assertEquals("", toolFailureLogCode("", success = false))
        assertEquals("", toolFailureLogCode("不是 JSON 也不是摘要", success = false))
    }

    @Test
    fun dirtyCodeIsDistinguishedFromMissingCode() {
        // 取到了码但过不了字符白名单 → unsanitized；完全没取到 → 空。
        assertEquals("unsanitized", toolFailureLogCode("失败 · code=BAD VALUE", success = false))
        assertEquals("unsanitized", toolFailureLogCode("失败 · code=AB\nforged", success = false))
        assertEquals("", toolFailureLogCode("失败 · 没有码字段", success = false))
    }

    @Test
    fun neverLeaksSensitiveSummaryText() {
        val summary = "失败 · /data/user/0/io.github.mangi.eta/files/secret.txt 写入被拒 · code=EACCES"
        val code = toolFailureLogCode(summary, success = false)
        assertEquals("EACCES", code)
        assertFalse(code.orEmpty().contains("secret.txt"))
        assertFalse(code.orEmpty().contains("/data/user"))
    }

    @Test
    fun boundsLongCodeValues() {
        val longCode = "A".repeat(120)
        val code = toolFailureLogCode("失败 · code=$longCode", success = false).orEmpty()
        assertEquals(40, code.length)
        assertEquals("A".repeat(40), code)
    }

    @Test
    fun oversizedJsonSummarySkipsParsingWithoutThrowing() {
        val huge = """{"ok":false,"code":"HUGE","padding":"${"x".repeat(8_000)}"}"""
        assertEquals("", toolFailureLogCode(huge, success = false))
    }
}
