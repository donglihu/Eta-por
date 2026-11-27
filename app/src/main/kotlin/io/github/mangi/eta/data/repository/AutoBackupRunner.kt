package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AutoBackupInterval
import io.github.mangi.eta.data.model.AutoBackupKeepCount
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 自动备份的结果，供界面提示与日志使用。 */
internal sealed interface AutoBackupResult {
    data class Done(val fileName: String, val conversationCount: Int, val kept: Int) : AutoBackupResult
    data class Skipped(val reason: String) : AutoBackupResult
    data class Failed(val message: String) : AutoBackupResult
}

/**
 * 备份目录的判定结果。
 *
 * 必须区分「用户填的路径」与「实际落盘路径」：旧实现只会静默回落到默认目录，
 * 用户填了 `/storage/emulated/0/ETA` 会以为设置成功，实际一直写在别处。
 */
internal sealed interface BackupDirectoryCheck {
    /** 目录确实可用；[created] 表示这次判定顺手新建了它。 */
    data class Usable(val path: String, val created: Boolean) : BackupDirectoryCheck

    /** 目录不可用；[reason] 给原因，[suggestion] 给可直接照做的替代方案。 */
    data class Unusable(val path: String, val reason: String, val suggestion: String) : BackupDirectoryCheck
}

/**
 * 定期自动备份：把 Eta 数据导出到共享目录的 `Eta备份/` 下，并按保留份数删掉最旧的。
 *
 * 复用「数据备份」页同一份导出实现（[EtaBackupRepository.export]），产物与手动导出完全一致，
 * 需要还原时直接走「数据备份 → 导入」即可。
 *
 * 省电策略：先比较一份轻量指纹（会话数 / 消息数 / 用量记录数），内容没变就只推进检查时间、
 * 不写文件，避免一天一次的白写盘。到点唤醒由 [io.github.mangi.eta.agent.automation.AutoBackupAlarm] 负责。
 *
 * 可靠性约定（这三条是这一版补的）：
 * 1. 全流程串行：闹钟、应用启动、回前台、手动「立即备份一次」都走同一把锁，不会两个协程各写一份；
 * 2. 先写 `.part` 再改名：导出中途被杀只会留下临时文件并当场清掉，不会出现半截 `.eta-backup.json`
 *    占着保留名额、或被当成「最近一次备份」；
 * 3. 失败落状态：失败原因与时间写进设置，界面能直接显示「为什么没备份成」，成功时清空。
 */
internal object AutoBackupRunner {

    const val DIR_NAME = "Eta备份"
    private const val FILE_PREFIX = "Eta-自动备份-"
    private const val FILE_SUFFIX = ".eta-backup.json"
    /** 全量包沿用旧名字前缀，方便和以前的备份放在一起看。 */
    private const val FULL_PREFIX = "Eta-全量备份-"
    private const val FULL_SUFFIX = EtaFullBackupArchive.FULL_SUFFIX
    private const val TEMP_SUFFIX = ".part"

    /** 同一时刻只允许一次备份在跑。 */
    private val mutex = Mutex()

    /**
     * 默认备份目录：应用专属外部目录 `/storage/emulated/0/Android/data/<pkg>/files/Eta备份`。
     *
     * 旧默认值是公共存储根目录下的 `/storage/emulated/0/ETA`。Android 11 起分区存储禁止应用
     * 在公共根目录新建目录，于是每次备份都以「无法创建备份目录」失败；应用专属目录不需要
     * 任何存储权限，也不需要 MANAGE_EXTERNAL_STORAGE。
     */
    fun defaultDirectory(): File {
        val context = SettingsDataStore.appContextOrNull()
            ?: return File(Environment.getExternalStorageDirectory(), DIR_NAME)
        val externalRoot = context.getExternalFilesDir(null) ?: context.filesDir
        return File(externalRoot, DIR_NAME)
    }

    /** 相对路径的解析根：优先应用专属外部目录，拿不到上下文才退回公共存储根。 */
    private fun relativeRoot(): File {
        val context = SettingsDataStore.appContextOrNull()
        return context?.getExternalFilesDir(null) ?: context?.filesDir
            ?: Environment.getExternalStorageDirectory()
    }

