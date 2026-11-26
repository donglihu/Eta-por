package io.github.mangi.eta.agent.phone

import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 闹钟重复规则与调度时间的纯 JVM 用例。
 *
 * 刻意不用 Robolectric：arm64 上 Robolectric 会因缺 native runtime 抛
 * AssumptionViolatedException 被静默跳过，skipped≠0 就不能算通过。
 */
class AlarmScheduleTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun maskOfUsesMondayFirstBitLayout() {
        // 真机 alarms.daysofweek：周一=1、周三=4、周五=16，三天合起来是 21。
        assertEquals(21, AlarmSchedule.maskOf(JSONArray(listOf("mon", "wed", "fri"))))
        assertEquals(64, AlarmSchedule.maskOf(JSONArray(listOf("sun"))))
        assertEquals(127, AlarmSchedule.maskOf(JSONArray(listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun"))))
        assertEquals(0, AlarmSchedule.maskOf(JSONArray()))
        assertEquals(0, AlarmSchedule.maskOf(null))
    }

    @Test
    fun maskOfRejectsUnknownOrOversizedDays() {
        assertThrows(IllegalArgumentException::class.java) {
            AlarmSchedule.maskOf(JSONArray(listOf("mon", "funday")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AlarmSchedule.maskOf(JSONArray(listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun", "mon")))
        }
    }

    @Test
    fun daysOfRoundTripsMask() {
        assertEquals(listOf("mon", "wed", "fri"), AlarmSchedule.daysOf(21))
        assertEquals(listOf("sun"), AlarmSchedule.daysOf(64))
        assertEquals(emptyList<String>(), AlarmSchedule.daysOf(0))
        // 128/256/512 是法定工作日/休息日/倒班标志位，不属于星期位。
        assertEquals(emptyList<String>(), AlarmSchedule.daysOf(128))
        assertEquals(21, AlarmSchedule.maskOf(JSONArray(AlarmSchedule.daysOf(21))))
    }

    @Test
    fun nextTriggerRollsToTomorrowWhenTimeAlreadyPassed() {
        val now = millis(2026, 11, 26, 9, 0)
        val next = AlarmSchedule.nextTriggerMillis(7, 45, now, zone)
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = next }
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, calendar.get(Calendar.MONTH))
        assertEquals(27, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
        assertEquals(0, calendar.get(Calendar.MILLISECOND))
    }

    @Test
    fun nextTriggerKeepsSameDayWhenTimeStillAhead() {
        val now = millis(2026, 11, 26, 6, 0)
        val next = AlarmSchedule.nextTriggerMillis(7, 45, now, zone)
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = next }
        assertEquals(26, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, calendar.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun nextTriggerIsStrictlyInTheFutureWhenEqualToNow() {
        val now = millis(2026, 11, 26, 7, 45)
        val next = AlarmSchedule.nextTriggerMillis(7, 45, now, zone)
        // 恰好等于当前时刻必须滚到明天，否则会立刻响或永不触发。
        assertEquals(millis(2026, 11, 27, 7, 45), next)
    }

    @Test
    fun scheduleFollowsProviderFourStateRule() {
        val now = millis(2026, 11, 26, 9, 0)

        // 无重复 + 启用 → alarmtime 为下次触发，skiptime 归零。
        val single = AlarmSchedule.schedule(mask = 0, enabled = true, hour = 7, minute = 45, now = now, zone = zone)
        assertEquals(millis(2026, 11, 27, 7, 45), single.alarmTime)
        assertEquals(0L, single.skipTime)

        // 有重复 + 启用 → alarmtime 归零，交给星期循环。
        val repeating = AlarmSchedule.schedule(mask = 21, enabled = true, hour = 7, minute = 45, now = now, zone = zone)
        assertEquals(0L, repeating.alarmTime)
        assertEquals(0L, repeating.skipTime)

        // 有重复 + 停用 → skiptime 记住下次触发，便于重新启用时恢复。
        val repeatingOff = AlarmSchedule.schedule(mask = 21, enabled = false, hour = 7, minute = 45, now = now, zone = zone)
        assertEquals(0L, repeatingOff.alarmTime)
        assertEquals(millis(2026, 11, 27, 7, 45), repeatingOff.skipTime)

        // 无重复 + 停用 → 两者都归零。
        val singleOff = AlarmSchedule.schedule(mask = 0, enabled = false, hour = 7, minute = 45, now = now, zone = zone)
        assertEquals(0L, singleOff.alarmTime)
        assertEquals(0L, singleOff.skipTime)
    }
}
