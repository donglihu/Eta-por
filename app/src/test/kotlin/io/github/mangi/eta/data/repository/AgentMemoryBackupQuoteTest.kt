package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆镜像备份的 shell 转义用例（纯 JVM，不依赖 Android）。
 *
 * 背景：`backupViaRoot` 把路径拼进 `su -c` 脚本。路径里的单引号若不转义，
 * 单引号会提前闭合，root 执行的就是被截断后的命令 —— 备份失败，甚至执行意外命令。
 */
class AgentMemoryBackupQuoteTest {

    @Test
    fun plainPathIsWrappedInSingleQuotes() {
        assertEquals("'/data/local/tmp/eta/memory'", AgentMemoryBackup.shq("/data/local/tmp/eta/memory"))
    }

    @Test
    fun singleQuoteIsEscapedNotLeftBare() {
        val quoted = AgentMemoryBackup.shq("/data/o'brien/memory")
        // 结果必须以单引号开头结尾，中间的 ' 全部变成 '\''
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        assertEquals("'/data/o'\\''brien/memory'", quoted)
    }

    @Test
    fun escapedPathKeepsQuoteCountBalanced() {
        val quoted = AgentMemoryBackup.shq("/a'b'c")
        // 整体引号 2 个 + 每个内嵌单引号产生 2 个（'\'' 里有 2 个单引号）
        val quotes = quoted.count { it == '\'' }
        assertTrue("单引号数量必须为偶数，否则 shell 会认为字符串未闭合: $quoted", quotes % 2 == 0)
    }

    @Test
    fun pathWithDollarAndBacktickIsNotInterpreted() {
        val quoted = AgentMemoryBackup.shq("/tmp/\$(whoami)`id`")
        // 单引号内 $ 与反引号不展开，原样保留即安全
        assertTrue(quoted.contains("\$(whoami)"))
        assertTrue(quoted.contains("`id`"))
        assertFalse("不应出现未包裹的裸 $ 前缀", quoted.startsWith("$"))
    }

    @Test
    fun emptyPathStillProducesQuotedEmptyString() {
        assertEquals("''", AgentMemoryBackup.shq(""))
    }
}
