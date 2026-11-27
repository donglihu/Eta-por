# 工具按需加载 · 开发实现规格（MCP / 技能 / 工具组）

> 状态：待实施（本文是实现规格，供编程工具逐 PR 执行）
> 目标分支：`feat/tool-group-loading`（从 `branch` 拉）
> 前置文档：`docs/TOOL_GROUP_LOADING.md`（工具组方案 v1）。**本文覆盖其 §3.1 档位、§3.6 nativeGroups 两处结论**，其余条款继续有效。
> 编写日期：2026-11-27

---

## 0. 决策记录

| # | 决策 | 理由 | 状态 |
| --- | --- | --- | --- |
| D1 | 吸收「组索引 + schema 扣留」与「固定 schema 的 `tool_call` 转发」两件套 | 这两件是省 token 的主力，且都不改执行链语义 | 采纳 |
| D2 | `MAX_GROUP_OPENS_PER_RUN = 3` | `toolsForRound()` 每轮只调一次（`AgentModelClient.kt:236-261`），同轮多开只付一次前缀重写 | 采纳（用户定） |
| D3 | v1 不做 `nativeGroups`（原生 schema 晋升） | ① por 的 `ToolCall.argumentsJson` 到转发层仍是 `JSONObject`，不存在 Expressive 那种字符串转义损失；② por 的 `toolsForRound()` 是**单调增补**，一旦追加 schema 就整体改写前缀，与「开组零字节变化」直接冲突；③ 需要新持久键 + 冻结位 + 跨 run 语义，维护成本高于收益 | 采纳（推翻上一版） |
| D4 | **不扩 `LocalToolProfile` 枚举**，GROUP 档由「FULL + 灰度开关」推导 | 扩枚举要动顶栏胶囊（`LocalProfileTopBarCapsule.kt:60` 固定三段、`CapsuleSegmentWidth=33.dp` 按 1/3 算位移）、动 `LocalToolProfileStore` 迁移、动 `profileOption()` 归一，全是 UI/存储涟漪，收益为零。改为：存值仍是 FULL/CHAT，`FULL + 灰度开启 → GROUP 行为`；用户点「全部工具」即显式要全量 | 采纳（推翻上一版） |
| D5 | 灰度维度用 `providerType`，不用 `providerId` | `providerId` 不可枚举（用户自建 provider 任意命名）；`data/model/Provider.kt:6-11` 只有四个稳定类型 | 采纳 |
| D6 | `custom` 类型默认 OFF | 自建端点多为弱模型/代理，转发层多一跳风险更高；出问题先 OFF 兜底 | 采纳 |
| D7 | 技能不重写，只修 3 处 | `AgentPromptBuilder.kt:408-438` 已经是「索引只放元信息」的正确形态，重写纯属破坏 | 采纳 |

---

## 1. 事实基线（实施前必须核对，行号基于 `branch` = `6c3083b`）

| 事实 | 位置 |
| --- | --- |
| 工具目录唯一入口 `AgentToolDirectory.build(...)`，请求侧与提示词预览共用 | `agent/model/AgentToolDirectory.kt:20-71` |
| 目录由 `AgentToolCatalog.build(...)` 一次性拼全量，再 `capabilities.project` + `disabledTools` + `policy.filterTools` + `restrictedToolNames` | `AgentToolCatalog.kt:24-76`、`AgentToolDirectory.kt:38-69` |
| 每轮目录：`toolsForRound()` 仅在 `rootAvailable / virtualScreenEnabled / virtualScreenFallbackEnabled` 变化时整体重建，否则**单调追加** | `AgentModelClient.kt:228-261` |
| 六道闸链只读 `call.name` | `AgentModelClient.kt:292-344` |
| `ToolExecutor` 接口与 `ToolCall` 形状 | `AgentModelClient.kt:577-597` |
| 每轮工具名集合与参数校验器在 `AgentLoop` 内重建 | `AgentLoop.kt:146-157`、`:473-489` |
| 执行前 `availableToolNames` 判定与资源闸门在 `executeTool` | `AgentLoop.kt:472-538` |
| 执行侧另有 `disabledTools` / 审批 / root / 能力复查 | `AgentLocalTools.kt:276-355`、`:366-421` |
| 提示词技能索引仅 `profile == FULL` 时注入 | `AgentPromptBuilder.kt:265-270` |
| 技能索引正文形态（元信息 + path + 两句约束） | `AgentPromptBuilder.kt:408-438` |
| MCP 快照只有全量 `appendModelTools`，超 64 静默丢弃 | `agent/mcp/McpRunContext.kt:22-49`、`:54-83` |
| MCP 模型名规则 | `McpRunContext.kt:85-97` |
| MCP 执行分流 `RoutingToolExecutor`（`mcp.contains(name)`） | `McpRunContext.kt:284-293` |
| MCP 组装点：run 启动三处 + 预览一处 | `AgentRuntimeRunExecutor.kt:223-231`、`:360-364`、`:428`、`:541`；`PromptStudioWidgets.kt:135` |
| 压缩产物是单条 assistant 摘要消息，header 在 `archiveHeader` | `AgentContextCompactor.kt:107-134` |
| `AGENT_TOOL_GROUP_LOADING` 等键不存在 | `config/Prefs.kt` |
| 计划模式只读白名单 | `agent/tool/PlanModePolicy.kt:16-70` |
| 白名单模式的 `ALWAYS_ALLOWED` | `agent/prompt/AgentToolPolicy.kt:39-52` |

**硬约束（违反即不可实施）**

1. 任何一次开组都不得改变 `tools` 数组字节——这是本方案唯一卖点，靠 `toolsForRound()` 单调增补保证。
2. `AgentToolGroups` 只能用 `org.json` + Kotlin stdlib，**禁止**依赖 Android 框架（arm64 上 Robolectric 静默跳过，纯 JVM 测试是唯一防线）。
3. `FULL` 档（灰度关）输出必须与改动前**逐字节一致**。
4. 解包点唯一在 `AgentLoop.executeTool`——`RoutingToolExecutor` 在其下层靠 `mcp.contains(name)` 分流，任何更靠内的解包都会让 `mcp_` 真名到不了 MCP 执行器（`McpRunContext.kt:284-293`）。
5. 不给 `AgentLocalTools.execute` 的既有分支加第二套权限判定；解得包交给下游，六道闸与执行侧复查自然生效。

