package io.github.mangi.eta.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.io.File
import java.io.IOException

/**
 * 诊断包定期自动导出：每周一次把诊断包落到「诊断包备份」目录，省得每次手动点。
 *
 * 只用不确定闹钟，没有常驻进程；到点唤醒一次，导完再排下一次。
 * 只保留最近 [KEEP_FILES] 个，免得备份目录越堆越大。
 *
 * 目标目录必须**先验证可写再写**：公共存储下的 `ETA` 属主是别的应用
 * （实测 `drwxrws--- u0_a258 media_rw`），Eta 进程不在 media_rw 组、也没有
 * `MANAGE_EXTERNAL_STORAGE`，直接写会抛 FileNotFoundException。
 * 早先的实现直接 writeBytes，失败后又按「上次成功导出时间」重排下一次，
 * 而那个时间永远是旧的，于是每次开机/唤醒都立刻重试——日志里累计 573 条
 * `diag.auto.fail`，把 ERROR 等级刷成噪音，真正的错误反而被埋掉。
 *
 * 现在的策略（四层，按「先治量产机制再治取证」排序）：
 * 1. 到期基准取 `maxOf(上次成功导出时间, 上次尝试时间)`，且退避状态**落盘跨进程存活**；
 *    旧实现的 `lastAttemptAt` / `consecutiveFailures` / `lastFailureSignature` 只是内存变量，
 *    进程一重启就归零，于是每个进程至少刷一条失败日志——这才是 573 条的量产机制。
 * 2. 主目录不可写就回落到应用专属外部目录（不需要任何权限），并记一条日志说明原因；
 *    目录判定可写之后，真正写这一下仍失败也会回退再试一次（用户删目录、换卡、FUSE 抖动）。
 * 3. 失败按指数退避重排（1 天起，最长 7 天），不再每次唤醒都重试。
 * 4. 同一失败原因只记一次，另有 24 小时节流兜底；失败日志正文带目标路径、真实原因与目录状态，
 *    否则分不清是 EACCES 还是 ENOENT——那正是上一轮排查最大的取证损失。
 */
internal object DiagnosticsAutoExport {

    private const val REQUEST_CODE = 0x0E7B
    private const val INTERVAL_MS = 7 * 24 * 3_600_000L
    private const val MIN_DELAY_MS = 60_000L
    private const val KEEP_FILES = 4
    private const val FILE_PREFIX = "Eta-诊断包-"

    /** 失败退避起点：1 天。失败一次就往后推一天，避免每次唤醒都重试。 */
    private const val RETRY_BACKOFF_BASE_MS = 24 * 3_600_000L

    /** 退避上限：不超过正常周期，最多就是「下次按周来」。 */
    private const val RETRY_BACKOFF_MAX_MS = INTERVAL_MS

    /** 自动导出单独放一个子目录：只在这里滚动保留，绝不碰你手动存下的诊断包。 */
    private const val BACKUP_DIR = SharedStorage.DIAGNOSTICS_AUTO_DIR

    private const val FALLBACK_DIR_NAME = "诊断包备份"

    /** 退避状态的落盘位置：这里存的是内部运行状态，不占用户设置项，也不进设置页。 */
    private const val PREFS = "diagnostics_auto_export"
    private const val KEY_LAST_ATTEMPT_AT = "lastAttemptAt"
    private const val KEY_CONSECUTIVE_FAILURES = "consecutiveFailures"
    private const val KEY_LAST_FAILURE_SIGNATURE = "lastFailureSignature"

    /** 失败日志最短间隔：24 小时。比「同签名只记一次」宽，用来兜住签名来回抖的情况。 */
    private const val FAILURE_LOG_WINDOW_MS = 24 * 3_600_000L

    /** 写进日志的失败原因最长长度：够看出 errno 与路径，又不至于把整段异常刷进日志页。 */
    private const val REASON_MAX_CHARS = 160

    /** 失败日志节流器；LogThrottle 走单调时钟，不受系统对时影响。 */
    private val failureLogThrottle = LogThrottle()

