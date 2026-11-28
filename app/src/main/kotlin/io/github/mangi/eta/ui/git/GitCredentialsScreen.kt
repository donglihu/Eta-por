package io.github.mangi.eta.ui.git

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixBackButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 凭据与署名独立页（自 aicode feature/credentials/presentation/component/CredentialScreen.kt 移植，2026-11-28）。
 *
 * 布局与交互（对齐 aicode）：
 * - 顶栏：返回 + 「凭据与署名」标题 + 右上「+」（弹出添加方式弹层）；
 * - 主体 HorizontalPager 两 tab + 底部悬浮页签条（署名 / 凭据），页签条版式与 [GitScreen] 一致
 *   （该文件里是 private 组件，本页持独立副本，视觉参数相同）；
 * - 署名 tab：[GitUserIdentityCard] 三输入框（user.name / user.email / 远程地址）+ 保存；
 * - 凭据 tab：空态文案 → 凭据列表（每 host 一行，点击编辑，删除带确认，计数分组头）；
 * - 「+」→ 添加方式弹层（GitHub 快捷登录 / 手动添加）→ 手动走 [CredentialEditorSheet]，
 *   GitHub 走 [GitHubQuickLoginSheet]（设备码流四态，状态机见 [GitCredentialState]）。
 *
 * ETA 纪律：组件库 Miuix（无 material3）；toast 用系统 Toast；翻页动画用 pendingPage +
 * LaunchedEffect 桥接（同 GitScreen，禁 rememberCoroutineScope）。
 */
