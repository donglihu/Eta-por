# 工具组加载：P0-1 / P0-2 落地状态

本文记录「MCP 工具按需投影」与「技能索引判据」两批改动的**实际落地形态**、验证证据与已知遗留。方案依据见同目录 `TOOL_GROUP_LOADING.md`、`LAZY_TOOL_LOADING_IMPL.md`；实现基线是分支 `branch` 的 `87ef97b`（本地工作树）。

## 0. 一句话结论

工具组加载落地后，`GROUP` 档下 **MCP 服务器的工具 schema 整体不进请求目录**，模型只能看到组索引里的一行「MCP 服务器动态组」，开组 `mcp:<serverPart8>` 只解锁 `tool_call(tool, arguments)` 转发；OFF 档保持全量、请求体逐字节不变。技能索引的注入判据从「工具档 == FULL」改为「目录里真有 `skills_read`」。

## 1. P0-1：MCP 按需投影

### 1.1 为什么不是「投影 schema，保留工具」

Expressive 的做法是延迟组内的工具整组 withheld、开组后追加真实 schema。搬到 MCP 上不成立，原因有三：

1. `tool_call` 的 `arguments` 在 Expressive 是**字符串**，需要二次 JSON 解析；por 的 `ToolCall.argumentsJson` 到转发层还是 `JSONObject`，一旦把内层参数序列化成字符串再解析，弱模型容易把转义写坏。
2. por 的 `AgentModelClient.toolsForRound()` 是**单调增补**：一旦把新 schema 追加进 `publishedTools`，请求前缀就变了，整段 prompt 缓存作废（Anthropic 的缓存断点在 tools → system → messages）。
3. MCP 工具是**动态**的（server 连接状态随时变），把动态集合塞进持久化的「已打开组」和冻结位，维护成本高于收益。

因此取「**源头裁剪 + 只解锁转发**」：schema 不进目录 ⇒ tools 字节恒定 ⇒ 六道闸、审批、资源闸门全部零改动。

### 1.2 改动清单

| 文件 | 作用 |
| --- | --- |
| `agent/mcp/McpRunContext.kt` | `McpRunSnapshot` 增加 `omittedToolsByServer`、`appendModelTools(destination, openedGroupIds)`、`serverGroups()`、`serverGroupSummaries()`、`truncationNotice()`；新增 `serverGroupPart()` 作为**组 id 与 `modelToolName` 的同一事实源** |
| `agent/model/AgentToolGroups.kt` | 静态分组表（basic 常驻 + 9 个延迟组）；`AgentToolGroupView` 承载**run 级** MCP 动态组，禁止全局可变状态 |
| `agent/model/AgentToolGroupCatalog.kt` | `tools_use_group` / `tool_call` 两个固定 schema；`resolve()` 返回 `Direct` / `Forwarded(outer, inner, group)` / `Rejected`；`AgentToolGroupSession` 管配额（每 run 3 次） |
| `agent/model/AgentToolDirectory.kt` | `holdDeferredGroups()`：延迟组成员换成 `deferredSchemas`（真名 → parameters），目录里只留两入口 |
| `agent/model/AgentModelClient.kt` | 组模式下剔除 `mcp_` 前缀工具后再 `compactMcp`；构造 `AgentToolGroupView`、`AgentToolGroupSession`、`groupIndex`；`toolsFor()` 末尾执行 hold |
| `agent/model/AgentLoop.kt` | **唯一解包点**：`executeTool` 在过闸之前把 `Forwarded` 换成内层真名，六道闸、审批、`AgentResourceGate` 全部看到真名 |
| `agent/model/AgentPromptBuilder.kt` | 组索引只加一行，且只依赖「可选组集合」，不依赖「已开组」 |
| `agent/runtime/AgentRuntimeRunExecutor.kt` | 把 `mcpSnapshot.truncationNotice()` 传进 `complete()` |

### 1.3 三个必须理解的设计点

**解包点只能有一处，且在 `AgentLoop.executeTool`。**
`AgentModelClient` 的六道闸（计划模式、专家白名单、`AgentToolPolicy`、`restrictedToolNames`、`capabilities.unavailableCode`）只读 `call.name`；`RoutingToolExecutor` 在 `AgentLocalTools` 之下靠 `mcp.contains(name)` 分流；`AgentResourceGate.ticketOf` 对未登记工具回退 `DEVICE:fallback:<name>`。若把解包放在 `AgentLocalTools` 内部，`mcp_` 真名永远到不了 MCP 执行器，屏幕/文件/MCP 还会被串成一条队列。

**开组是知识变更，不是能力变更。**
`tools_use_group` 与 `tool_call` 的 schema 恒定常驻；开组只改 system 里那一行索引，`tools` 一个字节不动。因此配额、幂等、超限回滚都在会话对象里做，不触碰请求前缀。

