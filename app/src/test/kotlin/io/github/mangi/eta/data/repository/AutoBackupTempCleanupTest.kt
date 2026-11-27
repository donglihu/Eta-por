package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消 / 失败路径必须丢掉 `.part`。
 *
 * 守的是 2026-11-26 的真机事故：自动备份跑到一半进程被杀，`runNow` 的 catch 里
 * `if (error is CancellationException) throw error` 直接重抛，`handle.delete()` 根本没执行，
 * 备份目录留下 1.53 GB 的 `Eta-全量备份-….part`。
 *
 * 启动预清理（`runNow` 里的 `endsWith(TEMP_SUFFIX)` 过滤）确实能兜，但 `runLocked` 有三个
 * 前置 `Skipped` 返回（未开启 / 未到周期 / 数据没变化），所以「下一次会清」是有条件的。
 *
 * 这里只测 [AutoBackupRunner.discardTempOnFailure] 这个纯逻辑单元：整条 `runNow` 依赖
 * Context、DataStore 与真实导出，纯 JVM 起不来，硬测只能靠 Robolectric，
 * 而本机 aarch64 上 Robolectric 会被 `AssumptionViolatedException` 静默跳过（等于没测）。
 */
class AutoBackupTempCleanupTest {

    /** 只记录 `delete()` 是否被调用；其余成员这条路径用不到。 */
    private class FakeHandle(
        private val deleteResult: Boolean = true,
        private val deleteThrows: Throwable? = null,
    ) : BackupHandle("Eta-自动备份-20260101-000000.eta-backup.json.part") {
        var deleteCalls = 0
            private set

        override val size: Long get() = 0L

        override fun openOutput(): OutputStream? = null

        override fun delete(): Boolean {
            deleteCalls++
            deleteThrows?.let { throw it }
            return deleteResult
        }

        override fun renameTo(newName: String): Boolean = false

        override fun verifyFullBackup(context: Context): EtaFullBackupArchive.EtaFullBackupCheck =
            EtaFullBackupArchive.EtaFullBackupCheck(ok = false, entries = 0, bytes = 0L)
    }

    @Test
    fun cancelledBackupDropsTempFile() {
        val handle = FakeHandle()

        val cleanup = AutoBackupRunner.discardTempOnFailure(handle, committed = false)

        assertEquals("取消路径必须删一次 .part", 1, handle.deleteCalls)
        assertTrue(cleanup.attempted)
        assertTrue("删除成功时返回 deleted=true", cleanup.deleted)
        assertNull(cleanup.error)
    }

    @Test
    fun committedBackupIsNeverDeleted() {
        val handle = FakeHandle()

        val cleanup = AutoBackupRunner.discardTempOnFailure(handle, committed = true)

        assertEquals("rename 成功后 handle 指向正式备份，不能再删", 0, handle.deleteCalls)
        assertFalse("committed 时不尝试删除", cleanup.attempted)
        assertFalse(cleanup.deleted)
    }

    @Test
    fun deleteFailureDoesNotEscape() {
        // 清理失败不能顶掉原始异常：否则日志里看到的是「删除失败」，
        // 真正的取消 / 导出错误就被盖掉了。
        val handle = FakeHandle(deleteThrows = IllegalStateException("boom"))

        val cleanup = AutoBackupRunner.discardTempOnFailure(handle, committed = false)

        assertEquals(1, handle.deleteCalls)
        assertTrue(cleanup.attempted)
        assertFalse("删除抛错时 deleted=false，且不向上抛", cleanup.deleted)
        assertTrue("异常要回传给调用方打日志", cleanup.error is IllegalStateException)
    }

    @Test
    fun deleteReturningFalseIsReported() {
        val handle = FakeHandle(deleteResult = false)

        val cleanup = AutoBackupRunner.discardTempOnFailure(handle, committed = false)

        assertEquals(1, handle.deleteCalls)
        assertTrue(cleanup.attempted)
        assertFalse(cleanup.deleted)
        assertNull(cleanup.error)
    }
}
