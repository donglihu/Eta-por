package io.github.mangi.eta.ui.git

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.github.GitHubAccountManager
import io.github.mangi.eta.agent.github.GitHubAccountState
import io.github.mangi.eta.agent.git.EtaGitEngine
import io.github.mangi.eta.agent.git.EtaGitRepository
import io.github.mangi.eta.agent.git.GitCommandFailureException
import io.github.mangi.eta.core.AgentLogger
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 凭据与署名页（AppRoute.GitCredentials）的 UI 状态机（自 aicode CredentialViewModel 思路移植，2026-11-28）。
 *
 * 两个真源：
 * - 署名 = git config（local 优先 global 回退），读写走 [EtaGitRepository] 的
 *   getUserName/getUserEmail/setUserIdentity/setRepoUrl（chroot 内 git config，
 *   落 /root/.gitconfig，与 GitHubTerminalSync 写入的 [user] 兜底段落点相同、语义兼容：
 *   git config 按 key 覆盖，不会复制出重复段落）；
 * - 凭据 = Linux rootfs 的 /root/.git-credentials（git-credential-store 标准格式，
 *   每行 https://user:pass@host）。App UID 不可直读 rootfs，读写一律经 [EtaGitEngine]
 *   的 raw 通道进 chroot。GitHub.com 行由 agent/github/GitHubTerminalSync 自动写入（单账号），
 *   本页的多 host 存储与它共存：**写前必读旧内容、按 host 行合并、绝不整文件覆盖丢掉别的行**
 *   （已知限制：GitHubTerminalSync 自身是整文件覆盖写，在 GitHub 账号页重连仍可能抹掉其他
 *   host 行——那是它的既有行为，本状态机对它写下的 github.com 行始终保留）。
 *
 * GitHub 快捷登录不重新实现设备码流：直接驱动 [GitHubAccountManager]（设备码流 + Keystore
 * 存储 + 终端同步开关），把它的连接状态映射为本页四态（请求中/等待授权/成功/失败重试）；
 * Connected 后确保 /root/.git-credentials 里有 github.com 凭据行（同步开关关闭时由这里补写）。
 */
