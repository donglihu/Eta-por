package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁剪预算的 token 估算改成「不物化字符串」之后，结果必须和旧的 `toString()` 口径一致到可比区间。
 */
class AgentHistoryTrimmerTokenEstimateTest {

    @Test
    fun jsonEstimateMatchesStringEstimateWithinTolerance() {
        val message = JSONObject()
            .put("role", "tool")
            .put("tool_call_id", "call-1")
            .put("content", "{\"ok\":true,\"stdout\":\"" + "a".repeat(4_000) + "\"}")

        val viaString = AgentHistoryTrimmer.estimateTokens(message.toString())
        val viaJson = AgentHistoryTrimmer.estimateJsonTokens(message)

        assertTrue(viaJson > 0)
        val delta = kotlin.math.abs(viaJson - viaString)
        assertTrue("delta=$delta viaJson=$viaJson viaString=$viaString", delta <= viaString / 4)
    }

    @Test
    fun jsonEstimateWalksNestedArraysAndNulls() {
        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "中文内容")))
            .put("reasoning_content", JSONObject.NULL)

        val tokens = AgentHistoryTrimmer.estimateJsonTokens(message)

        assertTrue(tokens >= 4)
    }

    @Test
    fun emptyEstimateIsZero() {
        assertEquals(0, AgentHistoryTrimmer.estimateJsonTokens(null))
        assertEquals(0, AgentHistoryTrimmer.estimateJsonTokens(JSONObject.NULL))
        assertEquals(0, AgentHistoryTrimmer.estimateJsonTokens(JSONObject()))
    }

    /** 老轮次按预算丢弃的行为不变：工具结果与调用不被拆散。 */
    @Test
    fun trimInPlaceStillDropsOldestRoundsWhenOverBudget() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system"))
        for (round in 1..30) {
            messages.put(JSONObject().put("role", "user").put("content", "ask $round"))
            messages.put(
                JSONObject().put("role", "tool").put("content", "x".repeat(20_000)),
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", "current"))

        val outcome = AgentHistoryTrimmer.trimInPlace(
            messages = messages,
            systemCount = 1,
            contextWindow = 40_000,
        )

        assertTrue(outcome.omittedMessages > 0)
        assertEquals("system", messages.optJSONObject(0)?.optString("role"))
        assertEquals("current", messages.optJSONObject(messages.length() - 1)?.optString("content"))
    }
}
