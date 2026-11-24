package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSafetyTest {

    @Test
    fun safeStackTracePreservesLocationsWithoutMessagesOrSuppressedPayloads() {
        val cause = IllegalArgumentException("private-conversation-content")
        val failure = IllegalStateException("private-api-key", cause).apply {
            stackTrace = arrayOf(StackTraceElement("io.github.mangi.eta.Store", "save", "Store.kt", 42))
            addSuppressed(IllegalStateException("private-path"))
        }
        val rendered = failure.safeStackTrace()
        assertTrue(rendered.contains("Store.save(Store.kt:42)"))
        assertTrue(rendered.contains("IllegalArgumentException"))
        assertFalse(rendered.contains("private-"))
    }

    @Test
    fun safeStackTraceBoundsDeepStacksAndStopsCauseCycles() {
        val first = IllegalStateException("secret")
        val second = IllegalArgumentException("secret")
        first.initCause(second)
        second.initCause(first)
        first.stackTrace = Array(1000) { StackTraceElement("Class".repeat(100), "method", "File.kt", it) }
        val rendered = first.safeStackTrace()
        assertTrue(rendered.length < 20_000)
        assertTrue(rendered.contains("IllegalStateException"))
        assertFalse(rendered.contains("secret"))
    }

    @Test
    fun safeLogType_returnsTypeWithoutExceptionMessage() {
        val secret = "sensitive-runtime-message"

        val rendered = IllegalStateException(secret).safeLogType()

        assertEquals("IllegalStateException", rendered)
        assertFalse(rendered.contains(secret))
    }

    @Test
    fun toSafeLogToken_acceptsStableAsciiTokens() {
        assertEquals("tool.read_file-1", "tool.read_file-1".toSafeLogToken())
        assertEquals("RESULT_OK", "RESULT_OK".toSafeLogToken())
    }

    @Test
    fun toSafeLogToken_rejectsUntrustedOrHighCardinalityValues() {
        assertEquals("unknown", null.toSafeLogToken())
        assertEquals("unknown", "".toSafeLogToken())
        assertEquals("unknown", "tool\nforged-entry".toSafeLogToken())
        assertEquals("unknown", "包含用户内容".toSafeLogToken())
        assertEquals("unknown", "a".repeat(65).toSafeLogToken())
    }

    @Test
    fun safeLogType_replacesObfuscatedAppTypeNamesWithStableFallback() {
        // 线上实测：release 包里应用自有异常 simpleName 退化成 sp / tp。
        assertEquals(UNCLASSIFIED_LOG_TYPE, sp("private-message").safeLogType())
        assertEquals(UNCLASSIFIED_LOG_TYPE, object : IllegalStateException("private-message") {}.safeLogType())
        assertFalse(sp("private-message").safeLogType().contains("private-message"))
    }

    @Test
    fun safeLogType_prefersDeclaredTokenOverObfuscatedClassName() {
        // 类名是混淆短名 tp，声明 token 是 DeclaredModelFailure：两者不同，
        // 因此删掉 LogSafety 的 StableLogType 分支后本用例必然变红（反向验证用例）。
        val failure = tp("private-message")
        assertEquals("DeclaredModelFailure", failure.safeLogType())
        assertFalse(failure.safeLogType().contains("private-message"))
    }

    @Test
    fun safeLogType_rejectsUntrustedDeclaredToken() {
        // 声明了带空格的 token 不能污染日志行，退回兜底 token。
        assertEquals(UNCLASSIFIED_LOG_TYPE, jq("private-message").safeLogType())
    }

    @Test
    fun safeLogType_keepsFrameworkTypesReadable() {
        assertEquals("IllegalArgumentException", IllegalArgumentException("secret").safeLogType())
        assertEquals("SSLHandshakeException", javax.net.ssl.SSLHandshakeException("certificate").safeLogType())
        assertEquals("JSONException", org.json.JSONException("x").safeLogType())
        assertEquals("IOException", java.io.IOException("x").safeLogType())
    }

    @Test
    fun safeLogType_exposesDeclaredTokensForProductionExceptionTypes() {
        val modelFailure = io.github.mangi.eta.agent.model.AgentModelFailure("MODEL_TLS_FAILED", false, "m")
        assertTrue(modelFailure is StableLogType)
        assertEquals("AgentModelFailure", modelFailure.safeLogType())

        // 线上 `cause=tp` 的载体类型。
        val cancelled = io.github.mangi.eta.agent.runtime.AgentRunCancelledException()
        assertTrue(cancelled is StableLogType)
        assertEquals("AgentRunCancelledException", cancelled.safeLogType())

        // 线上 `type=sp` 的载体类型。
        val execution = io.github.mangi.eta.agent.model.AgentModelExecutionException(
            cause = javax.net.ssl.SSLException("certificate"),
            reasoningContent = "",
            transcript = emptyList(),
        )
        assertTrue(execution is StableLogType)
        assertEquals("AgentModelExecutionException", execution.safeLogType())
    }
}

/** 模拟 R8 混淆后的应用类名（1-3 位小写），未声明稳定 token。 */
private class sp(message: String) : RuntimeException(message)

/** 混淆类名 + 声明稳定 token（token 与类名刻意不同）。 */
private class tp(message: String) : RuntimeException(message), StableLogType {
    override val stableLogType: String get() = "DeclaredModelFailure"
}

/** 混淆类名 + 声明不可信 token（含空格）。 */
private class jq(message: String) : RuntimeException(message), StableLogType {
    override val stableLogType: String get() = "bad token with space"
}
