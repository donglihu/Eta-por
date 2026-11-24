package io.github.mangi.eta.agent.automation

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppForeground
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity

/**
 * 可选的后台保活前台服务。
 *
 * 与 [AgentTriggerMonitorService] / [AgentExecutionService] 的差别只有一条，但很关键：
 * 那两个服务是「有活干才在」，活干完就自己走；本服务是「用户明确要求常驻」，
 * 因此 [onStartCommand] 返回 [START_STICKY]——被系统因内存压力杀掉后由系统重建，
 * 重建时 intent 为 null，此时**不能**按「没有会话就自停」的规则立刻退出，
 * 否则 STICKY 就白设了。自停判定只对显式 action 生效。
 *
 * 状态机（对齐 miniAI 语义）：
 * - ENABLE_PERSISTENT / DISABLE_PERSISTENT：用户级常驻开关
 * - START_SESSION / STOP_SESSION：一次性会话的计数，进出配对
 * - 会话计数为 0 且未开常驻 → stopSelf
 */
internal class AgentKeepaliveService : Service() {

    /** 常驻标记：用户通过设置页开启后为 true，DISABLE_PERSISTENT 置回 false。 */
    @Volatile
    private var persistent = false

    /** 会话计数：START_SESSION 加一、STOP_SESSION 减一，减到 0 才允许自停。 */
    @Volatile
    private var sessionCount = 0

    /** 只用于日志与诊断，真正的存活判定走 [isRunning]。 */
    @Volatile
    private var running = false

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val open = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText(getString(R.string.keepalive_notification_summary))
            .setContentIntent(open)
            .setOngoing(true)
            // 常驻通知不重播提示音与横幅：它一直在，反复提醒只会变成骚扰。
            .setOnlyAlertOnce(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        else startForeground(NOTIFICATION_ID, notification)
        acquireWifiLock()
        running = true
        AndroidAgentLogger.info("Keepalive service started")
    }

