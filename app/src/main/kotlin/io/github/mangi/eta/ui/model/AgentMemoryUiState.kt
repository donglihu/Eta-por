package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryStore

@Immutable
data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val autoMemoryEnabled: Boolean = true,
    val autoCompactEnabled: Boolean = true,
    val autoSkillsEnabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    /** 实际生效的核心注入字符数（滑杆值或自动计算值）。 */
    val coreBudgetChars: Int = 8_000,
    /** 用户自定义的核心预算；0 表示自动。 */
    val coreBudgetOverride: Int = 0,
    /** 实际生效的标题索引注入字符数。 */
    val headingBudgetChars: Int = 1_500,
    /** 用户自定义的索引预算；0 表示自动。 */
    val headingBudgetOverride: Int = 0,
    /** 记忆自动备份的最近状态摘要：成功显示路径，失败显示真实原因。 */
    val backupSummary: String? = null,
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes
}
