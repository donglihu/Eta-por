package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 全量备份包（`.eta-fullbackup.zip`）的导出结果。 */
internal data class EtaFullBackupSummary(
    val fileName: String = "",
    val conversationCount: Int = 0,
    val privateFiles: Int = 0,
    val privateBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

/**
 * 全量备份包：一个 zip，里面同时放
 *
 * - `eta-backup.json`：与手动导出完全一致的逻辑备份（会话、消息、记忆、角色卡、provider）；
 * - `private/`：应用私有目录里真正落盘的东西（databases / shared_prefs / files），
 *   用来兜住「JSON 表达不了」的部分（数据库原文件、DataStore 设置、草稿、技能、快照等）；
 * - `manifest.json`：包元信息（时间、文件数、字节数）。
 *
 * 刻意不打包 `files/terminal`、`files/terminal-user`：那是 Linux 终端与 chroot 的系统盘，
 * 动辄几个 GB，塞进备份里既慢又占地方，还原也用不上。需要连终端系统一起搬的话，
 * 可以单独用文件管理器打包。
 */
internal object EtaFullBackupArchive {

    const val FULL_SUFFIX = ".eta-fullbackup.zip"
    private const val JSON_ENTRY = "eta-backup.json"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val PRIVATE_DIR = "private"

    /** zip 目录结束标记「PK\x05\x06」按小端读出的 32 位值。 */
    private const val EOCD_SIGNATURE = 0x0605_4B50

    /** EOCD 固定 22 字节，末尾还可能跟最多 65535 字节注释。 */
    private const val MAX_TAIL_BYTES = 66_000

    /** 顶层只收这三类，其余（app_*、code_cache、cache 等）不进包。 */
    private val includeTop = listOf("databases", "shared_prefs", "files")

    /** 相对 dataDir 需要跳过的目录。 */
    private val excludedRelative = setOf(
        "files/terminal",
        "files/terminal-user",
        "files/restore-staging",
    )

    /** 还原暂存区：解压到这里，下次进程启动最早期再覆盖到真实目录。 */
    fun stagingDirectory(context: Context): File = File(context.filesDir, "restore-staging")

    fun isFullBackup(name: String): Boolean = name.endsWith(FULL_SUFFIX, ignoreCase = true)

    /** 全量包结构校验结果；[entries] 为 zip 中央目录里的条目数。 */
    data class EtaFullBackupCheck(
        val ok: Boolean,
        val entries: Int,
        val bytes: Long,
        val reason: String = "",
    )

    /** 校验磁盘上的包（自动备份的 `.part` 与已有归档）。不整包读回，只读尾部。 */
    fun verify(file: File): EtaFullBackupCheck {
        if (!file.isFile) return EtaFullBackupCheck(false, 0, 0L, "文件不存在")
        val size = file.length()
        val tailSize = minOf(size, MAX_TAIL_BYTES.toLong()).toInt()
        if (tailSize < 22) {
            return EtaFullBackupCheck(false, 0, size, "体积 " + size + "B，不是完整的 zip")
        }
        val tail = ByteArray(tailSize)
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(size - tailSize)
                raf.readFully(tail)
            }
            analyzeTail(tail, size - tailSize, size, "全量包")
        }.getOrElse {
            EtaFullBackupCheck(false, 0, size, "读取失败：" + it.safeLogType())
        }
    }

    /**
     * 校验刚写进 SAF 文档的包。
     *
     * 走 [Os.lseek] 定位文件末尾直接读 64KB 尾巴，避免为了校验把几 GB 的包再复制一份。
     * 目标位置不支持随机读取时无法确认完整性，按失败返回，交给调用方提示。
     */
    fun verify(context: Context, uri: Uri): EtaFullBackupCheck {
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
            .getOrNull() ?: return EtaFullBackupCheck(false, 0, 0L, "无法重新打开刚写入的文件，无法校验完整性")
        return descriptor.use { pfd ->
            runCatching {
                val fd = pfd.fileDescriptor
                val size = Os.lseek(fd, 0L, OsConstants.SEEK_END)
                val tailSize = minOf(size, MAX_TAIL_BYTES.toLong()).toInt()
                if (tailSize < 22) {
                    return@runCatching EtaFullBackupCheck(false, 0, size, "体积 " + size + "B，不是完整的 zip")
                }
                val tail = ByteArray(tailSize)
                Os.lseek(fd, size - tailSize, OsConstants.SEEK_SET)
                var read = 0
                while (read < tailSize) {
                    val count = Os.read(fd, tail, read, tailSize - read)
                    if (count <= 0) break
                    read += count
                }
                if (read < tailSize) {
                    EtaFullBackupCheck(false, 0, size, "回读失败：只读到 " + read + "B，期望 " + tailSize + "B")
                } else {
                    analyzeTail(tail, size - tailSize, size, "导出的全量包")
                }
            }.getOrElse {
                EtaFullBackupCheck(
                    false,
                    0,
                    0L,
                    "该位置不支持随机读取，无法校验完整性：" + it.safeLogType(),
                )
            }
        }
    }

    /** 从尾部字节里找 EOCD，解析中央目录位置与条目数，判断包是否被截断。 */
    private fun analyzeTail(
        tail: ByteArray,
        tailStart: Long,
        totalSize: Long,
        label: String,
    ): EtaFullBackupCheck {
        var eocd = -1
        var index = tail.size - 22
        while (index >= 0) {
            if (le32(tail, index) == EOCD_SIGNATURE) {
                eocd = index
                break
            }
            index--
        }
        if (eocd < 0) {
            return EtaFullBackupCheck(
                false,
                0,
                totalSize,
                label + " 末尾没有目录结束标记 PK\\x05\\x06，文件被截断",
            )
        }
        val entries = le16(tail, eocd + 10)
        val centralSize = le32(tail, eocd + 12).toLong() and 0xFFFF_FFFFL
        val centralOffset = le32(tail, eocd + 16).toLong() and 0xFFFF_FFFFL
        val commentLength = le16(tail, eocd + 20)
        val eocdAbsolute = tailStart + eocd
        if (eocdAbsolute + 22 + commentLength > totalSize) {
            return EtaFullBackupCheck(false, entries, totalSize, label + " 目录结束标记不完整，文件被截断")
        }
        if (entries <= 0) {
            return EtaFullBackupCheck(false, entries, totalSize, label + " 内没有任何条目")
        }
        if (centralOffset + centralSize > eocdAbsolute) {
            return EtaFullBackupCheck(
                false,
                entries,
                totalSize,
                label + " 中央目录越界（offset=" + centralOffset + " size=" + centralSize + "）",
            )
        }
        return EtaFullBackupCheck(true, entries, totalSize)
    }

    private fun le16(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(buffer: ByteArray, offset: Int): Int =
        le16(buffer, offset) or (le16(buffer, offset + 2) shl 16)

    /** [includeLinuxEnvironment] 为 true 时不再排除 terminal / terminal-user，把 Linux 容器一起打包。 */
    private fun excluded(name: String, includeLinuxEnvironment: Boolean): Boolean {
        if (includeLinuxEnvironment) {
            return name == "files/restore-staging" || name.startsWith("files/restore-staging/")
        }
        return excludedRelative.any { name == it || name.startsWith("$it/") }
    }

    /** SQLite 伴生日志文件的后缀：主库原文件离开它们就是不一致的。 */
    private val SQLITE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

    /** 是否是 SQLite 伴生文件（`-wal` / `-shm` / `-journal`）。 */
    internal fun isSqliteSidecar(relativePath: String): Boolean =
        SQLITE_SIDECAR_SUFFIXES.any { relativePath.endsWith(it, ignoreCase = true) }

    /**
     * 导出时是否收录这个相对路径（纯逻辑，可直接在 JVM 上断言）。
     *
     * 伴生文件一律不收：`-wal` 里是还没 checkpoint 进主库的最近提交，把它和主库一起
     * 打进包，还原时等于拿旧日志去盖当前库的日志。主库内容的完整性由导出前的
     * `PRAGMA wal_checkpoint(TRUNCATE)` 保证，不靠把日志一起搬。
     */
    internal fun shouldExport(relative: String, includeLinuxEnvironment: Boolean = false): Boolean =
        relative.isNotBlank() &&
            !relative.contains("..") &&
            !isSqliteSidecar(relative) &&
            !excluded(relative, includeLinuxEnvironment)

    /** 还原时对单个归档条目的处置。 */
    internal enum class RestoreDecision {
        /** 铺到暂存区。 */
        KEEP,

        /** 归档里的 stale 伴生文件：丢弃，绝不落盘。 */
        DROP_SIDECAR,

        /** 越界路径、被排除目录等：丢弃。 */
        DROP_OTHER,
    }

    /** 单个归档条目的处置（纯逻辑）。 */
    internal fun restoreDecision(relative: String, includeLinuxEnvironment: Boolean = false): RestoreDecision = when {
        relative.isBlank() || relative.contains("..") -> RestoreDecision.DROP_OTHER
        isSqliteSidecar(relative) -> RestoreDecision.DROP_SIDECAR
        excluded(relative, includeLinuxEnvironment) -> RestoreDecision.DROP_OTHER
        else -> RestoreDecision.KEEP
    }

    /** 归档条目清单的过滤结果，纯逻辑。 */
    internal data class RestoreEntryFilter(
        val keep: List<String>,
        val droppedSidecars: List<String>,
        val droppedOther: List<String>,
    )

    /** 把归档里的条目清单分成「铺回去」「丢弃的 stale 日志」「其余丢弃」三堆。 */
    internal fun filterRestoreEntries(
        entries: List<String>,
        includeLinuxEnvironment: Boolean = false,
    ): RestoreEntryFilter {
        val keep = ArrayList<String>(entries.size)
        val sidecars = ArrayList<String>()
        val other = ArrayList<String>()
        entries.forEach { name ->
            when (restoreDecision(name, includeLinuxEnvironment)) {
                RestoreDecision.KEEP -> keep += name
                RestoreDecision.DROP_SIDECAR -> sidecars += name
                RestoreDecision.DROP_OTHER -> other += name
            }
        }
        return RestoreEntryFilter(keep, sidecars, other)
    }

    /**
     * 删掉某个数据库主文件旁边的 `-wal` / `-shm` / `-journal`，返回删掉的文件名。
     *
     * 还原前必须先删：新铺回来的主库原文件配上当前库里残留的旧 WAL，SQLite 会按旧日志
     * 回放，轻则回滚到导出前的状态，重则直接判定库损坏。
     */
    internal fun deleteSqliteSidecars(databaseFile: File): List<String> {
        val removed = ArrayList<String>(SQLITE_SIDECAR_SUFFIXES.size)
        SQLITE_SIDECAR_SUFFIXES.forEach { suffix ->
            val sidecar = File(databaseFile.path + suffix)
            if (sidecar.exists() && sidecar.delete()) removed += sidecar.name
        }
        return removed
    }

    /** checkpoint 的判定结果；[ok] 为 false 时导出必须放弃。 */
    internal data class WalCheckpointVerdict(val ok: Boolean, val detail: String)

    /**
     * `PRAGMA wal_checkpoint(TRUNCATE)` 三个返回值的判定（纯逻辑）。
     *
     * 返回值是 `busy|log|checkpointed`：
     * - `log < 0`：库不在 WAL 模式（实测返回 `0|-1|-1`），没有日志要合，视为通过；
     * - `checkpointed >= log`：全部日志帧都已合进主库，数据完整，可以出包；
     * - `checkpointed < log`：还有帧留在 `-wal` 里，只拷主库会丢数据，必须拒绝。
     *
     * `busy` 的语义只是「TRUNCATE 阶段无法把 `-wal` 收缩到 0 字节」，反映文件大小，
     * 不反映数据是否已合进主库。实测 sqlite3 3.53.1：读事务挡着时返回 `1|202|202`，
     * 用 `immutable=1` 直读主库拿到 200 行全在。所以 `busy` 不能单独构成拒绝理由，
     * 只有 `checkpointed < log` 才代表包内数据不完整（2026-11-27 审核必修 2）。
     */
    internal fun walCheckpointVerdict(busy: Int, logFrames: Int, checkpointedFrames: Int): WalCheckpointVerdict =
        when {
            logFrames < 0 -> WalCheckpointVerdict(true, "not_wal")
            // 数据是否已合进主库只看 checkpointed；busy 只反映文件收缩受阻，不影响包内数据完整性。
            checkpointedFrames >= logFrames -> WalCheckpointVerdict(
                true,
                if (busy != 0) "frames=$logFrames busy=$busy(未收缩)" else "frames=$logFrames",
            )
            else -> WalCheckpointVerdict(false, "checkpointed=$checkpointedFrames/$logFrames busy=$busy")
        }

    /**
     * 对正在使用的库执行 `PRAGMA wal_checkpoint(TRUNCATE)`。
     *
     * 导出前必须把 WAL 合进主库：包里拷的是 `private/databases/eta.db` 这个主库原文件，
     * 未 checkpoint 的最近提交只存在 `-wal` 里，只拷主库会备份出一份旧数据
     * （2026-11-26 审核 P1-3；已用 sqlite3 实测复现：主库 0 行、库内实际 2 行）。
     */
    private fun checkpointWal(context: Context): WalCheckpointVerdict {
        val cursor = EtaDatabase.get(context).openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
        return cursor.use {
            if (!it.moveToFirst() || it.columnCount < 3) {
                WalCheckpointVerdict(false, "no_row")
            } else {
                walCheckpointVerdict(it.getInt(0), it.getInt(1), it.getInt(2))
            }
        }
    }

    /**
     * 估算一次导出会打包多少字节（只统计文件大小，不读内容）。
     *
     * 给 [AutoBackupSizeGate] 用：自动备份必须在动手之前就知道会不会超预算，
     * 否则等写完 16 GB 才发现超时，进程早就被系统杀掉了。
     * 只遍历元数据，本机 16 GB / 数万文件的 chroot 也在百毫秒级。
     */
    fun estimateExportBytes(
        context: Context,
        includeLinuxEnvironment: Boolean = false,
    ): Long {
        val dataDir = runCatching { context.applicationContext.dataDir }.getOrNull() ?: return 0L
        var total = 0L
        includeTop.forEach { top ->
            val root = File(dataDir, top)
            if (!root.isDirectory) return@forEach
            val walk = runCatching {
                root.walkTopDown()
                    .onEnter { dir -> !excluded(dir.relativeTo(dataDir).invariantSeparatorsPath, includeLinuxEnvironment) }
                    .filter { it.isFile }
                    // 与 export 用同一判据：伴生日志不进包，也不该计入预估体积。
                    .filter { shouldExport(it.relativeTo(dataDir).invariantSeparatorsPath, includeLinuxEnvironment) }
                    .forEach { file -> total += runCatching { file.length() }.getOrDefault(0L) }
            }
            // 目录不可读就当作估不出来：返回 0 让闸门放行，不因一次权限抖动误判成「体积超限」。
            if (walk.isFailure) return 0L
        }
        return total
    }

    /**
     * 导出全量包。
     *
     * [output] 由调用方负责关闭；这里只往里写 zip 条目。
     */
    suspend fun export(
        context: Context,
        output: OutputStream,
        includeLinuxEnvironment: Boolean = false,
        onPhase: (String) -> Unit = {},
    ): EtaFullBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dataDir = appContext.dataDir
        var fileCount = 0
        var byteCount = 0L
        var conversations = 0
        // 先把 WAL 合进主库，再动 zip。checkpoint 失败就不产出包：包里拷的是主库原文件，
        // 拿一份没合日志的主库当备份，等于给用户一份「看起来成功、实际少了最近提交」的包。
        onPhase("full_checkpoint")
        val checkpoint = runCatching { checkpointWal(appContext) }.getOrElse { error ->
            if (error is CancellationException) throw error
            AndroidAgentLogger.warn("Full backup checkpoint threw: type=" + error.safeLogType())
            WalCheckpointVerdict(false, "threw:" + error.safeLogType())
        }
        if (!checkpoint.ok) {
            AndroidAgentLogger.warn("Full backup aborted: wal checkpoint failed " + checkpoint.detail)
            throw IllegalStateException(
                "数据库 WAL 合并失败（" + checkpoint.detail + "），已放弃生成全量备份，避免产出不一致的包",
            )
        }
        // 不要再把 output 包一层 BufferedOutputStream：这一层的关闭权不在本函数手里
        // （output 由调用方 use{} 关闭，这里只 finish）。缓冲层没人 flush 时，最后 8KB
        // ——恰好是中央目录尾部与 EOCD——会留在内存里被丢弃，产物变成「没有 PK\x05\x06」的残包。
        val zip = ZipOutputStream(output, Charsets.UTF_8)
        try {
            onPhase("full_json")
            zip.putNextEntry(ZipEntry(JSON_ENTRY).apply { time = System.currentTimeMillis() })
            val summary = EtaBackupRepository.export(appContext, zip)
            conversations = summary.conversationCount
            zip.closeEntry()

            onPhase("full_private")
            includeTop.forEach { top ->
                val root = File(dataDir, top)
                if (!root.isDirectory) return@forEach
                val files = root.walkTopDown()
                    .onEnter { dir -> !excluded(dir.relativeTo(dataDir).invariantSeparatorsPath, includeLinuxEnvironment) }
                    .filter { it.isFile }
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    val relative = file.relativeTo(dataDir).invariantSeparatorsPath
                    // 跳过 -wal/-shm/-journal：主库内容已 checkpoint，日志进包只会污染还原。
                    if (!shouldExport(relative, includeLinuxEnvironment)) continue
                    try {
                        zip.putNextEntry(ZipEntry("$PRIVATE_DIR/$relative").apply { time = file.lastModified() })
                        file.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                                byteCount += count
                            }
                        }
                        zip.closeEntry()
                        fileCount++
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        AndroidAgentLogger.warn("Full backup skip file: type=${error.safeLogType()}")
                        runCatching { zip.closeEntry() }
                    }
                }
            }

            onPhase("full_manifest")
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = System.currentTimeMillis() })
            zip.write(
                manifestJson(
                    exportedAt = System.currentTimeMillis(),
                    conversationCount = conversations,
                    privateFiles = fileCount,
                    privateBytes = byteCount,
                    appVersion = appVersionName(appContext),
                ).toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            runCatching { zip.closeEntry() }
            throw error
        } finally {
            // finish 只负责写 zip 结构与 EOCD，不保证把数据推到底层流；显式 flush 才算落盘完成。
            runCatching { zip.finish() }
            runCatching { zip.flush() }
        }
        EtaFullBackupSummary(
            conversationCount = conversations,
            privateFiles = fileCount,
            privateBytes = byteCount,
            totalBytes = byteCount,
        )
    }

    /**
     * 把全量包解到暂存区，等下次启动最早期整体覆盖。
     *
     * 只解 `private/` 下的内容；`eta-backup.json` 由调用方另外读出交给
     * [EtaBackupRepository.import] 走正常的逻辑还原。
     */
    suspend fun stage(
        context: Context,
        input: InputStream,
        includeLinuxEnvironment: Boolean = false,
        onPhase: (String) -> Unit = {},
    ): EtaFullBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val staging = stagingDirectory(appContext)
        deleteRecursively(staging)
        val privateRoot = File(staging, PRIVATE_DIR)
        privateRoot.mkdirs()
        var fileCount = 0
        var byteCount = 0L
        val droppedSidecars = ArrayList<String>()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (!name.startsWith("$PRIVATE_DIR/")) {
                    zip.closeEntry()
                    continue
                }
                val relative = name.removePrefix("$PRIVATE_DIR/")
                // 旧归档里可能带着导出时的 -wal/-shm/-journal。它们是导出那一刻的日志，
                // 配上还原后的主库只会把库回滚或直接判损坏，一律丢弃。
                if (restoreDecision(relative, includeLinuxEnvironment) != RestoreDecision.KEEP) {
                    if (isSqliteSidecar(relative)) droppedSidecars += relative
                    zip.closeEntry()
                    continue
                }
                onPhase("restore_" + relative.substringBefore('/'))
                val target = File(privateRoot, relative)
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        byteCount += count
                    }
                }
                zip.closeEntry()
                fileCount++
            }
        }
        if (droppedSidecars.isNotEmpty()) {
            AndroidAgentLogger.info(
                "Full backup restore dropped stale sqlite sidecars: count=" + droppedSidecars.size +
                    " names=" + droppedSidecars.joinToString(","),
            )
        }
        EtaFullBackupSummary(privateFiles = fileCount, privateBytes = byteCount, totalBytes = byteCount)
    }

    /** 有没有等待应用的还原。 */
    fun hasPendingRestore(context: Context): Boolean = File(stagingDirectory(context), PRIVATE_DIR).isDirectory

    /**
     * 进程启动最早期调用：把暂存区里的私有目录内容覆盖回 dataDir。
     *
     * 必须在 Room / DataStore 打开前执行；databases 里的 `-wal` / `-shm` 先删掉，
     * 避免旧库原文件配上新日志导致 SQLite 校验失败。
     *
     * 失败语义（2026-11-26 审核）：单文件失败时记下错误，整体返回 false，
     * **不删暂存区**——保留现场，让用户能重试或手工捞出数据；早先失败只 warn 一句，
     * 暂存区照样被删，等于把唯一一份新数据也丢了。
     */
    fun applyPendingRestore(context: Context): Boolean {
        val appContext = context.applicationContext
        val staging = stagingDirectory(appContext)
        val privateRoot = File(staging, PRIVATE_DIR)
        if (!privateRoot.isDirectory) return false
        val dataDir = appContext.dataDir
        var restored = 0
        var droppedSidecars = 0
        var failure: Throwable? = null
        try {
            privateRoot.walkTopDown().filter { it.isFile }.forEach { source ->
                val relative = source.relativeTo(privateRoot).invariantSeparatorsPath
                if (relative.isBlank() || relative.contains("..")) return@forEach
                // 暂存区里残留的伴生文件（旧版本 stage 会铺进来）直接丢掉，不参与覆盖。
                if (isSqliteSidecar(relative)) {
                    droppedSidecars++
                    return@forEach
                }
                val target = File(dataDir, relative)
                target.parentFile?.mkdirs()
                if (relative.startsWith("databases/")) {
                    // 铺主库原文件之前先清掉目标位置残留的旧日志，否则 SQLite 会拿旧 WAL
                    // 回放新库，出现回滚甚至判定损坏。
                    deleteSqliteSidecars(target)
                }
                source.copyTo(target, overwrite = true)
                restored++
            }
        } catch (error: Throwable) {
            // 单个文件失败（空间不足、权限、文件被占用）都会走到这里。
            // 不吞：留着暂存区，并把原因交给上层记录。
            failure = error
        }
        if (failure != null) {
            io.github.mangi.eta.core.AndroidAgentLogger.warn(
                "Full backup restore incomplete: files=$restored type=${failure.safeLogType()}" +
                    "，暂存区已保留以便重试",
            )
            return false
        }
        deleteRecursively(staging)
        io.github.mangi.eta.core.AndroidAgentLogger.info(
            "Full backup restore applied: files=$restored droppedSidecars=$droppedSidecars",
        )
        return true
    }

    /** 从全量包里只取出 `eta-backup.json`，交给现有导入链路。 */
    suspend fun extractDocument(context: Context, input: InputStream): File = withContext(Dispatchers.IO) {
        val target = File(File(context.cacheDir, "fullbackup"), "eta-backup.json")
        target.parentFile?.mkdirs()
        var found = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // 必须精确匹配顶层条目名：只看 basename 会把 private/files/ 里用户自己
                // 命名为 eta-backup.json 的文件当成备份正文取出来（2026-11-26 审核）。
                if (!entry.isDirectory && entry.name == JSON_ENTRY) {
                    target.outputStream().use { out -> zip.copyTo(out) }
                    found = true
                    zip.closeEntry()
                    break
                }
                zip.closeEntry()
            }
        }
        require(found) { "全量包里没有 eta-backup.json" }
        target
    }

    private fun manifestJson(
        exportedAt: Long,
        conversationCount: Int,
        privateFiles: Int,
        privateBytes: Long,
        appVersion: String,
    ): String = buildString {
        append('{')
        append("\"format\":\"eta-fullbackup\",")
        append("\"schemaVersion\":1,")
        append("\"exportedAt\":").append(exportedAt).append(',')
        append("\"appVersion\":\"").append(appVersion.replace("\"", "")).append("\",")
        append("\"conversationCount\":").append(conversationCount).append(',')
        append("\"privateFiles\":").append(privateFiles).append(',')
        append("\"privateBytes\":").append(privateBytes)
        append('}')
    }

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        runCatching { file.walkBottomUp().forEach { it.delete() } }
    }
}
