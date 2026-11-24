package io.github.mangi.eta.agent.automation

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * 保活自检的调度器：只用 JobScheduler，不引入 WorkManager。
 *
 * 为什么用周期任务而不是闹钟：保活本身是「尽量别被系统清掉」，被清掉后需要一次兜底
 * 重排。周期任务天然会被系统待机策略拉长，这正好符合「不追求精确时刻」的语义；
 * `setPersisted(true)` 让它在重启后仍然存在，不必再挂一个只做重排的开机接收器。
 */
internal object AgentKeepaliveScheduler {

    /** 周期任务的固定 id：与 AgentTaskScheduler 的 1110、触发器通知的 1111 不冲突。 */
    private const val KEEPALIVE_JOB_ID = 1112

    /**
     * 自检周期：15 分钟是 JobScheduler 的周期下限，系统实际会拉长。
     *
     * 待机（Doze）下真实间隔可能是几小时。这里只作「服务没了就有人来拉一把」的兜底
     * 频率，不承诺 15 分钟一定被唤醒；即时性靠 START_STICKY 与前台通知。
     */
    private const val KEEPALIVE_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * 调度专用后台线程。
     *
     * 主线程回调（[android.app.Service.onTaskRemoved] 等）不能就地 `runBlocking` 读 DataStore，
     * 否则就是「主线程同步读盘」，配置差一点就是 ANR。所有非 UI 的排/撤动作都排到这里；
     * 单线程足够——每个动作只是「读一位开关 → 排或撤一个 job」，串行还顺带避免了设置竞争。
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-keepalive-resched").apply { isDaemon = true }
    }

    /** 排一次周期自检；重复调用幂等（同 id 会覆盖旧任务）。 */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler == null) {
            AndroidAgentLogger.warn("Keepalive job scheduling unavailable: JobScheduler missing")
            return
        }
        val job = JobInfo.Builder(
            KEEPALIVE_JOB_ID,
            ComponentName(context, AgentKeepaliveJobService::class.java),
        )
            .setPeriodic(KEEPALIVE_INTERVAL_MS)
            // 重启后仍保留：省掉一个只做重排的开机接收器，也不会漏掉装完包就被杀的情况。
            .setPersisted(true)
            .build()
        runCatching { scheduler.schedule(job) }
            .onSuccess { result ->
                if (result != JobScheduler.RESULT_SUCCESS) {
                    AndroidAgentLogger.warn("Keepalive job scheduling rejected: result=$result")
                }
            }
            .onFailure { error ->
                AndroidAgentLogger.warn("Keepalive job scheduling failed: type=${error.safeLogType()}")
            }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(KEEPALIVE_JOB_ID) }
            .onFailure { error ->
                AndroidAgentLogger.warn("Keepalive job cancel failed: type=${error.safeLogType()}")
            }
    }

    /**
     * 按设置决定排或撤：启动补排与设置页切换都走这里，避免两处判断口径不一致。
     *
     * 读设置失败时保持现状（不 cancel）：「读不到」不等于「用户关了」，贸然撤掉会让
     * 本来开着的保活静默失效。
     */
    fun refresh(context: Context) {
        val enabled = runCatching { runBlocking { SettingsDataStore.settings().keepaliveEnabled } }
            .onFailure { error ->
                AndroidAgentLogger.warn("Keepalive settings read failed: type=${error.safeLogType()}")
            }
            .getOrNull()
        when (enabled) {
            true -> schedule(context)
            false -> cancel(context)
            null -> AndroidAgentLogger.warn("Keepalive refresh skipped: settings unavailable")
        }
    }

    /**
     * 非阻塞入口：给主线程回调（如 `Service.onTaskRemoved`）用。
     *
     * 只负责把 [refresh] 甩到后台线程，立即返回；读设置失败仍由 [refresh] 记 warn，
     * 提交任务被拒（线程池已关闭等）也在这里记 warn，不静默吞掉。
     */
    fun refreshAsync(context: Context) {
        val app = context.applicationContext
        runCatching { worker.execute { refresh(app) } }
            .onFailure { error ->
                AndroidAgentLogger.warn(
                    "Keepalive async reschedule rejected: type=${error.safeLogType()}"
                )
            }
    }

    /**
     * 同步入口：给 UI 用。
     *
     * UI 层只调这一句，内部自己切后台线程把「存设置 → 排/撤 job → 起/停服务」跑完，
     * 这样 Compose 侧不需要协程作用域（ETA 纪律禁止 `rememberCoroutineScope()` 做异步）。
     *
     * 顺序不能反：先落设置再动服务。即使服务起不来，重启后的补排也按新值走，
     * 不会出现「设置开着但没人重排」的空窗。设置写失败则不动 job/服务——
     * 否则内存与磁盘不一致，下次启动 refresh 又按旧值改回去，用户会看到开关自己跳。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        runCatching {
            worker.execute {
                val saved = runCatching {
                    runBlocking { SettingsDataStore.setKeepaliveEnabled(enabled) }
                }.onFailure { error ->
                    AndroidAgentLogger.warn(
                        "Keepalive settings write failed: enabled=$enabled " +
                            "type=${error.safeLogType()}"
                    )
                }
                if (saved.isSuccess) {
                    if (enabled) {
                        schedule(app)
                        AgentKeepaliveService.enable(app)
                    } else {
                        cancel(app)
                        AgentKeepaliveService.disable(app)
                    }
                }
            }
        }.onFailure { error ->
            AndroidAgentLogger.warn(
                "Keepalive toggle rejected: enabled=$enabled type=${error.safeLogType()}"
            )
        }
    }

    /**
     * 电池优化白名单引导：只返回 Intent，不自动弹窗。
     *
     * 返回 null 表示已经加入白名单，调用方据此决定要不要显示「去设置」入口。
     * 部分 ROM 没有这个 Activity，调用方仍应包一层 try/catch，这里不做静默吞掉。
     */
    fun batteryOptimizationIntent(context: Context): Intent? {
        val power = context.getSystemService(PowerManager::class.java)
        if (power == null) {
            AndroidAgentLogger.warn("Battery optimization check unavailable: PowerManager missing")
            return null
        }
        if (power.isIgnoringBatteryOptimizations(context.packageName)) return null
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
