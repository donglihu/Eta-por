package io.github.mangi.eta.ui.screens.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Description
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 内置代码编辑器页（自 aicode `CodeEditorScreen` 移植并按 ETA 的 Miuix 视觉重写，2026-11-27）。
 *
 * sora 编辑器经 [AndroidView] 承载；TextMate 语法由 [TextMateSetup] 提供，未打包语法的文件降级纯文本。
 * 保存走 [CodeEditorState]：磁盘指纹冲突时弹窗让用户选「覆盖 / 放弃我的修改」。
 * Markdown 文件提供源码 / 预览切换（ETA 自建 markdown 渲染）。
 *
 * v1 边界（如实声明）：文案暂用硬编码中文（未入 R.string）；编辑器接管硬件返回前的保存确认。
 */
@Composable
internal fun CodeEditorScreen(
    path: String,
    initialLine: Int,
    editorState: CodeEditorState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by editorState.uiState.collectAsStateWithLifecycle()
    val saving by editorState.saving.collectAsStateWithLifecycle()

    var message by remember { mutableStateOf<String?>(null) }
    var conflictDiskContent by remember { mutableStateOf<String?>(null) }
    var leaveGuard by remember { mutableStateOf(false) }
    var previewMarkdown by remember { mutableStateOf(false) }
    val editorRef = remember { java.util.concurrent.atomic.AtomicReference<CodeEditor?>(null) }

    LaunchedEffect(path) { editorState.load(path) }

    LaunchedEffect(Unit) {
        editorState.saveEvents.collect { result ->
            when (result) {
                is CodeEditorState.SaveResult.Success -> {
                    conflictDiskContent = null
                    message = "已保存"
                }
                is CodeEditorState.SaveResult.Conflict -> {
                    message = null
                    conflictDiskContent = result.diskContent
                }
                is CodeEditorState.SaveResult.Error -> {
                    conflictDiskContent = null
                    message = "保存失败：${result.detail ?: "未知错误"}"
                }
            }
        }
    }

    // 消息条自动消失
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            message = null
        }
    }

    val success = uiState as? CodeEditorState.UiState.Success

    // 编辑器里有未保存修改时，返回先确认（比对当前文本与加载基线，不依赖 sora 内部 dirty 标记）。
    val requestBack: () -> Unit = {
        val editor = editorRef.get()
        val modified = success != null && editor != null &&
            editor.text.toString() != success.content
        if (modified) leaveGuard = true else onBack()
    }
    BackHandler(enabled = uiState is CodeEditorState.UiState.Success) { requestBack() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = path.substringAfterLast('/'),
                navigationIcon = { MiuixBackButton(onClick = requestBack) },
                actions = {
                    if (success != null) {
                        if (success.scopeName == "text.html.markdown") {
                            IconButton(onClick = { previewMarkdown = !previewMarkdown }) {
                                Icon(
                                    imageVector = Icons.Rounded.Description,
                                    contentDescription = "切换预览",
                                    tint = if (previewMarkdown) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { editorRef.get()?.undo() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Undo,
                                contentDescription = "撤销",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                        IconButton(onClick = { editorRef.get()?.redo() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Redo,
                                contentDescription = "重做",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                        IconButton(
                            onClick = {
                                val editor = editorRef.get() ?: return@IconButton
                                editorState.save(editor.text.toString())
                            },
                            enabled = !saving,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = "保存",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val state = uiState) {
                is CodeEditorState.UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InfiniteProgressIndicator()
                    }
                }
                is CodeEditorState.UiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = "无法打开文件", style = MiuixTheme.textStyles.title3)
                        Text(
                            text = state.detail ?: "文件不存在或不可读",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                is CodeEditorState.UiState.TooLarge -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = "文件过大，不支持内置编辑", style = MiuixTheme.textStyles.title3)
                        Text(
                            text = "${state.sizeBytes} 字节（上限 2 MB）",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                is CodeEditorState.UiState.Binary -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = "二进制文件，不支持文本编辑", style = MiuixTheme.textStyles.title3)
                        Text(
                            text = "开头 8000 字节内包含 NUL，按二进制处理",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                is CodeEditorState.UiState.Success -> {
                    if (previewMarkdown && state.scopeName == "text.html.markdown") {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            StaticMarkdown(
                                content = state.content,
                                tone = MarkdownTone.Answer,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                codeWrap = true,
                            )
                        }
                    } else {
                        EditorSurface(
                            path = path,
                            initialLine = initialLine,
                            content = state.content,
                            scopeName = state.scopeName,
                            editorRef = editorRef,
                        )
                    }
                }
            }
            message?.let { text ->
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                )
            }
        }
    }

    conflictDiskContent?.let { diskContent ->
        EtaWindowDialog(
            show = true,
            title = "文件已被修改",
            summary = "磁盘上的内容在编辑器打开之后被改过（可能是 AI 会话写入）。本次保存未写入，请选择处理方式。",
            onDismissRequest = { conflictDiskContent = null },
        ) {
            MiuixDialogActions(
                confirmText = "覆盖磁盘",
                onCancel = {
                    conflictDiskContent = null
                    editorState.reloadFromDisk()
                },
                onConfirm = {
                    val editor = editorRef.get()
                    if (editor != null) {
                        editorState.saveForce(editor.text.toString())
                    }
                },
                destructive = true,
            )
        }
    }

    if (leaveGuard) {
        EtaWindowDialog(
            show = true,
            title = "未保存的修改",
            summary = "编辑器里的内容与磁盘不一致，返回前如何处理？",
            onDismissRequest = { leaveGuard = false },
        ) {
            MiuixDialogActions(
                confirmText = "保存并返回",
                onCancel = {
                    leaveGuard = false
                    onBack()
                },
                cancelText = "不保存",
                onConfirm = {
                    leaveGuard = false
                    val editor = editorRef.get()
                    if (editor != null) {
                        editorState.save(editor.text.toString())
                    }
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun EditorSurface(
    path: String,
    initialLine: Int,
    content: String,
    scopeName: String?,
    editorRef: java.util.concurrent.atomic.AtomicReference<CodeEditor?>,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    var jumpDone by remember(path) { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextMateSetup.ensureInitialized(ctx.applicationContext)
            TextMateSetup.applyTheme(dark)
            CodeEditor(ctx).apply {
                // Compose 的 AndroidView 添加子 View 时用的是默认 WRAP_CONTENT，必须显式改掉，
                // 否则 sora 内部按自身布局请求测量，行号栏与文本区会被压扁。
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isLineNumberEnabled = true
                setText(content)
                if (scopeName != null) {
                    setEditorLanguage(TextMateLanguage.create(scopeName, dark))
                }
                editorRef.set(this)
            }
        },
        update = { editor ->
            TextMateSetup.applyTheme(dark)
            if (scopeName != null) {
                val current = editor.editorLanguage as? TextMateLanguage
                if (current == null) {
                    editor.setEditorLanguage(TextMateLanguage.create(scopeName, dark))
                }
            }
            if (!jumpDone && initialLine > 0) {
                jumpDone = true
                editor.post {
                    // 行号从 0 计；目标行超界时 sora 会按 false 返回并保持原位。
                    editor.setSelection(initialLine - 1, 0)
                }
            }
        },
        onRelease = { editor ->
            editorRef.set(null)
            editor.release()
        },
    )
}
