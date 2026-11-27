package io.github.mangi.eta.ui.app

import java.io.File
import java.io.IOException

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 工作区文件写操作内核（自 aicode `FileAccessProvider`/`LocalFileAccess` 移植，2026-11-27）。
 *
 * 纯 JVM：只依赖 `java.io`/`java.nio` 与一个 root [File]，不碰 Android 类，
 * 因此在 arm64 上（Robolectric 全 skipped 的环境）也能被纯 JUnit 用例覆盖。
 *
 * [WorkspaceFileStore] 持有实例并以 suspend 包装对外；所有路径先经
 * [resolve]（canonical + root 前缀校验，与既有读路径同一套防线），
 * 越界一律抛 [SecurityException]，与 `WORKSPACE_PATH_OUTSIDE_ROOT` 语义一致。
 *
 * 与 aicode 的差异（ETA 纪律要求）：
 * - 删除不直接 `deleteRecursively`：先整体移入 `root/.trash/`（时间戳前缀防同名覆盖），
 *   清空 .trash 仍走工作区巡检/人工确认，不在 UI 链路里物理删除；
 * - 写文件统一原子写（同目录临时文件 + ATOMIC_MOVE），失败不留半截文件。
 */
internal class WorkspaceFileOps(private val root: File) {

    val trashDir: File = File(root, ".trash")

    /** 相对路径 → root 内 File；canonical 解析父级符号链接，越界拒绝。 */
    fun resolve(path: String): File {
        val candidate = if (path.isBlank()) root else File(root, path)
        val canonical = candidate.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        return canonical
    }

    /** 文件名合法性：非空、不含路径分隔符、不是 `.` / `..`（自 aicode isValidFileEntryName 移植）。 */
    fun isValidEntryName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.contains('/') &&
            !trimmed.contains('\\') &&
            trimmed != "." &&
            trimmed != ".."
    }

    /**
     * 写文本文件。父目录自动创建；[overwrite] 为 false 且目标已存在时抛 [FileAlreadyExistsException]。
     * 原子写：先写同目录临时文件（保证与目标同一文件系统，rename 才是原子的），再 ATOMIC_MOVE 落位。
     */
    fun writeText(path: String, content: String, overwrite: Boolean = true) {
        val target = resolve(path)
        if (target.exists() && !overwrite) throw java.nio.file.FileAlreadyExistsException(target.path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 创建目录（含父目录）；已存在且是目录时静默成功，存在同名普通文件时抛 [IOException]。 */
    fun mkdirs(path: String) {
        val target = resolve(path)
        if (target.isDirectory) return
        if (target.exists()) throw java.nio.file.FileAlreadyExistsException(target.path)
        if (!target.mkdirs()) throw IOException("WORKSPACE_MKDIR_FAILED: $path")
    }

    /**
     * 同目录重命名。[newName] 经 [isValidEntryName] 校验；目标已存在时抛 [FileAlreadyExistsException]（不覆盖）。
     * 返回新相对路径。
     */
    fun rename(path: String, newName: String): String {
        require(isValidEntryName(newName)) { "WORKSPACE_INVALID_ENTRY_NAME" }
        val source = resolve(path)
        if (!source.exists()) throw NoSuchFileException(source)
        val target = File(source.parentFile, newName.trim())
        if (target.exists()) throw java.nio.file.FileAlreadyExistsException(target.path)
        if (!source.renameTo(target)) throw IOException("WORKSPACE_RENAME_FAILED: $path")
        return target.relativeTo(root).path
    }

    /**
     * 删除（实为移入 `.trash/`）：root 内任意文件/目录整体挪到 `root/.trash/<时间戳>-<原名>`。
     * `.trash` 本身与 `.trash` 内条目不可再删（防止把回收站搬进自己）。
     * 返回 trash 内的落位路径（供日志与后续恢复），源不存在时抛 [NoSuchFileException]。
     */
    fun deleteToTrash(path: String): File {
        val source = resolve(path)
        if (source == trashDir || source.toPath().startsWith(trashDir.toPath())) {
            throw IOException("WORKSPACE_DELETE_TRASH_DENIED")
        }
        if (!source.exists()) throw NoSuchFileException(source)
        if (!trashDir.isDirectory && !trashDir.mkdirs()) throw IOException("WORKSPACE_TRASH_UNAVAILABLE")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(java.util.Date())
        val destination = File(trashDir, "$stamp-${source.name}")
        if (!source.renameTo(destination)) {
            // rename 跨目录在同一文件系统内应成功；失败（如权限）时抛错，不做递归复制兜底，避免误伤不可恢复。
            throw IOException("WORKSPACE_DELETE_MOVE_FAILED: $path")
        }
        return destination
    }

    /** 复制/剪切粘贴共用：把 [path] 复制到 [newPath]，[overwrite] 为 false 且目标存在时抛 [FileAlreadyExistsException]。 */
    fun copy(path: String, newPath: String, overwrite: Boolean = false) {
        val source = resolve(path)
        val target = resolve(newPath)
        if (target.toPath().startsWith(source.toPath())) throw IOException("WORKSPACE_COPY_INTO_SELF")
        if (!source.exists()) throw NoSuchFileException(source)
        if (target.exists() && !overwrite) throw java.nio.file.FileAlreadyExistsException(target.path)
        if (source.isDirectory) {
            // 基准必须是 source 本身而不是它的父目录：否则整棵树会多套一层源目录名落在目标下面。
            source.walkBottomUp().forEach { item ->
                val relative = source.toPath().relativize(item.toPath())
                val destination = if (relative.toString().isEmpty()) target else File(target, relative.toString())
                if (item.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    Files.copy(item.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } else {
            target.parentFile?.mkdirs()
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 内容指纹：`sha256:<十六进制小写>`（自 aicode sha256Fingerprint 移植，算法与前缀语义一致）。
     * 前缀写进指纹本身：将来换算法时新旧记录天然不可比，退化成「未知版本」而不是错误判等。
     * 文件不存在或不是普通文件时返回 null。
     */
    fun fingerprint(path: String): String? {
        val file = resolve(path)
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return buildString(digest.size * 2 + 7) {
            append("sha256:")
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private companion object {
        private const val HEX = "0123456789abcdef"
    }
}
