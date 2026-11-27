# 缺陷审计复核与修复报告（2026-11-26）

> 对应文档：`docs/AUDIT_REPORT_2026-11-26.md`（branch @ 6c3083b，2C / 15H / 9M / 9L，共 35 条）
> 复核方式：每一条都回到源码逐行核对，不采信报告结论；修复按「A 档：只修实现缺陷，不改安全策略」范围执行。
> 基线：`2934102d0`（分支 `branch`）

## 0. 结论速览

| 分类 | 条数 |
|---|---|
| 逐条核对后确认成立 | 30 |
| 部分成立（描述有偏差，问题真实存在） | 3 |
| 判定不成立 | 2 |
| **本轮已修复** | **17** |
| 未修（按用户选择留在范围外） | 18 |

修复验证：`:app:compileDebugKotlin` 通过；`:app:assembleDebug` / `:app:assembleRelease` 通过；
新增 4 个纯 JVM 测试类共 29 个用例全部通过；全量 `:app:testDebugUnitTest` 与前基线（`2934102d0`）逐用例对比，**无新增失败**。

## 1. 逐条核对结论

### Critical

| 编号 | 结论 | 核对要点 |
|---|---|---|
| C1 | **成立** | `AgentConfirmGuard.skip()`（`:662-671`）与 `chooseFreeform()`（`:629-654`）调用 `completeVerdict(token, reply)` 时未传 `rejected`，默认 `false`；`AgentLocalTools.kt:350-355` 只判 `outcome.rejected`，否则 `approvalGranted = true` 继续执行原工具。默认值是 fail-open。 |
| C2 | **成立** | `EtaDatabase.kt` 迁移链最低 6→7，配合 `fallbackToDestructiveMigration(dropAllTables = true)`；`EtaApp.kt:57` 在 Room 打开前恢复备份，回退安装或老库升级都会命中。 |

### High

| 编号 | 结论 | 核对要点 |
|---|---|---|
| H1 | **成立** | 清单 `:186-189` `exported="true"` 且无 `android:permission`；`onStartCommand`（`:202-227`）在 `consentAllowsRuntime()` 后直接按 action 分派，无 `Binder.getCallingUid()` 校验。 |
| H2 | **成立** | `isMessageSenderAllowed`（`:1405-1412`）只比对 `getPackagesForUid` 结果与包名字符串集合，无签名校验。 |
| H3 | **成立** | `analyzeSegment` 只匹配字面 `-c` / `--command`，`bash -lc` 不递归。 |
| H4 | **成立** | 仅拦 `rm` / `mkfs*` / `dd of=/dev/*` / `pm clear` / `fastboot` / `wipe` / `chmod -R /` / `base64 -d`；`cp`、`mv`、`tee`、`ln -s`、`sed -i`、重定向全部放行。 |
| H5 | **成立** | `LocalFileToolBackend.resolve()` 对绝对路径直接 `File(path)`，仅 `canonicalFile` 收敛，无 workspace 前缀比较。 |
| H6 | **成立** | `subjectOf` 取 `take(24)`，`inspectTool` 的 target 取 `take(40)`，`grants` 窗口 24h。 |
| H7 | **成立** | `ToolReviewArbiter.decide` 超时/异常/非 END_TURN 一律 `null`，`SMART` 档把 `null` 当放行；`WebHttpTransport.validateUrl` 无环回/私网/元数据地址判定。 |
| H8 | **成立** | 清单 `:278-282` 的 provider 只有 `exported="true"` + `grantUriPermissions="false"`，注释却声称带 signature 权限。 |
| H9 | **成立** | `eta_file_paths.xml` 含 `<files-path path="."/>`、`<root-path path="/data/local/tmp/eta/"/>`、`<external-path path="."/>`；`MarkdownFileCard.openInMtEditor` 只校验存在性。 |
| H10 | **成立** | `includeTop = ["databases","shared_prefs","files"]`，`eta.db` 内 `api_key` 为明文（`ProviderEntities.kt:37`）；逻辑备份 `withoutOAuthSecrets()` 会剔除 OAuth 密钥，两条路径口径不一致属实。 |
| H11 | **成立** | `BackupStorage.renameTo` 回退分支只判 `target.length() > 0L`；`AutoBackupRunner` 仅 `full` 调 verify；`analyzeTail` 只读尾部 EOCD。 |
| H12 | **成立** | `applyPendingRestore` 逐文件 `copyTo(overwrite=true)`，无暂存→校验→替换流程；失败只 `warn`，且仍会 `deleteRecursively(staging)`。 |
| H13 | **成立** | `network_security_config.xml` 全文只有 `<base-config cleartextTrafficPermitted="true"/>`。 |
| H14 | **成立** | `releaseIfStale()` 内 `slot.release()`，`guarded()` 的 `finally` 再 `release()`，超时后 permits 可到 2。 |
| H15 | **成立** | `Prefs.disabledTools` 读失败 `getOrDefault(emptySet())`（全开），`AgentRuntimePolicy.allowed` 是 fail-closed；`SettingsDataStore` 多处 `.catch { if (IOException) emit(emptyPreferences()) }` 且无 `ReplaceFileCorruptionHandler`；`DetachedTaskSupervisor` / `PromptPresetRepository` 损坏即重置并回写。 |

