package io.github.mangi.eta.agent.phone

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 时钟业务接口。
 *
 * 本机时钟是小米 MIUIDeskClock（包名 com.android.deskclock，17.42.0）。反编译
 * `com.android.deskclock.AlarmProvider` 并真机实测后确认三条通道与两个坑：
 *
 * 通道（root 子进程实测全部可用）：
 * - 查询：`content://com.android.deskclock/alarm`（dir），走 SQLiteQueryBuilder 查 alarms 表
 * - 新建：同 dir 上 insert，走 AlarmDatabaseHelper.commonInsert
 * - 改/删：必须用 `content://com.android.deskclock/alarm/<id>`（item）
 *
 * 坑 1（数据损坏级）：Provider 的 `update` 在 dir 分支写死
 * `db.update("alarms", values, null, null)`，**完全忽略 selection**。实测在 /alarm 上带
 * `_id=4` 更新，把另一条 `_id=3` 的 minutes 一起改了。所以改必须走 item URI。
 * 坑 2：Provider 的 insert/update 都不计算 `alarmtime`/`skiptime`（只有 voiceassist
 * 分支算），需按 Provider 内部同一套规则补齐，否则单次闹钟按旧时刻调度。
 *
 * 写完后发 `miui.intent.action.ALARM_CHANGED` 给 AlarmInitReceiver，触发
 * AlarmHelper.setNextAlert 重排 AlarmManager（真机实测能取消旧调度）。
 *
 * 所有写操作都写后回读校验，未命中返回 verified=false，不谎报成功。
 */