---

## 2. 目标 / 非目标

**目标**

- MCP：未打开的 server 不进 `tools`，只留一行索引；打开后仍走 `tool_call`，前缀零变化。
- 技能：写/装/发现三个大 schema 进 `skill` 组；读四件套常驻；索引注入判据改为「目录里真有 `skills_read`」。
- 通用：任何未打开的静态组都不进 `tools`，模型用 `tools_use_group` 打开、`tool_call` 调用。
- 全链路可回滚：一个 Prefs 开关回到今天的全量行为。

**非目标**

- 不做 `tools_search`（关键词点单）——用户明确暂不需要。
- 不做 `nativeGroups`（D3）。
- 不改 MCP 模型名规则、不改 `MAX_RUN_TOOLS=64` 的数值，只改「截断在哪一层发生」及其可见性。
- 不改审批档（AUTO/STANDARD/STRICT/SMART）语义，只保证它们看到真名。
- 不动子代理 `SubagentExecutor` 的裁剪逻辑（`profile.filterTools` 保持不变）。

---

## 3. 数据流与状态机

```
AgentRuntimeRunExecutor
  ├─ groupState = AgentToolGroupState(seed = historyOpenedGroups)   // 新
  ├─ mcpSnapshot.load() → 实现 McpToolGroupProjection              // 改
  └─ AgentModelClient.complete(..., groupState, mcpProjection)
        ├─ toolsFor(): AgentToolDirectory.buildWithOutcome(..., openedGroups = groupState.snapshot())
        │     ├─ withheld = 未打开的延迟组成员
        │     ├─ deferredSchemas = 被扣留工具的 schema（供内层校验）
        │     └─ openableGroups = 通过 gate 且未打开
        ├─ system 段: + groupIndexSection(openableGroups)   // 只依赖可选组集合
        └─ AgentLoop(..., deferredTools = deferredSchemas, groupState)
              └─ executeTool: AgentToolForwarding.resolve(call, opened, mcpIds, basicNames)
                    ├─ Rejected → rejectedToolOutcome(错误码 + 修正指引)
                    ├─ Direct   → 现状路径（六道闸 → 资源闸门 → realExecutor）
                    └─ Forward  → deferredValidator.validate(inner) → 六道闸(inner) → 闸门(inner) → realExecutor(inner)
```

状态归属：

| 状态 | 生命周期 | 存储 |
| --- | --- | --- |
| `openedGroups` | 单 run 内单调增 | `AgentToolGroupState`（`ConcurrentHashMap.newKeySet()`），run 对象持有 |
| 历史已打开组 | 跨 run 恢复 | 从 messages 的 `tools_use_group` / `tool_call` 调用 + 压缩摘要 header 反推 |
| MCP server 集合 | 单 run 冻结 | `McpRunSnapshot` |
| 档位 | 持久 | `LocalToolProfileStore`（FULL/CHAT）× `Prefs.AGENT_TOOL_GROUP_LOADING` × `providerType` |

---

## 4. 详细改动清单

### 4.1 PR-S1 · 技能三修（最小、先落）

**S1-1 `AgentPromptBuilder.buildSystemMessages` 增可选参数**

`agent/model/AgentPromptBuilder.kt:52`：

```kotlin
fun buildSystemMessages(
    config: ModelConfig,
    skillContext: SkillContext?,
    memoryContext: MemoryContext,
    rootAvailable: Boolean,
    roleplayContext: RoleplayContext?,
    executionContext: ExecutionContext?,
    virtualScreenEnabled: Boolean = false,
    virtualScreenFallbackEnabled: Boolean = false,
    customization: PromptCustomization = PromptCustomization.None,
    planMode: Boolean = false,
    jailbreak: PromptJailbreak = PromptJailbreak.None,
    expert: ExpertDefinition? = null,
    availableToolNames: Set<String>? = null,   // 新增：null = 沿用旧判据
): List<JSONObject>
```

把 `:265-270` 改为：

```kotlin
val activeProfile = LocalToolProfileStore.current(config.providerId, config.model)
val skillToolsResident = availableToolNames?.contains(AgentSkillToolCatalog.READ) ?: (activeProfile == LocalToolProfile.FULL)
if (skillToolsResident) buildSkillSystemMessage(skillContext)?.let(messages::put)
```

> 用**可选参数 + null 语义**而不是必填：`buildSystemMessages` 现有 20 处调用（`AgentPromptBuilderTest` 17 处、`PromptStudioWidgets.kt:123`、`AgentModelClient.kt:169/241`），必填会污染 17 个测试。null 表示「调用方不知道目录」，退回旧行为，测试零改动。

**S1-2 `/skill:<id>` 标记段**

`AgentPromptBuilder` 内新增（在技能索引之后注入，仅当 `skillContext != null`）：

```kotlin
private const val SKILL_MENTION_REGEX_SOURCE = "(?:^|\\s)/skill:([A-Za-z0-9._-]{1,64})"

/**
 * 用户消息里 `/skill:<id>` 是显式点名：优先用 skills_read 读该技能，不要当成普通文本。
 * 只在技能工具常驻时注入，否则又是一句读不到的指引。
 */
private fun skillMentionSection(enabled: Boolean): String? = ...
```

判定：`if (skillToolsResident) messages += system(skillMentionSection())`，内容 2 句、约 120 字。

**S1-3 无代码改动**：技能读四件套保持常驻，写/装三件套的组归属放到 PR-G1 的组表里（避免 S1 依赖尚未存在的 `AgentToolGroups`）。

**验收（S1）**

- `AgentPromptBuilderTest` 既有用例全绿（`availableToolNames = null` 走旧路径）。
- 新增：`availableToolNames = emptySet()` → 无技能索引；`= setOf("skills_read")` → 有索引；`= null` 且 profile=FULL → 有索引。

---

### 4.2 PR-M1 · MCP 投影（第二落，独立可测）

**M1-1 新文件 `agent/mcp/McpToolGroupProjection.kt`（约 90 行）**

```kotlin
internal interface McpToolGroupProjection {
    /** 每 server 一组，id = mcpGroupId(serverId)；与目录追加必须同一映射。 */
    fun deferredGroups(): List<AgentToolGroup>

    /**
     * @param openedGroupIds null = 全部（FULL 档 / 提示词预览）；空集 = 一个都不追加。
     */
    fun appendTools(destination: JSONArray, openedGroupIds: Set<String>?)
}
```

