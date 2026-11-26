# 工具按需加载（Tool Group Loading）实施方案

> 目标仓库：`donglihu/Eta-por`（上游 `Mangi-11/Eta` 的本地 fork）
> 参考实现：`EtaExpressive-main`（5.7.0 线，`agent/model/AgentToolGroups.kt` / `AgentToolSearch.kt`）
> 本文只描述方案，不含代码改动。落地时按 §6 的 PR 顺序推进，一个 PR 一个阶段。
> 文档位于分支 `docs/tool-group-loading` 的 `docs/` 下，不 push 到主线、不开 PR。

---

## 0. 结论与决策记录

**结论**：por 可以移植这套机制，但**不能照搬 Expressive 的实现位置**。Expressive 把开组逻辑塞进 `AgentLocalTools`（一个 15 万字节的 `ToolExecutor`），por 的同名文件已经有 2111 行、并且资源闸门/审批/计划模式/能力投影四套语义都压在 `execute()` 的前 80 行里（`agent/tool/AgentLocalTools.kt:276-451`）。往那里再加一层递归调用，会把"工具调用审查在什么名字上生效"这件事变成不可推理的。

por 的正确插入点是 **`AgentLoop.executeTool` 的唯一出口**（`agent/model/AgentLoop.kt:472-538`）：在进入 `AgentResourceGate.dispatch` 与 `toolExecutor.execute` 之前，把转发调用还原成真实工具调用。这样 por 现存的五道闸、审批卡、资源域键、参数校验全部无感知地作用在真实工具名上。

**决策记录（已由需求方确认）**

| 编号 | 决策 | 依据 |
| --- | --- | --- |
| D1 | 三档 `OFF` / `GROUP`，**默认 `GROUP`**，按 `providerType` 白名单灰度（`anthropic` / `openai_compatible` / `gemini`），`custom` 默认 `OFF` | 用户指定"GROUP 默认开、按 provider 白名单灰度"。白名单必须按 `providerType`（`data/model/Provider.kt:6-11`）而不是 `providerId`：`providerId` 是用户自建的任意字符串（`AgentModelClient.kt:500`），无法枚举，也无法预置白名单 |
| D2 | `MAX_GROUP_OPENS_PER_RUN = 3` | 用户指定。上限的作用不是省 token（同一轮多次开组只重付一次，见 §5.2），而是防"一次全开=等于 OFF 档"的退化 |
| D3 | **不做 `tools_search`**，不做关键词点单工具 | 用户指定。入口收敛为 `tools_use_group` 整组打开 + `tool_call` 转发两个，PR 数从 4 降到 3 |

---

## 1. 现状与硬约束

### 1.1 por 现在是"全量声明"

一条 run 的目录只经过四道加工，没有"按需"这一维：

```
AgentModelClient.toolsFor(capabilities)                        // :183-209
  └─ AgentToolDirectory.build(...)                             // :62-69  唯一构建入口
       └─ AgentToolCatalog.build(... capabilities.project(...)) // :24-76  全量追加
            → AgentToolRequirements.project(tools, rootAvailable)      // 未登记/无 Root 的丢弃
       → disabledTools 逐工具过滤（additionalTools 那一路）
       → policy.filterTools(tools)（AgentToolPolicy 白名单）
       → restrictedToolNames 收窄
  → toolFilter?.invoke(...)（子代理）→ expertFilterTools(...)（专家）
```

`AgentToolRequirements.kt` 登记的工具约 130+（另有 `AgentPhoneToolCatalog.entries`、`PersonalSearchTools.names`、`VivoPersonalContextQuery.tools` 批量登记，实际请求目录 150 上下，精确值以 `ToolCountProbeTest` 的 `COUNT=` 行为准）。这就是每轮请求固定要付的 `tools` 前缀。

### 1.2 约束（每条都有代码依据）

| 约束 | 依据 |
| --- | --- |
| 请求前缀任一字节变化 → 其后内容全部重付 | `docs/TECHNICAL.md:291`；Anthropic 唯一缓存断点在 `agent/model/AnthropicMessagesProvider.kt:174`，而 Anthropic 的缓存前缀顺序是 tools → system → messages，**改 tools 就是重付全前缀** |
| 系统提示只放会话内稳定事实 | `AgentPromptBuilder.kt:52-67` KDoc |
| 工具目录运行内只增不减，能力中途失去靠执行侧复核 | `docs/TECHNICAL.md:294`；`AgentModelClient.kt:230-231` 注释；`:253-259` 单调合并 |
| 目录与系统提示同源同刻重建 | `AgentModelClient.kt:238-252`（root/灵屏可用性变化才整体重建） |
| 每个工具必须登记 Root/域要求 | `AgentToolRequirements.kt:261-262`（缺登记直接抛 `Missing tool requirements`）；`:289-310` 未登记工具被 `project()` 静默跳过 |
| 执行侧必须复核开关 | `AgentLocalTools.kt:285-287`（注释：历史消息、专家白名单、模型凭记忆硬调都能绕过目录） |
| 审批按工具真名出卡 | `AgentLocalTools.kt:296-355`、`ToolReviewMode.kt:68-149` |
| 资源闸门按 `ToolDomain` 分槽 | `AgentResourceGate.kt`（`ticketOf` 用 `AgentToolRequirements.domainOf(name)`，未登记工具退化成 `DEVICE:fallback:<name>` 并打 `perf.gate.unregistered`） |
| 计划模式白名单 | `PlanModePolicy.kt:16-70`，`PlanGate.allowsTool` = 未武装/已提交/只读工具（`PlanGate.kt:38-39`） |
| 工具档只有 FULL/CHAT | `LocalToolProfile.kt:5-8`（CHAT 档目录直接空数组，`AgentToolDirectory.kt:37`） |

