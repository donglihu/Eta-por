package io.github.mangi.eta.agent.phone

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class NativeAppOperationsTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun calendarCreationUsesOneBatchAndVerifiesReminderBackReferences() {
        val provider = FakeProvider()
        val result =
            PhoneOperations.execute(
                context,
                "create_calendar_event",
                event().put("reminder_minutes", org.json.JSONArray(listOf(60, 1440))),
                access = provider,
            )
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals(1, provider.batches)
        assertEquals(3, provider.lastBatchSize)
        assertEquals("1", result.getString("event_id"))
        assertEquals(setOf("1"), provider.reminders.map { it.getAsString("event_id") }.toSet())
        assertEquals(setOf(60, 1440), provider.reminders.map { it.getAsInteger("minutes") }.toSet())
    }

    @Test
    fun calendarBatchBindsEachReminderToItsOwnEvent() {
        val provider = FakeProvider()
        val events =
            org.json
                .JSONArray()
                .put(event().put("reminder_minutes", org.json.JSONArray(listOf(10))))
                .put(
                    event()
                        .put("title", "另一会议")
                        .put("reminder_minutes", org.json.JSONArray(listOf(30)))
                )
        val result =
            PhoneOperations.execute(
                context,
                "create_calendar_events",
                JSONObject().put("events", events),
                access = provider,
            )
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals(1, provider.batches)
        assertEquals(2, provider.events.size)
        assertEquals(
            mapOf("1" to 10, "2" to 30),
            provider.reminders.associate {
                it.getAsString("event_id") to it.getAsInteger("minutes")
            },
        )
    }

    @Test
    fun invalidLaterEventCannotPartiallyCreateCalendarBatch() {
        val provider = FakeProvider()
        val events =
            org.json
                .JSONArray()
                .put(event())
                .put(event().put("end_time", "2026-10-04T10:00:00+08:00"))
        val result =
            PhoneOperations.execute(
                context,
                "create_calendar_events",
                JSONObject().put("events", events),
                access = provider,
            )
        assertEquals("INVALID_ARGUMENT", result.getString("code"))
        assertEquals(0, provider.batches)
        assertTrue(provider.events.isEmpty())
    }

    @Test
    fun calendarDoesNotGuessAccountOrAcceptReversedAndNonUtcAllDayRanges() {
        val multiple = FakeProvider().apply { calendarCount = 2 }
        val selection =
            PhoneOperations.execute(context, "create_calendar_event", event(), access = multiple)
        assertEquals("CALENDAR_SELECTION_REQUIRED", selection.getString("code"))
        assertEquals(0, multiple.batches)
        val provider = FakeProvider()
        for (args in
            listOf(
                event().put("end_time", "2026-10-04T10:00:00+08:00"),
                event().put("all_day", true),
            )) {
            assertEquals(
                "INVALID_ARGUMENT",
                PhoneOperations.execute(context, "create_calendar_event", args, access = provider)
                    .getString("code"),
            )
        }
        assertEquals(0, provider.batches)
    }

    @Test
    fun nativeFailureAfterDispatchIsNotReportedAsSafeToRetry() {
        val provider = FakeProvider().apply { failBatch = true }
        val result =
            PhoneOperations.execute(context, "create_calendar_event", event(), access = provider)
        assertFalse(result.getBoolean("ok"))
        assertEquals("unconfirmed", result.getString("status"))
        assertFalse(result.getBoolean("verified"))
        assertFalse(result.toString().contains("private-server-message"))
    }

    @Test
    fun aNonNullErrorUriIsNeverAConfirmedNoteCreation() {
        val provider = FakeProvider().apply { noteError = true }
        val result =
            PhoneOperations.execute(
                context,
                "create_note",
                JSONObject().put("content", "新便签正文"),
                access = provider,
            )
        assertFalse(result.getBoolean("ok"))
        assertEquals("NOTE_CREATE_REJECTED", result.getString("code"))
        assertFalse(result.has("note_id"))
        assertEquals(provider.callerPackage, provider.lastInsert!!.getAsString("package_name"))
    }

    @Test
    fun noteRequiresReadableProviderBeforeAnyCreation() {
        val provider = FakeProvider().apply { notesReady = false }
        val result =
            PhoneOperations.execute(
                context,
                "create_note",
                JSONObject().put("content", "正文"),
                access = provider,
            )
        assertEquals("NOTES_NOT_READY", result.getString("code"))
        assertNull(provider.lastInsert)
        assertFalse(result.has("status"))
    }

    @Test
    fun noteDeletionUsesUuidAndRecyclesWithoutAcceptingIndexIds() {
        val provider = FakeProvider()
        val bad =
            PhoneOperations.execute(
                context,
                "delete_note",
                JSONObject().put("note_id", "123"),
                access = provider,
            )
        assertEquals("INVALID_ARGUMENT", bad.getString("code"))
        assertNull(provider.deletedUri)
    }

    @Test
    fun clockTimeEditUsesItemUriSoOtherAlarmsAreNotTouched() {
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 0, enabled = false)
        provider.addClockRow(8, 6, 15, 0, enabled = true)
        val changed =
            PhoneOperations.execute(
                context,
                "update_alarm_time",
                JSONObject().put("alarm_id", "7").put("hour", 9).put("minute", 0),
                userId = 0,
                access = provider,
            )
        assertTrue(changed.toString(), changed.getBoolean("verified"))
        assertEquals(9, changed.getJSONObject("alarm").getInt("hour"))
        assertEquals(0, changed.getJSONObject("alarm").getInt("minute"))
        // 必须落到 item URI：Provider 的 dir 分支 update 会忽略 selection 改掉全部闹钟。
        assertEquals("content://com.android.deskclock/alarm/7", provider.updatedUri.toString())
        // 另一条闹钟必须原封不动。
        val other = provider.clockRows.first { it.getAsLong("_id") == 8L }
        assertEquals(6, other.getAsInteger("hour"))
        assertEquals(15, other.getAsInteger("minutes"))
        // 改时不应顺带启用原本关闭的闹钟。
        assertFalse(changed.getJSONObject("alarm").getBoolean("enabled"))
    }

    @Test
    fun clockCreateWritesThroughProviderAndComputesSingleShotTrigger() {
        val provider = FakeProvider()
        val result =
            PhoneOperations.execute(
                context,
                "set_alarm",
                JSONObject().put("hour", 7).put("minute", 45).put("label", "早课"),
                userId = 0,
                access = provider,
            )
        assertTrue(result.toString(), result.getBoolean("verified"))
        val row = provider.clockRows.single()
        assertEquals(7, row.getAsInteger("hour"))
        assertEquals(45, row.getAsInteger("minutes"))
        assertEquals("早课", row.getAsString("message"))
        // 单次闹钟必须自己算 alarmtime，Provider 不会算。
        assertTrue(row.getAsLong("alarmtime")!! > System.currentTimeMillis())
    }

    @Test
    fun clockCreateWithRepeatDaysLeavesAlarmTimeZero() {
        val provider = FakeProvider()
        val result =
            PhoneOperations.execute(
                context,
                "set_alarm",
                JSONObject()
                    .put("hour", 7)
                    .put("minute", 45)
                    .put("repeat_days", org.json.JSONArray(listOf("mon", "wed", "fri"))),
                userId = 0,
                access = provider,
            )
        assertTrue(result.toString(), result.getBoolean("verified"))
        val row = provider.clockRows.single()
        // 周一=1 + 周三=4 + 周五=16 = 21
        assertEquals(21, row.getAsInteger("daysofweek"))
        assertEquals(0L, row.getAsLong("alarmtime"))
    }

    @Test
    fun clockInvalidTimeCannotReachMutation() {
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 0, enabled = true)
        val result = PhoneOperations.execute(context, "set_alarm",
            JSONObject().put("hour", -1).put("minute", 0), userId = 0, access = provider)
        assertEquals("INVALID_ARGUMENT", result.getString("code"))
        assertNull(provider.updatedUri)
        assertNull(provider.insertedUri)
    }

    @Test
    fun clockIgnoresExtraFieldsWithoutChangingProviderTarget() {
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 0, enabled = true)
        val result = PhoneOperations.execute(context, "set_alarm_enabled",
            JSONObject().put("alarm_id", "7").put("enabled", false).put("provider_uri", "content://anything"),
            userId = 0, access = provider)
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals("content://com.android.deskclock/alarm/7", provider.updatedUri.toString())
        assertFalse(result.getJSONObject("alarm").getBoolean("enabled"))
    }

    @Test
    fun clockDeleteRemovesRowAndVerifiesAbsence() {
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 0, enabled = true)
        val result = PhoneOperations.execute(context, "delete_alarm",
            JSONObject().put("alarm_id", "7"), userId = 0, access = provider)
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals(0, provider.clockRows.size)
        assertEquals("content://com.android.deskclock/alarm/7", provider.deletedUri.toString())
    }

    @Test
    fun clockMissingIdReportsNotFound() {
        val provider = FakeProvider()
        val result = PhoneOperations.execute(context, "delete_alarm",
            JSONObject().put("alarm_id", "999"), userId = 0, access = provider)
        assertEquals("ALARM_NOT_FOUND", result.getString("code"))
    }

    @Test
    fun clockListReturnsRepeatDaysAndAlarmTime() {
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 21, enabled = true, label = "早课")
        val result = PhoneOperations.execute(context, "list_alarms",
            JSONObject().put("enabled_only", true), userId = 0, access = provider)
        assertTrue(result.toString(), result.getBoolean("ok"))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertEquals(7L, item.getLong("alarm_id"))
        assertEquals(21, item.getInt("repeat_mask"))
        assertEquals(listOf("mon", "wed", "fri"), (0 until item.getJSONArray("repeat_days").length())
            .map { item.getJSONArray("repeat_days").getString(it) })
        assertEquals("早课", item.getString("label"))
    }

    @Test
    fun alarmIdIsReturnedAsTextSoModelCanFeedItBack() {
        // 工具入参契约是文本（id("alarm_id")）；生产者给数字时，模型照抄回填会报
        // 「alarm_id 必须是文本」——契约与返回体必须一致（2026-11-26 审核）。
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 0, enabled = true)
        val listed = PhoneOperations.execute(context, "list_alarms",
            JSONObject(), userId = 0, access = provider)
        assertEquals("7", listed.getJSONArray("items").getJSONObject(0).getString("alarm_id"))

        val created = PhoneOperations.execute(context, "set_alarm",
            JSONObject().put("hour", 9).put("minute", 15), userId = 0, access = provider)
        assertTrue(created.toString(), created.get("alarm_id") is String)

        val changed = PhoneOperations.execute(context, "set_alarm_enabled",
            JSONObject().put("alarm_id", "7").put("enabled", false), userId = 0, access = provider)
        assertTrue(changed.toString(), changed.get("alarm_id") is String)
    }

    @Test
    fun alarmIdAcceptsNumericFormForBackwardCompatibility() {
        // 模型把 7 当数字回填时也必须能走通，不许因为类型不同就判非法。
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 0, enabled = true)
        val result = PhoneOperations.execute(context, "update_alarm_time",
            JSONObject().put("alarm_id", 7).put("hour", 10).put("minute", 5),
            userId = 0, access = provider)
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals("content://com.android.deskclock/alarm/7", provider.updatedUri.toString())
    }

    @Test
    fun alarmDeleteDoesNotClaimSuccessWhenQueryFails() {
        // 早先用 `after == null` 判删除成功，而查询异常也被兜成 null，
        // 于是「读不出来」被报成「已删除」（2026-11-26 审核）。
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 0, enabled = true)
        provider.alarmQueryFails = true
        val result = runCatching {
            PhoneOperations.execute(context, "update_alarm_time",
                JSONObject().put("alarm_id", "7").put("hour", 9).put("minute", 0),
                userId = 0, access = provider)
        }
        val payload = result.getOrNull()
        // 查询失败既不能报成功，也不能报「闹钟不存在」。
        if (payload != null) {
            assertFalse("查询失败不能报 verified", payload.optBoolean("verified", false))
            assertEquals("CLOCK_UNAVAILABLE", payload.optString("code"))
        }
    }

    @Test
    fun alarmMissingIdStillReportsNotFound() {
        // 「确实没有这条」仍要保留 ALARM_NOT_FOUND，不能被改成 CLOCK_UNAVAILABLE。
        val provider = FakeProvider()
        provider.addClockRow(7, 8, 30, 0, enabled = true)
        val result = PhoneOperations.execute(context, "delete_alarm",
            JSONObject().put("alarm_id", "999"), userId = 0, access = provider)
        assertEquals("ALARM_NOT_FOUND", result.getString("code"))
    }

    private fun event() =
        JSONObject()
            .put("title", "会议")
            .put("start_time", "2026-10-05T10:00:00+08:00")
            .put("end_time", "2026-10-05T11:00:00+08:00")
            .put("timezone", "Asia/Shanghai")

    private class FakeProvider : PhoneProviderAccess {
        override val callerPackage = "example.caller"
        var calendarCount = 1
        var batches = 0
        var lastBatchSize = 0
        var failBatch = false
        val events = mutableMapOf<Long, ContentValues>()
        val reminders = mutableListOf<ContentValues>()
        var lastInsert: ContentValues? = null
        var noteError = false
        var notesReady = true
        var deletedUri: Uri? = null
        var updatedUri: Uri? = null
        var insertedUri: Uri? = null
        val calls = mutableListOf<String>()
        var createdAlarm: Bundle? = null
        var alarmEnabled = true
        var alarmHour = 8
        var alarmMinute = 30
        var repeatMask = 0
        /** 模拟 Provider 查询本身失败（不是「没有这条」）：删除/改时的回读必须区分两者。 */
        var alarmQueryFails = false

        override fun query(
            uri: Uri,
            columns: Array<String>,
            selection: String?,
            args: Array<String>?,
            sort: String?,
        ): Cursor? {
            if (uri.authority.orEmpty().endsWith("com.nearme.note") && !notesReady) return null
            // 时钟查询失败：模拟 Provider 抛异常（不是返回空列表）。
            if (alarmQueryFails && uri.pathSegments.firstOrNull() == "alarm") {
                throw IllegalStateException("provider unavailable")
            }
            val rows: List<Map<String, Any?>> =
                when (uri.pathSegments.firstOrNull()) {
                    "calendars" ->
                        (1..calendarCount).map {
                            mapOf(
                                "_id" to it,
                                "calendar_displayName" to "日历$it",
                                "calendar_access_level" to 700,
                                "visible" to 1,
                            )
                        }
                    "events" ->
                        events.entries
                            .filter { args == null || it.key.toString() == args[0] }
                            .map { (id, values) ->
                                values.keySet().associateWith { values.get(it) } +
                                    mapOf("_id" to id, "deleted" to 0)
                            }
                    "reminders" ->
                        reminders
                            .filter { args == null || it.getAsString("event_id") == args[0] }
                            .map { v -> v.keySet().associateWith { v.get(it) } }
                    "alarm" ->
                        clockRows
                            .filter { row ->
                                args == null || selection?.let { sel ->
                                    val id = args.firstOrNull()?.toLongOrNull()
                                    !sel.contains("_id") || row.getAsLong("_id") == id
                                } ?: true
                            }
                            .map { v -> v.keySet().associateWith { v.get(it) } }
                    else -> emptyList()
                }
            return MatrixCursor(columns).apply {
                if (selection != "0")
                    rows.forEach { row -> addRow(columns.map { row[it] }.toTypedArray()) }
            }
        }

        override fun insert(uri: Uri, values: ContentValues): Uri? {
            lastInsert = ContentValues(values)
            return when (uri.pathSegments.first()) {
                "events" -> {
                    val id = events.size.toLong() + 1
                    events[id] = ContentValues(values)
                    uri.buildUpon().appendPath(id.toString()).build()
                }
                "reminders" -> {
                    reminders += ContentValues(values)
                    uri.buildUpon().appendPath(reminders.size.toString()).build()
                }
                "text_note" ->
                    uri.buildUpon()
                        .appendQueryParameter("result", if (noteError) "error" else "success")
                        .appendQueryParameter("message", "private-server-message")
                        .build()
                "alarm" -> {
                    insertedUri = uri
                    val id = (clockRows.maxOfOrNull { it.getAsLong("_id")!! } ?: 0L) + 1
                    clockRows +=
                        ContentValues(values).apply {
                            put("_id", id)
                            put("deleteAfterUse", 0)
                        }
                    uri.buildUpon().appendPath(id.toString()).build()
                }
                else -> null
            }
        }

        override fun update(uri: Uri, values: ContentValues, selection: String?, args: Array<String>?): Int {
            updatedUri = uri
            if (uri.pathSegments.firstOrNull() != "alarm") return 0
            // 真机 Provider 的 dir 分支会忽略 selection 改掉全部闹钟；这里复刻该行为，
            // 让「误用 dir URI」在测试里直接表现为数据被改坏，而不是悄悄通过。
            if (uri.pathSegments.size < 2) {
                clockRows =
                    clockRows.map { row -> ContentValues(row).apply { putAll(values) } }.toMutableList()
                return clockRows.size
            }
            val id = uri.pathSegments[1].toLongOrNull() ?: args?.firstOrNull()?.toLongOrNull() ?: return 0
            var affected = 0
            clockRows =
                clockRows.map { row ->
                    if (row.getAsLong("_id") != id) row
                    else {
                        affected++
                        ContentValues(row).apply { putAll(values) }
                    }
                }.toMutableList()
            return affected
        }

        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
            deletedUri = uri
            if (uri.pathSegments.firstOrNull() != "alarm") return 0
            val id = uri.pathSegments.getOrNull(1)?.toLongOrNull()
                ?: args?.firstOrNull()?.toLongOrNull()
                ?: return 0
            val before = clockRows.size
            clockRows = clockRows.filterNot { it.getAsLong("_id") == id }.toMutableList()
            return before - clockRows.size
        }

        override fun call(uri: Uri, method: String, arg: String?, extras: Bundle): Bundle {
            calls += method
            return Bundle()
        }

        /** 模拟小米时钟 alarms 表：query 返回当前行，update/delete 改内存并记调用。 */
        var clockRows = mutableListOf<ContentValues>()

        fun addClockRow(id: Long, hour: Int, minute: Int, mask: Int, enabled: Boolean, label: String = "") {
            clockRows +=
                ContentValues().apply {
                    put("_id", id)
                    put("hour", hour)
                    put("minutes", minute)
                    put("daysofweek", mask)
                    put("alarmtime", 0L)
                    put("enabled", if (enabled) 1 else 0)
                    put("message", label)
                    put("vibrate", 1)
                    put("deleteAfterUse", 0)
                }
        }

        override fun applyBatch(
            authority: String,
            operations: ArrayList<ContentProviderOperation>,
        ): Array<ContentProviderResult> {
            batches++
            lastBatchSize = operations.size
            if (failBatch) throw IllegalStateException("private-server-message")
            val provider =
                object : ContentProvider() {
                    override fun onCreate() = true

                    override fun query(
                        uri: Uri,
                        projection: Array<out String>?,
                        selection: String?,
                        selectionArgs: Array<out String>?,
                        sortOrder: String?,
                    ): Cursor? = null

                    override fun getType(uri: Uri): String? = null

                    override fun insert(uri: Uri, values: ContentValues?): Uri? =
                        this@FakeProvider.insert(uri, values!!)

                    override fun delete(
                        uri: Uri,
                        selection: String?,
                        selectionArgs: Array<out String>?,
                    ): Int = 0

                    override fun update(
                        uri: Uri,
                        values: ContentValues?,
                        selection: String?,
                        selectionArgs: Array<out String>?,
                    ): Int = 0
                }
            val results = arrayOfNulls<ContentProviderResult>(operations.size)
            operations.forEachIndexed { index, operation ->
                results[index] = operation.apply(provider, results, index)
            }
            @Suppress("UNCHECKED_CAST")
            return results as Array<ContentProviderResult>
        }
    }
}