**M1-2 `McpRunSnapshot` 实现它（`McpRunContext.kt:22-49`、`:54-83`）**

```kotlin
internal class McpRunSnapshot(...) : McpToolGroupProjection {
    // 冻结时算一次：组表 + 每组的工具名（≤ MCP_SUMMARY_TOOL_LIMIT = 6 用于索引）
    private val groups: List<AgentToolGroup> by lazy { ... }
    private val toolsByGroup: Map<String, List<JSONObject>> by lazy { ... }
    private val truncated: Map<String, Int> by lazy { ... }   // 超 MAX_RUN_TOOLS 被略过的数量

    override fun deferredGroups(): List<AgentToolGroup> = groups

    override fun appendTools(destination: JSONArray, openedGroupIds: Set<String>?) {
        val want = openedGroupIds ?: groups.map { it.id }.toSet()
        want.forEach { id -> toolsByGroup[id]?.forEach(destination::put) }
    }

    fun toolDocs(groupId: String, max: Int = 24): List<String>   // 回执语料，读压缩后的 description
}
```

关键改动点：

1. `load()` **不再**在 `:72-73` `return@forEach` 静默丢弃：超 `MAX_RUN_TOOLS` 的工具名记入 `truncated[groupId]`，并在组 summary 里追加「本 server 共 N 个工具，本次可下发 64 个」。
2. `compactMcp` 前移到快照构造时执行一次，`appendModelTools` / `toolDocs` / 内层校验读同一份（今天在 `AgentModelClient.kt:177-181` 做，MCP 场景下与预览页不同源）。
3. 保留 `appendModelTools(destination)` 作为 `appendTools(destination, null)` 的别名，避免一次改完所有调用点。

**M1-3 `AgentRuntimeRunExecutor`（`:223-231`、`:428`）**

```kotlin
val mcpTools = JSONArray()
// FULL 档保持今天的全量；GROUP 档一个都不追加，改由 client 按 opened 投影
if (!groupLoading) mcpSnapshot.appendTools(mcpTools, openedGroupIds = null)
runTools = JSONArray(mcpTools.toString()).also { /* 子代理工具保持原逻辑 */ }
```

并把 `mcpSnapshot` 作为 `mcpProjection` 传给 `complete(...)`。

**M1-4 预览页（`PromptStudioWidgets.kt:135`）**

```kotlin
McpRunSnapshot.load().appendTools(tools, openedGroupIds = if (groupLoading) emptySet() else null)
```

> 这正是 `AgentToolDirectory` KDoc 里「预览 19 个、实际发 34 个」的同类问题，必须同源。

**验收（M1）**

- FULL 档：`AgentToolCatalogTest` 快照不变；MCP 工具数、顺序、字节与改动前一致。
- GROUP 档：`mcp_` 工具名在 `tools` 中出现 0 次；`tools_use_group` 回执的 `tool_docs` 与内层校验用的 schema 一致（同源断言）。
- 截断：构造 70 个工具的假 server，回执含 `truncated: {limit: 64, omitted: 6}`，且组 id 可打开、内层前 64 个名字可调用。

---

### 4.3 PR-G1 · 工具组与转发（主菜）

#### G1-1 新文件 `agent/model/AgentToolGroups.kt`（约 330 行）

```kotlin
internal data class AgentToolGroup(
    val id: String,
    val summary: String,
    val members: Set<String>,
    val deferred: Boolean,
)

internal object AgentToolGroups {
    const val USE_GROUP = "tools_use_group"
    const val CALL = "tool_call"
    const val MCP_PREFIX = "mcp:"
    const val MAX_GROUP_OPENS_PER_RUN = 3
    const val MCP_SUMMARY_TOOL_LIMIT = 6
    const val OPENED_GROUPS_MARKER = "[Eta 已打开工具栏 v1: "

    /** 常驻组：basic。members 由显式集合给出，见下表。 */
    val basic: AgentToolGroup

    fun staticGroups(): List<AgentToolGroup>          // 不含 basic、不含 mcp:*
    fun mcpGroupId(serverId: String): String = MCP_PREFIX + serverId
    fun isMcpGroupId(id: String): Boolean = id.startsWith(MCP_PREFIX)
    fun allGroups(mcp: List<AgentToolGroup>): List<AgentToolGroup> = staticGroups() + mcp

    /** 静态成员反查（含 basic）；返回 null 表示未登记。 */
    fun groupOfMember(name: String): AgentToolGroup?

    fun withheld(opened: Set<String>, disabled: Set<String>, mcp: List<AgentToolGroup>): Set<String>
    fun openable(gatePass: (String) -> Boolean, opened: Set<String>, mcp: List<AgentToolGroup>): List<AgentToolGroup>
    fun indexMessage(openable: List<AgentToolGroup>): String?
    fun usage(groupId: String): String
    fun openedGroupsFromHistory(messages: JSONArray, mcp: List<AgentToolGroup>): Set<String>
}
```

**分组表（实施时以 `ToolCountProbeTest` 校准）**

| 组 id | 成员来源 | 说明 |
| --- | --- | --- |
| `basic`（常驻） | 显式集合 | `ask_user`、`update_plan`、`get_current_context`、`device_status`、`web_search`、`fetch_url`、`wait`、`memory_get`、`character_memory_get`、`skills_list`、`skills_read`、`skills_read_resource`、`skills_list_curated`、`subagent`、`tasks_list`、`conversation_history` |
| `screen` | `AgentToolRequirements.domainOf(n) == SCREEN` | 屏幕操作全部 |
| `file` | `agent/file` 与终端文件工具显式集合 | `read_file`、`write_file`、`edit_file`、`list_directory`、`stat_file`、`glob_files`、`grep_files`、`read_image`、`search_files`、`search_downloads` 等 |
| `terminal` | `domainOf == SHELL` | `terminal`、`terminal_job`、`terminal_daemon`、`check_root` |
| `device` | `domainOf == DEVICE` | 设置/闹钟/日历/剪贴板等 |
| `personal` | `find(n)?.colorOs == true` | 个人数据检索（ColorOS 专属） |
| `browser` | `domainOf == BROWSER` | `browser_use` |
| `skill` | 显式：`skills_manage`、`skills_inspect_github`、`skills_install_from_github` | 写/装/发现，低频大 schema |
| `expert` | 显式：`expert_manage`、`subagent_profile` | 专家/子代理定义 |
| `mcp:<serverId>` | `McpRunSnapshot.deferredGroups()` | 每 server 一组，**永不进常驻** |