---

## 2. 设计目标与非目标

**目标**

1. 首轮请求的 `tools` 体积显著下降（§7 给量化口径与验收阈值）。
2. 一次 run 内**最多一次**因开组导致的整体前缀重写（且默认发生在模型明确需要时）。
3. 权限语义零放宽：任何被关/越权/未登记的工具，经转发也必须被同样拒绝，且错误码可解释。
4. 与现有 `OFF` 档字节级一致：`OFF` 档请求体必须与今天的实现完全相同。
5. 上游可合并：新增文件不与 `Mangi-11/Eta` 冲突，改动集中在 por 自研文件。

**非目标**

- 不做 `tools_search` 关键词点单（D3）。
- 不做"工具级懒加载"（单工具粒度），组粒度足够，且组的边界已经有事实源 `AgentToolRequirements`。
- 不改 MCP 的 `compactMcp` 压缩策略（`AgentToolSchemaCompactor.kt`），两条机制叠加，互不替代。
- 不改 CHAT 档语义（CHAT 档直接返回空目录，按需加载对它无意义）。

---

## 3. 方案

### 3.1 三档

| 档位 | id | 目录内容 | 入口 |
| --- | --- | --- | --- |
| `OFF` | `off` | 与今天完全一致（全量） | 无 |
| `GROUP` | `group`（默认） | 常驻组全量 + 未打开组只留一行 system 索引 | `tools_use_group` / `tool_call` |
| CHAT | （既有） | 空目录 | 无 |

`OFF` 与 CHAT 都不受本方案影响，`OFF` 同时是回滚开关。

### 3.2 分组表

分组的**唯一事实源**是 `AgentToolRequirements` 的登记表（它已经逐条登记了 `ToolDomain` / `rootRequirement`），不新建第二张表，避免"设置页一套、请求侧一套"。

| 组 id | 常驻？ | 内容 | 依据 |
| --- | --- | --- | --- |
| `basic` | 常驻 | `ask_user`、`update_plan`、`get_current_context`、`device_status`、`network_info`、`search_apps`、`launch_app`、`open_uri`、`memory_get`、`memory_write`、`conversation_history`、`skills_list*`、`subagent*`、`tasks_*`、`wait*` | 编排与上下文类工具；`AgentToolPolicy.ALWAYS_ALLOWED`（`:39-52`）已承认"编排类工具被滤掉会出现提示词教模型用、请求里没有"的错位 |
| `screen` | 否 | `observe_screen`、`tap*`、`long_press*`、`swipe`、`scroll*`、`type_text`、`clear_text`、`press_key`、`virtual_screen`、`lingscreen_open_app`、`wait_for_text`、`wait_for_package` | `ToolDomain.SCREEN` 全量 |
| `file` | 否 | `read_file`、`write_file`、`edit_file`、`list_directory`、`stat_file`、`glob_files`、`grep_files`、`read_image` | `FS_WRITE` + 只读文件族 |
| `terminal` | 否 | `terminal`、`terminal_job`、`terminal_daemon`、`get_logcat` | `ToolDomain.SHELL` |
| `device` | 否 | `set_*`、`app_state_control`、`media_control`、`get_device_state`、`get_display_state`、`get_flashlight`、`get_hotspot`、`get_sound_state` | `ToolDomain.DEVICE` |
| `personal` | 否 | `search_*`（短信/通讯录/日历/相册/便签/通话…）、`create_note`、`create_calendar_event*`、`calendar` 读族 | 个人数据面（colorOS 专属，能力投影会再削一刀） |
| `browser` | 否 | `browser_use` | `ToolDomain.BROWSER` |
| `skill` | 否 | `skills_read`、`skills_inspect_github`、`skills_manage`、`skills_install_from_github` | 技能正文读取；`AgentPromptBuilder.kt:265-270` 已经做过"非 FULL 档不注入技能索引"的同源判断 |
| `expert` | 否 | `expert_manage` | 低频 |
| `mcp:<server>` | 否 | 该 server 的全部工具 | `additionalTools` 按 server 前缀切分；`AgentResourceGate` 已有 `MCP:<server>` 域键，天然对齐 |

**常驻组的判定原则**：某个工具被"其它工具的前置条件"需要（`ask_user` 审批、`update_plan` 计划、`skills_list` 发现），或几乎每轮都要（`get_current_context`、`memory_get`），就常驻。其余一律按组。

### 3.3 两个入口

