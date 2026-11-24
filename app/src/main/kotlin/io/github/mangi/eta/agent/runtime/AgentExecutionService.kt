package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false

    /**
     * 通知里的 PendingIntent 缓存。
     *
     * 每次状态刷新都重新 `Intent(...)` + `PendingIntent.getService` 会多一遍 Intent 序列化分配；
     * 2026-11-22 的崩溃栈点正落在 `PendingIntent.getService → Intent.prepareToLeaveProcess`。
     * 这里只在「动作变了」时重建，`stop` 动作恒定只建一次。
     */
    private var stopPendingIntent: PendingIntent? = null
    private var openPendingIntent: PendingIntent? = null
    private var openPendingAction: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foregroundActive = true
        } catch (failure: Throwable) {
            // 捕获 Throwable 而非仅 RuntimeException：OOM（OutOfMemoryError）在通知构建/
            // startForeground 时也会抛，若只捕 RuntimeException 会让前台执行服务直接崩溃。
            // 降级为拒绝启动前台，并收回本次已登记的任务，避免半启动状态泄漏。
            // ThreadDeath（线程 stop 信号）不可恢复，必须重新抛出交给 VM 处理。
            if (failure is ThreadDeath) throw failure
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTasks()
        } else {
            ensureForeground()
            refreshNotification()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            } catch (failure: Throwable) {
                // 堆接近上限时连通知都构建不出来（11-22 栈点就在 notification() 里）。
                // 这一次刷新降级跳过，让 run 继续跑并把真实错误留给 run 级日志，
                // 而不是把主线程打死、连失败现场都留不下。ThreadDeath 必须原样抛出。
                if (failure is ThreadDeath) throw failure
                AndroidAgentLogger.warnThrottled("execution_notification_refresh_failed") {
                    "Execution notification refresh failed: type=${failure.safeLogType()}"
                }
            }
        }
    }

    /**
     * 打开入口的 PendingIntent：只在目标动作变化时重建。
     *
     * 这个 Intent 里**不放任何 extras**：需要让界面知道上下文时只传 id 或文件路径，
     * 由接收方从存储读（大 payload 走 Intent/Bundle 会在序列化时再复制一份，
     * 正是 11-22 崩在 `Intent.prepareToLeaveProcess` 的那类开销）。
     */
    private fun openPendingIntent(linuxBusy: Boolean): PendingIntent? {
        val action = if (linuxBusy) MainActivity.ACTION_OPEN_LINUX_ENVIRONMENT else null
        openPendingIntent?.let { cached -> if (openPendingAction == action) return cached }
        val intent = Intent(this, MainActivity::class.java).setAction(action)
        val created = try {
            PendingIntent.getActivity(
                this, if (action == null) 0 else 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warnThrottled("execution_pending_intent_open_failed") {
                "Execution notification open PendingIntent failed: type=${failure.safeLogType()}"
            }
            null
        }
        openPendingIntent = created
        openPendingAction = action
        return created
    }

    /** 停止动作恒定，只建一次；分配失败时下一次刷新重试。 */
    private fun stopPendingIntent(): PendingIntent? {
        stopPendingIntent?.let { return it }
        val created = try {
            PendingIntent.getService(
                this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warnThrottled("execution_pending_intent_stop_failed") {
                "Execution notification stop PendingIntent failed: type=${failure.safeLogType()}"
            }
            null
        }
        stopPendingIntent = created
        return created
    }

    private fun notification(): Notification {
        val status = runStatus
        // 正在装 Linux 工具时，点击通知直接落到 Linux 工具环境页，而不是停在首页。
        val open = openPendingIntent(linuxBusy = io.github.mangi.eta.agent.terminal.LinuxInstallRunner.busy)
        val stop = stopPendingIntent()
        val taskCount = leases.count()
        val contentText = status?.localizedText(resources)
            ?: resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4285F4.toInt())
            .setContentTitle(getString(R.string.execution_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        open?.let { builder.setContentIntent(it) }
        stop?.let { stopIntent ->
            builder.addAction(
                Notification.Action.Builder(null, getString(R.string.execution_stop), stopIntent).build()
            )
        }
        // Agent run 期间把运行总卡片提升为 Live Update：状态栏胶囊、锁屏与流体云都读这张卡。
        if (status != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // 不用无进度的 ProgressStyle：那会渲染出一条空进度白条。改用 BigTextStyle 承载状态文本。
            builder
                .setStyle(Notification.BigTextStyle().bigText(contentText))
                .setShortCriticalText(status.shortText())
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        }
        return builder.build()
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer -> getString(R.string.execution_chip_answering)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var publishedStatusText: String? = null

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?) {
            mainHandler.post {
                runStatus = status
                val text = status?.localizedText(context.resources)
                if (text == publishedStatusText) return@post
                publishedStatusText = text
                instance?.takeIf { it.foregroundActive }?.refreshNotification()
            }
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            onStop: () -> Unit,
        ): Boolean {
            if (instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, onStop)) return true
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