**成员覆盖不变式（必须有测试）**：`AgentToolRequirements.toolNames` 中除两个入口外的每个名字，必须落在恰好一个组（basic 或某个 deferred 组）。落不进 → 测试失败并打印名单，避免「工具被静默扣留且无法打开」。

#### G1-2 新文件 `agent/model/AgentToolGroupState.kt`（约 45 行）

```kotlin
/** run 级已打开工具组；只增不减，线程安全。 */
internal class AgentToolGroupState(seed: Set<String> = emptySet()) {
    private val opened = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(seed) }
    fun snapshot(): Set<String> = opened.toSet()
    fun isOpen(id: String): Boolean = id in opened
    /** @return true=本次新开；false=已打开（调用方据此回 ALREADY_OPEN，不重复付) */
    fun open(id: String): Boolean = opened.add(id)
    fun size(): Int = opened.size
}
```

#### G1-3 新文件 `agent/model/AgentToolGroupCatalog.kt`（约 230 行）

```kotlin
internal object AgentToolGroupCatalog {
    fun appendTo(tools: JSONArray, openableIds: List<String>, maxOpensPerRun: Int = AgentToolGroups.MAX_GROUP_OPENS_PER_RUN)

    fun useGroupResult(
        group: AgentToolGroup,
        usableTools: List<String>,
        toolDocs: List<String>,
        callVia: String,           // "tool_call"
        alreadyOpen: Boolean,
        opensLeft: Int,
        truncated: Int,
    ): String

    fun reject(code: String, message: String, hint: String? = null): String
}
```

`tools_use_group` schema（**字节恒定**，不随 openable 变化——openable 只进描述文字，且描述文字必须只依赖组集合的稳定序列，不要拼时间/随机）：

```json
{"name":"tools_use_group","description":"打开一个延迟工具组，拿到组内工具清单与参数说明。打开后本轮就能用 tool_call 调用。","parameters":{"type":"object","properties":{"group":{"type":"string","description":"组 id，例如 screen、file、mcp:abc123"}},"required":["group"]}}
```

`tool_call` schema：

```json
{"name":"tool_call","description":"调用延迟组里的工具。tool 填真实工具名，arguments 填该工具的参数对象。","parameters":{"type":"object","properties":{"tool":{"type":"string"},"arguments":{"type":"object"}},"required":["tool"]}}
```

**回执形状**（成功）：

```json
{"ok":true,"group":"screen","tools":["observe_screen","tap",...],"tool_docs":["observe_screen: ...","tap: ..."],
 "usage":"屏幕操作要点：先 observe_screen 再 tap；坐标基于最近一次观察。",
 "available":"now","call_via":"tool_call","opens_left":2,
 "message":"已打开 screen；本轮可直接用 tool_call 调用；下轮起仍然有效，无需重复打开。"}
```

失败码见附录 A。

#### G1-4 `AgentToolForwarding`（并入 `AgentToolGroups.kt` 或独立文件，约 110 行）

```kotlin
internal object AgentToolForwarding {
    sealed interface Resolution
    data class Direct(val call: AgentModelClient.ToolCall) : Resolution
    data class Forward(val call: AgentModelClient.ToolCall, val groupId: String) : Resolution
    data class Rejected(val code: String, val message: String, val hint: String? = null) : Resolution

    fun resolve(
        call: AgentModelClient.ToolCall,
        opened: Set<String>,
        mcpGroups: List<AgentToolGroup>,
        basicNames: Set<String>,
    ): Resolution
}
```

判定表（必须逐条落测试）：

| 输入 `call.name` | 条件 | 结果 |
| --- | --- | --- |
| `tools_use_group` | — | `Direct`（由执行侧开组） |
| `tool_call` | `arguments.tool` 空 | `Rejected(TOOL_CALL_MISSING_TOOL)` |
| `tool_call` | `tool ∈ {tool_call, tools_use_group}` | `Rejected(TOOL_CALL_RECURSIVE)` |
| `tool_call` | `arguments` 不是对象（含字符串） | `Rejected(TOOL_CALL_INVALID_ARGUMENTS)`，提示「arguments 必须是 JSON 对象，不要传字符串」 |
| `tool_call` | `tool` 反查不到组 | `Rejected(UNKNOWN_TOOL)` |
| `tool_call` | 组未打开 | `Rejected(TOOL_GROUP_NOT_LOADED, hint = 组 id)` |
| `tool_call` | 组已打开 | `Forward(ToolCall(name=arguments.tool, argumentsJson=arguments 序列化), groupId)` |
| 其他 | `name ∈ basicNames` | `Direct` |
| 其他 | `groupOfMember(name)` 命中某个 deferred 组 | `Rejected(TOOL_NOT_LOADED_USE_TOOL_CALL, hint = 组 id)`（时序纠正；今天这种调用会直接 `UNKNOWN_TOOL` ▸ `AgentLocalTools.kt:518-521`） |
| 其他 | 未登记 | `Direct`（保持今天的行为：MCP 真名、宿主工具都走这条路） |

> 最后一行是关键：**未登记的 `mcp_` 真名必须继续 `Direct`**，否则 MCP 全挂。`Forward` 只在 `tool_call` 分支产生。

#### G1-5 `AgentToolCatalog.build` 增参（约 15 行）

```kotlin
fun build(..., groupLoading: Boolean = false, maxOpensPerRun: Int = 3): JSONArray {
    ...
    if (groupLoading) {
        val openable = AgentToolGroups.openable(gatePass = { ... }, opened = ..., mcp = ...) // 由调用方准备
        AgentToolGroupCatalog.appendTo(tools, openable.map { it.id }, maxOpensPerRun)
    }
    ...
}
```

`groupLoading=false` 时一个字节都不加 → `AgentToolCatalogTest` 的既有断言天然通过。

#### G1-6 `AgentToolDirectory` 增「扣留 + 出参」（约 75 行）