    /**
     * 跨进程存活的退避状态。
     *
     * 全部有明确缺省值：读不到等价于「从没试过」，最坏退化成旧的重排行为，
     * 绝不会因为一份坏掉的偏好文件让自动导出永久停摆。
     */
    private data class ExportState(
        val lastAttemptAt: Long = 0L,
        val consecutiveFailures: Int = 0,
        val lastFailureSignature: String = "",
    )

    /** 按「上次成功导出」与「上次尝试」里较新的那个排下一次；从没试过就先在 1 分钟后补一次。 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val due = nextDueAt(app, readState(app), now)
        runCatching {
            app.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(due, now + MIN_DELAY_MS), pending(app))
        }.onFailure { error -> logAlarmProblem("排期", error) }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(pending(app)) }
            .onFailure { error -> logAlarmProblem("取消", error) }
    }

    /** 导出一次并重排下一次；失败只留一条日志，不弹通知。 */
    suspend fun runOnce(context: Context) {
        val app = context.applicationContext
        // 先把「这次试过了」落盘：采集中途被系统杀掉，基准也已经被推进，
        // 不会出现「进程一起来就判定到期」的老循环。
        markAttempt(app)
        // 记住这次实际选定的落点：失败时要报的「目标路径」必须是真落点，而不是首选目录。
        var activeDir: File? = null
        runCatching {
            // 先定落点再采集：目录压根不可写时，没必要白花几十秒去打包。
            val dir = writableDirectory(app)
            activeDir = dir
            val bytes = DiagnosticsBundle.collect(app)
            val archive = writeArchive(app, dir, bytes)
            clearFailures(app)
            val placedDir = archive.parentFile ?: dir
            EtaTrace.log("diag.auto", "已自动导出 ${archive.name} ${bytes.size} 字节 → ${placedDir.absolutePath}")
            prune(placedDir, archive.name)
        }.onFailure { error -> recordFailure(app, error, activeDir ?: backupDir(app)) }
        scheduleAfterRun(app)
    }

    /**
     * 下一次该在什么时候唤醒。
     *
     * 基准取 `maxOf(latestExportTime(), lastAttemptAt)`：只按成功时间算时，一次失败会让
     * 「上次成功」依然停在一周前，于是每次唤醒都判定已到期 → 60 秒最小延迟 + 约 50 秒采集，
     * 就是实测 107–131 秒一条失败日志的指纹。失败时沿用当前退避档，和 [scheduleAfterRun] 一致。
     */
    private fun nextDueAt(context: Context, state: ExportState, now: Long): Long {
        // 锚点必须夹在 [0, now]：用户把系统时间往前调过再调回来时，一个未来的时间戳
        // 会把 due 推到几十天后，表现成「自动导出再也不触发」——同样是永不导出，只是另一种死法。
        val anchor = maxOf(latestExportTime(context), state.lastAttemptAt).coerceAtMost(now).coerceAtLeast(0L)
        if (anchor <= 0L) return now + MIN_DELAY_MS
        val delayMs = if (state.consecutiveFailures > 0) retryDelayMs(state.consecutiveFailures) else INTERVAL_MS
        return anchor + delayMs
    }

    /** 记下这次尝试的时间：跨进程的「别马上又来一次」全靠这一个值。 */
    private fun markAttempt(context: Context) {
        val state = readState(context)
        writeState(context, state.copy(lastAttemptAt = System.currentTimeMillis()))
    }

    /** 成功一次清零；不清 lastAttemptAt——它同时也是下次排期的基准。 */
    private fun clearFailures(context: Context) {
        val state = readState(context)
        if (state.consecutiveFailures == 0 && state.lastFailureSignature.isEmpty()) return
        writeState(context, state.copy(consecutiveFailures = 0, lastFailureSignature = ""))
    }