**组 id 必须单一事实源。**
`modelToolName` 形如 `mcp_<serverPart8>_<tool>_<hash8>`，组 id 是 `mcp:<serverPart8>`。两侧各自推导一次就会漂移——漂移的表现是「组打开成功、工具永远调不到」。现在两边都用 `McpRunSnapshot.serverGroupPart()`。

### 1.4 截断不再静默

旧实现超过 `MAX_RUN_TOOLS = 64` 时 `return@forEach` 静默丢弃，模型会以为该 server 只有前面那些工具。现在：

- `load()` 计数进 `omittedToolsByServer`；
- `serverGroupSummaries()` 在组索引里写明「等 N 个工具」；
- `truncationNotice()` 明确「服务器 X 有 N 个工具超出单 run 上限未加载」，经 `AgentRuntimeRunExecutor` 传进 prompt。

## 2. P0-2：技能索引判据

### 2.1 问题

技能索引（`buildSkillSystemMessage`）原来只在 `LocalToolProfile.FULL` 时注入。组加载把 `skills_manage` / `skills_inspect_github` / `skills_install_from_github` 放进了延迟组，只有 `skills_list` / `skills_read` 常驻——判据与目录开始脱钩：一旦目录里没有 `skills_read`（工具档、专家白名单、能力投影、逐工具开关任一环节裁掉），索引仍在，模型会照着索引去调一个不存在的工具，或干脆编造技能正文。

### 2.2 改法

`AgentPromptBuilder.buildSystemMessages` 新增 `availableToolNames: Set<String>? = null`：

```kotlin
val skillToolsAvailable = availableToolNames?.contains(SKILLS_READ_TOOL)
    ?: (activeProfile == LocalToolProfile.FULL)
```

- 传了目录名 → 只按目录判（目录里没有 `skills_read` 就不注入）；
- `null` → 退回旧判据，既有调用方与 17 个测试行为不变。

`AgentModelClient` 相应把「先算目录、再建 messages」的顺序调正：`compactedAdditionalTools` 与局部 `toolsFor()` 前移到 `buildInitialMessages` 之前，得到 `initialTools` / `initialToolNames`；`toolsForRound()` 的重建分支用**当轮** `toolNames(nextTools)` 重算，避免 Root 授权变化时索引与目录重新拆开。

## 3. 验证证据

环境：Windows + JDK 25 + SDK `platforms;android-37.0`（稳定渠道无 `android-37`，用 canary 装 37.0 后 junction 成 `android-37`）。

| 项 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| 定向用例：`--tests *AgentToolGroups* *AgentToolDirectory* *AgentToolCatalog* *AgentToolPolicy* *AgentToolRequirements* *ToolCountProbe* *ToolCapabilityProjection* *AgentToolGuard* *AgentToolReviewPolicy* *AgentToolCallValidator* *Mcp* *AgentPromptBuilderTest*` | 114 tests，112 通过，2 失败（见 §4） |
| 新增 `McpRunSnapshotProjectionTest`（7 例） | 全通过：组 id 与 `modelToolName` 同源、无参=全量、按已开组过滤、投影不影响 `resolve()`、摘要带个数、截断按 server 上报、空快照不发任何工具 |
| 全量 `:app:testDebugUnitTest` | 2448 tests，182 失败，全部为环境类（`SystemClock.elapsedRealtime not mocked`、`sh: CreateProcess error=2`、Windows 符号链接权限、Room 迁移用例在 Windows 上的路径差异、Robolectric nativeruntime、堆内存 Critical） |

## 4. 已知遗留（**非本批改动引入**）

`AgentPromptBuilderTest` 有两例失败：`messagesKeepSystemHistoryAndCurrentImageInputInStableOrder`、`browserAndSkillMessagesAreConditionalAndStructurallyComplete`，断言 `expected:<[system ×4, user]> but was:<[system ×5, user]>`。

根因是 `526c7e4`（合入专家中心）在系统提示里新增了一段「用户可以让你直接创建或修改可召唤的专家」，而这两个用例的期望条数没跟着更新。诊断输出（临时用例 dump）显示 5 条 system 依次是：主系统段、网页段、**专家段**、subagent 段、技能索引段。与本批改动无关：两例在本批改动之前就同样失败，且都不传 `availableToolNames`（走 `null` 回退，行为与改动前逐字节一致）。

未改这两个用例的原因：修它需要同步调整 `getJSONObject(2..6)` 的下标，属于专家中心那条线的测试对齐，混进本 PR 会让责任边界变糊。

## 5. 回滚

- 业务回滚：`Prefs.Keys.TOOL_GROUP_LOADING_OFF` 一档关掉组加载，目录回到全量、MCP 回到全量、`tool_call`/`tools_use_group` 不下发。
- 代码回滚：只需回退 `AgentToolDirectory.holdDeferredGroups` 的调用点与 `AgentModelClient.compactedAdditionalTools` 的剔除分支，其余（`McpRunSnapshot` 的投影签名）在 OFF 档是空操作。