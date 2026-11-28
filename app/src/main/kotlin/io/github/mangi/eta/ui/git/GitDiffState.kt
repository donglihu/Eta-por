package io.github.mangi.eta.ui.git

import io.github.mangi.eta.agent.git.EtaGitRepository
import io.github.mangi.eta.agent.git.GitCommandFailureException
import io.github.mangi.eta.agent.git.GitOutputTooLargeException
import io.github.mangi.eta.core.AgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Git diff 全屏页（AppRoute.GitDiff）的 UI 状态机（自 aicode GitViewModel.computeDiff 思路移植，2026-11-28）。
 *
 * 与 aicode 版的差异：
 * - 不是 Android ViewModel：ETA 无 DI，由 AgentAppRoot 的 entry 用 remember 构造持有，
 *   异步全部走构造传入的 scope（AgentAppState.uiScope），Composable 里禁止 rememberCoroutineScope；
 * - 不做语法高亮：ETA 无 Highlights 依赖，diff 行按纯文本渲染（视觉规格见 GitDiffScreen）；
 * - 数据源不走 unified patch 解析：repo 层已有 showFileContent / indexFileContent / worktreeFileContent
 *   三个内容读取口，直接取旧/新两份全文用行级 LCS（[LineDiffCore]）算差异，与 aicode
 *   GitViewModel.loadDiff → computeDiff 的「双全文 + LineDiff」路径一致。
 *
 * diff 方向由 [staged] 决定：
 * - staged=true：HEAD → index（已暂存的改动，git diff --cached 语义）；
 * - staged=false：index → worktree（未暂存的改动，git diff 语义）。
 * 任一侧取不到内容（新增文件的首个提交、未跟踪文件）按空串处理，整文件呈现为全增/全删。
 */