```kotlin
internal class DirectoryOutcome(
    val tools: JSONArray,
    val deferredSchemas: JSONArray,   // 被扣留工具的 schema，供内层校验
    val openableGroups: List<AgentToolGroup>,
)

fun build(... 原参数 ..., groupLoading: Boolean = false, openedGroups: Set<String> = emptySet(),
          mcpGroups: List<AgentToolGroup> = emptyList(), maxOpensPerRun: Int = 3): JSONArray =
    buildWithOutcome(...).tools

fun buildWithOutcome(...): DirectoryOutcome
```

`buildWithOutcome` 的顺序（**顺序本身就是正确性**）：

1. `rewriteReply` / `CHAT` → 空结果。
2. `gate` 与 `availability` 算出 `openable`（未打开、且组内有至少一个可用工具）。
3. `withheld = AgentToolGroups.withheld(openedGroups, disabledTools, mcpGroups)`。
4. 委托 `AgentToolCatalog.build(..., groupLoading = groupLoading)`。
5. 遍历成品：命中 `withheld` 的移入 `deferredSchemas`，其余留下。**同时修掉既有 bug**：把 `disabledTools` 真正传进来（今天 `AgentModelClient.kt:183-209` 没传，KDoc 承诺未兑现）。
6. `additionalTools` 同样过 `withheld` 与 `isToolEnabled`（MCP 在 GROUP 档不会有内容进这里，但要防其他来源）。
7. `policy.filterTools`、`restrictedToolNames` 保持现状。
8. `expertFilterTools` 保持现状。

#### G1-7 `AgentModelClient`（约 55 行）

```kotlin
fun complete(
    ...,
    groupState: AgentToolGroupState? = null,          // 新
    mcpProjection: McpToolGroupProjection? = null,    // 新
    groupLoading: Boolean = false,                    // 新（由 Prefs × providerType 算好传入）
)
```

- `toolsFor(capabilities)`：调 `buildWithOutcome(...)`，缓存 `lastDeferredSchemas` 与 `lastOpenableGroups`。
- `:169` / `:241` 的 `buildSystemMessages(...)` 都追加 `availableToolNames = 当前目录真实名字集` 与 `groupIndex = openableGroups`。
- `toolsForRound()`：**逻辑不变**（开组后目录确实变了，但变化被 `withheld` 吸收——开组后该组成员**仍然被扣留**，所以 `tools` 字节不变；`deferredSchemas` 需要刷新，单独更新它，不触发 `systemMessages` 重建）。
- 把 `deferredSchemas`、`groupState` 传给 `AgentLoop`。

> 这一点是 D3 的直接推论：**开组不改 `tools`，只改 `system` 索引那一段与 `deferredSchemas`**。索引段依赖 `openable`，开组后 ``openable` 变少 → 索引变短 → 前缀会变一次；这不可避免（否则模型不知道开过什么），但成本是「一行索引」，不是「几十个 schema」。

#### G1-8 `AgentLoop`（约 65 行）

`agent/model/AgentLoop.kt:472-538` 内插入：

```kotlin
val resolution = if (groupState == null) AgentToolForwarding.Resolution.Direct(toolCall)
    else AgentToolForwarding.resolve(toolCall, groupState.snapshot(), mcpGroups, AgentToolGroups.basicNames)
