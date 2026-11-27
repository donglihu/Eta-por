package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 备份目录里的一个文件条目。 */
internal data class BackupEntry(val name: String, val size: Long)

/**
 * 备份落点抽象。
 *
 * 为什么需要它：Android 11+ 分区存储下，公共存储根目录（`/storage/emulated/0/...`）里的文件夹
 * 可能属于别的应用（权限 0770），用 [File] 直接写会被 FUSE 拒绝。通过 SAF 拿到文件夹授权后，
 * 读写由 MediaProvider 代理，同一个目录就能用了。
 *
 * 写入、列表、改名、清理必须走同一套实现，否则会出现「写进 SAF 目录、列表却读 File 目录」
 * 这种只显示一半的状态。
 */
internal sealed interface BackupStorage {
    /** 展示给用户的路径/描述。 */
    val label: String

    /** 目录里的文件条目；读取失败返回空列表。 */
    fun entries(): List<BackupEntry>

    /** 在目录里新建（或覆盖同名）文件；无法创建返回 null。 */
    fun create(name: String): BackupHandle?

    /** 按文件名删除；不存在或删除失败返回 false。 */
    fun deleteName(name: String): Boolean

    /** 按文件名打开输入流；不存在或不可读返回 null。 */
    fun openRead(name: String): InputStream?
}

internal abstract class BackupHandle(val name: String) {
    abstract val size: Long

    abstract fun openOutput(): OutputStream?

    abstract fun delete(): Boolean

    /** 改成正式文件名；成功表示目录里只剩新名字。 */
    abstract fun renameTo(newName: String): Boolean

    /** 整包结构校验：普通文件走文件版，SAF 走 Uri 版。 */
    abstract fun verifyFullBackup(context: Context): EtaFullBackupArchive.EtaFullBackupCheck

    /**
     * 改名之前把已写入的字节真正落盘。
     *
     * 不落盘就直接 rename 的后果：断电/进程被杀时目录项换成了新名字，但数据块还没刷到
     * 存储，得到一个「名字对、内容截断」的包（2026-11-26 审核）。
     * 默认实现返回 true；个别落点无法 sync 时按「尽力而为」处理，不阻断备份。
     */
    open fun syncBeforeRename(): Boolean = true
}

/** 普通文件目录落点（应用专属目录、确实可写的外部目录）。 */
internal class FileBackupStorage(val dir: File) : BackupStorage {

    override val label: String get() = dir.absolutePath

    override fun entries(): List<BackupEntry> = dir
        .listFiles()
        ?.filter { it.isFile }
        ?.map { BackupEntry(it.name, it.length()) }
        .orEmpty()

    override fun create(name: String): BackupHandle {
        val file = File(dir, name)
        return object : BackupHandle(name) {
            override val size: Long get() = file.length()

            override fun openOutput(): OutputStream? =
                runCatching { file.outputStream() }.getOrNull()

            override fun delete(): Boolean = !file.exists() || file.delete()

            /** 打开一次只读句柄把数据刷到存储（fsync 需要可写句柄，这里用 rw）。 */
            override fun syncBeforeRename(): Boolean = runCatching {
                java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
                true
            }.getOrDefault(false)

            override fun renameTo(newName: String): Boolean {
                val target = File(dir, newName)
                if (target.exists()) target.delete()
                if (file.renameTo(target)) return true
                // 个别 ROM 的 FUSE 会拒绝跨 UID 的改名（应用重装后旧文件属主还是上一个 UID），
                // 这时退化成复制一份，保证备份本身不会因为改名失败而丢掉。
                return runCatching {
                    file.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
                        }
                    }
                    file.delete()
                    target.length() > 0L
                }.getOrDefault(false)
            }

            override fun verifyFullBackup(context: Context): EtaFullBackupArchive.EtaFullBackupCheck =
                EtaFullBackupArchive.verify(file)
        }
    }

    override fun deleteName(name: String): Boolean {
        val file = File(dir, name)
        return !file.exists() || file.delete()
    }

    override fun openRead(name: String): InputStream? =
        runCatching { File(dir, name).inputStream() }.getOrNull()
}

