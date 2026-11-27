package io.github.mangi.eta.agent.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闹钟写后回读判定 + 原始 ID 解析。
 *
 * 纯 JVM 测试：这两条逻辑都不碰 Android API，可以在 arm64 本机上真正跑起来
 * （依赖 Robolectric 的用例在 aarch64 会被跳过，等于没验证）。
 */
class AlarmVerificationTest {

    private fun found(hour: Int, minute: Int, enabled: Boolean) =
        AlarmVerification.Lookup.Found(hour, minute, enabled)

    @Test
    fun deleteIsConfirmedOnlyWhenLookupSucceededAndRowIsGone() {
        assertTrue(
            AlarmVerification.verified("delete_alarm", AlarmVerification.Lookup.Gone, 0, 0, false),
        )
    }

    @Test
    fun deleteIsNotConfirmedWhenQueryFailed() {
        // 早先：查询异常被兜成 null → 报「已删除」。现在必须是「未确认」。
        assertFalse(
            AlarmVerification.verified("delete_alarm", AlarmVerification.Lookup.Unknown, 0, 0, false),
        )
    }

    @Test
    fun deleteIsNotConfirmedWhenRowStillExists() {
        assertFalse(
            AlarmVerification.verified("delete_alarm", found(8, 30, true), 0, 0, false),
        )
    }

    @Test
    fun timeEditRequiresMatchingHourAndMinute() {
        assertTrue(
            AlarmVerification.verified("update_alarm_time", found(9, 0, true), 9, 0, true),
        )
        assertFalse(
            AlarmVerification.verified("update_alarm_time", found(9, 5, true), 9, 0, true),
        )
        assertFalse(
            AlarmVerification.verified("update_alarm_time", AlarmVerification.Lookup.Unknown, 9, 0, true),
        )
    }

    @Test
    fun enableToggleRequiresMatchingFlag() {
        assertTrue(
            AlarmVerification.verified("set_alarm_enabled", found(8, 30, false), 8, 30, false),
        )
        assertFalse(
            AlarmVerification.verified("set_alarm_enabled", found(8, 30, true), 8, 30, false),
        )
        assertFalse(
            AlarmVerification.verified("set_alarm_enabled", AlarmVerification.Lookup.Unknown, 8, 30, false),
        )
    }

    @Test
    fun idAcceptsBothTextAndNumber() {
        assertEquals(7L, AlarmVerification.parseId("7"))
        assertEquals(7L, AlarmVerification.parseId(7))
        assertEquals(7L, AlarmVerification.parseId(7L))
        // JSON 数字常被解析成 Double：整值要接受，真小数要拒绝。
        assertEquals(7L, AlarmVerification.parseId(7.0))
        assertNull(AlarmVerification.parseId(7.5))
    }

    @Test
    fun idRejectsInvalidShapes() {
        assertNull(AlarmVerification.parseId(null))
        assertNull(AlarmVerification.parseId(""))
        assertNull(AlarmVerification.parseId("abc"))
        assertNull(AlarmVerification.parseId("0"))
        assertNull(AlarmVerification.parseId("-3"))
        assertNull(AlarmVerification.parseId("1 2"))
        assertNull(AlarmVerification.parseId(0))
        assertNull(AlarmVerification.parseId(true))
        // 超长数字串不许被截断成错误 id。
        assertNull(AlarmVerification.parseId("12345678901234567890"))
    }
}
