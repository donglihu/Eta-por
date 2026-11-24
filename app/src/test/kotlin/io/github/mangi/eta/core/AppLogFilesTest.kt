package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日志文件的行解析：日志页按等级着色、按等级过滤全靠它。
 *
 * 对应需求：日志要落盘成文件（之前只有 logcat，没有文件），界面要能显示等级。
 */
class AppLogFilesTest {

    @Test
    fun `parses standard line into time level tag and message`() {
        val entry = AppLogFiles.parseLine("2026-11-20 08:15:32.348 [INFO] [FileLogger] 日志初始化完成")
        assertEquals("2026-11-20 08:15:32.348", entry?.time)
        assertEquals(AppLogLevel.INFO, entry?.level)
        assertEquals("FileLogger", entry?.tag)
        assertEquals("日志初始化完成", entry?.message)
    }

    @Test
    fun `parses each level key`() {
        AppLogLevel.entries.forEach { level ->
            val entry = AppLogFiles.parseLine("2026-11-20 09:00:00.000 [${level.label}] [run] msg")
            assertEquals(level, entry?.level)
        }
    }

    @Test
    fun `blank line yields null`() {
        assertNull(AppLogFiles.parseLine("   "))
    }

    @Test
    fun `line without time prefix is skipped instead of mislabeled`() {
        assertNull(AppLogFiles.parseLine("随便一段没有时间戳的文本"))
    }

    @Test
    fun `unknown level label falls back to INFO so old files still render`() {
        val entry = AppLogFiles.parseLine("2026-11-20 09:00:00.000 [TRACE] [app] msg")
        assertEquals(AppLogLevel.INFO, entry?.level)
    }

    @Test
    fun `message keeps bracket characters after tag`() {
        val entry = AppLogFiles.parseLine("2026-11-20 09:00:00.000 [WARN] [http] status=[503] retry")
        assertEquals("http", entry?.tag)
        assertEquals("status=[503] retry", entry?.message)
    }

    @Test
    fun `level filter keeps only the selected level`() {
        // 需求：每个等级只显示该等级。选 WARN 就只有 WARN，ERROR 不再被带出来。
        val entries = listOf(
            entry("2026-11-20 09:00:00.000", AppLogLevel.VERBOSE, "v"),
            entry("2026-11-20 09:00:01.000", AppLogLevel.DEBUG, "d"),
            entry("2026-11-20 09:00:02.000", AppLogLevel.INFO, "i"),
            entry("2026-11-20 09:00:03.000", AppLogLevel.WARN, "w"),
            entry("2026-11-20 09:00:04.000", AppLogLevel.ERROR, "e"),
        )

        AppLogLevel.entries.forEach { level ->
            val selected = AppLogView.select(entries = entries, level = level, query = "")
            assertEquals(listOf(level), selected.map { it.level })
        }
    }

    @Test
    fun `null level means show everything`() {
        val entries = listOf(
            entry("2026-11-20 09:00:00.000", AppLogLevel.INFO, "i"),
            entry("2026-11-20 09:00:01.000", AppLogLevel.ERROR, "e"),
        )
        val selected = AppLogView.select(entries = entries, level = null, query = "")
        assertEquals(2, selected.size)
    }

    @Test
    fun `newest entry comes first`() {
        // 需求：日志最新显示在顶部。文件是追加写的，正序读出来最旧在前，界面必须反转。
        val entries = listOf(
            entry("2026-11-20 09:00:00.000", AppLogLevel.INFO, "第一条"),
            entry("2026-11-20 09:00:01.000", AppLogLevel.INFO, "第二条"),
            entry("2026-11-20 09:00:02.000", AppLogLevel.INFO, "第三条"),
        )

        val selected = AppLogView.select(entries = entries, level = null, query = "")
        assertEquals(listOf("第三条", "第二条", "第一条"), selected.map { it.message })
    }

    @Test
    fun `limit keeps the newest entries not the oldest`() {
        // 上限截断必须保留最新的一段；旧实现用 takeLast 再正序渲染，顶部反而是最旧的。
        val entries = (1..10).map { index ->
            entry("2026-11-20 09:00:0$index.000", AppLogLevel.INFO, "第 $index 条")
        }

        val selected = AppLogView.select(entries = entries, level = null, query = "", limit = 3)
        assertEquals(listOf("第 10 条", "第 9 条", "第 8 条"), selected.map { it.message })
    }