    /**
     * 备份目录：自定义路径优先——绝对路径直接用，相对路径按应用专属外部目录解析；为空时用默认目录。
     *
     * 关键：配置的目录不可写时自动回落到默认目录。旧配置 `/storage/emulated/0/ETA/` 在分区存储下
     * 永远不可写，如果只在写入时回退、读列表还用配置路径，界面就会「备份成功但列表永远为空」。
     * 这里把解析收敛到一处，读、写、展示三边看到的是同一个真实目录。
     */
    /**
     * 用户配置真正指向的目录（不做可写性回落）。
     *
     * 界面要用它告诉用户「你填的是这个」，[directory] 用它告诉写入方「实际写这个」，
     * 两者不一致时就是需要提示的场景，而不是悄悄覆盖掉用户的输入。
     */
    fun configuredDirectory(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): File {
        val trimmed = customDirectory.trim()
        return when {
            trimmed.isEmpty() -> defaultDirectory()
            trimmed.startsWith("/") -> File(trimmed)
            else -> File(relativeRoot(), trimmed)
        }
    }

    /**
     * 备份目录：自定义路径优先——绝对路径直接用，相对路径按应用专属外部目录解析；为空时用默认目录。
     *
     * 关键：配置的目录不可写时自动回落到默认目录。旧配置 `/storage/emulated/0/ETA/` 在分区存储下
     * 永远不可写，如果只在写入时回退、读列表还用配置路径，界面就会「备份成功但列表永远为空」。
     * 这里把解析收敛到一处，读、写、展示三边看到的是同一个真实目录。
     *
     * 注意：回落只解决「还能不能备份」，不解决「用户以为设好了」。设置页必须在保存前用
     * [checkDirectory] 明确告知不可写，不要靠这个回落吞掉用户的输入。
     */
    fun directory(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): File {
        val preferred = configuredDirectory(customDirectory)
        return if (canUseDirectory(preferred)) preferred else defaultDirectory()
    }

    /** 配置路径是否真的生效（false 说明已被回落到默认目录）。供界面打标用，不产生写操作。 */
    fun isConfiguredDirectoryEffective(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): Boolean {
        if (customDirectory.trim().isEmpty()) return true
        return configuredDirectory(customDirectory).absolutePath == directory(customDirectory).absolutePath
    }

    /**
     * 保存设置前的真实判定：会建目录、会写探针文件，必须在 IO 线程调用。
     *
     * 只靠 [File.canWrite] 不够：分区存储下 FUSE 会在真正写的那一刻才拒绝，
     * 所以这里补一次「建目录 + 写 0 字节探针 + 立刻删除」的端到端探测，
     * 让设置页能在保存前就给出确定结论，而不是保存完再在备份时失败。
     */
    fun checkDirectory(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): BackupDirectoryCheck {
        val target = configuredDirectory(customDirectory)
        val path = target.absolutePath
        val suggestion = "留空使用默认目录 " + defaultDirectory().absolutePath + "（应用专属目录，不需要任何存储权限）"

        if (target.exists() && !target.isDirectory) {
            return BackupDirectoryCheck.Unusable(path, "该路径是一个文件，不是文件夹", suggestion)
        }
        val created = !target.exists()
        if (created && !target.mkdirs() && !target.isDirectory) {
            val parent = target.parentFile
            val reason = when {
                parent == null -> "路径不合法，无法解析上级目录"
                !parent.exists() -> "上级目录不存在：" + parent.absolutePath
                !parent.canWrite() -> "上级目录不可写：" + parent.absolutePath +
                    "（Android 11+ 分区存储不允许应用在公共存储根目录新建目录）"
                else -> "创建目录被系统拒绝：" + path
            }
            return BackupDirectoryCheck.Unusable(path, reason, suggestion)
        }
        if (!target.canWrite()) {
            // 不能用 canWrite() 直接判定失败：分区存储下 FUSE 的 access(W_OK) 与实际 open(O_CREAT)
            // 不是同一套判定（授权刚打开时尤其明显），这里以探针写入为准，canWrite 只用于补充原因。
            val probe = File(target, ".eta-write-probe-" + System.nanoTime())
            val writable = runCatching {
                probe.outputStream().use { it.write(0) }
                probe.length() == 0L
            }.getOrDefault(false)
            probe.delete()
            return if (writable) {
                BackupDirectoryCheck.Usable(path, created)
            } else {
                BackupDirectoryCheck.Unusable(
                    path,
                    "目录存在但写入被拒绝（多为其他应用在公共存储根目录创建的文件夹，权限 0770；" +
                        "可改用「选择文件夹」授权，或开启所有文件访问权限）",
                    suggestion,
                )
            }
        }
        val probe = File(target, ".eta-write-probe-" + System.nanoTime())
        val writable = runCatching {
            probe.outputStream().use { it.write(0) }
            probe.length() == 0L
        }.getOrDefault(false)
        probe.delete()
        if (!writable) {
            return BackupDirectoryCheck.Unusable(path, "探针写入失败，目录不接受本应用的写入", suggestion)
        }
        return BackupDirectoryCheck.Usable(path, created)
    }

