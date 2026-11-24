package io.github.mangi.eta.data.repository

import java.io.File
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.MemoryBudgetPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 本轮四问的回归用例，纯 JVM 运行（不依赖 Robolectric）。
 *
 * 存储层通过 [PlainMemoryFileStore] 注入非 Android 的原子写实现：
 * arm64 上 Robolectric 会被 assume 跳过，用 Robolectric 写的用例等于没验证。
 */
class AgentMemoryBudgetBackupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = AgentMemoryStore(
        rootDir = temporaryFolder.newFolder(),
        fileFactory = { file -> PlainMemoryFileStore(file) },
    )

    @Test
    fun rejectsGrowthPastCoreBudgetButAllowsTrimming() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n" + "约束".repeat(30))

        val rejected = assertThrows(AgentMemoryException::class.java) {
            store.mutate(
                AgentMemoryMutation.Append(initial.revision, "\n## 项目\n新增进展"),
                coreBudgetChars = 20,
            )
        }
        assertEquals("MEMORY_BUDGET_EXCEEDED", rejected.code)
        assertEquals(initial.content, store.snapshot().content)
        assertEquals(initial.revision, store.snapshot().revision)

        // 仍然超预算（26 > 20），但比之前小：修剪必须放行，否则超限文件永远救不回来。
        val trimmed = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = initial.revision,
                startLine = 1,
                endLine = 2,
                content = "# 核心记忆\n" + "约束".repeat(10),
            ),
            coreBudgetChars = 20,
        )
        assertTrue(trimmed is AgentMemoryWriteResult.Success)
        assertEquals("# 核心记忆\n" + "约束".repeat(10), store.snapshot().content)
    }

    @Test
    fun budgetAllowsWritesThatStayInsideLimit() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n短规则")

        val result = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "\n## 偏好\n夜间用深色"),
            coreBudgetChars = 8_000,
        )

        assertTrue(result is AgentMemoryWriteResult.Success)
        assertTrue(store.snapshot().content.contains("夜间用深色"))
    }

    @Test
    fun notesChannelKeepsCoreMemoryUntouched() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n稳定偏好")

        val notes = store.appendNote("项目进展：构建通过", now = 1_700_000_000_000)

        assertTrue(notes.content.contains("项目进展：构建通过"))
        assertEquals(initial.content, store.snapshot().content)
        assertEquals(initial.revision, store.snapshot().revision)
        assertTrue(store.readNotes(query = "构建").content.contains("构建通过"))
        assertFalse(File(store.memoryDirectory(), "MEMORY.md").readText().contains("构建通过"))
    }

    @Test
    fun backupWritesTimestampedCopyLatestAndPrunesOldSnapshots() {
        val sourceDir = temporaryFolder.newFolder("source")
        File(sourceDir, "MEMORY.md").writeText("# 核心记忆\n内容")
        File(sourceDir, "MEMORY-ARCHIVE.md").writeText("冷区正文")
        val target = File(temporaryFolder.root, "mirror")

        val first = AgentMemoryBackup.backup(sourceDir, target, now = 1_700_000_000_000)

        assertTrue(first.error.orEmpty(), first.ok)
        assertTrue(File(target, AgentMemoryBackup.LATEST_FILE).readText().contains("内容"))
        assertTrue(File(target, AgentMemoryBackup.ARCHIVE_LATEST_FILE).exists())
        val snapshot = File(target, requireNotNull(first.file))
        assertTrue(snapshot.exists())
        assertEquals("# 核心记忆\n内容", snapshot.readText())

        repeat(AgentMemoryBackup.KEEP + 5) { index ->
            AgentMemoryBackup.backup(sourceDir, target, now = 1_700_000_000_000 + index * 60_000L)
        }
        val snapshots = target.listFiles { file: File ->
            file.isFile && file.name.startsWith("MEMORY-") &&
                file.name != AgentMemoryBackup.LATEST_FILE && file.name != AgentMemoryBackup.ARCHIVE_LATEST_FILE
        }.orEmpty()
        assertTrue("snapshots=${snapshots.size}", snapshots.size <= AgentMemoryBackup.KEEP * 2)
    }

    @Test
    fun backupReportsMissingSourceInsteadOfThrowing() {
        val sourceDir = temporaryFolder.newFolder("empty")
        val status = AgentMemoryBackup.backup(sourceDir, File(temporaryFolder.root, "mirror2"), now = 1L)

        assertFalse(status.ok)
        assertTrue(status.error.orEmpty().contains("记忆文件不存在"))
    }

    @Test
    fun coreSectionIsSplitWithTheSameRulerAsInjection() {
        val markdown = "# 核心记忆\n规则\n# 其他\n不该算进核心"

        assertEquals("# 核心记忆\n规则".length, AgentMemoryCoreSection.chars(markdown))
        assertFalse(AgentMemoryCoreSection.content(markdown).contains("不该算进核心"))
    }

    @Test
    fun coreHeadingWithSuffixIsStillRecognised() {
        // 真实文件头是 `# 核心记忆 P0`：全等比较会掉进兜底分支，量到的不是核心段。
        val markdown = "# 核心记忆 P0\n规则\n# 其他\n不该算进核心"

        assertEquals("# 核心记忆 P0\n规则".length, AgentMemoryCoreSection.chars(markdown))
    }

    @After
    fun resetBudgetPolicy() {
        // MemoryBudgetPolicy 是进程级缓存，用例之间必须复位，否则顺序一换就串味。
        MemoryBudgetPolicy.apply(0, 0)
    }

    @Test
    fun budgetFollowsSliderSettingInsteadOfHardcodedDefault() {
        MemoryBudgetPolicy.apply(4_000, 0)
        val slider = AgentMemoryContextBuilder.coreBudgetChars(null)
        assertEquals(4_000, slider)

        val store = store()
        val initial = store.replaceAll("# 核心记忆\n" + "约束".repeat(100))
        val padded = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "\n" + "约束".repeat(800)),
            coreBudgetChars = slider,
        )
        assertTrue(padded is AgentMemoryWriteResult.Success)

        // 过了滑杆值就是超预算：默认 8,000 在这里会放行，滑杆值必须生效。
        val latest = store.snapshot()
        val rejected = assertThrows(AgentMemoryException::class.java) {
            store.mutate(
                AgentMemoryMutation.Append(latest.revision, "\n" + "约束".repeat(1_500)),
                coreBudgetChars = slider,
            )
        }
        assertEquals("MEMORY_BUDGET_EXCEEDED", rejected.code)
    }

    @Test
    fun pinnedSectionGrowthCountsAgainstBudget() {
        // 钉住章节写在核心段之外，但一样会被注入：只量核心段就会把这条路放过去。
        val base = "# 核心记忆\n短规则\n# 其他\n## ⛔ 硬约束\n" + "铁律".repeat(10)
        val store = store()
        val initial = store.replaceAll(base)
        val injectedBefore = AgentMemoryCoreSection.injectedChars(base)
        assertTrue(injectedBefore > AgentMemoryCoreSection.chars(base))

        val rejected = assertThrows(AgentMemoryException::class.java) {
            store.mutate(
                AgentMemoryMutation.Append(initial.revision, "\n" + "铁律".repeat(40)),
                coreBudgetChars = injectedBefore + 10,
            )
        }
        assertEquals("MEMORY_BUDGET_EXCEEDED", rejected.code)
    }
}
