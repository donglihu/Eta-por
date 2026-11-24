package io.github.mangi.eta.agent.device

import android.net.Uri
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.core.AgentLogger
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(EtaRobolectricTestRunner::class)
class AgentFileReferenceGatewayTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appReadableFilesAndDirectoriesNeverAskForRoot() {
        val file = temporaryFolder.newFile("report.txt").apply { writeText("ordinary file") }
        val gateway = AgentFileReferenceGateway(rootAvailable = { false }) {
            error("普通路径不应执行 Root 校验")
        }
        val result = gateway.resolveAbsolutePath(file.absolutePath, AgentFileReferenceKind.File)
            as AgentFileReferenceGateway.Resolution.Success
        assertEquals(file.canonicalPath, result.reference.absolutePath)
        assertFailure(
            AgentFileReferenceGateway.Error.TypeMismatch,
            gateway.resolveAbsolutePath(file.absolutePath, AgentFileReferenceKind.Directory),
        )
        assertTrue(gateway.resolveAbsolutePath(temporaryFolder.root.path) is AgentFileReferenceGateway.Resolution.Success)
        assertFailure(
            AgentFileReferenceGateway.Error.PathNotFound,
            gateway.resolveAbsolutePath(File(temporaryFolder.root, "missing").path),
        )
    }

    @Test
    fun documentWithNoFilesystemPathIsImportedIntoPersistentWorkspace() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://cloud.example/document/report")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("授权内容".toByteArray()))
        val gateway = AgentFileReferenceGateway(context, NoOpLogger, rootAvailable = { false })

        val result = gateway.resolveDocumentUri(uri, AgentFileReferenceKind.File)
            as AgentFileReferenceGateway.Resolution.Success
        val imported = File(result.reference.absolutePath)
        assertTrue(imported.path.startsWith(File(context.filesDir, "terminal-user/workspace/imports").path + "/"))
        assertEquals("授权内容", imported.readText())
        imported.parentFile?.deleteRecursively()
    }

    @Test
    fun importNamesCannotEscapeTheirPrivateDirectory() {
        assertEquals(".._.._secret_name", AgentFileReferenceGateway.safeImportName("../../secret\nname"))
        assertEquals("imported-file", AgentFileReferenceGateway.safeImportName(".."))
    }

    @Test
    fun mapPrimaryStorageDocument_mapsPrimaryAndNonPrimaryVolumes() {
        assertEquals(
            "/storage/emulated/0/Download/report.txt",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/report.txt",
            ),
        )
        assertEquals(
            "/storage/emulated/0",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:",
            ),
        )
        // SD 卡 / 其它卷的 documentId 形如 1A1B-2C3D:Download/a.bin，必须映射到
        // /storage/<volumeId>/<relativePath>，否则会掉进复制导入分支撞大小限制。
        assertEquals(
            "/storage/1A1B-2C3D/Download/a.bin",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "1A1B-2C3D:Download/a.bin",
            ),
        )
        assertEquals(
            "/storage/1234-5678",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "1234-5678:",
            ),
        )
        // 非 externalstorage 授权的 documentId 不能当本地路径用。
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                "com.android.providers.downloads.documents",
                "primary:Download/report.txt",
            )
        )
        // 没有 ':' 分隔符就不是卷路径。
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "1234-5678-report.txt",
            )
        )
    }

    @Test
    fun mapPrimaryStorageDocument_rejectsTraversalAndControlCharacters() {
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/../secret",
            )
        )
        // 非 primary 卷同样要挡住 .. 穿越、卷名穿越与控制字符。
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "1A1B-2C3D:Download/../secret",
            )
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "..:Download/secret",
            )
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "1A1B-2C3D:/secret",
            )
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/bad\nname",
            )
        )
    }

    @Test
    fun computeImportLimit_followsUsableSpaceWithSafetyMarginAndHardCap() {
        val mib = 1L * 1024 * 1024
        // 可用空间充足时，余量取「64 MiB 与可用空间 10% 的较大者」，上限随之放大。
        assertEquals(
            2_000L * mib - 200L * mib,
            AgentFileReferenceGateway.computeImportLimit(2_000L * mib),
        )
        // 原 32 MiB 文件必须放行：上限不再写死常数。
        assertTrue(
            AgentFileReferenceGateway.computeImportLimit(2_000L * mib) > 32L * mib,
        )
        // 可用空间很小、10% 余量低于 64 MiB 时按 64 MiB 扣，且不低于下限。
        assertEquals(
            AgentFileReferenceGateway.MIN_IMPORT_BYTES,
            AgentFileReferenceGateway.computeImportLimit(40L * mib),
        )
        // 硬上限防失控：可用空间被误读成超大值时封顶 4 GiB。
        assertEquals(
            AgentFileReferenceGateway.MAX_IMPORT_LIMIT_BYTES,
            AgentFileReferenceGateway.computeImportLimit(16L * 1024L * mib),
        )
    }

    @Test
    fun resolveAbsolutePath_returnsCanonicalFileAndDirectory() {
        val fileGateway = AgentFileReferenceGateway(rootAvailable = { true }) {
            success("file\n/storage/emulated/0/Download/report.txt")
        }
        val directoryGateway = AgentFileReferenceGateway(rootAvailable = { true }) {
            success("directory\n/data/local/tmp/project")
        }

        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "report.txt",
                    absolutePath = "/storage/emulated/0/Download/report.txt",
                    kind = AgentFileReferenceKind.File,
                )
            ),
            fileGateway.resolveAbsolutePath(
                "/storage/emulated/0/Download/report.txt",
                AgentFileReferenceKind.File,
            ),
        )
        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "project",
                    absolutePath = "/data/local/tmp/project",
                    kind = AgentFileReferenceKind.Directory,
                )
            ),
            directoryGateway.resolveAbsolutePath("/data/local/tmp/project"),
        )
    }

    @Test
    fun resolveAbsolutePath_mapsRootAndFilesystemFailures() {
        assertFailure(
            expected = AgentFileReferenceGateway.Error.RootUnavailable,
            result = AgentFileReferenceGateway(rootAvailable = { true }) {
                BoundedRootCommandExecutor.Result.failed("ROOT_UNAVAILABLE")
            }.resolveAbsolutePath("/data/local/tmp/file"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.RootUnavailable,
            result = AgentFileReferenceGateway(rootAvailable = { true }) { failed(exitCode = 20) }
                .resolveAbsolutePath("/data/local/tmp/file"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.UnsupportedFileType,
            result = AgentFileReferenceGateway(rootAvailable = { true }) { failed(exitCode = 23) }
                .resolveAbsolutePath("/data/local/tmp/socket"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.PathNotFound,
            result = AgentFileReferenceGateway(rootAvailable = { true }) { failed(exitCode = 21) }
                .resolveAbsolutePath("/data/local/tmp/missing"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.ValidationTimedOut,
            result = AgentFileReferenceGateway(rootAvailable = { true }) {
                failed(exitCode = -2, timedOut = true)
            }.resolveAbsolutePath("/data/local/tmp/file"),
        )
        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "secret",
                    absolutePath = "/data/adb/secret",
                    kind = AgentFileReferenceKind.File,
                )
            ),
            AgentFileReferenceGateway(rootAvailable = { true }) {
                success("file\n/data/adb/secret")
            }.resolveAbsolutePath("/data/adb/secret"),
        )
    }

    @Test
    fun resolveAbsolutePath_rejectsInvalidInputAndTypeMismatch() {
        var executed = false
        val invalidGateway = AgentFileReferenceGateway(rootAvailable = { true }) {
            executed = true
            success("file\n/data/local/tmp/file")
        }
        assertFailure(
            expected = AgentFileReferenceGateway.Error.InvalidPath,
            result = invalidGateway.resolveAbsolutePath("relative/path"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.InvalidPath,
            result = invalidGateway.resolveAbsolutePath("/data/local/tmp/bad\npath"),
        )
        assertFalse(executed)

        assertFailure(
            expected = AgentFileReferenceGateway.Error.TypeMismatch,
            result = AgentFileReferenceGateway(rootAvailable = { true }) {
                success("directory\n/data/local/tmp/project")
            }.resolveAbsolutePath("/data/local/tmp/project", AgentFileReferenceKind.File),
        )
    }

    @Test
    fun resolveAbsolutePath_shellQuotesUntrustedPath() {
        var command = ""
        val path = "/data/local/tmp/a'\$(touch pwned)"
        val gateway = AgentFileReferenceGateway(rootAvailable = { true }) { captured ->
            command = captured
            success("file\n$path")
        }

        val result = gateway.resolveAbsolutePath(path)

        assertTrue(result is AgentFileReferenceGateway.Resolution.Success)
        assertTrue(command.contains("'/data/local/tmp/a'\\''\$(touch pwned)'"))
    }

    @Test
    fun resolveDocumentUri_usesLocalPathResolvedFromMediaDocument() {
        val uri = Uri.parse(
            "content://com.android.providers.media.documents/document/image%3A24708"
        )
        val gateway = AgentFileReferenceGateway(
            rootAvailable = { true },
            resolveDocumentPath = { selectedUri ->
                assertEquals(uri, selectedUri)
                "/storage/emulated/0/Pictures/Screenshots/example.jpg"
            },
            executeRootCommand = {
                success("file\n/storage/emulated/0/Pictures/Screenshots/example.jpg")
            },
        )

        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "example.jpg",
                    absolutePath = "/storage/emulated/0/Pictures/Screenshots/example.jpg",
                    kind = AgentFileReferenceKind.File,
                )
            ),
            gateway.resolveDocumentUri(uri, AgentFileReferenceKind.File),
        )
    }

    @Test
    fun resolveDocumentUri_rejectsProviderWithoutLocalPath() {
        val gateway = AgentFileReferenceGateway(
            rootAvailable = { true },
            resolveDocumentPath = { null },
            executeRootCommand = { error("不应执行 Root 校验") },
        )

        assertFailure(
            expected = AgentFileReferenceGateway.Error.UnsupportedDocumentProvider,
            result = gateway.resolveDocumentUri(
                Uri.parse("content://cloud.example/document/remote%3A1"),
                AgentFileReferenceKind.File,
            ),
        )
    }

    private fun assertFailure(
        expected: AgentFileReferenceGateway.Error,
        result: AgentFileReferenceGateway.Resolution,
    ) {
        assertEquals(expected, (result as AgentFileReferenceGateway.Resolution.Failure).error)
    }

    private fun success(stdout: String) = BoundedRootCommandExecutor.Result(
        exitCode = 0,
        stdout = stdout,
        stderr = "",
        timedOut = false,
        truncated = false,
    )

    private fun failed(
        exitCode: Int,
        timedOut: Boolean = false,
    ) = BoundedRootCommandExecutor.Result(
        exitCode = exitCode,
        stdout = "",
        stderr = "",
        timedOut = timedOut,
        truncated = false,
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