```kotlin
// 1) 打开整组，返回该组的工具清单/用法/可用性
tools_use_group(group: String, reason: String? = null)

// 2) 转发到组内工具（组必须已经打开，或本轮刚打开）
tool_call(tool: String, arguments: String)
```

两者的 `function.parameters` 都是**固定 schema**（`tool_call.arguments` 是字符串，内部再解析）。这一点是硬要求：`tool_call` 的 schema 一旦随"已打开组"变化，就等于把目录又变成动态前缀，方案的全部收益归零。

### 3.4 核心：有效工具名的唯一解析点

这是整个方案里唯一有风险的改动，所以把它收成一个纯函数，放在 `agent/model/` 下，可单测、无 Android 依赖（沿用 `AgentToolAvailability.kt` 的做法：arm64 上 Robolectric 静默跳过，纯 JVM 才可靠）：

```kotlin
// agent/model/AgentToolForwarding.kt（新增）
internal sealed interface EffectiveCall {
    data class Direct(val call: AgentModelClient.ToolCall) : EffectiveCall
    data class Forwarded(
        val outer: AgentModelClient.ToolCall,   // tool_call
        val inner: AgentModelClient.ToolCall,   // 真实工具
        val group: String,
    ) : EffectiveCall
    data class Rejected(val code: String, val message: String) : EffectiveCall
}

internal object AgentToolForwarding {
    const val USE_GROUP = "tools_use_group"
    const val CALL = "tool_call"

    fun resolve(
        call: AgentModelClient.ToolCall,
        openedGroups: Set<String>,
        groupOfTool: (String) -> String?,      // 由 AgentToolGroupCatalog 提供
        isDeferredTool: (String) -> Boolean,
    ): EffectiveCall
}
```

`resolve()` 的判定表：

| 输入 | 结果 |
| --- | --- |
| 名字不是两个入口之一 | `Direct` |
| `tools_use_group` | `Rejected`（它由执行器自己处理，不该进转发链） |
| `tool_call` + `arguments` 坏 JSON / 缺 `tool` | `Rejected("INVALID_TOOL_ARGUMENTS")` |
| `tool_call` 的内层工具不存在 | `Rejected("UNKNOWN_TOOL")` |
| 内层工具不在延迟组（即常驻工具） | `Rejected("TOOL_NOT_DEFERRED")` —— 常驻工具直调即可，转发它只会让审批卡多一层 |
| 内层工具所属组未打开 | `Rejected("TOOL_GROUP_NOT_OPEN", "先 tools_use_group(\"$group\")")` |
| 其余 | `Forwarded(outer, inner.copy(id = outer.id, round = outer.round), group)` |

调用点只有一处 —— `AgentLoop.executeTool`：

```
:477  availableToolNames 判定（用 outer 名字）
:485  roundCall
:488  validator.validate(roundCall)            // 转发名只需通过 tool_call 的固定 schema
NEW   val effective = AgentToolForwarding.resolve(roundCall, openedGroups, ...)
NEW   Rejected -> rejectedToolOutcome(...)      // 直接以错误码回灌，不进闸门
NEW   Forwarded -> 用 effective.inner 继续下面三步：
:498  ToolStarted(name = outer 名, 便于 trace 看清"这是一次转发")
:510  参数解析（inner 的 arguments 再解析一次，失败同样 INVALID_TOOL_ARGUMENTS）
:527  AgentResourceGate.dispatch(toolName = inner 名, args = inner args, ...)
      toolExecutor.execute(inner)              // 五道闸 + 审批 + 执行侧开关复核都在这里
```

**为什么必须在 `executeTool` 而不是在 `AgentLocalTools.execute` 里递归**：

1. 五道闸在 `AgentModelClient` 的 `ToolExecutor` 包装里（`:292-344`），`AgentLocalTools` 在它**里面**。在 `AgentLocalTools` 里展开，闸门看到的仍是 `tool_call`，专家白名单/工具策略/复盘白名单全部按 `tool_call` 判断 —— 等于给所有被禁工具开了一个通用后门。
2. 资源闸门的域键由 `domainOf(name)` 算（`AgentResourceGate.ticketOf`）。用 `tool_call` 去算会落到 `DEVICE:fallback:tool_call`（`:perf.gate.unregistered`），全局串行化所有转发调用 —— 屏幕操作和文件读写会互相排队，直接违反 `ResourceGate` 的分域设计。
3. 审批卡文案用真名才可读（`strictDraft(toolName)` 的标题是"确认执行 $toolName"）。

### 3.5 目录层：hold 而不是删

`AgentToolDirectory.build(...)` 增加两个参数（不改变既有语义，默认值让 `OFF` 档字节不变）：

```kotlin
groupLoadingEnabled: Boolean = false,          // OFF 档 false
openedGroups: Set<String> = emptySet(),        // run 级
deferredSchemas: MutableMap<String, JSONObject>? = null,  // 出参：被 hold 的 schema
```

实现位置：在 `AgentToolCatalog.build(...)` 产出全量、`capabilities.project(...)` **之后**、`disabledTools` 过滤**之前**做按组 hold。顺序理由与 `AgentToolCatalog.kt:67-76` 的注释同源：先投影再过滤，否则"被关掉的工具在不可用统计里继续占位"。