/**
 * SAF 授权目录落点：`content://com.android.externalstorage.documents/tree/...`。
 *
 * 只依赖平台 API（[DocumentsContract]），不引第三方库：授权目录的读写都是
 * `contentResolver` + document URI，够用且没有额外依赖。
 */
internal class SafBackupStorage(
    private val context: Context,
    private val treeUri: Uri,
    displayName: String,
) : BackupStorage {

    private val resolver get() = context.contentResolver

    private val treeDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override val label: String = displayName.ifBlank { treeUri.toString() }

    private fun parentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)

    private fun childrenUri(): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

    private val nameProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    /** 授权还在不在、目录还能不能读；只做一次有界查询，不写盘。 */
    fun isAccessible(): Boolean = runCatching {
        resolver.query(childrenUri(), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { cursor -> cursor.moveToFirst(); true }
            ?: false
    }.getOrDefault(false)

    override fun entries(): List<BackupEntry> {
        val result = mutableListOf<BackupEntry>()
        runCatching {
            resolver.query(childrenUri(), nameProjection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val size = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                    result += BackupEntry(name, size)
                }
            }
        }.onFailure { error ->
            AndroidAgentLogger.warn("SAF backup list failed: type=${error.safeLogType()}")
        }
        return result.sortedByDescending { it.name }
    }

    private fun documentIdOf(name: String): String? {
        val selection = DocumentsContract.Document.COLUMN_DISPLAY_NAME + "=?"
        return runCatching {
            resolver.query(childrenUri(), nameProjection, selection, arrayOf(name), null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    override fun create(name: String): BackupHandle? {
        val documentUri = runCatching {
            DocumentsContract.createDocument(resolver, parentUri(), MIME_TYPE, name)
        }.onFailure { error ->
            AndroidAgentLogger.warn("SAF backup create failed: type=${error.safeLogType()}")
        }.getOrNull() ?: return null
        return SafHandle(name, documentUri)
    }

    override fun deleteName(name: String): Boolean {
        val id = documentIdOf(name) ?: return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        return runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    override fun openRead(name: String): InputStream? {
        val id = documentIdOf(name) ?: return null
        return runCatching {
            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
        }.getOrNull()
    }

    private inner class SafHandle(name: String, private val documentUri: Uri) : BackupHandle(name) {

        override val size: Long
            get() = runCatching {
                resolver.query(
                    documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L } ?: 0L
            }.getOrDefault(0L)

        override fun openOutput(): OutputStream? =
            runCatching { resolver.openOutputStream(documentUri, "wt") }.getOrNull()

        override fun delete(): Boolean =
            runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }.getOrDefault(false)

        override fun renameTo(newName: String): Boolean = runCatching {
            DocumentsContract.renameDocument(resolver, documentUri, newName) != null
        }.onFailure { error ->
            AndroidAgentLogger.warn("SAF backup rename failed: type=${error.safeLogType()}")
        }.getOrDefault(false)

        override fun verifyFullBackup(context: Context): EtaFullBackupArchive.EtaFullBackupCheck =
            EtaFullBackupArchive.verify(context, documentUri)

        /** SAF 落点：借 FileDescriptor.sync 把已写字节落盘，再交给 rename。 */
        override fun syncBeforeRename(): Boolean = runCatching {
            resolver.openFileDescriptor(documentUri, "rw")?.use { pfd ->
                pfd.fileDescriptor.sync()
            }
            true
        }.getOrDefault(false)
    }

    private companion object {
        /** 备份是 JSON / zip，统一按二进制流处理，避免 DocumentsUI 按 MIME 追加扩展名。 */
        const val MIME_TYPE = "application/octet-stream"
    }
}

/** 解析 SAF 授权目录的展示名；取不到时回退 URI 尾段。 */
internal fun safTreeDisplayName(context: Context, treeUri: Uri): String {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
    if (!documentId.isNullOrBlank()) {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val name = runCatching {
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        if (!name.isNullOrBlank()) return name
    }
    return treeUri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
}