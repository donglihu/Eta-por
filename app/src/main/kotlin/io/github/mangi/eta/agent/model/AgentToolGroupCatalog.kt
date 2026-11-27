package io.github.mangi.eta.agent.model

import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「工具按需加载」的两个入口、run 级开组状态与转发解包。
 *
 * 与 `docs/TOOL_GROUP_LOADING.md` 的关键差异（有意为之，理由都在这里）：
 *
 * 1. **解包点唯一**：只在 [AgentLoop.executeTool] 的入口处把 `tool_call` 还原成真实工具调用，
 *    之后五道闸（计划模式、专家白名单、工具白名单、复盘白名单、能力码）、资源域键、审批卡、
 *    逐工具开关看到的都是真名。[AgentLocalTools] 内递归展开会让所有闸门只看到 `tool_call`，
 *    等于给被禁工具开一条通用后门。
 * 2. **不做 nativeGroups（组转正）**：`openedGroups` 只影响「允许转发哪些真名」，
 *    **不改变请求里的 tools 数组**。因此一次 run 内 tools 前缀从第一轮到最后轮字节不变，
 *    开组不产生任何重付；代价是组内工具始终要走 `tool_call` 的双层 JSON。
 *    方案原文用「已开组下一轮转成原生 schema」换掉这一点，代价是每次开组都要整体重写前缀，
 *    两害相权取「前缀恒定」。弱模型写不对 `arguments` 的风险由 schema 内的完整示例
 *    与 `INVALID_TOOL_ARGUMENTS` 回灌兜住。
 * 3. **不做关键词预开组**：命中率靠猜，猜错就是白付一组 schema 或反复开组。
 */
internal object AgentToolGroupCatalog {

    /** 打开整组。 */
    const val USE_GROUP = "tools_use_group"

    /** 转发到组内工具。 */
    const val CALL = "tool_call"

    /** 两个入口自身：任何模式下都要在目录里，否则机制不可用。 */
    val entryToolNames: Set<String> = setOf(USE_GROUP, CALL)

    /**
     * 把两个入口追加到目录末尾。
     *
     * 顺序固定（先 `tools_use_group` 再 `tool_call`），保证同一档位下请求字节稳定。
     * 两个入口同样过逐工具开关：关掉后不声明，「关了就不出现」的语义与其它工具一致。
     */
    fun appendSchemas(
        tools: JSONArray,
        includeUseGroup: Boolean = true,
        includeCall: Boolean = true,
    ) {
        if (includeUseGroup) tools.put(useGroupSchema())
        if (includeCall) tools.put(callSchema())
    }

