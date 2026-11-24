package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentFileReferenceGateway] 的纯逻辑用例（卷路径映射 + 导入上限计算）。
 *
 * 放在独立于 [AgentFileReferenceGatewayTest] 的普通 JUnit 类里，是因为后者用
 * `EtaRobolectricTestRunner`，在 arm64 上会被 `AssumptionViolatedException` 整体跳过，
 * 本地拿不到任何执行证据。这两段逻辑不依赖 Android 框架，直接在 JVM 上跑才能验证。
 */
class AgentFileReferenceGatewayImportLimitTest {

    @Test
    fun mapsNonPrimaryVolumeToStoragePath() {
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
        // primary 的语义不变，仍走传入的（或默认的）共享存储根。
        assertEquals(
            "/storage/emulated/0/Download/report.txt",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/report.txt",
            ),
        )
        assertEquals(
            "/custom/root/Download/report.txt",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/report.txt",
                "/custom/root",
            ),
        )
    }

    @Test
    fun rejectsTraversalAndControlCharactersOnNonPrimaryVolume() {
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
                "1A1B-2C3D:Download/bad\nname",
            )
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                "com.android.providers.downloads.documents",
                "1A1B-2C3D:a.bin",
            )
        )
    }

    @Test
    fun computeImportLimitScalesWithUsableSpaceInsteadOfFixedThreshold() {
        val mib = 1L * 1024 * 1024

        // 余量取 max(64 MiB, 可用空间 10%)：2 GiB 可用 → 扣 200 MiB。
        assertEquals(
            2_000L * mib - 200L * mib,
            AgentFileReferenceGateway.computeImportLimit(2_000L * mib),
        )
        // 核心回归点：原来写死的 32 MiB 不再是上限。
        assertTrue(
            "可用空间充足时必须放行超过 32 MiB 的文件，上限应随可用空间放大",
            AgentFileReferenceGateway.computeImportLimit(2_000L * mib) > 32L * mib,
        )
        // 小可用空间下 10% 余量不足 64 MiB，按 64 MiB 扣，结果不得低于下限。
        assertEquals(
            AgentFileReferenceGateway.MIN_IMPORT_BYTES,
            AgentFileReferenceGateway.computeImportLimit(40L * mib),
        )
        assertEquals(
            AgentFileReferenceGateway.MIN_IMPORT_BYTES,
            AgentFileReferenceGateway.computeImportLimit(0L),
        )
        // 硬上限防失控：可用空间被误读成超大值时封顶 4 GiB。
        assertEquals(
            AgentFileReferenceGateway.MAX_IMPORT_LIMIT_BYTES,
            AgentFileReferenceGateway.computeImportLimit(64L * 1024L * mib),
        )
    }
}
