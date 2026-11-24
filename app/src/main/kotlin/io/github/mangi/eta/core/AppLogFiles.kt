package io.github.mangi.eta.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志等级：界面按它着色，落盘也带这个字段。 */
enum class AppLogLevel(val key: String, val label: String, val priority: Int) {
    VERBOSE("V", "VERBOSE", 0),
    DEBUG("D", "DEBUG", 1),
    INFO("I", "INFO", 2),
    WARN("W", "WARN", 3),
    ERROR("E", "ERROR", 4);

    companion object {
        fun fromKey(key: String?): AppLogLevel =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: INFO

        fun fromLabel(label: String?): AppLogLevel =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: INFO
    }
}

/** 一条已落盘的日志。 */
internal data class AppLogEntry(
    val time: String,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
) {
    /** 界面上原样展示的一行（与文件里存的格式一致）。 */
    val displayLine: String get() = "$time [${level.label}] [$tag] $message"
}

/**
 * 日志页的筛选与排序：抽成纯函数，界面只负责渲染，单测能直接覆盖。
 *
 * 两条硬约束（都来自使用现场）：
 * 1. **最新在最上**：文件是追加写的，条目天然按时间正序；这里反转一次，
 *    让 index 0 就是最新一条，列表从上往下读就是「新的在前」。
 * 2. **等级精确匹配**：选中 WARN 就只留 WARN，不再把 ERROR 一起带出来；
 *    `level == null` 才表示「全部」。原来的 `priority >=` 是「及以上」语义，
 *    和「每个等级只显示该等级」不是一回事。
 */
internal object AppLogView {

    /** 界面与单测共用的单次渲染上限：文件可能很大，只取最新的一段。 */
    const val DEFAULT_LIMIT = 1500

    fun select(
        entries: List<AppLogEntry>,
        level: AppLogLevel?,
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<AppLogEntry> {
        require(limit > 0) { "日志渲染上限必须大于 0" }
        if (entries.isEmpty()) return emptyList()
        val keyword = query.trim()
        return entries.asReversed()
            .asSequence()
            .filter { level == null || it.level == level }
            .filter { keyword.isEmpty() || it.matches(keyword) }
            .take(limit)
            .toList()
    }

    /**
     * 列表项 key：内容稳定，新日志插到顶部时旧行的 key 不变，滚动位置不会跳。
     *
     * 同一毫秒可能落两条完全相同的行，所以给重复项补序号保证 key 唯一——
     * LazyColumn 遇到重复 key 会直接抛异常，不能只靠内容。
     */
    fun keysOf(entries: List<AppLogEntry>): List<String> {
        val seen = HashMap<String, Int>()
        return entries.map { entry ->
            val base = "${entry.time}|${entry.level.key}|${entry.tag}|${entry.message}"
            val occurrence = (seen[base] ?: 0) + 1
            seen[base] = occurrence
            if (occurrence == 1) base else "$base#$occurrence"
        }
    }

    private fun AppLogEntry.matches(keyword: String): Boolean =
        message.contains(keyword, ignoreCase = true) || tag.contains(keyword, ignoreCase = true)
}

/** 一个日志文件及其元信息。 */
internal data class AppLogFile(
    val file: File,
    val name: String,
    val date: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/**
 * 应用日志文件。
 *
 * 为什么要有它：logcat 是环形缓冲区、release 还会删掉 VERBOSE/DEBUG，出问题时现场常被冲掉；
 * 而「日志」页如果只读 logcat，用户看到的是系统噪声，也没法回看昨天。
 * 这里把应用自己的日志按文件落盘，界面直接读文件。
 *
 * 保留策略：**最多 3 份**，超过就删最旧的（用户要求）。
 * 单文件超过 [MAX_BYTES_PER_FILE] 时滚动到新文件，避免一个文件无限长。
 */
internal object AppLogFiles {

    private const val DIR_NAME = "logs"
    private const val MAX_FILES = 3
    private const val MAX_BYTES_PER_FILE = 512L * 1024

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            // 启动即落一条，用户打开日志页能立刻看到「有文件、有时间」，
            // 而不是空的让人以为功能没做。
            appendInternal(AppLogLevel.INFO, "FileLogger", "日志初始化完成，目录：${File(context.filesDir, DIR_NAME).absolutePath}")
        }
    }