    /**
     * WifiLock 只在保活期间持有：HIGH_PERF 会压住 Wi-Fi 省电策略，
     * 让长连接（模型流式响应、MCP 长轮询）不因息屏被切断。
     *
     * 拿不到不算致命——保活主体仍然是前台服务，Wi-Fi 锁只是加分项，
     * 所以失败只记 warn，不抛也不中断服务。
     */
    private fun acquireWifiLock() {
        runCatching {
            val manager = applicationContext.getSystemService(WifiManager::class.java)
            if (manager == null) {
                AndroidAgentLogger.warn("Keepalive wifi lock unavailable: WifiManager missing")
                return
            }
            manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }.also { wifiLock = it }
        }.onFailure { error ->
            AndroidAgentLogger.warn("Keepalive wifi lock acquire failed: type=${error.safeLogType()}")
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { error ->
                AndroidAgentLogger.warn("Keepalive wifi lock release failed: type=${error.safeLogType()}")
            }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_ENABLE_PERSISTENT -> {
                persistent = true
                AndroidAgentLogger.info("Keepalive persistent enabled")
            }

            ACTION_DISABLE_PERSISTENT -> {
                persistent = false
                AndroidAgentLogger.info("Keepalive persistent disabled: sessions=$sessionCount")
            }

            ACTION_START_SESSION -> {
                sessionCount += 1
                AndroidAgentLogger.info("Keepalive session started: sessions=$sessionCount")
            }

            ACTION_STOP_SESSION -> {
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                AndroidAgentLogger.info("Keepalive session stopped: sessions=$sessionCount")
            }

            null -> {
                // 系统按 START_STICKY 重建：会话计数已随进程丢失，这里只恢复前台形态，
                // 不按「无会话」判停，否则重建后立刻自停，STICKY 形同虚设。
                AndroidAgentLogger.info("Keepalive service restarted by system")
            }

            else -> AndroidAgentLogger.warn("Keepalive unknown action: $action")
        }
        if (action != null && sessionCount <= 0 && !persistent) {
            AndroidAgentLogger.info("Keepalive idle: sessions=0 persistent=false, stopping")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务里划掉应用：先重排自检任务（下一次拉起靠它），再撤前台并自停。
     *
     * 不在这里强行「续命」是刻意的：用户划掉就是明确要它停，硬撑会让应用变成流氓软件。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AndroidAgentLogger.info("Keepalive task removed: rescheduling self-check")
        // onTaskRemoved 跑在主线程，refresh() 内部是 runBlocking 读 DataStore，
        // 就地调用等于主线程同步读盘，极端情况直接 ANR。这里只投递后台任务后立即返回，
        // 失败仍由 refreshAsync 自己记 warn，不静默吞。
        AgentKeepaliveScheduler.refreshAsync(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        releaseWifiLock()
        AndroidAgentLogger.info("Keepalive service destroyed: sessions=$sessionCount persistent=$persistent")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "eta_keepalive"
        private const val NOTIFICATION_ID = 1113
        private const val WIFI_LOCK_TAG = "Eta:Keepalive"

        const val ACTION_ENABLE_PERSISTENT = "io.github.mangi.eta.action.KEEPALIVE_ENABLE"
        const val ACTION_DISABLE_PERSISTENT = "io.github.mangi.eta.action.KEEPALIVE_DISABLE"
        const val ACTION_START_SESSION = "io.github.mangi.eta.action.KEEPALIVE_SESSION_START"
        const val ACTION_STOP_SESSION = "io.github.mangi.eta.action.KEEPALIVE_SESSION_STOP"

        /**
         * 开启常驻。
         *
         * targetSdk 37 下后台启动前台服务会被 `ForegroundServiceStartNotAllowedException`
         * 直接拒掉（Android 12+ 的 FGS 后台启动限制）。所以这里先判前台：
         * 不在前台就只记日志、把恢复交给周期自检任务，而不是硬起一个必然失败的请求
         * （对应 miniAI 的 "app is in background, keepalive deferred"）。
         */
        fun enable(context: Context, fromJob: Boolean = false) {
            // 周期任务里没有可见 Activity，AppForeground 必然为假，但任务上下文允许起前台服务，
            // 所以 fromJob 直接放行；其余调用方仍按前台限制判定，避免后台硬起必然被拒的请求。
            if (!fromJob && !AppForeground.isForeground) {
                AndroidAgentLogger.info("Keepalive start skipped: app is in background, keepalive deferred")
                runCatching { AgentKeepaliveScheduler.schedule(context) }
                    .onFailure { error ->
                        AndroidAgentLogger.warn(
                            "Keepalive deferred scheduling failed: type=${error.safeLogType()}"
                        )
                    }
                return
            }
            start(context, ACTION_ENABLE_PERSISTENT)
        }

        fun disable(context: Context) = start(context, ACTION_DISABLE_PERSISTENT)

        fun startSession(context: Context) = start(context, ACTION_START_SESSION)

        fun stopSession(context: Context) = start(context, ACTION_STOP_SESSION)

        /**
         * 统一入口：失败必须可见，且要有兜底。
         *
         * 后台被拒时回退成 stopService —— 对 disable/stopSession 来说这正是想要的结果，
         * 对 enable 来说服务本来也没起来，不留下「以为开了其实没开」的假象（日志会写明）。
         */
        private fun start(context: Context, action: String) {
            val intent = Intent(context, AgentKeepaliveService::class.java).setAction(action)
            runCatching { context.startForegroundService(intent) }
                .onFailure { error ->
                    AndroidAgentLogger.warn(
                        "Keepalive start rejected: action=$action type=${error.safeLogType()}"
                    )
                    runCatching { context.stopService(intent) }
                        .onFailure { stopError ->
                            AndroidAgentLogger.warn(
                                "Keepalive stop fallback failed: type=${stopError.safeLogType()}"
                            )
                        }
                }
        }

        /**
         * 服务是否在跑。
         *
         * `getRunningServices` 在 Android O+ 只返回调用方自己进程的服务，
         * 对「查自己应用的服务」这个用途恰好有效，也不需要额外权限。
         */
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(ActivityManager::class.java)
            if (manager == null) {
                AndroidAgentLogger.warn("Keepalive running check unavailable: ActivityManager missing")
                return false
            }
            val services = runCatching { manager.getRunningServices(Int.MAX_VALUE) }
                .onFailure { error ->
                    AndroidAgentLogger.warn(
                        "Keepalive running check failed: type=${error.safeLogType()}"
                    )
                }
                .getOrNull()
                ?: return false
            return services.any { it.service.className == AgentKeepaliveService::class.java.name }
        }
    }
}