### Medium

| 编号 | 结论 | 核对要点 |
|---|---|---|
| M1 | **成立** | `PhoneOperation.id()` 只接受字符串；`ClockOperations` 的 list / create / change 用 `.put("alarm_id", c.getLong(...))` 输出数字；日历侧一律 `id.toString()`。 |
| M2 | **成立** | `DiagnosticsBundle.logcat(root)` 在 root 时执行 `su -c "logcat -d -v threadtime -t 4000"`（全系统）；`DiagnosticsAutoExport` 周期采集并写共享存储；`AgentConfirmGuard.steer()` 把插话前 60 字写进 `EtaTrace`，而 README 声称「不含会话正文」。 |
| M3 | **成立** | `register()` 先 `putIfAbsent` 新 slot，再 `filter{...} + pending` 重建列表；旧 token 的 slot 留在 map 里但卡片消失，`takeIfCurrent` 找不到。 |
| M4 | **成立** | `restoreTextTail` 的 `parseReference(stored) ?: return ""`、分块缺失 `break`；严格版 `restoreText` 抛错。`ChatDraftStore` 用 `prefs.all.keys.toList()`（无序）淘汰；`extractDocument` 只比对 basename。 |
| M5 | **成立** | `UserTerminalController.exec` 传 `timeoutMs = null`；`execInternal` 每 `POLL_INTERVAL_MS` 用 `session.stdout.text()` 全量取样再 `substring`。 |
| M6 | **成立** | `verified` 用 `after == null` 判删除，而 `awaitAlarm` 里 `runCatching{...}.getOrNull()` 把查询异常也变 `null`。 |
| M7 | **成立** | `eventSink`（`:581-593`）与 `handleAcceptedRunEvent`（`:738-779`）都在事件路径上 `runBlocking { SettingsDataStore.settings() }`。 |
| M8 | **成立** | `loadRecentOpenedIds` / `persistRecentOpened` 用 `runBlocking` 读写 DataStore，构造期与主线程调用点各一处。 |
| M9 | **部分成立（第 1 点不成立）** | `VivoIslandNotifications` 存的是 `context.applicationContext`，**不存在 Activity 泄漏**（登记在 Xposed 进程里长期存活）；而且进程内没有重建实例的路径（唯一赋值点在 `CopilotApp.onCreate` 拦截里，`ModuleMain` 无热重载、`hookHandles` 无 unhook），所以「缺 `unregisterReceiver`」也不是正确性缺陷，**不需要修**。`VirtualScreenFloatWindow.hide()` 先 `rootView = null` 再 `removeCallbacks`（恒 no-op）属实；`AgentLiveActivity.endTakeover` 每次新建不可取消 `CoroutineScope` 属实。 |

### Low