val effective = when (resolution) {
    is Rejected -> return rejectedToolOutcome(round, toolCall, resolution.code, resolution.message, resolution.hint)
    is Direct -> resolution.call
    is Forward -> resolution.call
}
```

后续全部改用 `effective`：

- `effective.name !in availableToolNames` 的判定：`Forward` 跳过（组已打开，且已由 resolve 保证成员存在）；`Direct` 保持。
- 参数校验：`Forward` 用 `deferredValidator.validate(effective)`（`deferredValidator = AgentToolCallValidator(deferredTools)`，每次 `toolsForRound` 后重建）；`Direct` 用现有 `toolCallValidator`。
- `ToolStarted(name = effective.name, ...)`：**用真名**，轨迹/审批卡才显示人类能懂的名字。
- `AgentResourceGate.dispatch(toolName = effective.name, ...)`：真名 → 域键正确（`SHELL:<conversationKey>/shell`、`MCP:<server>`），不会退化成 `DEVICE:fallback:tool_call`（`AgentResourceGate.ticketOf` 的未登记回退）。

#### G1-9 `AgentLocalTools`（约 85 行）

- 构造参数新增 `groupState: AgentToolGroupState? = null`、`mcpGroups: List<AgentToolGroup> = emptyList()`（默认值保证 `AgentExperienceReview.kt:122` 那个构造点不改也能编译）。
- `when (toolCall.name)` 前插入显式分支：

```kotlin
if (toolCall.name == AgentToolGroups.USE_GROUP) return@runCatching useToolGroup(args)
if (toolCall.name == AgentToolGroups.CALL) return@runCatching errorResult("TOOL_CALL_UNRESOLVED", "tool_call 应在进入执行前被解包")
```

- `useToolGroup(args)`：组存在 → 未打开 → 组内可用工具非空（否则 `TOOL_GROUP_DISABLED`）→ `opensLeft` 校验（`TOOL_GROUP_LIMIT_REACHED`）→ `groupState.open(id)` → 回执（含 `tool_docs` 从目录 schema 取 description 截 200 字，与 MCP 同源规则）。

#### G1-10 注册与白名单（3 行，但缺一不可）

| 文件 | 改动 | 缺了会怎样 |
| --- | --- | --- |
| `AgentToolRequirements.kt` | `register(RootRequirement.NONE, ToolDomain.NONE, "tools_use_group", "tool_call")` | `project()` 静默 `continue` 丢弃 → 工具永远不进目录（`AgentToolRequirements.kt:289-310`）；`rootRequirement()` 缺登记直接抛异常 |
| `PlanModePolicy.kt` | `READ_ONLY_TOOLS += "tools_use_group"` | 计划模式下改被 `PLAN_REQUIRED_FIRST` 挡住，模型无法先看清单再写计划 |
| `AgentToolPolicy.kt` | `ALWAYS_ALLOWED += "tools_use_group"` | `ALLOW_LIST` 模式下被 `filterTools` 滤掉 → 提示词教模型调、请求里没有（同一类 2026-11-20 错位） |

`tool_call` **不要**进 `READ_ONLY_TOOLS`：它的只读性取决于内层真名，由解包后的六道闸判定。

#### G1-11 `AgentPromptBuilder` 组索引段（约 45 行）

- 新增可选参数 `groupIndex: List<AgentToolGroup> = emptyList()`。
- 在技能索引之后注入一段 system（**仅当 `groupIndex` 非空**）：

```
延迟工具组（schema 未加载，调用前先打开）：
- screen | 屏幕操作：观察、点击、滑动、输入
- file | 本机文件：读、写、编辑、检索
- mcp:abc123 | MCP 服务器「GitHub」：search_repos、get_file 等 27 个工具
需要其中工具时先调 tools_use_group(group=<id>) 拿到清单与参数说明，再用 tool_call(tool=<名字>, arguments={...}) 调用。
不要把未打开组里的工具当成不可用，也不要凭印象编造参数。
```

字节稳定性：内容只依赖 `openable` 的有序列表（`staticGroups()` 固定顺序 + MCP 按 serverId 排序）。

#### G1-12 跨 run 恢复（`AgentToolGroups.openedGroupsFromHistory`，约 60 行）

三个来源取并集：

1. 历史 assistant `tool_calls` 中 `name == tools_use_group` 的 `arguments.group`；
2. 历史 assistant `tool_calls` 中 `name == tool_call` 的 `arguments.tool` → `groupOfMember`（MCP 真名 → `mcp_` 前缀推导 server 组）；
3. `_eta_context_summary == true` 的消息内容里的 `[Eta 已打开工具栏 v1: a, b, c]`。

`AgentContextCompactor.kt:107-119` 的 `archiveHeader` 追加一行：

```kotlin
val opened = openedGroupsProvider()      // 构造参数，默认 { emptySet() }
if (opened.isNotEmpty()) append("\n[Eta 已打开工具栏 v1: ${opened.sorted().joinToString(", ")}]")
```

> 空集时**一个字节都不加**，`AgentContextCompactorTest` 现有快照不受影响。

**验收（G1）**

- `GroupsCoverageTest`：每个登记工具恰好一个组。
- `ForwardingTableTest`：判定表 10 行全覆盖。
- `GroupLoadingByteTest`：FULL 档 `tools.toString()` 与基线快照**完全相等**；GROUP 档首轮字符数下降 ≥ 50%；开组后再取一次，`tools.toString()` 仍然相等。
- `GroupGateTest`：STRICT 档 `tap` 经 `tool_call` 出**真名**审批卡；READ_ONLY 档返回 `TOOL_REVIEW_READ_ONLY`；计划模式 `tools_use_group` 放行、`tap` 返回 `PLAN_REQUIRED_FIRST`。
- `GroupResourceGateTest`：`tool_call(terminal)` 的域键是 `SHELL:<conversationKey>/shell`，不是 `DEVICE:fallback:tool_call`。

---

### 4.4 PR-D1 · 配置与文档

- `Prefs.kt` 新增 `Keys.AGENT_TOOL_GROUP_LOADING = "agent_tool_group_loading"`（读缺失 = true，与 `AGENT_RESOURCE_GATE_ENABLED` 同风格）。
- 新增 `AgentToolLoadingPolicy`（约 40 行，纯函数便于测试）：

```kotlin
internal object AgentToolLoadingPolicy {
    val GROUP_PROVIDER_TYPES = setOf(ProviderTypes.ANTHROPIC, ProviderTypes.OPENAI_COMPATIBLE, ProviderTypes.GEMINI)
    fun enabled(prefEnabled: Boolean, providerType: String): Boolean = prefEnabled && providerType in GROUP_PROVIDER_TYPES
    fun groupLoading(profile: LocalToolProfile, prefEnabled: Boolean, providerType: String): Boolean =
        profile == LocalToolProfile.FULL && enabled(prefEnabled, providerType)
}
```

- `docs/TOOL_GROUP_LOADING.md`：§3.1 档位改为「FULL × 灰度」；§3.6 删 `nativeGroups`，留「为什么不做」（D3）；分组表补 `skill` / `mcp:<serverId>`。

---

## 5. 测试清单与命令

**新增测试（全部纯 JVM，不碰 Robolectric）**

| 文件 | 断言 |
| --- | --- |
| `agent/model/AgentToolGroupsTest.kt` | 覆盖不变式、`withheld`、`indexMessage` 字节稳定、`groupOfMember`、MCP 组 id 映射 |
| `agent/model/AgentToolForwardingTest.kt` | 判定表 10 行 + `arguments` 传字符串被拒 |
| `agent/model/AgentToolDirectoryGroupTest.kt` | GROUP 扣留 / FULL 字节相等 / `deferredSchemas` 内容正确 / `disabledTools` 生效 |
| `agent/model/AgentToolGroupStateTest.kt` | 单调、并发开组不丢、`open` 返回值 |
| `agent/mcp/McpToolGroupProjectionTest.kt` | 投影按 opened 过滤、64 截断上报、`appendTools(dest, null)` 与旧 `appendModelTools` 等价 |
| `agent/model/AgentToolLoadingPolicyTest.kt` | 四类 provider × 开关 × 档位 8 组组合 |
| `AgentToolCatalogTest`（改） | `groupLoading=false` 快照不变；`=true` 多两个入口 |
| `ToolCountProbeTest`（改） | 输出 `resident=… deferred=… est_chars=…`，作为文档口径依据 |

**命令**

```bash
./gradlew :app:testDebugUnitTest --tests "*AgentToolGroupsTest" --tests "*AgentToolForwardingTest" \
  --tests "*AgentToolDirectoryGroupTest" --tests "*AgentToolGroupStateTest" \
  --tests "*McpToolGroupProjectionTest" --tests "*AgentToolLoadingPolicyTest"
./gradlew :app:testDebugUnitTest            # 基线：1777 tests / 44 failed / 91 skipped（失败全为 arm64 Robolectric）
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

**基线纪律**：失败数不得高于 44；高于即自己引入问题，先修再看。

---

## 6. 可行性分析

