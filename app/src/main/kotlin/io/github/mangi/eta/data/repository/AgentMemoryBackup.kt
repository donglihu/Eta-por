package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MEMORY.md 的自动备份副本。
 *
 * 之前记忆只有一份活文件（应用私有目录），改坏了没有回滚点，工作区里那份还是手工拷的旧副本。
 * 这里把每次写入后的记忆镜像成带时间戳的副本：
 * 1. 首选工作区 `/data/local/tmp/eta/memory`，失败再退应用专属外部目录、内部目录；
 * 2. 同时保留 `MEMORY.latest.md` 方便直接 diff；
 * 3. 只留最近 [KEEP] 份，避免刷爆存储。
 */
internal object AgentMemoryBackup {
    /** 用户指定的镜像目录：与工作区 `memory/` 同一份。 */
    const val WORKSPACE_DIRECTORY = "/data/local/tmp/eta/memory"
    const val LATEST_FILE = "MEMORY.latest.md"
    const val ARCHIVE_LATEST_FILE = "MEMORY-ARCHIVE.latest.md"
    const val KEEP = 20

    /** root 复制是同步调用，超时就放弃并回显原因，避免卡住记忆页。 */
    private const val ROOT_TIMEOUT_SECONDS = 8L

    private const val SOURCE_FILE = "MEMORY.md"
    private const val SOURCE_ARCHIVE_FILE = "MEMORY-ARCHIVE.md"
    /** 非核心笔记：同样要备份，否则 NOTES.md 写完就没了。 */
    private const val SOURCE_NOTES_FILE = "NOTES.md"
    private const val NOTES_PREFIX = "NOTES-"
    private const val NOTES_LATEST_FILE = "NOTES.latest.md"
    private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"
    private const val SNAPSHOT_PREFIX = "MEMORY-"
    private const val ARCHIVE_PREFIX = "MEMORY-ARCHIVE-"
    private const val SNAPSHOT_SUFFIX = ".md"

    data class Status(
        val at: Long,
        val directory: String,
        val file: String?,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null
    }

    @Volatile
    var last: Status? = null
        private set

    /** 按候选目录顺序镜像一次，第一个成功即返回；全部失败时把第一条错误暴露出去。 */
    fun mirror(sourceDir: File, context: Context, now: Long = System.currentTimeMillis()): Status {
        val failures = mutableListOf<Status>()
        val workspace = File(WORKSPACE_DIRECTORY)
        // 1. 应用进程直写：SELinux permissive 或旧系统才走得到这里。
        // 2. root 通道写：/data/local/tmp/eta 是 shell_data_file，Enforcing 下 untrusted_app 写不进去，
        //    必须借 su 的域复制，否则「自动备份到工作区 memory/」在真机上永远失败。
        listOf(
            { backup(sourceDir, workspace, now) },
            { backupViaRoot(sourceDir, workspace, now) },
        ).forEach { attempt ->
            val status = attempt()
            if (status.ok) {
                last = status
                return status
            }
            failures += status
        }
        // 3. 应用专属目录兜底：至少保证本机有一份可回滚副本。
        val candidates = buildList {
            context.getExternalFilesDir(null)?.let { add(File(it, "memory-backup")) }
            add(File(context.filesDir, "memory-backup"))
        }
        candidates.forEach { candidate ->
            val status = backup(sourceDir, candidate, now)
            if (status.ok) {
                last = status
                return status
            }
            failures += status
        }
        val status = failures.firstOrNull() ?: Status(now, WORKSPACE_DIRECTORY, null, "没有可写的备份目录")
        last = status
        AndroidAgentLogger.warnThrottled("memory_backup_failed") {
            "Memory mirror failed: dir=${status.directory} type=backup_failed"
        }
        return status
    }