    /**
     * 只判断可用性、不建目录：这个函数会被界面组合读取（展示路径、列备份列表），
     * 不该在主线程产生写操作。真正的 mkdir 交给 [resolveWritableDirectory]。
     *
     * 注意：**不能用 canWrite() 否决已存在的目录**。分区存储下 FUSE 的 access(W_OK) 与实际
     * open(O_CREAT) 不是同一套判定，canWrite 为 false 时写入往往照样成功；用 canWrite 当闸门
     * 会让用户填了完全可写的目录（例如 /storage/emulated/0/ETA）也被判不可用并静默回落默认目录。
     * 真实可写性交给 [checkDirectory] 的探针与写入时的失败提示。
     */
    private fun canUseDirectory(dir: File): Boolean =
        dir.isDirectory || (!dir.exists() && dir.parentFile?.let { it.isDirectory && it.canWrite() } == true)

    private fun isWritableDirectory(dir: File): Boolean =
        dir.isDirectory || dir.mkdirs() || dir.isDirectory

    /**
     * 真正用来落盘的目录：解析后的目录再兜一层应用内部目录。
     * 返回 null 表示连应用内部目录都不可写，调用点据此给出可操作的失败提示。
     */
    fun resolveWritableDirectory(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): File? {
        val candidates = buildList {
            add(directory(customDirectory))
            SettingsDataStore.appContextOrNull()?.let { context -> add(File(context.filesDir, DIR_NAME)) }
        }.distinct()
        return candidates.firstOrNull { isWritableDirectory(it) }
    }

    /**
     * 当前生效的落点：SAF 授权目录优先，其次是自定义/默认 File 目录，最后兜到应用内部目录。
     * 授权目录失效（用户撤销授权、文件夹被删）时返回 null，让调用点明确报错，不静默换目录。
     */
    fun storage(
        customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory(),
        treeUri: String = SettingsDataStore.cachedAutoBackupTreeUri(),
    ): BackupStorage? {
        if (treeUri.isNotBlank()) return safStorage(treeUri)
        return resolveWritableDirectory(customDirectory)?.let { FileBackupStorage(it) }
    }

    /** SAF 授权目录落点；未授权、URI 不合法或授权已失效时返回 null。 */
    fun safStorage(
        treeUri: String,
        context: Context? = SettingsDataStore.appContextOrNull(),
    ): BackupStorage? {
        if (treeUri.isBlank() || context == null) return null
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        val storage = SafBackupStorage(context, uri, safTreeDisplayName(context, uri))
        return if (storage.isAccessible()) storage else null
    }

    private fun suggestionText(): String =
        "留空使用默认目录 " + defaultDirectory().absolutePath + "（应用专属目录，不需要任何存储权限）"

