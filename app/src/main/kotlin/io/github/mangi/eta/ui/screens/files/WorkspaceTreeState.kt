package io.github.mangi.eta.ui.screens.files

import android.os.FileObserver
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 树状态机需要的最小文件访问面；抽出来是为了纯 JVM 单测可以直接实现（arm64 无 Robolectric）。 */
internal interface TreeFileAccess {
    suspend fun list(path: String): List<WorkspaceEntry>
    fun isValidEntryName(name: String): Boolean
    suspend fun writeText(path: String, content: String, overwrite: Boolean)
    suspend fun mkdirs(path: String)
    suspend fun rename(path: String, newName: String): String
    suspend fun deleteToTrash(path: String): java.io.File
    suspend fun copy(path: String, newPath: String, overwrite: Boolean)
    fun absolutePath(path: String): String
}

/** [WorkspaceFileStore] → [TreeFileAccess] 适配；生产侧唯一实现。 */
internal class WorkspaceFileTreeAccess(private val store: WorkspaceFileStore) : TreeFileAccess {
    override suspend fun list(path: String) = store.list(path)
    override fun isValidEntryName(name: String) = store.isValidEntryName(name)
    override suspend fun writeText(path: String, content: String, overwrite: Boolean) =
        store.writeText(path, content, overwrite)
    override suspend fun mkdirs(path: String) = store.mkdirs(path)
    override suspend fun rename(path: String, newName: String): String = store.rename(path, newName)
    override suspend fun deleteToTrash(path: String) = store.deleteToTrash(path)
    override suspend fun copy(path: String, newPath: String, overwrite: Boolean) =
        store.copy(path, newPath, overwrite)
    override fun absolutePath(path: String): String = store.absolutePath(path)
}

/**
 * 工作区文件树状态机（自 aicode `AIAgentViewModel` 文件浏览部分移植，2026-11-27）。
 *
 * 保留 aicode 的核心机制：
 * - 目录列表缓存 + 脏目录：展开只重读该目录，折叠只按缓存重排，写操作只失效受影响父目录；
 * - 命令循环串行消费并合并刷新命令（同目录连续事件只重读一次，整树刷新吞掉其余）；
 * - 展开集持久化；目录监听只在有人订阅时挂载（退后台 5 秒后停止，回前台整树重读）；
 * - 写操作统一包装：IO 派发 + 忙标记 + 成功后按父目录定点失效。
 *
 * 按 ETA 场景的裁剪与替换：
 * - 单 root（[io.github.mangi.eta.agent.terminal.TerminalRuntime.ROOT_WORKSPACE_PATH]），无远程/profile/backend 维度，工作区根即唯一根；
 * - 目录监听用 [FileObserver]（aicode 是自建 inotify 桥），事件可达性已探针实测；
 * - 删除走 [WorkspaceFileStore.deleteToTrash]（ETA 纪律），剪切粘贴的源删除同样入 trash；
 * - aicode 的根级 .gitignore 过滤暂未移植（ETA 浏览器原本也没有），为后续独立项。
 */
