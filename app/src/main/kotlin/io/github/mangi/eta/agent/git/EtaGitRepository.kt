package io.github.mangi.eta.agent.git

import io.github.mangi.eta.agent.git.model.GitBranch
import io.github.mangi.eta.agent.git.model.GitCommit
import io.github.mangi.eta.agent.git.model.GitFileChange
import io.github.mangi.eta.agent.git.model.GitGraph
import io.github.mangi.eta.agent.git.model.GitGraphRef
import io.github.mangi.eta.agent.git.model.GraphCommit
import io.github.mangi.eta.agent.git.model.GitStash
import io.github.mangi.eta.agent.git.model.GitStatus
import io.github.mangi.eta.agent.git.model.GitTag
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 在 Debian rootfs 内执行 git 命令并解析输出（自 aicode feature/git/domain/GitRepository.kt 移植，
 * 2026-11-28，去 Hilt：引擎改为构造注入的 [EtaGitEngine]，仓库选择与绑定根由 ETA 工作区结构决定）。
 *
 * 与 aicode 版的关键差异：
 * - 绑定根 = [TerminalRuntime.ROOT_WORKSPACE_PATH] 下的 projects（ETA 工作区的正式项目域），不是 aicode 的单工作区根；
 *   `repoCwd()` 对选中路径做 realpath 校验（canonicalFile + 前缀比较），拒绝 `..` 与符号链接逃逸，
 *   否则一次「手动指定仓库」就能把 git 命令的执行目录指到工作区外，读写都会越界；
 * - `discoverRepos()` 用 Linux `find` 在绑定根内扫 .git 目录层深 ≤4（仓库最深三层，.git 深一层；
 *   prune node_modules/tmp/archive/build——注意 .git 不能进 prune 名单，原因见 DISCOVERY_SKIP_DIRS，
 *   上限 50）——保证 projects/ETA/Eta、projects/ETA/Eta-dispatch（深度 2）能被发现；
 * - clone 的目标是绑定根下**新建子目录**（先校验不存在或为空），不是 aicode 的 `git clone url .`：
 *   ETA 的绑定根是长期项目域，直接克隆进根目录会污染所有项目；
 * - init 用 `git init -b main`，旧 git 不支持 `-b` 时降级 `git init && git symbolic-ref HEAD refs/heads/main`。
 *
 * 命令经 [EtaGitEngine.shellQuote] 逐参数转义后拼成单条 `git ...` 字符串交给 `/bin/sh -c`，
 * 故格式串里的 `|`、`%(...)`、含空格的路径都能安全传递。
 */
internal class EtaGitRepository(private val engine: EtaGitEngine) {

    /** 当前选中的 git 仓库 Android 路径；null 表示未指定（用绑定根）。 */
    @Volatile
    var currentRepoPath: String? = null
        private set

    companion object {
        /**
         * git 功能的绑定根：ETA 工作区的正式项目域。所有仓库路径必须落在它里面（realpath 校验），
         * 这是 git 命令执行目录的唯一边界；取 canonical 前缀比较，`..` 与符号链接逃逸都会被拒。
         */
        val BIND_ROOT: File = File(TerminalRuntime.ROOT_WORKSPACE_PATH, "projects")

        /** 提交拓扑图每页加载条数。首批与每次「加载更多」都取这么多条，超过的需滚到底再拉。 */
        const val GRAPH_PAGE_SIZE = 100

        /** 仓库发现的引擎层超时（秒）：让 supervisor 在 10s 杀进程树，不拖满引擎默认 60s。 */
        const val DISCOVERY_TIMEOUT_SECONDS = 10L

        /** ShellProcessSupervisor 一次性执行超时的退出码约定：waitFor 超时杀进程树后返回 -2。 */
        const val ENGINE_TIMEOUT_EXIT_CODE = -2

        /** diff 允许读取的单侧文件字节上限：超过则不拉内容，直接降级为「文件过大」。 */
        const val MAX_DIFF_FILE_BYTES = 2L * 1024 * 1024

        /** 仓库发现上限：绑定根下目录很多时防 find 爆列表；超出部分等用户手动指定。 */
        const val MAX_DISCOVERED_REPOS = 50

        /** 仓库发现 prune 掉的目录名：体积巨大或与版本管理无关的目录（.git 不 prune，见下）。 */
        // 注意：不能把 ".git" 放进 prune 名单——左支 -prune 且无 -print 会吞掉所有 .git 目录，
        // 右支 -name .git -type d -print 永远无输出（审查员实测复现）。.git 只靠右支识别。
        private val DISCOVERY_SKIP_DIRS = setOf("node_modules", "tmp", "archive", "build")
    }

    /**
     * 当前 git 命令的执行目录（Linux 内路径）：选中了绑定根内的子目录仓库才用其路径，
     * 否则回到绑定根。选中路径先过 [validateUnderBindRoot]，非法路径在入口即抛，
     * 绝不带出工作区边界。
     */
    private fun repoCwd(): String = engine.toLinuxPath(selectedRepoOrRoot())

