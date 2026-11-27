package io.github.mangi.eta.data.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/** 结构版本的唯一来源：@Database 与自检页共用，文案里不再写字面量。 */
internal const val ETA_DB_SCHEMA_VERSION = 36

/**
 * 隔离动作本身失败时的可观测出口。
 *
 * 触发条件：磁盘满、目录只读、权限不足——文件搬不走，但也不能让 App 带着一个
 * 打不开的库静默崩溃。日志里已写明原因，再把这条异常交给调用方，调用方拿到的是
 * 「隔离没做成」这个明确事实，而不是 Room 抛出的、与真实原因无关的 IllegalStateException。
 */
internal class EtaDatabaseQuarantineException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@Database(
    entities = [
        AgentTextChunkEntity::class,
        ConversationEntity::class,
        ConversationCategoryEntity::class,
        ConversationContextCheckpointEntity::class,
        ConversationMessageEntity::class,
        ConversationStateEntity::class,
        ProviderEntity::class,
        ProviderModelEntity::class,
        RuntimeResultEntity::class,
        RuntimeArchiveRunEntity::class,
        RuntimeArchiveEventEntity::class,
        RuntimeInFlightRunEntity::class,
        RuntimeInFlightEventEntity::class,
        SkillRegistryEntity::class,
        McpServerEntity::class,
        CharacterEntity::class,
        UserPersonaEntity::class,
        AgentTaskEntity::class,
        AgentTaskRunEntity::class,
        TokenUsageRecordEntity::class,
    ],
    version = ETA_DB_SCHEMA_VERSION,
    exportSchema = false,
)
internal abstract class EtaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun providerDao(): ProviderDao
    abstract fun runtimeRunDao(): RuntimeRunDao
    abstract fun skillDao(): SkillDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun characterDao(): CharacterDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun tokenUsageDao(): TokenUsageDao

    companion object {
        @Volatile
        private var instance: EtaDatabase? = null

        fun get(context: Context): EtaDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    // 隔离必须先于任何一次打开：一旦 Room 打开并判定无迁移路径，
                    // 用户数据就已经被 DROP 掉了，再改名只剩空壳。
                    // 隔离动作自身失败（磁盘满/目录只读/权限不足）时只记日志不阻断：
                    // 隔离是「保数据」的加分项，不能反过来变成启动崩溃的原因。
                    runQuarantineForStartup {
                        quarantineIfUnmigratable(appContext)
                    }
                    Room.databaseBuilder(
                        appContext,
                        EtaDatabase::class.java,
                        "eta.db",
                    )
                    // 上面的启动前判定只按「文件头 + 探针」猜可迁移性，猜错有两种方向：
                    // 猜成「不可迁移」会误搬完好库（见 quarantineIfUnmigratable 的探针兜底），
                    // 猜成「可迁移」会让 Room 在首次打开时抛 IllegalStateException 打崩启动。
                    // 后者由这个工厂在真正打开的那一刻兜住：无迁移路径 → 隔离原库 → 重建空库。
                    .openHelperFactory(
                        quarantineOnOpenFailureFactory(appContext.getDatabasePath("eta.db")),
                    )
                    .addMigrations(
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_31,
                        MIGRATION_31_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35,
                        MIGRATION_35_36,
                    )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) { createTextChunkCleanup(db) }
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { createTextChunkCleanup(db) }
                    })
                    .build()
                    .also { instance = it }
                }
            }

        /**
         * 启动路径上的隔离兜底：隔离失败只留下可见日志，不把异常抛给启动方。
         *
         * [quarantineIfUnmigratable] 在文件搬不走时会抛 [EtaDatabaseQuarantineException]，
         * 那是「隔离没做成」的明确事实，不是启动必须失败的理由——`EtaApp.onCreate` 的
         * `guard("agentTaskRecover")` 只记日志，之后的 ProviderRepository.init、AgentAppState
         * 等 UI 路径照样会再调 [get]，异常从 [get] 里冒出去只会把启动打崩。
         *
         * 只吞隔离异常本身：其它异常（OOM、SecurityException 等）照原样上抛，
         * 不用一个宽口径 catch 把真实缺陷一起藏起来。
         */
        @VisibleForTesting
        internal fun runQuarantineForStartup(quarantine: () -> Unit) {
            try {
                quarantine()
            } catch (error: EtaDatabaseQuarantineException) {
                // 文案必须与实现一致：这里既没有只读打开，也没有新建兜底——原库原样交给
                // Room，无迁移路径时照样抛 IllegalStateException。真正的「隔离 + 重建空库」
                // 兜底在首次打开处（见 [withRoomOpenFallback]），不在这里。写成「以只读/
                // 新建方式打开」会把排查方向带到一条不存在的兜底上。
                emitQuarantineLog(
                    "启动前隔离未完成，数据库仍按原样打开（可能因无迁移路径而失败）：" +
                        (error.message ?: error.javaClass.simpleName),
                    error = true,
                )
            }
        }

        /**
         * 打开数据库之前，先处理「没有迁移路径」的两种情况。
         *
         * 早先这里挂了 `fallbackToDestructiveMigration(dropAllTables = true)`：
         * 老库（v1～v5，迁移链从 6→7 才开始）或无迁移的降级安装，会被 Room 直接 DROP 掉
         * 全部表——会话、消息、model_providers（含明文 api_key）、MCP 配置、token 统计全没，
         * 而且 release 包不抛异常、UI 无任何提示（2026-11-26 审核）。
         *
         * 现在改成「隔离而非销毁」：把库连同 -wal/-shm/-journal 改名成
         * `eta.db.broken-<ts>` 留在原地，再让 Room 建新库。用户可以自己把文件捞出来导出，
         * 数据不会在用户不知情的情况下消失。
         *
         * 版本号先从 SQLite 文件头读（偏移 60，大端 4 字节），不必先打开库：
         * 打开就意味着已经错过隔离时机；只有 -wal 非空、头部可能陈旧时才退化成
         * 只读打开读真实版本（见纯文件版本）。
         */
        @VisibleForTesting
        internal fun quarantineIfUnmigratable(context: Context): File? =
            quarantineIfUnmigratable(context.getDatabasePath("eta.db"))

        /**
         * 隔离判定的纯文件版本：不碰 Context，可直接用纯 JVM 单测覆盖。
         *
         * 版本号优先从 SQLite 文件头读（偏移 60，大端 4 字节），不必先打开库：
         * 打开就意味着已经错过隔离时机。但 WAL 模式下文件头可能是陈旧的（见下方
         * hasPendingWal 分支），此时才退化成只读打开读真实 user_version。
         */
        @VisibleForTesting
        internal fun quarantineIfUnmigratable(
            file: File,
            walVersionProbe: (File) -> Int = ::readUserVersionByOpening,
            walRescueProbe: (File) -> Int = ::readUserVersionBySnapshot,
        ): File? {
            val key = canonicalKey(file)
            synchronized(quarantineLock) {
                // 幂等：本进程已隔离过这个路径，直接返回同一个 broken 文件。
                // 既不再复制第二份，也不返回 null 让调用方误判「库没事」。
                quarantined[key]?.let { return it }
                if (!file.exists() || !file.isFile) return null
                // 长度为 0 是首次启动的空占位，Room 会自己初始化；不把「第一次启动」误判成灾难。
                if (file.length() == 0L) return null
                val headerVersion = readUserVersion(file)
                // 读不出版本（magic 不匹配 / 头部截断 / 读失败）不再「交给 Room 自己报」：
                // Room 构建会抛 IllegalStateException 把启动路径打崩，用户没有恢复入口。
                // 这类库与「版本不可迁移」同等处理——隔离，然后建空库。
                val version = when {
                    headerVersion < 0 -> UNKNOWN_SCHEMA_VERSION
                    headerVersion == 0 -> UNKNOWN_SCHEMA_VERSION
                    isMigratableVersion(headerVersion) -> return null
                    hasPendingWal(file) -> {
                        val probed = walVersionProbe(file)
                        if (probed <= 0) {
                            // 探针读不出 ≠ 库坏了：-shm 拿不到写权限、库被别的进程独占、目录只读，
                            // 都会让常规只读打开必返 -1，而库可能完全完好。把「读不出」直接当成
                            // 「不可迁移」再搬走文件，用户下次启动看到的就是空库，原库已经不在原位。
                            // 所以先做一次 WAL 感知的快照读取：能读出真实版本就按真实版本判定。
                            val rescued = walRescueProbe(file)
                            if (rescued > 0 && isMigratableVersion(rescued)) {
                                emitQuarantineLog(
                                    "WAL 版本探测失败（header=$headerVersion），快照只读读出真实版本 " +
                                        "$rescued 可迁移；不隔离，也不留副本",
                                    error = false,
                                )
                                return null
                            }
                            if (rescued > 0) {
                                // 快照读出的真实版本确实不可迁移：这才是该隔离的情况，按它判定。
                                rescued
                            } else {
                                // 连快照都读不出：既证明不了可迁移，也证明不了不可迁移。
                                // 不搬走原库，先留一份完整副本（主库 + -wal/-shm）保底，再放行让
                                // Room 自己试；Room 打不开时由首次打开兜底隔离（副本仍在）。
                                val copy = copyForProbeFailure(file)
                                emitQuarantineLog(
                                    "WAL 版本探测失败（header=$headerVersion）；已留副本 " +
                                        "${copy?.name ?: "<复制失败，无副本>"}，仍尝试打开原库",
                                    error = true,
                                )
                                return null
                            }
                        } else if (isMigratableVersion(probed)) {
                            return null
                        } else {
                            probed
                        }
                    }
                    else -> headerVersion
                }
                val reason = when {
                    version == UNKNOWN_SCHEMA_VERSION ->
                        "无法判定 schema 版本（文件头不是 SQLite、已截断或读取失败，header=$headerVersion）"
                    version < MIN_MIGRATABLE_VERSION ->
                        "schema $version 低于迁移链起点 $MIN_MIGRATABLE_VERSION"
                    else ->
                        "schema $version 高于当前 $ETA_DB_SCHEMA_VERSION（降级安装）"
                }
                return moveToBroken(file, key, reason)
            }
        }

        /**
         * 把主库连同 `-wal`/`-shm`/`-journal` 一起改名成 `eta.db.broken-<ts>`。
         *
         * 抽出来是因为有两条调用路径：[quarantineIfUnmigratable] 的版本判定分支，以及
         * Room 首次打开失败后的兜底。两条都必须做到同一件事——sidecar 跟着走，否则残留的
         * `-wal` 会被新库当成自己的日志，直接损坏新库。
         */
        private fun moveToBroken(file: File, key: String, reason: String): File {
            val parent = file.parentFile
                ?: throw quarantineFailure(file, "$reason；库文件没有父目录，无法改名")
            val broken = uniqueBrokenTarget(file, parent)
            if (!moveFile(file, broken)) {
                throw quarantineFailure(file, "$reason；改名到 ${broken.name} 失败（磁盘满/权限/目录只读）")
            }
            val sidecarFailures = mutableListOf<String>()
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = File(parent, file.name + suffix)
                if (!sidecar.exists()) return@forEach
                if (!moveFile(sidecar, File(broken.path + suffix))) sidecarFailures += suffix
            }
            if (sidecarFailures.isNotEmpty()) {
                // 残留的 -wal 会被新库当成自己的日志，直接损坏新库，所以必须失败可见。
                throw quarantineFailure(
                    file,
                    "$reason；主库已隔离为 ${broken.name}，但日志文件移动失败：${sidecarFailures.joinToString()}",
                )
            }
            quarantined[key] = broken
            emitQuarantineLog(
                "$reason，原库已隔离为 ${broken.name}，将新建空库",
                error = false,
            )
            return broken
        }

        /** 读不出版本时的哨兵值：与「版本号不可迁移」走同一条隔离路径。 */
        @VisibleForTesting
        internal const val UNKNOWN_SCHEMA_VERSION = -1

        /** 同进程隔离锁：幂等判定、缓存写入、文件改名必须在同一临界区里。 */
        private val quarantineLock = Any()

        /** 已隔离：原库 canonical 路径 → broken 文件。 */
        private val quarantined = HashMap<String, File>()

        /**
         * 单测注入的日志出口。
         *
         * 纯 JVM 单测里 android.jar 是 stub，任何 android.util.Log 调用都抛 RuntimeException("Stub!")，
         * 所以日志必须可替换，不能让它把隔离这件事本身变成异常。
         */
        @VisibleForTesting
        internal var quarantineLogSink: ((String) -> Unit)? = null

        private fun canonicalKey(file: File): String =
            runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

        /** 同秒内多次隔离（时钟回拨、跨进程）不能互相覆盖：撞名就加序号。 */
        private fun uniqueBrokenTarget(file: File, parent: File): File =
            uniqueSuffixTarget(file, parent, ".broken-")

        /** [uniqueBrokenTarget] 的通用版：`.broken-` 与 `.probefail-` 共用同一套撞名规则。 */
        private fun uniqueSuffixTarget(file: File, parent: File, marker: String): File {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            var candidate = File(parent, file.name + marker + stamp)
            var index = 2
            while (candidate.exists()) {
                candidate = File(parent, file.name + marker + stamp + "-" + index)
                index++
            }
            return candidate
        }

        /**
         * 先试 rename（同目录、原子、零拷贝），失败再退化成复制+删除。
         * 任一步失败都把半成品目标清掉，不留一个看起来像备份的空壳。
         */
        private fun moveFile(source: File, target: File): Boolean {
            if (runCatching { source.renameTo(target) }.getOrDefault(false)) return true
            if (!runCatching { source.copyTo(target, overwrite = true) }.isSuccess) {
                runCatching { target.delete() }
                return false
            }
            if (!source.delete()) {
                runCatching { target.delete() }
                return false
            }
            return true
        }

        private fun quarantineFailure(file: File, detail: String): EtaDatabaseQuarantineException {
            val message = "eta.db 隔离失败（${file.path}）：$detail"
            emitQuarantineLog(message, error = true)
            return EtaDatabaseQuarantineException(message)
        }

        private fun emitQuarantineLog(message: String, error: Boolean) {
            val sink = quarantineLogSink
            if (sink != null) {
                runCatching { sink(message) }
                return
            }
            runCatching {
                if (error) {
                    io.github.mangi.eta.core.AndroidAgentLogger.error(message)
                } else {
                    io.github.mangi.eta.core.AndroidAgentLogger.warn(message)
                }
            }
        }

        /** 读 SQLite 文件头的 user_version（偏移 60，大端 int）。读不出返回 -1。 */
        private fun readUserVersion(file: File): Int = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 100) return@use -1
                val magic = ByteArray(16)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "SQLite format 3\u0000") return@use -1
                // user_version 是 SQLite 头部大端 4 字节；RandomAccessFile.readInt() 本身即大端，
                // 再反一次会把 36 读成 604241920。
                raf.seek(60L)
                raf.readInt()
            }
        }.getOrDefault(-1)

        /** 版本号落在迁移链覆盖区间内，就不该隔离。 */
        private fun isMigratableVersion(version: Int): Boolean =
            version >= MIN_MIGRATABLE_VERSION && version <= ETA_DB_SCHEMA_VERSION

        /**
         * 常规探针失败后的第二次尝试：把主库 + `-wal`/`-shm` 快照到临时目录再只读打开。
         *
         * 为什么不直接用 `immutable=1` 读原路径：immutable 会**忽略 `-wal`**，读到的是陈旧的
         * 文件头版本。实测（sqlite3 3.46.1）一个头 v5、WAL 里已提交 v36 的库，`immutable=1`
         * 读到 5 且数据 0 行，`mode=ro` 读到 36 且数据 1 行。用 immutable 判可迁移性，正好会
         * 把完好的库判成不可迁移。
         *
         * 快照方案绕开的正是常规探针失败的两个原因：原路径的 `-shm` 拿不到写权限、原库被别的
         * 进程独占。副本在临时目录里，`-wal`/`-shm` 齐全且可写，读到的就是真实版本。
         *
         * 读不出返回 [UNKNOWN_SCHEMA_VERSION]。
         */
        private fun readUserVersionBySnapshot(file: File): Int {
            val parent = file.parentFile ?: return UNKNOWN_SCHEMA_VERSION
            val snapshotDir = runCatching {
                java.nio.file.Files.createTempDirectory("eta-db-walprobe").toFile()
            }.getOrElse { error ->
                emitQuarantineLog(
                    "WAL 快照目录创建失败（${error.javaClass.simpleName}），跳过快照探测",
                    error = true,
                )
                return UNKNOWN_SCHEMA_VERSION
            }
            try {
                val snapshot = File(snapshotDir, file.name)
                // 主库与 sidecar 都要拷：只拷主库会读到陈旧头，正是 immutable 的错。
                runCatching { file.copyTo(snapshot, overwrite = true) }
                    .getOrElse { error ->
                        emitQuarantineLog(
                            "WAL 快照复制失败（${error.javaClass.simpleName}），跳过快照探测",
                            error = true,
                        )
                        return UNKNOWN_SCHEMA_VERSION
                    }
                listOf("-wal", "-shm").forEach { suffix ->
                    val sidecar = File(parent, file.name + suffix)
                    if (sidecar.isFile) {
                        runCatching { sidecar.copyTo(File(snapshotDir, snapshot.name + suffix), overwrite = true) }
                    }
                }
                return readUserVersionByOpening(snapshot)
            } finally {
                runCatching { snapshotDir.deleteRecursively() }
            }
        }

        /**
         * 探针彻底失败时的保底：留一份完整副本，名字带 `.probefail-<ts>`。
         *
         * 只复制不移动：原库留在原位，Room 照常尝试打开。副本是给用户捞数据用的保底——
         * 真打不开时，首次打开兜底会把原库隔离成 `.broken-`，副本则保证「探针读不出的那一刻
         * 到底长什么样」也被留存下来。
         *
         * 返回真实生成的副本文件；复制失败返回 null（调用方把 null 写进日志，不假装成功）。
         */
        private fun copyForProbeFailure(file: File): File? {
            val parent = file.parentFile ?: return null
            val target = uniqueSuffixTarget(file, parent, ".probefail-")
            val copied = runCatching { file.copyTo(target, overwrite = true) }.isSuccess
            if (!copied) {
                runCatching { target.delete() }
                return null
            }
            listOf("-wal", "-shm").forEach { suffix ->
                val sidecar = File(parent, file.name + suffix)
                if (sidecar.isFile) {
                    runCatching { sidecar.copyTo(File(parent, target.name + suffix), overwrite = true) }
                }
            }
            return target
        }

        /**
         * Room 首次打开失败的兜底：无迁移路径时隔离原库并重建空库。
         *
         * 位置选择的依据（对 Room 2.8.5 反编译确认）：`databaseBuilder().build()` 本身不打开
         * 数据库，连接在首次 query 时由 `SupportSQLiteDriver.open()` →
         * `SupportSQLiteOpenHelper.getWritableDatabase()` 触发，而
         * `getWritableDatabase()` 会抛 `IllegalStateException`
         * （"A migration from N was required but not found..."）。所以在
         * `openHelperFactory` 上包一层，正好罩住「真正打开」的那一次调用。
         *
         * 隔离只在第一次失败时做一次：重建的空库再次失败（磁盘满、目录只读）时不再搬文件，
         * 直接把原异常抛出去——那时再搬也救不了启动，藏起来只会掩盖真实原因。
         */
        @VisibleForTesting
        internal fun quarantineOnOpenFailureFactory(
            dbFile: File,
            delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
        ): SupportSQLiteOpenHelper.Factory = object : SupportSQLiteOpenHelper.Factory {
            override fun create(
                configuration: SupportSQLiteOpenHelper.Configuration,
            ): SupportSQLiteOpenHelper = wrapOpenHelper(dbFile, delegate.create(configuration))
        }

        /**
         * 把兜底套在真实 helper 上。
         *
         * 单独抽出来是为了能被纯 JVM 单测直接驱动：`Configuration` 需要 `Context`，纯 JVM 里
         * android.jar 是 stub，构造不出来。直接传 helper 就绕开了这层，接线的验证不再依赖
         * Robolectric（aarch64 上会被静默跳过）。
         */
        @VisibleForTesting
        internal fun wrapOpenHelper(
            dbFile: File,
            helper: SupportSQLiteOpenHelper,
        ): SupportSQLiteOpenHelper = object : SupportSQLiteOpenHelper {
            override val databaseName: String? get() = helper.databaseName
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) =
                helper.setWriteAheadLoggingEnabled(enabled)

            override val writableDatabase: SupportSQLiteDatabase
                get() = openOrQuarantine(dbFile) { helper.writableDatabase }

            override val readableDatabase: SupportSQLiteDatabase
                get() = openOrQuarantine(dbFile) { helper.readableDatabase }

            override fun close() = helper.close()
        }

        /**
         * 跑一次真实的打开动作；只接 `IllegalStateException` 这一种。
         *
         * 只接这一种是有意的：磁盘满、权限不足抛的是 `SQLiteException`，那些不是「无迁移路径」，
         * 搬走库既救不了启动也会误导排查方向。接住 `IllegalStateException` 后隔离 + 重开一次
         * 空库；重开仍失败就把新异常抛出去，不静默。
         */
        @VisibleForTesting
        internal fun <T> openOrQuarantine(dbFile: File, open: () -> T): T {
            return try {
                open()
            } catch (error: IllegalStateException) {
                val key = canonicalKey(dbFile)
                val alreadyQuarantined = synchronized(quarantineLock) { quarantined.containsKey(key) }
                if (alreadyQuarantined) throw error
                val broken = try {
                    synchronized(quarantineLock) {
                        if (!dbFile.isFile) {
                            // 库文件已经不在原位：没有可隔离的东西，原异常照抛。
                            throw error
                        }
                        moveToBroken(
                            dbFile,
                            key,
                            "Room 打开失败（${error.message ?: error.javaClass.simpleName}）",
                        )
                    }
                } catch (quarantineError: EtaDatabaseQuarantineException) {
                    emitQuarantineLog(
                        "Room 打开失败后隔离未完成，异常继续上抛：" +
                            (quarantineError.message ?: quarantineError.javaClass.simpleName),
                        error = true,
                    )
                    throw error
                }
                emitQuarantineLog(
                    "Room 打开失败，已隔离为 ${broken.name} 并重建空库",
                    error = false,
                )
                // 隔离后原路径已让开，这一次打开建的是新空库；再失败就把异常交出去。
                open()
            }
        }

        /**
         * `<db>-wal` 存在且有内容，说明可能有未 checkpoint 的提交。
         *
         * 只判「存在且 > 0 字节」：不解析 WAL 帧格式。帧解析要跟着 SQLite 的
         * checksum 与 salt 规则走，写错会把真实版本读成垃圾值，比多开一次库风险更大。
         */
        private fun hasPendingWal(file: File): Boolean {
            val parent = file.parentFile ?: return false
            val wal = File(parent, file.name + "-wal")
            return wal.isFile && wal.length() > 0L
        }

        /**
         * 只读打开数据库读真实 user_version（WAL 里已提交的版本以这里为准）。
         *
         * 返回 -1 表示读不出来：文件不是库、被别的进程独占、纯 JVM 单测里 android.jar 是
         * stub（任何 android.database 调用抛 RuntimeException("Stub!")），或者 WAL 库在
         * 只读目录下拿不到 -shm 写权限。调用方按「读不出来就保守隔离」处理：
         * 没有 destructive fallback 兜底，放行等于让 Room 抛异常打崩启动路径。
         */
        private fun readUserVersionByOpening(file: File): Int {
            return try {
                val database = android.database.sqlite.SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                )
                try {
                    database.rawQuery("PRAGMA user_version", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else -1
                    }
                } finally {
                    database.close()
                }
            } catch (e: RuntimeException) {
                // SQLiteException 是 RuntimeException 子类，这里一并接住：库被独占、不是 SQLite、
                // 只读目录拿不到 -shm 写权限、以及纯 JVM 单测里 android.jar 是 stub 抛的
                // RuntimeException("Stub!") 都落这里。只降级成「读不出」，不上抛。
                // 降级本身是可见的：这里留一条节流日志，调用方据此走「保守隔离」分支。
                runCatching {
                    io.github.mangi.eta.core.AndroidAgentLogger.warnThrottled(
                        key = "db-wal-version-probe-failed",
                    ) { "WAL 版本探测失败（${e.javaClass.simpleName}: ${e.message}），按保守隔离处理" }
                }
                -1
            }
        }

        /**
         * 迁移链的最低起点：第一条迁移是 6→7。
         *
         * 比它还老的库（v1～v5）今天没有补齐迁移的路径，只能隔离——但这个常量是「隔离判据」，
         * 不是「允许删数据」的许可：将来补上 1→6 迁移时把它改成 1 即可。
         */
        @VisibleForTesting
        internal const val MIN_MIGRATABLE_VERSION = 6

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        // Versions 22-24 of the vivo branch predate upstream's conversation model binding.
        internal val MIGRATION_24_25 = Migration(24, 25) { database ->
            val hasModelId = database.query("PRAGMA table_info(conversations)").use { cursor ->
                val column = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(column) == "model_id") found = true
                found
            }
            if (!hasModelId) database.execSQL("ALTER TABLE conversations ADD COLUMN model_id TEXT")
        }

        /** 26：会话收藏置顶。新列默认 0，旧库升级后全部为非置顶。 */
        internal val MIGRATION_25_26 = Migration(25, 26) { database ->
            database.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }

        /** 27：会话分类。新增分类表与会话归属列，旧会话全部留在未分类。 */
        internal val MIGRATION_26_27 = Migration(26, 27) { database ->
            database.execSQL("CREATE TABLE IF NOT EXISTS conversation_categories (id TEXT NOT NULL, name TEXT NOT NULL, sort_order INTEGER NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id))")
            database.execSQL("ALTER TABLE conversations ADD COLUMN category_id TEXT")
        }

        /** 28：用量统计。新表独立存放，旧库升级后统计从空开始。 */
        internal val MIGRATION_27_28 = Migration(27, 28) { database ->
            database.execSQL("CREATE TABLE IF NOT EXISTS token_usage_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversation_id TEXT, model_id TEXT, created_at INTEGER NOT NULL, kind TEXT NOT NULL, context_tokens INTEGER, input_tokens INTEGER, output_tokens INTEGER, reasoning_tokens INTEGER, cached_tokens INTEGER, duration_ms INTEGER)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_token_usage_records_created_at ON token_usage_records (created_at)")
        }

        internal val MIGRATION_23_24 = Migration(23, 24) { database ->
            database.execSQL("ALTER TABLE conversations ADD COLUMN last_model_usage_json TEXT NOT NULL DEFAULT ''")
        }

        internal val MIGRATION_22_23 = Migration(22, 23) { database ->
            createAgentTaskTables(database)
            database.execSQL("ALTER TABLE conversation_messages ADD COLUMN request_duration_ms INTEGER")
        }

        private fun createAgentTaskTables(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks (" +
                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, prompt TEXT NOT NULL, triggerJson TEXT NOT NULL, " +
                "enabled INTEGER NOT NULL, nextRunAt INTEGER, cooldownSeconds INTEGER NOT NULL, maxRuns INTEGER NOT NULL, " +
                "runCount INTEGER NOT NULL, lastStartedAt INTEGER, lastStatus TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS agent_task_runs (" +
                "id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, fireKey TEXT NOT NULL, status TEXT NOT NULL, " +
                "eventJson TEXT NOT NULL, queuedAt INTEGER NOT NULL, startedAt INTEGER, finishedAt INTEGER, resultPreview TEXT NOT NULL)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_task_runs_taskId_fireKey ON agent_task_runs(taskId,fireKey)")
        }

        internal val MIGRATION_19_20 = Migration(19, 20) { database ->
            database.execSQL("ALTER TABLE conversation_context_checkpoints ADD COLUMN journal_json TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE runtime_inflight_runs ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'")
            database.execSQL("CREATE TABLE IF NOT EXISTS agent_text_chunks (" +
                "owner_table TEXT NOT NULL, owner_id TEXT NOT NULL, field TEXT NOT NULL, " +
                "chunk_index INTEGER NOT NULL, content TEXT NOT NULL, " +
                "PRIMARY KEY(owner_table, owner_id, field, chunk_index))")
            database.execSQL("UPDATE conversation_context_checkpoints SET journal_json = history_json")
            HistoryPayloadMigration.migrate(database)
            createTextChunkCleanup(database)
        }

        internal val MIGRATION_20_21 = Migration(20, 21) { database ->
            database.execSQL("ALTER TABLE conversations ADD COLUMN roleplay_json TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE conversations ADD COLUMN revisions_json TEXT NOT NULL DEFAULT ''")
            listOf("runtime_results", "runtime_archive_runs", "runtime_inflight_runs").forEach { table ->
                database.execSQL("ALTER TABLE $table ADD COLUMN rewrite_target_message_id TEXT")
            }
            database.execSQL("CREATE TABLE IF NOT EXISTS roleplay_characters (" +
                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, card_json TEXT NOT NULL, " +
                "avatar_path TEXT, archived INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS roleplay_user_persona (" +
                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL)")
            createTextChunkCleanup(database)
        }

        internal val MIGRATION_21_22 = Migration(21, 22) { database ->
            database.execSQL("ALTER TABLE conversations ADD COLUMN model_id TEXT")
        }

        internal val MIGRATION_28_29 = Migration(28, 29) { database ->
            database.execSQL("ALTER TABLE model_providers ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'api_key'")
        }

        /**
         * 29 → 30：会话分支（分叉来源 / 分叉点 / 整棵树的根）与消息时间戳。
         *
         * - 老消息没有真实时间，`created_at` 留 0，界面按「不显示时间」处理，不回填假时间。
         * - 老会话把 `branch_root_id` 回填成自身 id，这样第一次分叉时整棵树可以直接由
         *   `branch_root_id` 一次查出来，不需要递归。
         */
        internal val MIGRATION_29_30 = Migration(29, 30) { database ->
            database.execSQL("ALTER TABLE conversations ADD COLUMN parent_conversation_id TEXT")
            database.execSQL("ALTER TABLE conversations ADD COLUMN parent_message_id TEXT")
            database.execSQL("ALTER TABLE conversations ADD COLUMN branch_root_id TEXT")
            database.execSQL("UPDATE conversations SET branch_root_id = id WHERE branch_root_id IS NULL")
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_conversations_branch_root_id ON conversations(branch_root_id)"
            )
            database.execSQL("ALTER TABLE conversation_messages ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
        }

        /**
         * 30 → 31：上下文构成与耗时明细。
         *
         * 五列全部可空，旧消息升级后为 NULL —— 面板按「没有就不画」处理，不回填假数据。
         * 存下来是因为这些值只能从运行期拿到：不落库的话，应用重启后历史消息的用量面板
         * 就只剩头部与进度条。
         */
        internal val MIGRATION_30_31 = Migration(30, 31) { database ->
            database.execSQL("ALTER TABLE conversation_messages ADD COLUMN system_prompt_tokens INTEGER")
            database.execSQL("ALTER TABLE conversation_messages ADD COLUMN tool_definition_tokens INTEGER")
            database.execSQL("ALTER TABLE conversation_messages ADD COLUMN conversation_tokens INTEGER")
            database.execSQL("ALTER TABLE conversation_messages ADD COLUMN first_token_ms INTEGER")
            database.execSQL("ALTER TABLE conversation_messages ADD COLUMN tool_duration_ms INTEGER")
        }

        /**
         * 31 → 32：run 内请求数。
         *
         * 用量面板从「单次响应」改成「整轮任务」：模型用时、输出、缓存命中都是本次 run 的累计，
         * 首 token 显示的是平均值，所以需要知道一共几次请求。
         */
        internal val MIGRATION_31_32 = Migration(31, 32) { database ->
            database.execSQL("ALTER TABLE conversation_messages ADD COLUMN request_count INTEGER")
        }

        /**
         * 32 → 33：缓存写入统计、压缩归档引用与上下文构成补齐。
         *
         * 幂等实现：本仓库有两条来源路径都能走到 33 —— branch 线（32 起，已有上下文五列与
         * request_count）与本地装机线（31 起，已有 cache_write_tokens / compaction_archive_id /
         * token_usage_records 两列但缺上下文列）。逐列查 PRAGMA 再补，缺什么补什么，
         * 不重复 ALTER，两条路径升级后 schema 收敛到同一份。
         */
        internal val MIGRATION_32_33 = Migration(32, 33) { database ->
            database.addColumnIfMissing("conversation_messages", "cache_write_tokens", "INTEGER")
            database.addColumnIfMissing("conversation_messages", "compaction_archive_id", "TEXT")
            database.addColumnIfMissing("token_usage_records", "cache_write_tokens", "INTEGER")
            database.addColumnIfMissing("token_usage_records", "request_kind", "TEXT")
            database.addColumnIfMissing("conversation_messages", "system_prompt_tokens", "INTEGER")
            database.addColumnIfMissing("conversation_messages", "tool_definition_tokens", "INTEGER")
            database.addColumnIfMissing("conversation_messages", "conversation_tokens", "INTEGER")
            database.addColumnIfMissing("conversation_messages", "first_token_ms", "INTEGER")
            database.addColumnIfMissing("conversation_messages", "tool_duration_ms", "INTEGER")
            database.addColumnIfMissing("conversation_messages", "request_count", "INTEGER")
        }

        internal val MIGRATION_33_34 = Migration(33, 34) { database ->
            database.execSQL("ALTER TABLE mcp_servers ADD COLUMN tools_truncation_reason TEXT")
        }

        /**
         * 35：Plan 模式。
         *
         * 两列一次到位：`plan_mode` 记住会话开关，`plan_steps_json` 记住计划清单。
         * 走 [addColumnIfMissing]，两条来源路径（装机版 34 起、旧版一次跳多级）都能落库，
         * 重跑也不会撞 `duplicate column`。
         */
        internal val MIGRATION_34_35 = Migration(34, 35) { database ->
            database.addColumnIfMissing("conversations", "plan_mode", "INTEGER NOT NULL DEFAULT 0")
            database.addColumnIfMissing("conversations", "plan_steps_json", "TEXT NOT NULL DEFAULT ''")
        }

        /**
         * 36：工具运行计时起点。
         *
         * 只加一列，旧消息为 NULL —— 界面按「没有起点」处理，不显示已用时，也不回填假时间。
         * 走 [addColumnIfMissing]，两条来源路径（装机版 35 起、旧库一次跳多级）都能落库，
         * 重跑不会撞 `duplicate column`。
         */
        internal val MIGRATION_35_36 = Migration(35, 36) { database ->
            database.addColumnIfMissing("conversation_messages", "tool_started_at", "INTEGER")
        }

        /** 只在列不存在时 ALTER，供 [MIGRATION_32_33] 合并两条来源路径。 */
        private fun androidx.sqlite.db.SupportSQLiteDatabase.addColumnIfMissing(
            table: String,
            column: String,
            definition: String,
        ) {
            val exists = query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }

        private fun createTextChunkCleanup(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            mapOf("runtime_results" to "run_id", "runtime_archive_runs" to "archive_run_id",
                "runtime_inflight_runs" to "run_id", "conversation_context_checkpoints" to "conversation_id",
                "conversation_messages" to "id", "conversations" to "id",
                "mcp_servers" to "id")
                .plus(if (database.version >= 21 || database.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'roleplay_characters'"
                ).use { it.moveToFirst() }) mapOf("roleplay_characters" to "id", "roleplay_user_persona" to "id") else emptyMap())
                .forEach { (table, key) ->
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS ${table}_text_cleanup AFTER DELETE ON $table " +
                        "BEGIN DELETE FROM agent_text_chunks WHERE owner_table = '$table' AND owner_id = OLD.$key; END")
                }
        }

        internal val MIGRATION_18_19 = Migration(18, 19) { database ->
            listOf("runtime_results", "runtime_archive_runs", "runtime_inflight_runs").forEach { table ->
                database.execSQL("ALTER TABLE $table ADD COLUMN context_snapshot_json TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE $table ADD COLUMN operation TEXT NOT NULL DEFAULT 'chat'")
            }
        }

        internal val MIGRATION_6_7 = Migration(6, 7) { database ->
            database.execSQL(
                "ALTER TABLE runtime_results ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
            )
            database.execSQL(
                "ALTER TABLE runtime_archive_runs ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_16_17 = Migration(16, 17) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS mcp_servers (" +
                    "id TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "enabled INTEGER NOT NULL, " +
                    "protocol_mode TEXT NOT NULL, " +
                    "authorization_type TEXT NOT NULL, " +
                    "tools_json TEXT NOT NULL, " +
                    "enabled_tool_names_json TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "sort_order INTEGER NOT NULL, " +
                    "last_refreshed_at INTEGER, " +
                    "last_protocol_version TEXT, " +
                    "PRIMARY KEY(id))"
            )
        }

        internal val MIGRATION_17_18 = Migration(17, 18) { database ->
            database.execSQL("ALTER TABLE mcp_servers ADD COLUMN tools_expire_at INTEGER")
        }

        internal val MIGRATION_7_8 = Migration(7, 8) { database ->
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN " +
                    "applied_runtime_run_ids_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_8_9 = Migration(8, 9) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'"
            )
            database.execSQL(
                "UPDATE provider_models SET source = 'catalog' WHERE is_built_in = 1"
            )
            // 旧版“添加自定义模型”会在打开编辑框时提前落下一条空记录。
            database.execSQL("DELETE FROM provider_models WHERE TRIM(model_id) = ''")
            // 只清理由旧版“新建对话”产生、且用户从未真正使用或命名过的占位记录。
            database.execSQL(
                "DELETE FROM conversations " +
                    "WHERE title = '新对话' " +
                    "AND TRIM(history_json) = '[]' " +
                    "AND TRIM(applied_runtime_run_ids_json) = '[]' " +
                    "AND NOT EXISTS (" +
                    "SELECT 1 FROM conversation_messages " +
                    "WHERE conversation_messages.conversation_id = conversations.id)"
            )
            database.execSQL(
                "DELETE FROM conversation_state WHERE selected_conversation_id NOT IN " +
                    "(SELECT id FROM conversations)"
            )
        }

        internal val MIGRATION_9_10 = Migration(9, 10) { database ->
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN " +
                    "reasoning_effort TEXT NOT NULL DEFAULT 'default'"
            )
            database.execSQL(
                "UPDATE conversations SET reasoning_effort = " +
                    "CASE WHEN thinking_enabled = 1 THEN 'default' ELSE 'off' END"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN " +
                    "reasoning_capabilities_json TEXT NOT NULL DEFAULT 'null'"
            )
        }

        internal val MIGRATION_10_11 = Migration(10, 11) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_context_checkpoints (" +
                    "conversation_id TEXT NOT NULL, " +
                    "history_json TEXT NOT NULL, " +
                    "PRIMARY KEY(conversation_id), " +
                    "FOREIGN KEY(conversation_id) REFERENCES conversations(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "INSERT INTO conversation_context_checkpoints (conversation_id, history_json) " +
                    "SELECT id, history_json FROM conversations"
            )
            // SQL 内搬移完整正文；后续分块迁移负责行大小，不能因旧字段过大丢弃历史。
            database.execSQL("UPDATE conversations SET history_json = '[]'")
        }

        internal val MIGRATION_11_12 = Migration(11, 12) { database ->
            database.execSQL(
                "ALTER TABLE conversation_messages ADD COLUMN " +
                    "is_edited INTEGER NOT NULL DEFAULT 0"
            )
        }

        internal val MIGRATION_12_13 = Migration(12, 13) { database ->
            database.execSQL(
                "ALTER TABLE model_providers ADD COLUMN " +
                    "hosted_web_search_enabled INTEGER NOT NULL DEFAULT 0"
            )
        }

        internal val MIGRATION_13_14 = Migration(13, 14) { database ->
            database.execSQL(
                "ALTER TABLE runtime_archive_runs ADD COLUMN " +
                    "user_image_previews_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_14_15 = Migration(14, 15) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN context_window_override INTEGER"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN reasoning_override INTEGER"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN " +
                    "reasoning_capabilities_override_json TEXT NOT NULL DEFAULT 'null'"
            )
        }

        internal val MIGRATION_15_16 = Migration(15, 16) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS runtime_inflight_runs (" +
                    "run_id TEXT NOT NULL, " +
                    "owner_instance_id TEXT NOT NULL, " +
                    "handoff_id TEXT NOT NULL, " +
                    "handoff_source TEXT NOT NULL, " +
                    "handoff_payload TEXT NOT NULL, " +
                    "dismiss_entry_surface INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(run_id))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS runtime_inflight_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "run_id TEXT NOT NULL, " +
                    "sort_index INTEGER NOT NULL, " +
                    "event_json TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runtime_inflight_runs(run_id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_runtime_inflight_events_run_id " +
                    "ON runtime_inflight_events(run_id)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_runtime_inflight_events_run_id_sort_index " +
                    "ON runtime_inflight_events(run_id, sort_index)"
            )
        }
    }
}