- hold 的 schema 写进 `deferredSchemas`，供 §3.4 的参数校验与 §3.6 的解锁判断使用。
- `basic` 组 + 未分组工具 + 两个入口本身常驻。
- `mcp:<server>`：`additionalTools` 那一路按 server 前缀分组后同样参与 hold；`compactMcp` 仍在最前面压一次（`AgentModelClient.kt:173-181`），顺序不能反。
- **顺带修的真 bug**：`AgentModelClient.kt:183-209` 调 `AgentToolDirectory.build` 时**从未传 `disabledTools`**（`AgentModelClient.kt:32-33` 的 KDoc 里承诺了"调用方按需传 `Prefs.disabledTools()`"，生产路径只有 `ui/screens/prompts/PromptStudioWidgets.kt:155`、`ui/screens/expert/ExpertCenterScreen.kt:587` 两个预览页传了）。结果是用户在工具能力页关掉的工具仍然被声明给模型。本条独立成一个 commit，与按需加载解耦，便于回滚。

### 3.6 状态与恢复

| 状态 | 作用域 | 载体 | 恢复方式 |
| --- | --- | --- | --- |
| `openedGroups` | run（跨轮） | `AgentLocalTools` 内的并发集合（同轮工具调用是并行的，`AgentLoop.executeToolBatch:364-408`） | run 结束即丢 |
| `historyOpenedGroups` | 会话（跨 run） | 历史 `toolCallsJson` 里出现过 `tools_use_group` 的参数 | 每轮 toolsFor 时重算（纯函数，O(历史条数)） |
| 压缩标记 | 会话（跨压缩） | system 消息里的 `[Eta opened tool groups v1] g1,g2` | `AgentContextSession` 压缩边界写入；解析时合并进 `historyOpenedGroups` |
| `nativeGroups` | 会话 | 已"转正"为原生 schema 的组集合 | `Prefs` 按 conversationKey 存；超字符预算即冻结（不扩张） |
| 预开组 | 首轮 | 首条用户文本 + 可选的前缀关键词表 | 只在 run 首轮生效，不落盘 |

**nativeGroups 的必要性**：模型最常见的失败模式是"先 `tools_use_group`、下一轮才敢调"，白付一轮 RTT。解法是：本轮已打开过的组，下一轮直接以原生 schema 出现（不再需要 `tool_call`），且**只增不减**（与 `TECHNICAL.md:294` 的既有不变式一致）。但原生 schema 会永久占住前缀，所以设字符预算：

```
NATIVE_GROUP_GROWTH_CHAR_BUDGET = 32_000   // 约 8k token，且只在会话级增长
```

预算用满即冻结（`frozen` 标记落 Prefs），此后新开的组一律走 `tool_call` 转发。MCP 组**永不进 nativeGroups**：第三方 schema 体积不可控（`compactMcp` 之后仍可到 190KB），且 server 生命周期与会话生命周期不同。

**预开组**：首轮用关键词表把"明显要用屏幕/终端/文件"的会话直接开组，省掉一次重付。只做 3 类强信号（截图/点击/滑动 → `screen`；终端/命令/日志 → `terminal`；读写文件/仓库 → `file`），命中即预开，**不写入 `openedGroups` 的持久语义**（只在 run 内生效）。这是纯猜测逻辑，命中率低也不能有害 —— 最坏情况是多付一组 schema。

### 3.7 系统提示：只加一行索引

`AgentPromptBuilder.buildSystemMessages(...)` 增加一段（仅 `GROUP` 档、且 `!pureChat` 时注入）：

```
延迟加载的工具组（schema 未加载，调用前先打开）：
- screen | 屏幕观察与点击/滑动/输入
- file   | 文件读写与检索
- terminal | 终端与后台任务
...
用 tools_use_group(group, reason) 打开整组；打开后本轮即可用 tool_call(tool, arguments) 直接调用组内工具。
```

一行一组，不含工具清单、不含 schema。**索引内容必须只依赖"可选组集合"，不依赖"已打开组"** —— 否则每开一次组就改一次 system，把 §5.2 的"一次重付"变成"两次"（tools + system 都要重写）。

---

## 4. 权限链完整性（逐闸核对）

转发路径必须让下面每一条都作用在内层真名上：