    /**
     * 保存 SAF 目录前的真实判定：建探针文件写 0 字节再删掉。
     * 与 [checkDirectory] 同理——授权状态正常也可能在真正写入的那一刻被拒。
     */
    fun checkTreeDirectory(context: Context, treeUri: String): BackupDirectoryCheck {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: return BackupDirectoryCheck.Unusable(treeUri, "文件夹授权地址不合法", suggestionText())
        val label = safTreeDisplayName(context, uri).ifBlank { treeUri }
        val storage = SafBackupStorage(context, uri, label)
        if (!storage.isAccessible()) {
            return BackupDirectoryCheck.Unusable(
                label,
                "系统没有授予这个文件夹的读写权限（可能已撤销授权或文件夹被删除）",
                suggestionText(),
            )
        }
        val handle = storage.create(".eta-write-probe-" + System.nanoTime())
            ?: return BackupDirectoryCheck.Unusable(label, "无法在该文件夹里创建文件", suggestionText())
        val written = runCatching { handle.openOutput()?.use { it.write(0) } != null }.getOrDefault(false)
        handle.delete()
        if (!written) {
            return BackupDirectoryCheck.Unusable(label, "探针写入失败，文件夹不接受本应用的写入", suggestionText())
        }
        return BackupDirectoryCheck.Usable(label, created = false)
    }

    /** 已存在的自动备份条目（JSON），最新在前。 */
    fun entries(
        customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory(),
        treeUri: String = SettingsDataStore.cachedAutoBackupTreeUri(),
    ): List<BackupEntry> = storage(customDirectory, treeUri)?.entries().orEmpty()
        .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
        .sortedByDescending { it.name }

    /** 已存在的全量包条目（.eta-fullbackup.zip），最新在前。 */
    fun fullEntries(
        customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory(),
        treeUri: String = SettingsDataStore.cachedAutoBackupTreeUri(),
    ): List<BackupEntry> = storage(customDirectory, treeUri)?.entries().orEmpty()
        .filter { EtaFullBackupArchive.isFullBackup(it.name) }
        .sortedByDescending { it.name }

    /** JSON 备份 + 全量包，按文件名倒序（两套命名的时间戳格式一致，可以直接混排）。 */
    fun allEntries(
        customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory(),
        treeUri: String = SettingsDataStore.cachedAutoBackupTreeUri(),
    ): List<BackupEntry> =
        (entries(customDirectory, treeUri) + fullEntries(customDirectory, treeUri))
            .sortedByDescending { it.name }

    /**
     * 目录里真实存在的最新一份备份。
     *
     * 界面显示「最近备份」时必须用它，而不是直接用设置里记的文件名：
     * 文件可能被用户清理掉、或换过备份目录，光看记录会显示一个根本不存在的文件。
     */
    fun resolveLastEntry(
        recordedName: String,
        customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory(),
        treeUri: String = SettingsDataStore.cachedAutoBackupTreeUri(),
    ): BackupEntry? {
        val all = allEntries(customDirectory, treeUri)
        if (recordedName.isNotBlank()) {
            all.firstOrNull { it.name == recordedName }?.let { return it }
        }
        return all.firstOrNull()
    }

    /**
     * 到点且内容有变化才执行；[force] 为 true 时忽略开关、周期与指纹（手动「立即备份一次」）。
     * 任何失败都只记录日志与状态，不抛出，避免影响启动或闹钟回调。
     */
    suspend fun maybeRun(context: Context, force: Boolean = false): AutoBackupResult =
        withContext(Dispatchers.IO) {
            mutex.withLock { runLocked(context, force) }
        }

