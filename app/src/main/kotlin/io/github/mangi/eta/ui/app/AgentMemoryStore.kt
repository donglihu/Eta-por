package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val settings = SettingsDataStore.settings()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                Triple(snapshot, settings, contextWindow)
            }.fold(
                onSuccess = { (snapshot, settings, contextWindow) ->
                    val backupStatus = AgentMemoryRepository.memoryBackupStatus()
                    val backupSummary = backupStatus?.let { status ->
                        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(status.at))
                        if (status.ok) {
                            appContext.getString(R.string.memory_backup_summary_done, stamp, status.directory)
                        } else {
                            appContext.getString(
                                R.string.memory_backup_summary_failed,
                                status.error.orEmpty(),
                                status.directory,
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = AgentMemoryUiState(
                            enabled = settings.memoryEnabled,
                            autoMemoryEnabled = settings.autoMemoryEnabled,
                            autoCompactEnabled = settings.memoryAutoCompactEnabled,
                            autoSkillsEnabled = settings.autoSkillsEnabled,
                            isLoading = false,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            draftBytes = snapshot.byteSize,
                            coreBudgetChars = AgentMemoryContextBuilder.coreBudgetChars(
                                contextWindow,
                                settings.memoryCoreBudgetChars,
                            ),
                            coreBudgetOverride = settings.memoryCoreBudgetChars,
                            headingBudgetChars = AgentMemoryContextBuilder.headingBudgetChars(
                                settings.memoryHeadingBudgetChars,
                            ),
                            headingBudgetOverride = settings.memoryHeadingBudgetChars,
                            backupSummary = backupSummary,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setAutomaticReview(memory: Boolean? = null, skills: Boolean? = null) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                SettingsDataStore.updateSettings { current -> current.copy(
                    autoMemoryEnabled = memory ?: current.autoMemoryEnabled,
                    autoSkillsEnabled = skills ?: current.autoSkillsEnabled,
                ) }
                SettingsDataStore.settings()
            }.onSuccess { settings ->
                withContext(Dispatchers.Main) {
                    memoryState = memoryState.copy(autoMemoryEnabled = settings.autoMemoryEnabled,
                            autoCompactEnabled = settings.memoryAutoCompactEnabled,
                        autoSkillsEnabled = settings.autoSkillsEnabled, notice = null)
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                }
            }
        }
    }

    fun setMemoryAutoCompact(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setAutoCompactEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(autoCompactEnabled = enabled, notice = null)
                        }
                    },
                    onFailure = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        io.github.mangi.eta.agent.automation.AgentTaskScheduler.publish(
                            appContext, "memory_updated", snapshot.revision,
                        )
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    /** 手动触发一次记忆镜像备份；结果回显在记忆页的「记忆自动备份」那一行。 */
    fun backupMemory() {
        scope.launch(Dispatchers.IO) {
            val status = runCatching { AgentMemoryRepository.backupNow() }.getOrNull()
            if (status == null) {
                withContext(Dispatchers.Main) {
                    memoryState = memoryState.copy(
                        notice = appContext.getString(
                            R.string.memory_backup_summary_failed,
                            "备份未执行",
                            io.github.mangi.eta.data.repository.AgentMemoryBackup.WORKSPACE_DIRECTORY,
                        ),
                    )
                }
            } else {
                refreshMemory()
            }
        }
    }

    /** 自定义核心注入预算；0 = 自动。保存后立即刷新界面上的实际注入值。 */
    fun setCoreBudget(chars: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setMemoryCoreBudgetChars(chars) }
                .onSuccess { withContext(Dispatchers.Main) { refreshMemory() } }
                .onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_budget_failed") {
                        "Memory budget update failed: type=${throwable.safeLogType()}"
                    }
                }
        }
    }

    /** 自定义标题索引预算；0 = 自动。 */
    fun setHeadingBudget(chars: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setMemoryHeadingBudgetChars(chars) }
                .onSuccess { withContext(Dispatchers.Main) { refreshMemory() } }
                .onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_budget_failed") {
                        "Memory budget update failed: type=${throwable.safeLogType()}"
                    }
                }
        }
    }

    /** 两项预算一起恢复自动（跟随上下文窗口）。 */
    fun resetMemoryBudgets() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                SettingsDataStore.setMemoryCoreBudgetChars(0)
                SettingsDataStore.setMemoryHeadingBudgetChars(0)
            }.onSuccess { withContext(Dispatchers.Main) { refreshMemory() } }
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }
}