| 闸 | 位置 | 转发后看到什么 | 结论 |
| --- | --- | --- | --- |
| `availableToolNames` | `AgentLoop.kt:477` | outer（`tool_call` 在目录里） | 保留；内层是否可用由 §3.4 的 `TOOL_GROUP_NOT_OPEN` 判定 |
| JSON Schema 参数校验 | `AgentLoop.kt:488` | outer 过 `tool_call` 固定 schema；inner 过 `deferredSchemas` | 需要新增 `AgentToolCallValidator(tools, extra = deferredSchemas)` |
| 远端跳过策略 | `AgentLoop.kt:486-487` | 用 **inner** 名判断是否在 `remoteToolNames` | 内层是 MCP 工具时同样跳过本地 schema 校验 |
| 资源闸门 | `AgentLoop.kt:527` | inner 名 → 正确 `ToolDomain` | 必须用 inner |
| 计划模式 | `ModelClient:292-302` | inner | `READ_ONLY_TOOLS` 需加 `tools_use_group`（见下） |
| `PlanGate.allowsTool` | `PlanGate.kt:38-39` | inner | 计划未提交时，非只读内层工具同样被拦 |
| 专家白名单 | `ModelClient:303` | inner | `expert_manage` 等组内工具被正确裁剪 |
| 工具策略白名单 | `ModelClient:305` | inner | `ALWAYS_ALLOWED` 里的 `subagent`/`ask_user` 不受影响 |
| 复盘白名单 | `ModelClient:307` | inner | `restrictedToolNames` 精确到真名 |
| 能力码 | `ModelClient:309-315` | inner | `unavailableCode(inner)` |
| 执行侧逐工具开关 | `AgentLocalTools.kt:287` | inner | 转发也绕不过 `Prefs.disabledTools()` |
| 审批 | `AgentLocalTools.kt:296-355` | inner | 严格档出卡标题是"确认执行 tap"，不是"确认执行 tool_call" |
| 主屏闸门 | `AgentLocalTools.kt:371` | inner | `primaryGuardedTools` 照常生效 |

**`tools_use_group` 自身必须进 `PlanModePolicy.READ_ONLY_TOOLS`**：它只改 run 内状态、不碰设备，若被计划模式拦下，计划阶段的"只读探索"会因为工具没打开而失败。`tool_call` **不加** —— 它的只读性取决于内层工具，交给解包后的真名去判。

**`AgentToolRequirements` 必须登记两个入口**：

```kotlin
register(RootRequirement.NONE, ToolDomain.NONE, "tools_use_group", "tool_call")
```

不登记的后果是双重的：`project(tools, rootAvailable)` 会把它们**静默丢掉**（`AgentToolRequirements.kt:289-310` 的 `skipped += name`），而 `rootRequirement()` 又会在别处抛 `Missing tool requirements`。登记后它们自然获得 `ToolDomain.NONE`（闸门放行、不参与串行），与"不改设备状态"的语义一致。

---

## 5. 多维度论证

### 5.1 体积收益（口算，待实测校准）

以 150 个工具、平均 schema 约 500–700 字符计，`tools` 前缀约 75–105 KB。`AgentHistoryTrimmer.estimateTokens()` 对中文按字、对 JSON 大致按 3.5–4 字符/token，即 **20–30k token** 量级（MCP 另计，`compactMcp` 之后仍可达 190KB）。

`GROUP` 档首轮只留 `basic`（约 25 个工具）+ 两个入口 + 一行索引，估 **15–20%** 原体积，即降幅 80% 附近。保守取验收阈值 **≥ 50%**（§7），实测不足则说明分组切分过细（`basic` 塞太多）。

**必须承认的边界**：如果一次 run 把所有组都开了，`GROUP` 档反而多付（多了索引 + 一次重写）。所以 D2 的 3 次上限和 `basic` 的克制是方案成立的前提，不是可选项。

### 5.2 缓存重付模型（对首版论证的修正）

**关键结论**：`toolsForRound()` 每轮只调一次（`AgentLoop.kt:146-152`），而 `openedGroups` 的变更在**下一轮**才反映到目录。因此：

> 同一轮里模型连续开 3 个组，只产生 **1 次**前缀重写，不是 3 次。

这直接支撑了 D2 取 3 而不是 1：3 次上限保护的是"长期累积"（一次 run 最多 3 次重写 = 最多 3 次全前缀重付），而不是单轮成本。首版论证按"每次开组各付一次"估算，偏保守，此处修正。

配合 `nativeGroups`：已打开的组下一轮直接以原生 schema 出现，模型不必再走 `tool_call`，也不需要额外的"再确认"轮次。

### 5.3 前缀字节稳定性

三个"每轮都会算"的量必须保证"没有真变化就不改字节"：

1. 索引段只依赖可选组集合（§3.7）。
2. `tools` 数组顺序固定：常驻组按登记顺序，`tools_use_group`/`tool_call` 追加在末尾；打开的组按打开顺序追加（与 `:253-259` 的"只增不减"合并策略天然兼容 —— 实际上开组后应当直接整体换而不是走单调合并，见 §6 PR2 的说明）。
3. `nativeGroups` 的行序按组 id 字典序，不按打开时间。

### 5.4 并发

同轮工具调用是并行的（`executeToolBatch` 合并 subagent 调用，`:364-408`）。`openedGroups` 会被多个执行线程同时读、被 `tools_use_group` 写，必须是并发集合（`ConcurrentHashMap.newKeySet()`），且"打开成功/超上限"的判定必须原子（`size` 检查 + 插入合成一个 `synchronized` 或 CAS 循环）。

`OFF` 档这条路径完全不走，零风险。

### 5.5 模型行为

