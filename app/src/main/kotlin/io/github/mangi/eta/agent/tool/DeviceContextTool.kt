package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt
import org.json.JSONObject

/** 按需提供手机当前采用的时间环境与最近系统位置。 */
internal object DeviceContextTool {
    fun current(
        context: Context,
        clock: Clock = Clock.systemDefaultZone(),
    ): String = current(clock, DeviceLocationProvider.latest(context))

    internal fun current(
        clock: Clock,
        location: DeviceLocationProvider.Result,
    ): String {
        val localTime = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)
        val json = JSONObject()
            .put("datetime", localTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("timezone", localTime.zone.id)
            .put(
                "weekday",
                localTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE),
            )
        // 只有真正读到最近位置才带 location。时间与位置共用一个工具，读不到位置时
        // 回一个 status 只会让每次问时间都多出一条「位置未授权」噪音；需要位置状态的
        // 调用方应改用 get_current_location，它在未授权时根本不会发布给模型。
        location.toJson()?.let { json.put("location", it) }
        return json.toString()
    }

    private fun DeviceLocationProvider.Result.toJson(): JSONObject? = when (this) {
        is DeviceLocationProvider.Result.Available -> JSONObject()
            .put("latitude", latitude.roundCoordinate())
            .put("longitude", longitude.roundCoordinate())
            .put("accuracy_m", accuracyMeters?.roundToInt())
            .put("age_s", ageMillis / 1_000L)

        is DeviceLocationProvider.Result.Unavailable -> null
    }

    private fun Double.roundCoordinate(): Double =
        round(this * COORDINATE_SCALE) / COORDINATE_SCALE

    private const val COORDINATE_SCALE = 100_000.0
}