    /**
     * 通过 root 复制到工作区。
     * 只做三件事：建目录、拷带时间戳副本、刷新 latest；失败原因原样带回界面，不做静默吞掉。
     */
    private fun backupViaRoot(sourceDir: File, targetDir: File, now: Long): Status {
        val source = File(sourceDir, SOURCE_FILE)
        if (!source.isFile) {
            return Status(now, targetDir.absolutePath, null, "记忆文件不存在 " + source.absolutePath)
        }
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(now))
        val snapshotPath = targetDir.absolutePath + "/" + SNAPSHOT_PREFIX + stamp + SNAPSHOT_SUFFIX
        val latestPath = targetDir.absolutePath + "/" + LATEST_FILE
        val archive = File(sourceDir, SOURCE_ARCHIVE_FILE)
        val script = buildString {
            append("mkdir -p ").append(shq(targetDir.absolutePath)).append(" && ")
            append("cp -f ").append(shq(source.absolutePath)).append(' ')
                .append(shq(snapshotPath)).append(" && ")
            append("cp -f ").append(shq(source.absolutePath)).append(' ')
                .append(shq(latestPath))
            if (archive.isFile) {
                append(" && cp -f ").append(shq(archive.absolutePath)).append(' ')
                    .append(shq(targetDir.absolutePath + "/" + ARCHIVE_PREFIX + stamp + SNAPSHOT_SUFFIX))
                append(" && cp -f ").append(shq(archive.absolutePath)).append(' ')
                    .append(shq(targetDir.absolutePath + "/" + ARCHIVE_LATEST_FILE))
            }
            // NOTES.md 也要镜像：非核心笔记不在 MEMORY.md 里，
            // 漏了它就等于「写进冷区的记录没有回滚点」。
            val notes = File(sourceDir, SOURCE_NOTES_FILE)
            if (notes.isFile) {
                append(" && cp -f ").append(shq(notes.absolutePath)).append(' ')
                    .append(shq(targetDir.absolutePath + "/" + NOTES_PREFIX + stamp + SNAPSHOT_SUFFIX))
                append(" && cp -f ").append(shq(notes.absolutePath)).append(' ')
                    .append(shq(targetDir.absolutePath + "/" + NOTES_LATEST_FILE))
            }
        }
        return try {
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Status(now, targetDir.absolutePath, null, "root 备份超时")
            }
            if (process.exitValue() != 0) {
                val message = process.inputStream.bufferedReader().use { it.readText() }.trim()
                Status(
                    now,
                    targetDir.absolutePath,
                    null,
                    "root 备份失败：" + message.take(160).ifBlank { "exit=" + process.exitValue() },
                )
            } else {
                Status(now, targetDir.absolutePath, File(snapshotPath).name, null)
            }
        } catch (error: Exception) {
            Status(
                now,
                targetDir.absolutePath,
                null,
                error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
            )
        }
    }

    /**
     * POSIX shell 单引号转义，与 [io.github.mangi.eta.agent.device.AgentFileReferenceGateway]
     * 的 `shellQuote` 同一口径。
     *
     * 路径里出现单引号（用户可自建目录名、备份目录可配）时，裸拼接会让单引号提前闭合，
     * `su -c` 执行的就不再是这条 cp 命令。这里把 `'` 换成 `'\''` 后再整体加引号。
     */
    internal fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** 把 [sourceDir] 里的记忆文件复制到 [targetDir]；不抛异常，结果全在返回状态里。 */    fun backup(sourceDir: File, targetDir: File, now: Long): Status {
        val source = File(sourceDir, SOURCE_FILE)
        if (!source.isFile) {
            return Status(now, targetDir.absolutePath, null, "记忆文件不存在 " + source.absolutePath)
        }
        if (!targetDir.isDirectory && !targetDir.mkdirs() && !targetDir.isDirectory) {
            return Status(now, targetDir.absolutePath, null, "无法创建备份目录 " + targetDir.absolutePath)
        }
        if (!targetDir.canWrite()) {
            return Status(now, targetDir.absolutePath, null, "备份目录不可写 " + targetDir.absolutePath)
        }
        return try {
            val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(now))
            val snapshot = File(targetDir, SNAPSHOT_PREFIX + stamp + SNAPSHOT_SUFFIX)
            source.copyTo(snapshot, overwrite = true)
            source.copyTo(File(targetDir, LATEST_FILE), overwrite = true)
            val archive = File(sourceDir, SOURCE_ARCHIVE_FILE)
            if (archive.isFile) {
                archive.copyTo(File(targetDir, ARCHIVE_PREFIX + stamp + SNAPSHOT_SUFFIX), overwrite = true)
                archive.copyTo(File(targetDir, ARCHIVE_LATEST_FILE), overwrite = true)
            }
            // NOTES.md 同样备份，否则非核心笔记写完就没回滚点。
            val notes = File(sourceDir, SOURCE_NOTES_FILE)
            if (notes.isFile) {
                notes.copyTo(File(targetDir, NOTES_PREFIX + stamp + SNAPSHOT_SUFFIX), overwrite = true)
                notes.copyTo(File(targetDir, NOTES_LATEST_FILE), overwrite = true)
            }
            prune(targetDir)
            Status(now, targetDir.absolutePath, snapshot.name, null)
        } catch (error: Exception) {
            Status(
                now,
                targetDir.absolutePath,
                null,
                error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
            )
        }
    }

    private fun prune(targetDir: File) {
        val snapshots = targetDir
            .listFiles { file: File ->
                file.isFile && file.name.endsWith(SNAPSHOT_SUFFIX) &&
                    (file.name.startsWith(SNAPSHOT_PREFIX) ||
                        file.name.startsWith(ARCHIVE_PREFIX) ||
                        file.name.startsWith(NOTES_PREFIX)) &&
                    file.name != LATEST_FILE && file.name != ARCHIVE_LATEST_FILE &&
                    file.name != NOTES_LATEST_FILE
            }
            ?.sortedByDescending { it.name }
            .orEmpty()
        snapshots.drop(KEEP).forEach { stale -> stale.delete() }
    }
}