@Composable
internal fun GitCredentialsScreen(
    stateMachine: GitCredentialState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by stateMachine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 进页触发加载（每次进入都重读：用户可能在终端改过署名或凭据文件）。
    LaunchedEffect(Unit) { stateMachine.load() }
    // toast → 系统 Toast 一次性消费。
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            stateMachine.consumeToast()
        }
    }

    // 两 tab 滚动态提升到页面层，聚合「是否滚动中」用于底部页签条弱化（同 GitScreen）。
    val identityListState = rememberScrollState()
    val credentialListState = rememberLazyListState()
    val pagerState = rememberPagerState { 2 }
    val tabsScrolling by remember {
        derivedStateOf { identityListState.isScrollInProgress || credentialListState.isScrollInProgress }
    }

    // 「复制并前往授权」：复制设备码 + 拉起系统浏览器打开 verification_uri（无浏览器时静默降级，已复制码仍可手动前往）。
    fun authorizeUri(uri: String) {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
        }
    }

    // 页面层纯 UI 开关：添加方式弹层 / 手动编辑弹层 / 待删除凭据确认。
    var showAddSheet by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<String?>(null) }
    var addingCredential by remember { mutableStateOf(false) }
    var deletingHost by remember { mutableStateOf<String?>(null) }


    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回 + 标题 + 右上「+」。
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixBackButton(onClick = onBack)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.git_credentials_and_identity),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showAddSheet = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.credential_add),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> IdentityTab(
                        state = state,
                        stateMachine = stateMachine,
                        scrollState = identityListState,
                    )
                    else -> CredentialsTab(
                        state = state,
                        stateMachine = stateMachine,
                        listState = credentialListState,
                        onEdit = { editingHost = it },
                        onDeleteRequest = { deletingHost = it },
                    )
                }
            }
            // 底部悬浮页签条（渐变蒙版在 GitScreen 由内容渐变实现；本页直接叠底）。
            CredentialsFloatingTabBar(
                pagerState = pagerState,
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    }

    // 手动编辑弹层：editingHost 非空 → 编辑已有凭据；addingCredential → 新增。
    val editTarget = state.credentials.firstOrNull { it.host == editingHost }
    if (editTarget != null) {
        CredentialEditorSheet(
            title = stringResource(R.string.credential_edit),
            initialHost = editTarget.host,
            initialUsername = editTarget.username,
            busy = state.busy,
            onDismiss = { editingHost = null },
            onSave = { host, username, token ->
                stateMachine.saveCredential(host, username, token)
                editingHost = null
            },
        )
    }
    if (addingCredential) {
        CredentialEditorSheet(
            title = stringResource(R.string.credential_add),
            initialHost = "",
            initialUsername = "",
            busy = state.busy,
            onDismiss = { addingCredential = false },
            onSave = { host, username, token ->
                stateMachine.saveCredential(host, username, token)
                addingCredential = false
            },
        )
    }
    // 添加方式弹层。
    if (showAddSheet) {
        OverlayBottomSheet(
            show = true,
            title = stringResource(R.string.credential_add_sheet_title),
            onDismissRequest = { showAddSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CredentialAddRow(
                    icon = Icons.Rounded.Person,
                    title = stringResource(R.string.credential_add_github_title),
                    subtitle = stringResource(R.string.credential_add_github_desc),
                    onClick = {
                        showAddSheet = false
                        stateMachine.startQuickLogin()
                    },
                )
                CredentialAddRow(
                    icon = Icons.Rounded.Key,
                    title = stringResource(R.string.credential_add_manual_title),
                    subtitle = stringResource(R.string.credential_add_manual_subtitle),
                    onClick = {
                        showAddSheet = false
                        addingCredential = true
                    },
                )
            }
        }
    }
    // 快捷登录四态弹层。
    if (state.quickLogin !is GitCredentialState.QuickLoginState.Idle) {
        GitHubQuickLoginSheet(
            state = state.quickLogin,
            onCancel = { stateMachine.cancelQuickLogin() },
            onAuthorize = { uri -> authorizeUri(uri) },
            onRetry = { stateMachine.startQuickLogin() },
            onCopyCode = { code ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("device-code", code),
                )
                Toast.makeText(context, R.string.credential_device_code_copied, Toast.LENGTH_SHORT).show()
            },
            onSuccessDismiss = { stateMachine.consumeQuickLogin() },
        )
    }
    // 删除确认弹窗。
    val deleteTarget = deletingHost
    if (deleteTarget != null) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.credential_delete_confirm_title),
            summary = stringResource(R.string.credential_delete_confirm, deleteTarget),
            onDismissRequest = { deletingHost = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EtaTextButton(
                    text = stringResource(R.string.credential_cancel),
                    onClick = { deletingHost = null },
                    modifier = Modifier.weight(1f),
                )
                EtaTextButton(
                    text = stringResource(R.string.credential_delete),
                    onClick = {
                        stateMachine.deleteCredential(deleteTarget)
                        deletingHost = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 署名 tab：三输入框分组卡片 + 保存按钮（对齐 aicode GitUserIdentityCard）。 */
@Composable
private fun IdentityTab(
    state: GitCredentialState.CredentialUiState,
    stateMachine: GitCredentialState,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    var name by remember(state.userName) { mutableStateOf(state.userName) }
    var email by remember(state.userEmail) { mutableStateOf(state.userEmail) }
    var repoUrl by remember(state.repoUrl) { mutableStateOf(state.repoUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(bottom = 70.dp),
    ) {
        Text(
            text = stringResource(R.string.identity_group_signature),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.identity_desc),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            CredentialField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.identity_user_name),
            )
            CredentialField(
                value = email,
                onValueChange = { email = it },
                label = stringResource(R.string.identity_user_email),
                keyboardType = KeyboardType.Email,
            )
        }
        Text(
            text = stringResource(R.string.identity_group_repo),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .padding(12.dp),
        ) {
            CredentialField(
                value = repoUrl,
                onValueChange = { repoUrl = it },
                label = stringResource(R.string.identity_repo_url),
            )
        }
        EtaTextButton(
            text = stringResource(R.string.identity_save),
            onClick = { stateMachine.saveIdentity(name, email, repoUrl) },
            enabled = !state.identitySaving,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

/** 凭据 tab：空态 / 列表（计数分组头 + 每行 host+username，点击编辑，长按删除走确认弹窗）。 */
@Composable
private fun CredentialsTab(
    state: GitCredentialState.CredentialUiState,
    stateMachine: GitCredentialState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onEdit: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
) {
    if (state.credentials.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 70.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.credential_empty_title),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.credential_empty_desc),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 70.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "credential-header") {
            Text(
                text = stringResource(R.string.credential_count, state.credentials.size),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            )
        }
        items(state.credentials.size, key = { state.credentials[it].host }) { index ->
            val credential = state.credentials[index]
            CredentialRow(
                credential = credential,
                onClick = { onEdit(credential.host) },
                onDelete = { onDeleteRequest(credential.host) },
                busy = state.busy,
            )
        }
    }
}

/** 凭据列表单行：图标方块 + host（主）+ username（次）+ 删除小按钮；整行点击编辑。 */
@Composable
private fun CredentialRow(
    credential: GitCredentialState.CredentialUi,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = credential.host,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (credential.username.isNotBlank()) {
                Text(
                    text = credential.username,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(R.string.credential_delete),
            color = MiuixTheme.colorScheme.error,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !busy, onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** 添加方式弹层的单行入口：图标 + 标题 + 说明（对齐 aicode CredentialAddSheet 的行版式）。 */
@Composable
private fun CredentialAddRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = title, color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 手动凭据编辑弹层（新增/编辑共用）：host / username / token 三表单 + 保存。
 * token 输入默认掩码显示，可点右侧「显示」切换；保存交 [GitCredentialState.saveCredential] 合并写。
 */
@Composable
private fun CredentialEditorSheet(
    title: String,
    initialHost: String,
    initialUsername: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (host: String, username: String, token: String) -> Unit,
) {
    var host by remember(initialHost) { mutableStateOf(initialHost) }
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var token by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    val canSave = host.trim().isNotBlank() && username.trim().isNotBlank() && token.isNotBlank()

    OverlayBottomSheet(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CredentialField(
                value = host,
                onValueChange = { host = it },
                label = stringResource(R.string.credential_host),
                placeholder = "github.com",
            )
            CredentialField(
                value = username,
                onValueChange = { username = it },
                label = stringResource(R.string.credential_username),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.credential_token),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = token,
                            onValueChange = { token = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            visualTransformation = if (tokenVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(if (tokenVisible) R.string.credential_hide else R.string.credential_show),
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { tokenVisible = !tokenVisible }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            EtaTextButton(
                text = stringResource(R.string.identity_save),
                onClick = { onSave(host.trim(), username.trim(), token) },
                enabled = canSave && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * GitHub 快捷登录四态弹层（对齐 aicode GitHubDeviceFlowSheet 的状态组织）：
 * 请求中（spinner）/ 等待授权（user_code 大字 + verification_uri + 复制/前往授权）/
 * 成功（延时收起）/ 失败（原因 + 重试）。状态由 [GitCredentialState] 映射 GitHubAccountManager 产生。
 */
@Composable
private fun GitHubQuickLoginSheet(
    state: GitCredentialState.QuickLoginState,
    onCancel: () -> Unit,
    /** 「复制并前往授权」：页面层复制设备码并拉起浏览器打开 verification_uri。 */
    onAuthorize: (String) -> Unit,
    onRetry: () -> Unit,
    onCopyCode: (String) -> Unit,
    onSuccessDismiss: () -> Unit,
) {
    // 成功后短暂展示反馈再收起（对齐 aicode 的 1400ms 延时）。
    LaunchedEffect(state) {
        if (state is GitCredentialState.QuickLoginState.Success) {
            kotlinx.coroutines.delay(1400)
            onSuccessDismiss()
        }
    }
    OverlayBottomSheet(
        show = true,
        title = stringResource(R.string.credential_device_title),
        onDismissRequest = onCancel,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.credential_device_subtitle),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
            )
            when (state) {
                GitCredentialState.QuickLoginState.Idle -> Unit
                GitCredentialState.QuickLoginState.Requesting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.credential_device_requesting),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
                is GitCredentialState.QuickLoginState.Awaiting -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.credential_device_instruction),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    )
                    // 设备码卡片：大字 monospace，点击复制。
                    Text(
                        text = state.userCode,
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainer)
                            .clickable { onCopyCode(state.userCode) }
                            .padding(vertical = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        text = state.verificationUri,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EtaTextButton(
                            text = stringResource(R.string.credential_device_copy),
                            onClick = { onCopyCode(state.userCode) },
                        )
                        EtaTextButton(
                            text = stringResource(R.string.credential_device_authorize),
                            onClick = {
                                onCopyCode(state.userCode)
                                onAuthorize(state.verificationUri)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.credential_device_waiting),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                }
                is GitCredentialState.QuickLoginState.Success -> Text(
                    text = stringResource(R.string.credential_device_success, state.login),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
                is GitCredentialState.QuickLoginState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = state.message,
                        color = MiuixTheme.colorScheme.error,
                        fontSize = 14.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EtaTextButton(
                            text = stringResource(R.string.credential_cancel),
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                        )
                        EtaTextButton(
                            text = stringResource(R.string.credential_retry),
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底部悬浮页签条（署名/凭据两 pill）：视觉参数与 GitScreen 的 FloatingTabBar 一致；
 * 点击翻页用 pendingPage + LaunchedEffect 桥接（禁 rememberCoroutineScope）。
 */
@Composable
private fun CredentialsFloatingTabBar(
    pagerState: androidx.compose.foundation.pager.PagerState,
    isScrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        Icons.Rounded.Person to stringResource(R.string.git_tab_identity),
        Icons.Rounded.Key to stringResource(R.string.git_tab_credentials),
    )
    var pendingPage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pendingPage) {
        if (pendingPage >= 0) {
            pagerState.animateScrollToPage(pendingPage)
            pendingPage = -1
        }
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = if (isScrolling) 0.55f else 0.94f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, (icon, label) ->
            val selected = pagerState.currentPage == index
            val tint by animateColorAsState(
                targetValue = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                label = "credential-tab-tint",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { pendingPage = index }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(15.dp))
                Text(text = label, color = tint, fontSize = 12.sp)
            }
        }
    }
}

/**
 * 表单输入框：surfaceContainer 底 + 圆角 + 顶部小标签（BasicTextField 无 hint，
 * placeholder 用叠加文本实现，同 GitScreen.DialogTextField 手法）。
 */
@Composable
private fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    text = placeholder,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