    private fun useGroupSchema(): JSONObject = AgentToolSchema.function(
        name = USE_GROUP,
        description = "打开一个延迟加载的工具组，返回该组包含的工具清单与用法。打开后本轮即可用 " +
            "$CALL(tool, arguments) 调用组内工具。只开当前任务真正要用的组，不要一次全开。",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "group",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "组 id，取值见系统提示里的延迟加载工具组索引。"),
                    )
                    .put(
                        "reason",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "一句话说明为什么需要这组工具。"),
                    ),
            )
            .put("required", JSONArray().put("group")),
    )

    private fun callSchema(): JSONObject = AgentToolSchema.function(
        name = CALL,
        description = "调用延迟加载组里的工具。tool 填真实工具名，arguments 填该工具参数的 JSON 对象" +
            "（字符串形式，不是嵌套结构）。示例：tool=\"tap\", arguments=\"{\\\"x\\\":500,\\\"y\\\":800," +
            "\\\"coordinate_space\\\":\\\"normalized\\\"}\"。常驻组（basic）的工具可直接调用，不要经本工具转发。",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "tool",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "要调用的真实工具名，例如 tap、terminal、read_file。"),
                    )
                    .put(
                        "arguments",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "该工具参数的 JSON 对象字符串，例如 {\"path\":\"/sdcard/a.txt\"}；" +
                                    "无参数时填 {}。",
                            ),
                    ),
            )
            .put("required", JSONArray().put("tool").put("arguments")),
    )

    // —— 解包（纯函数，可单测，不碰 Android） ——

    internal sealed interface EffectiveCall {
        /** 不是转发入口，原样执行。 */
        data class Direct(val call: AgentModelClient.ToolCall) : EffectiveCall

        /** `tool_call`：内层才是真正要执行的工具。 */
        data class Forwarded(
            val outer: AgentModelClient.ToolCall,
            val inner: AgentModelClient.ToolCall,
            val group: String,
        ) : EffectiveCall

        /** 转发不成立：以错误码回灌，不进闸门也不执行。 */
        data class Rejected(val code: String, val message: String) : EffectiveCall
    }

    /**
     * 把一次工具调用还原成「实际要执行什么」。
     *
     * @param openedGroups 本次 run 已打开的组 id。
     * @param disabledTools 工具能力页关掉的工具名。
     *
     * 为什么这里也要判 [disabledTools]：被关掉的工具在目录侧已被过滤，
     * `heldSchemas` 里自然没有它，转发会在参数校验阶段报「不在目录里」——
     * 那是**另一套说法**，与 `OFF` 档直调时得到的 `TOOL_DISABLED` 不一致。
     * 验收口径是「同一个错误码」，所以这里早一步给出统一拒绝。
     */
    fun resolve(
        call: AgentModelClient.ToolCall,
        openedGroups: Set<String>,
        disabledTools: Set<String> = emptySet(),
    ): EffectiveCall {
        if (call.name != CALL) return EffectiveCall.Direct(call)
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return EffectiveCall.Rejected(
                "INVALID_TOOL_ARGUMENTS",
                "$CALL.arguments 必须是 JSON 对象字符串，例如 {\"tool\":\"tap\",\"arguments\":\"{}\"}",
            )
        val tool = args.optString("tool").trim()
        if (tool.isEmpty()) {
            return EffectiveCall.Rejected(
                "INVALID_TOOL_ARGUMENTS",
                "$CALL 缺少 tool 字段；示例：{\"tool\":\"terminal\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}",
            )
        }
        val group = AgentToolGroups.groupOf(tool)
        val deferred = group != null && group != AgentToolGroups.BASIC
        return when {
            group == null -> EffectiveCall.Rejected("UNKNOWN_TOOL", "未知工具：$tool")
            !deferred -> EffectiveCall.Rejected(
                "TOOL_NOT_DEFERRED",
                "$tool 属于常驻组，直接调用即可，不要经 $CALL 转发",
            )
            group !in openedGroups -> EffectiveCall.Rejected(
                "TOOL_GROUP_NOT_OPEN",
                "组 $group 未打开：先调用 $USE_GROUP(group=\"$group\")，再用 $CALL 调用 $tool",
            )
            // 与 OFF 档的执行侧拒绝同一个码（AgentToolGuard.CODE_TOOL_DISABLED）。
            tool in disabledTools -> EffectiveCall.Rejected(
                "TOOL_DISABLED",
                "$tool 已在工具能力页关闭，本次未执行；需要时先在该页重新打开，下一轮生效",
            )
            else -> {
                // 内层参数允许三种写法：字符串（推荐，schema 里就是这么教的）、
                // 直接给 JSON 对象（部分模型会把双层编码拍平）、缺省（无参工具）。
                val raw = args.opt("arguments")
                val rawArguments = when {
                    raw == null || raw == JSONObject.NULL -> "{}"
                    raw is JSONObject -> raw.toString()
                    raw is String -> raw.ifBlank { "{}" }
                    else -> return EffectiveCall.Rejected(
                        "INVALID_TOOL_ARGUMENTS",
                        "$CALL.arguments 必须是该工具参数的 JSON 对象字符串",
                    )
                }
                EffectiveCall.Forwarded(
                    outer = call,
                    inner = call.copy(name = tool, argumentsJson = rawArguments),
                    group = group,
                )
            }
        }
    }

    /**
     * 目录里没有这个名字时给模型的说明。
     *
     * 延迟组工具**直接按真名调用**是最常见的失败模式（模型知道工具名，但没先开组）。
     * 通用文案「工具未在本轮能力目录中提供」会让它换工具硬凑；这里直接告诉它先开哪一组。
     */
    fun notAvailableMessage(toolName: String): Pair<String, String> {
        val group = AgentToolGroups.groupOf(toolName)
        return if (group != null && group != AgentToolGroups.BASIC) {
            "TOOL_GROUP_NOT_OPEN" to
                "$toolName 属于延迟加载的组 $group，schema 未随本轮请求下发。" +
                "先调用 $USE_GROUP(group=\"$group\") 打开，再用 $CALL(tool=\"$toolName\", arguments=\"{...}\") 调用。"
        } else {
            "TOOL_NOT_AVAILABLE" to "工具未在本轮能力目录中提供，请使用当前可用工具"
        }
    }

    /** `tools_use_group` 的回执。 */
    fun openedReceipt(group: String, members: Set<String>): String = JSONObject()
        .put("ok", true)
        .put("group", group)
        .put("tools", JSONArray(members.sorted()))
        .put("usage", "本轮起可用 $CALL(tool, arguments) 调用以上工具；" +
            "参数填该工具自己的 JSON 对象，不需要再包一层。")
        .put("call_via", CALL)
        .toString()

    fun rejection(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()
}

