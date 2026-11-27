package io.github.mangi.eta.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份与 WAL 的一致性（2026-11-26 审核 P1-3）。
 *
 * 守的是两件事：
 *
 * 1. 导出时只拷主库原文件、不 checkpoint WAL —— 包里少了最近提交（sqlite3 实测：主库 0 行、
 *    库内实际 2 行）；
 * 2. 还原时把归档里的 `-wal`/`-shm` 一起铺回去 —— 旧日志盖住当前库的日志，回滚甚至损坏。
 *
 * 这里**不继承 Robolectric runner**：本机 linux-aarch64 上 Robolectric 会被
 * `AssumptionViolatedException` 静默跳过，跳过＝零执行。所以断言对象是
 * [EtaFullBackupArchive] 里那几个纯逻辑函数，IO 接线由编译保证。
 */
class EtaFullBackupWalConsistencyTest {

    // ---- 1. 导出跳过 -wal/-shm/-journal -------------------------------------

    @Test
    fun exportSkipsSqliteSidecars() {
        listOf(
            "databases/eta.db-wal",
            "databases/eta.db-shm",
            "databases/eta.db-journal",
        ).forEach { relative ->
            assertTrue("$relative 必须被识别为伴生文件", EtaFullBackupArchive.isSqliteSidecar(relative))
            assertFalse("$relative 不能进备份包", EtaFullBackupArchive.shouldExport(relative))
        }
    }

    @Test
    fun exportKeepsMainDatabaseFile() {
        assertTrue("主库原文件必须进包", EtaFullBackupArchive.shouldExport("databases/eta.db"))
        assertTrue(EtaFullBackupArchive.shouldExport("shared_prefs/eta.xml"))
        assertTrue(EtaFullBackupArchive.shouldExport("files/draft.txt"))
    }

    @Test
    fun sidecarDetectionIgnoresCase() {
        assertTrue(EtaFullBackupArchive.isSqliteSidecar("databases/eta.db-WAL"))
        assertFalse(EtaFullBackupArchive.shouldExport("databases/eta.db-WAL"))
    }

    @Test
    fun exportStillHonoursExistingExclusions() {
        // 回归保护：新加的伴生文件判据不能把原有排除规则顶掉。
        assertFalse(EtaFullBackupArchive.shouldExport("files/restore-staging/private/databases/eta.db"))
        assertFalse(EtaFullBackupArchive.shouldExport("files/terminal/rootfs.img"))
        assertTrue(
            "含 Linux 环境时 terminal 要放行",
            EtaFullBackupArchive.shouldExport("files/terminal/rootfs.img", includeLinuxEnvironment = true),
        )
        assertFalse(EtaFullBackupArchive.shouldExport(""))
        assertFalse("路径穿越一律拒", EtaFullBackupArchive.shouldExport("../outside.txt"))
    }

    @Test
    fun exportRejectsSidecarEvenWhenLinuxEnvironmentIncluded() {
        assertFalse(EtaFullBackupArchive.shouldExport("databases/eta.db-wal", includeLinuxEnvironment = true))
    }

    // ---- 2. 还原丢弃归档内 stale -wal/-shm ---------------------------------

    @Test
    fun restoreDropsStaleSidecarsFromLegacyArchive() {
        val entries = listOf(
            "databases/eta.db",
            "databases/eta.db-wal",
            "databases/eta.db-shm",
            "databases/eta.db-journal",
            "shared_prefs/eta.xml",
        )

        val filtered = EtaFullBackupArchive.filterRestoreEntries(entries)

        assertEquals(
            "只应铺回主库与普通文件",
            listOf("databases/eta.db", "shared_prefs/eta.xml"),
            filtered.keep,
        )
        assertEquals(
            "旧归档里的三个伴生文件必须被单独点名丢弃",
            listOf("databases/eta.db-wal", "databases/eta.db-shm", "databases/eta.db-journal"),
            filtered.droppedSidecars,
        )
        assertTrue(filtered.droppedOther.isEmpty())
    }

