# Eta 缺陷审计报告（branch @ 6c3083b）

- 审计对象：`donglihu/Eta-por`，分支 `branch`，提交 `6c3083b`（test(alarm): 补纯 JVM 调度用例，FakeProvider 复刻真机 selection 陷阱）
- 审计方式：纯静态阅读（约 12.1 万行 Kotlin / 1225 个 `.kt` 文件），聚焦组件暴露面、Agent 工具层权限边界、审批闭环、数据持久化、并发与生命周期。
- 构建限制：审计机为 JDK 21 / Android SDK 36（缺 `platforms;android-37.0`），无法执行 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`，**未做动态验证**；所有结论均给出 `文件:行` 供复核。
- 结论分布：**Critical 2 / High 15 / Medium 9 / Low 9**。

> 阅读建议：先看 C1、C2 与 H1～H6（审批与 IPC 两条主链），其余按需。

---

## Critical

### C1 用户点「跳过」被当成「批准」，危险工具照常执行

- 证据：`app/src/main/kotlin/io/github/mangi/eta/agent/display/dashboard/AgentConfirmGuard.kt`
  - `:662-671` `skip()` 组装「用户跳过了…按你认为最合理的方式继续」后调用 `completeVerdict(token, reply)`，**未传 `rejected`**
  - `:494` `completeVerdict(token, reply, rejected: Boolean = false)`
  - `:629-654` `chooseFreeform()`（用户手写答案，例如「不要执行」）同样不带 `rejected`
  - `:517-520` `verdictResult` 以 `put("ok", !rejected)` / `put("code", if (rejected) "USER_REJECTED" else "USER_VERDICT")` 为唯一判据
- 消费侧：`app/src/main/kotlin/io/github/mangi/eta/agent/tool/AgentLocalTools.kt:334-361` 只判断 `outcome.rejected`，否则 `:350-355` 置 `approvalGranted = true` 并继续执行原工具；`:371-399` 主屏 GUI 兜底闸门同构。
- UI 入口：`app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatBody.kt:1146-1147` → `AgentConfirmGuard.skip(...)`；`app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentApprovalCard.kt:84`、`:379` `onSkip`。
- 影响：`shell rm -rf ...`、`write_file /system/...`、`tap_element("确认支付")` 等一次性不可逆操作，在用户点「跳过」或写下否定意见后**仍然执行**。这条一旦成立，下游所有「危险命令白名单」「免批窗口」都失去意义。
- 修复：`skip()`/`chooseFreeform()` 必须 `rejected = true`；或在 `AgentLocalTools` 改为「只认显式 APPROVE/REMEMBER，未知 verdict 一律拒绝」，把默认值从 fail-open 改为 fail-closed。

### C2 Room 缺迁移路径时静默销毁全部业务数据

- 证据：`app/src/main/kotlin/io/github/mangi/eta/data/db/EtaDatabase.kt:60-91` 注册 `MIGRATION_6_7 … MIGRATION_35_36`（30 个，**最低只到 6→7**）；`:96` `.fallbackToDestructiveMigration(dropAllTables = true)`。
- 触发路径：
  1. 从 v1～v5 的老库升级（无 1→6 迁移，直接命中 destructive）；
  2. 回退安装低版本 APK（schema 降级）；
  3. `app/src/main/kotlin/io/github/mangi/eta/EtaApp.kt:57` 在 Room 打开**之前**就用全量备份覆盖 install 目录，从而恢复更高版本的数据。
- 影响：会话、消息、checkpoint、`model_providers`（含明文 `api_key`，见 `data/db/ProviderEntities.kt:37`）、MCP 配置、token 统计全部 DROP，release 包不抛异常、UI 无任何提示。
- 修复：移除 `fallbackToDestructiveMigration`；捕获 `IllegalStateException` 后把库改名为 `eta.db.broken-<ts>` 并提示导出，再建新库；补齐 1→6 迁移，或在 `version < 7` 时禁止自动打开并引导用户导出/重装。

---

## High

### H1 `AgentRuntimeService` 导出且无权限，`onStartCommand` 不校验调用方

- 证据：`app/src/main/AndroidManifest.xml:186-189` `exported="true"`、无 `android:permission`、仅 `tools:ignore="ExportedService"`；`app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt:202-227` 在 `consentAllowsRuntime()`（`:188-190`，正式包恒 `true`）之后直接分派：
  - `:211` `ACTION_STOP_RUNS` → `sessions.keys.toList().forEach { cancelRun(it) }`
  - `:216-219` `ACTION_SUPPLEMENT_RUN` → `requestSupplement(getStringExtra(EXTRA_SUPPLEMENT_TEXT).orEmpty(), ...)`
  - 动作常量 `:1457-1479` 均为 `const`，任意本机应用可字面量构造。
- 影响：
  1. 任意应用可静默终止用户正在跑的会话（在途工具调用与产出丢失）；
  2. 可把任意文本塞进运行中会话（`requestSupplement` → `session.steer`），对具备 root / 无障碍能力的 Agent 构成干净的提示注入入口，**无需诱导用户点击**；
  3. 无活跃会话但有 `lastCompletedRunContext` 时，还会 `startRun(...)`（`:1359-1369`）起一个新 run。
- 修复：service 改 `exported="false"`，或加 signature 级自定义权限；`onStartCommand`/`onBind` 校验 `Binder.getCallingUid()`；把「停止/插话」限制为同进程或已显式授权的入口包。

### H2 Messenger 通道只按包名字符串信任调用方

- 证据：`AgentRuntimeService.kt:1405-1412` `isMessageSenderAllowed(msg)`：`uid == Process.myUid()` 放行，否则 `packageManager.getPackagesForUid(uid)` 与 `ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES` 取交集；后者定义在 `app/src/main/kotlin/io/github/mangi/eta/core/ModuleConfig.kt:17` = `com.heytap.speechassist` / `com.miui.voiceassist` / `com.vivo.ai.copilot`。
- 影响：只比较包名、不校验签名。在非 OPPO/小米/vivo 设备上这三个包通常不存在，任意应用改名即可占据其中一个包名，从而获得完整 Agent 能力（`MSG_START_RUN` 等）。该 fork 在 `ModuleConfig.kt:54-58` 还把机型伪装成三星 SM-S928B，说明它并非只跑在原生三厂商环境。
- 修复：用 `checkSignatures(myUid, callingUid) == SIGNATURE_MATCH` 或内置白名单签名摘要（SHA-256）判定；包名只作为第一层过滤。

### H3 `bash -lc` 之类组合短选项可绕过危险命令判定

- 证据：`app/src/main/kotlin/io/github/mangi/eta/agent/display/dashboard/ConfirmRiskDetector.kt:62` `shellWrappers`、`:122` `val inner = rest.indexOfFirst { it == "-c" || it == "--command" }`、`:123-126` 命中才递归。
- 影响：`bash -lc '<危险命令>'`、`sh -ec '<危险命令>'` 不匹配字面 `-c` → 不递归 → `inspectTool` 返回 `null` → 标准档不出审批卡，命令直接执行。
- 修复：把任意以 `-` 开头且包含 `c` 的组合短选项（`-lc`、`-ec`、`-xc`…）都当作 `-c`；更稳的做法是识别 wrapper 后交给 shell 词法解析再判定。

### H4 危险写动词只覆盖十余条命令

- 证据：`ConfirmRiskDetector.kt:113-154` 仅拦：`rm`（需 recursive/force 且目标命中 `isProtectedPath`）、`mkfs*`、`dd of=/dev/*`、`pm clear`、`cmd package clear`、`fastboot flash/erase/format/oem/update`、`wipe data|userdata`、`chmod -R /`、`base64 -d`、`> /dev/block`。受保护前缀见 `:64-68`（`/system`、`/vendor`、`/product`、`/boot`、`/data/adb`、`/init.rc`、`/default.prop`、`/metadata`、`/sdcard`、`/storage`）。
- 影响：`cp`/`mv`/`tee`/`ln -s`/`sed -i`/`echo >` 改写系统分区，或写入 `/data/adb/modules`、`/data/adb/service.d`（模块开机自启）**全部放行**，而这批命令本身足以完成持久化。
- 修复：改为「写动词 + 目标路径落在受保护前缀」的通用判定（含重定向解析），不再枚举命令；对 `/data/adb` 下任何写入强确认。

### H5 读类文件工具永不过审批，且后端不限制 workspace

- 证据：`ConfirmRiskDetector.kt:77-97` `inspectTool` 只处理 `terminalTools` 与 `write_file/edit_file`，其余一律 `return null` → `read_file`/`stat_file`/`list_directory`/`glob_files`/`grep_files` 零审批；`app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LocalFileToolBackend.kt:26-37` `resolve()` 中 `File(path).isAbsolute -> File(path)`，末尾仅 `.canonicalFile`，**没有 workspace 前缀比较**，`:41-59`/`:61-69` 直接读取与列目录。
- 影响：模型（可被网页正文、分享内容提示注入）可读取 `/data/data/<任意应用>` 私有数据、`/sdcard` 全量内容并回灌上下文，无任何确认。`docs/AGENT_RUNTIME.md:194` 把「不限定目录白名单」写作设计选择，但叠加「读到的内容会外发远端模型」后风险等级已变。
- 修复：读类工具默认限制在 workspace（`canonicalPath.startsWith(workspace.canonicalPath)`），越界需显式用户授权；对 `/data/data`、`/data/misc`、`shared_prefs`、`*.db` 等敏感路径单独加闸门。

### H6 「记住」免批窗口以 24 字符前缀为键，24 小时内同前缀全免批

- 证据：`AgentConfirmGuard.kt:873-874` `subjectOf = draft.risk.name + "|" + draft.title + "|" + target.orEmpty().trim().take(24)`；`:322-323` terminal 的 target 取 `args.optString("command").take(40)`；`:799-803` `grants[current.subject] = now + (remember ? GRANT_LONG_MS /*24h*/ : GRANT_WINDOW_MS /*30min*/)`；`:376-380` `register()` 命中 grant 直接 `return null`。
- 影响：用户批准过一次 `su -c 'rm -rf /data/local/tmp/eta/build'`，24 小时内所有前 24 字符相同、后缀任意的命令都免确认。
- 修复：免批键改用「命令全文 + 参数规范化哈希」或结构化规则（命令 + 目标路径）；对 `MONEY`/不可逆类目彻底禁用记住。

### H7 智能档裁决失败即放行；`fetch_url` 不过滤内网与环回

- 证据：`app/src/main/kotlin/io/github/mangi/eta/agent/tool/ToolReviewArbiter.kt:26` 注释自承「裁决失败永远偏『放行』」，`:35-74` 超时/异常/`stopReason != END_TURN` 一律返回 `null`，`:86-99` 首词不合规也 `null`，`:81` 参数只喂前 1200 字符；`app/src/main/kotlin/io/github/mangi/eta/agent/tool/ToolReviewMode.kt:85-142` SMART 把 `null` 与 `false` 都当 Delegate。
- SSRF：`app/src/main/kotlin/io/github/mangi/eta/agent/web/WebHttpTransport.kt:183-194` `validateUrl` 只判空/长度/控制字符/`toHttpUrlOrNull`/禁内嵌账号，**无 loopback、私有网段、link-local、云元数据地址判定**；正文上限 2 MB 回灌模型。（`:47` `followRedirects(false)` 且 `:117-119` 每跳重验，跳转侧安全。）
- 修复：裁决失败应保持「等用户」而非放行；`validateUrl` 增加内网/环回/元数据地址黑名单（解析后按 IP 判定，防 DNS 重绑定至少做一次 final-IP 校验）。

### H8 `VirtualScreenSurfaceProvider` 清单里根本没有签名权限

- 证据：`app/src/main/AndroidManifest.xml:278-282` `exported="true"`、`grantUriPermissions="false"`、**无 `android:permission`**；紧邻注释 `:275-277` 却写明「provider 带 signature 级权限，普通应用调不到，第三方应用即使拿到 authority 也取不到 Surface」——注释与清单不一致。
- 现状唯一防线：`app/src/main/kotlin/io/github/mangi/eta/agent/display/VirtualScreenSurfaceProvider.kt:22` 校验 `extras` 中的 token 等于进程内 `UUID.randomUUID()` 生成、且不轮换的随机串（由虚拟屏宿主对象随 create 请求经 stdin 交给 root helper 使用）。
- 影响：安全性完全依赖一个静态随机串；一旦被日志、调试或未来某处打印，虚拟屏 Surface 即可被任意应用通过 `call()` 取得（等于屏幕内容与输入通道外包）。
- 修复：清单补 signature 权限（与注释对齐）；token 按会话轮换，并在 provider 侧校验调用 uid。

### H9 FileProvider 暴露范围过宽，且对外授读写

- 证据：`app/src/main/res/xml/eta_file_paths.xml` 同时声明 `cache-path`（diagnostics/、images/、shared_docs/）、`<files-path path="." />`（**整个私有 files 根**，含 DataStore、`files/traces`）、`<root-path path="/data/local/tmp/eta/" />`、`<external-path path="." />`（**整张外部存储**）；`app/src/main/AndroidManifest.xml:80-88` `grantUriPermissions="true"`。
- 放大点：`app/src/main/kotlin/io/github/mangi/eta/ui/markdown/MarkdownFileCard.kt:593-620` `openInMtEditor` 只校验 `path.startsWith("/") && File(path).isFile`，**不做 workspace 包含性校验**，随后 `:601-613` 通过 `FileProvider.getUriForFile(...)` + `FLAG_GRANT_READ|WRITE_URI_PERMISSION` 交给 MT 管理器；`:623-635` `openWithSystemChooser` 同样授读写后交给用户任选的第三方应用。
- 影响：任意「点一下用编辑器打开」的路径都能把 files 全树或外部存储以 URI 形式交给外部应用并授予写权限；与 H5 结合可批量外带。
- 修复：`files-path` 收窄到具体子目录；去掉 `external-path` 与 `root-path` 通配；分享操作只授 READ，写权限仅对 workspace 内文件开放并做 canonical 前缀校验。

### H10 全量备份包明文携带 API Key 等凭据

- 证据：`app/src/main/kotlin/io/github/mangi/eta/data/repository/EtaFullBackupArchive.kt:57` `includeTop = listOf("databases","shared_prefs","files")`、`:45` 后缀 `.eta-fullbackup.zip`、`:60-64` 仅排除 `files/terminal`、`files/terminal-user`、`files/restore-staging`。
- 影响：包内含 `databases/eta.db`，其中 `data/db/ProviderEntities.kt:37` `@ColumnInfo(name = "api_key") val apiKey: String` 为**明文**；另有全部 `shared_prefs/*.xml`。落点是用户自选的 SAF/网盘，zip 不加密（逻辑备份 `EtaBackupJsonStreams` 反而剔除了 apiKey，两条路径不一致）。
- 修复：全量包对 `api_key` 与 `shared_prefs` 敏感键做剔除或加密（Keystore 派生口令），导出前明确告知包含凭据。

### H11 备份写入与校验不足，半截文件会被判成功

- 证据：`app/src/main/kotlin/io/github/mangi/eta/data/repository/BackupStorage.kt:77-91` 先 `target.delete()`，`renameTo` 失败则回退 `input.copyTo(output)` 后仅判 `target.length() > 0L`（注释自承个别 ROM 的 FUSE 拒绝跨 UID 改名）；`AutoBackupRunner.kt:481-493` 仅 `full` 模式调 verify，而 `EtaFullBackupArchive.kt:80-97` 的 verify 只读尾部 64 KB 找 EOCD。
- 影响：中断或空间不足产生的截断包可通过校验，用户以为有备份，恢复时才发现不可用；非 full 模式完全不校验。
- 修复：写临时文件 → fsync → 原子 rename，失败保留原文件；verify 校验条目数与总字节（或 CRC）；所有模式统一校验。

### H12 全量还原无事务、无回滚，失败被吞掉

- 证据：`EtaFullBackupArchive.kt:388-406` `privateRoot.walkTopDown().filter{it.isFile}.forEach { source.copyTo(target, overwrite = true) }`、`:393-397` 先删 `-wal/-shm/-journal`、失败仅 `.onFailure { warn(...) }`；`EtaApp.kt:57` 用 `runCatching` 吞掉异常。
- 影响：中途失败会留下「一半新一半旧」的数据目录（旧表 + 新 WAL 已被删），UI 仍报成功；也不清理备份中不存在的文件，残留旧 schema 文件。
- 修复：解包到 `files/restore-staging` → 校验完整性 → 关闭数据库后整体替换 → 失败回滚；异常必须冒泡到 UI。

### H13 全局允许明文 HTTP

- 证据：`app/src/main/res/xml/network_security_config.xml` 全文仅 `<base-config cleartextTrafficPermitted="true" />`（无 `domain-config`、无更严的 `trust-anchors`）。
- 影响：模型 API、MCP、WebDAV 同步等携带 API Key / 会话正文的请求可被降级到明文；自定义 base URL 时风险放大。
- 修复：默认 `cleartextTrafficPermitted="false"`，仅对确需的局域网地址开 `domain-config`。

### H14 `HeavyCommandGate` 双重释放导致互斥永久失效

- 证据：`app/src/main/kotlin/io/github/mangi/eta/agent/terminal/HeavyCommandGate.kt:60-73` `releaseIfStale()` 在 `heldTooLong`（`> STALE_HOLD_MS`）时清空 `ownerThread/acquiredAt` 并 `slot.release()`；`:115-123` `finally { ownerThread = null; acquiredAt = 0L; slot.release() }`。
- 影响：一条重命令持锁超时后被他人强制释放，原线程结束时再释放一次 → `permits = 2`，此后可并发进入两条重命令（多会话同时编译），内存峰值叠加，正是这道闸门要避免的 OOM；反复触发会持续放大。
- 修复：保证「只释放一次」（换成带持有者标记的锁，`finally` 中仅当仍持有 owner 标记才释放），或 `releaseIfStale` 只清标记不 `release()`，由下一次 `tryAcquire` 自愈。

### H15 读侧配置/状态损坏一律 fail-open 或「重置为空」

- `app/src/main/kotlin/io/github/mangi/eta/config/Prefs.kt:277-284` 禁用工具列表 `runCatching{...}.getOrDefault(emptySet())`（注释「读失败按全开处理」）、`:160-164` 布尔默认 `true`；而运行期 `agent/runtime/AgentRuntimePolicy.kt:78-82` 是 fail-closed。**两处策略相反**，读失败时用户禁用的危险工具会重新出现。
- `app/src/main/kotlin/io/github/mangi/eta/data/datastore/SettingsDataStore.kt:226-232`（另有 `:58-61`、`:78-81`、`:355-360`、`:402-405`、`:422-424`、`:430-431`、`:636-639` 同型）`.catch { if (cause is IOException) emit(emptyPreferences()) else throw cause }`，且 `:174` 未注册 `ReplaceFileCorruptionHandler`；`CorruptionException` 是 `IOException` 子类 → 设置静默回默认（记忆、自动备份、审批档位、虚拟屏全组归零）并被后续写入固化。
- `app/src/main/kotlin/io/github/mangi/eta/agent/terminal/DetachedTaskSupervisor.kt:551-578` JSON 损坏即 `mutableListOf()` 并在 `:578` 回写覆盖（`rename` 失败直接 `writeText`，非原子）→ 后台任务记录整体丢失。
- `app/src/main/kotlin/io/github/mangi/eta/agent/prompt/PromptPresetRepository.kt:32-38` 解析失败 `return emptyList()`，`:40-52` 基于空表算 `next` 后 persist，`:84-89` 写失败静默 → 预设被清空。
- 修复：统一「损坏即隔离」策略（改名 `.corrupt-<ts>` + 提示 + 保留原文件）；禁用列表读失败按空集（全禁）处理，与运行期一致。

---

## Medium

### M1 闹钟 `alarm_id` 类型不一致，改/删/停用在模型侧不可用

`app/src/main/kotlin/io/github/mangi/eta/agent/phone/PhoneOperation.kt:70-75` `id()` → `text()`（`:43-52` `args.opt(key) as? String ?: error("INVALID_ARGUMENT", "$key 必须是文本")`）只接受字符串；工具契约 `agent/model/AgentPhoneToolCatalog.kt:214` 亦声明 `text(...)`。但生产者输出数字：`agent/phone/ClockOperations.kt:96`（list_alarms）、`:147`（set_alarm）、`:198`（change）。模型照抄返回体的数字回填即报「alarm_id 必须是文本」。对比同类模块均为字符串（`CalendarOperations.kt:190`/`:239`/`:255` `id.toString()`）。属 `a47723b`/`047ca47` 回归。修：统一 `put("alarm_id", id.toString())`。

### M2 诊断包自动落共享存储，且含全系统日志与插话正文

`app/src/main/kotlin/io/github/mangi/eta/core/DiagnosticsBundle.kt:65-77` root 时执行 `su -c "logcat -d -v threadtime -t 4000"` 取**全系统**日志；`core/DiagnosticsAutoExport.kt:98-117` 周期性无条件采集并写入共享存储目录（`:244-266` 失败回退 `getExternalFilesDir`），触发点 `EtaApp.kt:128` 与 `core/DiagnosticsAutoExportReceiver.kt:21`/`:23`。同时 `AgentConfirmGuard.kt:850-858` `steer()` 把用户插话正文前 60 字写入 `EtaTrace`，而 `core/EtaTrace.kt:165` 落在 `files/traces`（正好在 FileProvider 白名单内），与 `DiagnosticsBundle.kt:215-226` README 声称的「不含会话正文」冲突。修：logcat 按本应用 `--pid` 过滤；插话正文不入 trace；自动导出改为显式触发。

### M3 同会话新审批卡顶掉旧卡，旧工具线程空等 30 分钟

`AgentConfirmGuard.kt:420-423` 先 `verdictResults.putIfAbsent(pending.token, ...)`，再用 `filter { it.conversationKey != owner ... } + pending` 重建列表 → 旧 token 的 slot 仍在，但卡已从 UI 消失，`takeIfCurrent`（`:835-848`）永远找不到，`awaitVerdict`（`:453-474`，30 分钟）超时后按 `NEED_CONFIRM` 收尾。修：替换卡时同步 `verdictResults.remove(oldToken)` 并给旧 token 写 `superseded` 结果。

### M4 文本分块宽松解析会把空/半截内容写回

`app/src/main/kotlin/io/github/mangi/eta/data/db/ChunkedTextDao.kt:121` `parseReference(stored) ?: return ""`、`:129-132` 分块缺失即 `break`，而严格版 `restoreText`（`:82-102`）会抛错 → 同库两条读取路径语义不一致，历史可能在后续写入时被固化为空。相关：`ChatDraftStore.kt:20-34` 用 `prefs.all.keys.toList()` 无序 `take` 删草稿；`EtaFullBackupArchive.kt:417` 仅按 `substringAfterLast('/')` 匹配 JSON 条目。

### M5 交互终端无超时、输出重建为 O(n²)

`app/src/main/kotlin/io/github/mangi/eta/ui/terminal/UserTerminalController.kt:173-181`、`:208` 交互 exec 传 `timeoutMs = null`；`:320-371` 每 50 ms 用 `session.stdout.text()` 全量重建缓冲区。长命令或高频输出时 CPU/主线程压力显著。修：交互命令加可取消超时与增量读取；输出改为环形缓冲 + 增量 diff。

### M6 `delete_alarm` 的 verified 判定会把查询失败当成功

`ClockOperations.kt:187-196` 用 `after == null` 判删除成功，而 `:222` `runCatching { alarms()... }.getOrNull()` 把查询异常也变成 `null` → 读取失败即报「已删除」。同时 `:204-217` `mutate` 自身在 block 返回 null 时已抛 `CLOCK_UNAVAILABLE`，`:135`、`:173`、`:183` 之后的 `?: PhoneOperation.error(...)` 为不可达死代码。修：区分「查询成功且为空」与「查询失败」，删除后必须回读确认。

### M7 `AgentRuntimeService` 在事件热路径上反复阻塞读设置

`AgentRuntimeService.kt:581-593` 对每个 `ToolStarted` 执行 `runBlocking { SettingsDataStore.settings() }`（`:585`，无 `runCatching`），`:738-779` `handleAcceptedRunEvent` 每个事件同样 `runBlocking`（`:746-747`）。DataStore I/O 抖动会直接拖慢事件链，异常还会打断事件处理。修：设置值缓存到内存 + `Flow` 订阅，热路径只读缓存。

### M8 主线程同步读 DataStore

`app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt:224-248` `loadRecentOpenedIds` / `persistRecentOpened` 用 `runBlocking` 读写 DataStore，调用点 `:1804`、`:1970`（主线程）。首次读取整文件时可见掉帧。

### M9 若干监听器/回调清理不完整

`app/src/main/kotlin/io/github/mangi/eta/hook/vivo/VivoIslandNotifications.kt:61-69` 只 `registerReceiver` 无对应 `unregisterReceiver`（构造于 `VivoHooks.kt:73-79`）；`VirtualScreenFloatWindow.kt:519-523` 先 `rootView = null` 再 `removeCallbacks(...)`（后者恒 no-op）；`AgentLiveActivity.kt:436-448` 每次 `endTakeover` 新建不可取消的 `CoroutineScope`。

---

## Low

- L1 `ClockOperations.kt:135`/`:173`/`:183` 不可达分支（同 M6）。纯冗余，删掉即可。
- L2 `app/src/main/kotlin/io/github/mangi/eta/agent/tool/AgentToolCallValidator.kt:201-250` `uniqueItems` 判重用 O(n²) 且无元素上限；`:252-261` `pattern` 用 `containsMatchIn`（非全串匹配）。
- L3 `LocalFileToolBackend.kt:95-161` append 写用 `FileOutputStream(target, append = true)`，多写并发/中断时非原子（未实机验证）。
- L4 `AgentConfirmGuard.kt:893-896` 审批 token 为 `"guard-" + currentTimeMillis() + "-" + tokenSeed`，可预测（当前未见可注入入口，防御纵深不足）。
- L5 `AgentRuntimeService.kt:551` `sessionLimit = AgentMemoryGate.sessionLimit().coerceAtMost(MAX_CONCURRENT_RUNS)` 中两个上限同源（`AgentMemoryGate.MAX = 6` 与 `MAX_CONCURRENT_RUNS = 6`），`coerceAtMost` 实为 no-op，易被误读为双保险。
- L6 `agent/phone/AlarmSchedule.kt:61-72` 夏令时跳变日目标本地时刻不存在时仅依赖 `Calendar` 规范化，未显式处理（中国大陆无 DST，暂不影响）。
- L7 `EtaFullBackupArchive.kt` stage 目录无字节/文件数上限，异常大包会撑爆临时空间。
- L8 `.github/workflows/android-release.yml:57` `build-apks` 的 checkout 未设 `persist-credentials: false`（`verify` job 在 `:29` 设了），持签名 secrets 的 job 里 `.git/config` 残留 token；因全局 `permissions: contents: read` 无法 push，仅纵深不足。
- L9 `agent/terminal/TerminalExecutionContext.kt:51-52` 有 root 时终端身份直接切 `root`（`:59` 注释亦如此说明）；建议 UI 强区分「root 终端」与「普通终端」，避免用户误判操作影响范围。

---

## 已核查、确认不是缺陷（勿重复排查）

- `AgentLoop.kt:488-495`、`:510-525`：坏 JSON / 错误参数类型已返回 `INVALID_TOOL_ARGUMENTS`。
- `AgentLocalTools.kt:280`：工具执行整体 `runCatching` 包裹。
- `WebHttpTransport.kt:47`、`:117-119`：`followRedirects(false)` + 每跳重校验，跳转不可达 `file://`。
- `MainScreenFallbackApproval.kt:42` 超时判 `TIMEOUT`，且 `AgentLocalTools.kt:240-259` 只认 `ALLOWED`（该路径 fail-closed）。
- `RootShellTerminalController.kt:178-229`：180 s 上限 + 进程树清理。
- `AgentMemoryGate.kt:70-79`：`availableMb() == -1`（读不到）不会落入冻结区，`:94-101` 读不到时返回 DEFAULT，无「误冻结」。
- `AgentExecutionService.kt:59-61`、`:243`：`startForeground` + `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 合规。
- 持久化：`AgentMemoryRepository`（`AtomicFile` + 同步）、`McpSecretStore`/`SpeechSettingsRepository`/`WebToolsSettingsRepository`（Keystore AES-GCM）、`CharacterRepository` 头像（`canonicalPath` 限 `files/roleplay`）、`NotificationHistoryRepository`（单事务插入 + 裁剪）、30 个迁移的 `addColumnIfMissing` 幂等、39 处 `updateSettings` 无陈旧快照。
- 组件：`EtaVoiceInteractionSessionService`/`EtaRecognitionService`/`AgentAccessibilityService`/`AgentAccessibilityHealthProvider`/Job 服务均带系统级 `android:permission`；`AgentTaskReceiver` 等均 `exported="false"`。
- `ui/app/ShareIntake.kt` 只把分享文本预填到输入框、不自动发送，故「任意应用经 `MainActivity` 注入并自动执行」不成立（仅 UI 打扰，低危）。
- `AgentRuntimeImageTransfer`：临时文件名用 `UUID`（`:116-119`）无路径穿越；数量/字节上限齐全（`:32`/`:41`/`:44`）。
- CI：`android-release.yml` 全局只读、action 按 SHA 固定、`apksigner verify` + tag/versionName 一致性校验；`pr-check.yml` 无 secrets。

## 一处已撤回的误报（保留记录，防止重复排查）

曾有结论称「备份/崩溃文件名的时间戳 `SimpleDateFormat` pattern 写错，导致按名排序不等于时间序，`prune()` 会删新留旧」。经独立复现（把该字面量写入临时 Java 程序，打印每个 `char` 的数值编码，并做「两个不同字面量编译后编码相同」的对称性实验）确认：**该结论不成立**，代码中的时间戳格式与实际值正常，`CrashRecorder.records()/prune()` 的排序逻辑无误。当时的异常渲染是审计环境的显示层假象，不是仓库缺陷。

## 建议修复顺序

1. **C1 + H1 + H2 + H3**：审批闭环与 IPC 信任边界，四者互相放大，必须先修。
2. **C2 + H15**：数据安全底线（静默删库、损坏即重置）。
3. **H5 + H9 + H13 + H10**：读取边界、外发通道、明文与凭据。
4. **H4 + H6 + H7**：危险操作判定与免批粒度、SSRF。
5. **H11 + H12 + H14 + M6**：备份/还原事务性与闸门语义。
6. **M1～M9、L1～L9**：功能回归与体验/健壮性。