    @Test
    fun `search matches message and tag ignoring case and surrounding blanks`() {
        val entries = listOf(
            entry("2026-11-20 09:00:00.000", AppLogLevel.INFO, "HTTP 503", tag = "net"),
            entry("2026-11-20 09:00:01.000", AppLogLevel.INFO, "正常", tag = "Run"),
        )

        assertEquals(listOf("net"), AppLogView.select(entries, null, "  http  ").map { it.tag })
        assertEquals(listOf("Run"), AppLogView.select(entries, null, "run").map { it.tag })
    }

    @Test
    fun `empty input yields empty result and zero limit is rejected`() {
        assertEquals(emptyList<AppLogEntry>(), AppLogView.select(emptyList(), null, ""))
        try {
            AppLogView.select(listOf(entry("2026-11-20 09:00:00.000", AppLogLevel.INFO, "x")), null, "", limit = 0)
            throw AssertionError("limit = 0 应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertEquals("日志渲染上限必须大于 0", expected.message)
        }
    }

    @Test
    fun `keys stay stable for old rows when a new entry is prepended`() {
        // 新日志插到顶部时，旧行的 key 不能变，否则 LazyColumn 会整体重排、滚动位置跳动。
        val first = entry("2026-11-20 09:00:00.000", AppLogLevel.INFO, "第一条")
        val second = entry("2026-11-20 09:00:01.000", AppLogLevel.INFO, "第二条")
        val third = entry("2026-11-20 09:00:02.000", AppLogLevel.INFO, "第三条")

        val before = AppLogView.keysOf(AppLogView.select(listOf(first, second), null, ""))
        val after = AppLogView.keysOf(AppLogView.select(listOf(first, second, third), null, ""))

        assertEquals(before, after.takeLast(2))
    }

    @Test
    fun `keys stay unique for duplicate lines`() {
        // 同一毫秒可能落两条完全相同的行；重复 key 会让 LazyColumn 直接抛异常。
        val same = entry("2026-11-20 09:00:00.000", AppLogLevel.INFO, "重复")
        val keys = AppLogView.keysOf(listOf(same, same, same))
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun `display line round-trips the stored format`() {
        val entry = AppLogEntry("2026-11-20 08:15:32.348", AppLogLevel.ERROR, "run", "失败了")
        assertEquals("2026-11-20 08:15:32.348 [ERROR] [run] 失败了", entry.displayLine)
    }

    private fun entry(
        time: String,
        level: AppLogLevel,
        message: String,
        tag: String = "app",
    ) = AppLogEntry(time = time, level = level, tag = tag, message = message)

    @Test
    fun `trace infers error for failures`() {
        assertEquals(AppLogLevel.ERROR, EtaTrace.inferLevel("run", "run.fail runId=abc"))
        assertEquals(AppLogLevel.ERROR, EtaTrace.inferLevel("diag", "自动导出失败：FileNotFoundException"))
        assertEquals(AppLogLevel.ERROR, EtaTrace.inferLevel("http", "request timeout after 15s"))
    }

    @Test
    fun `trace infers warn for degraded paths`() {
        assertEquals(AppLogLevel.WARN, EtaTrace.inferLevel("display", "main screen fallback approved"))
        assertEquals(AppLogLevel.WARN, EtaTrace.inferLevel("net", "触发重试"))
        assertEquals(AppLogLevel.WARN, EtaTrace.inferLevel("completion.skip", "POST_NOTIFICATIONS 未授权"))
    }

    @Test
    fun `trace ignores severity keywords inside the event name`() {
        // 「前台且在看该会话，所以不弹通知」是正常分支：事件名里的 skip 不是严重度，
        // 早先把它拼进匹配文本，导致 38 条正常记录被记成 WARN，等级筛选形同虚设。
        assertEquals(AppLogLevel.INFO, EtaTrace.inferLevel("completion.skip", "前台且在看该会话"))
        assertEquals(AppLogLevel.INFO, EtaTrace.inferLevel("heavy.start", "cat /tmp/build.log | grep BUILD"))
        assertEquals(AppLogLevel.INFO, EtaTrace.inferLevel("confirm.skip", "title=继续执行"))
    }

    @Test
    fun `trace keeps normal events at info`() {
        assertEquals(AppLogLevel.INFO, EtaTrace.inferLevel("app", "onCreate"))
        assertEquals(AppLogLevel.INFO, EtaTrace.inferLevel("run", "run.end runId=abc 剩余并发=0/6"))
    }
}
