package io.github.mangi.eta.agent.git

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.OneShotShellResult
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ETA 的 git 执行引擎：包装 [ShellProcessSupervisor] + [runOneShotShell]，
 * 把 `git` 命令送进 Debian rootfs 执行（rootfs 已带 git，实测 /usr/bin/git 存在）。
 *
 * 与 aicode 的 CommandEngine 差异：
 * - 无 cwd 参数——cwd 靠 `cd <linuxCwd> && ` 拼进命令串（runOneShotShell 原语不收 cwd）；
 * - 输出上限独立设为 16MiB（MAX_OUTPUT_BYTES），不经 AI 工具链的限幅（git porcelain/log/diff 数据流里
 *   混入截断占位符会被 UI 解析成伪行）；
 * - chroot（identity=root）读写全通；proot（identity=user）写操作在执行前抛
 *   [GitProotUnsupportedException] 拦截——proot 既读不到 credential.helper=store 的凭据
 *   （写死在 chroot 的 /root），也看不到工作区绑定根（proot 只绑
 *   App 私有 workspace），放行只会得到含混的 fatal 输出。
 */
internal class EtaGitEngine(private val context: Context) {

    /** 引擎单次命令结果：退出码 + 合并解码后的 stdout/stderr 文本 + 两侧截断标记。 */
    data class EngineResult(
        val exitCode: Int,
        val output: String,
        val outputTruncated: Boolean,
        val stderrTruncated: Boolean,
    )

    /** supervisor 惰性创建：UI 层自建 supervisor 先例见 WorkspaceFileStore.workspaceBackend()。 */
    private val supervisor by lazy { ShellProcessSupervisor() }