| 编号 | 结论 | 核对要点 |
|---|---|---|
| L1 | **部分成立** | 报告说 `:135/:173/:183` 是「不可达分支」。核对后：这三处是 `?: PhoneOperation.error(...)`，`mutate` 返回 `T?` 时**可达**。一旦把 `mutate` 收紧为 `T`，它们才真正变成死代码。本轮按后者处理。 |
| L2 | **成立** | `validateArray` 的 `uniqueItems` 是双重循环 `jsonEquals`；`pattern` 用 `containsMatchIn`（JSON Schema 语义正是「部分匹配」，此处报告定性有误，但不构成缺陷）。 |
| L3 | **成立（未验证）** | `append` 分支用 `FileOutputStream(target, true)` 直写 + `fd.sync()`，非原子，报告已注明未实机验证。 |
| L4 | **成立** | `nextToken()` = `"guard-" + currentTimeMillis() + "-" + tokenSeed`。 |
| L5 | **成立** | `AgentMemoryGate.MAX_SESSION_LIMIT = 6` 与 `MAX_CONCURRENT_RUNS = 6` 同源，`coerceAtMost` 为 no-op。 |
| L6 | **成立（无影响）** | `AlarmSchedule.nextTriggerMillis` 依赖 `Calendar` 规范化，未显式处理 DST 跳变；中国大陆无 DST。 |
| L7 | **成立** | `stage()` 无字节/文件数上限。 |
| L8 | **成立** | `build-apks` job 的 checkout 未设 `persist-credentials`，`verify` job 设了。 |
| L9 | **成立** | `TerminalExecutionContext.identityFor` 有 root 时直接返回 `"root"`。 |

### 判定不成立 / 需修正的表述

1. **L1「这三处是不可达死代码」**：原文结论因果颠倒。真正成立的是「`mutate` 返回类型为 `T?`，导致调用点的 `?: error(...)` 冗余」。
2. **L2 中「`pattern` 用 `containsMatchIn`（非全串匹配）」**：JSON Schema 草案规范中 `pattern` 就是「在字符串中搜索该正则」，`containsMatchIn` 是正确实现，不是缺陷。报告自身在「已核查、确认不是缺陷」一节并未收录这一条，前后不一致。
3. **M9 的 Activity 泄漏判断**：见上表，其余两点属实。

## 2. 本轮改动清单

### 2.1 危险动作判定（H3、H4）

`agent/display/dashboard/ConfirmRiskDetector.kt`

- `isCommandFlag()`：任意含 `c` 的组合短选项（`-lc`、`-ec`、`-xc`）都按 `-c` 递归。
- `redirectTarget()` + `redirectToken` / `inlineRedirect`：解析 `>`、`>>`、`2>`、`2>>`，含无空格写法。
- `writeVerbs` + `writeTargets()`：`cp`/`mv`/`ln`/`tee`/`install`/`rsync`/`sed`/`chmod`/`chown`/`truncate`/`mkdir`/`rmdir`/`touch`/`mknod`，按「动词 + 目标」判。
- `systemWritePrefixes` + `isSystemWritePath()`：写入判定只覆盖系统分区；`/sdcard`、`/storage` 不拦（删改日常文件不该弹卡，否则用户会无脑批准）。
- `sedWrites()`：`sed` 仅在带 `-i` 时算写操作。
- `dd of=/…` 与 `chmod -R /` 的原有分支保留。

### 2.2 数据安全（C2）

`data/db/EtaDatabase.kt`

- 移除 `fallbackToDestructiveMigration(dropAllTables = true)`。
- 新增 `quarantineIfUnmigratable(context)` / `quarantineIfUnmigratable(File)`：打开前读 SQLite 头 `user_version`（偏移 60，大端），
  版本 < `MIN_MIGRATABLE_VERSION`（=6）或 > `ETA_DB_SCHEMA_VERSION` 时，把库与 `-wal`/`-shm`/`-journal` 一起改名成 `eta.db.broken-<ts>` 并新建库。
- 纯文件重载供纯 JVM 单测覆盖（Robolectric 在 aarch64 会被跳过）。

### 2.3 审批闭环（H6、M3）

`agent/display/dashboard/AgentConfirmGuard.kt`

