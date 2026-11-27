package io.github.mangi.eta.agent.phone

/**
 * 闹钟写后回读的判定规则（纯函数，可脱离 Android 单测）。
 *
 * 单独抽出来的原因：`delete_alarm`/`update_alarm_time` 的确认逻辑依赖「查询成功但没这条」
 * 与「查询本身失败」的区别。早先两者都被兜成 null，于是读取失败会谎报「已删除」。
 */
internal object AlarmVerification {

    /** 一次按 id 回读的结果。 */
    sealed interface Lookup {
        /** 查到了这条。 */
        data class Found(val hour: Int, val minute: Int, val enabled: Boolean) : Lookup

        /** 查询成功，但列表里确实没有这条。 */
        data object Gone : Lookup

        /** 查询本身失败：不能作为任何结论。 */
        data object Unknown : Lookup
    }

    /**
     * 判定写操作是否已确认生效。
     *
     * - 删除：只有 [Lookup.Gone] 才算成功；[Lookup.Unknown] 一律不确认。
     * - 改时：只有查到且时分一致才算成功。
     * - 开关：只有查到且启用位一致才算成功。
     */
    fun verified(
        tool: String,
        lookup: Lookup,
        expectedHour: Int,
        expectedMinute: Int,
        expectedEnabled: Boolean,
    ): Boolean = when (tool) {
        "delete_alarm" -> lookup == Lookup.Gone
        "update_alarm_time" -> (lookup as? Lookup.Found)?.let {
            it.hour == expectedHour && it.minute == expectedMinute
        } == true
        else -> (lookup as? Lookup.Found)?.enabled == expectedEnabled
    }

    /**
     * 解析原始记录 ID。
     *
     * 同时接受数字与数字文本：工具契约声明 `text(...)`，但 list 类工具若把 id 输出成数字，
     * 模型会照抄回填数字，早先只接受文本就报「必须是文本」——契约与生产者对不上。
     * 返回 null 表示非法，调用方负责转成 INVALID_ARGUMENT。
     */
    fun parseId(raw: Any?): Long? {
        val text = when (raw) {
            // JSON 里数字常被解析成 Double：7 会变成 "7.0"。只接受没有小数部分的整值。
            is Double -> {
                if (raw != Math.floor(raw) || raw.isInfinite() || raw.isNaN()) return null
                raw.toLong().toString()
            }
            is Float -> {
                val asDouble = raw.toDouble()
                if (asDouble != Math.floor(asDouble) || asDouble.isInfinite() || asDouble.isNaN()) return null
                asDouble.toLong().toString()
            }
            is Number -> raw.toString()
            is String -> raw
            else -> return null
        }
        if (!Regex("[0-9]{1,19}").matches(text)) return null
        return text.toLongOrNull()?.takeIf { it > 0 }
    }
}