| 风险 | 表现 | 缓解 |
| --- | --- | --- |
| 不知道有什么工具 | 拒答"我没有该能力" | 索引行明确列出组 id + 一句用途；`use_group` 回执带 `tools` 清单 |
| 每轮都开组 | 反复重付 | `nativeGroups` 转正 + run 内已开组直接命中 |
| 用 `tool_call` 调常驻工具 | 多一层、审批卡难读 | `TOOL_NOT_DEFERRED` 明确拒绝 |
| 弱模型不会写 `arguments` 字符串 | 转发失败 | `tool_call.arguments` 在 schema 描述里给完整示例；解析失败返回 `INVALID_TOOL_ARGUMENTS` 并回灌"示例" |
| 一次全开 | 退化成 OFF | D2 上限 3 + 超限错误码 `TOOL_GROUP_OPEN_LIMIT` |

弱模型这条是真实风险（上游 issue #323 已经记录了"弱模型照抄占位符导致带参工具持续失败"）：`tool_call` 是双重 JSON 编码，比原生调用难写。所以 `nativeGroups` 不是优化项而是**必要补偿**，且白名单优先覆盖强模型/强 provider。

### 5.6 兼容性

| 面 | 影响 | 处理 |
| --- | --- | --- |
| 历史回放 | 旧历史里没有 `tool_call`，反推逻辑天然兼容 | `historyOpenedGroups` 只读不写历史 |
| 子代理 | `SubagentExecutor` 传 `toolFilter`/`additionalTools`（`:433`、`:441`） | 子代理目录由 `profile.filterTools` 裁剪，本方案在 `profile` 之后生效；子代理不启用按需加载（工具少，收益低于学习成本） |
| 体验复盘 | `AgentExperienceReview` 用 `restrictedToolNames`（`:152`） | 白名单按真名收窄，`OFF` 语义不变 |
| 工具档 | CHAT 档空目录 | `AgentToolDirectory.build` 在 CHAT 分支之前就返回空，本方案不介入 |
| MCP | `compactMcp` 先压、再按 server 分组 | 顺序不能反 |
| 计划模式 | `update_plan` 在 `basic` 组常驻 | 计划流程不受影响 |
| 能力投影 | `project()` 在 hold 之前 | 先投影再 hold，避免"不可用工具占据组容量" |

### 5.7 维护性与上游

- 新增 3 个文件：`AgentToolGroups.kt`（分组表 + 索引行）、`AgentToolGroupCatalog.kt`（两个入口的 schema 与回执）、`AgentToolForwarding.kt`（纯函数解包）。全是 por 自研，上游没有同名文件，`source/main` 合并不会冲突。
- 改动 6 个文件：`AgentToolDirectory.kt`、`AgentToolCatalog.kt`（hold 与出参）、`AgentModelClient.kt`（三档装配 + 传 deferredSchemas）、`AgentLoop.kt`（解包点）、`AgentToolCallValidator.kt`（extra schemas）、`PlanModePolicy.kt`（一行）、`AgentToolRequirements.kt`（一行登记）、`AgentPromptBuilder.kt`（索引段）。都是 por 侧已经被改过的文件（`docs/FORK_STATE.md` 已登记为本地自研），上游冲突面可控。
- 与 Expressive 的关系：只借"组"这个抽象，不借 `AgentLocalTools` 内递归、不借 `tools_search`（D3）、不借 suffix 模式命名（por 用 `GROUP`/`OFF`，避免与已退役的 `suffix` 混淆）。

### 5.8 灰度与回滚

- 灰度维度：`providerType`（`anthropic` / `openai_compatible` / `gemini`），`custom` 默认 `OFF`。`custom` 覆盖各类自建中转，行为不可预期，先观察。
- 灰度实现：`AgentToolGroupLoadingPolicy.modeFor(providerType, providerId)`，先查用户显式设置（`Prefs`），再查白名单常量，默认 `GROUP`。
- 回滚：`Prefs` 一键 `OFF` → 目录回到全量；`OFF` 档在代码里走的是"参数全默认值"分支，PR1 的验收项就是"`OFF` 档请求体与改动前逐字节一致"。

### 5.9 反对意见与反证

| 反对 | 反证 |
| --- | --- |
| "`compactMcp` 已经省 token 了，不需要按需加载" | 压缩只改描述文本，不动工具**数量**；150 个工具的结构骨架（`parameters` 的 properties/required/enum）无法压缩，且压缩有下限（描述压到 90 字就不能再压）。两者是乘性关系：先按需砍数量，再压缩剩下的。 |
| "多一层转发会拖慢响应" | 转发不增加 HTTP 轮次：`tool_call` 在本轮内执行完，结果直接回灌（`available=now`）。真正的额外成本是"模型可能先开组再调用"的两步式；`nativeGroups` + 预开 + 允许同轮转发把这一步压到最少。 |
| "`tool_call` 让审批卡难懂" | 恰恰相反：解包后审批卡显示真实工具名与真实参数（§4 表末三行）。若不方案化，模型在 `OFF` 档下本来就会直调真名，卡片内容反而一致。 |
| "组切分靠猜，以后新增工具会漏" | 分组表从 `AgentToolRequirements` 推导，新工具**必须**登记（否则 `rootRequirement` 抛错），登记时强制选域 → 自动归组。默认落到 `basic`（宁可多付也不漏能力），并在测试里断言"每个登记工具恰好属于一组"。 |

---

## 6. 实施计划

### PR1 — 基础设施 + `OFF` 档零变化（不出效果，只铺路）

