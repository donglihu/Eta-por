package io.github.mangi.eta.core

import android.util.Log

internal interface AgentLogger {
    /**
     * 记录仅用于开发期诊断的信息。
     *
     * supplier 只能构造诊断文本，不能承担程序正确性依赖的副作用；Release 构建会删除整次调用。
     */
    fun debug(message: () -> String)

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

internal object AndroidAgentLogger : AgentLogger {
    private val logThrottle = LogThrottle()

    /**
     * `android.util.Log` 是否真的可用。
     *
     * 纯 JVM 单测里 android.jar 是 stub，任何 `Log.x` 都抛 `RuntimeException("Stub!")`。
     * 日志是旁路设施，不能因为它跑在无 Android 运行时上就把业务路径炸掉——审批裁决
     * 必须能在纯 JVM 下被覆盖到，否则只剩 Robolectric 一条路，而它在 arm64 上会被
     * 静默跳过，等于零覆盖（2026-11-26 审核 P0-2 修复需要真跑 skip() 全路径）。
     *
     * 只在首次调用探测一次：`isLoggable` 不抛异常就认为可用。不拿它的返回值当判据
     * （那受 ro.log.tag 影响，会把真机日志关掉）。
     */
    private val logAvailable: Boolean by lazy {
        runCatching {
            Log.isLoggable(ModuleConfig.TAG, Log.DEBUG)
            true
        }.getOrDefault(false)
    }

    override fun debug(message: () -> String) {
        if (!logAvailable) return
        Log.d(ModuleConfig.TAG, message())
    }

    override fun info(message: String) {
        if (!logAvailable) return
        Log.i(ModuleConfig.TAG, message)
    }

    override fun warn(message: String) {
        if (!logAvailable) return
        Log.w(ModuleConfig.TAG, message)
    }

    fun warnThrottled(
        key: String,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("warn:$key", windowMs)) {
            warn(message())
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (!logAvailable) return
        if (throwable == null) {
            Log.e(ModuleConfig.TAG, message)
        } else {
            Log.e(ModuleConfig.TAG, message, throwable)
        }
    }

    fun errorThrottled(
        key: String,
        throwable: Throwable? = null,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("error:$key", windowMs)) {
            error(message(), throwable)
        }
    }
}
