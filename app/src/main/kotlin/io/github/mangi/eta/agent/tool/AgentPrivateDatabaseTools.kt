package io.github.mangi.eta.agent.tool

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 读取经过真机验证的固定数据库；路径、表、字段与查询均不接受模型输入。 */
internal class AgentPrivateDatabaseTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "list_alarms" -> sensitive(readDatabase(CLOCK_DATABASE, "CLOCK_DATA_UNAVAILABLE") { listAlarms(it, args) })
        "list_active_timers" -> sensitive(listTimers(args))
        "search_clipboard_history" -> sensitive(readDatabase(CLIPBOARD_DATABASE, "CLIPBOARD_HISTORY_UNAVAILABLE") { searchClipboard(it, args) })
        "get_health_summary" -> sensitive(readDatabase(HEALTH_DATABASE, "HEALTH_DATA_UNAVAILABLE") { healthSummary(it, args) })
        else -> null
    }

    private fun listAlarms(database: SQLiteDatabase, args: JSONObject): String {
        if (!database.hasColumns("alarms", setOf("_id", "hour", "minutes", "enabled"))) {
            return error("CLOCK_SCHEMA_UNSUPPORTED", "当前时钟数据库结构暂不受支持")
        }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val items = database.rows(
            table = "alarms",
            columns = listOf(
                "_id", "hour", "minutes", "daysofweek", "alarmtime", "enabled", "message",
                "vibrate", "deleteAfterUse", "skiptime",
            ),
            selection = if (args.optBoolean("enabled_only", true)) "enabled=1" else null,
            order = "enabled DESC, alarmtime ASC",
            limit = limit,
        )
        return ok("list_alarms", items, limit)
    }

    private fun listTimers(args: JSONObject): String {
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val state = queryTimerCountdown()
            ?: return error("CLOCK_TIMER_UNAVAILABLE", "时钟计时器状态暂时不可访问")
        val stateCode = state.optInt("timestate", TIMER_STATE_OFF)
        val active = stateCode == TIMER_STATE_RUNNING || stateCode == TIMER_STATE_PAUSED
        val items = JSONArray()
        if (active) {
            items.put(
                JSONObject()
                    .put("state", stateCode)
                    .put("state_label", timerStateLabel(stateCode))
                    .put("duration_ms", state.optLong("duration", 0L))
                    .put("remaining_ms", state.optLong("timerremained", 0L))
                    .put("end_time", state.optLong("endtime", 0L)),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "list_active_timers")
            .put("items", items)
            .put("count", items.length())
            .put("truncated", items.length() >= limit)
            .put("state", stateCode)
            .put("state_label", timerStateLabel(stateCode))
            .toString()
    }

    /** 计时器运行态只存在于时钟 App 的 SharedPreferences，通过 Provider 读取当前状态。 */
    private fun queryTimerCountdown(): JSONObject? {
        val query = "content query --uri content://$AOSP_CLOCK_AUTHORITY/timercountdown"
        val direct = root.execute(query, timeoutMillis = 10_000L, maxOutputBytes = 8 * 1024)
        val output = if (direct.ok && direct.stdout.contains("Row:")) {
            direct.stdout
        } else {
            // 应用沙箱的挂载视图可能看不到时钟 Provider，退回 init 命名空间再查一次。
            root.execute(
                "nsenter -t 1 -m -- /system/bin/sh -c ${shellQuote(query)}",
                timeoutMillis = 10_000L,
                maxOutputBytes = 8 * 1024,
            ).takeIf { it.ok }?.stdout
        } ?: return null
        val row = output.lineSequence().firstOrNull { it.trimStart().startsWith("Row:") }
            ?: return if (output.contains("No result found")) {
                JSONObject().put("timestate", TIMER_STATE_OFF)
            } else {
                null
            }
        val json = JSONObject()
        row.substringAfter("Row:").trim().substringAfter(' ', "").trim()
            .split(',')
            .forEach { pair ->
                val key = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (key.isNotEmpty()) value.toLongOrNull()?.let { json.put(key, it) } ?: json.put(key, value)
            }
        return if (json.has("timestate")) json else null
    }

    private fun timerStateLabel(state: Int): String = when (state) {
        TIMER_STATE_STOP -> "已停止"
        TIMER_STATE_RUNNING -> "运行中"
        TIMER_STATE_PAUSED -> "已暂停"
        TIMER_STATE_OFF -> "空闲"
        else -> "未知"
    }

    private fun searchClipboard(database: SQLiteDatabase, args: JSONObject): String {
        if (!database.hasColumns("CLIPBOARD_ITEM", setOf("TIME", "CONTENT"))) {
            return error("CLIPBOARD_SCHEMA_UNSUPPORTED", "当前剪贴板历史结构暂不受支持")
        }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val query = args.optString("query").trim()
        val items = database.rows(
            table = "CLIPBOARD_ITEM",
            columns = listOf("TIME", "CONTENT"),
            selection = query.takeIf(String::isNotBlank)?.let { "CONTENT LIKE ? ESCAPE '\\' COLLATE NOCASE" },
            selectionArgs = query.takeIf(String::isNotBlank)?.let { arrayOf("%${it.escapeLike()}%") },
            order = "TIME DESC",
            limit = limit,
        )
        return ok("search_clipboard_history", items, limit)
    }

    private fun healthSummary(database: SQLiteDatabase, args: JSONObject): String {
        val days = args.optInt("days", 7).coerceIn(1, 30)
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val summary = JSONObject()
        database.aggregate(
            "steps_record_table",
            "SELECT COUNT(*), COALESCE(SUM(count),0) FROM steps_record_table WHERE end_time>=?",
            cutoff,
        )?.let { summary.put("steps", JSONObject().put("records", it[0]).put("count", it[1])) }
        database.aggregate(
            "sleep_session_record_table",
            "SELECT COUNT(*), COALESCE(SUM(end_time-start_time),0) FROM sleep_session_record_table WHERE end_time>=?",
            cutoff,
        )?.let { summary.put("sleep", JSONObject().put("sessions", it[0]).put("duration_ms", it[1])) }
        database.aggregate(
            "exercise_session_record_table",
            "SELECT COUNT(*), COALESCE(SUM(end_time-start_time),0) FROM exercise_session_record_table WHERE end_time>=?",
            cutoff,
        )?.let { summary.put("exercise", JSONObject().put("sessions", it[0]).put("duration_ms", it[1])) }
        if (
            database.hasColumns("heart_rate_record_table", setOf("row_id", "end_time")) &&
            database.hasColumns("heart_rate_record_series_table", setOf("parent_key", "beats_per_minute"))
        ) {
            database.rawQuery(
                "SELECT COUNT(*), MIN(beats_per_minute), MAX(beats_per_minute), AVG(beats_per_minute) " +
                    "FROM heart_rate_record_series_table WHERE parent_key IN " +
                    "(SELECT row_id FROM heart_rate_record_table WHERE end_time>=?)",
                arrayOf(cutoff.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst() && cursor.getLong(0) > 0) {
                    summary.put(
                        "heart_rate",
                        JSONObject()
                            .put("samples", cursor.getLong(0))
                            .put("min_bpm", cursor.getLong(1))
                            .put("max_bpm", cursor.getLong(2))
                            .put("avg_bpm", cursor.getDouble(3)),
                    )
                }
            }
        }
        database.latestMeasurement("weight_record_table", "time", "weight", cutoff)
            ?.let { summary.put("latest_weight_kg", it / 1_000.0) }
        database.latestMeasurement("oxygen_saturation_record_table", "time", "percentage", cutoff)
            ?.let { summary.put("latest_oxygen_saturation", it) }
        return JSONObject()
            .put("ok", true)
            .put("tool", "get_health_summary")
            .put("window_days", days)
            .put("summary", summary)
            .toString()
    }

    private fun readDatabase(source: DatabaseSource, unavailableCode: String, block: (SQLiteDatabase) -> String): String =
        synchronized(snapshotLock) {
            val userId = context.dataDir.parentFile?.name?.toIntOrNull()
                ?: return@synchronized error(unavailableCode, "无法确定当前 Android 用户")
            val snapshot = createSnapshotFromCandidates(source, userId)
                ?: return@synchronized error(unavailableCode, source.unavailableMessage)
            try {
                val database =
                    try {
                        SQLiteDatabase.openDatabase(
                            snapshot.absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                        )
                    } catch (failure: android.database.sqlite.SQLiteException) {
                        return@synchronized error(
                            "CLOCK_SNAPSHOT_OPEN_FAILED",
                            "时钟快照打开失败：${failure.message ?: "SQLiteException"}",
                        )
                    }
                database.use { block(it) }
            } finally {
                deleteSnapshot(snapshot)
            }
        }

    /** 按候选路径依次尝试：优先走 /data_mirror（不受应用沙箱挂载遮蔽），再退回常规路径。 */
    private fun createSnapshotFromCandidates(source: DatabaseSource, userId: Int): File? =
        source.paths(userId).firstNotNullOfOrNull { createSnapshot(it, source.maxBytes) }

    private fun createSnapshot(source: String, maxBytes: Long): File? {
        cleanupStaleSnapshots()
        val snapshot = runCatching { File.createTempFile(SNAPSHOT_PREFIX, ".db", context.cacheDir) }.getOrNull()
            ?: return null
        val command = buildString {
            append("[ -f ").append(shellQuote(source)).append(" ] || exit 21; ")
            append("[ ! -L ").append(shellQuote(source)).append(" ] || exit 22; ")
            append("[ \"\$(stat -c %s ").append(shellQuote(source)).append(")\" -le ").append(maxBytes).append(" ] || exit 23; ")
            append("cp ").append(shellQuote(source)).append(' ').append(shellQuote(snapshot.absolutePath)).append(" || exit 24; ")
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val extraSource = source + suffix
                val extraTarget = snapshot.absolutePath + suffix
                append("if [ -f ").append(shellQuote(extraSource)).append(" ]; then ")
                append("[ ! -L ").append(shellQuote(extraSource)).append(" ] || exit 25; ")
                append("[ \"\$(stat -c %s ").append(shellQuote(extraSource)).append(")\" -le ").append(maxBytes).append(" ] || exit 26; ")
                append("cp ").append(shellQuote(extraSource)).append(' ').append(shellQuote(extraTarget)).append(" || exit 25; fi; ")
            }
        }
        if (root.execute(command, timeoutMillis = 15_000L, maxOutputBytes = 8 * 1024).ok) return snapshot
        // 应用沙箱的挂载视图可能看不到目标目录，退回 init 命名空间再复制一次。
        val namespaced = "nsenter -t 1 -m -- /system/bin/sh -c ${shellQuote(command)}"
        if (root.execute(namespaced, timeoutMillis = 15_000L, maxOutputBytes = 8 * 1024).ok) return snapshot
        deleteSnapshot(snapshot)
        return null
    }

    private fun SQLiteDatabase.rows(
        table: String,
        columns: List<String>,
        selection: String?,
        selectionArgs: Array<String>? = null,
        order: String,
        limit: Int,
    ): JSONArray {
        val available = tableColumns(table)
        val projection = columns.filter(available::contains)
        return JSONArray().also { rows ->
            query(table, projection.toTypedArray(), selection, selectionArgs, null, null, order, limit.toString()).use { cursor ->
                while (cursor.moveToNext()) rows.put(cursor.toJson())
            }
        }
    }

    private fun SQLiteDatabase.aggregate(table: String, query: String, cutoff: Long): LongArray? {
        if (tableColumns(table).isEmpty()) return null
        return runCatching {
            rawQuery(query, arrayOf(cutoff.toString())).use { cursor ->
                if (!cursor.moveToFirst()) null else longArrayOf(cursor.getLong(0), cursor.getLong(1))
            }
        }.getOrNull()
    }

    private fun SQLiteDatabase.latestMeasurement(table: String, timeColumn: String, valueColumn: String, cutoff: Long): Double? {
        if (!hasColumns(table, setOf(timeColumn, valueColumn))) return null
        return rawQuery(
            "SELECT $valueColumn FROM $table WHERE $timeColumn>=? ORDER BY $timeColumn DESC LIMIT 1",
            arrayOf(cutoff.toString()),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null }
    }

    private fun SQLiteDatabase.hasColumns(table: String, required: Set<String>): Boolean =
        tableColumns(table).containsAll(required)

    /**
     * 读取表列名。之前这里是 `runCatching{...}.getOrDefault(emptySet())`，把「库打不开 /
     * 表不存在 / PRAGMA 失败」全吞成「空列集」，再被 hasColumns 判成结构不支持，最终对用户
     * 报 CLOCK_SCHEMA_UNSUPPORTED —— 真正的失败原因（快照没拷到、库损坏）永久不可见。
     * 现在异常照常上抛，由 readDatabase 的具名 catch 统一转成可诊断错误码。
     */
    private fun SQLiteDatabase.tableColumns(table: String): Set<String> {
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val name = cursor.getColumnIndex("name")
            if (name < 0) return emptySet()
            return buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }
    }

    private fun Cursor.toJson(): JSONObject = JSONObject().also { row ->
        for (index in 0 until columnCount) {
            if (isNull(index)) continue
            val value: Any = when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_STRING -> getString(index).take(MAX_FIELD_CHARS)
                else -> continue
            }
            row.put(getColumnName(index), value)
        }
    }

    private fun ok(tool: String, items: JSONArray, limit: Int): String = JSONObject()
        .put("ok", true)
        .put("tool", tool)
        .put("items", items)
        .put("count", items.length())
        .put("truncated", items.length() == limit)
        .toString()

    private fun cleanupStaleSnapshots() {
        context.cacheDir.listFiles()?.filter { it.name.startsWith(SNAPSHOT_PREFIX) }?.forEach(File::delete)
    }

    private fun deleteSnapshot(snapshot: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(snapshot.absolutePath + it).delete() }
    }

    private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun error(code: String, message: String): String = JSONObject().put("ok", false).put("code", code).put("message", message).toString()
    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    private data class DatabaseSource(
        val candidatePaths: List<String>,
        val maxBytes: Long,
        val unavailableMessage: String,
    ) {
        fun paths(userId: Int): List<String> = candidatePaths.map { it.replace("{user}", userId.toString()) }
    }

    private companion object {
        val snapshotLock = Any()
        const val SNAPSHOT_PREFIX = "eta-private-data-"
        const val MAX_FIELD_CHARS = 4_000
        const val DAY_MS = 24L * 60 * 60 * 1_000
        const val AOSP_CLOCK_PACKAGE = "com.android.deskclock"
        const val COLOROS_CLOCK_PACKAGE = "com.coloros.alarmclock"
        const val AOSP_CLOCK_AUTHORITY = "com.android.deskclock"
        const val TIMER_STATE_STOP = 0
        const val TIMER_STATE_RUNNING = 1
        const val TIMER_STATE_PAUSED = 2
        const val TIMER_STATE_OFF = 3
        val CLOCK_DATABASE = DatabaseSource(
            listOf(
                "/data_mirror/data_de/null/{user}/$AOSP_CLOCK_PACKAGE/databases/alarms.db",
                "/data_mirror/data_de/null/{user}/$COLOROS_CLOCK_PACKAGE/databases/alarms.db",
                "/data/user_de/{user}/$AOSP_CLOCK_PACKAGE/databases/alarms.db",
                "/data/user_de/{user}/$COLOROS_CLOCK_PACKAGE/databases/alarms.db",
            ),
            32L * 1024 * 1024,
            "时钟数据暂时不可访问",
        )
        val CLIPBOARD_DATABASE = DatabaseSource(
            listOf(
                "/data_mirror/data_ce/null/{user}/com.sohu.inputmethod.sogouoem/databases/clipboard_db",
                "/data/user/{user}/com.sohu.inputmethod.sogouoem/databases/clipboard_db",
            ),
            32L * 1024 * 1024,
            "当前输入法没有可访问的剪贴板历史",
        )
        val HEALTH_DATABASE = DatabaseSource(
            listOf(
                "/data_mirror/data_ce/null/{user}/healthconnect/healthconnect.db",
                "/data/system_ce/{user}/healthconnect/healthconnect.db",
            ),
            256L * 1024 * 1024,
            "系统健康数据暂时不可访问",
        )
    }
}