| 文件 | 改动 | 估行 |
| --- | --- | --- |
| `agent/model/AgentToolGroups.kt` | 新增：组定义、`groupOfTool`、`withheldTools(openedGroups, …)`、`indexMessage(...)`、`nativeGroups` 预算常量 | ~180 |
| `agent/model/AgentToolForwarding.kt` | 新增：`EffectiveCall` + `resolve()` 纯函数 | ~90 |
| `agent/tool/AgentToolRequirements.kt` | 一行 `register(NONE, ToolDomain.NONE, "tools_use_group", "tool_call")` | +1 |
| `agent/model/AgentToolDirectory.kt` | 新增 `groupLoadingEnabled` / `openedGroups` / `deferredSchemas` 三参数（默认值保持全量），hold 逻辑 | ~40 |
| `agent/model/AgentToolCatalog.kt` | 同上（hold 发生在 `project()` 之后、`disabledTools` 过滤之前） | ~15 |
| `config/Prefs.kt` | `AGENT_TOOL_GROUP_LOADING`（off/group）+ 会话级 `nativeGroups` 键 | ~40 |
| `app/src/test/.../AgentToolGroupsTest.kt` | 新增：每个登记工具恰属一组；`basic` 覆盖编排类；`withheldTools` 不误伤常驻 | ~120 |

验收：`OFF` 档（默认参数）下 `AgentToolDirectory.build` 输出与改动前逐字节相同（用 `ToolCountProbeTest` 的 `TOOL=` 行做快照）；全量单测绿。

### PR2 — `GROUP` 档生效 + 解包

| 文件 | 改动 | 估行 |
| --- | --- | --- |
| `agent/model/AgentToolGroupCatalog.kt` | 新增：`tools_use_group` / `tool_call` 的固定 schema、回执结构（`tools`/`usage`/`available`/`call_via`）、错误码 | ~200 |
| `agent/model/AgentModelClient.kt` | 三档装配；`deferredSchemas` 收集并传给 `AgentLoop`；`toolsForRound` 在"开组导致的目录变化"时**整体替换**而非单调合并（开组是显式动作，重付一次是预算内的） | ~60 |
| `agent/model/AgentLoop.kt` | `executeTool` 插入 `resolve()`；内层参数二次解析；`dispatch`/`toolExecutor` 用内层 | ~70 |
| `agent/model/AgentToolCallValidator.kt` | 增加 `extraSchemas: Map<String, JSONObject>`（内层参数校验） | ~25 |
| `agent/tool/AgentLocalTools.kt` | `tools_use_group` 分支：校验→上限→`openedGroups += group`→回执；`tool_call` 不单独分支（由解包兜住，落到内层后走原 `when`） | ~80 |
| `agent/tool/PlanModePolicy.kt` | `READ_ONLY_TOOLS += "tools_use_group"` | +1 |
| `agent/model/AgentPromptBuilder.kt` | 索引段（仅 GROUP 档、非 CHAT） | ~30 |
| 测试 | `AgentToolForwardingTest`、`AgentToolGroupCatalogTest`、`AgentLocalToolsGroupOpenTest`、`AgentLoopForwardingTest`（含：严格档出卡真名、只读档拒真名、计划模式拦真名、资源闸门域键=内层域） | ~350 |

验收：见 §7 第 2–6 项。

### PR3 — 状态持久与恢复 + 打点

| 内容 | 说明 |
| --- | --- |
| `historyOpenedGroups` | 从历史 `toolCallsJson` 反推（纯函数 + 单测） |
| 压缩标记 `[Eta opened tool groups v1]` | `AgentContextSession` 压缩边界写入；解析合并；不参与"尾部逐字节不变"判定 |
| `nativeGroups` 转正与冻结 | 字符预算 `32_000`，冻结后不再扩张；MCP 组永不转正 |
| 首轮预开 | 三类强信号关键词，最多 3 组 |
| 打点 | `perf.tools.catalog_chars`、`perf.tools.group_open`（name/ms/limit）、`perf.tools.forward`（outer→inner） |

### PR4（可选）— 设置页开关

`ui/screens/tools/AgentToolsScreen.kt` 加"按需加载"开关（`本地/按组/关闭`）。**注意**：por 的 `app/src/main/res` 目前只有 `values/`（无 `values-zh`、`values-zh-rTW`），与 `AGENTS.md` 声明的"en/zh/zh-TW 三份同步"不一致，落地前需先确认文案资源实际存放位置，否则会出现"加了 `values-zh` 但三语不同步"的返工。

---

## 7. 验收标准

