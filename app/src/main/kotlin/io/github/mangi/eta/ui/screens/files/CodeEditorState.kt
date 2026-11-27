package io.github.mangi.eta.ui.screens.files

import android.content.Context
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.ui.app.WORKSPACE_PREVIEW_MAX_BYTES
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置代码编辑器状态机（自 aicode `CodeEditorViewModel` 移植，2026-11-27）。
 *
 * 与 aicode 版的差异：
 * - 不是 Android ViewModel：ETA 无 DI，由 [io.github.mangi.eta.ui.app.AgentAppState]
 *   持有的 uiScope 直接构造，生命周期随 Activity 状态所有者；
 * - IO 后端是 [WorkspaceFileStore]（单 root 工作区），替代 aicode 的 FileAccessProvider 多后端路由；
 * - aicode 的 FileVersionRegistry 联动登记（AI 会话版本过期）暂未接入：编辑器与磁盘的
 *   双向冲突保护语义完整（见 save），AI 工具侧版本联动为后续独立项。
 */
internal class CodeEditorState(
    private val context: Context,
    private val store: WorkspaceFileStore,
    private val scope: CoroutineScope,
    private val logger: AgentLogger,
) {

    /** 编辑器页状态。 */
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val content: String, val scopeName: String?) : UiState
        data class TooLarge(val sizeBytes: Long) : UiState
        data object Binary : UiState
        data class Error(val detail: String?) : UiState
    }

    /** 保存结果，一次性事件，经 [saveEvents] 下发。 */
    sealed interface SaveResult {
        data object Success : SaveResult

        /**
         * 磁盘上的内容在编辑器打开之后被改过（多半是 AI 会话写的），本次保存**未写入**。
         * 携带磁盘当前内容供 UI 展示：不直接覆盖，也不直接丢弃用户的编辑，由用户选。
         */
        data class Conflict(val diskContent: String) : SaveResult

        data class Error(val detail: String?) : SaveResult
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _saveEvents = Channel<SaveResult>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    /** 是否正在写盘（供 UI 转圈）。 */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private var loadedPath: String? = null

    /** 加载时磁盘内容的指纹，用于保存前判断「文件是否被 AI/其他会话改过」。 */
    private var loadedFingerprint: String? = null

    /** 重复调用同一路径不会重复读盘，供 Compose 重组时安全调用。 */
    fun load(path: String) {
        if (loadedPath == path) return
        loadedPath = path
        // 换文件时先清空基线：否则旧文件的指纹会被拿来跟新文件比，误报冲突。
        loadedFingerprint = null
        _uiState.value = UiState.Loading
        scope.launch(Dispatchers.IO) {
            try {
                val size = store.fileSize(path)
                if (size < 0) {
                    _uiState.value = UiState.Error(null)
                    return@launch
                }
                if (size > WORKSPACE_PREVIEW_MAX_BYTES) {
                    _uiState.value = UiState.TooLarge(size)
                    return@launch
                }
                val head = store.readHeadBytes(path)
                if (head.any { it.toInt() == 0 }) {
                    _uiState.value = UiState.Binary
                    return@launch
                }
                // 语法包解析放 IO 线程：确保 AndroidView factory 在主线程创建编辑器时 registry 已就绪。
                TextMateSetup.ensureInitialized(context)
                val scopeName = TextMateSetup.scopeNameFor(path)
                if (scopeName != null) {
                    // 预热 grammar：某种语法首次构造要编译大量正则，不在这里做就会压到主线程并拖后首次上色。
                    // 不把 Language 传给 UI：Language 绑编辑器生命周期，editor.release() 会连带销毁它，
                    // 跨重建复用已销毁实例会出问题——此处仅为把编译结果缓进 registry。
                    try {
                        io.github.rosemoe.sora.langs.textmate.TextMateLanguage.create(scopeName, false).destroy()
                    } catch (e: Exception) {
                        logger.error("$TAG: TextMate grammar 预热失败: $scopeName", e)
                    }
                }
                _uiState.value = UiState.Success(content = store.readText(path), scopeName = scopeName)
                loadedFingerprint = runCatching { store.fingerprint(path) }
                    .onFailure { logger.error("$TAG: 指纹计算失败: $path", it) }
                    .getOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("$TAG: 打开文件失败: $path", e)
                _uiState.value = UiState.Error(e.message)
            }
        }
    }

    /**
     * 把编辑器当前内容写回文件。写入在 IO 线程进行，结果通过 [saveEvents] 通知，期间 [saving] 置位。
     *
     * 保存前先比对磁盘指纹：若文件在编辑器打开之后被 AI 会话或别的进程改过，**不写入**，
     * 改为下发 [SaveResult.Conflict]，由用户决定覆盖还是放弃自己的编辑。否则用户的保存会
     * 静默抹掉 AI 刚写的改动（反向亦然）。
     */
    fun save(content: String) {
        val path = loadedPath ?: return
        scope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                val current = runCatching { store.fingerprint(path) }
                    .onFailure { logger.error("$TAG: 指纹计算失败: $path", it) }
                    .getOrNull()
                if (loadedFingerprint != null && current != null && current != loadedFingerprint) {
                    logger.warn("$TAG: 保存冲突（磁盘内容已变）: $path")
                    val disk = try {
                        store.readText(path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        logger.error("$TAG: 冲突回读磁盘内容失败: $path", e)
                        ""
                    }
                    _saveEvents.send(SaveResult.Conflict(disk))
                    return@launch
                }
                _saveEvents.send(writeThrough(path, content))
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * 无视冲突直接覆盖磁盘（用户在冲突提示里选了「覆盖」）。
     * 这是用户显式确认的覆盖，因此不再比对指纹——但仍走 [WorkspaceFileStore.writeText] 的原子写。
     */
    fun saveForce(content: String) {
        val path = loadedPath ?: return
        scope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                _saveEvents.send(writeThrough(path, content))
            } finally {
                _saving.value = false
            }
        }
    }

    /** 放弃编辑器里的修改、重新从磁盘加载（用户在冲突提示里选了「放弃我的修改」）。 */
    fun reloadFromDisk() {
        val path = loadedPath ?: return
        loadedPath = null
        load(path)
    }

    /** 真正写盘并维护版本基线；成功/失败统一转成 [SaveResult]。 */
    private suspend fun writeThrough(path: String, content: String): SaveResult = try {
        store.writeText(path, content, overwrite = true)
        // 更新基线，否则紧接着的第二次保存会被自己上一次的写入判成冲突。
        loadedFingerprint = runCatching { store.fingerprint(path) }
            .onFailure { logger.error("$TAG: 指纹计算失败: $path", it) }
            .getOrNull()
        SaveResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        logger.error("$TAG: 保存文件失败: $path", e)
        SaveResult.Error(e.message)
    }

    private companion object {
        const val TAG = "CodeEditorState"
    }
}
