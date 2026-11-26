package io.github.mangi.eta.agent.phone

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import org.json.JSONObject

internal class PhoneAppTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
    private val colorOs: () -> Boolean,
) {
    fun execute(tool: String, arguments: JSONObject): JSONObject? {
        if (tool !in PhoneOperation.tools) return null
        val calendar = tool.contains("calendar")
        val system =
            tool in NativePersonalQueries.sources.keys ||
                tool in
                    setOf(
                        "get_night_light",
                        "get_mobile_data",
                        "set_night_light",
                        "set_mobile_data",
                        "get_hotspot",
                        "set_hotspot",
                        "set_sound_mode",
                    )
        if (tool == "set_sound_mode" && !rootAvailable())
            return PhoneOperations.execute(context, tool, arguments)
        // 闹钟读写都不再按厂商白名单丢弃：ClockOperations 已改为「Intent 建 + Provider 查改删」，
        // 走的是标准 AlarmClock Intent 与时钟 App 自己的 AlarmProvider，AOSP/小米/ColorOS 同一套。
        // 之前非 ColorOS 设备把 list_alarms 直接返回 null，会掉到私有库快照分支并报
        // CLOCK_SCHEMA_UNSUPPORTED，这就是闹钟列表在本机不可用的原因。
        if (tool in ALARM_TOOLS) {
            if (rootAvailable()) return executeRoot(tool, arguments)
            // 没有 Root 时先让应用自身身份试一次（新建走 Intent、查询走 Provider 读权限）；
            // 只有抛异常（接口不可用 / 被拒）才交回上层，由上层给出明确失败原因。
            return runCatching { PhoneOperations.execute(context, tool, arguments) }
                .getOrElse {
                    PhoneOperation.failure(
                        "CLOCK_UNAVAILABLE",
                        "时钟接口不可用：${it.message ?: it::class.java.simpleName}；已确认未写入任何记录",
                    )
                }
        }
        if (!calendar && !system && !colorOs())
            return PhoneOperation.failure("DEVICE_UNSUPPORTED", "当前设备没有兼容的一方应用接口")
        if (calendar && hasCalendarAccess(tool in PhoneOperation.writes))
            return PhoneOperations.execute(context, tool, arguments)
        if (!rootAvailable())
            return PhoneOperation.failure(
                if (calendar) "CALENDAR_PERMISSION_REQUIRED" else "ROOT_REQUIRED",
                if (calendar) "请在权限健康页授予日历读取和写入权限" else "此一方应用接口需要 Root",
            )
        return executeRoot(tool, arguments)
    }

    fun executeVivoPersonal(tool: String, arguments: JSONObject): JSONObject {
        if (!rootAvailable()) return PhoneOperation.failure("ROOT_REQUIRED", "vivo 个人数据接口需要 Root")
        return executeRoot(tool, arguments, "vivo_personal")
    }

    private fun executeRoot(tool: String, arguments: JSONObject, backend: String? = null): JSONObject {
        val request =
            JSONObject()
                .put("version", 1)
                .put("tool", tool)
                .put("user_id", DeviceToolContract.appUserId(context))
                .put("arguments", arguments)
        backend?.let { request.put("backend", it) }
        val command =
            "CLASSPATH=${DeviceToolContract.quote(context.applicationInfo.sourceDir)} app_process /system/bin io.github.mangi.eta.agent.phone.PhoneCommandMain"
        val result =
            root.execute(
                command,
                timeoutMillis = 25000,
                maxOutputBytes = 768 * 1024,
                input = request.toString().toByteArray(Charsets.UTF_8),
            )
        if (!result.ok || result.truncated)
            return PhoneOperation.failure(
                    result.errorCode.ifBlank { "PHONE_OPERATION_UNCONFIRMED" },
                    "原生操作通道未返回完整结果，请先核对状态",
                )
                .put("status", if (tool in PhoneOperation.writes) "unconfirmed" else "failed")
                .put("verified", false)
        val payload =
            result.stdout
                .lineSequence()
                .singleOrNull { it.startsWith(PhoneCommandMain.MARKER) }
                ?.removePrefix(PhoneCommandMain.MARKER)
                ?: return PhoneOperation.failure("PHONE_OPERATION_UNCONFIRMED", "未收到可验证的操作结果")
                    .put("status", "unconfirmed")
        return try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            PhoneOperation.failure("PHONE_OPERATION_UNCONFIRMED", "操作结果格式不完整")
                .put("status", "unconfirmed")
        }
    }

    private fun hasCalendarAccess(write: Boolean) =
        io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, write)

    private companion object {
        /** 闹钟读写：走 Intent + 时钟 App 的 AlarmProvider，不再受厂商白名单限制。 */
        val ALARM_TOOLS =
            setOf("list_alarms", "set_alarm", "update_alarm_time", "set_alarm_enabled", "delete_alarm")
    }
}