internal class ClockOperations(
    private val resolver: PhoneProviderAccess,
    private val userId: Int,
    private val onMutation: () -> Unit = {},
    private val context: Context? = null,
    private val reschedule: () -> Boolean = { broadcastReschedule(context) },
) {
    /** 本次写操作后调度是否已确认重排；false 表示写入成功但需用户打开时钟确认。 */
    private var lastRescheduled = false

    fun execute(tool: String, args: JSONObject): JSONObject =
        when (tool) {
            "list_alarms" -> list(args)
            "set_alarm" -> create(args)
            "update_alarm_time",
            "set_alarm_enabled",
            "delete_alarm" -> change(tool, args)
            else -> PhoneOperation.failure("UNKNOWN_TOOL", "未知时钟操作")
        }

    private fun list(args: JSONObject): JSONObject {
        val query = PhoneOperation.text(args, "query", false, 200).orEmpty()
        val limit = PhoneOperation.integer(args, "limit", 1, 50, 20).toInt()
        val offset = PhoneOperation.integer(args, "offset", 0, 10000, 0).toInt()
        val enabledOnly = PhoneOperation.boolean(args, "enabled_only", true)
        val hour = if (args.has("hour")) PhoneOperation.integer(args, "hour", 0, 23).toInt() else null
        val minute = if (args.has("minute")) PhoneOperation.integer(args, "minute", 0, 59).toInt() else null
        val matches =
            alarms().filter {
                (!enabledOnly || it.getBoolean("enabled")) &&
                    (hour == null || it.getInt("hour") == hour) &&
                    (minute == null || it.getInt("minute") == minute) &&
                    (query.isBlank() || it.optString("label").contains(query, true))
            }.sortedBy { it.getLong("alarm_id") }
        val page = matches.drop(offset).take(limit)
        return PhoneOperation.ok("list_alarms")
            .put("items", JSONArray(page))
            .put("count", matches.size)
            .put("has_more", matches.size > offset + page.size)
            .put("next_offset", if (matches.size > offset + page.size) offset + page.size else JSONObject.NULL)
    }

    /** 读取闹钟表；列清单对齐真机 alarms 表（PRAGMA table_info 实测 13 列）。 */
    private fun alarms(): List<JSONObject> {
        val columns =
            arrayOf("_id", "hour", "minutes", "daysofweek", "alarmtime", "enabled", "message", "vibrate", "skiptime")
        val cursor =
            resolver.query(DIR_URI, columns, null, null, "_id ASC")
                ?: PhoneOperation.error("CLOCK_UNAVAILABLE", "时钟未返回闹钟数据")
        return cursor.use { c ->
            val ids = c.getColumnIndexOrThrow("_id")
            val hours = c.getColumnIndexOrThrow("hour")
            val minutes = c.getColumnIndexOrThrow("minutes")
            val days = c.getColumnIndexOrThrow("daysofweek")
            val times = c.getColumnIndex("alarmtime")
            val enabled = c.getColumnIndexOrThrow("enabled")
            val message = c.getColumnIndex("message")
            val vibrate = c.getColumnIndex("vibrate")
            buildList {
                while (c.moveToNext()) {
                    val mask = c.getInt(days)
                    add(
                        JSONObject()
                            .put("alarm_id", c.getLong(ids))
                            .put("hour", c.getInt(hours))
                            .put("minute", c.getInt(minutes))
                            .put("repeat_mask", mask)
                            .put("repeat_days", daysOf(mask))
                            .put("alarm_time", if (times >= 0 && !c.isNull(times)) c.getLong(times) else 0L)
                            .put("enabled", c.getInt(enabled) != 0)
                            .put("label", if (message >= 0 && !c.isNull(message)) c.getString(message) else "")
                            .put("vibrate", vibrate >= 0 && !c.isNull(vibrate) && c.getInt(vibrate) != 0),
                    )
                }
            }
        }
    }

    /**
     * 新建闹钟：Provider insert（真机实测可用），不走 Intent。
     * Intent 是异步的、要拉起时钟进程、还可能被忽略 SKIP_UI 而弹界面；直写能精确回读。
     */
    private fun create(args: JSONObject): JSONObject {
        val hour = PhoneOperation.integer(args, "hour", 0, 23).toInt()
        val minute = PhoneOperation.integer(args, "minute", 0, 59).toInt()
        val mask = repeatMask(args)
        val label = PhoneOperation.text(args, "label", false, 100)
        val vibrate = PhoneOperation.boolean(args, "vibrate", true)

        val values =
            ContentValues().apply {
                put("hour", hour)
                put("minutes", minute)
                put("daysofweek", mask)
                put("enabled", 1)
                put("vibrate", if (vibrate) 1 else 0)
                put("message", label.orEmpty())
                put("deleteAfterUse", 0)
                applySchedule(this, mask, enabled = true, hour = hour, minute = minute)
            }
        val created =
            mutate { resolver.insert(DIR_URI, values) }
                ?: PhoneOperation.error("CLOCK_UNAVAILABLE", "时钟未返回新闹钟记录")
        val id =
            created.lastPathSegment?.toLongOrNull()
                ?: PhoneOperation.error("CLOCK_UNAVAILABLE", "时钟未返回新闹钟 ID")
        val after = awaitAlarm { it.getLong("alarm_id") == id }
        val verified =
            after != null &&
                after.optInt("hour") == hour &&
                after.optInt("minute") == minute &&
                after.optInt("repeat_mask") == mask &&
                after.optBoolean("enabled")
        return PhoneOperation.changed("set_alarm", verified)
            .put("alarm_id", id)
            .put("alarm", after ?: JSONObject.NULL)
            .put("rescheduled", lastRescheduled)
    }

    /** 改时 / 开关 / 删除：item URI 直写后回读确认。 */
    private fun change(tool: String, args: JSONObject): JSONObject {
        val id = PhoneOperation.id(args, "alarm_id")
        val before =
            alarms().firstOrNull { it.getLong("alarm_id") == id }
                ?: PhoneOperation.error("ALARM_NOT_FOUND", "未找到指定闹钟")
        val itemUri = ContentUris.withAppendedId(DIR_URI, id)
        var expectedHour = before.optInt("hour")
        var expectedMinute = before.optInt("minute")
        var expectedEnabled = before.optBoolean("enabled")
        when (tool) {
            "update_alarm_time" -> {
                expectedHour = PhoneOperation.integer(args, "hour", 0, 23).toInt()
                expectedMinute = PhoneOperation.integer(args, "minute", 0, 59).toInt()
                val mask = before.optInt("repeat_mask")
                val values =
                    ContentValues().apply {
                        put("hour", expectedHour)
                        put("minutes", expectedMinute)
                        applySchedule(this, mask, expectedEnabled, expectedHour, expectedMinute)
                    }
                mutate { resolver.update(itemUri, values, null, null) }
            }
            "set_alarm_enabled" -> {
                expectedEnabled = PhoneOperation.boolean(args, "enabled")
                val mask = before.optInt("repeat_mask")
                val values =
                    ContentValues().apply {
                        put("enabled", if (expectedEnabled) 1 else 0)
                        applySchedule(this, mask, expectedEnabled, expectedHour, expectedMinute)
                    }
                mutate { resolver.update(itemUri, values, null, null) }
            }
            "delete_alarm" -> mutate { resolver.delete(itemUri, null, null) }
        }
        val after = awaitAlarm { it.getLong("alarm_id") == id }
        val verified =
            when (tool) {
                "delete_alarm" -> after == null
                "update_alarm_time" ->
                    after != null &&
                        after.optInt("hour") == expectedHour &&
                        after.optInt("minute") == expectedMinute
                else -> after != null && after.optBoolean("enabled") == expectedEnabled
            }
        return PhoneOperation.changed(tool, verified)
            .put("alarm_id", id)
            .put("before", before)
            .put("alarm", after ?: JSONObject.NULL)
            .put("rescheduled", lastRescheduled)
    }

    private fun <T> mutate(block: () -> T?): T? {
        onMutation()
        val result =
            runCatching(block)
                .getOrElse {
                    PhoneOperation.error(
                        "CLOCK_UNAVAILABLE",
                        "时钟写入失败：${it.message ?: it::class.java.simpleName}",
                    )
                }
                ?: PhoneOperation.error("CLOCK_UNAVAILABLE", "时钟未返回写入结果")
        lastRescheduled = reschedule()
        return result
    }

    /** 轮询回读，等时钟落库；最多约 2 秒。 */
    private fun awaitAlarm(predicate: (JSONObject) -> Boolean): JSONObject? {
        repeat(POLL_ATTEMPTS) { attempt ->
            val hit = runCatching { alarms().firstOrNull(predicate) }.getOrNull()
            if (hit != null) return hit
            if (attempt < POLL_ATTEMPTS - 1) Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * 按 Provider 内部同一套规则补齐 alarmtime / skiptime。
     *
     * 反编译自 AlarmProvider 的 voiceassist 分支，四态：
     * - 无重复 + 启用：skiptime=0，alarmtime=下一次触发时刻
     * - 有重复 + 启用：alarmtime=0，skiptime=0（按星期循环）
     * - 有重复 + 停用：alarmtime=0，skiptime=下一次触发时刻
     * - 无重复 + 停用：alarmtime=0，skiptime=0
     */
    private fun applySchedule(values: ContentValues, mask: Int, enabled: Boolean, hour: Int, minute: Int) {
        val schedule = AlarmSchedule.schedule(mask, enabled, hour, minute, System.currentTimeMillis())
        values.put("alarmtime", schedule.alarmTime)
        values.put("skiptime", schedule.skipTime)
    }

    /** repeat_days 转 alarms.daysofweek 位掩码，编码与校验都在 [AlarmSchedule]。 */
    private fun repeatMask(args: JSONObject): Int {
        val days =
            args.optJSONArray("repeat_days")
                ?: if (args.has("repeat_days")) {
                    PhoneOperation.error("INVALID_ARGUMENT", "repeat_days 必须是数组")
                } else {
                    JSONArray()
                }
        return try {
            AlarmSchedule.maskOf(days)
        } catch (failure: IllegalArgumentException) {
            PhoneOperation.error("INVALID_ARGUMENT", failure.message ?: "重复星期无效")
        }
    }

    private fun daysOf(mask: Int): JSONArray = JSONArray(AlarmSchedule.daysOf(mask))

    private companion object {
        val DIR_URI: Uri = Uri.parse("content://com.android.deskclock/alarm")
        const val ALARM_CHANGED_ACTION = "miui.intent.action.ALARM_CHANGED"
        const val CLOCK_PACKAGE = "com.android.deskclock"
        const val CLOCK_RECEIVER = "com.android.deskclock.AlarmInitReceiver"
        const val POLL_ATTEMPTS = 8
        const val POLL_INTERVAL_MS = 250L

        /**
         * 让时钟重排 AlarmManager。Provider 写库不动调度，只有 AlarmInitReceiver 收到
         * miui.intent.action.ALARM_CHANGED 才调 AlarmHelper.setNextAlert（真机实测有效）。
         * 该 receiver 未导出，只有 root/system 身份能显式投递；普通身份失败不影响写入本身。
         */
        fun broadcastReschedule(context: Context?): Boolean {
            // root 子进程（PhoneCommandMain）里先用 am 投递：receiver 未导出，只有
            // root/system 身份能显式发给它，这是真机实测唯一稳定可用的路径。
            val viaAm =
                runCatching {
                    val process =
                        ProcessBuilder(
                            "/system/bin/sh",
                            "-c",
                            "am broadcast -a $ALARM_CHANGED_ACTION -n $CLOCK_PACKAGE/$CLOCK_RECEIVER",
                        ).redirectErrorStream(true).start()
                    val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (!finished) process.destroyForcibly()
                    finished && process.exitValue() == 0
                }.getOrDefault(false)
            if (viaAm) return true
            // 应用身份兜底：未导出 receiver 通常会被拒绝，失败如实返回 false。
            if (context == null) return false
            return runCatching {
                context.sendBroadcast(
                    Intent(ALARM_CHANGED_ACTION)
                        .setClassName(CLOCK_PACKAGE, CLOCK_RECEIVER),
                )
                true
            }.getOrDefault(false)
        }
    }
}