    @Test
    fun restoreDecisionClassifiesEachEntry() {
        assertEquals(
            EtaFullBackupArchive.RestoreDecision.KEEP,
            EtaFullBackupArchive.restoreDecision("databases/eta.db"),
        )
        assertEquals(
            EtaFullBackupArchive.RestoreDecision.DROP_SIDECAR,
            EtaFullBackupArchive.restoreDecision("databases/eta.db-wal"),
        )
        assertEquals(
            EtaFullBackupArchive.RestoreDecision.DROP_OTHER,
            EtaFullBackupArchive.restoreDecision("files/restore-staging/x"),
        )
        assertEquals(
            EtaFullBackupArchive.RestoreDecision.DROP_OTHER,
            EtaFullBackupArchive.restoreDecision("../escape"),
        )
    }

    @Test
    fun restoreDropsSidecarsEvenWithLinuxEnvironmentIncluded() {
        val filtered = EtaFullBackupArchive.filterRestoreEntries(
            listOf("databases/eta.db", "databases/eta.db-wal"),
            includeLinuxEnvironment = true,
        )

        assertEquals(listOf("databases/eta.db"), filtered.keep)
        assertEquals(listOf("databases/eta.db-wal"), filtered.droppedSidecars)
    }

    // ---- 3. 当前库的旧 -wal/-shm 在还原前被清掉 -----------------------------

