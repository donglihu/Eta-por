package io.github.mangi.eta.ui.git

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.git.EtaGitRepository
import io.github.mangi.eta.agent.git.GitCommandFailureException
import io.github.mangi.eta.agent.git.GitErrorMessage
import io.github.mangi.eta.agent.git.RepoDiscoveryTimeoutException
import io.github.mangi.eta.agent.git.model.GitBranch
import io.github.mangi.eta.agent.git.model.GitFileChange
import io.github.mangi.eta.agent.git.model.GitGraph
import io.github.mangi.eta.agent.git.model.GitStash
import io.github.mangi.eta.agent.git.model.GitStatus
import io.github.mangi.eta.agent.git.model.GitTab
import io.github.mangi.eta.core.AgentLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Git 页面的 UI 状态机（自 aicode presentation/GitViewModel.kt 移植并按 ETA 纪律改造，2026-11-28）。
 *
 * 与 aicode 版的差异：
 * - 不是 Android ViewModel：ETA 无 DI，由 [io.github.mangi.eta.ui.app.AgentAppState] 用 uiScope 构造持有
 *   （先例 [io.github.mangi.eta.ui.screens.files.CodeEditorState]），生命周期随 Activity 状态所有者；
 * - 异步全部走构造传入的 scope，Composable 里禁止 rememberCoroutineScope；
 * - toast 直接用系统 Toast（全屏页没有 SnackbarHost 可挂）；
 * - 仓库绑定根固定为工作区 projects 目录（EtaGitRepository），这是与 aicode 的唯一差异。
 *
 * 2026-11-28 第二批扩展（分支/提交 tab 落地）：分支/标签扩展操作（删远程分支/重命名/标签增删/
 * 合并/重置）、commit 的 amend 参数、提交详情弹层（detailCommitHash + 改动文件清单懒加载）。
 * 2026-11-28 扩展（对齐 aicode GitUiState）：identity（hasIdentity/userName）、储藏列表、
 * 未跟踪目录展开态、各确认弹窗目标与动作分发（回退/删除/丢弃储藏/中止合并/清空储藏/全部取消暂存/
 * 全部回退/master 首推三选）。进入页面即 [refresh] 并发拉取 status/graph/remote/identity；
 * 每个写操作执行后自动刷新并通过 [GitUiState.toast] 反馈。[GitUiState.busy] 守卫防止并发写操作相互踩踏。
 */
