package io.github.mangi.eta.agent.git.model

/**
 * Git 领域模型（自 aicode feature/git/domain/model/GitModels.kt 移植，2026-11-28）。
 * 所有字段均由 [io.github.mangi.eta.agent.git.EtaGitRepository] 解析
 * `git status --porcelain` / `git log` / `git for-each-ref` 的输出得到，纯数据、无 Android 依赖。
 */

/**
 * 单个文件的改动：路径 + git status 的 XY 两字符状态码 + 是否已暂存。
 * staged 由 X 列决定；UI 分组（暂存/未暂存/未跟踪）完全依赖这个字段，
 * 不再解析时二次推断——推断错了会把已暂存文件显示进未暂存组，误导用户重复提交。
 */
data class GitFileChange(
    val path: String,
    /** 形如 "M"/"A"/"D"/"R"/"??"，来自 porcelain 输出。 */
    val statusCode: String,
    val staged: Boolean
)

/** 一条提交历史的精简视图：完整哈希 + 短哈希 + 作者 + 相对日期 + 标题。 */
data class GitCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val message: String
)

/** 一个分支：名称 + 是否当前 + 是否远程 + 上游与领先/落后计数（有上游时由 for-each-ref 给出）。 */
data class GitBranch(
    val name: String,
    val current: Boolean,
    val remote: Boolean,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0
)

/** 一个轻量标签：名称 + 指向提交的短哈希。 */
data class GitTag(
    val name: String,
    val shortHash: String
)

/** 一条储藏记录：引用名（stash@{0}）+ 说明 + 相对日期。 */
data class GitStash(
    val index: String,
    val message: String,
    val date: String
)

/** `git status` 的聚合视图：分支跟踪信息 + 分组后的文件改动。 */
data class GitStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val staged: List<GitFileChange>,
    val unstaged: List<GitFileChange>,
    val untracked: List<String>,
    val conflicted: List<GitFileChange> = emptyList(),
    val isMerging: Boolean = false,
    val upstream: String? = null,
    val isDetached: Boolean = false
) {
    /** 是否存在任何待处理改动；提交按钮、空态判断都以此为准，避免四处重复判空。 */
    val hasChanges: Boolean
        get() = staged.isNotEmpty() || unstaged.isNotEmpty() || untracked.isNotEmpty() || conflicted.isNotEmpty()
}

/** Git 面板的三个页签；顺序即 UI 展示顺序（状态/分支/提交）。 */
enum class GitTab { STATUS, BRANCHES, LOG }