internal class GitCredentialState(
    private val context: Context,
    private val repository: EtaGitRepository,
    private val engine: EtaGitEngine,
    private val scope: CoroutineScope,
    private val logger: AgentLogger? = null,
) {

    /** 凭据列表行 UI 模型：host + username（token 永不出现在 UI 模型，编辑时留空=沿用旧值）。 */
    data class CredentialUi(val host: String, val username: String)

    /** GitHub 快捷登录四态（对齐 aicode QuickLoginUiState：请求中/等待授权/成功/失败重试）。 */
    sealed interface QuickLoginState {
        data object Idle : QuickLoginState
        data object Requesting : QuickLoginState
        data class Awaiting(val userCode: String, val verificationUri: String) : QuickLoginState
        data class Success(val login: String) : QuickLoginState
        data class Failed(val message: String) : QuickLoginState
    }

    /** 凭据与署名页状态；整体不可变，经 [state] 暴露，变更只走 [_state].update。 */
    data class CredentialUiState(
        /** 首次加载中。 */
        val loading: Boolean = true,
        /** 加载失败原因（环境未就绪 / proot / git fatal），非空时整页显示错误态 + 重试。 */
        val loadError: String? = null,
        val credentials: List<CredentialUi> = emptyList(),
        /** 署名三输入框回显值（git config 实际生效值）。 */
        val userName: String = "",
        val userEmail: String = "",
        val repoUrl: String = "",
        /** 署名保存中。 */
        val identitySaving: Boolean = false,
        /** 凭据写操作（增/删）执行中，守卫并发。 */
        val busy: Boolean = false,
        /** 一次性 toast 文案，展示后由 [consumeToast] 清位。 */
        val toast: String? = null,
        val quickLogin: QuickLoginState = QuickLoginState.Idle,
    )

    private val _state = MutableStateFlow(CredentialUiState())
    val state: StateFlow<CredentialUiState> = _state.asStateFlow()

    /** GitHubAccountManager 状态订阅句柄：快捷登录期间挂上，消费完结果即摘除。 */
    private var quickLoginJob: Job? = null

    /**
     * 与 GitPanelState 选中的仓库对齐（独立 repository 实例，理由见 [GitDiffState.syncRepoPath]）；
     * 署名读写按 local 优先落到当前选中仓库。
     */
    fun syncRepoPath(path: String?) {
        runCatching { repository.setRepoPath(path) }
    }

    /** 进页/重试加载：环境守卫 → 读署名回显 → 读凭据列表。任何失败置 [CredentialUiState.loadError]。 */
    fun load() {
        scope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            val hint = engine.notReadyHint()
            if (hint != null) {
                _state.update { it.copy(loading = false, loadError = hint) }
                return@launch
            }
            if (engine.proot) {
                _state.update { it.copy(loading = false, loadError = PROOT_HINT) }
                return@launch
            }
            val identity = runCatching {
                Triple(repository.getUserName(), repository.getUserEmail(), repository.getRepoUrl())
            }
            val credentials = runCatching { readCredentials() }
            identity.fold(
                onSuccess = { (name, email, url) ->
                    _state.update {
                        it.copy(
                            userName = name,
                            userEmail = email,
                            repoUrl = url,
                        )
                    }
                },
                onFailure = { e ->
                    logger?.error("GitCredentialState: 读取署名失败", e)
                    _state.update { it.copy(loadError = describe(e)) }
                },
            )
            credentials.fold(
                onSuccess = { list -> _state.update { it.copy(credentials = list) } },
                onFailure = { e ->
                    logger?.error("GitCredentialState: 读取凭据失败", e)
                    _state.update { it.copy(loadError = describe(e)) }
                },
            )
            _state.update { it.copy(loading = false) }
        }
    }

    /** 保存署名（user.name / user.email 走 setUserIdentity 的 local 优先逻辑；remote.origin.url 走 setRepoUrl）。 */
    fun saveIdentity(name: String, email: String, repoUrl: String) {
        scope.launch {
            _state.update { it.copy(identitySaving = true) }
            runCatching {
                repository.setUserIdentity(name.trim(), email.trim())
                repository.setRepoUrl(repoUrl.trim())
            }.fold(
                onSuccess = {
                    _state.update { it.copy(identitySaving = false, toast = IDENTITY_SAVED) }
                },
                onFailure = { e ->
                    logger?.error("GitCredentialState: 保存署名失败", e)
                    _state.update {
                        it.copy(identitySaving = false, toast = "署名保存失败：${describe(e)}")
                    }
                },
            )
        }
    }

    /**
     * 保存凭据（新增或编辑同一 host）：读旧文件 → 合并本 host 行 → 合并写回。
     * 合并写绝不丢其他 host 行（含 GitHubTerminalSync 的 github.com 行），见类注释。
     */
    fun saveCredential(host: String, username: String, token: String) {
        val h = host.trim().trimEnd('/')
        if (h.isBlank()) {
            _state.update { it.copy(toast = "保存失败：host 不能为空") }
            return
        }
        scope.launch {
            if (!guardWritable()) return@launch
            _state.update { it.copy(busy = true) }
            runCatching {
                val oldLines = readRawCredentialLines()
                val newLine = "https://${encodeUserInfo(username.trim())}:${encodeUserInfo(token)}@$h"
                val merged = oldLines.filterNot { hostOf(it).equals(h, ignoreCase = true) } + newLine
                writeCredentialsFile(merged)
            }.fold(
                onSuccess = {
                    _state.update { it.copy(busy = false, toast = CREDENTIAL_SAVED) }
                    load()
                },
                onFailure = { e ->
                    logger?.error("GitCredentialState: 保存凭据失败", e)
                    _state.update { it.copy(busy = false, toast = "凭据保存失败：${describe(e)}") }
                },
            )
        }
    }

    /** 删除指定 host 的凭据行：读旧文件 → 滤掉该 host → 合并写回（其余行原样保留）。 */
    fun deleteCredential(host: String) {
        scope.launch {
            if (!guardWritable()) return@launch
            _state.update { it.copy(busy = true) }
            runCatching {
                val merged = readRawCredentialLines().filterNot { hostOf(it).equals(host, ignoreCase = true) }
                writeCredentialsFile(merged)
            }.fold(
                onSuccess = {
                    _state.update { it.copy(busy = false, toast = CREDENTIAL_DELETED) }
                    load()
                },
                onFailure = { e ->
                    logger?.error("GitCredentialState: 删除凭据失败", e)
                    _state.update { it.copy(busy = false, toast = "凭据删除失败：${describe(e)}") }
                },
            )
        }
    }

    /** 消费一次性 toast。 */
    fun consumeToast() = _state.update { it.copy(toast = null) }

    /**
     * 发起 GitHub 快捷登录：驱动 [GitHubAccountManager]（内部设备码流），本状态机只做状态映射；
     * 连接成功后确保 github.com 凭据行存在（终端同步开关关闭时由这里补写，token 从 Keystore 取）。
     */
    fun startQuickLogin() {
        if (quickLoginJob?.isActive == true) return
        quickLoginJob = scope.launch {
            _state.update { it.copy(quickLogin = QuickLoginState.Requesting) }
            // 订阅 manager 的连接状态流并映射为四态；Disconnected 视为回到初始态。
            GitHubAccountManager.state.collect { accountState ->
                when (accountState) {
                    GitHubAccountState.Disconnected -> _state.update {
                        if (it.quickLogin !is QuickLoginState.Idle && it.quickLogin !is QuickLoginState.Success) {
                            it.copy(quickLogin = QuickLoginState.Idle)
                        } else {
                            it
                        }
                    }
                    GitHubAccountState.Connecting -> _state.update {
                        it.copy(quickLogin = QuickLoginState.Requesting)
                    }
                    is GitHubAccountState.Waiting -> _state.update {
                        it.copy(
                            quickLogin = QuickLoginState.Awaiting(
                                userCode = accountState.userCode,
                                verificationUri = accountState.verificationUri,
                            ),
                        )
                    }
                    is GitHubAccountState.Connected -> {
                        ensureGitHubCredentialRow()
                        _state.update { it.copy(quickLogin = QuickLoginState.Success(accountState.login)) }
                    }
                    is GitHubAccountState.Failed -> _state.update {
                        it.copy(quickLogin = QuickLoginState.Failed(accountState.message))
                    }
                }
            }
        }
        GitHubAccountManager.connect()
    }

    /** 取消快捷登录：摘掉订阅并复位 manager（cancel 会按已存账号回落到 Connected/Disconnected）。 */
    fun cancelQuickLogin() {
        quickLoginJob?.cancel()
        quickLoginJob = null
        GitHubAccountManager.cancel()
        _state.update { it.copy(quickLogin = QuickLoginState.Idle) }
    }

    /** 消费成功/失败弹层（成功后由 UI 延时收起时调用）。 */
    fun consumeQuickLogin() {
        quickLoginJob?.cancel()
        quickLoginJob = null
        _state.update { it.copy(quickLogin = QuickLoginState.Idle) }
    }

    /** 写通道前置守卫：环境未就绪 / proot 时置错误并返回 false。 */
    private suspend fun guardWritable(): Boolean {
        engine.notReadyHint()?.let { hint ->
            _state.update { it.copy(busy = false, toast = hint) }
            return false
        }
        if (engine.proot) {
            _state.update { it.copy(busy = false, toast = PROOT_HINT) }
            return false
        }
        return true
    }

    /**
     * 读取凭据文件原始行（/root/.git-credentials，git-credential-store 格式）。
     * 文件不存在（首次使用）时 cat 退出码非零且输出为空 → 返回空列表，不算错误。
     */
    private suspend fun readRawCredentialLines(): List<String> {
        val result = engine.raw("cat /root/.git-credentials 2>/dev/null")
        if (result.exitCode != 0 && result.output.isBlank()) return emptyList()
        return result.output.split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /** 原始行 → UI 列表模型（host + username）；无法解析的行跳过，不让脏行崩掉整页。 */
    private suspend fun readCredentials(): List<CredentialUi> =
        readRawCredentialLines().mapNotNull { line ->
            val host = hostOf(line) ?: return@mapNotNull null
            val userinfo = line.substringAfter("://", "").substringBeforeLast('@')
            val rawUsername = userinfo.substringBefore(':')
            val username = runCatching { URLDecoder.decode(rawUsername, "UTF-8") }.getOrDefault(rawUsername)
            CredentialUi(host = host, username = username)
        }.distinctBy { it.host.lowercase() }

    /** 从一行凭据里取 host；不符合 `scheme://userinfo@host` 结构的行返回 null。 */
    private fun hostOf(line: String): String? {
        val body = line.substringAfter("://", "")
        if (body == line || body.isBlank()) return null
        val at = body.lastIndexOf('@')
        if (at < 0) return null
        val host = body.substring(at + 1).trim()
        return host.takeIf { it.isNotBlank() }
    }

    /**
     * 合并写回凭据文件：整份内容 base64 编码后经 printf | base64 -d 落盘并 chmod 600
     * （与 GitHubTerminalSync 的落盘方式一致：内容不经 shell 明文展开，也没有引号注入面；
     * token 不写任何日志）。写的是「读旧 → 合并 → 全量」的结果：其他 host 的行原样保留，
     * 这是与 GitHubTerminalSync 整文件覆盖写的本质差别。
     */
    private suspend fun writeCredentialsFile(lines: List<String>) {
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
        val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        val cmd = "printf %s '$b64' | base64 -d > /root/.git-credentials && chmod 600 /root/.git-credentials"
        val result = engine.raw(cmd)
        if (result.exitCode != 0) {
            throw GitCommandFailureException(result.output.ifBlank { "写入 /root/.git-credentials 失败（退出码 ${result.exitCode}）" })
        }
    }

    /**
     * GitHub 登录成功后确保 github.com 凭据行存在：文件里已有该 host 行（终端同步已写入）就跳过；
     * 缺失时从 Keystore 账号取 token 补写一行 github.com 的凭据（token 作 userinfo）。
     * 只增不改，不影响其他 host 行。
     */
    private suspend fun ensureGitHubCredentialRow() {
        runCatching {
            val lines = readRawCredentialLines()
            if (lines.any { hostOf(it).equals("github.com", ignoreCase = true) }) return
            val account = io.github.mangi.eta.data.repository.GitHubAccountStore(context).load() ?: return
            val merged = lines + "https://" + CREDENTIAL_USERINFO_SCHEME + encodeUserInfo(account.token) + "@github.com"
            writeCredentialsFile(merged)
        }.onFailure { e ->
            logger?.error("GitCredentialState: 补写 github.com 凭据行失败", e)
        }
    }

    /** 异常 → 用户可读文案。 */
    private fun describe(e: Throwable): String =
        (e as? GitCommandFailureException)?.output ?: e.message ?: e.javaClass.simpleName

    companion object {
        /** proot 后端提示（与 EtaGitEngine 类注释一致：proot 读不到 rootfs 的凭据文件）。 */
        const val PROOT_HINT = "proot 后端读写不了容器内凭据与全局署名，请切换 chroot（Root）后端后重试"

        /** git-credential-store 的 userinfo 前缀：user 固定为 x-access-token（GitHub App/令牌约定）。 */
        private const val CREDENTIAL_USERINFO_SCHEME = "x-access-token" + ":"

        const val IDENTITY_SAVED = "署名与仓库地址已保存"
        const val CREDENTIAL_SAVED = "凭据已保存"
        const val CREDENTIAL_DELETED = "凭据已删除"

        /**
         * userinfo 段百分号编码：git-credential-store 的 user:pass 段里 `/ : @` 等保留字符必须转义，
         * 否则解析时会把密码当 host。空格按 RFC 3986 编成 %20（URLEncoder 的 + 是表单语义，这里不适用）。
         */
        private fun encodeUserInfo(raw: String): String =
            java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    }
}