internal class GitPanelState(
    private val context: Context,
    private val repository: EtaGitRepository,
    private val scope: CoroutineScope,
    private val logger: AgentLogger,
) {

    /**
     * 待确认的回退目标。[staged] 为 true 表示连已暂存内容一起还原到上次提交；
     * [deleted] 只影响文案——文件已被删时这个操作实际是把它取回而非丢改动。
     */
    data class RevertTarget(val path: String, val staged: Boolean, val deleted: Boolean)

    /**
     * 仓库选择弹窗的单行条目。
     *
     * 口径演进：初版显示名拼「工作区名/子目录绝对路径」（前缀失配渲染脏数据），上轮改为目录名；
     * 本轮（2026-11-28）改为「相对绑定根的相对路径」——discoverRepos 深扫（maxdepth 4）后出现
     * 深层仓库（如 ETA/Eta），仅目录名区分不了同名子仓库，相对路径自带层级且无绝对路径尾巴。
     * path 保留真实绝对路径，点击切换、去重 key、选中高亮都以它为准；isRepo 在构建条目时判定：
     * 发现仓库天然 true（不逐条再跑 rev-parse），绑定根条目单独判定一次，结果缓存，渲染层不逐帧重查。
     */
    data class RepoPickerEntry(
        /** 真实绝对 Android 路径；点击切换、去重 key、选中高亮都以它为准。 */
        val path: String,
        /** 显示名：相对绑定根的相对路径（如 aicode、ETA/Eta）；绑定根条目用绑定根目录名。 */
        val displayName: String,
        /** 该路径是否为 git 仓库（构建时判定并缓存：发现仓库天然 true；渲染层不逐帧重查）。 */
        val isRepo: Boolean,
    )

    /** Git 页状态；整体不可变，经 [state] 暴露，变更只走 [_state].update。 */
    data class GitUiState(
        /** 是否正在刷新；初始 false（默认 true 会让 ensureLoaded 首开短路），首帧由 refresh 入口同步置 true。 */
        val loading: Boolean = false,
        /** 页面是否已完成过一次仓库发现（成功或确认非仓库）；控制进入页面时是否自动重扫。 */
        val loaded: Boolean = false,
        /** 仓库发现/快照加载失败原因；非空时内容区显示「加载失败 + 重试」，替代只进 toast。 */
        val loadError: String? = null,
        val notARepo: Boolean = false,
        val status: GitStatus? = null,
        val branches: List<GitBranch> = emptyList(),
        /** 拓扑图视图：提交（含父哈希）+ 引用 + 泳道布局，供日志页绘制彩色分支连线与分页。 */
        val graph: GitGraph = GitGraph.EMPTY,
        val tags: List<String> = emptyList(),
        val tab: GitTab = GitTab.STATUS,
        val busy: Boolean = false,
        val toast: String? = null,
        /** 是否已配置远程仓库，控制拉取/推送按钮可用性。 */
        val hasRemote: Boolean = false,
        /** 是否已配置提交署名 user.name（git config 实际生效值）；无署名提交按钮禁用并给指引文案。 */
        val hasIdentity: Boolean = false,
        /** 当前实际生效的 user.name（local→global→system），凭据页回显用。 */
        val userName: String = "",
        /** 仓库选择弹窗条目（discoverRepos 全部发现仓库 + 绑定根条目，相对路径显示名，含 isRepo 缓存判定）。 */
        val repoEntries: List<RepoPickerEntry> = emptyList(),
        /** 当前选中的 git 仓库路径；null 表示未选中（绑定根 + 子目录都非仓库）。 */
        val currentRepo: String? = null,
        /** 绑定根路径（仓库选择弹窗的基准目录展示）。 */
        val workspaceRoot: String = "",
        /** 是否显示仓库选择弹窗。 */
        val repoPickerVisible: Boolean = false,
        /** 正在分页加载更旧的提交（滚动到底触发）。不置 busy，避免阻塞写操作。 */
        val graphLoadingMore: Boolean = false,
        /** 全量分支/标签是否已加载（进入页面即后台拉取，不阻塞首屏）。 */
        val branchesLoaded: Boolean = false,
        /** 正在加载全量分支/标签。 */
        val branchesLoading: Boolean = false,
        /** 正在切换分支。 */
        val checkoutLoading: String? = null,
        /** 打开中的提交详情哈希；null 表示未打开详情弹层（提交 tab 专用）。 */
        val detailCommitHash: String? = null,
        /** 已加载的提交改动文件清单缓存（按提交哈希；同一提交内容不可变，可安全复用）。 */
        val commitFiles: Map<String, List<GitFileChange>> = emptyMap(),
        /** 正在加载的提交文件清单哈希。 */
        val commitFilesLoading: String? = null,
        /** 当前 Linux 后端是否为 proot；写操作按钮据此降级为提示而非执行。 */
        val prootBlocked: Boolean = false,
        /** 储藏列表（打开储藏箱时按需拉取）。 */
        val stashes: List<GitStash> = emptyList(),
        /** 正在加载储藏列表。 */
        val stashesLoading: Boolean = false,
        /** 已展开的未跟踪目录 → 其下未跟踪文件清单。git status 把新目录折叠成一行，展开时按需查询。 */
        val untrackedDirFiles: Map<String, List<String>> = emptyMap(),
        /** 正在展开的未跟踪目录路径。 */
        val untrackedDirLoading: String? = null,
        /** 待确认的回退目标；null 表示未打开确认弹窗。 */
        val pendingRevert: RevertTarget? = null,
        /** 待确认删除的未跟踪文件/目录；null 表示未打开确认弹窗。 */
        val pendingDeleteUntracked: String? = null,
        /** 待确认丢弃的储藏引用（stash@{n}）；null 表示未打开确认弹窗。 */
        val pendingDropStash: String? = null,
        /** 「全部取消暂存」确认弹窗。 */
        val showUnstageAllConfirm: Boolean = false,
        /** 「全部回退」确认弹窗。 */
        val showRevertAllConfirm: Boolean = false,
        /** 「中止合并」确认弹窗。 */
        val showAbortMergeConfirm: Boolean = false,
        /** 「清空全部储藏」确认弹窗。 */
        val showClearStashConfirm: Boolean = false,
        /** master 首推「重命名为 main」三选弹窗。 */
        val showPushMasterConfirm: Boolean = false,
    )

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    /** 仓库快照：并发拉取 status/graph/remote/identity 的结果。不含全量分支/标签——那些在 refresh 成功后异步拉取。 */
    private data class RepoSnapshot(
        val status: GitStatus,
        val graph: GitGraph,
        val hasRemote: Boolean,
        val hasIdentity: Boolean,
        val untrackedDirFiles: Map<String, List<String>>,
    )

    /** 并发拉取轻量快照：status + graph(本地 refs) + remote + identity + 已展开未跟踪目录重扫。 */
    private suspend fun loadSnapshot(): RepoSnapshot = coroutineScope {
        val status = async { repository.status() }
        val localRefs = async { repository.localRefsOnly() }
        val hasRemote = async { repository.hasRemote() }
        val identity = async { repository.getUserName() }
        val graph = async { repository.graph(refs = localRefs.await()) }
        val s = status.await()
        RepoSnapshot(
            status = s,
            graph = graph.await(),
            hasRemote = hasRemote.await(),
            hasIdentity = identity.await().isNotBlank(),
            untrackedDirFiles = reloadExpandedUntrackedDirs(s),
        )
    }

    /** 重新拉取已展开的未跟踪目录内容（写操作/刷新后目录内容可能变化）。 */
    private suspend fun reloadExpandedUntrackedDirs(status: GitStatus): Map<String, List<String>> = coroutineScope {
        status.untracked.filter { it.endsWith("/") }
            .map { dir -> async { dir to runCatching { repository.untrackedFilesIn(dir) }.onFailure { logger.error("$TAG: 拉取未跟踪目录 $dir 内容失败", it) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .toMap()
    }

    /**
     * 刷新仓库快照：发现仓库 → 选默认仓库 → 拉快照。
     *
     * 仓库发现（Linux find）钉在 Dispatchers.IO：引擎层 10s 超时收口（supervisor 杀进程树，超时抛
     * [RepoDiscoveryTimeoutException]），withTimeout([REPO_DISCOVER_TIMEOUT_MS]) 只作兜底取消挂起点；
     * 超时或失败都置 [GitUiState.loadError] 错误态（UI 显示「加载失败 + 重试」），不再只走 toast
     * 或卡死在「加载中」。成功（或确认非仓库）置 [GitUiState.loaded]，再次进页复用快照。
     */
    fun refresh() {
        // busy 或正在加载都直接返回：防重复触发叠请求（loading 初始为 false，此守卫语义才成立）。
        if (_state.value.busy || _state.value.loading) return
        repository.notReadyHint()?.let { hint ->
            _state.update { it.copy(loading = false, loaded = true, loadError = null, notARepo = true, toast = hint) }
            return
        }
        _state.update { it.copy(loading = true, loadError = null, toast = null, prootBlocked = repository.prootBackend) }
        scope.launch {
            try {
                val repos = withTimeout(REPO_DISCOVER_TIMEOUT_MS) {
                    // 阻塞调用靠引擎层 10s 超时收口（supervisor 杀进程树，超时抛 RepoDiscoveryTimeoutException）；
                    // 这里 withTimeout 只作兜底：取消挂起点的协程等待。
                    withContext(Dispatchers.IO) { repository.discoverRepos() }
                }
                val current = repository.currentRepoPath
                // 当前选中仓库已不在发现列表（如切换了项目）时回退默认：第一个发现的仓库。
                val effectiveRepo = current?.takeIf { it in repos } ?: repos.firstOrNull()
                repository.setRepoPath(effectiveRepo)
                // workspaceRoot 在 setRepoPath 之后取：克隆对话框默认子目录名与绑定根展示对准新选中仓库（或绑定根）。
                val workspaceRoot = repository.workspaceRoot()
                // 弹窗条目直接以本次 discoverRepos 结果构建（发现即全列），不再额外扫一层子目录。
                val repoEntries = buildRepoPickerEntries(repos)
                if (effectiveRepo == null) {
                    _state.update {
                        it.copy(
                            loading = false, loaded = true, loadError = null, notARepo = true,
                            repoEntries = repoEntries, workspaceRoot = workspaceRoot, currentRepo = null,
                        )
                    }
                    return@launch
                }
                val snap = loadSnapshot()
                _state.update {
                    it.copy(
                        loading = false, loaded = true, loadError = null, notARepo = false,
                        status = snap.status, graph = snap.graph, hasRemote = snap.hasRemote,
                        hasIdentity = snap.hasIdentity, untrackedDirFiles = snap.untrackedDirFiles,
                        branchesLoaded = false, branchesLoading = false,
                        branches = emptyList(), tags = emptyList(),
                        repoEntries = repoEntries, workspaceRoot = workspaceRoot, currentRepo = effectiveRepo,
                    )
                }
                // 页面已打开：后台拉取全量分支/标签，用户切到分支页时无需再等。
                loadBranches()
            } catch (timeout: RepoDiscoveryTimeoutException) {
                logger.error("$TAG: 仓库发现超时（引擎层 10s 收口）")
                _state.update { it.copy(loading = false, loadError = context.getString(R.string.git_load_timeout)) }
            } catch (timeout: TimeoutCancellationException) {
                logger.error("$TAG: 仓库发现超时（${REPO_DISCOVER_TIMEOUT_MS}ms）")
                _state.update { it.copy(loading = false, loadError = context.getString(R.string.git_load_timeout)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 刷新失败", e)
                _state.update {
                    it.copy(
                        loading = false,
                        loadError = context.getString(R.string.git_load_failed, GitErrorMessage.friendly(e.message ?: "")),
                    )
                }
            }
        }
    }

    /**
     * 页面进入时触发首次加载（全屏页每次进页都走这里）。由 GitScreen 的 LaunchedEffect(Unit) 调用：
     * 未加载过或上次失败时执行 [refresh]；成功过则直接复用快照，手动刷新走刷新按钮。
     */
    fun ensureLoaded() {
        if (_state.value.loading || _state.value.loaded) return
        refresh()
    }

    /** 切换页签；分支页未加载过时兜底补一次加载。 */
    fun setTab(tab: GitTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == GitTab.BRANCHES && !_state.value.branchesLoaded && !_state.value.branchesLoading) {
            loadBranches()
        }
    }

    /** 加载全量分支/标签（进入页面 refresh 成功后即触发）。一次 loadAllRefs 拉取，不置 busy。 */
    fun loadBranches() {
        if (_state.value.branchesLoading || _state.value.branchesLoaded) return
        _state.update { it.copy(branchesLoading = true) }
        scope.launch {
            try {
                val allRefs = repository.loadAllRefs()
                // 用全量 refs 更新 graph 标注（远程分支+标签补全）。
                val graph = repository.graphAppend(_state.value.graph.commits, allRefs.refsByCommit)
                _state.update {
                    it.copy(
                        branches = allRefs.branches,
                        tags = allRefs.tags.map { t -> t.name },
                        graph = graph,
                        branchesLoading = false,
                        branchesLoaded = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 加载分支列表失败", e)
                _state.update {
                    it.copy(branchesLoading = false, toast = context.getString(R.string.git_toast_load_branches_failed, e.message ?: ""))
                }
            }
        }
    }

    /** 写操作后同步刷新已加载的分支/标签（若已加载过）。未加载过则不主动拉，保持延迟加载语义。 */
    private fun refreshBranchesIfLoaded() {
        if (!_state.value.branchesLoaded) return
        scope.launch {
            try {
                val allRefs = repository.loadAllRefs()
                _state.update { it.copy(branches = allRefs.branches, tags = allRefs.tags.map { t -> t.name }) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 刷新分支列表失败", e)
            }
        }
    }

    /** 执行一个写操作：置 busy → 跑命令 → 刷新 → 反馈。操作间互斥；[after] 在成功刷新后追加（如重拉储藏列表）。 */
    private fun runAction(name: String, action: suspend () -> String, after: (() -> Unit)? = null) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, toast = null) }
        scope.launch {
            val msg = try {
                action()
                context.getString(R.string.git_toast_action_success, name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: ${name}失败", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_action_failed, name, GitErrorMessage.friendly(reason ?: ""))
            }
            // 刷新以反映新状态；失败也刷新，让 UI 与仓库一致。
            try {
                if (repository.isRepo()) {
                    val snap = loadSnapshot()
                    _state.update {
                        it.copy(
                            busy = false, status = snap.status, graph = snap.graph, hasRemote = snap.hasRemote,
                            hasIdentity = snap.hasIdentity, untrackedDirFiles = snap.untrackedDirFiles,
                            notARepo = false, toast = msg,
                        )
                    }
                    refreshBranchesIfLoaded()
                    after?.invoke()
                } else {
                    _state.update { it.copy(busy = false, notARepo = true, toast = msg) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: ${name}后刷新失败", e)
                _state.update { it.copy(busy = false, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    fun stage(path: String) = runAction(context.getString(R.string.git_stage), { repository.stage(path) })
    fun unstage(path: String) = runAction(context.getString(R.string.git_unstage), { repository.unstage(path) })
    fun stageAll() = runAction(context.getString(R.string.git_action_stage_all), { repository.stageAll() })
    fun unstageAll() = runAction(context.getString(R.string.git_action_unstage_all), { repository.unstageAll() })

    /** 提交已暂存改动；[amend]=true 追加到上一次提交（git commit --amend）。信息为空由 UI 按钮禁用挡住；无署名等失败经 runAction toast 透出。 */
    fun commit(message: String, amend: Boolean = false) {
        if (message.isBlank()) return
        runAction(context.getString(R.string.git_action_commit), { repository.commit(message, amend) })
    }

    /** 在当前选中目录初始化仓库；成功后 runAction 末尾自动刷新（notARepo 翻 false）。 */
    fun initRepo() = runAction(context.getString(R.string.git_action_init), { repository.initRepo() })

    /** 在绑定根下新建子目录并克隆远程仓库；目标非法/非空在仓库层前置校验即抛。 */
    fun cloneRepo(url: String, targetSubdir: String) {
        if (url.isBlank() || targetSubdir.isBlank()) return
        runAction(context.getString(R.string.git_action_clone), { repository.cloneRepo(url, targetSubdir) })
    }

    /** 拉取远程更新；未配置远程时提示而非空转。 */
    fun pull() {
        if (!_state.value.hasRemote) {
            _state.update { it.copy(toast = context.getString(R.string.git_toast_no_remote_pull)) }
            return
        }
        runAction(context.getString(R.string.git_pull), { repository.pull() })
    }

    /**
     * 推送：无上游时仓库层自动 --set-upstream 首推建关联。
     * master 分支首推先弹「重命名为 main」三选弹窗（对齐 aicode，2026-11-28）。
     */
    fun push() {
        if (!_state.value.hasRemote) {
            _state.update { it.copy(toast = context.getString(R.string.git_toast_no_remote_push)) }
            return
        }
        scope.launch {
            val firstMaster = try {
                repository.isFirstPushOfMaster()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 判断 master 首推失败", e)
                false
            }
            if (firstMaster) {
                _state.update { it.copy(showPushMasterConfirm = true) }
            } else {
                runAction(context.getString(R.string.git_push), { repository.push() })
            }
        }
    }

    /** 用户在 master 首推三选弹窗里选「重命名为 main 并推送」。 */
    fun pushRenameMasterToMain() =
        runAction(context.getString(R.string.git_push), { repository.renameMasterToMainAndPush() })

    /** 用户在 master 首推三选弹窗里选「保留 master 推送」。 */
    fun pushDirectly() = runAction(context.getString(R.string.git_push), { repository.push() })

    // ---------- 确认弹窗状态（目标/开关都在状态机，UI 只读展示与调 request/dismiss） ----------

    /** 请求确认回退单个文件：[staged]=true 连已暂存内容一起还原；[deleted]=true 实为恢复被删文件。 */
    fun requestRevert(path: String, staged: Boolean, deleted: Boolean = false) =
        _state.update { it.copy(pendingRevert = RevertTarget(path, staged, deleted)) }

    /** 关闭回退确认弹窗。 */
    fun dismissRevert() = _state.update { it.copy(pendingRevert = null) }

    /** 请求确认删除未跟踪文件/目录。 */
    fun requestDeleteUntracked(path: String) = _state.update { it.copy(pendingDeleteUntracked = path) }

    /** 关闭删除未跟踪确认弹窗。 */
    fun dismissDeleteUntracked() = _state.update { it.copy(pendingDeleteUntracked = null) }

    /** 请求确认丢弃储藏 [index]（stash@{n}）。 */
    fun requestDropStash(index: String) = _state.update { it.copy(pendingDropStash = index) }

    /** 关闭丢弃储藏确认弹窗。 */
    fun dismissDropStash() = _state.update { it.copy(pendingDropStash = null) }

    /** 请求「全部取消暂存」确认。 */
    fun requestUnstageAllConfirm() = _state.update { it.copy(showUnstageAllConfirm = true) }

    /** 关闭「全部取消暂存」确认。 */
    fun dismissUnstageAllConfirm() = _state.update { it.copy(showUnstageAllConfirm = false) }

    /** 请求「全部回退」确认。 */
    fun requestRevertAllConfirm() = _state.update { it.copy(showRevertAllConfirm = true) }

    /** 关闭「全部回退」确认。 */
    fun dismissRevertAllConfirm() = _state.update { it.copy(showRevertAllConfirm = false) }

    /** 请求「中止合并」确认。 */
    fun requestAbortMergeConfirm() = _state.update { it.copy(showAbortMergeConfirm = true) }

    /** 关闭「中止合并」确认。 */
    fun dismissAbortMergeConfirm() = _state.update { it.copy(showAbortMergeConfirm = false) }

    /** 请求「清空全部储藏」确认。 */
    fun requestClearStashConfirm() = _state.update { it.copy(showClearStashConfirm = true) }

    /** 关闭「清空全部储藏」确认。 */
    fun dismissClearStashConfirm() = _state.update { it.copy(showClearStashConfirm = false) }

    /** 关闭 master 首推三选弹窗（取消）。 */
    fun dismissPushMasterConfirm() = _state.update { it.copy(showPushMasterConfirm = false) }

    // ---------- 还原 / 忽略 / 中止合并 / 未跟踪目录展开 ----------

    /** 还原单个文件改动：[staged]=true 连已暂存内容一起还原到上次提交（git checkout HEAD --）；否则只还原工作区。 */
    fun revertFile(path: String, staged: Boolean) =
        if (staged) {
            runAction(context.getString(R.string.git_action_revert), { repository.revertToHead(path) })
        } else {
            runAction(context.getString(R.string.git_action_revert), { repository.revertWorktree(path) })
        }

    /** 丢弃全部未暂存改动（还原到上次暂存或提交的内容；已暂存内容不受影响）。 */
    fun revertAllUnstaged() =
        runAction(context.getString(R.string.git_action_revert_all), { repository.revertAllWorktree() })

    /** 删除未跟踪文件/目录（git clean -f -d；不带 -x，被 .gitignore 忽略的文件保持不动）。 */
    fun deleteUntracked(path: String) =
        runAction(context.getString(R.string.git_action_delete_file), { repository.deleteUntracked(path) })

    /** 添加 .gitignore 条目（/dir/、*.ext、/file 三种形态都由仓库层落盘）。 */
    fun addToGitignore(pattern: String) =
        runAction(context.getString(R.string.git_add_to_gitignore), { repository.addToGitignore(pattern) })

    /** 中止当前合并：放弃未解决冲突的改动，工作区还原到合并前状态。 */
    fun abortMerge() = runAction(context.getString(R.string.git_action_abort_merge), { repository.abortMerge() })

    /** 展开/收起未跟踪目录：展开时按需查询目录下的未跟踪文件清单（git status 把新目录折叠成一行）。 */
    fun toggleUntrackedDir(dir: String) {
        val current = _state.value
        if (current.untrackedDirLoading != null) return
        if (dir in current.untrackedDirFiles) {
            _state.update { it.copy(untrackedDirFiles = it.untrackedDirFiles - dir) }
            return
        }
        _state.update { it.copy(untrackedDirLoading = dir) }
        scope.launch {
            val files = try {
                repository.untrackedFilesIn(dir)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 展开未跟踪目录失败 $dir", e)
                _state.update {
                    it.copy(toast = context.getString(R.string.git_toast_action_failed, context.getString(R.string.git_status_untracked), e.message ?: ""))
                }
                emptyList()
            }
            _state.update { it.copy(untrackedDirFiles = it.untrackedDirFiles + (dir to files), untrackedDirLoading = null) }
        }
    }

    // ---------- 储藏（stash） ----------

    /** 打开储藏箱时拉取储藏列表（只读查看，不置 busy，不阻塞 status 页写操作）。 */
    fun loadStashes() {
        if (_state.value.stashesLoading) return
        _state.update { it.copy(stashesLoading = true) }
        scope.launch {
            val list = try {
                repository.stashList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 加载储藏列表失败", e)
                emptyList()
            }
            _state.update { it.copy(stashes = list, stashesLoading = false) }
        }
    }

    /** 保存储藏：[message] 可空；[includeUntracked] 对应 git stash -u。成功后重拉储藏列表。 */
    fun stashSave(message: String?, includeUntracked: Boolean) =
        runAction(context.getString(R.string.git_action_stash), { repository.stashSave(message, includeUntracked) }) { loadStashes() }

    /** 弹出储藏（pop = apply + drop）。成功后重拉储藏列表。 */
    fun stashPop(index: String) =
        runAction(context.getString(R.string.git_stash_pop), { repository.stashPop(index) }) { loadStashes() }

    /** 应用储藏（保留储藏记录）。成功后重拉储藏列表。 */
    fun stashApply(index: String) =
        runAction(context.getString(R.string.git_stash_apply), { repository.stashApply(index) }) { loadStashes() }

    /** 丢弃单条储藏。成功后重拉储藏列表。 */
    fun stashDrop(index: String) =
        runAction(context.getString(R.string.git_stash_drop), { repository.stashDrop(index) }) { loadStashes() }

    /** 清空全部储藏。成功后重拉储藏列表。 */
    fun stashClear() =
        runAction(context.getString(R.string.git_stash_clear), { repository.stashClear() }) { loadStashes() }

    // ---------- 仓库选择 ----------

    /**
     * 构建仓库选择弹窗条目：以 [EtaGitRepository.discoverRepos]（Linux find maxdepth 4，自带 distinct）
     * 发现的全部仓库为主体，绑定根本身是仓库时排首位——旧口径只列绑定根一层子目录，深两三层才有的
     * 仓库（如 ETA/Eta）根本进不了弹窗，本轮拍板改为「发现即全列」。
     *
     * 显示名 = 相对绑定根的相对路径（aicode、ETA/Eta），绑定根条目用绑定根目录名：深层仓库仅靠
     * 目录名区分不了同名子仓库，相对路径自带层级；canonicalFile 相对化（与 validateUnderBindRoot
     * 的 realpath 规范化同源，吃掉符号链接与 `..` 差异），前缀失配时降级取目录名，绝不出现绝对路径尾巴。
     *
     * 去重双保险：discoverRepos 自带 distinct，这里再按 canonicalFile distinct；绑定根已在发现清单
     * 时天然合并，仅在缺失时单独跑一次 [EtaGitRepository.isGitRepo] 兜底判定（发现层异常时根条目
     * 不消失）。isRepo 不逐条再查：发现仓库天然 true，渲染层零 rev-parse 开销。
     *
     * 排序：绑定根条目最前，其余按相对路径逐段字典序（路径感知，ETA/Eta 与 ETA/Eta-dispatch 相邻）；
     * LazyColumn 以绝对路径作 key 天然唯一（上轮重复 key 闪退的修复语义不变）。
     */
    private suspend fun buildRepoPickerEntries(discovered: List<String>): List<RepoPickerEntry> {
        val bindRoot = EtaGitRepository.BIND_ROOT.canonicalFile.absolutePath.trimEnd('/')
        val bindRootName = bindRoot.substringAfterLast('/')
        val discoveredCanonical = discovered.map { File(it).canonicalFile.absolutePath.trimEnd('/') }.distinct()
        // 绑定根是仓库才出现根条目：find 已含它时零额外开销；不在清单时单独判定一次兜底。
        val bindRootIsRepo = bindRoot in discoveredCanonical || repository.isGitRepo(bindRoot)
        // 绑定根最前；其余按相对路径逐段比较（路径感知），同段前缀的深层仓库互为邻居。
        val others = discoveredCanonical
            .filter { it != bindRoot }
            .sortedWith { x, y ->
                val a = x.removePrefix("$bindRoot/").split('/')
                val b = y.removePrefix("$bindRoot/").split('/')
                a.zip(b) { sa, sb -> sa.compareTo(sb) }.firstOrNull { it != 0 } ?: (a.size - b.size)
            }
        val ordered = (if (bindRootIsRepo) listOf(bindRoot) else emptyList()) + others
        return ordered.map { path ->
            val relative = when {
                path == bindRoot -> ""
                path.startsWith("$bindRoot/") -> path.removePrefix("$bindRoot/")
                // 理论不可达（setRepoPath 已校验绑定根边界）；降级取目录名，绝不让绝对路径尾巴漏进 UI。
                else -> path.substringAfterLast('/')
            }
            RepoPickerEntry(
                path = path,
                displayName = relative.ifBlank { bindRootName },
                isRepo = path != bindRoot || bindRootIsRepo,
            )
        }
    }

    /** 显示仓库选择弹窗（列出 discoverRepos 发现的全部仓库，条目 isRepo 构建期已判定）。 */
    fun showRepoPicker() = _state.update { it.copy(repoPickerVisible = true) }

    /** 关闭仓库选择弹窗。 */
    fun hideRepoPicker() = _state.update { it.copy(repoPickerVisible = false) }

    /** 切换到指定路径的 git 仓库：先校验目标是仓库，通过后设置并刷新。 */
    fun switchRepo(path: String) {
        if (_state.value.busy) return
        _state.update { it.copy(repoPickerVisible = false) }
        scope.launch {
            try {
                if (!repository.isGitRepo(path)) {
                    _state.update { it.copy(toast = context.getString(R.string.git_toast_not_repo)) }
                    return@launch
                }
                repository.setRepoPath(path)
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 切换仓库失败", e)
                _state.update { it.copy(toast = context.getString(R.string.git_toast_switch_repo_failed, e.message ?: "")) }
            }
        }
    }

    /**
     * 分页加载更旧的提交。UI 滚到底时调用：graphAppend 用 `--skip` 取下一页并整体重算泳道布局。
     * 守卫：正在加载或无更多时直接返回。失败 toast 提示且保留 hasMore 允许重试。
     */
    fun loadMoreCommits() {
        val current = _state.value
        if (current.graphLoadingMore || !current.graph.hasMore) return
        _state.update { it.copy(graphLoadingMore = true) }
        scope.launch {
            try {
                val graph = repository.graphAppend(current.graph.commits, current.graph.refs)
                _state.update { it.copy(graph = graph, graphLoadingMore = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 加载更多提交失败", e)
                _state.update { it.copy(graphLoadingMore = false, toast = context.getString(R.string.git_toast_load_more_failed, e.message ?: "")) }
            }
        }
    }

    /** 切换到指定分支或标签；成功后刷新全量状态与已加载的分支列表。 */
    fun checkoutBranch(ref: String, isRemote: Boolean = false) {
        if (_state.value.busy || _state.value.checkoutLoading != null) return
        _state.update { it.copy(checkoutLoading = ref, toast = null) }
        scope.launch {
            val msg = try {
                repository.checkout(ref, isRemote)
                context.getString(R.string.git_toast_checkout_success, ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 切换分支失败", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_checkout_failed, GitErrorMessage.friendly(reason ?: ""))
            }
            try {
                if (repository.isRepo()) {
                    val snap = loadSnapshot()
                    _state.update {
                        it.copy(
                            checkoutLoading = null, status = snap.status, graph = snap.graph, hasRemote = snap.hasRemote,
                            hasIdentity = snap.hasIdentity, untrackedDirFiles = snap.untrackedDirFiles,
                            notARepo = false, toast = msg,
                        )
                    }
                    // 切换分支后分支列表可能变化，若已加载过则刷新。
                    refreshBranchesIfLoaded()
                } else {
                    _state.update { it.copy(checkoutLoading = null, notARepo = true, toast = msg) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 切换分支后刷新失败", e)
                _state.update { it.copy(checkoutLoading = null, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    /** 创建新分支（name 非空由调用方保证；checkout=true 则创建并切换）。 */
    fun createBranch(name: String, startPoint: String? = null, checkout: Boolean = true) {
        if (name.isBlank()) return
        runAction(context.getString(R.string.git_action_create_branch), { repository.createBranch(name, startPoint, checkout) })
    }

    /** 安全删除本地分支（git branch -d）。未合并或为当前分支时 git 报错，经 toast 透出。 */
    fun deleteBranch(name: String) {
        if (name.isBlank()) return
        runAction(context.getString(R.string.git_action_delete_branch), { repository.deleteBranch(name) })
    }

    /** 删除远程分支（git push <remote> --delete）。会改远端，失败经 runAction toast 透出。 */
    fun deleteRemoteBranch(ref: String) {
        if (ref.isBlank()) return
        runAction(context.getString(R.string.git_action_delete_remote_branch), { repository.deleteRemoteBranch(ref) })
    }

    /** 重命名本地分支（git branch -m）。当前分支也可重命名；失败经 runAction toast 透出。 */
    fun renameBranch(oldName: String, newName: String) {
        if (oldName.isBlank() || newName.isBlank()) return
        runAction(context.getString(R.string.git_action_rename_branch), { repository.renameBranch(oldName, newName) })
    }

    /** 创建轻量标签（git tag <name>），指向当前 HEAD 或指定提交。失败经 runAction toast 透出。 */
    fun createTag(name: String, commitHash: String? = null) {
        if (name.isBlank()) return
        runAction(context.getString(R.string.git_action_create_tag), { repository.createTag(name, commitHash) })
    }

    /** 删除本地标签（git tag -d）。失败经 runAction toast 透出。 */
    fun deleteTag(name: String) {
        if (name.isBlank()) return
        runAction(context.getString(R.string.git_action_delete_tag), { repository.deleteTag(name) })
    }

    /** 合并指定分支到当前分支；[noFf]=true 强制生成合并提交。产生冲突时 UI 走状态页冲突组/中止合并横幅。 */
    fun mergeBranch(branch: String, noFf: Boolean = false) {
        if (branch.isBlank()) return
        runAction(context.getString(R.string.git_action_merge), { repository.mergeBranch(branch, noFf) })
    }

    /** 重置当前分支到指定提交；[mode] 为 soft/mixed/hard（git reset --<mode> <hash>）。 */
    fun resetToCommit(commitHash: String, mode: String = "mixed") {
        if (commitHash.isBlank()) return
        runAction(context.getString(R.string.git_reset_to_commit), { repository.resetToCommit(commitHash, mode) })
    }

    /**
     * 打开某条提交的详情弹层（提交 tab 专用）。文件清单懒加载：
     * 未缓存时后台拉取，不置 busy——这是只读查看，不应阻塞状态/分支页的写操作。
     */
    fun openCommitDetail(hash: String) {
        _state.update { it.copy(detailCommitHash = hash) }
        loadCommitFiles(hash)
    }

    /** 关闭提交详情弹层（文件清单缓存保留，再次打开同一提交直接复用）。 */
    fun closeCommitDetail() = _state.update { it.copy(detailCommitHash = null) }

    /** 加载提交的改动文件清单；已缓存或正在加载时跳过。失败 toast 且不缓存（允许重试）。 */
    fun loadCommitFiles(hash: String) {
        val current = _state.value
        if (hash in current.commitFiles || current.commitFilesLoading != null) return
        _state.update { it.copy(commitFilesLoading = hash) }
        scope.launch {
            val files = try {
                repository.commitFiles(hash)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 加载提交文件失败: $hash", e)
                _state.update { it.copy(toast = context.getString(R.string.git_toast_commit_files_failed, e.message ?: "")) }
                emptyList()
            }
            _state.update { it.copy(commitFiles = it.commitFiles + (hash to files), commitFilesLoading = null) }
        }
    }

    /** 消费一次性 toast（UI 显示后回调，避免重复弹出）。 */
    fun consumeToast() = _state.update { it.copy(toast = null) }

    private companion object {
        const val TAG = "GitPanelState"

        /** 仓库发现超时：Linux find 在大目录树上可能长时间不返回，超时转错误态而非永久「加载中」。 */
        const val REPO_DISCOVER_TIMEOUT_MS = 10_000L
    }
}