    /** 选中仓库的 Android 路径；未选中时为绑定根本身。 */
    private fun selectedRepoOrRoot(): String =
        currentRepoPath?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: BIND_ROOT.absolutePath

    /**
     * realpath 边界校验：canonical 化后必须等于绑定根或落在其前缀内。
     * canonicalFile 同时解析 `..` 与符号链接，两者一次覆盖；不校验的话，一次恶意/误操作
     * 的「选择仓库」就能让 git 在工作区外任意目录执行读写。
     */
    private fun validateUnderBindRoot(path: String): String {
        val canonical = File(path).canonicalFile.absolutePath
        val rootCanonical = BIND_ROOT.canonicalFile.absolutePath
        if (canonical != rootCanonical && !canonical.startsWith("$rootCanonical/")) {
            throw GitCommandFailureException("仓库路径越出绑定根（$rootCanonical）：$path")
        }
        return canonical
    }

    /** 设置当前选中的 git 仓库路径（null 回到绑定根）；路径必须通过绑定根校验，否则抛异常。 */
    fun setRepoPath(path: String?) {
        currentRepoPath = path?.let { validateUnderBindRoot(it) }
    }

    /**
     * 发现绑定根内的 git 仓库：绑定根本身 + 深度三层内的含 `.git` 子目录（即 .git 目录层深 ≤ 4）。
     * 实现为 Linux `find`（绑定根在 App UID 下不可读，Java File 遍历会静默得空）：
     * `-maxdepth 4` 对应「仓库最深三层」（.git 比仓库深一层），对跳过名单 prune，
     * 输出经 [EtaGitEngine.toAndroidPath] 反向映射为 Android 路径，上限 [MAX_DISCOVERED_REPOS]。整段跑在 Dispatchers.IO（阻塞的进程等待，不占调用协程线程）；引擎层传 [DISCOVERY_TIMEOUT_SECONDS]（10s）收口——supervisor waitFor 超时杀进程树并按约定返回 -2，显式抛 [RepoDiscoveryTimeoutException]，不会被「非零退出且输出为空 → 非仓库」分支误吞。
     * 2026-11-28：仓库选择弹窗改为「发现即全列」（见 GitPanelState.buildRepoPickerEntries），
     * 本函数成为弹窗条目的唯一数据源；绑定根本身是仓库时排最前（find 的 .git 输出已含它）。
     */
    suspend fun discoverRepos(): List<String> = withContext(Dispatchers.IO) {
        val rootLinux = engine.toLinuxPath(BIND_ROOT.absolutePath)
        val prune = DISCOVERY_SKIP_DIRS.joinToString(" -o ") { "-name ${EtaGitEngine.shellQuote(it)}" }
        val cmd = "find ${EtaGitEngine.shellQuote(rootLinux)} -maxdepth 4 " +
            "\\( $prune \\) -prune -o -name .git -type d -print 2>/dev/null"
        // 引擎层超时收口：阻塞的 waitFor 最多 10s（否则 supervisor 杀进程树），withTimeout 只是兜底。
        val result = engine.raw(cmd, DISCOVERY_TIMEOUT_SECONDS)
        if (result.exitCode == ENGINE_TIMEOUT_EXIT_CODE) throw RepoDiscoveryTimeoutException()
        if (result.exitCode != 0 && result.output.isBlank()) return@withContext emptyList()
        val repos = mutableListOf<String>()
        for (line in result.output.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            // 显式 -print 只在右支：prune 名单目录不进输出，正常情况下输出即各仓库的 .git 目录路径；
            // 行尾过滤 /.git 只作防御；仓库目录 = 去掉尾部的 /.git（绑定根自身是仓库时即得绑定根）。
            if (!l.endsWith("/.git")) continue
            val repoLinux = l.removeSuffix("/.git")
            repos += engine.toAndroidPath(repoLinux)
            if (repos.size >= MAX_DISCOVERED_REPOS) break
        }
        // 绑定根本身是仓库时排最前（find 的 .git 输出已含它），保持「根优先、其余按发现顺序」。
        repos.distinct()
    }

    /** 指定路径是否处于 git 工作树内（用于仓库发现确认与手动指定校验）。 */
    suspend fun isGitRepo(path: String): Boolean {
        return runCatching {
            validateUnderBindRoot(path)
            engine.git(engine.toLinuxPath(path), "rev-parse", "--is-inside-work-tree").trim() == "true"
        }.getOrElse { false }
    }

    /** git 命令的默认执行目录（Android 路径，绑定根或选中仓库）。 */
    fun workspaceRoot(): String = selectedRepoOrRoot()

    /** 绑定根内是否处于一个 git 工作树内。环境异常时返回 false 而非抛出，避免 UI 崩溃。 */
    suspend fun isRepo(): Boolean {
        return runCatching { git("rev-parse", "--is-inside-work-tree").trim() == "true" }
            .getOrElse { false }
    }

    /** 引擎是否就绪可执行 git 命令；未就绪时返回引导文案（供 Git 面板在刷新前提示）。 */
    fun notReadyHint(): String? = engine.notReadyHint()

