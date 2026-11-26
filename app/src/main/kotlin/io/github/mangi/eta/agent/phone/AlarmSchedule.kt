package io.github.mangi.eta.agent.phone

import org.json.JSONArray

/**
 * 闹钟重复规则与调度时间的纯逻辑。
 *
 * 从 [ClockOperations] 抽出来是为了能在 arm64 上被纯 JVM 单测覆盖：Robolectric 在
 * linux-aarch64 没有 native runtime，凡是碰 ContentValues/Cursor 的用例都会被
 * AssumptionViolatedException 静默跳过（skipped 而非通过）。这里只依赖 org.json，
 * 不碰任何 Android 类型。
 *
 * 编码口径来自真机反编译 `com.android.deskclock.Alarm.DaysOfWeek`：
 * `DAY_MAP = {2,3,4,5,6,7,1}`，即 alarms.daysofweek 位掩码周一=1、周二=2 … 周日=64，
 * 另有 128=法定工作日、256=法定休息日、512=倒班（这三位不参与 repeat_days 解析）。
 */
internal object AlarmSchedule {
    const val MONDAY = 1
    const val TUESDAY = 2
    const val WEDNESDAY = 4
    const val THURSDAY = 8
    const val FRIDAY = 16
    const val SATURDAY = 32
    const val SUNDAY = 64

    /** 星期名 → 位掩码。 */
    val dayBits: Map<String, Int> =
        mapOf(
            "mon" to MONDAY,
            "tue" to TUESDAY,
            "wed" to WEDNESDAY,
            "thu" to THURSDAY,
            "fri" to FRIDAY,
            "sat" to SATURDAY,
            "sun" to SUNDAY,
        )

    /** repeat_days 数组转位掩码；非法值抛 [IllegalArgumentException]，由调用方转成业务错误码。 */
    fun maskOf(days: JSONArray?): Int {
        if (days == null) return 0
        require(days.length() <= 7) { "重复星期数量无效" }
        var mask = 0
        for (index in 0 until days.length()) {
            val name = days.optString(index)
            val bit = dayBits[name] ?: throw IllegalArgumentException("重复星期无效")
            mask = mask or bit
        }
        return mask
    }

    /** 位掩码转星期名数组，顺序固定为 mon…sun。 */
    fun daysOf(mask: Int): List<String> =
        dayBits.entries.filter { mask and it.value != 0 }.map { it.key }

    /**
     * 下一次触发时刻（严格晚于 [now]）。
     *
     * 单次闹钟必须自己算 alarmtime：真机 Provider 的 insert/update 都不算，
     * 只有 voiceassist 分支算，漏算会导致按旧时刻调度。
     */
    fun nextTriggerMillis(hour: Int, minute: Int, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val calendar =
            java.util.Calendar.getInstance(zone).apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        return calendar.timeInMillis
    }

    /**
     * 按真机 Provider voiceassist 分支的四态规则算 alarmtime / skiptime。
     *
     * | 有重复 | 启用 | alarmtime | skiptime |
     * | 否     | 是   | 下次触发  | 0        |
     * | 是     | 是   | 0         | 0        |
     * | 是     | 否   | 0         | 下次触发 |
     * | 否     | 否   | 0         | 0        |
     */
    fun schedule(
        mask: Int,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        now: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Schedule =
        when {
            mask == 0 && enabled ->
                Schedule(alarmTime = nextTriggerMillis(hour, minute, now, zone), skipTime = 0L)
            mask != 0 && enabled -> Schedule(alarmTime = 0L, skipTime = 0L)
            mask != 0 -> Schedule(alarmTime = 0L, skipTime = nextTriggerMillis(hour, minute, now, zone))
            else -> Schedule(alarmTime = 0L, skipTime = 0L)
        }

    data class Schedule(val alarmTime: Long, val skipTime: Long)
}