    /**
     * 记录一次失败：先落盘状态，再决定要不要出日志。
     *
     * 两条闸门叠着用：
     * 1. 同一签名（异常类型 + 目标目录）已经记过就不再记——这条靠落盘，跨进程有效；
     * 2. 签名来回抖也别刷屏，24 小时内最多一条。
     */
    private fun recordFailure(context: Context, error: Throwable, target: File) {
        val previous = readState(context)
        val dir = target
        val signature = "${error.safeLogType()}@${dir.absolutePath}"
        val failures = previous.consecutiveFailures + 1
        writeState(context, previous.copy(consecutiveFailures = failures, lastFailureSignature = signature))
        if (signature == previous.lastFailureSignature) return
        // 失败是 WARN 不是 ERROR：导出失败不影响使用，也不该淹没真正的运行错误。
        // 正文必须带路径、真实原因和目录状态：只有异常类名的话，EACCES 和 ENOENT 长得一模一样。
        logThrottled("diag.auto.fail:$signature", AppLogLevel.WARN) {
            "自动导出失败：${error.safeLogType()}（目标 ${dir.absolutePath}" +
                "，原因 ${error.reasonHint()}" +
                "，目录存在=${dir.exists()} 可写=${dir.canWrite()}" +
                "，连续 $failures 次，按退避 ${retryDelayMs(failures) / 3_600_000L} 小时后重试）"
        }
    }

