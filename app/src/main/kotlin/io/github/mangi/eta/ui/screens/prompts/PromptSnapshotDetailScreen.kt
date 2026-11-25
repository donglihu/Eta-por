package io.github.mangi.eta.ui.screens.prompts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentRequestSnapshot
import io.github.mangi.eta.agent.model.AgentSnapshotAnalysis
import io.github.mangi.eta.agent.model.SegmentChangeState
import io.github.mangi.eta.agent.model.SnapshotDiff
import io.github.mangi.eta.agent.model.SystemSegmentKind
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaExpandPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 快照详情：概览 → 与基准的差异 → system 分段 → 工具清单。
 *
 * 每一段只显示一行摘要，正文按需展开；差异只给"能得出结论"的聚合，不逐个工具刷屏。
 */
@Composable
internal fun PromptSnapshotDetailScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 当前快照与基准差异：全部在后台线程计算（list/read 是磁盘 IO + 全树 JSON 解析，单份约 107KB），
    // 避免 Activity 重建（SAF 文档选择器打开期间旋转/暗色切换/系统回收）后主线程重算阻塞导致「跳转文件夹卡住」。
    var file by remember { mutableStateOf<File?>(null) }
    var json by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var baseJson by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var diff by remember { mutableStateOf<SnapshotDiff?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var baseName by remember { mutableStateOf(AgentRequestSnapshot.baseName()) }
    var expandedSegment by remember { mutableStateOf<Int?>(null) }
    var showTools by remember { mutableStateOf(false) }
    // 导出目标只用文件名持有，跨 Activity 重建（SAF 文档选择器可能触发）不丢；
    // 重建后文件本体可能已被 prune 淘汰，回调时重新按名取，取不到则按失败处理。
    var pendingExportName by rememberSaveable { mutableStateOf<String?>(null) }
    // SAF 选择器返回的 uri：先暂存，交给 LaunchedEffect 在 suspend 环境里执行 IO 复制，
    // 避免在 rememberLauncherForActivityResult 的普通回调里直接调用 suspend 的 withContext。
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // 后台加载当前快照 + 基准；baseName 变化（点「设为基准/清除」）时因 key 变化自动重算。
    LaunchedEffect(fileName, baseName) {
        val result = withContext(Dispatchers.Default) {
            val current = AgentRequestSnapshot.list().firstOrNull { it.name == fileName }
            val currentJson = current?.let(AgentRequestSnapshot::read)
            val base = if (baseName == null || baseName == fileName || currentJson == null) {
                null
            } else {
                AgentRequestSnapshot.list().firstOrNull { it.name == baseName }?.let(AgentRequestSnapshot::read)
            }
            val diffResult = if (base != null && currentJson != null) {
                AgentSnapshotAnalysis.diff(base, currentJson)
            } else {
                null
            }
            SnapshotDetailData(current, currentJson, base, diffResult)
        }
        file = result.file
        json = result.json
        baseJson = result.baseJson
        diff = result.diff
        loaded = true
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        // 只暂存 uri，实际导出交给 LaunchedEffect(pendingExportUri) 在协程里做 IO，不阻塞主线程。
        pendingExportUri = uri
    }

    // 导出执行器：suspend 环境，取文件（Default）→ 流式复制（IO）→ 回主线程 toast。
    LaunchedEffect(pendingExportUri) {
        val uri = pendingExportUri ?: return@LaunchedEffect
        val targetName = pendingExportName
        pendingExportName = null
        pendingExportUri = null
        if (targetName == null) {
            toast(context, context.getString(R.string.prompt_snapshot_export_failed))
            return@LaunchedEffect
        }
        // 重建后文件本体可能已被 prune 淘汰，重新按名取（后台线程，避免主线程 IO）。
        val target = withContext(Dispatchers.Default) {
            AgentRequestSnapshot.list().firstOrNull { it.name == targetName }
        }
        if (target == null) {
            toast(context, context.getString(R.string.prompt_snapshot_export_failed))
            return@LaunchedEffect
        }
        val ok = runCatching {
            val output = context.contentResolver.openOutputStream(uri)
            if (output == null) {
                false
            } else {
                // 流式复制在 IO 线程：避免主线程分配/复制整个文件的 byte[]，也避免阻塞 SAF 返回后的界面。
                withContext(Dispatchers.IO) {
                    target.inputStream().use { input -> output.use { input.copyTo(it) } }
                }
                true
            }
        }.getOrDefault(false)
        toast(context, context.getString(if (ok) R.string.prompt_snapshot_exported else R.string.prompt_snapshot_export_failed))
    }

    if (!loaded) {
        MiuixScaffoldPage(title = stringResource(R.string.prompt_snapshot_detail_title), onBack = onBack) {
            item(key = "loading") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_snapshot_loading),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        return
    }

    if (json == null) {
        MiuixScaffoldPage(title = stringResource(R.string.prompt_snapshot_detail_title), onBack = onBack) {
            item(key = "missing") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_snapshot_missing),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        return
    }
    // json 是 delegated property，不能 smart cast；用本地非空引用，编译器才能推断 JSONObject。
    val jsonData = json ?: return

    val counts = jsonData.optJSONObject("counts")
    val system = jsonData.optJSONArray("system")
    val tools = jsonData.optJSONArray("tools")

    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_snapshot_detail_title),
        onBack = onBack,
    ) {
        item(key = "overview_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_overview_group))
        }
        item(key = "overview") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.prompt_snapshot_row_title,
                            formatPromptTime(jsonData.optLong("at")),
                            kindLabel(AgentSnapshotAnalysis.kindOf(jsonData)),
                        ),
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(
                        text = stringResource(
                            R.string.prompt_snapshot_overview_meta,
                            jsonData.optString("model"),
                            counts?.optInt("system") ?: 0,
                            counts?.optInt("system_chars") ?: 0,
                            counts?.optInt("tools") ?: 0,
                            counts?.optInt("tools_chars") ?: 0,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = if (baseName == fileName) {
                        stringResource(R.string.prompt_snapshot_base_clear)
                    } else {
                        stringResource(R.string.prompt_snapshot_base_set)
                    },
                    summary = if (baseName == fileName) null else stringResource(R.string.prompt_snapshot_base_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Compare, tint = EtaPreferenceColors.Green) },
                    onClick = {
                        val next = if (baseName == fileName) null else fileName
                        AgentRequestSnapshot.setBase(next)
                        baseName = next
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = stringResource(R.string.prompt_snapshot_copy_all),
                    summary = stringResource(R.string.prompt_snapshot_copy_all_hint),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.ContentCopy, tint = EtaPreferenceColors.Blue) },
                    onClick = {
                        copyPromptText(context, systemText(system))
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaActionPreference(
                    title = stringResource(R.string.prompt_snapshot_export),
                    summary = file?.name.orEmpty(),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.IosShare, tint = EtaPreferenceColors.Orange) },
                    onClick = {
                        val target = file ?: return@EtaActionPreference
                        pendingExportName = target.name
                        exportLauncher.launch(target.name)
                    },
                )
            }
        }

        if (diff != null) {
            // diff 是 delegated property，不能 smart cast；用本地非空引用（外层 if 已保证非空）。
            val diffData = diff!!
            item(key = "diff_title") {
                EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_diff_group))
            }
            item(key = "diff_summary") {
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = stringResource(
                                R.string.prompt_snapshot_diff_headline,
                                diffData.systemCountAfter - diffData.systemCountBefore,
                                diffData.systemCharsAfter - diffData.systemCharsBefore,
                                diffData.toolsAfter - diffData.toolsBefore,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = EtaPreferenceColors.Blue,
                        )
                        if (diffData.identical) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_diff_same),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        diffData.systemChanges.forEach { change ->
                            Text(
                                text = stringResource(
                                    R.string.prompt_snapshot_diff_segment,
                                    segmentLabel(change.kind),
                                    change.beforeChars,
                                    change.afterChars,
                                    when (change.state) {
                                        SegmentChangeState.ADDED -> stringResource(R.string.prompt_snapshot_diff_added)
                                        SegmentChangeState.REMOVED -> stringResource(R.string.prompt_snapshot_diff_removed)
                                        SegmentChangeState.CHANGED -> stringResource(R.string.prompt_snapshot_diff_changed)
                                    },
                                ),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (diffData.unchangedSegments > 0) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_diff_unchanged, diffData.unchangedSegments),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (diffData.toolsRemoved.isNotEmpty()) {
                        EtaPreferenceDivider(hasLeading = true)
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_diff_tools_removed, diffData.toolsRemoved.size),
                                style = MiuixTheme.textStyles.body2,
                                color = EtaPreferenceColors.Orange,
                            )
                            Text(
                                text = diffData.toolsRemoved.take(NAME_LIMIT).joinToString("、") +
                                    if (diffData.toolsRemoved.size > NAME_LIMIT) {
                                        " 等 ${diffData.toolsRemoved.size} 个"
                                    } else {
                                        ""
                                    },
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (diffData.toolsAdded.isNotEmpty()) {
                        EtaPreferenceDivider(hasLeading = true)
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.prompt_snapshot_diff_tools_added, diffData.toolsAdded.size),
                                style = MiuixTheme.textStyles.body2,
                                color = EtaPreferenceColors.Green,
                            )
                            Text(
                                text = diffData.toolsAdded.take(NAME_LIMIT).joinToString("、"),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (diffData.toolDescRewritten.isNotEmpty()) {
                        EtaPreferenceDivider(hasLeading = true)
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.prompt_snapshot_diff_tools_rewritten,
                                    diffData.toolDescRewritten.size,
                                ),
                                style = MiuixTheme.textStyles.body2,
                            )
                            val rewrittenParts = mutableListOf<String>()
                            for (change in diffData.toolDescRewritten.take(NAME_LIMIT)) {
                                rewrittenParts += stringResource(
                                    R.string.prompt_snapshot_diff_tools_rewritten_item,
                                    change.name,
                                    change.beforeChars,
                                    change.afterChars,
                                )
                            }
                            Text(
                                text = rewrittenParts.joinToString("、"),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        item(key = "system_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_system_group, system?.length() ?: 0))
        }
        items(system?.length() ?: 0, key = { index -> "seg-$index" }) { index ->
            val message = system?.optJSONObject(index) ?: return@items
            val text = message.optString("text")
            val kind = AgentSnapshotAnalysis.classifySegment(text)
            val expanded = expandedSegment == index
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                EtaExpandPreference(
expanded = expanded,
                    title = stringResource(
                        R.string.prompt_snapshot_segment_row,
                        segmentLabel(kind),
                        message.optInt("chars"),
                    ),
                    summary = text.replace('\n', ' ').take(48),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.ViewAgenda, tint = EtaPreferenceColors.Blue) },
                    onClick = { expandedSegment = if (expanded) null else index },
                )
                if (expanded) {
                    EtaPreferenceDivider(hasLeading = true)
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item(key = "tools_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.prompt_snapshot_tools_group, tools?.length() ?: 0))
        }
        item(key = "tools_toggle") {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                EtaExpandPreference(
expanded = showTools,
                    title = stringResource(
                        if (showTools) R.string.prompt_snapshot_collapse
                        else R.string.prompt_snapshot_tools_expand,
                    ),
                    summary = stringResource(
                        R.string.prompt_snapshot_tools_summary,
                        tools?.length() ?: 0,
                        counts?.optInt("tools_chars") ?: 0,
                    ),
                    startAction = { EtaPreferenceIcon(icon = Icons.Rounded.Terminal, tint = EtaPreferenceColors.Orange) },
                    onClick = { showTools = !showTools },
                )
            }
        }
        if (showTools) {
            items(tools?.length() ?: 0, key = { index -> "tool-$index" }) { index ->
                val tool = tools?.optJSONObject(index) ?: return@items
                EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = tool.optString("name"),
                            style = MiuixTheme.textStyles.body2,
                        )
                        Text(
                            text = stringResource(R.string.prompt_snapshot_tool_chars, tool.optInt("chars")),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

private fun systemText(system: org.json.JSONArray?): String = buildString {
    for (index in 0 until (system?.length() ?: 0)) {
        append(system?.optJSONObject(index)?.optString("text").orEmpty())
        append("\n\n")
    }
}

private data class SnapshotDetailData(
    val file: File?,
    val json: org.json.JSONObject?,
    val baseJson: org.json.JSONObject?,
    val diff: SnapshotDiff?,
)

private const val NAME_LIMIT = 10