    private suspend fun runLocked(context: Context, force: Boolean): AutoBackupResult {
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
            ?: return AutoBackupResult.Skipped("设置不可读")
        if (!force && !settings.autoBackupEnabled) {
            return AutoBackupResult.Skipped("未开启自动备份")
        }
        val intervalMinutes = AutoBackupInterval.effectiveMinutes(
            settings.autoBackupIntervalMinutes,
            settings.autoBackupIntervalHours,
        )
        val intervalMs = intervalMinutes * 60_000L
        val now = System.currentTimeMillis()
        if (!force && settings.autoBackupLastAt > 0L && now - settings.autoBackupLastAt < intervalMs) {
            return AutoBackupResult.Skipped("未到备份周期")
        }
        val fingerprint = fingerprint(context)
        // 短周期（≤ 60 分钟）时每次都写一份，保证「每 10 分钟一份」不会被内容指纹挡掉。
        val alwaysWrite = intervalMinutes <= AutoBackupInterval.ALWAYS_WRITE_MAX_MINUTES
        if (!force && !alwaysWrite && fingerprint.isNotEmpty() && fingerprint == settings.autoBackupFingerprint) {
            runCatching {
                SettingsDataStore.setAutoBackupState(
                    lastAt = now,
                    fingerprint = fingerprint,
                    fileName = settings.autoBackupLastFileName,
                    error = settings.autoBackupLastError,
                    errorAt = settings.autoBackupLastErrorAt,
                )
            }
            return AutoBackupResult.Skipped("数据没有变化")
        }
        // 体积闸门：自动（闹钟/启动）触发的全量导出必须先在预算内，否则不碰 Linux 容器。
        // 历史事故：恢复旧设置后 includeLinux=true，16 GB 的 chroot 被打进 zip，
        // 广播 10 秒预算内必超时，系统 ANR 杀进程，用户看到的是「启动一会就闪退」。
        val requestedFull = io.github.mangi.eta.data.model.AutoBackupFormat.isFull(settings.autoBackupFormat) ||
            settings.autoBackupIncludeLinux
        val estimatedBytes = if (requestedFull) {
            EtaFullBackupArchive.estimateExportBytes(context, includeLinuxEnvironment = settings.autoBackupIncludeLinux)
        } else {
            0L
        }
        val gate = AutoBackupSizeGate.decide(
            requestedFull = requestedFull,
            automatic = !force,
            estimatedBytes = estimatedBytes,
        )
        val includeLinux = settings.autoBackupIncludeLinux && gate is AutoBackupSizeGate.Decision.Full
        val full = gate is AutoBackupSizeGate.Decision.Full
        val note = when (gate) {
            is AutoBackupSizeGate.Decision.Full -> ""
            is AutoBackupSizeGate.Decision.DegradeToJson ->
                "自动备份已跳过 Linux 容器：待打包体积 " + formatBytes(gate.estimatedBytes) +
                    " 超过自动上限 " + formatBytes(gate.limitBytes) +
                    "；需要连容器一起备份请手动点「立即备份一次」。"
        }
        if (note.isNotEmpty()) AndroidAgentLogger.warn("Auto backup degraded: " + note)
        return runNow(
            context = context,
            keep = AutoBackupKeepCount.normalize(settings.autoBackupKeepCount),
            fingerprint = fingerprint,
            customDirectory = settings.autoBackupDirectory,
            treeUri = settings.autoBackupTreeUri,
            full = full,
            includeLinux = includeLinux,
            note = note,
            now = now,
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }

    /** 轻量指纹：只读三个计数，不碰数据库文件本身。取不到时返回空串（空串不做跳过判断）。 */
    private suspend fun fingerprint(context: Context): String = runCatching {
        val database = EtaDatabase.get(context)
        val conversations = database.conversationDao().conversationCount()
        val messages = database.conversationDao().messageCount()
        val usage = database.tokenUsageDao().recordCount()
        "c=$conversations;m=$messages;u=$usage"
    }.getOrDefault("")

    private suspend fun runNow(
        context: Context,
        keep: Int,
        fingerprint: String,
        customDirectory: String,
        treeUri: String,
        full: Boolean,
        includeLinux: Boolean,
        note: String,
        now: Long,
    ): AutoBackupResult {
        val storage = storage(customDirectory, treeUri)
        if (storage == null) {
            val targetLabel = if (treeUri.isNotBlank()) {
                runCatching { safTreeDisplayName(context, Uri.parse(treeUri)) }
                    .getOrDefault("")
                    .ifBlank { treeUri }
            } else {
                directory(customDirectory).absolutePath
            }
            return fail(
                now,
                "无法使用备份文件夹 " + targetLabel +
                    "（授权可能已失效：请在「备份文件夹」里重新选择，或留空使用默认目录）",
            )
        }
        // 上一次导出被系统杀掉留下的临时文件，先清掉，免得越积越多。
        // 全量包的临时文件是 `Eta-全量备份-…part`，只按 JSON 前缀匹配会漏掉，白占几个 GB。
        storage.entries()
            .filter { entry ->
                entry.name.endsWith(TEMP_SUFFIX) &&
                    (entry.name.startsWith(FILE_PREFIX) || entry.name.startsWith(FULL_PREFIX))
            }
            .forEach { storage.deleteName(it.name) }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val targetName = if (full) {
            "$FULL_PREFIX$stamp$FULL_SUFFIX"
        } else {
            "$FILE_PREFIX$stamp$FILE_SUFFIX"
        }
        val tempName = targetName + TEMP_SUFFIX
        // SAF 目录里 createDocument 遇到同名会自动加 " (1)" 后缀，先删掉同名残留保证名字稳定。
        storage.deleteName(tempName)
        storage.deleteName(targetName)
        // 先记账再干活：导出可能被系统在广播预算内直接杀掉，进程连 catch/finally 都跑不到。
        // 旧实现只在成功或 catch 里推进 lastAt，被 ANR 杀掉时 lastAt 原地不动，
        // 下次启动又立刻到期，于是「启动 → 备份 → 被杀 → 再启动」无限循环。
        // 这里在真正开始导出前就把尝试时间落盘，代价是失败时也要等一个周期才重试（手动不受影响），
        // 换来的是任何死法都不会把用户锁进重启循环。
        runCatching {
            SettingsDataStore.setAutoBackupState(
                lastAt = now,
                fingerprint = fingerprint,
                fileName = targetName,
                error = note,
                errorAt = if (note.isBlank()) 0L else now,
            )
        }
        val handle = storage.create(tempName)
            ?: return fail(now, "备份文件创建失败：" + storage.label)
        // 改名成功即视为已落盘：之后任何异常都不再删文件，否则会把正式备份删掉。
        var committed = false
        return try {
            val output = handle.openOutput()
            if (output == null) {
                handle.delete()
                return fail(now, "备份文件不可写：" + storage.label)
            }
            val conversationCount = output.use { stream ->
                if (full) EtaFullBackupArchive.export(context, stream, includeLinuxEnvironment = includeLinux).conversationCount
                else EtaBackupRepository.export(context, stream).conversationCount
            }
            // 落正式名之前先 fsync：不落盘就 rename，断电时会出现「名字对、内容截断」的包。
            if (!handle.syncBeforeRename()) {
                AndroidAgentLogger.warn("Backup fsync skipped: " + storage.label)
            }
            // 落正式名之前先验一次结构。全量包是流式写的 zip，导出中途被杀会留下
            // 「没有中央目录」的残包；逻辑备份是流式 JSON，截断后同样解析不出。
            // 早先只对 full 校验，非 full 完全没校验（2026-11-26 审核）。
            if (full) {
                val check = handle.verifyFullBackup(context)
                if (!check.ok) {
                    AndroidAgentLogger.warn("Full backup incomplete: entries=" + check.entries + " bytes=" + check.bytes)
                    handle.delete()
                    return fail(now, "全量包不完整：" + check.reason + "；已丢弃，未写入备份目录")
                }
            } else if (handle.size <= 0L) {
                // 逻辑备份没有中央目录可验，最低限度确认「不是空文件」。
                handle.delete()
                return fail(now, "备份文件为空；已丢弃，未写入备份目录")
            }
            if (!handle.renameTo(targetName)) {
                handle.delete()
                return fail(now, "备份文件落盘失败 " + storage.label + "/" + targetName)
            }
            committed = true
            val kept = prune(keep, storage, full)
            runCatching {
                SettingsDataStore.setAutoBackupState(
                    lastAt = System.currentTimeMillis(),
                    fingerprint = fingerprint,
                    fileName = targetName,
                    error = note,
                    errorAt = if (note.isBlank()) 0L else now,
                )
            }
            AutoBackupResult.Done(targetName, conversationCount, kept)
        } catch (error: Throwable) {
            // 清理必须发生在 rethrow 之前：取消（任务撤销 / 进程被杀）走的是下面那条 rethrow 分支，
            // 早先直接抛出，`.part` 就留在备份目录里（实测残留 1.53 GB），只能靠下一次真正跑到
            // runNow 的预清理兜——而 runLocked 有「未开启 / 未到周期 / 数据没变化」三个前置返回，
            // 这个「下一次」是有条件的。所以取消路径也在这里删。
            // 用 runCatching 包住：清理失败不能顶掉原始异常（否则日志里看到的是删除错误）。
            // committed 之后不再删：那时 handle 指向的文件已经被改名成正式备份，删掉等于丢包。
            val cleanup = discardTempOnFailure(handle, committed)
            if (cleanup.error != null) {
                AndroidAgentLogger.warn(
                    "Auto backup temp delete failed: type=${cleanup.error.safeLogType()}, name-length=${handle.name.length}",
                )
            } else if (cleanup.attempted && !cleanup.deleted) {
                // 删不掉不致命：runNow 开头的预清理是第二道防线，下一轮真正跑到那里会再清一次。
                AndroidAgentLogger.warn("Auto backup temp left behind: name-length=" + handle.name.length)
            }
            if (error is CancellationException) throw error
            AndroidAgentLogger.warn("Auto backup failed: type=${error.safeLogType()}")
            fail(
                now,
                error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
            )
        }
    }

    /**
     * 失败 / 取消时丢弃临时文件的结论。
     *
     * 之所以用返回值而不是在函数里直接打日志：`AndroidAgentLogger` 走 `android.util.Log`，
     * 纯 JVM 单测里调用会抛 "not mocked"。这里只做判定，日志交给调用方（[runNow] 本来就在 Android 进程内）。
     */
    internal data class TempCleanup(
        /** 是否真的尝试过删除；false 表示已 committed，跳过。 */
        val attempted: Boolean,
        /** 删除是否成功。 */
        val deleted: Boolean,
        /** 删除时抛出的异常；成功或跳过为 null。 */
        val error: Throwable? = null,
    )

    /**
     * 失败 / 取消时丢弃临时文件。
     *
     * 抽成独立函数是为了让「取消也要删 `.part`」这条行为能被纯 JVM 用例直接覆盖：
     * 整条 [runNow] 依赖 Context、DataStore 与真实导出，纯 JVM 起不来。
     * [committed] 为 true 表示 rename 已经成功，handle 指向的已是正式备份，此时不动它。
     */
    internal fun discardTempOnFailure(handle: BackupHandle, committed: Boolean): TempCleanup {
        if (committed) return TempCleanup(attempted = false, deleted = false)
        return try {
            TempCleanup(attempted = true, deleted = handle.delete())
        } catch (error: Exception) {
            // 只捕获删除动作本身；清理失败不能顶掉调用方正在处理的原始异常。
            TempCleanup(attempted = true, deleted = false, error = error)
        }
    }

    private suspend fun fail(now: Long, message: String): AutoBackupResult {
        // 失败也要推进「上次检查时间」，否则每次打开应用都会立刻重试同一个失败，
        // 既刷日志又反复占用 IO；下一个周期再试，手动「立即备份一次」不受影响。
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        runCatching {
            SettingsDataStore.setAutoBackupState(
                lastAt = now,
                fingerprint = settings?.autoBackupFingerprint.orEmpty(),
                fileName = settings?.autoBackupLastFileName.orEmpty(),
                error = message,
                errorAt = now,
            )
        }
        return AutoBackupResult.Failed(message)
    }

    private fun prune(keep: Int, storage: BackupStorage, full: Boolean): Int {
        fun belongs(name: String): Boolean = if (full) {
            EtaFullBackupArchive.isFullBackup(name)
        } else {
            name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)
        }
        val pool = storage.entries().map { it.name }.filter { belongs(it) }.sortedDescending()
        pool.drop(keep.coerceAtLeast(1)).forEach { stale ->
            if (!storage.deleteName(stale)) {
                AndroidAgentLogger.warn("Auto backup prune skipped: name-length=" + stale.length)
            }
        }
        return storage.entries().count { belongs(it.name) }
    }
}
