package io.github.mangi.eta.ui.app

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 纯 JVM 用例（不依赖 Robolectric，arm64 上可真跑）：覆盖移植自 aicode 的写操作内核。 */
class WorkspaceFileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ops(): WorkspaceFileOps {
        val root = tmp.newFolder("ws")
        File(root, ".trash").mkdirs()
        return WorkspaceFileOps(root)
    }

    @Test
    fun writeTextIsAtomicAndOverwriteControlled() {
        val ops = ops()
        ops.writeText("a/b.txt", "v1", overwrite = false)
        assertEquals("v1", ops.resolve("a/b.txt").readText())
        // 无残留临时文件
        assertTrue(ops.resolve("a").listFiles()!!.none { it.name.startsWith(".b.txt.tmp-") })
        try {
            ops.writeText("a/b.txt", "v2", overwrite = false)
            assertFalse("expected FileAlreadyExistsException", false)
        } catch (expected: java.nio.file.FileAlreadyExistsException) {
            assertEquals("v1", ops.resolve("a/b.txt").readText())
        }
        ops.writeText("a/b.txt", "v2", overwrite = true)
        assertEquals("v2", ops.resolve("a/b.txt").readText())
    }

    @Test
    fun pathOutsideRootIsRejected() {
        val ops = ops()
        val outside = tmp.newFile("outside.txt")
        try {
            ops.resolve("../outside.txt")
            assertFalse("expected WORKSPACE_PATH_OUTSIDE_ROOT", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("WORKSPACE_PATH_OUTSIDE_ROOT"))
        }
        // 符号链接逃逸同样拒绝
        val link = File(ops.resolve(""), "link")
        link.delete(); java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        try {
            ops.writeText("link", "x")
            assertFalse("expected WORKSPACE_PATH_OUTSIDE_ROOT via symlink", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("WORKSPACE_PATH_OUTSIDE_ROOT"))
        }
    }

    @Test
    fun deleteMovesIntoTrashInsteadOfPhysicalRemoval() {
        val ops = ops()
        ops.writeText("gone.txt", "bye")
        val landed = ops.deleteToTrash("gone.txt")
        assertTrue(landed.path.contains(".trash"))
        assertEquals("bye", landed.readText())
        assertFalse(ops.resolve("gone.txt").exists())
        // 目录连同内容一并入 trash
        ops.writeText("dir/sub.txt", "deep")
        val dirLanded = ops.deleteToTrash("dir")
        assertEquals("deep", File(dirLanded, "sub.txt").readText())
        // .trash 自身不可删
        try {
            ops.deleteToTrash(".trash")
            assertFalse("expected WORKSPACE_DELETE_TRASH_DENIED", false)
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("WORKSPACE_DELETE_TRASH_DENIED"))
        }
    }

    @Test
    fun renameValidatesNameAndRefusesOverwrite() {
        val ops = ops()
        ops.writeText("old.txt", "x")
        assertEquals("new.txt", ops.rename("old.txt", "new.txt"))
        assertEquals("x", ops.resolve("new.txt").readText())
        ops.writeText("taken.txt", "y")
        try {
            ops.rename("new.txt", "taken.txt")
            assertFalse("expected FileAlreadyExistsException", false)
        } catch (expected: java.nio.file.FileAlreadyExistsException) {
            assertEquals("x", ops.resolve("new.txt").readText())
        }
        try {
            ops.rename("new.txt", "../escape")
            assertFalse("expected WORKSPACE_INVALID_ENTRY_NAME", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("WORKSPACE_INVALID_ENTRY_NAME"))
        }
    }

    @Test
    fun fingerprintMatchesAicodeSemantics() {
        val ops = ops()
        assertNull(ops.fingerprint("missing.txt"))
        ops.writeText("f.txt", "hello")
        val fp = ops.fingerprint("f.txt")
        assertNotNull(fp)
        assertTrue(fp!!.startsWith("sha256:"))
        ops.writeText("f.txt", "hello")
        assertEquals(fp, ops.fingerprint("f.txt"))
        ops.writeText("f.txt", "hello!")
        assertTrue(fp != ops.fingerprint("f.txt"))
        // 目录返回 null
        ops.mkdirs("d")
        assertNull(ops.fingerprint("d"))
    }

    @Test
    fun copyRejectsIntoSelfAndCopiesTrees() {
        val ops = ops()
        ops.writeText("tree/a.txt", "A")
        ops.writeText("tree/nested/b.txt", "B")
        ops.copy("tree", "copy-out")
        assertEquals("B", ops.resolve("copy-out/nested/b.txt").readText())
        try {
            ops.copy("tree", "tree/inner")
            assertFalse("expected WORKSPACE_COPY_INTO_SELF", false)
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("WORKSPACE_COPY_INTO_SELF"))
        }
    }

    @Test
    fun entryNameValidation() {
        val ops = ops()
        assertTrue(ops.isValidEntryName("a.txt"))
        assertTrue(ops.isValidEntryName(" a.txt "))
        assertFalse(ops.isValidEntryName(""))
        assertFalse(ops.isValidEntryName("."))
        assertFalse(ops.isValidEntryName(".."))
        assertFalse(ops.isValidEntryName("a/b"))
        assertFalse(ops.isValidEntryName("..".padEnd(2)))
    }
}