    /** 失败走退避，成功按正常周期；都从「现在」起算，不拿旧文件时间当基准。 */
    private fun scheduleAfterRun(context: Context) {
        val state = readState(context)
        val delayMs = if (state.consecutiveFailures > 0) retryDelayMs(state.consecutiveFailures) else INTERVAL_MS
        val due = System.currentTimeMillis() + delayMs
        runCatching {
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending(context))
        }.onFailure { error -> logAlarmProblem("重排", error) }
    }

    /** internal 只为纯 JVM 单测可及（Robolectric 在 arm64 上被静默跳过，skipped 不算覆盖）；语义不变。 */
    internal fun retryDelayMs(failures: Int): Long {
        val shift = (failures.coerceAtLeast(1) - 1).coerceAtMost(3)
        return (RETRY_BACKOFF_BASE_MS shl shift).coerceAtMost(RETRY_BACKOFF_MAX_MS)
    }

    /**
     * 把诊断包落到 [dir]；写这一下失败就回退到应用专属外部目录再写一次。
     *
     * 目录已经过 [ensureWritable] 判定，但判定与真正落盘之间照样会变：用户刚删了目录、
     * 换了存储卡、FUSE 抖动、空间写满。旧实现只有一次机会，失败就整周期作废，
     * 于是永远导不出来。只有回退也失败才算真失败，那时把次因抛回失败分支（首因挂 suppressed）。
     */
    private fun writeArchive(context: Context, dir: File, bytes: ByteArray): File {
        val name = DiagnosticsBundle.defaultFileName()
        val primary = File(dir, name)
        try {
            primary.writeBytes(bytes)
            return primary
        } catch (error: IOException) {
            return writeFallback(context, name, bytes, error)
        } catch (error: SecurityException) {
            // 分区存储也会以 SecurityException 的形式拒绝写入，同样只给一次回退机会。
            return writeFallback(context, name, bytes, error)
        }
    }

    /**
     * 主目录写失败后的唯一一次回退。
     *
     * 回落目录也不可写时把首因原样抛回失败分支（不包装），这样失败签名与日志里的异常类型
     * 仍是真实的 FileNotFoundException / EACCES 一类，而不是被降级成笼统的 IOException。
     */
    private fun writeFallback(context: Context, name: String, bytes: ByteArray, cause: Exception): File {
        val fallback = fallbackDir(context)
        if (!ensureWritable(fallback)) throw cause
        val alternate = File(fallback, name)
        try {
            alternate.writeBytes(bytes)
        } catch (second: IOException) {
            second.addSuppressed(cause)
            throw second
        } catch (second: SecurityException) {
            second.addSuppressed(cause)
            throw second
        }
        // 回退落盘必须说出来：否则「目录里没文件」和「写到了别处」在日志里长得一样。
        EtaTrace.log(
            "diag.auto",
            "主目录写入失败（${cause.safeLogType()}：${cause.reasonHint()}），" +
                "已回退落盘 ${alternate.parentFile?.absolutePath}",
        )
        return alternate
    }

    /**
     * 首选公共目录，不可写就回落到应用专属外部目录——不需要任何存储权限。
     *
     * 回落必须留痕（文件头注释承诺过、旧实现只默默换了目录）：否则「文件写在别处」与
     * 「压根没导出」在日志里长得一样。原因串带 errno 级消息，才能分清 EACCES 与 ENOENT。
     */
    private fun writableDirectory(context: Context): File {
        val preferred = backupDir(context)
        val preferredProblem = probeWritable(preferred)
        if (preferredProblem == null) return preferred
        val fallback = fallbackDir(context)
        val fallbackProblem = probeWritable(fallback)
        if (fallbackProblem == null) {
            logThrottled("diag.auto.dir", AppLogLevel.INFO) {
                "首选目录不可写（$preferredProblem），本次落盘改用回落目录 ${fallback.absolutePath}"
            }
            return fallback
        }
        error(
            "没有可写的诊断包目录：" +
                "${preferred.absolutePath}（$preferredProblem），${fallback.absolutePath}（$fallbackProblem）",
        )
    }

    private fun backupDir(context: Context): File = File(BACKUP_DIR)

    /** 回落目录：应用专属外部目录，分区存储下也永远可写。 */
    private fun fallbackDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FALLBACK_DIR_NAME)

    /**
     * 建目录 + 写探针：分区存储下 FUSE 会拖到真正写那一刻才拒绝，只看 canWrite 不够。
     *
     * 返回 null 表示可写，否则返回可读原因。旧版这里是 `runCatching { … }.getOrDefault(false)`，
     * 把探针抛出的 errno 消息一起吞掉，失败日志只剩「没有可写的诊断包目录」——
     * 与这一轮要治的「EACCES / ENOENT 分不清」是同一个病根。
     */
    private fun probeWritable(dir: File): String? = try {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            "目录不存在且创建失败 ${dir.absolutePath}"
        } else if (!dir.isDirectory) {
            "路径不是目录 ${dir.absolutePath}"
        } else {
            val probe = File(dir, ".eta-write-probe")
            probe.writeBytes(ByteArray(0))
            probe.delete()
            null
        }
    } catch (error: IOException) {
        "${error.safeLogType()}：${error.reasonHint()}"
    } catch (error: SecurityException) {
        "${error.safeLogType()}：${error.reasonHint()}"
    }

    /** 只要布尔结论的调用点：可写 == 探针没有返回原因。 */
    private fun ensureWritable(dir: File): Boolean = probeWritable(dir) == null

    /** 上次成功导出的时间：主目录与回落目录都算，取较新的那份。 */
    private fun latestExportTime(context: Context): Long {
        val scan = runCatching {
            val dirs = listOf(backupDir(context), fallbackDir(context))
            dirs.flatMap { listArchives(it) }.maxOfOrNull { it.lastModified() } ?: 0L
        }
        // 扫描失败不能静默：读不到时间会退化成「1 分钟后再试」，得有据可查。
        scan.onFailure { error ->
            logThrottled("diag.auto.scan", AppLogLevel.WARN) {
                "读取上次导出时间失败：${error.safeLogType()}（${error.reasonHint()}），按未导出处理"
            }
        }
        return scan.getOrDefault(0L)
    }

    private fun listArchives(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".zip") }
            ?: emptyList()

    private fun prune(dir: File, keepName: String) {
        val pruned = runCatching {
            val olds = listArchives(dir).sortedByDescending { it.lastModified() }
            var undeleted = 0
            olds.drop(KEEP_FILES).forEach { file ->
                if (file.name != keepName && !file.delete()) undeleted += 1
            }
            undeleted
        }
        pruned.onFailure { error ->
            logThrottled("diag.auto.prune", AppLogLevel.WARN) {
                "诊断包清理失败：${error.safeLogType()}（${error.reasonHint()}）"
            }
        }
        val undeleted = pruned.getOrDefault(0)
        if (undeleted > 0) {
            // File.delete 返回 false 不抛异常，不主动查就等于静默失败。
            logThrottled("diag.auto.prune", AppLogLevel.WARN) {
                "诊断包清理有 $undeleted 个旧文件删不掉（${dir.absolutePath}）"
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读退避状态；读不出来就用全缺省，不让一份坏偏好把导出彻底卡死。 */
    private fun readState(context: Context): ExportState {
        val store = try {
            prefs(context)
        } catch (error: RuntimeException) {
            logStateProblem(error.safeLogType())
            return ExportState()
        }
        return try {
            ExportState(
                lastAttemptAt = store.getLong(KEY_LAST_ATTEMPT_AT, 0L).coerceAtLeast(0L),
                consecutiveFailures = store.getInt(KEY_CONSECUTIVE_FAILURES, 0).coerceAtLeast(0),
                lastFailureSignature = store.getString(KEY_LAST_FAILURE_SIGNATURE, "").orEmpty(),
            )
        } catch (error: RuntimeException) {
            // 类型不匹配（旧版本写过别的类型）会抛 ClassCastException，同样退回缺省。
            logStateProblem(error.safeLogType())
            ExportState()
        }
    }

    /**
     * 写退避状态。
     *
     * 用 commit 而不是 apply：这几处写盘都发生在闹钟广播的后台线程与 EtaApp 的 IO 派发器上，
     * 不碰主线程；而 apply 的异步落盘在进程被立刻杀掉时会丢，丢掉的后果正是「重启后又判定到期」——
     * 也就是这一轮要治的那个循环。
     */
    private fun writeState(context: Context, state: ExportState) {
        val store = try {
            prefs(context)
        } catch (error: RuntimeException) {
            logStateProblem(error.safeLogType())
            return
        }
        val written = runCatching {
            store.edit()
                .putLong(KEY_LAST_ATTEMPT_AT, state.lastAttemptAt)
                .putInt(KEY_CONSECUTIVE_FAILURES, state.consecutiveFailures)
                .putString(KEY_LAST_FAILURE_SIGNATURE, state.lastFailureSignature)
                .commit()
        }.getOrDefault(false)
        // 写不进去只影响「下次会不会立刻重试」，不影响导出本身，记一条 WARN 就够。
        if (!written) logStateProblem("commit=false")
    }

    private fun logStateProblem(reason: String) {
        logThrottled("diag.auto.state", AppLogLevel.WARN) { "退避状态读写失败：$reason，按缺省值排期" }
    }

    /** 闹钟排期失败会直接让自动导出停摆，必须留痕（同一动作 24 小时一条）。 */
    private fun logAlarmProblem(action: String, error: Throwable) {
        logThrottled("diag.auto.alarm.$action", AppLogLevel.WARN) {
            "自动导出闹钟$action 失败：${error.safeLogType()}（${error.reasonHint()}），本次排期未生效"
        }
    }

    /** 节流日志：同一个 key 在 [FAILURE_LOG_WINDOW_MS] 内只出一条，命中时连消息串都不拼。 */
    private fun logThrottled(area: String, level: AppLogLevel, message: () -> String) {
        if (!failureLogThrottle.shouldLog(area, FAILURE_LOG_WINDOW_MS)) return
        EtaTrace.log(area, message(), level)
    }

    /**
     * 失败原因片段。
     *
     * LogSafety 的约定是「异常消息不进日志」，但文件写入失败只看类型根本分不出 EACCES 与 ENOENT
     * （两者都被 java.io 统一成 FileNotFoundException，消息就是路径本身），这正是上一轮
     * 573 条日志刷了三周也定位不到原因的关键。这里只取 errno/路径级别的短原因，压成单行并截断，
     * 不引入会话正文、载荷或堆栈。
     */
    private fun Throwable.reasonHint(): String {
        val raw = message.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace("  ", " ")
        if (raw.isEmpty()) return "无消息"
        return raw.take(REASON_MAX_CHARS)
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, DiagnosticsAutoExportReceiver::class.java)
            .setAction(DiagnosticsAutoExportReceiver.ACTION_EXPORT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
