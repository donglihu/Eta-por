package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.agent.automation.AutoBackupAlarm
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AutoBackupInterval
import io.github.mangi.eta.data.model.AutoBackupFormat
import io.github.mangi.eta.data.model.AutoBackupKeepCount
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.repository.AutoBackupResult
import io.github.mangi.eta.data.repository.AutoBackupRunner
import io.github.mangi.eta.data.repository.BackupDirectoryCheck
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.EtaFullBackupSummary
import io.github.mangi.eta.data.repository.EtaFullBackupArchive
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream) -> EtaBackupSummary,
    onExportFull: suspend (OutputStream, Boolean) -> EtaFullBackupSummary = { _, _ ->
        error("full backup not wired")
    },
    onImport: suspend (InputStream) -> EtaBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    var autoBusy by remember { mutableStateOf(false) }
    var autoNote by remember { mutableStateOf<String?>(null) }
    // 「最近备份」必须以磁盘上的真实文件为准：记录里的文件名可能已被清理掉或换过目录，
    // 直接显示记录会指向一个根本不存在的文件。
    var lastBackupName by remember { mutableStateOf("") }
    LaunchedEffect(
        settings.autoBackupDirectory,
        settings.autoBackupTreeUri,
        settings.autoBackupLastFileName,
        settings.autoBackupLastAt,
    ) {
        lastBackupName = withContext(Dispatchers.IO) {
            AutoBackupRunner.resolveLastEntry(
                recordedName = settings.autoBackupLastFileName,
                customDirectory = settings.autoBackupDirectory,
                treeUri = settings.autoBackupTreeUri,
            )?.name.orEmpty()
        }
    }
    var showDirDialog by remember { mutableStateOf(false) }
    var dirInput by remember { mutableStateOf("") }
    /** 当前是否已拿到「所有文件访问权限」；对话框打开时与授权返回后刷新。 */
    var allFilesGranted by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var intervalInput by remember { mutableStateOf("") }
    var showKeepDialog by remember { mutableStateOf(false) }
    var keepInput by remember { mutableStateOf("") }

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AndroidAgentLogger.error("Agent backup UI failed: type=${throwable.safeLogType()}\n${throwable.safeStackTrace()}")
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.data_backup_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** 统一导出实现：JSON 与全量 zip 共用一套失败提示与忙碌标记。 */
    fun performExport(uri: Uri, full: Boolean) {
        scope.launch {
            busy = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                if (full) {
                    // 勾了「含完整 Linux 环境」就必须产全量 zip，而不是只写一份 JSON。
                    val summary = output.use { onExportFull(it, true) }
                    // 只看「写完了」不够：流式 zip 被截断时不会抛异常，导入才发现包是坏的。
                    // 这里回读一次尾部结构，确认中央目录与条目数都在，再报成功。
                    val check = EtaFullBackupArchive.verify(context, uri)
                    if (!check.ok) {
                        AndroidAgentLogger.error("Full backup verify failed: bytes=" + check.bytes + " entries=" + check.entries)
                        error("全量包导出后校验失败：" + check.reason)
                    }
                    Toast.makeText(
                        context,
                        "已导出全量包（含 Linux 环境）：${summary.conversationCount} 个会话 / " +
                            "${summary.privateFiles} 个文件 / ${formatBytes(summary.totalBytes)} / " +
                            "${check.entries} 个 zip 条目",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    val summary = output.use { onExport(it) }
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.data_backup_exported,
                            summary.conversationCount,
                            summary.providerCount,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                busy = false
            }
        }
    }

    // MIME 必须与产物一致：全量包用 application/zip，否则部分 ROM 会按 json 追加扩展名，
    // 落成 `.eta-fullbackup.zip.json`，导入侧就认不出来了。
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) performExport(uri, full = false)
    }

    val exportFullLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) performExport(uri, full = true)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    /** 手输绝对路径的保存流程：先真实探测（建目录 + 探针写入），不可用就说明原因，不写「假成功」。 */
    fun saveManualDirectory(value: String) {
        scope.launch {
            val check = withContext(Dispatchers.IO) {
                runCatching { AutoBackupRunner.checkDirectory(value) }
                    .getOrElse {
                        BackupDirectoryCheck.Unusable(
                            value,
                            "检查目录时出错：" + it.safeLogType(),
                            "留空使用默认目录 " + AutoBackupRunner.defaultDirectory().absolutePath,
                        )
                    }
            }
            when (check) {
                is BackupDirectoryCheck.Usable -> {
                    val saved = runCatching {
                        SettingsDataStore.updateSettings {
                            it.copy(
                                autoBackupDirectory = value,
                                autoBackupTreeUri = "",
                                autoBackupTreeLabel = "",
                            )
                        }
                    }
                    if (saved.isFailure) {
                        val error = saved.exceptionOrNull()
                        Toast.makeText(
                            context,
                            "保存备份文件夹失败：" + (error?.message ?: error?.safeLogType()),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    AutoBackupAlarm.refresh(context)
                    Toast.makeText(
                        context,
                        context.getString(R.string.data_backup_dir_saved, check.path),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is BackupDirectoryCheck.Unusable -> {
                    AndroidAgentLogger.warn("Backup directory rejected: reason=" + check.reason)
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.data_backup_dir_invalid,
                            check.path,
                            check.reason,
                            check.suggestion,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** 系统文件夹授权：回调里立刻持久化 URI 权限，再实测一次写入，避免「授权了但写不进」。 */
    val dirTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { error ->
                AndroidAgentLogger.warn("SAF persist failed: type=" + error.safeLogType())
            }
            showDirDialog = false
            scope.launch {
                val check = withContext(Dispatchers.IO) {
                    runCatching { AutoBackupRunner.checkTreeDirectory(context, uri.toString()) }
                        .getOrElse {
                            BackupDirectoryCheck.Unusable(
                                uri.toString(),
                                "检查授权文件夹时出错：" + it.safeLogType(),
                                "重新选择一个文件夹",
                            )
                        }
                }
                val applied = if (check is BackupDirectoryCheck.Usable) {
                    runCatching {
                        SettingsDataStore.updateSettings {
                            it.copy(
                                autoBackupTreeUri = uri.toString(),
                                autoBackupTreeLabel = check.path,
                                autoBackupDirectory = "",
                            )
                        }
                    }.isSuccess
                } else {
                    false
                }
                if (applied) AutoBackupAlarm.refresh(context)
                val text = when (check) {
                    is BackupDirectoryCheck.Usable ->
                        if (applied) {
                            context.getString(R.string.data_backup_dir_saf_saved, check.path)
                        } else {
                            "文件夹已授权，但保存设置失败"
                        }
                    is BackupDirectoryCheck.Unusable ->
                        context.getString(
                            R.string.data_backup_dir_invalid,
                            check.path,
                            check.reason,
                            check.suggestion,
                        )
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 跳系统「所有文件访问权限」页；回来重测手输路径，能写就自动保存。 */
    val allFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        allFilesGranted = Environment.isExternalStorageManager()
        val pending = dirInput.trim()
        if (pending.isNotEmpty()) saveManualDirectory(pending)
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "warning") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaPreference(
                    title = stringResource(R.string.data_backup_warning_title),
                    summary = stringResource(R.string.data_backup_warning_summary),
                )
            }
        }
        item(key = "actions-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.data_backup_actions))
        }
        item(key = "actions-card") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_export),
                    summary = if (busy) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_export_summary)
                    },
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Download,
                            loading = busy,
                        )
                    },
                    onClick = {
                        if (settings.autoBackupIncludeLinux) {
                            exportFullLauncher.launch(defaultFullBackupFileName())
                        } else {
                            exportLauncher.launch(defaultBackupFileName())
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_import),
                    summary = stringResource(R.string.data_backup_import_summary),
                    enabled = !busy,
                    startAction = {
                        BackupIcon(
                            icon = Icons.Rounded.Description,
                            loading = false,
                        )
                    },
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/zip", "application/octet-stream"))
                    },
                )
            }
        }
        item(key = "auto-backup-title") {
            EtaPreferenceGroupTitle(stringResource(R.string.data_backup_auto))
        }
        item(key = "auto-backup-card") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                EtaSwitchPreference(
                    title = stringResource(R.string.data_backup_auto_enable),
                    summary = stringResource(R.string.data_backup_auto_enable_summary),
                    checked = settings.autoBackupEnabled,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(autoBackupEnabled = value) }
                            if (value) AutoBackupAlarm.refresh(context) else AutoBackupAlarm.cancel(context)
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_interval),
                    summary = intervalLabel(context, settings.autoBackupIntervalMinutes),
                    enabled = settings.autoBackupEnabled,
                    onClick = {
                        intervalInput = AutoBackupInterval
                            .effectiveMinutes(settings.autoBackupIntervalMinutes, settings.autoBackupIntervalHours)
                            .toString()
                        showIntervalDialog = true
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_keep),
                    summary = stringResource(R.string.data_backup_auto_keep_value, settings.autoBackupKeepCount),
                    enabled = settings.autoBackupEnabled,
                    onClick = {
                        keepInput = settings.autoBackupKeepCount.toString()
                        showKeepDialog = true
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_format),
                    summary = if (AutoBackupFormat.isFull(settings.autoBackupFormat)) {
                        stringResource(R.string.data_backup_auto_format_full_summary)
                    } else {
                        stringResource(R.string.data_backup_auto_format_json_summary)
                    },
                    enabled = settings.autoBackupEnabled,
                    startAction = {
                        BackupIcon(icon = Icons.Rounded.Description, loading = false)
                    },
                    onClick = {
                        val next = if (AutoBackupFormat.isFull(settings.autoBackupFormat)) {
                            AutoBackupFormat.JSON
                        } else {
                            AutoBackupFormat.FULL
                        }
                        scope.launch {
                            SettingsDataStore.updateSettings { it.copy(autoBackupFormat = next) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_auto_format_saved,
                                    if (AutoBackupFormat.isFull(next)) {
                                        context.getString(R.string.data_backup_auto_format_full)
                                    } else {
                                        context.getString(R.string.data_backup_auto_format_json)
                                    },
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaSwitchPreference(
                    title = "含完整 Linux 环境",
                    summary = if (AutoBackupFormat.isFull(settings.autoBackupFormat)) {
                        "自动备份与手动导出都连同 Linux 容器（已装工具、工作区文件）一起打包，体积会大很多"
                    } else {
                        "勾选后自动备份与手动导出都会强制走全量包（含 Linux 容器），不再是轻量 JSON"
                    },
                    checked = settings.autoBackupIncludeLinux,
                    onCheckedChange = { value ->
                        scope.launch {
                            SettingsDataStore.updateSettings { it.copy(autoBackupIncludeLinux = value) }
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_run_now),
                    summary = autoNote ?: when {
                        settings.autoBackupLastError.isNotBlank() -> stringResource(
                            R.string.data_backup_auto_error,
                            settings.autoBackupLastError,
                        )
                        lastBackupName.isNotBlank() -> stringResource(R.string.data_backup_auto_last_file, lastBackupName)
                        else -> stringResource(R.string.data_backup_auto_never)
                    },
                    enabled = !autoBusy && !busy,
                    startAction = {
                        BackupIcon(icon = Icons.Rounded.Download, loading = autoBusy)
                    },
                    onClick = {
                        if (!autoBusy) {
                            autoBusy = true
                            scope.launch {
                                val result = AutoBackupRunner.maybeRun(context, force = true)
                                val text = when (result) {
                                    is AutoBackupResult.Done -> context.getString(
                                        R.string.data_backup_auto_done,
                                        result.fileName,
                                        result.conversationCount,
                                        result.kept,
                                    )
                                    is AutoBackupResult.Failed -> context.getString(
                                        R.string.data_backup_auto_failed,
                                        result.message,
                                    )
                                    is AutoBackupResult.Skipped -> context.getString(R.string.data_backup_auto_skipped)
                                }
                                AutoBackupAlarm.refresh(context)
                                autoBusy = false
                                if (result is AutoBackupResult.Done) lastBackupName = result.fileName
                                autoNote = text
                                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                val effectiveDirPath = AutoBackupRunner.directory(settings.autoBackupDirectory).absolutePath
                val customDirPath = settings.autoBackupDirectory.trim()
                val treeLabel = settings.autoBackupTreeLabel.ifBlank { settings.autoBackupTreeUri }
                val customDirRejected = settings.autoBackupTreeUri.isBlank() &&
                    customDirPath.isNotEmpty() &&
                    !AutoBackupRunner.isConfiguredDirectoryEffective(customDirPath)
                EtaArrowPreference(
                    title = stringResource(R.string.data_backup_auto_dir),
                    // 自定义路径存在但用不上时，必须写出来：否则用户以为备份在那儿，实际在别处。
                    summary = when {
                        settings.autoBackupTreeUri.isNotBlank() ->
                            treeLabel + " · " + stringResource(R.string.data_backup_dir_saf)
                        customDirRejected ->
                            effectiveDirPath + " · 自定义路径 " + customDirPath + " 不可写，已回落默认目录"
                        customDirPath.isEmpty() ->
                            effectiveDirPath + " · " + stringResource(R.string.data_backup_dir_default)
                        else ->
                            effectiveDirPath + " · " + stringResource(R.string.data_backup_dir_custom)
                    },
                    onClick = {
                        dirInput = settings.autoBackupDirectory
                        allFilesGranted = Environment.isExternalStorageManager()
                        showDirDialog = true
                    },
                )
            }
        }
    }

    if (showDirDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_dir_dialog_title),
            summary = stringResource(
                R.string.data_backup_dir_dialog_summary,
                AutoBackupRunner.defaultDirectory().absolutePath,
            ),
            onDismissRequest = { showDirDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = dirInput,
                    onValueChange = { dirInput = it },
                    label = stringResource(R.string.data_backup_dir_dialog_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 分区存储下公共根目录里的文件夹（例如 /storage/emulated/0/ETA）用路径写不进去，
                // 走系统文件夹授权才能真写。两个入口并排一行，避免竖排按钮把弹窗撑满。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EtaTextButton(
                        text = stringResource(R.string.data_backup_dir_pick),
                        onClick = { dirTreeLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                        minHeight = 40.dp,
                    )
                    if (!allFilesGranted) {
                        EtaTextButton(
                            text = stringResource(R.string.data_backup_dir_grant_all),
                            onClick = {
                                runCatching {
                                    allFilesLauncher.launch(
                                        Intent(
                                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:" + context.packageName),
                                        ),
                                    )
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        "打开系统授权页失败：" + error.safeLogType(),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            minHeight = 40.dp,
                        )
                    }
                }
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_confirm),
                    onCancel = { showDirDialog = false },
                    onConfirm = {
                        val value = dirInput.trim()
                        showDirDialog = false
                        saveManualDirectory(value)
                    },
                )
            }
        }
    }

    if (showIntervalDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_interval_dialog_title),
            summary = stringResource(
                R.string.data_backup_interval_dialog_summary,
                AutoBackupInterval.MIN_MINUTES,
                AutoBackupInterval.MAX_MINUTES,
            ),
            onDismissRequest = { showIntervalDialog = false },
        ) {
            Column {
                TextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it },
                    label = stringResource(R.string.data_backup_interval_dialog_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_confirm),
                    onCancel = { showIntervalDialog = false },
                    onConfirm = {
                        val minutes = intervalInput.trim().toIntOrNull()
                        showIntervalDialog = false
                        if (minutes == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.data_backup_interval_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val value = AutoBackupInterval.normalize(minutes)
                            scope.launch {
                                SettingsDataStore.updateSettings { it.copy(autoBackupIntervalMinutes = value) }
                                AutoBackupAlarm.refresh(context)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.data_backup_interval_saved, minutesText(context, value)),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
        }
    }

    if (showKeepDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_keep_dialog_title),
            summary = stringResource(
                R.string.data_backup_keep_dialog_summary,
                AutoBackupKeepCount.MIN,
                AutoBackupKeepCount.MAX,
            ),
            onDismissRequest = { showKeepDialog = false },
        ) {
            Column {
                TextField(
                    value = keepInput,
                    onValueChange = { keepInput = it },
                    label = stringResource(R.string.data_backup_keep_dialog_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_confirm),
                    onCancel = { showKeepDialog = false },
                    onConfirm = {
                        val count = keepInput.trim().toIntOrNull()
                        showKeepDialog = false
                        if (count == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.data_backup_keep_invalid),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val value = AutoBackupKeepCount.normalize(count)
                            scope.launch {
                                SettingsDataStore.updateSettings { it.copy(autoBackupKeepCount = value) }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.data_backup_keep_saved, value),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
        }
    }

    if (showImportDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_import_confirm_title),
            summary = stringResource(R.string.data_backup_import_confirm_summary),
            onDismissRequest = {
                if (!busy) {
                    showImportDialog = false
                    pendingImportUri = null
                }
            },
        ) {
            MiuixDialogActions(
                confirmText = if (busy) {
                    stringResource(R.string.data_backup_working)
                } else {
                    stringResource(R.string.action_import)
                },
                destructive = true,
                cancelEnabled = !busy,
                confirmEnabled = !busy,
                onCancel = {
                    showImportDialog = false
                    pendingImportUri = null
                },
                onConfirm = {
                    val uri = pendingImportUri ?: return@MiuixDialogActions
                    showImportDialog = false
                    scope.launch {
                        busy = true
                        try {
                            val input = BufferedInputStream(
                                context.contentResolver.openInputStream(uri)
                                    ?: error(context.getString(R.string.data_backup_file_open_failed)),
                            )
                            val head = ByteArray(4)
                            input.mark(4)
                            val headRead = input.read(head)
                            input.reset()
                            // 全量包是 zip：先解出 eta-backup.json 走正常导入，再把 private/ 铺到暂存区等重启。
                            val isZip = headRead == 4 &&
                                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
                            val summary = if (isZip) {
                                val cache = File(File(context.cacheDir, "fullbackup"), "incoming.zip")
                                cache.parentFile?.mkdirs()
                                input.use { source -> cache.outputStream().use { target -> source.copyTo(target) } }
                                val jsonFile = EtaFullBackupArchive.extractDocument(context, cache.inputStream())
                                val restored = jsonFile.inputStream().use { onImport(it) }
                                // 含 Linux 环境：把 private/ 下的 Linux 容器（files/terminal*）也一并铺到暂存区等重启。
                                EtaFullBackupArchive.stage(context, cache.inputStream(), includeLinuxEnvironment = true)
                                cache.delete()
                                jsonFile.delete()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.data_backup_full_restore_ready),
                                    Toast.LENGTH_LONG,
                                ).show()
                                restored
                            } else {
                                input.use { onImport(it) }
                            }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_imported,
                                    summary.conversationCount,
                                    summary.providerCount,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (throwable: Throwable) {
                            showFailure(throwable)
                        } finally {
                            pendingImportUri = null
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun BackupIcon(icon: ImageVector, loading: Boolean) {
    Box(
        // 与 EtaPreferenceIcon 同宽，正文和分割线起点保持对齐。
        modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            InfiniteProgressIndicator(size = 20.dp)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun intervalLabel(context: Context, minutes: Int): String =
    context.getString(R.string.data_backup_auto_interval_value, minutesText(context, minutes))

/** 把分钟数写成「10 分钟 / 2 小时 / 1 天 / 1 小时 30 分钟」。 */
private fun minutesText(context: Context, minutes: Int): String {
    val value = AutoBackupInterval.normalize(minutes)
    return when {
        value % 1440 == 0 -> context.getString(R.string.data_backup_unit_day, value / 1440)
        value % 60 == 0 -> context.getString(R.string.data_backup_unit_hour, value / 60)
        value < 60 -> context.getString(R.string.data_backup_unit_minute, value)
        else -> context.getString(R.string.data_backup_unit_hour_minute, value / 60, value % 60)
    }
}

private fun defaultBackupFileName(): String =
    "Eta-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.eta-backup.json"

/** 全量包文件名：与 [EtaFullBackupArchive.FULL_SUFFIX] 一致，导入侧据此识别。 */
private fun defaultFullBackupFileName(): String =
    "Eta-fullbackup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}${EtaFullBackupArchive.FULL_SUFFIX}"

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