    /** 当前 Linux 后端是否为 proot；UI 写按钮据此降级展示，真实拦截在引擎执行前兜底。 */
    val prootBackend: Boolean get() = engine.proot

    /**
     * 执行一条 `git` 只读子命令，返回合并后的 stdout+stderr 文本：这些靠输出解析、容错，
     * git 非零退出码不会让 UI 误判（解析得空罢了）。
     */
    private suspend fun git(vararg args: String): String = engine.git(repoCwd(), *args)

    /**
     * 执行一条 `git` **写**子命令，据退出码判成败：非零（真实失败）抛 [GitCommandFailureException]
     * 携带 git 输出文本，上层据此如实显示「失败 + 原因」而非误报成功。proot 后端在引擎层
     * 执行前拦截（见 [EtaGitEngine.gitChecked]），凭据与绑定根双不可达的静默失败被显式阻断。
     */
    private suspend fun gitChecked(vararg args: String): String = engine.gitChecked(repoCwd(), args.toList())

    /** 写通道的放宽超时版本：clone 等长命令专用。 */
    private suspend fun gitCheckedWithTimeout(timeoutSeconds: Long, vararg args: String): String =
        engine.gitChecked(repoCwd(), args.toList(), timeoutSeconds)

    /**
     * 在当前目录（绑定根或选中项目目录）初始化 git 仓库。
     *
     * 默认分支优先使用现代标准的 `main`（Git 2.28+ 支持 `git init -b main`），
     * 若环境 Git 版本较低不支持 `-b` 参数，降级为先 `git init` 再将未初始化的 HEAD 指向 `refs/heads/main`。
     */
    suspend fun initRepo(): String {
        return runCatching { gitChecked("init", "-b", "main") }
            .getOrElse {
                val out = gitChecked("init")
                runCatching { git("symbolic-ref", "HEAD", "refs/heads/main") }
                out
            }
    }

    /**
     * 克隆远程仓库到绑定根下的新建子目录 [targetSubdir]（如 `Eta-fork`）。
     * 目标先过 [validateCloneTarget]（realpath 边界 + 不存在或为空），随后 `git clone <url> <dir>`，
     * 10 分钟超时。与 aicode 的 `git clone url .` 不同：绑定根是长期项目域，直接克隆进根会被拒。
     */
    suspend fun cloneRepo(url: String, targetSubdir: String): String {
        val target = validateCloneTarget(targetSubdir)
        val rootLinux = engine.toLinuxPath(BIND_ROOT.absolutePath)
        // 写操作（含 clone）在引擎 gitChecked 内已做 proot 拦截；这里直接走放宽超时通道。
        return engine.gitChecked(
            rootLinux,
            listOf("clone", url.trim(), target.name),
            EtaGitEngine.CLONE_TIMEOUT_SECONDS,
        )
    }

