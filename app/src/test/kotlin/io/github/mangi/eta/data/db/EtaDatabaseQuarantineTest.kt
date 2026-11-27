package io.github.mangi.eta.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「无迁移路径的库不能被静默销毁」的回归测试。
 *
 * 早先 EtaDatabase 挂了 `fallbackToDestructiveMigration(dropAllTables = true)`：
 * 老库（v1～v5）与降级安装会被 Room 直接 DROP 全部表，release 包不抛异常、UI 无提示。
 * 现在改为先隔离成 `eta.db.broken-<ts>`，再建新库。
 *
 * 纯 JVM 测试：只操作临时目录里的文件，不依赖 Robolectric（aarch64 上会被跳过）。
 */
class EtaDatabaseQuarantineTest {

    private lateinit var dir: File
    private lateinit var db: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("eta-db-quarantine").toFile()
        db = File(dir, "eta.db")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun legacyVersionBelowMigrationChainIsQuarantined() {
        writeSqliteHeader(db, version = 3)

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db)

        assertNotNull("v3 老库应被隔离", quarantined)
        assertTrue(quarantined!!.isFile)
        assertTrue(quarantined.name.startsWith("eta.db.broken-"))
        assertTrue("原路径应已让开，Room 才能建新库", !db.exists())
    }

    @Test
    fun futureVersionFromDowngradeIsQuarantined() {
        writeSqliteHeader(db, version = ETA_DB_SCHEMA_VERSION + 5)

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db)

        assertNotNull("降级安装的更高版本库应被隔离，而不是被 Room 删表重写", quarantined)
        assertTrue(!db.exists())
    }

    @Test
    fun currentSchemaIsLeftAlone() {
        writeSqliteHeader(db, version = ETA_DB_SCHEMA_VERSION)

        assertNull(EtaDatabase.quarantineIfUnmigratable(db))
        assertTrue("正常库不应被动过", db.isFile)
    }

    @Test
    fun migratableOldVersionIsLeftAlone() {
        writeSqliteHeader(db, version = EtaDatabase.MIN_MIGRATABLE_VERSION)

        assertNull(EtaDatabase.quarantineIfUnmigratable(db))
        assertTrue(db.isFile)
    }

    @Test
    fun walSidecarsFollowTheQuarantine() {
        writeSqliteHeader(db, version = 3)
        val wal = File(dir, "eta.db-wal").apply { writeText("wal") }
        val shm = File(dir, "eta.db-shm").apply { writeText("shm") }

        // 本用例只关心 sidecar 是否跟随改名；WAL 里的真实版本显式给出 v3（不可迁移），
        // 避免被「读不出真实版本就不隔离」的保守分支挡住。
        val quarantined = EtaDatabase.quarantineIfUnmigratable(db) { 3 }

        assertNotNull(quarantined)
        // -wal/-shm 必须跟着走：留在原路径上会被新库误认为自己的日志，直接损坏新库。
        assertTrue(!wal.exists())
        assertTrue(!shm.exists())
        assertTrue(File(quarantined!!.path + "-wal").isFile)
        assertTrue(File(quarantined.path + "-shm").isFile)
    }

    @Test
    fun missingDatabaseIsLeftAlone() {
        assertNull(EtaDatabase.quarantineIfUnmigratable(db))
    }

    @Test
    fun emptyFileIsNotTreatedAsLegacyDatabase() {
        db.writeText("")
        assertNull("空文件不该被当成损坏库", EtaDatabase.quarantineIfUnmigratable(db))
        assertTrue(db.isFile)
    }

    /**
     * P1-1：magic 不匹配的损坏库必须被隔离，且异常不能冒到调用方。
     *
     * 修之前：readUserVersion 返回 -1 → quarantineIfUnmigratable 返回 null → Room 构建抛
     * IllegalStateException，启动路径直接崩，用户没有恢复入口。修之后：与「版本不可迁移」
     * 同等处理，搬成 broken 文件后建空库，调用方拿到的是 broken 文件而不是异常。
     */
    @Test
    fun corruptedMagicIsQuarantinedInsteadOfCrashingRoom() {
        db.writeBytes(ByteArray(200) { 0x41 })

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db)

        assertNotNull("magic 不匹配的损坏库应被隔离，而不是留给 Room 抛异常", quarantined)
        assertTrue(quarantined!!.isFile)
        assertTrue(quarantined.name.startsWith("eta.db.broken-"))
        assertTrue("原路径应已让开，Room 才能建新库", !db.exists())
        assertEquals(200L, quarantined.length())
    }

    /** 头部被截断（长度 < 100 的 SQLite 文件）：同样隔离，不是静默放行。 */
    @Test
    fun truncatedSqliteFileIsQuarantined() {
        RandomAccessFile(db, "rw").use { raf ->
            raf.setLength(0)
            raf.write("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
        }
        assertEquals(16L, db.length())

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db)

        assertNotNull("截断的库文件无法判定版本，应被隔离", quarantined)
        assertTrue(!db.exists())
    }

    /**
     * 隔离动作本身失败时不能静默返回 null：必须写日志说明原因，再抛可观测的异常。
     *
     * 这里用「相对路径 = 没有父目录」构造失败（改名无处可去）。不用 chmod 构造只读目录：
     * 测试 JVM 以 root 运行，权限位会被绕过，那样的用例只会静默通过，等于没测。
     */
    @Test
    fun quarantineFailureIsObservable() {
        val logs = mutableListOf<String>()
        val relative = File("eta-db-quarantine-failure-probe.db")
        EtaDatabase.quarantineLogSink = { logs += it }
        try {
            relative.writeBytes(ByteArray(200) { 0x41 })
            assertNull("前提：相对路径没有父目录", relative.parentFile)

            var thrown: EtaDatabaseQuarantineException? = null
            try {
                EtaDatabase.quarantineIfUnmigratable(relative)
            } catch (e: EtaDatabaseQuarantineException) {
                thrown = e
            }

            assertNotNull("隔离失败必须抛出可观测异常，而不是静默返回 null", thrown)
            assertTrue(
                "异常信息要写明原因",
                thrown!!.message!!.contains("隔离失败"),
            )
            assertEquals("失败时必须留下一条日志", 1, logs.size)
            assertTrue("日志要说明失败原因", logs.single().contains("隔离失败"))
            assertTrue("失败时不能把库搬走", relative.isFile)
        } finally {
            EtaDatabase.quarantineLogSink = null
            relative.delete()
        }
    }

    /**
     * 幂等：同进程连续两次调用只产生一个 broken 文件，第二次拿回同一个对象。
     *
     * 隔离成功后原路径已经不存在，如果幂等只靠「文件还在不在」，第二次调用就会返回 null，
     * 调用方会误判「库没事」；如果靠时间戳，同一秒内还会再复制一份。
     */
    @Test
    fun quarantineIsIdempotentWithinProcess() {
        db.writeBytes(ByteArray(200) { 0x41 })

        val first = EtaDatabase.quarantineIfUnmigratable(db)
        val second = EtaDatabase.quarantineIfUnmigratable(db)

        assertNotNull(first)
        assertNotNull("第二次调用必须拿回同一个 broken 文件，而不是 null", second)
        assertEquals("同进程重复调用不能产生第二个 broken 文件", first, second)
        val brokenCount = dir.listFiles()!!.count { it.name.contains(".broken-") }
        assertEquals("目录里只应有一个 broken 文件", 1, brokenCount)
    }

    /**
     * P1-2：主文件头 user_version 陈旧（5）而 WAL 非空时，不能按头判「不可迁移」。
     *
     * 探针成功读到 WAL 里的真实版本 6（可迁移）时，库必须原样留下。
     * 修之前这条用例靠默认探针（纯 JVM 下 android.jar 是 stub，只读打开必然失败 → -1）
     * 走「读不出就不隔离」的分支通过；必修 3 之后该分支已改成「保守隔离」，
     * 所以必须显式给出探针结果，否则测的就不是「WAL 版本被优先采用」这件事。
     */
    @Test
    fun staleHeaderWithWalIsNotQuarantined() {
        writeSqliteHeader(db, version = 5)
        File(dir, "eta.db-wal").writeBytes(ByteArray(64) { 0x5A })

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db) { 6 }

        assertNull("主文件头陈旧但 WAL 里已是 v6 时不能隔离完好库", quarantined)
        assertTrue(db.isFile)
        assertTrue("不该产生任何 broken 文件", dir.listFiles()!!.none { it.name.contains(".broken-") })
    }

    /** WAL 里真实版本是 6（可迁移）时，用 WAL 的版本判定，库留在原地。 */
    @Test
    fun walVersionIsPreferredOverStaleHeader() {
        writeSqliteHeader(db, version = 5)
        File(dir, "eta.db-wal").writeBytes(ByteArray(64) { 0x5A })

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db) { 6 }

        assertNull("WAL 里已是 v6，不该被隔离", quarantined)
        assertTrue(db.isFile)
        assertTrue("不该产生任何 broken 文件", dir.listFiles()!!.none { it.name.contains(".broken-") })
    }

    /** 反向保证：WAL 里的版本确实不可迁移时仍然隔离，不是「有 WAL 就一律放行」。 */
    @Test
    fun walVersionStillQuarantinesWhenItselfUnmigratable() {
        writeSqliteHeader(db, version = 5)
        File(dir, "eta.db-wal").writeBytes(ByteArray(64) { 0x5A })

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db) { 3 }

        assertNotNull("WAL 里真实版本也是 v3，仍应隔离", quarantined)
        assertTrue(!db.exists())
    }

    /** 没有 WAL 时行为不变：头里 v5 低于迁移链起点，照旧隔离。 */
    @Test
    fun staleHeaderWithoutWalIsQuarantined() {
        writeSqliteHeader(db, version = 5)
        assertTrue(!File(dir, "eta.db-wal").exists())

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db)

        assertNotNull("无 WAL 的 v5 老库应被隔离", quarantined)
        assertTrue(!db.exists())
    }

    /**
     * 探针读不出真实版本（返回 -1）+ `-wal` 非空 → 不隔离原库，先留一份副本再放行。
     *
     * 上一轮的实现是「保守隔离」：`probed <= 0` 就直接把库改名成 `.broken-`。但「读不出」不等于
     * 「不可迁移」——`-shm` 拿不到写权限、库被别的进程独占、目录只读，都会让常规只读打开必返
     * -1，而库可能完全完好。那时用户下次启动看到的是空库，原库已经被搬走。
     *
     * 现在：连快照都读不出时只复制一份 `.probefail-<ts>` 保底，原库留在原位让 Room 自己试；
     * Room 真打不开由首次打开兜底隔离（见 roomOpenFailureTriggersQuarantineAndReopen）。
     */
    @Test
    fun walProbeFailureKeepsOriginalAndLeavesCopy() {
        writeSqliteHeader(db, version = 5)
        File(dir, "eta.db-wal").writeBytes(ByteArray(64) { 0x5A })
        val originalBytes = db.readBytes()

        val quarantined = EtaDatabase.quarantineIfUnmigratable(db) { -1 }

        assertNull("探针失败不能直接搬走可能完好的库", quarantined)
        assertTrue("原库必须留在原位，让 Room 自己试", db.isFile)
        assertEquals("原库内容不能被改动", 100L, db.length())
        assertTrue("不该产生 broken 文件", dir.listFiles()!!.none { it.name.contains(".broken-") })

        val copy = dir.listFiles()!!.firstOrNull { it.name.contains(".probefail-") }
        assertNotNull("必须留下 .probefail- 副本作为保底", copy)
        assertEquals("副本字节数要与原库一致", 100L, copy!!.length())
        assertTrue("副本内容要与原库一致", originalBytes.contentEquals(copy.readBytes()))
    }

    /** 探针失败时日志 sink 必须收到说明原因的记录（纯 JVM 下不能碰 android.util.Log）。 */
    @Test
    fun walProbeFailureEmitsVisibleLog() {
        writeSqliteHeader(db, version = 5)
        File(dir, "eta.db-wal").writeBytes(ByteArray(64) { 0x5A })
        val logs = mutableListOf<String>()
        EtaDatabase.quarantineLogSink = { logs += it }
        try {
            EtaDatabase.quarantineIfUnmigratable(db) { -1 }

            assertTrue("探针失败必须留日志，否则用户不知道发生了什么", logs.isNotEmpty())
            val probeLog = logs.firstOrNull { it.contains("WAL 版本探测失败") }
            assertNotNull("日志要写明是 WAL 探测失败，而不是笼统的隔离成功", probeLog)
            assertTrue("日志要带上文件头版本，便于排查", probeLog!!.contains("header=5"))
            assertTrue("日志要说明已经留了副本", probeLog.contains("留副本"))
            assertTrue(
                "日志要写明仍尝试打开原库，而不是说已经隔离",
                probeLog.contains("仍尝试打开原库"),
            )
            assertTrue("副本文件名要真实写进日志", probeLog.contains(".probefail-"))
            assertTrue(
                "不该再出现隔离成功的收尾日志",
                logs.none { it.contains("原库已隔离为") },
            )
        } finally {
            EtaDatabase.quarantineLogSink = null
        }
    }

    /**
     * 探针失败但「快照只读」能读出真实版本（可迁移）时：不隔离、也不留副本。
     *
     * 这是 C 的另一半：探针读不出不等于库坏。这里构造「主文件头 v5 + WAL 非空 + 常规只读打开
     * 失败（返回 -1）」的场景，并给出快照探针能读到的真实版本 36，断言库被放行且一个副本都不留
     * ——既然已经读出来了，就没有留副本的必要。
     *
     * 注意实现选择：快照探针**不用** `immutable=1`。实测（sqlite3 3.46.1）`immutable=1` 会忽略
     * `-wal`：同一个头 v5、WAL 里已提交 v36 的库，`immutable=1` 读到 5 且数据 0 行，`mode=ro`
     * 读到 36 且数据 1 行。用 immutable 判可迁移性会把完好的库判成不可迁移，正好是要防的误伤。
     * 所以实现改成「复制主库 + -wal/-shm 到临时目录后只读打开」，本用例按该语义断言。
     */
    @Test
    fun snapshotReadRescuesHealthyDatabaseWhenProbeFails() {
        writeSqliteHeader(db, version = 5)
        File(dir, "eta.db-wal").writeBytes(ByteArray(64) { 0x5A })
        val logs = mutableListOf<String>()
        EtaDatabase.quarantineLogSink = { logs += it }
        try {
            val quarantined = EtaDatabase.quarantineIfUnmigratable(
                db,
                walVersionProbe = { -1 },
                walRescueProbe = { ETA_DB_SCHEMA_VERSION },
            )

            assertNull("快照读出的真实版本可迁移时不能隔离", quarantined)
            assertTrue("原库必须留在原位", db.isFile)
            assertTrue("不该产生 broken 文件", dir.listFiles()!!.none { it.name.contains(".broken-") })
            assertTrue("已经读出来了，不该留副本", dir.listFiles()!!.none { it.name.contains(".probefail-") })
            val rescueLog = logs.firstOrNull { it.contains("快照只读读出真实版本") }
            assertNotNull("救援成功也要留日志，便于知道走了快照分支", rescueLog)
            assertTrue("日志要写明读到的真实版本", rescueLog!!.contains("$ETA_DB_SCHEMA_VERSION"))
        } finally {
            EtaDatabase.quarantineLogSink = null
        }
    }

    /** 快照读出的真实版本确实不可迁移时：仍然隔离，不是「有快照就一律放行」。 */
    @Test
    fun snapshotReadStillQuarantinesWhenRealVersionUnmigratable() {
        writeSqliteHeader(db, version = 5)
        File(dir, "eta.db-wal").writeBytes(ByteArray(64) { 0x5A })

        val quarantined = EtaDatabase.quarantineIfUnmigratable(
            db,
            walVersionProbe = { -1 },
            walRescueProbe = { 3 },
        )

        assertNotNull("快照读出的真实版本是 v3，仍应隔离", quarantined)
        assertTrue(quarantined!!.name.startsWith("eta.db.broken-"))
        assertTrue(!db.exists())
    }

    /**
     * Room 首次打开抛 IllegalStateException（无迁移路径）时：隔离原库 + 重建空库，且不向上抛。
     *
     * 为什么兜底放在 openHelperFactory 上：`databaseBuilder().build()` 不打开库，连接在首次
     * query 时经 `SupportSQLiteDriver.open()` → `getWritableDatabase()` 触发，
     * IllegalStateException 正是从那里抛出来的。这里直接驱动那个兜底函数本身。
     */
    @Test
    fun roomOpenFailureTriggersQuarantineAndReopen() {
        db.writeBytes(ByteArray(200) { 0x41 })
        val logs = mutableListOf<String>()
        EtaDatabase.quarantineLogSink = { logs += it }
        try {
            var attempts = 0
            val opened = EtaDatabase.openOrQuarantine(db) {
                attempts++
                if (attempts == 1) {
                    throw IllegalStateException(
                        "A migration from 5 to 36 was required but not found.",
                    )
                }
                "reopened-empty-db"
            }

            assertEquals("隔离后必须重开一次", 2, attempts)
            assertEquals("重开拿到的是新库", "reopened-empty-db", opened)
            assertTrue("原库必须被隔离让开路径", !db.exists())
            val broken = dir.listFiles()!!.firstOrNull { it.name.contains(".broken-") }
            assertNotNull("必须产生 broken 文件保住数据", broken)
            assertEquals("原库内容完整保留", 200L, broken!!.length())
            assertTrue(
                "日志要说明隔离后重建空库",
                logs.any { it.contains("已隔离为") && it.contains("重建空库") },
            )
        } finally {
            EtaDatabase.quarantineLogSink = null
        }
    }

    /** 兜底只接 IllegalStateException：磁盘满这类 SQLiteException 必须原样上抛，不能顺手搬库。 */
    @Test
    fun roomOpenFailureFallbackRethrowsNonMigrationErrors() {
        db.writeBytes(ByteArray(200) { 0x41 })

        var thrown: RuntimeException? = null
        try {
            EtaDatabase.openOrQuarantine(db) {
                throw RuntimeException("disk full")
            }
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertNotNull("非迁移类错误必须上抛", thrown)
        assertEquals("disk full", thrown?.message)
        assertTrue("不该因为磁盘满就把库搬走", db.isFile)
        assertTrue("不该产生 broken 文件", dir.listFiles()!!.none { it.name.contains(".broken-") })
    }

    /** 同一进程重复触发兜底时不能搬第二次：第二次失败直接上抛，不再产生新 broken 文件。 */
    @Test
    fun roomOpenFailureQuarantinesOnlyOnce() {
        db.writeBytes(ByteArray(200) { 0x41 })
        EtaDatabase.quarantineLogSink = { }
        try {
            // 第一次：失败 → 隔离 → 重开成功。
            assertEquals(
                "ok",
                EtaDatabase.openOrQuarantine(db) {
                    if (!db.exists()) "ok" else throw IllegalStateException("migration missing")
                },
            )
            assertTrue("第一次应已隔离原库", !db.exists())

            // 第二次：库已不在原位，且已记入 quarantined，异常原样上抛。
            var thrown: IllegalStateException? = null
            try {
                EtaDatabase.openOrQuarantine(db) { throw IllegalStateException("migration missing") }
            } catch (e: IllegalStateException) {
                thrown = e
            }
            assertNotNull("重复失败应原样上抛，不静默", thrown)
            assertEquals(
                "目录里只应有一个 broken 文件",
                1,
                dir.listFiles()!!.count { it.name.contains(".broken-") },
            )
        } finally {
            EtaDatabase.quarantineLogSink = null
        }
    }

    /**
     * 兜底的**接线**验证：包装后的 helper 第一次取 writableDatabase 抛 IllegalStateException 时，
     * 必须隔离原库并重开一次，而不是把异常漏给调用方。
     *
     * 前面的用例只驱动了 openOrQuarantine 本身；这一条验证它真的被挂到了 Room 打开数据库的
     * 那条路径上（`SupportSQLiteDriver.open()` → `getWritableDatabase()`）。用假 helper 绕开
     * Configuration 需要 Context 的问题——纯 JVM 里 android.jar 是 stub，构造不出来。
     */
    @Test
    fun wrappedHelperQuarantinesAndReopensOnMigrationFailure() {
        db.writeBytes(ByteArray(200) { 0x41 })
        val logs = mutableListOf<String>()
        EtaDatabase.quarantineLogSink = { logs += it }
        try {
            var calls = 0
            val fakeDb = fakeDatabase()
            val fake = object : SupportSQLiteOpenHelper {
                override val databaseName: String? = "eta.db"
                override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit
                override val writableDatabase: SupportSQLiteDatabase
                    get() {
                        calls++
                        if (calls == 1) {
                            throw IllegalStateException(
                                "A migration from 5 to 36 was required but not found.",
                            )
                        }
                        return fakeDb
                    }
                override val readableDatabase: SupportSQLiteDatabase get() = writableDatabase
                override fun close() = Unit
            }

            val wrapped = EtaDatabase.wrapOpenHelper(db, fake)
            val opened = wrapped.writableDatabase

            assertEquals("包装层必须重开一次", 2, calls)
            assertSame("返回的应是重开后的库", fakeDb, opened)
            assertTrue("原库应已被隔离", !db.exists())
            assertNotNull(
                "必须产生 broken 文件保住数据",
                dir.listFiles()!!.firstOrNull { it.name.contains(".broken-") },
            )
            assertTrue(
                "日志要说明隔离后重建空库",
                logs.any { it.contains("已隔离为") && it.contains("重建空库") },
            )
        } finally {
            EtaDatabase.quarantineLogSink = null
        }
    }

    /** 非迁移类异常穿过包装层时必须原样上抛，且不搬库（磁盘满不是「无迁移路径」）。 */
    @Test
    fun wrappedHelperLetsNonMigrationErrorsThrough() {
        db.writeBytes(ByteArray(200) { 0x41 })
        val fake = object : SupportSQLiteOpenHelper {
            override val databaseName: String? = "eta.db"
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit
            override val writableDatabase: SupportSQLiteDatabase
                get() = throw RuntimeException("disk full")
            override val readableDatabase: SupportSQLiteDatabase get() = writableDatabase
            override fun close() = Unit
        }

        var thrown: RuntimeException? = null
        try {
            EtaDatabase.wrapOpenHelper(db, fake).writableDatabase
        } catch (e: RuntimeException) {
            thrown = e
        }

        assertNotNull("非迁移类错误必须上抛", thrown)
        assertEquals("disk full", thrown?.message)
        assertTrue("不该因为磁盘满就把库搬走", db.isFile)
        assertTrue("不该产生 broken 文件", dir.listFiles()!!.none { it.name.contains(".broken-") })
    }

    /**
     * 假库对象：只为让包装层有东西可返回，不参与任何真实 SQLite 调用。
     *
     * 用动态代理而不是手写实现：`SupportSQLiteDatabase` 成员很多，逐个实现容易和接口版本对不上，
     * 而这个用例只关心「包装层是否拦住了第一次打开」。
     */
    private fun fakeDatabase(): SupportSQLiteDatabase =
        java.lang.reflect.Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isOpen" -> true
                "isReadOnly" -> false
                "getPath" -> "eta.db"
                "getVersion" -> ETA_DB_SCHEMA_VERSION
                "hashCode" -> System.identityHashCode(method)
                "equals" -> false
                "toString" -> "FakeDatabase"
                else -> null
            }
        } as SupportSQLiteDatabase

    /**
     * 必修 3 兜底：隔离动作自身抛 EtaDatabaseQuarantineException 时，启动路径不能被打崩。
     *
     * 用相对路径构造「没有父目录」→ 改名无处可去 → 隔离失败。`runQuarantineForStartup`
     * 必须吞掉这个异常并留日志，而不是让它冒到 EtaApp.onCreate / UI 路径上。
     */
    @Test
    fun quarantineFailureDoesNotEscapeStartup() {
        val logs = mutableListOf<String>()
        val relative = File("eta-db-startup-fallback-probe.db")
        EtaDatabase.quarantineLogSink = { logs += it }
        try {
            relative.writeBytes(ByteArray(200) { 0x41 })
            assertNull("前提：相对路径没有父目录，隔离必然失败", relative.parentFile)

            var thrown: EtaDatabaseQuarantineException? = null
            try {
                EtaDatabase.quarantineIfUnmigratable(relative)
            } catch (e: EtaDatabaseQuarantineException) {
                thrown = e
            }
            assertNotNull("前提：隔离动作本身会抛 EtaDatabaseQuarantineException", thrown)
            logs.clear()

            EtaDatabase.runQuarantineForStartup { EtaDatabase.quarantineIfUnmigratable(relative) }

            assertTrue("兜底必须留下可见日志，不能静默吞掉", logs.isNotEmpty())
            assertTrue("日志要说明隔离未完成", logs.any { it.contains("启动前隔离未完成") })
            assertTrue("失败时不能把库搬走", relative.isFile)
        } finally {
            EtaDatabase.quarantineLogSink = null
            relative.delete()
        }
    }

    /** 兜底只吞隔离异常：其它异常照原样上抛，不能用一个宽口径 catch 藏真实缺陷。 */
    @Test
    fun startupFallbackRethrowsUnrelatedExceptions() {
        var thrown: IllegalStateException? = null
        try {
            EtaDatabase.runQuarantineForStartup { throw IllegalStateException("boom-unrelated") }
        } catch (e: IllegalStateException) {
            thrown = e
        }

        assertNotNull("非隔离异常必须原样上抛", thrown)
        assertEquals("boom-unrelated", thrown!!.message)
    }

    private fun writeSqliteHeader(file: File, version: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteArray(100)
            "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
            header[16] = 0x10
            header[17] = 0x00
            header[18] = 1
            header[19] = 1
            header[21] = 64
            header[22] = 32
            header[23] = 32
            byteArrayOf(
                (version ushr 24 and 0xFF).toByte(),
                (version ushr 16 and 0xFF).toByte(),
                (version ushr 8 and 0xFF).toByte(),
                (version and 0xFF).toByte(),
            ).copyInto(header, 60)
            raf.write(header)
        }
    }
}
