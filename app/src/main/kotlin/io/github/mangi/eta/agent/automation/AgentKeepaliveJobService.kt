package io.github.mangi.eta.agent.automation

import android.app.job.JobParameters
import android.app.job.JobService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * 保活自检：周期任务只做一件事——发现 [AgentKeepaliveService] 不在了就把它拉起来。
 *
 * 为什么是自检而不是自己保活：JobService 本身也会被系统杀，撑不起「常驻」；
 * 它的价值是周期性地把「被清掉的前台服务」补回来，属于兜底而非主体。
 *
 * 线程模型照抄 [AgentTaskJobService]：`onStartJob` 立刻返回 true，
 * 真正的活放进具名后台线程，跑完再 `jobFinished`。
 */
internal class AgentKeepaliveJobService : JobService() {

    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = thread(name = "eta-keepalive-job") {
            try {
                val enabled = runBlocking { SettingsDataStore.settings().keepaliveEnabled }
                if (!enabled) {
                    // 关掉就是关掉：不拉起、也不再排下一轮（周期任务已在设置切换时 cancel）。
                    AndroidAgentLogger.info("Keepalive self-check skipped: setting disabled")
                } else if (AgentKeepaliveService.isRunning(this@AgentKeepaliveJobService)) {
                    AndroidAgentLogger.info("Keepalive self-check: service already running")
                } else {
                    AndroidAgentLogger.info("Keepalive self-check: service missing, restarting")
                    // fromJob=true：本进程此刻没有可见 Activity，AppForeground 判据必然为假，
                    // 按前台口径会直接放弃；任务上下文里仍应真的尝试一次，成败由日志说话。
                    AgentKeepaliveService.enable(this@AgentKeepaliveJobService, fromJob = true)
                }
            } catch (error: Exception) {
                // 不静默吞：读设置或查服务状态失败都要留痕，否则「保活为什么没生效」无从查起。
                AndroidAgentLogger.warn("Keepalive self-check failed: type=${error.safeLogType()}")
            } finally {
                // wantsReschedule=false：本轮处理完就交还，重试语义交给下一周期。
                jobFinished(params, false)
            }
        }
        return true
    }

    /**
     * 返回 false：不重试。
     *
     * 这一轮被系统打断（条件变化、超时）没有意义立刻重来——服务要不要在，下一轮
     * 周期自检还会再判一次；重试只会让被打断的任务反复占用唤醒窗口。
     */
    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        AndroidAgentLogger.info("Keepalive self-check stopped by system: reason=${params.stopReason}")
        return false
    }
}