    fun log(level: AppLogLevel, tag: String, message: String) {
        if (appContext == null) return
        synchronized(lock) { appendInternal(level, tag, message) }
    }

    /** 日志目录；未初始化时返回 null，调用方据此给「尚未就绪」而不是空列表。 */
    fun directoryOrNull(): File? = appContext?.let { File(it.filesDir, DIR_NAME) }

    fun listFiles(): List<AppLogFile> {
        val dir = directoryOrNull() ?: return emptyList()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") } ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                AppLogFile(
                    file = file,
                    name = file.name,
                    date = fileDateFormat.format(Date(file.lastModified())),
                    sizeBytes = file.length(),
                    modifiedAt = file.lastModified(),
                )
            }
    }

    /** 读一个文件；文件已被轮转删除时返回空表，界面自己兜底。 */
    fun read(file: File): List<AppLogEntry> {
        if (!file.exists() || !file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull(::parseLine)
        }.getOrDefault(emptyList())
    }

    /**
     * 解析一行：`2026-11-20 08:15:32.348 [INFO] [FileLogger] 内容`。
     * 老格式（没有等级段）按 INFO 收下，不让历史文件整段消失。
     */
    internal fun parseLine(line: String): AppLogEntry? {
        if (line.isBlank()) return null
        val levelStart = line.indexOf(" [") 
        val time = if (levelStart > 0) line.substring(0, levelStart) else return null
        val afterLevel = line.substring(levelStart + 2)
        val levelEnd = afterLevel.indexOf(']')
        if (levelEnd <= 0) return AppLogEntry(time, AppLogLevel.INFO, "app", afterLevel)
        val level = AppLogLevel.fromLabel(afterLevel.substring(0, levelEnd))
        val rest = afterLevel.substring(levelEnd + 1).trimStart()
        // tag 形如 `[FileLogger]`；没有就整体当消息。
        if (!rest.startsWith("[")) return AppLogEntry(time, level, "app", rest)
        val tagEnd = rest.indexOf(']')
        if (tagEnd <= 0) return AppLogEntry(time, level, "app", rest)
        val tag = rest.substring(1, tagEnd)
        val message = rest.substring(tagEnd + 1).trimStart()
        return AppLogEntry(time, level, tag, message)
    }

    private fun appendInternal(level: AppLogLevel, tag: String, message: String) {
        val context = appContext ?: return
        val now = Date()
        val line = "${timeFormat.format(now)} [${level.label}] [$tag] $message\n"
        runCatching {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val current = currentWritableFile(dir, now)
            current.appendText(line, Charsets.UTF_8)
            enforceRetention(dir)
        }.onFailure { failure ->
            // 日志组件自己写失败必须留痕，否则「日志页空着」和「没出问题」长得一样。
            // 落盘不可用，只能退回 logcat——这是最后一条可见通道。
            android.util.Log.w(
                "AppLogFiles",
                "日志落盘失败 level=${level.key} tag=$tag: ${failure.javaClass.simpleName}",
            )
        }
    }

    /** 当前文件；不存在或超过体积上限就换一个新的。 */
    private fun currentWritableFile(dir: File, now: Date): File {
        val existing = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
        if (existing != null && existing.length() < MAX_BYTES_PER_FILE) return existing
        return File(dir, "eta-${fileStampFormat.format(now)}.log").also { it.createNewFile() }
    }

    /** 只保留最新 [MAX_FILES] 份，其余直接删掉——旧日志不归档。 */
    private fun enforceRetention(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_FILES).forEach { stale ->
            runCatching { stale.delete() }
        }
    }
}