| 维度 | 结论 | 依据 |
| --- | --- | --- |
| 编译面 | 低风险 | 所有新参数带默认值，20 处 `buildSystemMessages`、3 处 `AgentLocalTools(...)`、4 处 `build` 调用点无需同批修改 |
| 运行面 | 低风险 | 解包在 `AgentLoop.executeTool`，六道闸 + 审批 + 闸门都只读 `call.name`，拿到的自动是真名，**不需要改任何闸** |
| 回滚 | 一个开关 | `Prefs.AGENT_TOOL_GROUP_LOADING=false` → `groupLoading=false` → 目录与今天逐字节一致 |
| 灰度 | `providerType` 三维 | custom 默认 OFF；出现异常只需把该 provider 移到 OFF 集合 |
| 性能 | 净收益 | 每轮少 8–11k token（MCP 64 工具）与 5–8k token（screen/device/file 组），代价是每次调用多一层 `resolve`（纯内存 JSON 解析，微秒级） |
| 状态一致性 | 单写者 | `openedGroups` 只有 `useToolGroup` 一处写；`toolsForRound` 只读快照 |
| 并发 | 无新锁 | `AgentToolGroupState` 用并发集合；跨会话不共享（run 级） |
| 恢复 | 三源并集 | 历史调用 + 压缩摘要标记；两者都失效时最坏是让模型重开一次（有 `TOOL_GROUP_NOT_LOADED` 的可执行指引，不是死路） |
| 可测试性 | 高 | 分组表、`resolve`、`withheld`、投影全是纯函数/纯对象 |
| 上游合并 | 有摩擦 | 新文件 4 个 + 改动 9 个文件；`AgentModelClient`/`AgentLoop`/`PromptBuilder` 是上游高频文件，建议两个 PR 之间不跨上游同步点 |

**已知摩擦点（实施时必须留意）**

1. `AgentPromptBuilder` 增 system 段会改变 `systemCount`，而 `systemCount` 同时被 `trimInPlace`（`AgentModelClient.kt:212-221`）与压缩器（`AgentContextCompactor.kt:42`）使用。两处都由同一个 builder 计算，保持一致即可，但**不要**在别处硬编码 `2` 之类的系统消息条数。
2. `toolsForRound()` 的「能力变化才重建」判定不含 `openableGroups`。开组会让索引段变化，所以 `:241-243` 的 `systemMessages` 必须能感知 `openable` 变化——实施时把 `lastOpenable` 加入重建条件，但**不要**把它加入 `tools` 重建条件（`tools` 不该变）。
3. `AgentLoop.kt:146-157` 重建 validator 时，`deferredValidator` 也要一并重建，否则内层 schema 用的是上一轮的。

---

## 7. 多维论证

**7.1 正确性：为什么「开组不改 tools」是安全的**

模型对工具的认知来自两处：`tools` 里的 schema（可调用性）与 system 里的说明（知识）。开组只改后者，不改前者，意味着「可调用性」由 `tool_call` 这个恒定入口提供——它是一个**总在目录里**的工具，参数是 `{tool, arguments}`，语义与今天 MCP 的转发完全同构。所以「开组」不是一次能力变更，而是一次**知识变更**；知识可以随时刷新（system 变一次），能力声明保持稳定（tools 不变）。

**7.2 缓存：三类字节变化的成本排序**

| 变化 | 影响面 | 频率 | 成本 |
| --- | --- | --- | --- |
| tools 变 | 全前缀重付（Anthropic 在 tools→system→messages 各设断点，`AnthropicMessagesProvider.kt:174`） | 若做 nativeGroups：每晋升一次 | 高 |
| system 变 | system 之后的 messages 重付 | 每次开组（1 次/组） | 中 |
| messages 追加 | 仅新增部分 | 每轮 | 低 |

本方案把「开组」从第一类降到第二类，且第二类的代价是**一行索引**而不是几十个 schema。

**7.3 权限：六道闸为什么不用改**

`AgentModelClient.kt:292-344` 的六个分支全部只读 `call.name`，而 `call` 是 `AgentLoop` 传进来的（解包发生在传参之前）。这是一个**结构性**结论而非约定：只要解包点在 `AgentLoop`，闸链看到的就是真名。

**7.4 计划模式：`tools_use_group` 的定位**

它不执行任何外部副作用，只改 run 内集合，等同 `update_plan`/`ask_user` 一类的元操作 → 进只读白名单。`tool_call` 不进：它的副作用完全由内层真名决定，由解包后的判定负责。

**7.5 并发：资源域键**

`AgentResourceGate.ticketOf` 对未登记工具回退 `DEVICE:fallback:<name>`（并打点 `perf.gate.unregistered`）。若在 `AgentLocalTools` 内解包，闸门会先看到 `tool_call`，把屏幕、文件、MCP 全串成一条队列——这是**功能性回归**，不是性能问题。解包点在 `AgentLoop` 直接规避。

**7.6 恢复：最坏情况可执行**

三源并集失效（压缩把开组调用折掉且摘要未带标记）时，模型会收到 `TOOL_GROUP_NOT_LOADED` + `hint=<组 id>`，可以立即重开。最坏成本是一次多余的 `tools_use_group` 往返，不是任务失败。

**7.7 可维护性：单一事实源**

- 组表：`AgentToolGroups`（唯一）
- 组 id ↔ MCP server：`mcpGroupId`（唯一）
- 目录：`AgentToolDirectory.buildWithOutcome`（唯一，预览同源）
- 解包：`AgentToolForwarding.resolve`（唯一，纯函数）
- 档位：`AgentToolLoadingPolicy`（唯一，纯函数）

**7.8 与 Expressive 的差异记账**

| 差异 | 原因 |
| --- | --- |
| 无 `tools_search` | 用户决策；por 的工具总数低于 Expressive，组索引足够定位 |
| 无 `nativeGroups` | D3 |
| 无 `tool_call` 的 MCP 专属分支 | por 的 `RoutingToolExecutor` 天然按名字分流，不需要 `mcpViaCall` 判定 |
| 档位由派生而非枚举 | D4 |

**7.9 反对意见与反证**

| 反对 | 反证 |
| --- | --- |
| 「多一层转发，弱模型会写错参数」 | `arguments` 是 JSON 对象，不是字符串；`AgentToolCallValidator` 对内层做同一套 schema 校验，错了会得到逐字段错误而不是静默执行 |
| 「组索引也是 token，不如全量」 | 索引一行 vs 组内 20 个工具 5–8k token，量级差 100 倍 |
| 「开组后模型可能忘记已经开过」 | 已开组不再出现在索引里（`openable` 排除已开），且 `tool_call` 的失败码明确说「组已打开/未打开」 |
| 「MCP 组化会丢工具」 | 截断显式上报 `truncated`，且组 summary 写明总数；不会出现「打开成功但 0 个工具」 |
| 「一处漏改就白做」 | `GroupsCoverageTest` + `GroupLoadingByteTest` 是确定性守卫，不是人肉检查 |