internal class GitDiffState(
    private val repository: EtaGitRepository,
    private val scope: CoroutineScope,
    /** 仓库相对路径，route 参数透传。 */
    val filePath: String,
    /** 是否查看暂存区差异（HEAD→index）；false 为工作区差异（index→worktree）。 */
    val staged: Boolean,
    private val logger: AgentLogger? = null,
) {

    /** 一行 diff 的渲染数据：类型 + 文本 + 旧/新行号；行号在状态机里算好，LazyColumn 懒加载下 UI 层不维护可变计数。 */
    data class DiffLineRow(
        val type: LineType,
        val text: String,
        val oldLineNum: Int?,
        val newLineNum: Int?,
    )

    /** 行类型：新增 / 删除 / 上下文。 */
    enum class LineType { ADD, REMOVE, CONTEXT }

    /** diff 页状态；整体不可变，经 [state] 暴露，变更只走 [_state].update。 */
    data class DiffUiState(
        /** 计算中（首次进入与重试时为 true）。 */
        val loading: Boolean = true,
        /** 加载失败原因（非 diff 语义错误，如 proot 被拦截、git fatal），非空时整页显示错误态。 */
        val error: String? = null,
        val rows: List<DiffLineRow> = emptyList(),
        val addedCount: Int = 0,
        val removedCount: Int = 0,
        /** 二进制文件（含 NUL 字节）：不渲染行，整页显示「二进制文件」提示。 */
        val isBinary: Boolean = false,
        /** 超大文件：任一侧超 [MAX_DIFF_LINES] 行 / 单行超 [MAX_DIFF_LINE_LENGTH] / 超引擎字节上限。 */
        val isLarge: Boolean = false,
    )

    private val _state = MutableStateFlow(DiffUiState())
    val state: StateFlow<DiffUiState> = _state.asStateFlow()

    /**
     * 与 GitPanelState 选中的仓库对齐：diff 全屏页持有独立的 [EtaGitRepository] 实例
     * （不共享 GitPanelState 的私有 repository），命令执行目录靠这里同步。
     * 由 AgentAppRoot 在进入页面时用 gitPanelState.state.value.currentRepo 调用。
     */
    fun syncRepoPath(path: String?) {
        runCatching { repository.setRepoPath(path) }
    }

    /** 计算 diff（进页与重试入口）。 */
    fun load() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val outcome = runCatching {
                withContext(Dispatchers.Default) { compute() }
            }
            outcome.fold(
                onSuccess = { data -> _state.value = data },
                onFailure = { e ->
                    when (e) {
                        is GitOutputTooLargeException -> _state.update {
                            it.copy(loading = false, isLarge = true, rows = emptyList())
                        }
                        else -> {
                            logger?.error("GitDiffState: diff 加载失败 $filePath", e)
                            _state.update {
                                it.copy(
                                    loading = false,
                                    error = (e as? GitCommandFailureException)?.output
                                        ?: e.message ?: "diff 加载失败",
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    /** 取旧/新两份全文并做防 OOM 守卫，再交给 [LineDiffCore] 生成带行号的行列表。 */
    private suspend fun compute(): DiffUiState {
        val oldContent = if (staged) repository.showFileContent("HEAD", filePath) else repository.indexFileContent(filePath)
        val newContent = if (staged) repository.indexFileContent(filePath) else repository.worktreeFileContent(filePath)

        // 二进制检测：与 aicode 一致，内容含 NUL 直接降级，不进 LCS。
        if (oldContent.contains('\u0000') || newContent.contains('\u0000')) {
            return DiffUiState(loading = false, isBinary = true)
        }
        val oldLines = oldContent.split('\n')
        val newLines = newContent.split('\n')
        if (oldLines.size > MAX_DIFF_LINES || newLines.size > MAX_DIFF_LINES ||
            oldLines.any { it.length > MAX_DIFF_LINE_LENGTH } ||
            newLines.any { it.length > MAX_DIFF_LINE_LENGTH }
        ) {
            return DiffUiState(loading = false, isLarge = true)
        }

        val diff = LineDiffCore.diff(oldLines, newLines)
        var oldNum = 0
        var newNum = 0
        val rows = diff.map { (type, text) ->
            val nums = when (type) {
                LineType.CONTEXT -> { oldNum++; newNum++; oldNum to newNum }
                LineType.REMOVE -> { oldNum++; oldNum to null }
                LineType.ADD -> { newNum++; null to newNum }
            }
            DiffLineRow(type, text, nums.first, nums.second)
        }
        return DiffUiState(
            loading = false,
            rows = rows,
            addedCount = rows.count { it.type == LineType.ADD },
            removedCount = rows.count { it.type == LineType.REMOVE },
        )
    }

    companion object {
        /** diff 行数上限（照 aicode GitViewModel.MAX_DIFF_LINES）：超过直接降级「文件过大」，防 LCS O(n·m) OOM。 */
        const val MAX_DIFF_LINES = 2000

        /** 单行长度上限（照 aicode）：超长行会让 Compose 文本测量爆炸，同样降级。 */
        const val MAX_DIFF_LINE_LENGTH = 2000
    }
}

/**
 * 行级 LCS 差异核心（自 aicode core/util/LineDiff.kt 移植，2026-11-28）。
 * 输入按行拆好的字符串数组，输出「类型 + 原文」序列；规模上限由 [GitDiffState.MAX_DIFF_LINES] 守卫。
 */
internal object LineDiffCore {

    /** 计算 old → new 的行级差异（LCS 回溯）。 */
    fun diff(old: List<String>, new: List<String>): List<Pair<GitDiffState.LineType, String>> {
        val n = old.size
        val m = new.size
        // dp[i][j] = old[i..] 与 new[j..] 的最长公共子序列长度。
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (old[i] == new[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val out = ArrayList<Pair<GitDiffState.LineType, String>>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> { out.add(GitDiffState.LineType.CONTEXT to old[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add(GitDiffState.LineType.REMOVE to old[i]); i++ }
                else -> { out.add(GitDiffState.LineType.ADD to new[j]); j++ }
            }
        }
        while (i < n) { out.add(GitDiffState.LineType.REMOVE to old[i]); i++ }
        while (j < m) { out.add(GitDiffState.LineType.ADD to new[j]); j++ }
        return out
    }
}
