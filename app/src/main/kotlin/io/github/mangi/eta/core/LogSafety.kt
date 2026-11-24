package io.github.mangi.eta.core

internal const val UNKNOWN_LOG_TOKEN = "unknown"

/**
 * 未登记类型的兜底 token。
 *
 * 不使用运行时 simpleName：release/R8 会把应用自有类重命名成 1-3 位短名，
 * 线上日志里因此出现 `type=sp`、`cause=tp` 之类无法检索的值。
 */
internal const val UNCLASSIFIED_LOG_TYPE = "UnclassifiedThrowable"

/**
 * 应用自有异常声明稳定日志类型的入口。
 *
 * 走接口而不是按类名字符串匹配：`is` 判断不受 R8 混淆影响，类名会被改掉。
 */
internal interface StableLogType {
    val stableLogType: String
}

/** 声明 token 与可读类名的长度上限，与 [toSafeLogToken] 默认口径一致。 */
private const val MAX_LOG_TYPE_CHARS = 64

/**
 * 可读类型名白名单：首字母大写、至少 3 字符、只含标识符字符。
 *
 * R8 混淆后的应用类名是 1-3 位小写短标识（线上实测 `sp` / `tp`），永不匹配；
 * 匿名类的 simpleName 为空，同样不匹配。框架类（`IOException`、`JSONException`、
 * `SSLHandshakeException`）正常匹配，因此不再需要按包名前缀额外放行。
 */
private val READABLE_LOG_TYPE_NAME = Regex("[A-Z][A-Za-z0-9_]{2,}")

/** 返回不会携带异常消息或运行时数据的稳定异常类型。 */
internal fun Throwable.safeLogType(): String {
    if (this is StableLogType) {
        // 声明了 token 也要过一遍安全闸：带空格/换行/超长的实现不能污染日志行。
        val declared = stableLogType.toSafeLogToken(MAX_LOG_TYPE_CHARS)
        if (declared != UNKNOWN_LOG_TOKEN) return declared
    }
    val simpleName = javaClass.simpleName.orEmpty()
    if (READABLE_LOG_TYPE_NAME.matches(simpleName)) return simpleName
    return UNCLASSIFIED_LOG_TYPE
}

/** 保留失败位置与原因类型，禁止异常消息、载荷或 suppressed 内容进入诊断日志。 */
internal fun Throwable.safeStackTrace(): String = buildString {
    var failure: Throwable? = this@safeStackTrace
    val visited = java.util.IdentityHashMap<Throwable, Boolean>()
    repeat(3) {
        val current = failure ?: return@buildString
        if (visited.put(current, true) != null) return@buildString
        if (isNotEmpty()) append("\nCaused by: ")
        append(current.safeLogType().take(128))
        val frames = current.stackTrace
        frames.take(16).forEach { frame ->
            append("\n at ")
            append(frame.className.take(256)).append('.').append(frame.methodName.take(128))
            append('(').append(frame.fileName?.take(128) ?: "unknown").append(':').append(frame.lineNumber).append(')')
        }
        if (frames.size > 16) append("\n ...")
        failure = current.cause
    }
}

/**
 * 将外部或模型生成的标识约束为低基数、单行的日志 token。
 */
internal fun String?.toSafeLogToken(maxLength: Int = 64): String {
    require(maxLength > 0) { "maxLength 必须大于 0" }
    val value = this ?: return UNKNOWN_LOG_TOKEN
    if (value.isEmpty() || value.length > maxLength) return UNKNOWN_LOG_TOKEN
    return value.takeIf { token ->
        token.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '.' ||
                character == '_' ||
                character == '-'
        }
    } ?: UNKNOWN_LOG_TOKEN
}
