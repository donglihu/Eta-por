package io.github.mangi.eta.ui.screens.files

import io.github.mangi.eta.ui.app.WorkspaceEntry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 纯 JVM 用例（arm64 可真跑，不依赖 Robolectric）。
 * 覆盖自 aicode 移植的树状态机核心：懒展开、写操作定点失效、剪贴板冲突、trash 语义。
 */
class WorkspaceTreeStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 临时目录上的最小 TreeFileAccess：root 即 [root]，路径相对 root。 */
    private class TempAccess(rootArg: File) : TreeFileAccess {
        private val root: File = rootArg.canonicalFile
        private val trash = File(root, ".trash")

        private fun resolve(path: String): File {
            val candidate = if (path.isBlank()) root else File(root, path)
            val canonical = candidate.canonicalFile
            require(canonical.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
            return canonical
        }

        override suspend fun list(path: String): List<WorkspaceEntry> {
            val directory = resolve(path)
            if (!directory.isDirectory) throw java.io.IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
            val children = directory.listFiles() ?: throw java.io.IOException("WORKSPACE_DIRECTORY_UNREADABLE")
            return children.filter { it.isFile || it.isDirectory }.map { child ->
                WorkspaceEntry(
                    path = child.absolutePath.removePrefix(root.absolutePath).trimStart('/'),
                    name = child.name,
                    directory = child.isDirectory,
                    size = if (child.isDirectory) 0L else child.length(),
                )
            }
        }

        override fun isValidEntryName(name: String): Boolean {
            val t = name.trim()
            return t.isNotEmpty() && !t.contains('/') && !t.contains('\\') && t != "." && t != ".."
        }

        override suspend fun writeText(path: String, content: String, overwrite: Boolean) {
            val file = resolve(path)
            if (file.exists() && !overwrite) throw java.nio.file.FileAlreadyExistsException(file.path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }

        override suspend fun mkdirs(path: String) {
            resolve(path).mkdirs()
        }

        override suspend fun rename(path: String, newName: String): String {
            val source = resolve(path)
            val target = File(source.parentFile, newName.trim())
            if (target.exists()) throw java.nio.file.FileAlreadyExistsException(target.path)
            if (!source.renameTo(target)) throw java.io.IOException("WORKSPACE_RENAME_FAILED")
            return target.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        }

        override suspend fun deleteToTrash(path: String): File {
            val source = resolve(path)
            if (source.toPath().startsWith(trash.toPath())) throw java.io.IOException("WORKSPACE_DELETE_TRASH_DENIED")
            trash.mkdirs()
            val destination = File(trash, "t-${source.name}")
            if (!source.renameTo(destination)) throw java.io.IOException("WORKSPACE_DELETE_MOVE_FAILED")
            return destination
        }

        override suspend fun copy(path: String, newPath: String, overwrite: Boolean) {
            val source = resolve(path)
            val target = resolve(newPath)
            if (target.exists() && !overwrite) throw java.nio.file.FileAlreadyExistsException(target.path)
            source.copyRecursively(target, overwrite = true)
        }

        override fun absolutePath(path: String): String = resolve(path).absolutePath
    }

    private class NoopLogger : io.github.mangi.eta.core.AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private fun state(access: TreeFileAccess, saved: MutableSet<String>): WorkspaceTreeState {
        return WorkspaceTreeState(
            store = access,
            scope = CoroutineScope(Dispatchers.IO + Job()),
            logger = NoopLogger(),
            expansionLoader = { setOf(WorkspaceTreeState.ROOT) },
            expansionSaver = { _, value -> saved.clear(); saved.addAll(value) },
            observerFactory = null,
        )
    }

    private suspend fun WorkspaceTreeState.awaitSuccess(): WorkspaceTreeState.BrowseState.Success {
        while (true) {
            val value = browseState.value
            if (value is WorkspaceTreeState.BrowseState.Success) return value
            delay(10)
        }
    }

    @Test
    fun lazyExpandBuildsFlatNodesAndCollapseRemovesDescendants() = runBlocking {
        val root = tmp.newFolder("ws")
        File(root, "sub").mkdirs()
        File(root, "sub/deep").mkdirs()
        File(root, "sub/a.txt").writeText("A")
        val saved = mutableSetOf<String>()
        val tree = state(TempAccess(root), saved)
        val collector = launch { tree.browseState.collect {} }

        val initial = withTimeout(5000) { tree.awaitSuccess() }
        // 初始只展开 root：sub 一行、未展开、没有 a.txt
        assertFalse(initial.nodes[0].isExpanded)

        tree.toggleExpand("sub")
        val expanded = tree.awaitNodesMatching { nodes -> nodes.any { it.path == "sub/deep" } }
        assertTrue(expanded.map { it.path }.containsAll(listOf("sub", "sub/a.txt", "sub/deep")))
        assertTrue(tree.expandedPaths.value.contains("sub"))
        assertTrue(saved.contains("sub"))

        tree.toggleExpand("sub")
        tree.awaitNodesMatching { nodes -> nodes.size == 1 }
        assertFalse(tree.expandedPaths.value.contains("sub"))
        collector.cancel()
    }

    @Test
    fun createRenameDeleteRefreshAffectedParentOnly() = runBlocking {
        val root = tmp.newFolder("ws")
        File(root, "d").mkdirs()
        val tree = state(TempAccess(root), mutableSetOf())
        val collector = launch { tree.browseState.collect {} }
        withTimeout(5000) { tree.awaitSuccess() }

        tree.toggleExpand("d")
        tree.awaitNodesMatching { nodes -> nodes.any { it.path == "d" && it.isExpanded } }

        // 新建文件：写完后树里出现（写操作定点失效父目录 d）
        tree.createFile("d", "new.txt") { }
        tree.awaitNodesMatching { nodes -> nodes.any { it.path == "d/new.txt" } }

        // 重命名：节点跟着变
        tree.rename("d/new.txt", "renamed.txt") { }
        tree.awaitNodesMatching { nodes -> nodes.any { it.path == "d/renamed.txt" } }

        // 删除：节点消失，文件进了 trash
        tree.delete("d/renamed.txt") { }
        tree.awaitNodesMatching { nodes -> nodes.none { it.path == "d/renamed.txt" } }
        assertTrue(File(root, ".trash").isDirectory)
        collector.cancel()
    }

    @Test
    fun pasteConflictSurfacesAndOverwriteResolves() = runBlocking {
        val root = tmp.newFolder("ws")
        File(root, "a").mkdirs()
        File(root, "b").mkdirs()
        File(root, "a/x.txt").writeText("FROM_A")
        File(root, "b/x.txt").writeText("FROM_B")
        val tree = state(TempAccess(root), mutableSetOf())
        val collector = launch { tree.browseState.collect {} }
        withTimeout(5000) { tree.awaitSuccess() }

        tree.copyEntry("a/x.txt", "x.txt")
        var pasted = false
        tree.paste("b") { pasted = it }
        withTimeout(5000) { while (tree.pasteConflict.value == null) delay(10) }
        assertEquals("a/x.txt" to "b/x.txt", tree.pasteConflict.value)
        assertFalse(pasted)
        tree.dismissPasteConflict()
        assertEquals("FROM_B", File(root, "b/x.txt").readText())

        tree.paste("b") { }
        withTimeout(5000) { while (tree.pasteConflict.value == null) delay(10) }
        tree.pasteOverwrite()
        withTimeout(5000) { while (File(root, "b/x.txt").readText() != "FROM_A") delay(10) }
        assertEquals("FROM_A", File(root, "b/x.txt").readText())
        collector.cancel()
    }

    @Test
    fun cutPasteRemovesSourceIntoTrash() = runBlocking {
        val root = tmp.newFolder("ws")
        File(root, "a").mkdirs()
        File(root, "b").mkdirs()
        File(root, "a/move.txt").writeText("M")
        val tree = state(TempAccess(root), mutableSetOf())
        val collector = launch { tree.browseState.collect {} }
        withTimeout(5000) { tree.awaitSuccess() }

        tree.cutEntry("a/move.txt", "move.txt")
        var pasteResult: Boolean? = null
        tree.paste("b") { pasteResult = it }
        withTimeout(5000) {
            while (!File(root, "b/move.txt").exists() || File(root, "a/move.txt").exists()) delay(10)
        }
        assertTrue(File(root, "b/move.txt").exists())
        assertFalse(File(root, "a/move.txt").exists())
        assertTrue(File(root, ".trash").listFiles()!!.any { it.name.contains("move.txt") })
        assertEquals(null, tree.clipboard.value)
        collector.cancel()
    }

}
private suspend fun WorkspaceTreeState.awaitNodesMatching(
    condition: (List<WorkspaceTreeState.TreeNode>) -> Boolean,
): List<WorkspaceTreeState.TreeNode> = withTimeout(5000) {
    while (true) {
        val value = browseState.value
        if (value is WorkspaceTreeState.BrowseState.Success && condition(value.nodes)) return@withTimeout value.nodes
        delay(10)
    }
    @Suppress("UNREACHABLE_CODE")
    error("unreachable")
}