- `subjectOf()`：免批签名改用**完整**命令/路径 + 空白归一化，不再 `take(24)`。
- `inspectTool()` / `requestReview()`：target 不再 `take(40)`；`AgentLocalTools.reviewTarget()` 同步。
- `register()` / `ask()`：换卡时给被顶掉的旧 token 写入 superseded 结果并 `countDown()`，旧工具线程立即以失败语义收尾，不再空等 30 分钟。

### 2.4 并发闸门（H14）

`agent/terminal/HeavyCommandGate.kt`

- 引入 `Holder`（持 `AtomicBoolean` 的 `releaseOnce()`）；`finally` 与残留自检都走它，保证「占用 → 归还」一一对应。
- 重写为 `releaseIfStale()` 语义：只由持有者票据决定能否归还。

### 2.5 闹钟（M1、M6、L1）

- 新增 `agent/phone/AlarmVerification.kt`：`Lookup`（`Found`/`Gone`/`Unknown`）、`verified()`、`parseId()`。
- `PhoneOperation.id()` 改为接受数字与数字文本（JSON 的 `7.0` 视为 7，真小数拒绝）；非法报 `INVALID_ARGUMENT`。
- `ClockOperations`：`alarm_id` 一律 `toString()` 输出；删除确认改走 `AlarmVerification`，查询失败报 `CLOCK_UNAVAILABLE` 而非 `ALARM_NOT_FOUND`；`mutate` 返回类型收紧为 `T`，删除三处死代码。

### 2.6 持久化（M4、H11、H12）

- `ChunkedTextDao.restoreTextTail`：引用头损坏或分块缺失改为抛错（与严格版口径一致），不再返回半截内容被写回。
- `ChatDraftStore`：新增 `KEY_ORDER` 记录写入顺序，淘汰按序进行；`load` 排除内部键。
- `EtaFullBackupArchive.extractDocument`：条目名精确匹配 `eta-backup.json`。
- `EtaFullBackupArchive.applyPendingRestore`：失败返回 `false` 且**保留暂存区**，不再吞异常后删现场。
- `BackupStorage`：新增 `syncBeforeRename()`（文件版 `RandomAccessFile.fd.sync()`，SAF 版 `FileDescriptor.sync()`）。
- `AutoBackupRunner`：rename 前先 fsync；非 full 模式补空文件校验。

### 2.7 性能与生命周期（M7、M8、M9）

- `SettingsDataStore`：新增 `virtualScreenEnabledCache` + `cachedVirtualScreenEnabled()`，设置流同步维护。
- `AgentRuntimeService`：两处事件路径的 `runBlocking { settings() }` 改读缓存。
- `AgentAppState`：`recentOpenedIds` 改为构造置空 + `Dispatchers.IO` 异步补读，写回投递到 `scope`；移除不再使用的 `runBlocking` import。
- `VivoIslandNotifications`：**未改动（更正原描述）**。本节初稿写「新增 `release()` 注销接收器」，实际当时只加了方法、全仓零调用，等于死代码，且让 M9 看起来已修。复核结论：`VivoHooks` 是 `object`，`islandNotifications` 是 `@Volatile` 字段，唯一赋值点在 `VivoHooks.kt:73`（拦截 `CopilotApp.onCreate`，每进程一次）；`ModuleMain` 未实现 `onHotReloading` / `onHotReloaded`，`hookHandles` 也无 unhook 路径，进程内不存在「重建实例」。因此该接收器无需注销，`release()` 已删除，改为在 `VivoIslandNotifications` 里写明理由。
- `VirtualScreenFloatWindow.hide()`：`removeCallbacks` 提到置空之前。
- `AgentLiveActivity`：`AgentLiveCoordinator` 持有进程级 `takeoverScope`。

### 2.8 组件与 CI（H8、L2、L5、L8）

- `AndroidManifest.xml`：新增 `${applicationId}.permission.HOST_SURFACE`（`protectionLevel="signature"`）并挂到 `VirtualScreenSurfaceProvider`。
- `AgentToolCallValidator`：`uniqueItems` 改 `canonicalJson` 哈希判重；新增 `MAX_ARRAY_ITEMS = 10_000` 上限。
- `AgentRuntimeService`：去掉 `coerceAtMost(MAX_CONCURRENT_RUNS)` 这个 no-op。
- `.github/workflows/android-release.yml`：`build-apks` 的 checkout 补 `persist-credentials: false`。

