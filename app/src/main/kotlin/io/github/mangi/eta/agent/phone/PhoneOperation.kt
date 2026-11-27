package io.github.mangi.eta.agent.phone

import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal class PhoneOperationFailure(val code: String, message: String) : RuntimeException(message)

internal object PhoneOperation {
    val reads =
        setOf(
            "get_night_light",
            "get_mobile_data",
            "get_hotspot",
            "list_calendars",
            "read_calendar_event",
            "search_calendar_events",
            "list_alarms",
            "read_note",
        )
    val writes =
        setOf(
            "set_night_light",
            "set_mobile_data",
            "set_hotspot",
            "set_sound_mode",
            "create_calendar_event",
            "create_calendar_events",
            "update_calendar_event",
            "delete_calendar_event",
            "set_alarm",
            "update_alarm_time",
            "set_alarm_enabled",
            "delete_alarm",
            "create_note",
            "delete_note",
        )
    val tools = reads + writes + NativePersonalQueries.sources.keys

    fun error(code: String, message: String): Nothing = throw PhoneOperationFailure(code, message)

    fun text(args: JSONObject, key: String, required: Boolean = false, max: Int = 2000): String? {
        if (!args.has(key)) {
            if (required) error("INVALID_ARGUMENT", "缺少 $key")
            return null
        }
        val value = args.opt(key) as? String ?: error("INVALID_ARGUMENT", "$key 必须是文本")
        if (value.codePointCount(0, value.length) > max || '\u0000' in value || required && value.isBlank())
            error("INVALID_ARGUMENT", "$key 为空或超出长度范围")
        return value
    }

    fun integer(args: JSONObject, key: String, min: Long, max: Long, default: Long? = null): Long {
        if (!args.has(key)) return default ?: error("INVALID_ARGUMENT", "缺少 $key")
        val raw = args.opt(key)
        if (raw !is Number) error("INVALID_ARGUMENT", "$key 必须是整数")
        val value =
            try {
                java.math.BigDecimal(raw.toString()).longValueExact()
            } catch (_: ArithmeticException) {
                error("INVALID_ARGUMENT", "$key 必须是整数")
            } catch (_: NumberFormatException) {
                error("INVALID_ARGUMENT", "$key 必须是整数")
            }
        if (value !in min..max) error("INVALID_ARGUMENT", "$key 超出允许范围")
        return value
    }

    /**
     * 读取原始记录 ID。
     *
     * 同时接受数字与数字文本：工具契约声明的是 `text(...)`，但 list 类工具把 id 作为
     * 数字输出时，模型会照抄返回体回填数字（`alarm_id: 7`），早先只接受文本就报
     * 「alarm_id 必须是文本」——契约与生产者对不上，模型侧完全没有可用路径。
     * 现在以文本为主、数字兼容，两边都能走通（2026-11-26 审核）。
     */
    fun id(args: JSONObject, key: String): Long {
        if (!args.has(key)) error("INVALID_ARGUMENT", "缺少 $key")
        return AlarmVerification.parseId(args.opt(key))
            ?: error("INVALID_ARGUMENT", "$key 必须是有效原始记录 ID")
    }

    fun boolean(args: JSONObject, key: String, default: Boolean? = null): Boolean =
        if (!args.has(key)) default ?: error("INVALID_ARGUMENT", "缺少 $key")
        else args.opt(key) as? Boolean ?: error("INVALID_ARGUMENT", "$key 必须是布尔值")

    fun time(args: JSONObject, key: String): Long =
        try {
            OffsetDateTime.parse(text(args, key, true, 64)).toInstant().toEpochMilli()
        } catch (_: java.time.DateTimeException) {
            error("INVALID_ARGUMENT", "$key 必须是带时区偏移的 ISO 8601 时间")
        } catch (_: ArithmeticException) {
            error("INVALID_ARGUMENT", "$key 超出时间范围")
        }

    fun timezone(args: JSONObject): String =
        text(args, "timezone", false, 80)?.also {
            try {
                ZoneId.of(it)
            } catch (_: java.time.DateTimeException) {
                error("INVALID_ARGUMENT", "timezone 无效")
            }
        } ?: ZoneId.systemDefault().id

    fun allowed(args: JSONObject, fields: Set<String>) {
        if (args.keys().asSequence().any { it !in fields })
            error("INVALID_ARGUMENT", "当前操作包含不支持的参数")
    }

    fun ok(tool: String) = JSONObject().put("ok", true).put("tool", tool)

    fun changed(tool: String, verified: Boolean) =
        ok(tool)
            .put("ok", verified)
            .put("verified", verified)
            .put("status", if (verified) "verified" else "unconfirmed")
            .also {
                if (!verified)
                    it.put("code", "OPERATION_UNCONFIRMED")
                        .put("message", "请求已提交但结果未确认，请先查询状态，不要重复创建")
            }

    fun failure(code: String, message: String) =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    fun safeList(values: List<JSONObject>) = JSONArray(values)
}