---

## 8. 验收判据（合入前逐条打勾）

1. `FULL` 档 `tools.toString()` 与基线快照逐字节相等。
2. `GROUP` 档首轮：字符数下降 ≥ 50%，`mcp_` 名字出现 0 次。
3. 开组后再取目录：`tools.toString()` 仍然逐字节相等。
4. `tool_call` 内层调用：`RoutingToolExecutor` 收到 `mcp_` 真名并成功返回。
5. MCP 超限：回执含 `truncated`，且组内前 64 个名字可调用。
6. STRICT 档：`tool_call(tap)` 出的是 `tap` 的真名审批卡。
7. READ_ONLY 档：`tool_call(write_file)` 返回 `TOOL_REVIEW_READ_ONLY`。
8. 计划模式：`tools_use_group` 放行；未提交计划时 `tool_call(tap)` 返回 `PLAN_REQUIRED_FIRST`。
9. 资源域键：`tool_call(terminal)` → `SHELL:<conversationKey>/shell`。
10. 技能：`skills_read` 不在目录时无技能索引；在目录时有索引。
11. 单测基线不恶化（≤ 44 failed），新增用例全绿。

---

## 9. 落地顺序与工时

| 顺序 | PR | 内容 | 估算 |
| --- | --- | --- | --- |
| 1 | PR-S1 | 技能索引判据 + `/skill:` 段 | 0.5 天，约 90 行 |
| 2 | PR-M1 | MCP 投影 + 截断可见 + 预览同源 | 1.5 天，约 300 行 |
| 3 | PR-G1 | 分组表 + 状态 + 转发 + 目录扣留 + 提示词索引 + 恢复 | 3 天，约 900 行（含测试） |
| 4 | PR-D1 | Prefs 键 + 灰度策略 + 文档修订 | 0.5 天 |

分支与提交纪律（AGENTS.md）：

```bash
git switch -c feat/tool-group-loading branch
# 每个 PR 一个提交，合并后立刻删分支
git commit -m "feat(agent): 工具按需加载 PR-S1 技能索引判据改为按真实目录"
```

---

## 附录 A · 错误码

| 码 | 触发 | 模型可见文案要点 |
| --- | --- | --- |
| `TOOL_GROUP_UNKNOWN` | 组 id 不存在 | 列出可打开组 id |
| `TOOL_GROUP_ALREADY_OPEN` | 重复打开 | 直接用 `tool_call`，无需重开 |
| `TOOL_GROUP_DISABLED` | 组内无可用工具（用户关光/能力缺失） | 说明是设置还是能力问题 |
| `TOOL_GROUP_LIMIT_REACHED` | 超过 3 组 | 说明本 run 已到上限 |
| `TOOL_GROUP_NOT_LOADED` | `tool_call` 打到未开组 | 附 `hint=<组 id>`，提示先 `tools_use_group` |
| `TOOL_NOT_LOADED_USE_TOOL_CALL` | 裸名调用了延迟组工具 | 附 `hint=<组 id>` |
| `TOOL_CALL_MISSING_TOOL` | 缺 `tool` | 附正确形状 |
| `TOOL_CALL_RECURSIVE` | `tool` 是入口自身 | 禁止自调用 |
| `TOOL_CALL_INVALID_ARGUMENTS` | `arguments` 非对象 | 明确「要对象，不要字符串」 |
| `TOOL_CALL_UNRESOLVED` | 执行侧收到未解包的 `tool_call` | 内部错误，仅防御 |

## 附录 B · 回执 schema（`tools_use_group`）

```json
{
  "ok": true,
  "group": "screen",
  "tools": ["observe_screen", "tap"],
  "tool_docs": ["observe_screen(…): …", "tap(…): …"],
  "usage": "先 observe_screen 再 tap；坐标基于最近一次观察。",
  "available": "now",
  "call_via": "tool_call",
  "opens_left": 2,
  "truncated": {"limit": 64, "omitted": 6},
  "message": "已打开 screen；本轮可直接用 tool_call 调用。"
}
```

`truncated` 仅在 `omitted > 0` 时出现（空对象会让模型误以为「总是被截断」）。

## 附录 C · `resolve()` 骨架

```kotlin
fun resolve(call: ToolCall, opened: Set<String>, mcpGroups: List<AgentToolGroup>, basicNames: Set<String>): Resolution {
    if (call.name == AgentToolGroups.USE_GROUP) return Direct(call)
    if (call.name == AgentToolGroups.CALL) {
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return Rejected("TOOL_CALL_INVALID_ARGUMENTS", "tool_call 的 arguments 必须是 JSON 对象。")
        val tool = args.optString("tool").trim()
        if (tool.isEmpty()) return Rejected("TOOL_CALL_MISSING_TOOL", "缺少 tool：请填真实工具名。")
        if (tool == AgentToolGroups.CALL || tool == AgentToolGroups.USE_GROUP) {
            return Rejected("TOOL_CALL_RECURSIVE", "不能通过 tool_call 调用入口自身。")
        }
        val raw = args.opt("arguments")
        val inner = when (raw) {
            null, JSONObject.NULL -> "{}"
            is JSONObject -> raw.toString()
            else -> return Rejected("TOOL_CALL_INVALID_ARGUMENTS", "arguments 必须是对象，不要传字符串。")
        }
        val group = AgentToolGroups.groupOfMember(tool) ?: mcpGroupOf(tool, mcpGroups)
            ?: return Rejected("UNKNOWN_TOOL", "未知工具：$tool")
        if (group.id !in opened) return Rejected("TOOL_GROUP_NOT_LOADED", "工具 $tool 属于未打开的组 ${group.id}。", group.id)
        return Forward(call.copy(name = tool, argumentsJson = inner), group.id)
    }
    if (call.name in basicNames) return Direct(call)
    val owner = AgentToolGroups.groupOfMember(call.name)
    if (owner != null && owner.deferred) {
        return Rejected("TOOL_NOT_LOADED_USE_TOOL_CALL", "工具 ${call.name} 属于延迟组 ${owner.id}：先 tools_use_group 再用 tool_call。", owner.id)
    }
    return Direct(call)   // MCP 真名 / 宿主工具 / 未登记：保持今天的行为
}
```