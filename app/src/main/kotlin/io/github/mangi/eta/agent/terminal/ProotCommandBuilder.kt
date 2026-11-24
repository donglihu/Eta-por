package io.github.mangi.eta.agent.terminal

import java.io.File

/** guest 的 root 只是 UID 映射，宿主进程始终保留 App UID。 */
internal object ProotCommandBuilder {
    fun available(): Boolean = TerminalRuntime.nativeExecutable("libproot_exec.so") != null &&
        TerminalRuntime.nativeExecutable("libproot_loader.so") != null

    fun payload(
        rootfsPath: String,
        command: String?,
        sharedMounts: List<SharedFolderMount> = emptyList(),
        termType: String = "dumb",
        nativeDirectory: File? = TerminalRuntime.nativeLibraryDir,
        workspace: String = TerminalRuntime.userWorkspacePath,
        tempDirectory: File = TerminalRuntime.temporaryDirectory,
        publicStorageGranted: Boolean = TerminalRuntime.publicStorageGranted,
        canReadProbe: (String) -> Boolean = { File(it).canRead() },
    ): String {
        val native = nativeDirectory ?: return "echo ETA_PROOT_UNAVAILABLE >&2; exit 127"
        val proot = File(native, "libproot_exec.so").absolutePath
        val loader = File(native, "libproot_loader.so").absolutePath
        val args = mutableListOf(proot, "--root-id", "--link2symlink", "--kill-on-exit", "--sysvipc", "-r", rootfsPath, "-w", "/workspace")
        fun bind(source: String, destination: String = source) {
            require(':' !in source && ':' !in destination) { "共享路径不能包含冒号" }
            args += listOf("-b", "$source:$destination")
        }
        listOf("/dev", "/proc", "/sys").filter { File(it).isDirectory }.forEach { bind(it) }
        bind(workspace, SharedFolderMounts.LINUX_WORKSPACE_ROOT)
        bind(tempDirectory.absolutePath, "/dev/shm")
        if (publicStorageGranted && File("/storage/emulated/0").canRead()) bind("/storage/emulated/0")
        // workspace 内的源不能 bind 到自己的子目录（mount --bind /a /a/b 自嵌套，proot 的嵌套 bind 同样不可靠），
        // 改成在挂载点建符号链接指向 /workspace（根）或 /workspace/<相对路径>：
        // 这类源就在 App 自己的 workspace 里，canRead 之类的 App UID 过滤不该把它静默丢掉。
        val hostMountsRoot = File(workspace, SharedFolderMounts.MOUNTS_DIR_NAME).absolutePath
        val workspaceLinks = mutableListOf<String>()
        sharedMounts.filter { SharedFolderMounts.validateName(it.name, emptyList()) == null }
            .forEach { mount ->
                val linkTarget = SharedFolderMounts.workspaceLinkTarget(mount.sourcePath, workspace)
                if (linkTarget != null && SharedFolderMounts.linkTargetLoopsBack(linkTarget)) {
                    // 源在 mounts 目录内部：链接要建在 mounts 里却指回 mounts 自身，遍历会绕圈。
                    workspaceLinks += "echo ETA_SHARED_SKIP ${shellQuote(mount.name)} " +
                        "${shellQuote(mount.sourcePath)} reason=loop_back >&2;"
                    return@forEach
                }
                if (linkTarget != null) {
                    workspaceLinks += "ln -sfn ${shellQuote(linkTarget)} " +
                        "${shellQuote(File(hostMountsRoot, mount.name).absolutePath)} 2>/dev/null" +
                        " || echo ETA_SHARED_LINK_FAILED >&2;"
                    return@forEach
                }
                // 不在 workspace 内的源走 bind；无论何种原因失败都必须可见，禁止静默丢弃。
                // proot 的 -b bind 由发起 proot 的宿主 shell 执行：user proot 会话的宿主 shell 是 App UID
                // （ShellProcessSupervisor:identity=="user" 时 ProcessBuilder("sh","-c",launcher)，root proot 被拒），
                // App UID 对 /data/local/tmp/eta 这类 shell_data_file 不可读，bind 也不可行——只能报错。
                val reason = when {
                    !permittedSharedSource(mount.sourcePath, publicStorageGranted) -> "forbidden"
                    // canRead 优先：存在但不可读与不存在（App UID 视角同样不可读）都归为 not_readable，
                    // 与设备上 /data/local/tmp/eta 对 App UID 的实际表现一致。
                    !canReadProbe(mount.sourcePath) -> "not_readable"
                    !File(mount.sourcePath).isDirectory -> "not_directory"
                    else -> null
                }
                if (reason != null) {
                    workspaceLinks += "echo ETA_SHARED_DROP ${shellQuote(mount.name)} ${shellQuote(mount.sourcePath)} reason=$reason >&2;"
                    return@forEach
                }
                bind(mount.sourcePath, "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}")
            }
        // 链接在会话建立时创建；proot 没有 mount namespace，符号链接落在 App 自己的 workspace 里。
        val linkCommands = if (workspaceLinks.isEmpty()) {
            ""
        } else {
            "mkdir -p ${shellQuote(hostMountsRoot)} || exit 125; " + workspaceLinks.joinToString(" ")
        }
        args += listOf("/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/sh", "TERM=$termType",
            "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "TMPDIR=/tmp", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        if (command != null) args += "NO_COLOR=1"
        args += "/bin/sh"
        if (command != null) args += listOf("-lc", command)
        return "[ -x ${shellQuote(proot)} ] && [ -x ${shellQuote(loader)} ] || { echo ETA_PROOT_UNAVAILABLE >&2; exit 127; }; " +
            "mkdir -p ${shellQuote(workspace)} ${shellQuote(tempDirectory.absolutePath)} || exit 125; " +
            linkCommands +
            "export PROOT_LOADER=${shellQuote(loader)} PROOT_TMP_DIR=${shellQuote(tempDirectory.absolutePath)}; " +
            "unset LD_PRELOAD; " + args.joinToString(" ", transform = ::shellQuote)
    }
    internal fun permittedSharedSource(path: String, publicStorageGranted: Boolean): Boolean {
        val canonical = File(path).canonicalPath
        val publicPath = canonical == "/sdcard" || canonical.startsWith("/sdcard/") ||
            canonical == "/storage" || canonical.startsWith("/storage/") ||
            canonical == "/mnt/media_rw" || canonical.startsWith("/mnt/media_rw/") ||
            canonical.startsWith("/mnt/runtime/") || canonical.startsWith("/mnt/user/")
        return !publicPath || publicStorageGranted
    }

}
