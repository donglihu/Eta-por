package io.github.mangi.eta.data.db

import io.github.mangi.eta.core.safeLogType

/**
 * 分块读取失败的稳定原因码。
 *
 * [ChunkedTextDao.restoreTextTail] 在引用头损坏、分块缺失时已经会抛错（正确），但上游曾经
 * `runCatching { }.getOrNull()` 把异常压成 null，于是失败彻底不可观测（2026-11-26 审核 P1-4）。
 * 异常消息只用于归类，不直接进日志或界面：同一类失败在日志里必须长得一样，否则线上没法按原因检索。
 */
internal object ChunkedTextFailureReason {
    const val REFERENCE_INVALID = "reference_invalid"
    const val SIZE_INVALID = "size_invalid"
    const val CHUNK_MISSING = "chunk_missing"
    const val CHUNK_OUT_OF_ORDER = "chunk_out_of_order"
    const val LENGTH_MISMATCH = "length_mismatch"
    const val UNKNOWN_PREFIX = "unknown_"

    fun of(failure: Throwable): String {
        if (failure is NumberFormatException) return REFERENCE_INVALID
        val message = failure.message.orEmpty()
        return when {
            message.contains("chunk missing", ignoreCase = true) -> CHUNK_MISSING
            message.contains("chunk out of order", ignoreCase = true) -> CHUNK_OUT_OF_ORDER
            message.contains("length mismatch", ignoreCase = true) -> LENGTH_MISMATCH
            message.contains("invalid history payload size", ignoreCase = true) -> SIZE_INVALID
            message.contains("invalid history payload reference", ignoreCase = true) -> REFERENCE_INVALID
            else -> UNKNOWN_PREFIX + failure.safeLogType()
        }
    }
}
