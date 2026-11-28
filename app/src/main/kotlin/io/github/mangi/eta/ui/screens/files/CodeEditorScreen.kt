package io.github.mangi.eta.ui.screens.files

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.text.UndoManager
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 内置代码编辑器页（自 aicode `CodeEditorScreen` 移植并按 ETA 的 Miuix 视觉重写）。
 *
 * 布局与 aicode 对齐：顶栏（预览/撤销/重做/保存/设置）+ 文件名小栏（行:列 + UTF-8）
 * + 编辑区 + 底部符号快捷栏；齿轮进入编辑器设置（字号/自动换行/缩进参考线/换行箭头/空白符号）。
 * 配色用 [TextMateColorScheme.create]——不设的话文字颜色与背景同色，表现就是「只见缩进线不见字」。
 * 保存走 [CodeEditorState]：磁盘指纹冲突时弹窗让用户选「覆盖 / 放弃我的修改」。
 * Markdown 文件提供源码 / 预览切换（ETA 自建 markdown 渲染）。
 */
@Composable
internal fun CodeEditorScreen(
    path: String,
    initialLine: Int,
    editorState: CodeEditorState,
    onBack: () -> Unit,
) {
    val uiState by editorState.uiState.collectAsStateWithLifecycle()
    val saving by editorState.saving.collectAsStateWithLifecycle()

    var message by remember { mutableStateOf<String?>(null) }
    var conflictDiskContent by remember { mutableStateOf<String?>(null) }
    var leaveGuard by remember { mutableStateOf(false) }
    var previewMarkdown by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val editorRef = remember { java.util.concurrent.atomic.AtomicReference<CodeEditor?>(null) }

    // 与 aicode 对齐的联动状态：撤销/重做可用性、脏标记、光标行列、编辑器底色。
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorColumn by remember { mutableIntStateOf(1) }
    var editorBackground by remember { mutableStateOf<Color?>(null) }

    // 编辑器设置：SharedPreferences 持久化（ETA 无 DataStore，语义与 aicode 一致）。
    val context = LocalContext.current
    var settings by remember {
        mutableStateOf(loadEditorSettings(context.applicationContext))
    }

    LaunchedEffect(path) { editorState.load(path) }

    LaunchedEffect(Unit) {
        editorState.saveEvents.collect { result ->
            when (result) {
                is CodeEditorState.SaveResult.Success -> {
                    conflictDiskContent = null
                    dirty = false
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

    val requestBack: () -> Unit = {
        if (dirty) leaveGuard = true else onBack()
    }
    // 设置页在最上层：先退设置页，再走编辑器的返回守卫。
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && uiState is CodeEditorState.UiState.Success) {
        requestBack()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = "",
                navigationIcon = { MiuixBackButton(onClick = if (showSettings) ({ showSettings = false }) else requestBack) },
                actions = {
                    if (success != null) {
                        if (success.scopeName == "text.html.markdown") {
                            IconButton(onClick = { previewMarkdown = !previewMarkdown }) {
                                Icon(
                                    imageVector = if (previewMarkdown) Icons.Rounded.Code else Icons.Rounded.Visibility,
                                    contentDescription = if (previewMarkdown) "查看源码" else "切换预览",
                                    tint = if (previewMarkdown) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { editorRef.get()?.undo() }, enabled = canUndo) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Undo,
                                contentDescription = "撤销",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                        IconButton(onClick = { editorRef.get()?.redo() }, enabled = canRedo) {
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
                            enabled = dirty && !saving,
                        ) {
                            if (saving) {
                                InfiniteProgressIndicator(size = 18.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Save,
                                    contentDescription = "保存",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "编辑器设置",
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
            if (showSettings) {
                EditorSettingsPane(
                    settings = settings,
                    onChange = {
                        settings = it
                        saveEditorSettings(context.applicationContext, it)
                    },
                )
            } else {
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
                            Column(modifier = Modifier.fillMaxSize()) {
                                FileTitleBar(
                                    fileName = path.substringAfterLast('/'),
                                    dirty = dirty,
                                    line = cursorLine,
                                    column = cursorColumn,
                                )
                                EditorSurface(
                                    path = path,
                                    initialLine = initialLine,
                                    content = state.content,
                                    scopeName = state.scopeName,
                                    settings = settings,
                                    editorRef = editorRef,
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    onBackgroundResolved = { editorBackground = it },
                                    onCursorChanged = { line, column ->
                                        cursorLine = line
                                        cursorColumn = column
                                    },
                                    onContentChanged = { undo, redo, changed ->
                                        canUndo = undo
                                        canRedo = redo
                                        dirty = changed
                                    },
                                )
                                EditorSymbolBar(
                                    backgroundColor = editorBackground
                                        ?: MiuixTheme.colorScheme.surface,
                                    onInsert = { editorRef.get()?.commitText(it) },
                                    onIndent = { editorRef.get()?.commitText("\t") },
                                    onMoveLeft = {
                                        editorRef.get()?.let { moveCursorHorizontally(it, forward = false) }
                                    },
                                    onMoveRight = {
                                        editorRef.get()?.let { moveCursorHorizontally(it, forward = true) }
                                    },
                                )
                            }
                        }
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

// ---------- 编辑器设置（对齐 aicode EditorSettingsRepository 的字段与默认值） ----------

const val MIN_EDITOR_FONT_SIZE_SP = 10
const val MAX_EDITOR_FONT_SIZE_SP = 28
const val DEFAULT_EDITOR_FONT_SIZE_SP = 14

private const val EDITOR_PREFS = "editor_prefs"
private const val KEY_FONT_SIZE = "font_size_sp"
private const val KEY_WORD_WRAP = "word_wrap"
private const val KEY_SHOW_INDENT_GUIDE = "show_indent_guide"
private const val KEY_SHOW_WRAP_ARROW = "show_wrap_arrow"
private const val KEY_SHOW_WHITESPACE = "show_whitespace"

/** 编辑器可持久化的显示设置。 */
data class EditorSettings(
    val fontSizeSp: Int = DEFAULT_EDITOR_FONT_SIZE_SP,
    val wordWrap: Boolean = false,
    val showIndentGuide: Boolean = true,
    val showWrapArrow: Boolean = false,
    val showWhitespace: Boolean = false,
)

private fun loadEditorSettings(context: Context): EditorSettings {
    val prefs = context.getSharedPreferences(EDITOR_PREFS, Context.MODE_PRIVATE)
    return EditorSettings(
        fontSizeSp = prefs.getInt(KEY_FONT_SIZE, DEFAULT_EDITOR_FONT_SIZE_SP)
            .coerceIn(MIN_EDITOR_FONT_SIZE_SP, MAX_EDITOR_FONT_SIZE_SP),
        wordWrap = prefs.getBoolean(KEY_WORD_WRAP, false),
        showIndentGuide = prefs.getBoolean(KEY_SHOW_INDENT_GUIDE, true),
        showWrapArrow = prefs.getBoolean(KEY_SHOW_WRAP_ARROW, false),
        showWhitespace = prefs.getBoolean(KEY_SHOW_WHITESPACE, false),
    )
}

private fun saveEditorSettings(context: Context, settings: EditorSettings) {
    context.getSharedPreferences(EDITOR_PREFS, Context.MODE_PRIVATE).edit()
        .putInt(KEY_FONT_SIZE, settings.fontSizeSp.coerceIn(MIN_EDITOR_FONT_SIZE_SP, MAX_EDITOR_FONT_SIZE_SP))
        .putBoolean(KEY_WORD_WRAP, settings.wordWrap)
        .putBoolean(KEY_SHOW_INDENT_GUIDE, settings.showIndentGuide)
        .putBoolean(KEY_SHOW_WRAP_ARROW, settings.showWrapArrow)
        .putBoolean(KEY_SHOW_WHITESPACE, settings.showWhitespace)
        .apply()
}

/**
 * 编辑器设置页（内嵌在编辑器顶层，对齐 aicode EditorSettingsScreen：
 * 字体大小滑杆 + 自动换行 / 缩进参考线 / 换行箭头 / 空白符号四个开关，改动即时生效并落盘）。
 */
@Composable
private fun EditorSettingsPane(
    settings: EditorSettings,
    onChange: (EditorSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "字体大小", style = MiuixTheme.textStyles.body1)
            Text(
                text = "${settings.fontSizeSp} sp",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        Slider(
            value = settings.fontSizeSp.toFloat(),
            onValueChange = { onChange(settings.copy(fontSizeSp = it.roundToInt())) },
            valueRange = MIN_EDITOR_FONT_SIZE_SP.toFloat()..MAX_EDITOR_FONT_SIZE_SP.toFloat(),
            showKeyPoints = true,
            keyPoints = (MIN_EDITOR_FONT_SIZE_SP..MAX_EDITOR_FONT_SIZE_SP).map { it.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SettingSwitchRow(
            label = "自动换行",
            checked = settings.wordWrap,
            onCheckedChange = { onChange(settings.copy(wordWrap = it)) },
        )
        SettingSwitchRow(
            label = "显示缩进参考线",
            checked = settings.showIndentGuide,
            onCheckedChange = { onChange(settings.copy(showIndentGuide = it)) },
        )
        SettingSwitchRow(
            label = "显示自动换行箭头",
            checked = settings.showWrapArrow,
            onCheckedChange = { onChange(settings.copy(showWrapArrow = it)) },
        )
        SettingSwitchRow(
            label = "显示空白符号",
            checked = settings.showWhitespace,
            onCheckedChange = { onChange(settings.copy(showWhitespace = it)) },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
        EtaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------- 文件名小栏（对齐 aicode FileTitleBar） ----------

/** 导航栏下方的文件名小栏：左侧文件名（未保存时名前加星号），右侧行:列与文件编码。 */
@Composable
private fun FileTitleBar(fileName: String, dirty: Boolean, line: Int, column: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dirty) {
            Text(
                text = "*",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        Text(
            text = fileName,
            style = MiuixTheme.textStyles.footnote1,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$line:$column",
            style = MiuixTheme.textStyles.footnote1,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = "UTF-8",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

// ---------- 编辑器承载（对齐 aicode EditorSurface 的关键配置） ----------

@Composable
private fun EditorSurface(
    path: String,
    initialLine: Int,
    content: String,
    scopeName: String?,
    settings: EditorSettings,
    editorRef: java.util.concurrent.atomic.AtomicReference<CodeEditor?>,
    modifier: Modifier = Modifier,
    onBackgroundResolved: (Color) -> Unit,
    onCursorChanged: (Int, Int) -> Unit,
    onContentChanged: (canUndo: Boolean, canRedo: Boolean, dirty: Boolean) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val themeSurface = MiuixTheme.colorScheme.surface
    var jumpDone by remember(path) { mutableStateOf(false) }
    val baselineRef = remember { java.util.concurrent.atomic.AtomicReference("") }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextMateSetup.ensureInitialized(ctx.applicationContext)
            TextMateSetup.applyTheme(dark)
            // sora 默认把 8 秒内的连续输入并成一条撤销记录，一次撤销会吞掉整段输入。
            UndoManager.setMergeTimeLimit(500L)
            baselineRef.set(content)
            CodeEditor(ctx).apply {
                // Compose 的 AndroidView 添加子 View 时用的是默认 WRAP_CONTENT，必须显式改掉，
                // 否则 sora 内部按自身布局请求测量，行号栏与文本区会被压扁。
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isEditable = true
                typefaceText = Typeface.MONOSPACE
                setTextSize(settings.fontSizeSp.toFloat())
                setWordwrap(settings.wordWrap)
                isCursorAnimationEnabled = false
                setLineNumberMarginLeft(spToPx(ctx, 2f))
                verticalExtraSpaceFactor = 0.5f
                isBlockLineEnabled = settings.showIndentGuide
                nonPrintablePaintingFlags = nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
                // 关键：不设 TextMateColorScheme 时文字颜色与背景同色，表现为「只见缩进线不见字」。
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                applyThemedBackground(this, themeSurface)
                applyLineNumberBackground(this, dark)
                isLineNumberEnabled = true
                if (scopeName != null) {
                    setEditorLanguage(TextMateLanguage.create(scopeName, dark))
                }
                setText(content)
                val editorView = this
                subscribeAlways(ContentChangeEvent::class.java) {
                    // UndoManager 先改文本、后移动栈指针，事件回调里同帧读 canUndo/canRedo
                    // 拿到的是移动前的旧值，延后一帧才准。
                    editorView.post {
                        val baseline = baselineRef.get()
                        val text = editorView.text
                        onContentChanged(
                            editorView.canUndo(),
                            editorView.canRedo(),
                            text.length != baseline.length || text.toString() != baseline,
                        )
                    }
                }
                subscribeAlways(SelectionChangeEvent::class.java) {
                    onCursorChanged(cursor.leftLine + 1, cursor.leftColumn + 1)
                }
                editorRef.set(this)
            }
        },
        update = { editor ->
            if (content != baselineRef.get()) {
                // 换文件或从磁盘重载：重置基线，脏标记与撤销状态由下一次事件刷新。
                baselineRef.set(content)
            }
            val scheme = editor.colorScheme
            if (scheme is TextMateColorScheme) {
                TextMateSetup.applyTheme(dark)
                applyThemedBackground(editor, themeSurface)
                applyLineNumberBackground(editor, dark)
                onBackgroundResolved(Color(editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)))
            }
            editor.setTextSize(settings.fontSizeSp.toFloat())
            editor.setWordwrap(settings.wordWrap)
            editor.isBlockLineEnabled = settings.showIndentGuide
            editor.nonPrintablePaintingFlags = nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
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

// ---------- 底部符号快捷栏（对齐 aicode EditorSymbolBar） ----------

@Composable
private fun EditorSymbolBar(
    backgroundColor: Color,
    onInsert: (String) -> Unit,
    onIndent: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .background(backgroundColor)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolKey(onClick = onMoveLeft) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowLeft,
                contentDescription = "光标左移",
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        SymbolKey(onClick = onMoveRight) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowRight,
                contentDescription = "光标右移",
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        SymbolKey(onClick = onIndent) {
            Text(text = "缩进", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
        }
        EDITOR_SYMBOLS.forEach { symbol ->
            SymbolKey(onClick = { onInsert(symbol) }) {
                Text(
                    text = symbol,
                    style = MiuixTheme.textStyles.body1,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SymbolKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ---------- 帮助函数（自 aicode 移植） ----------

/** 底部快捷栏的常用符号，点击在光标处插入。 */
private val EDITOR_SYMBOLS = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">",
    "=", "+", "-", "*", "/", "\\\\",
    ";", ":", ",", ".", "_", "\"", "'", "`",
    "|", "&", "!", "?", "@", "#", "\$", "%",
)

/** 给编辑器底色掺入当前主题的表面色，保留一成原色维持编辑区基调。 */
private fun applyThemedBackground(editor: CodeEditor, themeSurface: Color) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    scheme.setColor(
        EditorColorScheme.WHOLE_BACKGROUND,
        lerp(base, themeSurface, 0.9f).toArgb(),
    )
}

/** 行号 gutter 背景与编辑区拉开一点亮度差便于区分。 */
private fun applyLineNumberBackground(editor: CodeEditor, dark: Boolean) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    val target = if (dark) Color.White else Color.Black
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, lerp(base, target, 0.08f).toArgb())
}

/** 把 sp 换算为像素，用于行号左边距等需 px 的 sora API。 */
private fun spToPx(context: Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

/** 根据「显示空白符号」与「显示自动换行箭头」开关合成 sora 的非打印字符绘制标志。 */
private fun nonPrintableFlags(showWhitespace: Boolean, showWrapArrow: Boolean): Int {
    var flags = 0
    if (showWhitespace) {
        flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
            CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
            CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
            CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
            CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    if (showWrapArrow) {
        flags = flags or CodeEditor.FLAG_DRAW_SOFT_WRAP
    }
    return flags
}

/** 符号栏方向键：把光标水平移动一个字符，跨越行首/行尾时换到相邻行。 */
private fun moveCursorHorizontally(editor: CodeEditor, forward: Boolean) {
    val cursor = editor.cursor
    val line = cursor.leftLine
    val column = cursor.leftColumn
    if (forward) {
        val lineLength = editor.text.getColumnCount(line)
        when {
            column < lineLength -> editor.setSelection(line, column + 1)
            line < editor.lineCount - 1 -> editor.setSelection(line + 1, 0)
        }
    } else {
        when {
            column > 0 -> editor.setSelection(line, column - 1)
            line > 0 -> editor.setSelection(line - 1, editor.text.getColumnCount(line - 1))
        }
    }
}
