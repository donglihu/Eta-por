package io.github.mangi.eta.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.core.AppLogEntry
import io.github.mangi.eta.core.AppLogFile
import io.github.mangi.eta.core.AppLogFiles
import io.github.mangi.eta.core.AppLogLevel
import io.github.mangi.eta.core.AppLogView
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowRight

/** 实时刷新间隔：文件日志是轮询读取，2 秒够跟上，也不至于一直占 IO。 */
private const val LogRefreshIntervalMs = 2000L

/**
 * 日志页。布局对齐参考图：
 * 顶部「日志等级」「日志文件」两行 → 搜索 + 复制 → 「显示 N 行」+ 实时滚动开关 → 日志条目。
 *
 * 数据来自 [AppLogFiles]（应用自己的日志文件，最多 3 份），不再读 logcat：
 * 系统缓冲区只留 2 分钟，用户翻不到昨天，而日志文件可以按天回看。
 */
@Composable
internal fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var files by remember { mutableStateOf<List<AppLogFile>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<AppLogFile?>(null) }
    var entries by remember { mutableStateOf<List<AppLogEntry>>(emptyList()) }
    /** null = 全部；非空 = 只显示该等级，不再向上包含。 */
    var selectedLevel by remember { mutableStateOf<AppLogLevel?>(null) }
    var query by remember { mutableStateOf("") }
    var followLatest by remember { mutableStateOf(false) }
    var showLevelSheet by remember { mutableStateOf(false) }
    var showFileSheet by remember { mutableStateOf(false) }
    /** 手动刷新计数器：点一次加一，交给 LaunchedEffect 触发重读，不额外造 scope。 */
    var refreshTick by remember { mutableStateOf(0) }
    /** 切换日志文件计数器：同上，读盘留在 IO 线程。 */
    var fileSwitchTick by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    // 读文件是 IO：之前 load() 直接在 LaunchedEffect 的主线程上 listFiles/read，
    // 日志文件到 512 KB 时会卡住首帧。统一挪到 IO 线程。
    suspend fun load(refreshSelection: Boolean) {
        val snapshot = withContext(Dispatchers.IO) {
            val list = AppLogFiles.listFiles()
            val target = when {
                refreshSelection -> list.firstOrNull()
                else -> selectedFile?.let { current -> list.firstOrNull { it.name == current.name } } ?: list.firstOrNull()
            }
            list to (target to (target?.let { AppLogFiles.read(it.file) } ?: emptyList()))
        }
        files = snapshot.first
        selectedFile = snapshot.second.first
        entries = snapshot.second.second
    }

    LaunchedEffect(Unit) { load(refreshSelection = true) }

    // 「刷新」按钮：只重读当前文件，不换选中项。用计数触发，不引 rememberCoroutineScope。
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) load(refreshSelection = false)
    }

    // 切换日志文件：选中项已写 state，这里按它读盘。
    LaunchedEffect(fileSwitchTick) {
        if (fileSwitchTick > 0) {
            val target = selectedFile ?: return@LaunchedEffect
            entries = withContext(Dispatchers.IO) { AppLogFiles.read(target.file) }
            runCatching { listState.scrollToItem(0) }
        }
    }

    // 实时刷新只在开启时轮询；文件被轮转删除时跟着切到最新一份。
    LaunchedEffect(followLatest, selectedFile?.name) {
        while (followLatest) {
            delay(LogRefreshIntervalMs)
            val list = withContext(Dispatchers.IO) { AppLogFiles.listFiles() }
            val current = selectedFile
            val stillThere = current != null && list.any { it.name == current.name }
            if (!stillThere) {
                load(refreshSelection = true)
                continue
            }
            files = list
            entries = current?.let { withContext(Dispatchers.IO) { AppLogFiles.read(it.file) } } ?: emptyList()
            // 最新在最上：新条目出现在 index 0，滚回顶部才看得见。
            // 之前这里滚到 lastIndex，方向反了——列表已经倒序，底部是最旧的。
            runCatching { listState.scrollToItem(0) }
        }
    }

    // 筛选与排序都收敛在 AppLogView.select 里：最新在最上、等级精确匹配。
    val filtered = remember(entries, selectedLevel, query) {
        AppLogView.select(entries = entries, level = selectedLevel, query = query)
    }
    // key 必须在 Composable 作用域算好：LazyListScope 里不是 @Composable，调 remember 会编译失败。
    val filteredKeys = remember(filtered) { AppLogView.keysOf(filtered) }

    MiuixScaffoldPage(
        title = "日志",
        onBack = onBack,
        actions = {
            EtaTextButton(text = "刷新", onClick = { refreshTick += 1 })
        },
        overlay = {
            if (showLevelSheet) {
                OverlayBottomSheet(
                    show = true,
                    title = "日志等级",
                    onDismissRequest = { showLevelSheet = false },
                ) {
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        EtaPreference(
                            title = "全部",
                            summary = "显示所有等级",
                            onClick = { selectedLevel = null; showLevelSheet = false },
                        )
                        EtaPreferenceDivider()
                        AppLogLevel.entries.forEach { level ->
                            EtaPreference(
                                title = level.label,
                                summary = levelHint(level),
                                onClick = { selectedLevel = level; showLevelSheet = false },
                            )
                            EtaPreferenceDivider()
                        }
                    }
                }
            }
            if (showFileSheet) {
                OverlayBottomSheet(
                    show = true,
                    title = "日志文件",
                    onDismissRequest = { showFileSheet = false },
                ) {
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        if (files.isEmpty()) {
                            Text(
                                text = "还没有日志文件。",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                        files.forEachIndexed { index, file ->
                            if (index > 0) EtaPreferenceDivider()
                            EtaPreference(
                                title = file.date,
                                summary = "${file.name} · ${formatSize(file.sizeBytes)}",
                                onClick = {
                                    // 选中即写 state，真正读文件交给 LaunchedEffect 在 IO 线程做。
                                    selectedFile = file
                                    showFileSheet = false
                                    fileSwitchTick += 1
                                },
                            )
                        }
                        // 保留份数是用户明确要求：最多 3 份，超出自动删旧。这里写清规则，不让人猜。
                        Text(
                            text = "最多保留 3 份，超出自动删除最早的。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        item(key = "log-level") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                EtaPreference(
                    title = "日志等级",
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                    onClick = { showLevelSheet = true },
                    onClickLabel = "选择日志等级",
                    endActions = {
                        if (selectedLevel == null) {
                            LevelBadgeText("全部", MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        } else {
                            LevelBadge(selectedLevel!!)
                        }
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                )
                EtaPreferenceDivider()
                EtaPreference(
                    title = "日志文件",
                    summary = selectedFile?.date ?: "还没有日志文件",
                    onClick = { showFileSheet = true },
                    onClickLabel = "选择日志文件",
                    endActions = {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                )
            }
        }

        item(key = "log-search") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = "搜索日志",
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(filtered.joinToString("\n") { it.displayLine }))
                    },
                    modifier = Modifier.width(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "复制当前日志",
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item(key = "log-count") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                EtaPreference(
                    title = "显示 ${filtered.size} 行",
                    summary = when {
                        entries.isEmpty() -> "当前文件没有内容"
                        selectedLevel == null -> "共 ${entries.size} 行，最新在最上，显示 ${filtered.size} 行"
                        else -> "共 ${entries.size} 行，只看 ${selectedLevel!!.label}：${filtered.size} 行"
                    },
                    endActions = {
                        Text(
                            text = "实时滚动",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        EtaSwitch(checked = followLatest, onCheckedChange = { followLatest = it })
                    },
                )
            }
        }

        if (filtered.isEmpty()) {
            item(key = "log-empty") {
                Text(
                    text = if (entries.isEmpty()) {
                        "这个文件里还没有日志。跑一次会话或命令后再回来。"
                    } else if (selectedLevel != null) {
                        "当前文件里没有 ${selectedLevel!!.label} 级别的日志。换一个等级或选「全部」再看。"
                    } else {
                        "当前搜索条件下没有匹配的日志。"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                )
            }
        } else {
            // key 用内容派生：新日志插到顶部时旧行 key 不变，滚动位置不跳。
            items(
                count = filtered.size,
                key = { index -> filteredKeys[index] },
            ) { index ->
                val entry = filtered[index]
                LogLineRow(entry)
            }
        }
    }
}

/** 一行日志：时间 + 等级徽标 + `[tag]` + 正文，等级决定颜色。 */
@Composable
private fun LogLineRow(entry: AppLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = entry.time,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            modifier = Modifier.width(104.dp),
        )
        LevelBadge(entry.level, compact = true)
        Text(
            text = "[${entry.tag}] ${entry.message}",
            style = MiuixTheme.textStyles.footnote2,
            color = levelColor(entry.level),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
        )
    }
}

/** 等级徽标：参考图是「彩色点 + WARN」的胶囊，这里按等级配色。 */
@Composable
private fun LevelBadge(level: AppLogLevel, compact: Boolean = false) {
    LevelBadgeText(level.label, levelColor(level), compact)
}

/** 「全部」也要有个徽标，否则选完之后界面上看不出当前是什么筛选状态。 */
@Composable
private fun LevelBadgeText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    compact: Boolean = false,
) {
    Box(
        modifier = Modifier
            .padding(horizontal = if (compact) 0.dp else 6.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun levelColor(level: AppLogLevel): androidx.compose.ui.graphics.Color = when (level) {
    AppLogLevel.ERROR -> MiuixTheme.colorScheme.error
    AppLogLevel.WARN -> androidx.compose.ui.graphics.Color(0xFFFF7700)
    AppLogLevel.INFO -> androidx.compose.ui.graphics.Color(0xFF2BA24C)
    AppLogLevel.DEBUG -> MiuixTheme.colorScheme.primary
    AppLogLevel.VERBOSE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

private fun levelHint(level: AppLogLevel): String = when (level) {
    AppLogLevel.VERBOSE -> "只看 VERBOSE"
    AppLogLevel.DEBUG -> "只看 DEBUG"
    AppLogLevel.INFO -> "只看 INFO"
    AppLogLevel.WARN -> "只看 WARN"
    AppLogLevel.ERROR -> "只看 ERROR"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "${bytes} B"
}