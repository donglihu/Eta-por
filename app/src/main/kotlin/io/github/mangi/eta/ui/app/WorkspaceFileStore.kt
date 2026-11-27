package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class WorkspaceEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

/** 浏览器内联预览的文件大小上限：超过就不读全量，交给外部应用打开。 */
internal const val WORKSPACE_PREVIEW_MAX_BYTES: Long = 2L * 1024 * 1024

/** 二进制嗅探采样字节数（与 git / aicode 一致：开头 8000 字节内出现 NUL 判二进制）。 */
internal const val WORKSPACE_BINARY_SNIFF_BYTES: Int = 8000

internal class WorkspaceFileStore(
    context: Context,
    private val backendFor: (File) -> FileToolBackend = ::workspaceBackend,
) {
    private val appContext = context.applicationContext

    /**
     * 浏览的根目录：root 工作区（/data/local/tmp/eta，Linux 侧的 /workspace）。
     *
     * 不用 App 私有的 terminal-user/workspace：那里只有 proot 和 skill-backup，
     * 用户真正在用的项目、临时、产物都在 root 工作区。私有目录那个位置留给终端默认 cwd。
     */
    private val root: File = File(TerminalRuntime.ROOT_WORKSPACE_PATH).canonicalFile

    /**
     * backend 只建一次。每次 new ShellProcessSupervisor 都会新起一条 shell 进程，
     * 而 list() 是按目录逐个调的，根目录 + 每个展开目录各起一次进程，页面就会明显卡。
     */
    private val backend: FileToolBackend by lazy { backendFor(root) }

    /** 写操作内核（自 aicode 移植）：删除走 .trash、写文件原子落位，路径校验与读侧同一套防线。 */
    private val ops = WorkspaceFileOps(root)

    /**
     * 列目录。走 [java.io.File] API，不经 [io.github.mangi.eta.agent.terminal.FileToolBackend]。
     *
     * 原因是那条后端按「模型读文件」设计：逐条目跑一次 stat，输出超 1 MB 就整体判失败，
     * 而工作区根目录躺着 40 MB 的 db 和 12 MB 的 APK，元数据一次就撑爆预算——表现出来
     * 就是目录看着在转圈、结果为空。浏览要的是「快而全」，File API 直接读目录项，
     * 没有分页、没有逐条 stat，也不受 busybox find 不支持 -printf 的限制。
     *
     * App 对 /data/local/tmp/eta 有读权限（已验证），所以这里不需要 root。
     */
    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val directory = resolveFile(path)
        if (!directory.isDirectory) throw java.io.IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
        val children = directory.listFiles() ?: throw java.io.IOException("WORKSPACE_DIRECTORY_UNREADABLE")
        children.asSequence()
            // 符号链接不跟随：跟随可能绕出工作区，也可能在循环链接上打转。
            .filter { it.isFile || it.isDirectory }
            .map { child ->
                WorkspaceEntry(
                    path = child.absolutePath.removePrefix(root.absolutePath).trimStart('/'),
                    name = child.name,
                    directory = child.isDirectory,
                    size = if (child.isDirectory) 0L else child.length(),
                )
            }
            // FUSE 存储（外部/模拟存储）上 readdir 可能重复返回同一条目（aicode 实测教训，AOSP ReaddirHelper 同做此处理）：
            // 同名重复会产生重复节点 path，撞坏 LazyColumn 的 key。
            .distinctBy { it.name }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
            .toList()
    }

    /** 把相对路径解析成 root 内的 File，并校验没有越出 root。 */
    /** 相对路径 → 绝对路径。图片预览这类需要真实路径的地方用。 */
    fun absolutePath(path: String): String = resolveFile(path).absolutePath

    private fun resolveFile(path: String): File {
        val candidate = if (path.isBlank()) root else File(root, path)
        val canonical = candidate.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        return canonical
    }

    /**
     * 读取工作区内某个文件的文本内容，用于文件浏览器预览。
     *
     * 同样走 [java.io.File]：root 工作区里躺着 12 MB 的 APK 和 40 MB 的 db，
     * 走 shell 后端的 readBytes 会按 16 KB 一块地要，大文件光启动进程就够呛。
     */
    suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val file = resolveFile(path)
        if (!file.isFile) throw java.io.IOException("WORKSPACE_NOT_FILE")
        if (file.length() > WORKSPACE_PREVIEW_MAX_BYTES) throw java.io.IOException("WORKSPACE_FILE_TOO_LARGE")
        file.readText(Charsets.UTF_8)
    }

    /**
     * 把工作区里的文件按字节原样复制到 [destination]。
     *
     * 与 [readText] 的区别：不做 UTF-8 解码、不受 2 MB 预览上限约束，
     * 所以 APK / 图片 / 压缩包这类二进制也能原样搬出去给别的应用。
     */
    suspend fun copyTo(path: String, destination: File): Long = withContext(Dispatchers.IO) {
        val source = resolveFile(path)
        if (!source.isFile) throw java.io.IOException("WORKSPACE_NOT_FILE")
        destination.parentFile?.mkdirs()
        source.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        destination.length()
    }

    // region 写操作（自 aicode 侧边栏文件区移植；UI 层调用入口）

    /** 新建/覆盖写文本文件；文件树「新建文件」用 overwrite=false 防覆盖。 */
    suspend fun writeText(path: String, content: String, overwrite: Boolean = true) = withContext(Dispatchers.IO) {
        ops.writeText(path, content, overwrite)
    }

    /** 新建文件夹（含父目录）。 */
    suspend fun mkdirs(path: String) = withContext(Dispatchers.IO) {
        ops.mkdirs(path)
    }

    /** 同目录重命名，返回新相对路径。 */
    suspend fun rename(path: String, newName: String): String = withContext(Dispatchers.IO) {
        ops.rename(path, newName)
    }

    /** 删除 = 移入 root/.trash/，返回落位绝对路径（物理清理留给巡检/人工确认）。 */
    suspend fun deleteToTrash(path: String): File = withContext(Dispatchers.IO) {
        ops.deleteToTrash(path)
    }

    /** 复制到新路径；粘贴用。 */
    suspend fun copy(path: String, newPath: String, overwrite: Boolean = false) = withContext(Dispatchers.IO) {
        ops.copy(path, newPath, overwrite)
    }

    /** 内容指纹 `sha256:<hex>`；编辑器保存前冲突比对用。文件不存在或非普通文件返回 null。 */
    suspend fun fingerprint(path: String): String? = withContext(Dispatchers.IO) {
        ops.fingerprint(path)
    }

    /** 名称合法性（不含路径分隔符、非 . / ..）。 */
    fun isValidEntryName(name: String): Boolean = ops.isValidEntryName(name)

    /** 普通文件大小（字节）；不存在或不是文件时返回 -1。编辑器「过大拒绝打开」预检用。 */
    suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        val file = ops.resolve(path)
        if (file.isFile) file.length() else -1L
    }

    /** 读文件头部原始字节（二进制嗅探用，与 git 一致只看开头一段）；按 [maxBytes] 截断。 */
    suspend fun readHeadBytes(path: String, maxBytes: Int = WORKSPACE_BINARY_SNIFF_BYTES): ByteArray = withContext(Dispatchers.IO) {
        val file = ops.resolve(path)
        if (!file.isFile) throw java.io.IOException("WORKSPACE_NOT_FILE")
        file.inputStream().use { input ->
            val buffer = ByteArray(maxBytes)
            var offset = 0
            while (offset < maxBytes) {
                val count = input.read(buffer, offset, maxBytes - offset)
                if (count < 0) break
                offset += count
            }
            if (offset == maxBytes) buffer else buffer.copyOf(offset)
        }
    }

    // endregion

    suspend fun importFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        AgentFileReferenceGateway.importDocumentUri(appContext, uri) is AgentFileReferenceGateway.Resolution.Success
    }

    suspend fun exportFile(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        check(stat.kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
        output.use {
            val file = File(source)
            if (file.canRead()) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } else {
                var offset = 0L
                while (offset < stat.sizeBytes) {
                    ensureActive()
                    val bytes = backend.readBytes(source, offset, 16 * 1024)
                    check(bytes.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
                    output.write(bytes)
                    offset += bytes.size
                }
            }
        }
    }

    private fun resolve(backend: FileToolBackend, path: String): String = backend.resolve(path).also {
        require(File(it).toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
    }
}

private fun workspaceBackend(root: File): FileToolBackend {
    if (!RootAccess.isGranted) return LocalFileToolBackend(root)
    val supervisor = ShellProcessSupervisor()
    return ShellFileToolBackend("android", "root", root.path, root.path) { command, input, limit ->
        runOneShotShell(supervisor, "root", command, timeoutSeconds = 20, stdin = input, maxOutputBytes = limit)
    }
}

/** 预览用读取：失败返回 null（不抛给 UI）。 */
internal suspend fun readWorkspaceText(store: WorkspaceFileStore, relativePath: String): String? =
    try {
        store.readText(relativePath)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