## 3. 验证

```
:app:compileDebugKotlin                      BUILD SUCCESSFUL
:app:assembleDebug / :app:assembleRelease    BUILD SUCCESSFUL
```

### 3.1 产物

| 文件 | 大小 | 签名 | sha256 |
|---|---|---|---|
| `app/build/outputs/apk/release/Eta-v3.3.4.apk` | 12,535,531 B | **未签名**（本机无 CI 密钥） | `b0d1a32f…` |
| `app/build/outputs/apk/debug/Eta-v3.3.4-debug.apk` | 101,930,486 B | Android Debug 证书 | `fdfa55c3…` |

归档目录：`/data/media/0/ETA/audit-20261126/`（含 `SHA256SUMS.txt`）。

包内容抽查（`aapt2 dump`）：

- `permission: io.github.mangi.eta.permission.HOST_SURFACE` 已写入
- `provider` 节点带 `android:permission="io.github.mangi.eta.permission.HOST_SURFACE"`、`exported=true`
- `versionCode=2026112201`、`versionName=3.3.4`、`minSdk=33`、`targetSdk=37`

> **Release 包不可直接安装**：本机没有 `ETA_RELEASE_KEYSTORE*`，`assembleRelease` 产出的是未签名 APK
> （`apksigner verify` 会报 `Missing META-INF/MANIFEST.MF`）。要装机请用 debug 包，
> 或走 CI 的 `android-release.yml` 用真实密钥签名。

新增测试（纯 JVM，不依赖 Robolectric）：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `ConfirmRiskDetectorTest` | 9（+5） | 组合短选项递归、写动词进系统分区、`/data/adb` 写入、日常写读不误伤 |
| `EtaDatabaseQuarantineTest` | 8（新） | v3 / 高版本隔离、当前版本不动、`-wal/-shm` 跟随、空文件与非法文件不误判 |
| `HeavyCommandGateTest` | 5（新） | 重命令串行、异常归还、重复进出不多还、轻命令不受限 |
| `AlarmVerificationTest` | 7（新） | 删除仅在 `Gone` 确认、查询失败不确认、id 数字/文本兼容 |

全量回归：`:app:testDebugUnitTest` 共 1844 用例，失败 44。与 `2934102d0` 基线（1795 用例 / 失败 44）逐用例对比：

- 新增失败：仅 `SpeechHttpTest.cancellationClosesConnectionWhileBodyIsStillArriving`（本地 socket 时序敏感，单独重跑通过，且本分支未触及 speech 代码）。
- 基线失败、当前通过：`AgentToolRequirementsTest.everyRegisteredToolHasExactlyOneRequirement`（来自仓库内另一处进行中的改动，非本次范围）。
- 两侧共同失败 43 个：`SystemClock`/`Log` 未 mock、Robolectric aarch64 不支持、`ProviderComponentsTest` 品牌图数量等，均为环境或既有问题。

## 4. 未修项与原因

按用户选择只执行 A 档（不改安全策略），以下保持原样：

| 编号 | 原因 |
|---|---|
| C1 | 需改变「跳过」的产品语义（目前语义为「让模型自行继续」） |
| H1、H2 | 需改组件暴露面与签名信任策略，会影响三厂商语音入口的既有接入 |
| H5 | 读类工具限制 workspace 会破坏现有「读全盘」用法 |
| H7 | 裁决失败改 fail-closed 会显著增加等待 |
| H9 | FileProvider 收窄会让 MT 编辑器失去写权限 |
| H10 | 全量包剔除 `api_key` 会改变「完整可恢复」的定位 |
| H13 | 全局禁明文会打断自建 base URL / 局域网 WebDAV |
| H15 | 损坏即隔离涉及多模块统一策略与迁移 |
| L3、L4、L6、L7、L9 | 影响面小或有兼容取舍，留待后续单独评估 |