    /** Debian rootfs 绝对路径；backend 判定与执行都依赖它，构造时即按用户设置解析。 */
    private val rootfsPath: String by lazy {
        LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.DEBIAN).absolutePath
    }

    /** 当前 Linux 执行后端；决定身份（root/user）与路径翻译分支，读设置缓存，不落盘。 */
    val backend: LinuxExecutionBackend
        get() = LinuxEnvironmentPaths.backendOf(rootfsPath)

    /** Debian 环境是否就绪（.eta-environment-ready 标记存在）；未就绪时所有 git 命令都会失败，须先提示用户。 */
    val ready: Boolean
        get() = LinuxEnvironmentPaths.rootfsReady(rootfsPath)

    /** proot 后端标记；写操作拦截与路径翻译分支都要按它分叉。 */
    val proot: Boolean
        get() = backend == LinuxExecutionBackend.PROOT

    /** 引擎可用性提示：环境未就绪或缺 Root 时返回引导文案；就绪返回 null。 */
    fun notReadyHint(): String? = when {
        !ready -> "Linux 终端环境未就绪，请先在终端页完成 Debian 环境初始化"
        !proot && !TerminalRuntime.rootAvailable -> "chroot 后端需要 Root 权限，请在设置中授权或切换后端"
        else -> null
    }

    /**
     * 只读通道：执行 `git -c core.quotepath=false <args>`，返回合并后的 stdout+stderr 文本。
     * 不判退出码——只读命令（status/branches/log）靠输出解析、容错，git 非零退出码不应让 UI 误判；
     * 解析得空罢了。输出被限幅截断时抛 [GitOutputTooLargeException]：半截数据会被解析器当真。
     */
    suspend fun git(linuxCwd: String?, vararg args: String): String {
        val result = execute(buildGitCommand(args.toList(), linuxCwd), DEFAULT_TIMEOUT_SECONDS)
        if (result.outputTruncated || result.stderrTruncated) throw GitOutputTooLargeException()
        return result.output
    }

    /**
     * 写通道：与 [git] 同路，但按退出码判成败——非零抛 [GitCommandFailureException] 携带输出，
     * 让 UI 如实显示「失败 + 原因」而非误报成功（未配置署名提交、推送被拒等场景）。
     * proot 后端直接抛 [GitProotUnsupportedException]（拦截必须在执行前，理由见类注释）。
     * clone 这类长命令用 [timeoutSeconds] 放宽超时；默认 [DEFAULT_TIMEOUT_SECONDS] 覆盖常规写操作。
     */
    suspend fun gitChecked(
        linuxCwd: String?,
        args: List<String>,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): String {
        if (proot) throw GitProotUnsupportedException()
        val result = execute(buildGitCommand(args, linuxCwd), timeoutSeconds)
        if (result.outputTruncated || result.stderrTruncated) throw GitOutputTooLargeException()
        if (result.exitCode == 0) return result.output
        throw GitCommandFailureException(result.output.ifBlank { "git 退出码 ${result.exitCode}" })
    }

    /**
     * 任意 shell 命令原语（仓库发现 find、.gitignore 追加 printf 等 git 之外的辅助命令）。
     * 返回原始 [EngineResult] 由调用方按退出码自行解释；proot 下同样拦截写语义不现实——
     * 该通道只允许由仓库层用于只读辅助，写语义辅助命令在仓库层先做 proot 检查。
     */
    suspend fun raw(command: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): EngineResult =
        execute(command, timeoutSeconds)

    /**
     * Android 绑定根路径 → Linux 内路径，按 backend 分支翻译：
     * - chroot：绑定根 = [TerminalRuntime.ROOT_WORKSPACE_PATH]，经 bind mount 对应 /workspace，直接复用
     *   [SharedFolderMounts.workspaceLinkTarget] 的整段前缀映射（不会把工作区根的同前缀兄弟目录误判进来）；
     * - proot：guest 的 /workspace 绑定的是 App 私有 workspace（TerminalRuntime.userWorkspacePath），
     *   工作区绑定根（projects 目录）根本不可见——翻译仍按同一函数走私有 workspace 分支，
     *   结果是不存在的路径，git 会给出明确 fatal，UI 显示未就绪态（写操作已被前置拦截）。
     */
    fun toLinuxPath(androidPath: String): String {
        val workspaceRoot = if (proot) TerminalRuntime.userWorkspacePath else TerminalRuntime.ROOT_WORKSPACE_PATH
        return SharedFolderMounts.workspaceLinkTarget(androidPath, workspaceRoot)
            ?: throw GitCommandFailureException("路径不在 Linux 工作区映射范围内：$androidPath")
    }

    /**
     * Linux 内路径 → Android 路径（仓库发现用）：[toLinuxPath] 的逆映射，纯字符串前缀替换。
     * 两个方向必须共用同一对根定义，否则发现列表里的路径无法回填到 currentRepoPath。
     */
    fun toAndroidPath(linuxPath: String): String {
        val workspaceRoot = if (proot) TerminalRuntime.userWorkspacePath else TerminalRuntime.ROOT_WORKSPACE_PATH
        val normalized = linuxPath.trimEnd('/')
        if (normalized == SharedFolderMounts.LINUX_WORKSPACE_ROOT) return workspaceRoot
        if (!normalized.startsWith(SharedFolderMounts.LINUX_WORKSPACE_ROOT + "/")) {
            throw GitCommandFailureException("路径不在 Linux 工作区映射范围内：$linuxPath")
        }
        return "$workspaceRoot/${normalized.removePrefix(SharedFolderMounts.LINUX_WORKSPACE_ROOT + "/")}"
    }

    /**
     * 拼成交给 `/bin/sh -c` 的单条命令：`cd <linuxCwd> && git -c core.quotepath=false <逐参转义>`。
     * 每条参数经 [shellQuote] 单引号转义，格式串（带 `|`、`%(...)`）、含空格路径、提交消息均安全传递。
     * `core.quotepath=false`：git 默认把非 ASCII 路径转成八进制转义（中文 → \344\270\255...），
     * 关掉后直接输出 UTF-8 原文，中文目录/文件名不再乱码。
     */
    private fun buildGitCommand(args: List<String>, linuxCwd: String?): String = buildString {
        if (linuxCwd != null) {
            append("cd ")
            append(shellQuote(linuxCwd))
            append(" && ")
        }
        append("git -c core.quotepath=false")
        args.forEach {
            append(' ')
            append(shellQuote(it))
        }
    }

    /** 真正执行：送进 Debian rootfs；阻塞调用放 IO 线程，进程树由 supervisor 统一回收。 */
    private suspend fun execute(command: String, timeoutSeconds: Long): EngineResult =
        withContext(Dispatchers.IO) {
            notReadyHint()?.let { throw GitCommandFailureException(it) }
            val result: OneShotShellResult = runOneShotShell(
                processSupervisor = supervisor,
                identity = if (proot) "user" else "root",
                command = command,
                timeoutSeconds = timeoutSeconds,
                environment = TerminalEnvironment.DEBIAN,
                linuxRootfsPath = rootfsPath,
                linuxSharedMounts = SharedFolderMounts.current(),
                maxOutputBytes = MAX_OUTPUT_BYTES,
            )
            EngineResult(
                exitCode = result.exitCode,
                output = mergeOutput(result),
                outputTruncated = result.outputTruncated,
                stderrTruncated = result.stderrTruncated,
            )
        }

    /**
     * stdout 与 stderr 按序合并成单文本：stdout 在前、stderr 换行接后。
     * git 的失败信息走 stderr、数据走 stdout，解析器（porcelain/log）按行容错，
     * 合并文本与 aicode 的 runCommandSync(mergeStderr=true) 行为对齐。
     */
    private fun mergeOutput(result: OneShotShellResult): String {
        val out = result.output.toString(Charsets.UTF_8)
        val err = result.stderr.toString(Charsets.UTF_8)
        if (err.isBlank()) return out
        return if (out.isBlank()) err else "$out\n$err"
    }

    companion object {
        /** 常规 git 命令超时（秒）；commit/checkout 等写操作在该时间内足够。 */
        const val DEFAULT_TIMEOUT_SECONDS = 60L

        /** clone 超时（秒）：大仓库走慢速网络时 10 分钟是实际下限，超时由引擎回收进程树。 */
        const val CLONE_TIMEOUT_SECONDS = 600L
        /** 单条命令输出字节上限：porcelain/log 输出很小，diff 大文件场景靠它兜底防 OOM；超限置 outputTruncated，通道层抛 GitOutputTooLargeException。 */
        const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024

        /**
         * 对单个 shell 参数做单引号转义（仓库层拼辅助命令 cat/find 等也复用它）：含「安全字符」之外的字符时整体包单引号，
         * 内嵌单引号用 `'\''` 关闭-转义-重开。注意 `|` 是 shell 管道符，不可列入安全集。
         */
        internal fun shellQuote(arg: String): String {
            if (arg.isEmpty()) return "''"
            if (arg.all { it.isLetterOrDigit() || it in "_.@/:=+,%-" }) return arg
            return "'" + arg.replace("'", "'\\''") + "'"
        }
    }
}
