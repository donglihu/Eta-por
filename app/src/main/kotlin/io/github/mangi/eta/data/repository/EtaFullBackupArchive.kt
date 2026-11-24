package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
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
        val zip = ZipOutputStream(output.buffered(), Charsets.UTF_8)
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
                    if (excluded(relative, includeLinuxEnvironment)) continue
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
            runCatching { zip.finish() }
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
                if (relative.isBlank() || relative.contains("..")) {
                    zip.closeEntry()
                    continue
                }
                if (excluded(relative, includeLinuxEnvironment)) {
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
        EtaFullBackupSummary(privateFiles = fileCount, privateBytes = byteCount, totalBytes = byteCount)
    }

    /** 有没有等待应用的还原。 */
    fun hasPendingRestore(context: Context): Boolean = File(stagingDirectory(context), PRIVATE_DIR).isDirectory

    /**
     * 进程启动最早期调用：把暂存区里的私有目录内容覆盖回 dataDir。
     *
     * 必须在 Room / DataStore 打开前执行；databases 里的 `-wal` / `-shm` 先删掉，
     * 避免旧库原文件配上新日志导致 SQLite 校验失败。
     */
    fun applyPendingRestore(context: Context): Boolean {
        val appContext = context.applicationContext
        val staging = stagingDirectory(appContext)
        val privateRoot = File(staging, PRIVATE_DIR)
        if (!privateRoot.isDirectory) return false
        val dataDir = appContext.dataDir
        var restored = 0
        return runCatching {
            privateRoot.walkTopDown().filter { it.isFile }.forEach { source ->
                val relative = source.relativeTo(privateRoot).invariantSeparatorsPath
                if (relative.isBlank() || relative.contains("..")) return@forEach
                val target = File(dataDir, relative)
                target.parentFile?.mkdirs()
                if (relative.startsWith("databases/")) {
                    File(target.path + "-wal").delete()
                    File(target.path + "-shm").delete()
                    File(target.path + "-journal").delete()
                }
                source.copyTo(target, overwrite = true)
                restored++
            }
            deleteRecursively(staging)
            AndroidAgentLogger.info("Full backup restore applied: files=$restored")
            true
        }.onFailure {
            AndroidAgentLogger.warn("Full backup restore failed: type=${it.safeLogType()}")
        }.getOrDefault(false)
    }

    /** 从全量包里只取出 `eta-backup.json`，交给现有导入链路。 */
    suspend fun extractDocument(context: Context, input: InputStream): File = withContext(Dispatchers.IO) {
        val target = File(File(context.cacheDir, "fullbackup"), "eta-backup.json")
        target.parentFile?.mkdirs()
        var found = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == JSON_ENTRY) {
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
