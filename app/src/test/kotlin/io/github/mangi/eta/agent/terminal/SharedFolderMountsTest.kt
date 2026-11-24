package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFolderMountsTest {

    @Test
    fun normalizeSourcePathRejectsNonAbsoluteAndControlChars() {
        assertNull(SharedFolderMounts.normalizeSourcePath(""))
        assertNull(SharedFolderMounts.normalizeSourcePath("sdcard/Download"))
        assertNull(SharedFolderMounts.normalizeSourcePath("/sdcard/evil\npath"))
        assertNull(SharedFolderMounts.normalizeSourcePath("/"))
        assertNull(SharedFolderMounts.normalizeSourcePath("/../escape"))
    }

    @Test
    fun normalizeSourcePathCollapsesDotsAndDuplicateSlashes() {
        assertEquals("/sdcard/Download", SharedFolderMounts.normalizeSourcePath("/sdcard//Download/"))
        assertEquals("/sdcard/Download", SharedFolderMounts.normalizeSourcePath("/sdcard/./Download"))
        assertEquals("/sdcard", SharedFolderMounts.normalizeSourcePath("/sdcard/Download/.."))
    }

    @Test
    fun validateSourceRejectsSystemRootsAndSubtrees() {
        val existing = emptyList<SharedFolderMount>()
        listOf(
            "/proc",
            "/proc/1",
            "/sys",
            "/sys/kernel",
            "/dev",
            "/dev/block",
            "/system",
            "/system/vendor",
            "/vendor",
            "/vendor/etc",
            "/apex",
            "/apex/com.android.tzdata",
            "/product",
            "/product/media",
        ).forEach { path ->
            assertEquals(
                "应拒绝 $path",
                SharedFolderMounts.SourceError.FORBIDDEN_ROOT,
                SharedFolderMounts.validateSource(path, existing),
            )
        }
        assertEquals(
            SharedFolderMounts.SourceError.FORBIDDEN_ROOT,
            SharedFolderMounts.validateSource(
                "/data/user/0/app/files/terminal/alpine/rootfs",
                existing,
                extraForbiddenRoots = listOf("/data/user/0/app/files/terminal/alpine/rootfs"),
            ),
        )
        assertEquals(
            SharedFolderMounts.SourceError.FORBIDDEN_ROOT,
            SharedFolderMounts.validateSource(
                "/data/user/0/app/files/terminal/alpine/rootfs/etc",
                existing,
                extraForbiddenRoots = listOf("/data/user/0/app/files/terminal/alpine/rootfs/"),
            ),
        )
        assertNull(SharedFolderMounts.validateSource("/sdcard/Download", existing))
    }

    /** workspace 根与其子目录以前被当成禁区，现在放行并走符号链接。 */
    @Test
    fun validateSourceAllowsWorkspaceRootAndSubtrees() {
        val existing = emptyList<SharedFolderMount>()
        assertNull(SharedFolderMounts.validateSource(SharedFolderMounts.HOST_WORKSPACE_ROOT, existing))
        assertNull(SharedFolderMounts.validateSource("${SharedFolderMounts.HOST_WORKSPACE_ROOT}/projects", existing))
        assertNull(
            SharedFolderMounts.validateSource(
                "${SharedFolderMounts.HOST_WORKSPACE_ROOT}/projects/ETA/Eta/",
                existing,
            ),
        )
        // 形近目录不算 workspace 内，也不该被误拒。
        assertNull(SharedFolderMounts.validateSource("${SharedFolderMounts.HOST_WORKSPACE_ROOT}2", existing))
    }

    @Test
    fun validateSourceRejectsInvalidPaths() {
        val existing = emptyList<SharedFolderMount>()
        listOf(
            "",
            "   ",
            "relative/path",
            "./relative",
            "/..",
            "/a/../../b",
            "/sdcard/evil\npath",
            "/sdcard/evil\rpath",
        ).forEach { path ->
            assertEquals(
                "应判非法 $path",
                SharedFolderMounts.SourceError.INVALID_PATH,
                SharedFolderMounts.validateSource(path, existing),
            )
        }
    }

    @Test
    fun validateSourceRejectsDuplicateNormalizedPaths() {
        val existing = listOf(SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download"))
        assertEquals(
            SharedFolderMounts.SourceError.DUPLICATE,
            SharedFolderMounts.validateSource("/sdcard//Download/", existing),
        )
    }

    /** workspace 子目录的重复检测与普通源一致。 */
    @Test
    fun validateSourceRejectsDuplicateWorkspaceSubtree() {
        val existing = listOf(
            SharedFolderMount(
                name = "proj",
                sourcePath = "${SharedFolderMounts.HOST_WORKSPACE_ROOT}/projects",
            ),
        )
        assertEquals(
            SharedFolderMounts.SourceError.DUPLICATE,
            SharedFolderMounts.validateSource("${SharedFolderMounts.HOST_WORKSPACE_ROOT}//projects/", existing),
        )
        assertNull(
            SharedFolderMounts.validateSource("${SharedFolderMounts.HOST_WORKSPACE_ROOT}/logs", existing),
        )
    }

    @Test
    fun workspaceLinkTargetMapsWorkspaceIntoGuestPaths() {
        val root = SharedFolderMounts.HOST_WORKSPACE_ROOT
        assertEquals("/workspace", SharedFolderMounts.workspaceLinkTarget(root))
        assertEquals("/workspace", SharedFolderMounts.workspaceLinkTarget("$root/"))
        assertEquals("/workspace/projects", SharedFolderMounts.workspaceLinkTarget("$root/projects"))
        assertEquals(
            "/workspace/projects/ETA/Eta",
            SharedFolderMounts.workspaceLinkTarget("$root//projects/./ETA/Eta/"),
        )
        // 前缀必须按整段比较：/data/local/tmp/eta2 不是 workspace 内。
        assertNull(SharedFolderMounts.workspaceLinkTarget("${root}2"))
        assertNull(SharedFolderMounts.workspaceLinkTarget("${root}2/projects"))
        assertNull(SharedFolderMounts.workspaceLinkTarget("/sdcard/Download"))
    }

    /** mounts 目录内部作共享源会形成回绕：链接建在 mounts 里却指回 mounts 自身。 */
    @Test
    fun linkTargetLoopsBackDetectsTargetsInsideMountsRoot() {
        val mountsRoot = SharedFolderMounts.LINUX_MOUNTS_ROOT
        assertTrue(SharedFolderMounts.linkTargetLoopsBack(mountsRoot))
        assertTrue(SharedFolderMounts.linkTargetLoopsBack("$mountsRoot/MT2"))
        assertTrue(SharedFolderMounts.linkTargetLoopsBack("$mountsRoot/MT2/产物"))
        // mounts 之外的 workspace 路径不受影响。
        assertFalse(SharedFolderMounts.linkTargetLoopsBack("/workspace"))
        assertFalse(SharedFolderMounts.linkTargetLoopsBack("/workspace/projects"))
        assertFalse(SharedFolderMounts.linkTargetLoopsBack("/workspace/mounts2"))
        assertFalse(SharedFolderMounts.linkTargetLoopsBack(null))
    }

    @Test
    fun validateNameRejectsUnsafeNamesAndDuplicates() {
        val existing = listOf(SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download"))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("has space", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("a/b", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("中文名", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName(".", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("..", existing))
        assertEquals(
            SharedFolderMounts.NameError.INVALID,
            SharedFolderMounts.validateName("a".repeat(49), existing),
        )
        assertEquals(SharedFolderMounts.NameError.DUPLICATE, SharedFolderMounts.validateName("dl", existing))
        assertNull(SharedFolderMounts.validateName("downloads-2024_v2", existing))
    }

    @Test
    fun defaultNameFallsBackWhenBasenameHasNoSafeChars() {
        assertEquals("Download", SharedFolderMounts.defaultName("/sdcard/Download"))
        assertEquals("share", SharedFolderMounts.defaultName("/sdcard/下载"))
        assertEquals("a.b_c-d", SharedFolderMounts.defaultName("/x/a.b_c-d"))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val mounts = listOf(
            SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download"),
            SharedFolderMount(name = "app", sourcePath = "/data/data/com.example.app/files"),
        )
        val decoded = SharedFolderMounts.decode(SharedFolderMounts.encode(mounts))
        assertEquals(mounts, decoded)
    }

    @Test
    fun decodeSkipsMalformedEntriesAndCorruptJson() {
        assertEquals(emptyList<SharedFolderMount>(), SharedFolderMounts.decode("not-json"))
        assertEquals(emptyList<SharedFolderMount>(), SharedFolderMounts.decode(""))
        val mixed = """[{"name":"ok","source":"/sdcard/Download"},"broken",{"name":"bad name","source":"/sdcard/x"}]"""
        assertEquals(
            listOf(SharedFolderMount(name = "ok", sourcePath = "/sdcard/Download")),
            SharedFolderMounts.decode(mixed),
        )
    }

    @Test
    fun decodeCapsAtMaxMounts() {
        val mounts = (1..SharedFolderMounts.MAX_MOUNTS + 4).map {
            SharedFolderMount(name = "m$it", sourcePath = "/sdcard/d$it")
        }
        assertEquals(SharedFolderMounts.MAX_MOUNTS, SharedFolderMounts.decode(SharedFolderMounts.encode(mounts)).size)
    }

    @Test
    fun currentWithoutPreferencesReturnsEmptyAndSaveFails() {
        assertEquals(emptyList<SharedFolderMount>(), SharedFolderMounts.current(preferences = null))
        assertFalse(SharedFolderMounts.save(emptyList(), preferences = null))
    }

    @Test
    fun linuxPayloadMountsSharedFoldersIntoWorkspace() {
        val supervisor = ShellProcessSupervisor()
        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/app/files/terminal/alpine/rootfs",
            command = "ls",
            sharedMounts = listOf(
                SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download"),
                SharedFolderMount(name = "app", sourcePath = "/data/data/com.example.app/files"),
                // 名字非法的条目必须被跳过，不能拼进脚本。
                SharedFolderMount(name = "bad name", sourcePath = "/sdcard/Other"),
            ),
        )

        // 整个 inner script 被 shellQuote 包裹，挂载源路径两侧的单引号会被转义；按无引号片段断言。
        assertTrue(payload.contains("/sdcard/Download"))
        assertTrue(payload.contains("/data/data/com.example.app/files"))
        assertFalse(payload.contains("bad name"))
        assertTrue(payload.contains("\$eta_rootfs/workspace/mounts/dl\" bind"))
        assertTrue(payload.contains("\$eta_rootfs/workspace/mounts/app\" bind"))
        // 共享挂载必须在 workspace bind 之后执行，目标路径才落在已挂载的 workspace 上。
        val workspaceBind = payload.indexOf(
            "eta_mount_required ${SharedFolderMounts.HOST_WORKSPACE_ROOT}",
        )
        val sharedMount = payload.indexOf("workspace/mounts/dl")
        assertTrue(workspaceBind >= 0 && sharedMount > workspaceBind)
        // 挂载点目录先挂 tmpfs，符号链接与 bind 目标都不落 Android 侧。
        assertTrue(payload.contains("eta_mounts_tmpfs"))
    }

    /** workspace 内的源不能 bind 到自己的子目录，必须改走符号链接。 */
    @Test
    fun linuxPayloadLinksWorkspaceSourcesInsteadOfBind() {
        val supervisor = ShellProcessSupervisor()
        val root = SharedFolderMounts.HOST_WORKSPACE_ROOT
        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/app/files/terminal/alpine/rootfs",
            command = "ls",
            sharedMounts = listOf(
                SharedFolderMount(name = "ws", sourcePath = root),
                SharedFolderMount(name = "proj", sourcePath = "$root/projects"),
                SharedFolderMount(name = "all", sourcePath = "$root/projects/ETA/Eta"),
            ),
        )

        assertTrue(payload.contains("eta_link_shared"))
        assertTrue(payload.contains("/workspace/projects"))
        assertTrue(payload.contains("/workspace/projects/ETA/Eta"))
        assertFalse(payload.contains("workspace/mounts/ws\" bind"))
        assertFalse(payload.contains("workspace/mounts/proj\" bind"))
        assertFalse(payload.contains("workspace/mounts/all\" bind"))
        // 三个 workspace 内源全部走链接调用（定义行是 eta_link_shared() {，不匹配）。
        assertEquals(3, Regex("eta_link_shared '").findAll(payload).count())
    }

    /** mounts 内的源必须显式跳过并报错，不能生成回绕链接。 */
    @Test
    fun linuxPayloadSkipsLoopBackSharedSource() {
        val supervisor = ShellProcessSupervisor()
        val mountsRoot = SharedFolderMounts.LINUX_MOUNTS_ROOT
        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/app/files/terminal/alpine/rootfs",
            command = "ls",
            sharedMounts = listOf(
                SharedFolderMount(name = "host", sourcePath = SharedFolderMounts.ANDROID_MOUNTS_ROOT),
                SharedFolderMount(name = "mt2", sourcePath = "${SharedFolderMounts.ANDROID_MOUNTS_ROOT}/MT2"),
            ),
        )
        assertTrue(payload.contains("reason=loop_back"))
        assertFalse(payload.contains("eta_link_shared 'host'"))
        assertFalse(payload.contains("eta_link_shared 'mt2'"))
        // 未回绕的 workspace 路径仍正常走链接。
        assertTrue(payload.contains("eta_mounts_restore"))
    }

    /** 挂 tmpfs 后必须把宿主 mounts 内容回填，否则 Android 侧共享产物在 Linux 侧不可见。 */
    @Test
    fun linuxPayloadRestoresHostMountsAfterTmpfs() {
        val supervisor = ShellProcessSupervisor()
        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/app/files/terminal/alpine/rootfs",
            command = "ls",
            sharedMounts = listOf(SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download")),
        )
        assertTrue(payload.contains("eta_mounts_restore"))
        assertTrue(payload.contains(SharedFolderMounts.ANDROID_MOUNTS_ROOT))
        // 回填必须在 tmpfs 挂载之后，才能把内容挂进 tmpfs 视图。
        val tmpfsIndex = payload.indexOf("eta_mounts_tmpfs \"")
        val restoreCallIndex = payload.lastIndexOf("eta_mounts_restore")
        assertTrue(tmpfsIndex >= 0 && restoreCallIndex > tmpfsIndex)
    }

    @Test
    fun linuxPayloadWithoutSharedMountsHasNoMountsBlock() {
        val supervisor = ShellProcessSupervisor()
        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/app/files/terminal/alpine/rootfs",
            command = "ls",
        )
        assertFalse(payload.contains("workspace/mounts"))
        assertFalse(payload.contains("eta_mounts_tmpfs"))
    }

    /** proot 没有 mount namespace：workspace 内的源同样改走符号链接，且不被 canRead 静默丢掉。 */
    @Test
    fun prootPayloadLinksWorkspaceSources() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "eta-proot-workspace").absolutePath
        val payload = ProotCommandBuilder.payload(
            rootfsPath = "/data/user/0/app/files/terminal-user/proot/alpine/rootfs",
            command = "ls",
            sharedMounts = listOf(
                SharedFolderMount(name = "proj", sourcePath = "$workspace/projects"),
                SharedFolderMount(name = "ws", sourcePath = workspace),
            ),
            nativeDirectory = File("/data/app/io.github.mangi.eta/lib/arm64"),
            workspace = workspace,
            tempDirectory = File("/data/user/0/app/cache/terminal/proot"),
            publicStorageGranted = false,
        )

        assertTrue(payload.contains("ln -sfn"))
        assertTrue(payload.contains("/workspace/projects"))
        assertTrue(payload.contains("'$workspace/mounts/proj'"))
        assertTrue(payload.contains("'$workspace/mounts/ws'"))
        // 链接替代 bind：不能同时出现挂到 mounts 下的 -b 参数。
        assertFalse(payload.contains("/workspace/mounts/proj:"))
        assertFalse(payload.contains("/workspace/mounts/ws:"))
    }

    @Test
    fun prootPayloadReportsUnreadableSharedSourceInsteadOfSilentSkip() {
        // 镜像生产形态：user proot 会话 workspace 是 App 私有目录，不含 /data/local/tmp/eta；
        // App UID 对该路径不可读，必须产生 ETA_SHARED_DROP 可见标记，不得静默跳过。
        val workspace = File(System.getProperty("java.io.tmpdir"), "eta-proot-workspace").absolutePath
        val payload = ProotCommandBuilder.payload(
            rootfsPath = "/data/user/0/app/files/terminal-user/proot/alpine/rootfs",
            command = "ls",
            sharedMounts = listOf(
                SharedFolderMount(name = "eta", sourcePath = "/data/local/tmp/eta"),
            ),
            nativeDirectory = File("/data/app/io.github.mangi.eta/lib/arm64"),
            workspace = workspace,
            tempDirectory = File("/data/user/0/app/cache/terminal/proot"),
            publicStorageGranted = false,
            // JVM 测试环境可能以 root 运行、能读任意路径；显式注入不可读，镜像设备上 App UID 的表现。
            canReadProbe = { false },
        )

        assertTrue(payload.contains("ETA_SHARED_DROP 'eta' '/data/local/tmp/eta' reason=not_readable"))
        // 不得出现指向该源的 bind。
        assertFalse(payload.contains("-b '/data/local/tmp/eta:"))
    }

    @Test
    fun prootPayloadWithoutSharedMountsHasNoLinkCommands() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "eta-proot-workspace").absolutePath
        val payload = ProotCommandBuilder.payload(
            rootfsPath = "/data/user/0/app/files/terminal-user/proot/alpine/rootfs",
            command = "ls",
            nativeDirectory = File("/data/app/io.github.mangi.eta/lib/arm64"),
            workspace = workspace,
            tempDirectory = File("/data/user/0/app/cache/terminal/proot"),
            publicStorageGranted = false,
        )
        assertFalse(payload.contains("ln -sfn"))
    }
}
