package io.github.mangi.eta.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * 极致自动排查的闹钟落点：到点巡检一次并重排下一次；
 * 收到开机、时间变更、覆盖安装时只把闹钟重排回来（不立刻巡检）。
 */
internal class DiagnosticsPatrolReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                when (intent.action) {
                    ACTION_PATROL -> runBlocking { DiagnosticsPatrol.runOnce(app, reason = "定时巡检") }
                    ACTION_CAPTURE -> runBlocking {
                        DiagnosticsPatrol.runOnce(app, reason = "崩溃即取证", exportBundle = false)
                    }
                    else -> {
                        runBlocking {
                            DiagnosticsPatrolAlarm.refresh(app)
                            BootReceipt.record(
                                context = app,
                                alarmRescheduled = DiagnosticsPatrolAlarm.isScheduled(app),
                                receivers = 1,
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                AndroidAgentLogger.warn("Diagnostics patrol failed: type=${error.safeLogType()}")
                // 跑挂了也要把下一次闹钟排回去：重排在 runOnce 末尾，一次异常就会把
                // 整条周期链打断，之后只能等开机、改开关或手动巡检才能恢复。
                runBlocking {
                    runCatching { DiagnosticsPatrolAlarm.refresh(app) }
                        .onFailure { refreshError ->
                            AndroidAgentLogger.warn(
                                "Diagnostics patrol alarm refresh failed: type=${refreshError.safeLogType()}",
                            )
                        }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PATROL = "io.github.mangi.eta.action.DIAG_PATROL"
        const val ACTION_CAPTURE = "io.github.mangi.eta.action.DIAG_PATROL_CAPTURE"

        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "eta-diag-patrol").apply { isDaemon = true }
        }
    }
}