/**
 * 一次 run 的开组状态。
 *
 * 同轮工具调用是并行的（[AgentLoop] 会把 `subagent` 之外的工具逐个同步执行，但不同批次可能并发），
 * 所以开组判定必须原子：上限检查与插入合成一个循环 CAS，不能先 `size` 再 `add`。
 */
internal class AgentToolGroupSession(
    private val maxOpens: Int = MAX_OPENS_PER_RUN,
) {
    private val opened = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val opens = AtomicInteger(0)

    fun openedGroups(): Set<String> = opened

    /**
     * 打开一个组。
     *
     * @return null 表示成功（或已经打开过，幂等且不占配额）；否则返回拒绝回执正文。
     */
    fun open(groupId: String): String? {
        val group = AgentToolGroups.group(groupId)
            ?: return AgentToolGroupCatalog.rejection(
                "UNKNOWN_TOOL_GROUP",
                "未知工具组：$groupId；可选组：" +
                    AgentToolGroups.deferredGroups.joinToString("、") { it.id },
            )
        if (group.resident) {
            return AgentToolGroupCatalog.rejection(
                "UNKNOWN_TOOL_GROUP",
                "组 $groupId 是常驻组，成员已在目录里，直接调用即可",
            )
        }
        if (!opened.add(groupId)) return null
        var granted = false
        while (true) {
            val current = opens.get()
            if (current >= maxOpens) break
            if (opens.compareAndSet(current, current + 1)) {
                granted = true
                break
            }
        }
        if (!granted) {
            opened.remove(groupId)
            return AgentToolGroupCatalog.rejection(
                "TOOL_GROUP_OPEN_LIMIT",
                "一次任务最多打开 $maxOpens 个工具组，已开：" +
                    opened.sorted().joinToString("、") +
                    "；继续用 $AgentToolGroupCatalog.CALL 调用已开组的工具，或直接调用常驻工具",
            )
        }
        return null
    }

    companion object {
        const val MAX_OPENS_PER_RUN = 3
    }
}

/**
 * 档位决策：默认按组加载，按 `providerType` 灰度，用户设置优先。
 *
 * 白名单用 `providerType` 而不是 `providerId`：后者是用户自建的任意字符串，
 * 既无法枚举也无法预置。`custom` 覆盖各类自建中转，行为不可预期，先不纳入灰度。
 */
internal object AgentToolGroupLoadingPolicy {

    /** `Prefs` 里的取值：关闭（全量声明，回滚开关）。 */
    const val MODE_OFF = io.github.mangi.eta.config.Prefs.Keys.TOOL_GROUP_LOADING_OFF

    /** `Prefs` 里的取值：按组加载。 */
    const val MODE_GROUP = io.github.mangi.eta.config.Prefs.Keys.TOOL_GROUP_LOADING_GROUP

    private val grayscaleProviderTypes = setOf(
        io.github.mangi.eta.data.model.ProviderTypes.ANTHROPIC,
        io.github.mangi.eta.data.model.ProviderTypes.OPENAI_COMPATIBLE,
        io.github.mangi.eta.data.model.ProviderTypes.GEMINI,
    )

    /**
     * 按 provider 与用户设置裁决本次 run 是否按组加载。
     *
     * @param configuredMode `Prefs` 原值：`off` / `group` / 空串（未设置）。
     *   未设置时按 [grayscaleProviderTypes] 灰度，`custom` 这类自建中转先不放开——
     *   它们的行为不可预期（中转层可能改写 tools 数组），先观察再放。
     */
    fun enabled(
        providerType: String,
        configuredMode: String = io.github.mangi.eta.config.Prefs.toolGroupLoadingMode(),
    ): Boolean = when (configuredMode) {
        MODE_OFF -> false
        MODE_GROUP -> true
        // 未设置或未知值：只对白名单内的 provider 生效，其余一律保持全量。
        else -> providerType in grayscaleProviderTypes
    }
}
