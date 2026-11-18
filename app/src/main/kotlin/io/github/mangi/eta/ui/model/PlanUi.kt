package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * 计划步骤：plan 阶段由 `update_plan` 工具产出，执行阶段按同一份步骤回写状态。
 *
 * 只存文本与状态，不存工具、模型等执行细节：步骤是给人看的清单，
 * 执行细节留在消息流里，两者混在一起会让面板越滚越长。
 */
@Immutable
internal data class PlanStepUi(
    val text: String,
    val status: String = PLAN_STEP_PENDING,
) {
    val isDone: Boolean get() = status == PLAN_STEP_DONE
    val isActive: Boolean get() = status == PLAN_STEP_ACTIVE
}

internal const val PLAN_STEP_PENDING = "pending"
internal const val PLAN_STEP_ACTIVE = "in_progress"
internal const val PLAN_STEP_DONE = "done"

private val KNOWN_STATUS = setOf(PLAN_STEP_PENDING, PLAN_STEP_ACTIVE, PLAN_STEP_DONE)

/** 状态归一：模型给 `completed`、`doing`、`todo` 这类近义词时统一成三种。 */
internal fun normalizePlanStepStatus(raw: String?): String {
    val value = raw.orEmpty().trim().lowercase()
    if (value in KNOWN_STATUS) return value
    return when {
        value.contains("done") || value.contains("complete") || value.contains("finish") ||
            value.contains("success") || value == "ok" || value == "1" -> PLAN_STEP_DONE
        value.contains("progress") || value.contains("active") || value.contains("doing") ||
            value.contains("running") || value.contains("current") || value == "2" -> PLAN_STEP_ACTIVE
        else -> PLAN_STEP_PENDING
    }
}

/** 计划清单的 JSON 编解码：工具结果、事件载荷与数据库列共用同一份口径。 */
internal object PlanStepsCodec {
    fun arrayToSteps(array: JSONArray?): List<PlanStepUi> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").ifBlank { item.optString("step") }.trim()
                if (text.isEmpty()) continue
                add(PlanStepUi(text = text, status = normalizePlanStepStatus(item.optString("status"))))
            }
        }
    }

    fun decode(raw: String?): List<PlanStepUi> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        val array = runCatching { JSONArray(value) }.getOrNull() ?: return emptyList()
        return arrayToSteps(array)
    }

    fun encode(steps: List<PlanStepUi>): String {
        if (steps.isEmpty()) return ""
        val array = JSONArray()
        steps.forEach { step ->
            if (step.text.isBlank()) return@forEach
            array.put(
                JSONObject()
                    .put("text", step.text)
                    .put("status", normalizePlanStepStatus(step.status)),
            )
        }
        return array.toString()
    }
}