    /**
     * 校验克隆目标子目录：必须是绑定根下的直接子目录名（禁止路径分隔符与 `..`），
     * 且不存在或为空目录——往非空目录克隆会被 git 拒绝，而先清空目录可能吞掉用户文件。
     * 返回 canonical 化后的目标 [File]。
     */
    private fun validateCloneTarget(targetSubdir: String): File {
        val name = targetSubdir.trim().trim('/')
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") {
            throw GitCommandFailureException("克隆目标只能是绑定根下的一层子目录名：$targetSubdir")
        }
        val target = File(BIND_ROOT, name)
        val canonical = validateUnderBindRoot(target.absolutePath)
        val f = File(canonical)
        // listFiles 可能因 IO 权限返回 null：显式 orEmpty 判空，非目录直接视为可克隆目标
        if (f.exists() && (!f.isDirectory || !f.listFiles().isNullOrEmpty())) {
            throw GitCommandFailureException("克隆目标已存在且非空：${f.absolutePath}")
        }
        return f
    }

    /** 是否已配置至少一个远程仓库（`git remote` 输出非空）。拉取/推送前据此门控。 */
    suspend fun hasRemote(): Boolean = git("remote").trim().isNotEmpty()

    /** `git status` 聚合视图。 */
    suspend fun status(): GitStatus {
        val raw = git("status", "--porcelain=v1", "-b")
        val lines = raw.split('\n').map { it.removeSuffix("\r") }

        var branch = "(unknown)"
        var upstream: String? = null
        var isDetached = false
        var ahead = 0
        var behind = 0
        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<GitFileChange>()

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                val header = line.removePrefix("## ")
                isDetached = header.startsWith("HEAD (no branch)")
                val tracking = header.substringBefore(" [")
                if (isDetached) {
                    branch = "HEAD"
                } else {
                    branch = tracking.substringBefore("...").ifBlank { tracking }
                    upstream = tracking.substringAfter("...", "").ifBlank { null }
                }
                val bracket = header.substringAfter(" [", "")
                if (bracket.isNotBlank()) {
                    bracket.removeSuffix("]").split(",").forEach { tok ->
                        val t = tok.trim()
                        val n = t.filter { it.isDigit() }.toIntOrNull() ?: 0
                        if (t.startsWith("ahead")) ahead = n
                        else if (t.startsWith("behind")) behind = n
                    }
                }
                continue
            }
            if (line.length < 3) continue
            val x = line[0]
            val y = line[1]
            val rawPath = line.substring(3)
            // 重命名形如 "old -> new"，展示新路径。
            val path = unquoteGitPath(rawPath.substringAfter(" -> ").trim())

            if (x == '?' && y == '?') {
                untracked.add(path)
                continue
            }
            // 未合并冲突（Unmerged）：UU, AA, DD, AU, UD, UA, DU 等
            val isConflict = x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')
            if (isConflict) {
                conflicted.add(GitFileChange(path, "$x$y", staged = false))
                continue
            }
            if (x != ' ' && x != '?') {
                staged.add(GitFileChange(path, x.toString(), staged = true))
            }
            if (y != ' ' && y != '?') {
                unstaged.add(GitFileChange(path, y.toString(), staged = false))
            }
        }
        val isMerging = hasMergeHead() || conflicted.isNotEmpty()
        return GitStatus(branch, ahead, behind, staged, unstaged, untracked, conflicted, isMerging, upstream, isDetached)
    }

    /** 本地 + 远程分支列表，当前分支高亮。 */
    suspend fun branches(): List<GitBranch> = loadAllRefs().branches

    /**
     * 仅拉本地分支的 refs-by-commit 映射（`for-each-ref refs/heads`，大仓库亚秒级），
     * 供首屏轻量快照的 graph 标注当前分支位置。远程分支与标签标注延迟到 BRANCHES tab 全量加载。
     */
    suspend fun localRefsOnly(): Map<String, List<GitGraphRef>> = withContext(Dispatchers.Default) {
        val raw = runCatching {
            git("for-each-ref", "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)", "refs/heads")
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext emptyMap()
        val result = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val isHead = parts[2].trim() == "*"
            result.getOrPut(parts[1]) { mutableListOf() }
                .add(GitGraphRef(parts[0], isBranch = true, isCurrent = isHead, isRemote = false))
        }
        result
    }

    /** 最近 [limit] 条提交。 */
    suspend fun log(limit: Int = 50): List<GitCommit> {
        val raw = git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s", "-n", limit.toString())
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 5) null
            else GitCommit(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }

    /**
     * 拓扑图视图：提交（含父哈希）+ 引用（分支/标签）+ 泳道布局。
     * `git log --pretty=format:...%P` 在常规字段后追加父哈希列表（空格分隔，多个即合并提交）；
     * `git for-each-ref` 一次拿全部分支/标签及其指向的提交哈希与是否 HEAD。提交与引用解析后，
     * 在 [Dispatchers.Default] 上跑纯 Kotlin 泳道分配算法（见 [GitGraphBuilder]），产出 [GitGraph]。
     * 失败（非仓库/无提交）返回 [GitGraph.EMPTY]，不抛——UI 据空图显示空态。
     */
    suspend fun graph(
        limit: Int = GRAPH_PAGE_SIZE,
        refs: Map<String, List<GitGraphRef>> = emptyMap()
    ): GitGraph = graphAppend(emptyList(), refs, limit)

    /**
     * 分页加载下一批提交并整体重算泳道布局。
     * 从已加载数量处 `git log --skip=<existingCommits.size> -n <limit>` 取下一批提交，与 [existingCommits]
     * 合并为完整列表后整体调 [GitGraphBuilder] 重算布局——泳道分配依赖全局子父顺序，父提交可能跨批次
     * 指向已加载提交，单算新批次会让列号与旧布局冲突导致连线断裂，故必须整图重算。
     * [hasMore] = 新批次返回条数达到页大小（未到则历史末尾）。
     */
    suspend fun graphAppend(
        existingCommits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>> = emptyMap(),
        limit: Int = GRAPH_PAGE_SIZE
    ): GitGraph = withContext(Dispatchers.Default) {
        val skip = existingCommits.size
        val logRaw = runCatching {
            git("log", "--topo-order", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b", "--skip", skip.toString(), "-n", limit.toString())
        }.getOrDefault("")
        if (logRaw.isBlank() || logRaw.startsWith("fatal:")) {
            return@withContext if (existingCommits.isEmpty()) GitGraph.EMPTY
            else GitGraphBuilder.buildGraph(existingCommits, refs, hasMore = false)
        }

        val newCommits = GitGraphBuilder.parseGraphCommits(logRaw)
        if (newCommits.isEmpty() && existingCommits.isEmpty()) return@withContext GitGraph.EMPTY

        // 合并去重：新批次理论上不会与已加载重复（--skip 跳过已加载数量），但防御性按 hash 去重保顺序。
        val existingHashes = existingCommits.mapTo(HashSet()) { it.hash }
        val merged = existingCommits + newCommits.filter { it.hash !in existingHashes }
        GitGraphBuilder.buildGraph(merged, refs, hasMore = newCommits.size >= limit)
    }

    /**
     * 一次拉取全部分支/标签及其指向的提交哈希，解析为三份切面：[AllRefs.branches] 列表、[AllRefs.tags] 列表、
     * [AllRefs.refsByCommit] 映射。`branches()`、`listTags()`、graph 的 refs 标注共用此一次调用，
     * 消除原先三条独立 `for-each-ref`/`tag` 命令（大仓库各 8-20s）。
     * 本地分支（refs/heads）isRemote=false，远程分支（refs/remotes）isRemote=true，标签（refs/tags）isBranch=false。
     * 标签按 refname 倒序（纯字符串比较，不读对象内容），避免 `--sort=-creatordate` 遍历所有 tag 对象的开销。
     */
    data class AllRefs(
        val branches: List<GitBranch>,
        val tags: List<GitTag>,
        val refsByCommit: Map<String, List<GitGraphRef>>
    )

    suspend fun loadAllRefs(): AllRefs = withContext(Dispatchers.Default) {
        val raw = runCatching {
            git(
                "for-each-ref",
                "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)\u001f%(upstream:short)\u001f%(upstream:track)",
                "refs/heads", "refs/remotes", "refs/tags"
            )
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext AllRefs(emptyList(), emptyList(), emptyMap())
        val branches = mutableListOf<GitBranch>()
        val tags = mutableListOf<GitTag>()
        val refsByCommit = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val name = parts[0]
            val hash = parts[1]
            val isHead = parts[2].trim() == "*"
            val refname = parts[3]
            val upstream = parts.getOrNull(4)?.ifBlank { null }
            val track = parts.getOrNull(5).orEmpty()
            val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isRemote = refname.startsWith("refs/remotes")
            val isBranch = refname.startsWith("refs/heads") || isRemote
            refsByCommit.getOrPut(hash) { mutableListOf() }
                .add(GitGraphRef(name, isBranch, isHead && !isRemote, isRemote))
            if (isBranch) {
                branches.add(GitBranch(name, current = isHead && !isRemote, remote = isRemote, upstream = upstream, ahead = ahead, behind = behind))
            } else {
                // 标签：objectname 取短哈希（前 7 位）与原 listTags 行为一致。
                tags.add(GitTag(name, hash.take(7)))
            }
        }
        // for-each-ref 默认按 refname 排序（本地分支、远程分支、标签各自有序），标签再倒序与原行为对齐。
        tags.reverse()
        AllRefs(branches, tags, refsByCommit)
    }

    /**
     * 某次提交改动的文件清单。用 `diff-tree --root` 以兼容无父的根提交；`--no-renames` 让重命名
     * 退化为「删除旧 + 新增新」，状态码取首字符即可复用 [GitFileChange]。
     * 返回空列表表示该提交无文件改动（如空提交）。
     */
    suspend fun commitFiles(hash: String): List<GitFileChange> {
        val raw = git("diff-tree", "--no-commit-id", "-r", "--root", "--name-status", "--no-renames", hash)
        return withContext(Dispatchers.Default) {
            raw.lineSequence().mapNotNull { line ->
                val l = line.removeSuffix("\r").trim()
                val tab = l.indexOf('\t')
                if (l.isBlank() || tab < 0) null
                else {
                    val status = l.substring(0, tab).trim()
                    val path = unquoteGitPath(l.substring(tab + 1).trim())
                    GitFileChange(path, status, staged = false)
                }
            }.toList()
        }
    }

    suspend fun stage(path: String) = gitChecked("add", "--", path)
    suspend fun unstage(path: String) = gitChecked("reset", "HEAD", "--", path)
    suspend fun stageAll() = gitChecked("add", "-A")
    suspend fun unstageAll() = gitChecked("reset")
    suspend fun commit(message: String, amend: Boolean = false) =
        if (amend) gitChecked("commit", "--amend", "-m", message)
        else gitChecked("commit", "-m", message)

    /** 拉取：直接 `git pull`，凭据由 credential.helper=store 自动注入（chroot /root）。 */
    suspend fun pull(): String = gitChecked("pull")

    /**
     * 推送：有上游则 `git push`；当前分支无上游时自动 `git push --set-upstream <remote> <branch>` 首推建关联，
     * 避免用户撞到 `fatal: has no upstream branch` 原始报错。remote 取 `git remote` 首个（无 remote 已被
     * 上层 hasRemote 门控挡掉）；分支取 `git rev-parse --abbrev-ref HEAD`。
     */
    suspend fun push(): String {
        val hasUpstream = runCatching { git("rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return gitChecked("push")
        val remote = git("remote").split('\n').firstOrNull { it.removeSuffix("\r").isNotBlank() }?.removeSuffix("\r")?.trim()
            ?: throw GitCommandFailureException("未配置远程仓库")
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").removeSuffix("\r").trim()
            .takeIf { it.isNotBlank() && it != "HEAD" }
            ?: throw GitCommandFailureException("无法确定当前分支（处于 detached HEAD）")
        return gitChecked("push", "--set-upstream", remote, branch)
    }

    /**
     * 当前是否为 master 分支的首次推送（当前分支为 master 且尚未建立 upstream 跟踪关系）。
     * UI 据此弹「重命名为 main」三选弹窗（自 aicode GitRepository.isFirstPushOfMaster 移植，2026-11-28）。
     */
    suspend fun isFirstPushOfMaster(): Boolean {
        val hasUpstream = runCatching { git("rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return false
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").trim()
        return branch == "master"
    }

    /** 将当前 master 分支重命名为 main 并推送（首推场景下 [push] 会自动 --set-upstream 建立跟踪）。 */
    suspend fun renameMasterToMainAndPush(): String {
        renameBranch("master", "main")
        return push()
    }

    /** 切换到指定分支或标签。远程分支用 `git checkout -b <local> --track <remote>` 创建本地跟踪分支。 */
    suspend fun checkout(branch: String, isRemote: Boolean): String {
        return if (isRemote) {
            val localName = branch.substringAfter('/', branch)
            gitChecked("checkout", "-b", localName, "--track", branch)
        } else {
            gitChecked("checkout", branch)
        }
    }

    /**
     * 创建新分支。name 为新分支名；startPoint 为基准分支名（null/空 → 从当前 HEAD）；
     * checkout=true 则创建并切换（`git checkout -b`），否则仅创建不切换（`git branch`）。
     */
    suspend fun createBranch(name: String, startPoint: String?, checkout: Boolean): String {
        return if (checkout) {
            if (startPoint.isNullOrBlank()) gitChecked("checkout", "-b", name)
            else gitChecked("checkout", "-b", name, startPoint)
        } else {
            if (startPoint.isNullOrBlank()) gitChecked("branch", name)
            else gitChecked("branch", name, startPoint)
        }
    }

    /** 安全删除本地分支（`git branch -d`）：仅删除已合并分支；当前分支由 git 自身拦截。 */
    suspend fun deleteBranch(name: String): String = gitChecked("branch", "-d", name)

    /** 重命名本地分支（`git branch -m <old> <new>`）。失败由 [gitChecked] 抛 [GitCommandFailureException]。 */
    suspend fun renameBranch(oldName: String, newName: String): String =
        gitChecked("branch", "-m", oldName, newName)

    /** 创建轻量标签（`git tag <name>`），指向当前 HEAD 或指定提交。 */
    suspend fun createTag(name: String, commitHash: String? = null): String =
        if (commitHash.isNullOrBlank()) gitChecked("tag", name)
        else gitChecked("tag", name, commitHash)

    /** 删除本地标签（`git tag -d <name>`）。 */
    suspend fun deleteTag(name: String): String = gitChecked("tag", "-d", name)

    /** 本地标签列表，按名称倒序。 */
    suspend fun listTags(): List<GitTag> = loadAllRefs().tags

    /** 重置当前分支到指定提交。[mode] 可以是 "soft" 或 "mixed"。 */
    suspend fun resetToCommit(commitHash: String, mode: String = "mixed"): String =
        gitChecked("reset", "--$mode", commitHash)

    /**
     * 将忽略规则追加到当前仓库的 .gitignore：先 grep 判重，再用 printf 安全追加（不存在则创建）。
     * 辅助命令走 [EtaGitEngine.raw]，cwd 前缀用引擎同一个转义函数拼装，保证语义一致。
     */
    suspend fun addToGitignore(pattern: String): String {
        val normalized = pattern.trim()
        if (normalized.isBlank()) return ""
        val cwd = repoCwd()
        val check = engine.raw("cd ${EtaGitEngine.shellQuote(cwd)} && grep -F -x -- ${EtaGitEngine.shellQuote(normalized)} .gitignore 2>/dev/null")
        if (check.exitCode == 0) return normalized // 已存在，无需重复追加
        val append = engine.raw("cd ${EtaGitEngine.shellQuote(cwd)} && printf '%s\\n' ${EtaGitEngine.shellQuote(normalized)} >> .gitignore")
        if (append.exitCode != 0) {
            throw GitCommandFailureException(append.output.ifBlank { "写入 .gitignore 失败" })
        }
        return normalized
    }

    /** 删除远程分支（`git push <remote> --delete <branch>`）。会改远端，失败抛 [GitCommandFailureException]。 */
    suspend fun deleteRemoteBranch(ref: String): String {
        val remote = ref.substringBefore('/', "origin")
        val branch = ref.substringAfter('/', ref)
        return gitChecked("push", remote, "--delete", branch)
    }

    /** 合并指定分支到当前分支；[noFf] 为 true 时强制生成合并提交。 */
    suspend fun mergeBranch(branch: String, noFf: Boolean = false): String =
        if (noFf) gitChecked("merge", "--no-ff", branch) else gitChecked("merge", branch)

    /** 中止当前进行中的合并（`git merge --abort`）。 */
    suspend fun abortMerge(): String = gitChecked("merge", "--abort")

    /** 是否正在处于未完成的合并状态（MERGE_HEAD 存在）。 */
    private suspend fun hasMergeHead(): Boolean =
        runCatching { git("rev-parse", "-q", "--verify", "MERGE_HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /** 列出所有储藏记录。 */
    suspend fun stashList(): List<GitStash> = withContext(Dispatchers.Default) {
        val raw = runCatching { git("stash", "list", "--pretty=format:%gd%x1f%s%x1f%ar") }
            .getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext emptyList()
        raw.split('\n').mapNotNull { line ->
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) return@mapNotNull null
            val parts = l.split('\u001f')
            if (parts.size < 3) null
            else GitStash(index = parts[0], message = parts[1], date = parts[2])
        }
    }

    /** 储藏工作区改动。[message] 可选说明；[includeUntracked] 为 true 时包含未跟踪文件（`-u`）。 */
    suspend fun stashSave(message: String? = null, includeUntracked: Boolean = false): String {
        val args = mutableListOf("stash", "push")
        if (includeUntracked) args.add("-u")
        if (!message.isNullOrBlank()) {
            args.add("-m")
            args.add(message.trim())
        }
        return gitChecked(*args.toTypedArray())
    }

    /** 弹出指定储藏（应用并删除），默认为最近的一条 `stash@{0}`。 */
    suspend fun stashPop(index: String = "stash@{0}"): String = gitChecked("stash", "pop", index)

    /** 应用指定储藏（保留记录），默认为最近的一条 `stash@{0}`。 */
    suspend fun stashApply(index: String = "stash@{0}"): String = gitChecked("stash", "apply", index)

    /** 丢弃指定储藏，默认为 `stash@{0}`。 */
    suspend fun stashDrop(index: String = "stash@{0}"): String = gitChecked("stash", "drop", index)

    /** 清空所有储藏记录。 */
    suspend fun stashClear(): String = gitChecked("stash", "clear")

    /**
     * 回退单个文件的未暂存改动：把工作区还原成暂存区内容（`git checkout -- <path>`），
     * 被删除的文件由此重新出现。用 `checkout` 而非 `restore`——后者需 git 2.23+，兼容更老环境。
     */
    suspend fun revertWorktree(path: String): String = gitChecked("checkout", "--", path)

    /**
     * 回退单个文件的全部改动（已暂存 + 未暂存），还原到上次提交：先 `reset` 让索引回到 HEAD，
     * 再从索引还原工作区。文件在 HEAD 中不存在时（新增文件）reset 已让它退回未跟踪，
     * 此时跳过 checkout——否则 git 会以 `pathspec did not match` 失败。
     */
    suspend fun revertToHead(path: String): String {
        if (!hasHead()) return gitChecked("rm", "-q", "--cached", "--", path)
        gitChecked("reset", "-q", "HEAD", "--", path)
        return if (existsInHead(path)) gitChecked("checkout", "--", path) else ""
    }

    /**
     * 回退全部未暂存改动。pathspec `:/` 指仓库根，工作区是仓库子目录时也能覆盖全仓库改动
     * （`-- .` 只作用于当前目录）。已暂存的内容不受影响。
     */
    suspend fun revertAllWorktree(): String = gitChecked("checkout", "--", ":/")

    /** 删除未跟踪的文件或目录（`git clean -f -d`）。不带 `-x`，被 .gitignore 忽略的文件保持不动。 */
    suspend fun deleteUntracked(path: String): String = gitChecked("clean", "-f", "-d", "--", path)

    /** 某个未跟踪目录下的未跟踪文件清单（`git status` 把新目录折叠成一行，展开时按需调用）。 */
    suspend fun untrackedFilesIn(dir: String): List<String> {
        val raw = git("ls-files", "--others", "--exclude-standard", "--", dir)
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { it.removeSuffix("\r").trim().ifBlank { null } }
            .map { unquoteGitPath(it) }
    }

    /** 仓库是否已有提交（HEAD 可解析）。空仓库里 `reset HEAD` / `ls-tree HEAD` 都会失败。 */
    private suspend fun hasHead(): Boolean =
        runCatching { git("rev-parse", "--verify", "HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /** 指定路径在 HEAD 提交中是否存在。 */
    private suspend fun existsInHead(path: String): Boolean =
        runCatching { git("ls-tree", "HEAD", "--", path).trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /** 写入提交署名：优先项目级（--local），当前仓库无项目级署名时写全局作默认。 */
    suspend fun setUserIdentity(name: String, email: String) {
        val scope = if (hasLocalConfig("user.name") || hasLocalConfig("user.email")) "--local" else "--global"
        if (name.isNotBlank()) gitChecked("config", scope, "user.name", name)
        if (email.isNotBlank()) gitChecked("config", scope, "user.email", email)
    }

    /** 当前仓库是否有项目级（`--local`）配置值。非 git 仓库或无值时返回 false。 */
    private suspend fun hasLocalConfig(key: String): Boolean =
        runCatching { git("config", "--local", "--get", key).trim() }.getOrDefault("").isNotBlank()

    /** 读取 git 当前实际生效的 user.name（local→global→system），UI 回显与提交按钮判空用。 */
    suspend fun getUserName(): String =
        runCatching { git("config", "--get", "user.name").trim() }.getOrDefault("").removeSuffix("\r")

    /** 读取 git 当前实际生效的 user.email，UI 回显与编辑框初值。 */
    suspend fun getUserEmail(): String =
        runCatching { git("config", "--get", "user.email").trim() }.getOrDefault("").removeSuffix("\r")

    /** 写入仓库地址 remote.origin.url，仅写项目级——remote 地址绝不该污染 global（clone 解析双源问题）。 */
    suspend fun setRepoUrl(url: String) {
        if (url.isNotBlank()) {
            gitChecked("config", "--local", "remote.origin.url", url)
        } else {
            runCatching { gitChecked("config", "--local", "--unset", "remote.origin.url") }
        }
        runCatching { git("config", "--global", "--unset", "remote.origin.url") }
    }

    /** 读取 remote.origin.url（local→global→system），UI 回显与编辑框初值。 */
    suspend fun getRepoUrl(): String =
        runCatching { git("config", "--get", "remote.origin.url").trim() }.getOrDefault("").removeSuffix("\r")

    /**
     * 读取指定 ref（提交/分支/标签）下某文件的完整内容（`git show <ref>:<path>`）。
     * 文件在指定 ref 不存在时（如新增文件的首个提交）git 输出 `fatal:`，返回空串，
     * 上层据空串判定为「新增/删除」，整个文件按全增或全删呈现。
     */
    suspend fun showFileContent(ref: String, path: String): String {
        if (refBlobSize("$ref:$path") > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git("show", "$ref:$path")
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    /** 指定 ref 下文件（`<ref>:<path>` / `:<path>`）的字节数；取不到（不存在/非法 ref）时返回 0。 */
    private suspend fun refBlobSize(spec: String): Long =
        runCatching { git("cat-file", "-s", spec).trim().toLong() }.getOrDefault(0L)

    /**
     * 读取工作区当前文件内容（与 `HEAD:<path>` 对比看出未暂存的改动）。
     * 绑定根在 App UID 下不可读，java.io.File 直读会静默变空串（diff 显示成整文件删除），
     * 故统一借道 Linux 环境 `wc -c` + `cat`：先查大小防 OOM，超限抛 [GitOutputTooLargeException]。
     */
    suspend fun worktreeFileContent(path: String): String {
        val cwd = repoCwd()
        val size = engine.raw("cd ${EtaGitEngine.shellQuote(cwd)} && wc -c < ${EtaGitEngine.shellQuote(path)} 2>/dev/null")
        val bytes = size.output.trim().toLongOrNull() ?: 0L
        if (bytes > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val result = engine.raw("cd ${EtaGitEngine.shellQuote(cwd)} && cat -- ${EtaGitEngine.shellQuote(path)}")
        if (result.exitCode == 0) return result.output
        return ""
    }

    /** 读取暂存区当前文件内容（index）。文件尚未暂存时返回空串。 */
    suspend fun indexFileContent(path: String): String {
        if (refBlobSize(":$path") > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git("show", ":$path")
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }
}

/**
 * porcelain v1 / ls-files / diff-tree 对含引号、反斜杠、空格或控制字符的路径会整体加引号并做
 * C 风格转义（如 `"a\"b.txt"`、八进制 `\NNN`），这里做反向解析还原真实路径。
 * 未加引号的普通路径原样返回。
 * 八进制转义是**逐字节**的：一个汉字三个字节会转成三段 `\NNN`，逐段 `toChar()` 只能得到
 * 三个 Latin-1 字符（即乱码），故连续的 `\NNN` 先攒成字节序列再整体按 UTF-8 解码。
 */
internal fun unquoteGitPath(raw: String): String {
    if (!raw.startsWith("\"")) return raw
    val inner = raw.removeSurrounding("\"")
    val sb = StringBuilder(inner.length)
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c != '\\' || i + 1 >= inner.length) {
            sb.append(c)
            i += 1
            continue
        }
        if (inner[i + 1] in '0'..'7') {
            val bytes = mutableListOf<Byte>()
            while (i + 1 < inner.length && inner[i] == '\\' && inner[i + 1] in '0'..'7') {
                val octal = inner.substring(i + 1, minOf(i + 4, inner.length)).takeWhile { it in '0'..'7' }
                if (octal.length != 3) break
                bytes.add(octal.toInt(8).toByte())
                i += 4
            }
            if (bytes.isEmpty()) {
                sb.append(c)
                i += 1
            } else {
                sb.append(String(bytes.toByteArray(), Charsets.UTF_8))
            }
            continue
        }
        val decoded = when (inner[i + 1]) {
            'a' -> '\u0007'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'v' -> '\u000B'
            '"' -> '"'
            '\\' -> '\\'
            else -> null
        }
        if (decoded == null) {
            sb.append(c)
            i += 1
        } else {
            sb.append(decoded)
            i += 2
        }
    }
    return sb.toString()
}