internal class WorkspaceTreeState(
    private val store: TreeFileAccess,
    private val scope: CoroutineScope,
    private val logger: AgentLogger,
    /** 展开集读取（按 key）；测试注入内存实现。 */
    private val expansionLoader: (String) -> Set<String> = { setOf(ROOT) },
    /** 展开集保存。 */
    private val expansionSaver: (String, Set<String>) -> Unit = { _, _ -> },
    /** 目录监听工厂；生产实现见 [workspaceObserverFactory]，测试传 null 关闭监听。 */
    private val observerFactory: WorkspaceObserverFactory? = workspaceObserverFactory(),
    private val expansionKey: String = "default",
) {

    sealed interface BrowseState {
        data object Loading : BrowseState
        data class Success(val nodes: List<TreeNode>) : BrowseState
        data class Error(val detail: String?) : BrowseState
    }

    /** 扁平化树节点；[entry.name] 即显示名，[path] 为相对 root 的路径（root 本身是空串）。 */
    data class TreeNode(
        val entry: WorkspaceEntry,
        val path: String,
        val depth: Int,
        val isExpanded: Boolean,
        val hasError: Boolean = false,
    )

    /** 单槽剪贴板（自 aicode BrowseClipboard 移植）。 */
    data class Clipboard(val sourcePath: String, val sourceName: String, val isCut: Boolean)

    /** 目录监听句柄。 */
    interface WatchHandle { fun stop() }

    /** 监听工厂：给目录挂监听，目录内容变化时回调（可从任意线程）。 */
    fun interface WorkspaceObserverFactory {
        fun watch(path: String, absolutePath: String, onChanged: () -> Unit): WatchHandle
    }

    /** root 本身用空串表示（与 [WorkspaceFileStore.list] 的约定一致）。 */
    companion object {
        const val ROOT = ""
        private const val TAG = "WorkspaceTreeState"

        /** 生产监听工厂：FileObserver，覆盖增删改名与写入收尾；debounce 在状态机侧不做（合并去重由命令循环承担）。 */
        fun workspaceObserverFactory(): WorkspaceObserverFactory =
            WorkspaceObserverFactory { _, absolutePath, onChanged ->
                val mask = FileObserver.CREATE or FileObserver.DELETE or
                    FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
                    FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF
                val observer = object : FileObserver(File(absolutePath), mask) {
                    override fun onEvent(event: Int, path: String?) {
                        onChanged()
                    }
                }
                observer.startWatching()
                object : WatchHandle {
                    override fun stop() {
                        observer.stopWatching()
                    }
                }
            }
    }

    private val _expandedPaths = MutableStateFlow(expansionLoader(expansionKey))
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    private val _browseState = MutableStateFlow<BrowseState>(BrowseState.Loading)

    /** 是否有人在看文件树；为假时停掉全部监听。 */
    @Volatile
    private var subscribed = false

    val browseState: StateFlow<BrowseState> = _browseState
        .onStart {
            subscribed = true
            submit(Reload(full = true))
        }
        .onCompletion {
            subscribed = false
            stopAllWatches()
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), BrowseState.Loading)

    /** 正在展开、等待列目录返回的目录；UI 在该行显示等待动画。 */
    private val _expandingPath = MutableStateFlow<String?>(null)
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()

    /** 正在执行写操作的条目路径集合；UI 显示等待动画。 */
    private val _busyPaths = MutableStateFlow<Set<String>>(emptySet())
    val busyPaths: StateFlow<Set<String>> = _busyPaths.asStateFlow()

    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard.asStateFlow()

    /** 粘贴冲突（源路径 to 目标路径）；UI 弹窗确认后调 [pasteOverwrite]。 */
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    // 缓存只在 commandLoop 所在协程读写（同 aicode 纪律：对外方法只投命令，不直接碰缓存）。
    private val listings = HashMap<String, List<WorkspaceEntry>>()
    private val dirErrors = HashMap<String, String>()
    private val dirtyDirs = mutableSetOf<String>()
    private var dirtyAll = false
    private val watchJobs = HashMap<String, WatchHandle>()

    private data class Reload(val paths: Set<String> = emptySet(), val full: Boolean = false)

    private val commands = Channel<Reload>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) { commandLoop() }
    }

    private suspend fun commandLoop() {
        while (true) {
            val first = commands.receive()
            var full = first.full
            val paths = first.paths.toMutableSet()
            while (true) {
                val next = commands.tryReceive().getOrNull() ?: break
                full = full || next.full
                paths += next.paths
            }
            if (!subscribed) {
                stopAllWatches()
                continue
            }
            if (full) {
                listings.clear()
                dirErrors.clear()
                dirtyDirs.clear()
                dirtyAll = true
            } else {
                dirtyDirs += paths
            }
            publishNodes()
            val expanding = _expandingPath.value
            if (expanding != null && (full || expanding in paths)) _expandingPath.value = null
        }
    }

    private fun submit(reload: Reload) {
        commands.trySend(reload)
    }

    private suspend fun publishNodes() {
        var nodes = buildNodes() ?: return
        if (pruneExpansion()) nodes = buildNodes() ?: return
        _browseState.value = BrowseState.Success(nodes)
        pruneCache()
        syncWatches()
    }

    /** 按展开集拼扁平节点列表；只读缓存，缺失/失效目录经 [ensureDir]。返回 null 表示 root 读不到。 */
    private suspend fun buildNodes(): List<TreeNode>? {
        val expanded = _expandedPaths.value
        val rootEntries = if (ROOT in expanded) ensureDir(ROOT) else listings[ROOT]
        if (ROOT in expanded && rootEntries == null) {
            _browseState.value = BrowseState.Error(dirErrors[ROOT])
            return null
        }
        val nodes = mutableListOf<TreeNode>()
        if (ROOT in expanded && rootEntries != null) {
            appendChildren(ROOT, rootEntries, depth = 1, expanded = expanded, out = nodes)
        }
        return nodes
    }

    /** 取目录列表：命中缓存且未失效直接用，否则读一次。失败保留旧列表并登记原因。 */
    private suspend fun ensureDir(path: String): List<WorkspaceEntry>? {
        val cached = listings[path]
        val dirty = dirtyAll || path in dirtyDirs
        if (cached != null && !dirty) return cached
        dirtyDirs -= path
        try {
            val entries = store.list(path)
            // FUSE 存储 readdir 可能重复返回同一条目（aicode 实测教训）：去重防 LazyColumn key 撞车。
            listings[path] = entries.distinctBy { it.name }.sortedWith(
                compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() },
            )
            dirErrors -= path
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.error("$TAG: 列目录失败: $path", e)
            dirErrors[path] = e.message ?: "WORKSPACE_DIRECTORY_UNREADABLE"
        }
        return listings[path]
    }

    private suspend fun appendChildren(
        parent: String,
        entries: List<WorkspaceEntry>,
        depth: Int,
        expanded: Set<String>,
        out: MutableList<TreeNode>,
    ) {
        for (entry in entries) {
            val path = childPath(parent) + entry.name
            val open = entry.directory && path in expanded
            if (!open) {
                out += TreeNode(entry, path, depth, isExpanded = false)
                continue
            }
            val children = ensureDir(path)
            out += TreeNode(entry, path, depth, isExpanded = true, hasError = path in dirErrors)
            if (children != null) appendChildren(path, children, depth + 1, expanded, out)
        }
    }

    private fun childPath(path: String): String = when {
        path.isEmpty() -> ""  // root 的子路径直接用名字，不拼前导分隔符
        path.endsWith("/") -> path
        else -> "$path/"
    }

    private fun parentDirOf(path: String): String? {
        if (path == ROOT) return null
        val index = path.lastIndexOf('/')
        return if (index <= 0) ROOT else path.substring(0, index)
    }

    /** 清掉已不在树上的展开路径（目录被删/改名）；root 空列表（新工作区）不据此判定消失。 */
    private fun pruneExpansion(): Boolean {
        val expanded = _expandedPaths.value
        val gone = mutableSetOf<String>()
        for (path in expanded.sortedBy { it.count { c -> c == '/' } }) {
            if (path == ROOT) continue
            val parent = parentDirOf(path) ?: continue
            if (parent in gone) {
                gone += path
                continue
            }
            val listing = listings[parent] ?: continue
            if (parent == ROOT && listing.isEmpty()) continue
            if (parent in dirErrors) continue
            if (listing.none { it.directory && childPath(parent) + it.name == path }) gone += path
        }
        if (gone.isEmpty()) return false
        val remaining = expanded - gone
        _expandedPaths.value = remaining
        expansionSaver(expansionKey, remaining)
        return true
    }

    /** 缓存只保留当前展开集与 root，避免长期占用内存。 */
    private fun pruneCache() {
        val keep = _expandedPaths.value + ROOT
        listings.keys.retainAll(keep)
        dirErrors.keys.retainAll(keep)
    }

    /**
     * 按展开集增删监听：只新开/关闭变化的那几个（否则每次展开都重建全部 FileObserver）。
     * FileObserver 自带事件线程，工厂调用返回即完成挂载，不需要协程承载。
     */
    private fun syncWatches() {
        val factory = observerFactory ?: run {
            stopAllWatches()
            return
        }
        val wanted = if (!subscribed) emptySet() else _expandedPaths.value + ROOT
        for ((path, handle) in watchJobs.toList()) {
            if (path !in wanted) {
                try {
                    handle.stop()
                } catch (e: Exception) {
                    logger.error("$TAG: 目录监听卸载失败: $path", e)
                }
                watchJobs.remove(path)
            }
        }
        for (path in wanted) {
            if (path in watchJobs) continue
            try {
                val absolute = store.absolutePath(path)
                watchJobs[path] = factory.watch(path, absolute) {
                    submit(Reload(paths = setOf(path)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 目录监听挂载失败: $path", e)
            }
        }
    }

    private fun stopAllWatches() {
        for ((path, handle) in watchJobs.toList()) {
            try {
                handle.stop()
            } catch (e: Exception) {
                logger.error("$TAG: 目录监听卸载失败: $path", e)
            }
        }
        watchJobs.clear()
    }

    /** 名称合法性（不含路径分隔符、非 . / ..）；UI 对话框按钮可用性判断用。 */
    fun isValidEntryName(name: String): Boolean = store.isValidEntryName(name)

    /** 手动刷新：丢弃全部缓存整树重读。 */
    fun refresh() {
        submit(Reload(full = true))
    }

    /** 展开/折叠；折叠连同后代一并移出展开集。展开需等列目录返回，期间标记 expandingPath。 */
    fun toggleExpand(path: String) {
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated = if (path in current) {
            current.filterNot { it == path || it.startsWith(childPath(path)) }.toSet()
        } else {
            _expandingPath.value = path
            current + path
        }
        _expandedPaths.value = updated
        expansionSaver(expansionKey, updated)
        submit(Reload(paths = setOf(path)))
    }

    /** 重命名后把展开状态跟到新路径。 */
    private fun remapExpanded(oldPath: String, newPath: String) {
        val current = _expandedPaths.value
        if (current.none { it == oldPath || it.startsWith(childPath(oldPath)) }) return
        val updated = current.map {
            when {
                it == oldPath -> newPath
                it.startsWith(childPath(oldPath)) -> newPath + it.removePrefix(oldPath)
                else -> it
            }
        }.toSet()
        _expandedPaths.value = updated
        expansionSaver(expansionKey, updated)
    }

    /** 写操作统一包装（自 aicode mutateBrowse 移植）：忙标记 + 成功后失效受影响父目录。 */
    private fun mutate(
        busyPaths: Set<String> = emptySet(),
        onResult: (Boolean) -> Unit,
        block: suspend () -> Boolean,
    ) = scope.launch {
        if (busyPaths.isNotEmpty()) _busyPaths.value = _busyPaths.value + busyPaths
        try {
            val success = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    logger.error("$TAG: 文件操作失败", e)
                    false
                }
            }
            if (success) {
                submit(Reload(paths = busyPaths.mapNotNull { parentDirOf(it) }.toSet()))
            }
            onResult(success)
        } finally {
            if (busyPaths.isNotEmpty()) _busyPaths.value = _busyPaths.value - busyPaths
        }
    }

    /** 目标相对路径是否已存在（文件或目录）。 */
    private suspend fun entryExists(path: String): Boolean {
        val parent = parentDirOf(path) ?: return false
        val name = path.substringAfterLast('/')
        return try {
            store.list(parent).any { it.name == name }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** 新建空文件。 */
    fun createFile(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = if (store.isValidEntryName(name)) childPath(parent) + name.trim() else null
        mutate(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || entryExists(target)) return@mutate false
            store.writeText(target, "", overwrite = false)
            true
        }
    }

    /** 新建文件夹。 */
    fun createFolder(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = if (store.isValidEntryName(name)) childPath(parent) + name.trim() else null
        mutate(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || entryExists(target)) return@mutate false
            store.mkdirs(target)
            true
        }
    }

    /** 同目录重命名。 */
    fun rename(path: String, newName: String, onResult: (Boolean) -> Unit) {
        mutate(busyPaths = setOf(path), onResult = onResult) {
            val newRel = store.rename(path, newName)
            remapExpanded(path, newRel)
            true
        }
    }

    /** 删除（移入 root/.trash/）。 */
    fun delete(path: String, onResult: (Boolean) -> Unit) {
        mutate(busyPaths = setOf(path), onResult = onResult) {
            store.deleteToTrash(path)
            true
        }
    }

    // region 复制 / 剪切 / 粘贴

    fun copyEntry(path: String, name: String) {
        _clipboard.value = Clipboard(path, name, isCut = false)
    }

    fun cutEntry(path: String, name: String) {
        _clipboard.value = Clipboard(path, name, isCut = true)
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    /** 粘贴到 [targetDir]；目标同名冲突时不执行，置 pasteConflict 待确认。 */
    fun paste(targetDir: String, onResult: (Boolean) -> Unit) {
        val clip = _clipboard.value ?: return onResult(false)
        val target = "$targetDir/${clip.sourceName}"
        if (!store.isValidEntryName(clip.sourceName) ||
            target == clip.sourcePath ||
            target.startsWith(childPath(clip.sourcePath.trimEnd('/')))
        ) {
            return onResult(false)
        }
        scope.launch {
            val exists = kotlinx.coroutines.withContext(Dispatchers.IO) {
                entryExists(target)
            }
            if (exists) {
                _pasteConflict.value = clip.sourcePath to target
                onResult(false)
            } else {
                performPaste(clip, target, overwrite = false, onResult)
            }
        }
    }

    /** 冲突确认后覆盖粘贴。 */
    fun pasteOverwrite() {
        val conflict = _pasteConflict.value ?: return
        _pasteConflict.value = null
        val clip = _clipboard.value ?: return
        performPaste(clip, conflict.second, overwrite = true) { }
    }

    fun dismissPasteConflict() {
        _pasteConflict.value = null
    }

    private fun performPaste(clip: Clipboard, target: String, overwrite: Boolean, onResult: (Boolean) -> Unit) {
        mutate(busyPaths = setOf(target), onResult = onResult) {
            store.copy(clip.sourcePath, target, overwrite)
            if (clip.isCut) {
                // 剪切：源移入回收站（ETA 纪律），发生在复制成功之后。
                store.deleteToTrash(clip.sourcePath)
            }
            // 粘贴一次后失效（复制/剪切同理，与 aicode 一致；失败时 mutate 已返回 false 不清空）。
            _clipboard.value = null
            true
        }
    }
}