    @Test
    fun existingSidecarsAreRemovedBeforeRestore() {
        val dir = Files.createTempDirectory("eta-wal-restore").toFile()
        try {
            val database = File(dir, "databases/eta.db")
            database.parentFile?.mkdirs()
            database.writeText("main-db")
            val wal = File(database.path + "-wal").apply { writeText("stale-wal") }
            val shm = File(database.path + "-shm").apply { writeText("stale-shm") }
            val journal = File(database.path + "-journal").apply { writeText("stale-journal") }
            val unrelated = File(database.path + "-backup").apply { writeText("keep-me") }

            val removed = EtaFullBackupArchive.deleteSqliteSidecars(database)

            assertEquals(
                listOf("eta.db-journal", "eta.db-shm", "eta.db-wal"),
                removed.sorted(),
            )
            assertFalse("旧 -wal 必须被删掉", wal.exists())
            assertFalse("旧 -shm 必须被删掉", shm.exists())
            assertFalse("旧 -journal 必须被删掉", journal.exists())
            assertTrue("主库原文件不能被顺手删掉", database.exists())
            assertTrue("无关后缀文件不能被误删", unrelated.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingSidecarsAreNotReportedAsRemoved() {
        val dir = Files.createTempDirectory("eta-wal-none").toFile()
        try {
            val database = File(dir, "eta.db").apply { writeText("main-db") }

            val removed = EtaFullBackupArchive.deleteSqliteSidecars(database)

            assertTrue("没有伴生文件时应返回空清单，而不是谎报删除", removed.isEmpty())
            assertTrue(database.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 4. checkpoint 判定 -------------------------------------------------

    @Test
    fun walCheckpointVerdictAcceptsCleanCheckpoint() {
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(busy = 0, logFrames = 12, checkpointedFrames = 12)
        assertTrue("busy=0 且全部帧已合并＝可以出包", verdict.ok)
    }

    @Test
    fun walCheckpointVerdictAcceptsNonWalDatabase() {
        // 实测非 WAL 库返回 0|-1|-1：没有日志要合，不能误判成失败。
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(busy = 0, logFrames = -1, checkpointedFrames = -1)
        assertTrue(verdict.ok)
        assertEquals("not_wal", verdict.detail)
    }

    @Test
    fun walCheckpointVerdictRejectsBusyCheckpoint() {
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(busy = 1, logFrames = 12, checkpointedFrames = 0)
        assertFalse("有读者挡着就是没合完，必须拒绝出包", verdict.ok)
        assertTrue(verdict.detail.contains("busy=1"))
    }

    @Test
    fun walCheckpointVerdictRejectsPartialCheckpoint() {
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(busy = 0, logFrames = 12, checkpointedFrames = 5)
        assertFalse("只合了一部分，主库仍不完整", verdict.ok)
        assertTrue(verdict.detail.contains("5/12"))
    }

    @Test
    fun walCheckpointVerdictAcceptsNoFramesClean() {
        // 实测 WAL 库无待合日志：0|0|0，本来就没有日志要合。
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(busy = 0, logFrames = 0, checkpointedFrames = 0)
        assertTrue("无待合日志＝可以出包", verdict.ok)
    }

    // ---- 5. busy 只表示文件收缩受阻，不等于数据没合 --------------------------
    //
    // 实测 sqlite3 3.53.1：读事务挡着时 `PRAGMA wal_checkpoint(TRUNCATE)` 返回 `1|202|202`，
    // 此时用 immutable=1 直读主库拿到 200 行全在 —— checkpointed == log 就说明数据已完整
    // 落进主库，busy 只反映「TRUNCATE 阶段没法把 -wal 收缩到 0 字节」。旧判定把 busy 放在
    // checkpointed 之前，等于把「文件没缩」当成「数据没合」，自动备份遇到读事务就整条失败。

    @Test
    fun walCheckpointVerdictAcceptsBusyButFullyCheckpointed() {
        // 审查员实测的读事务场景：1|202|202，主库数据完整，必须放行。
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(
            busy = 1,
            logFrames = 202,
            checkpointedFrames = 202,
        )
        assertTrue("checkpointed == log 即数据完整，busy 只是没收缩，不能误拒", verdict.ok)
        assertTrue("detail 要保留 busy 事实便于排查: ${verdict.detail}", verdict.detail.contains("busy=1"))
        assertTrue("detail 要带帧数: ${verdict.detail}", verdict.detail.contains("202"))
    }

    @Test
    fun walCheckpointVerdictAcceptsBusyWithSmallFrames() {
        // 实测的写锁场景：1|3|3，同样数据已合完，只是文件没缩。
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(
            busy = 1,
            logFrames = 3,
            checkpointedFrames = 3,
        )
        assertTrue("3 帧全部合并＝可以出包", verdict.ok)
        assertTrue(verdict.detail.contains("busy=1"))
    }

    @Test
    fun walCheckpointVerdictAcceptsBusyWithoutFrames() {
        // busy 但没有待合日志：本来就没数据要合，主库完整。
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(
            busy = 1,
            logFrames = 0,
            checkpointedFrames = 0,
        )
        assertTrue("无待合日志时 busy 不构成拒绝理由", verdict.ok)
    }

    @Test
    fun walCheckpointVerdictRejectsPartialCheckpointWithBusy() {
        // 关键反例：busy 同时 checkpointed < log，才是真的没合完，必须拒绝。
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(
            busy = 1,
            logFrames = 202,
            checkpointedFrames = 100,
        )
        assertFalse("102 帧还没合进主库，出包会丢数据", verdict.ok)
        assertTrue("detail 要给出进度: ${verdict.detail}", verdict.detail.contains("100/202"))
        assertTrue(verdict.detail.contains("busy=1"))
    }

    @Test
    fun walCheckpointVerdictRejectsPartialCheckpointWithLargeFrames() {
        // 同一条规则在 202 帧规模、无 busy 时也必须拒绝（回归保护）。
        val verdict = EtaFullBackupArchive.walCheckpointVerdict(
            busy = 0,
            logFrames = 202,
            checkpointedFrames = 100,
        )
        assertFalse("checkpointed < log 一律拒绝", verdict.ok)
        assertTrue(verdict.detail.contains("100/202"))
    }
}