| # | 项 | 判据 |
| --- | --- | --- |
| 1 | `OFF` 档零变化 | `ToolCountProbeTest` 的 `COUNT=` 与 `TOOL=` 行与改动前逐行一致；`AgentToolDirectory.build` 输出字节相同 |
| 2 | 首轮降幅 | 无 MCP 目录下，`estimateTokens(tools.toString())` 首轮相对 `OFF` 档下降 **≥ 50%**（打点实测，非估算） |
| 3 | 只重付一次 | 同一轮内开 3 组只触发 1 次 `toolsFor` 整体替换（`perf.tools.catalog_chars` 计数为 1） |
| 4 | 严格审批档 | 经 `tool_call` 转发 `tap`，审批卡标题/目标必须是 `tap`，不能是 `tool_call` |
| 5 | 只读档 | 只读审查档下转发 `write_file` 必须 `TOOL_REVIEW_READ_ONLY` |
| 6 | 计划模式 | 计划未提交时转发非只读工具必须 `PLAN_REQUIRED_FIRST`；`tools_use_group` 必须放行 |
| 7 | 资源闸门 | 转发 `terminal` 的等待键必须是 `SHELL:<conversationKey>/shell`，不能是 `DEVICE:fallback:tool_call` |
| 8 | 权限不放宽 | 能力页被关的工具：`OFF` 档拒绝、`GROUP` 档经转发同样拒绝（同一个错误码） |
| 9 | 并发安全 | 同轮并行 5 个 `tools_use_group` 不超上限、不丢组、无 `ConcurrentModificationException` |
| 10 | 恢复 | 历史里有 `tools_use_group(screen)` 的会话，新 run 首轮 `screen` 即为原生 schema（未超预算时） |
| 11 | 测试面 | 新增用例全绿；`1777 tests / 44 failed / 91 skipped` 基线不恶化（失败全为 arm64 Robolectric） |

---

## 8. 风险登记

| 风险 | 概率 | 影响 | 处置 |
| --- | --- | --- | --- |
| 弱模型写不对 `tool_call.arguments` | 中 | 中 | schema 描述带完整示例；错误码回灌示例；白名单优先强 provider |
| 分组切分不准（高频工具落进非 `basic` 组） | 中 | 中 | PR3 的 `perf.tools.group_open` 统计真实开组分布，据此调整分包 |
| 开组后目录整体替换破坏前缀缓存 | 高（必然） | 中 | 预算内最多 3 次；`nativeGroups` 让后续轮次稳定 |
| 解包点遗漏（某处仍在用 outer 名） | 中 | 高 | `EffectiveCall` 收敛为纯函数 + 单测；§4 逐闸核对表作为 review checklist |
| 会话级 `nativeGroups` 无界增长 | 低 | 中 | 32k 字符预算 + 冻结标记 |
| 与上游 `AgentToolCatalog` 重构冲突 | 中 | 低 | 上游已把 catalog 拆成 `catalog/` 分包，por 保持扁平；改动集中在 por 自研文件，合并时按文件取用 |

---

## 附录 A：错误码

| 码 | 触发 | 提示语要点 |
| --- | --- | --- |
| `UNKNOWN_TOOL_GROUP` | 组 id 不存在 | 列出可选组 |
| `TOOL_GROUP_OPEN_LIMIT` | 本轮开组数超 3 | 告知上限与已开组 |
| `TOOL_GROUP_NOT_OPEN` | 转发未打开组的工具 | "先 `tools_use_group("<group>")`" |
| `TOOL_NOT_DEFERRED` | 转发常驻工具 | "该工具可直接调用" |
| `TOOL_DISABLED_BY_USER` | 能力页关闭 | 沿用现有错误码，不新增 |
| `INVALID_TOOL_ARGUMENTS` | 二次解析失败 | 附示例 |
| `TOOL_UNAVAILABLE` | 内层工具能力不可用 | 沿用 `capabilities.unavailableCode` |

## 附录 B：`tools_use_group` 回执形状

```json
{
  "ok": true,
  "group": "screen",
  "tools": ["observe_screen", "tap", "swipe", "..."],
  "usage": "先 observe_screen 拿到控件再 tap；主屏操作会触发确认卡",
  "available": "now",
  "call_via": "tool_call"
}
```

`available` 取 `now`（本轮即可转发）或 `next_turn`（需等目录刷新，仅在 `OFF`→`GROUP` 切换瞬间出现）。

## 附录 C：`resolve()` 骨架

```kotlin
fun resolve(call, openedGroups, groupOfTool, isDeferredTool): EffectiveCall {
    if (call.name != CALL) return EffectiveCall.Direct(call)
    val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
        ?: return EffectiveCall.Rejected("INVALID_TOOL_ARGUMENTS", "arguments 不是合法 JSON 对象")
    val tool = args.optString("tool").takeIf { it.isNotBlank() }
        ?: return EffectiveCall.Rejected("INVALID_TOOL_ARGUMENTS", "缺少 tool 字段")
    val group = groupOfTool(tool)
    return when {
        group == null -> EffectiveCall.Rejected("UNKNOWN_TOOL", "未知工具：$tool")
        !isDeferredTool(tool) -> EffectiveCall.Rejected("TOOL_NOT_DEFERRED", "$tool 可直接调用")
        group !in openedGroups -> EffectiveCall.Rejected(
            "TOOL_GROUP_NOT_OPEN",
            "组 $group 未打开：先调用 tools_use_group(\"$group\")",
        )
        else -> EffectiveCall.Forwarded(
            outer = call,
            inner = call.copy(
                name = tool,
                argumentsJson = args.optString("arguments").ifBlank { "{}" },
            ),
            group = group,
        )
    }
}
```
