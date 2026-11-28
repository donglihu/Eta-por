package io.github.mangi.eta.agent.git

/**
 * git 命令以非零退出码结束（真实失败），区别于进程异常崩溃。
 * 携带 git 自身输出的文本（stderr 优先），UI 据此把「成功」误报纠正为「失败 + 原因」。
 * 代表场景：未配置署名提交、未授权、合并冲突、无远程等。
 * 自 aicode 同名文件移植（2026-11-28），去 Hilt、改包名，语义不变。
 *
 * @param output git stdout+stderr 合并文本，作 [message] 供 UI 展示与日志排查。
 */
open class GitCommandFailureException(val output: String) : Exception(output)

/**
 * git 输出超过引擎上限被截断。继承 [GitCommandFailureException]，既有的失败处理路径
 * （toast）自动生效；需要完整内容的调用方单独捕获它并降级为「输出过大」提示，
 * 而不是拿半截数据解析——半截 porcelain/diff 数据会被解析成伪造的文件行。
 */
class GitOutputTooLargeException : GitCommandFailureException("输出过大，已截断，请缩小范围后重试")

/**
 * proot（非 root）后端下执行 git 写操作的拦截异常。
 * 必须在执行前抛出而不是等 git 失败：proot 里凭据（credential.helper=store 写在 /root）与
 * 工作区绑定根（projects 目录）都不可达，放行只会得到含混的 fatal 输出，等于静默失败。
 */
class GitProotUnsupportedException : GitCommandFailureException(
    "当前 Linux 环境为 proot（非 root）：既读不到 git 凭据（存于 chroot 的 /root），也看不到 " +
        "工作区绑定根（projects 目录）不可达，git 写操作不可用；请在终端设置切换到 chroot 后端后重试"
)

/**
 * 仓库发现超时：发现命令由引擎层显式收口（10s，ShellProcessSupervisor waitFor 超时杀进程树，
 * 退出码约定 -2）。区别于普通命令失败——UI 走「加载超时 + 重试」文案，
 * 不把超时吞成「exitCode!=0 且输出为空 → 非仓库」。
 */
class RepoDiscoveryTimeoutException : GitCommandFailureException("仓库发现超时，请检查 Linux 环境后重试")
