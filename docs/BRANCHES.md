# 分支说明

> 2026-11-16 定，2026-11-16 修订（`up`→`source`、`port`→`pro`，新增开发分支 `branch`，新增择优源 `expressive`）。本仓库 `donglihu/Eta-por` 采用五分支制。
> 与 `docs/BRANCH_AND_RELEASE.md` 的关系：那份是上游 Oo0520 的规矩（主线 `test/beta-on-latest`），本仓库不适用，以本文为准。

## 一、五条分支

| 分支 | 角色 | 起点 | 推送时机 |
|---|---|---|---|
| `main` | 稳定分支，唯一出包线，只汇聚不开发 | 现有主线 | 合并时 |
| `branch` | 开发分支，日常改动全在这里 | `main` | 随时 |
| `source` | 择优源 A：`Mangi-11/Eta` 的暂存 | `main` | 仅同步时 |
| `pro` | 择优源 B：`Oo0520/Eta-por` 的暂存 | `main` | 仅同步时 |
| `expressive` | 择优源 C：`Sam-Fic/EtaExpressive` 的暂存 | `main` | 仅同步时 |

## 二、择优源头说明

本仓库由 `Oo0520/Eta-por` fork 而来，最终源头是 `Mangi-11/Eta`。三条上游性质不同，各自对应一条暂存分支：

| 分支 | 择优源头 | 地址 | 内容性质 |
|---|---|---|---|
| `source` | `Mangi-11/Eta`（上游官方） | https://github.com/Mangi-11/Eta | 官方功能与修复，无二创内容 |
| `pro` | `Oo0520/Eta-por`（二创源） | https://github.com/Oo0520/Eta-por | 二创功能，散落在 `feat/*`、`fix/*`、`test/*` 分支 |
| `expressive` | `Sam-Fic/EtaExpressive`（M3 Expressive 二创） | https://github.com/Sam-Fic/EtaExpressive | 深度改造线，版本 5.x，与官方直接分叉 |
| `branch` | 本仓库自研 | — | 自己写的功能、修复、重构，改完合回 `main` |

仓库关系：

```
Mangi-11/Eta                    上游官方（fork=False）
   ├─ Oo0520/Eta-por               二创源（fork 自官方）
   │     └─ donglihu/Eta-por          本仓库（fork 自 Oo0520）
   └─ Sam-Fic/EtaExpressive        M3 Expressive 二创（直接 fork 自官方）
```

remote 与分支对应：

| remote | 地址 | 权限 | 对应分支 |
|---|---|---|---|
| `origin` | `github.com/donglihu/Eta-por` | 读写 | 全部五条 |
| `source` | `github.com/Mangi-11/Eta` | 只 fetch | `source` |
| `pro` | `github.com/Oo0520/Eta-por` | 只 fetch | `pro` |
| `expressive` | `github.com/Sam-Fic/EtaExpressive` | 只 fetch | `expressive` |

同步前先加好 remote：

```bash
git remote add source     https://github.com/Mangi-11/Eta.git
git remote add pro        https://github.com/Oo0520/Eta-por.git
git remote add expressive https://github.com/Sam-Fic/EtaExpressive.git
```

### expressive 的特殊性（2026-11-16 实测）

| 指标 | 值 |
|---|---|
| 与本仓库 `main` 关系 | diverged，`ahead 2201` / `behind 386` |
| 与官方 `Mangi-11/main` 关系 | diverged，`ahead 2201` / `behind 218` |
| 版本线 | `5.6.0`（versionCode `2026111501`） |
| 本仓库版本线 | `3.4.0-beta.57`（versionCode `2026110806`） |
| 分支 | `main` / `feature/eta-island` / `xuner-agent` |
| 标签 | `v5.3.10` ~ `v5.6.0` |

**结论：这不是「同一版本的另一个分支」，而是走了 2201 个提交的另一条产品线。** 因此：

- 不要整包 `merge`，也不建议整提交 `cherry-pick`（包名、目录、UI 框架已大量重构，冲突面接近全仓）。
- 正确做法是**按功能点移植**：从对方仓库取某个功能的源码/资源，落到 `expressive` 或直接落 `branch`，在本地改到能编译，再进 `main`。
- 适合移植的类型：独立工具函数、算法、协议实现、资源样式、文案表。
- 不适合移植的类型：UI 页面骨架、导航结构、依赖版本、构建脚本。

## 三、数据怎么流

```
   source（官方择优）─────┐
   pro（二创择优）────────┼─ merge --ff-only ─►  main  ◄─ merge --no-ff ─  branch（开发）
   expressive（深度择优）─┘                        │                          ▲
                                                   └────── merge main ────────┘
```

四条规则：

1. `main` 上不写代码，只接收合并，保证任何时刻都能出包。
2. 日常开发只在 `branch` 上，改完合 `main`。
3. 上游提交只允许出现在 `source` / `pro` / `expressive` 上，`main` 上永远只有自己的提交和快进带入的成果。
4. 三条择优分支都从 `main` 拉起，合回走 fast-forward，不产生合并提交，避免分叉点变旧导致冲突放大。

反例（不要做）：直接在 `main` 上 `cherry-pick` 上游提交，或直接在 `main` 上改代码。

## 四、日常操作

### 开发（每天）

```bash
git switch branch
git merge main          # 先跟上主线，避免积压
# 改代码 → 提交 → 推送
git push origin branch
```

### 改完合并 main

```bash
git switch branch
git merge main                          # 再同步一次，冲突在这里解
git switch main
git merge --no-ff branch -m "merge: <本轮内容概述>"
git push origin main
```

合并前自检：

```bash
git branch --show-current    # 必须是 main
git status --short | wc -l   # 必须是 0
git log --oneline -1         # 确认这个提交含本轮要发的改动
```

### 吸收官方 / 二创（cherry-pick 型）

```bash
git fetch source                # 或 pro
git switch source               # 或 pro
git merge --ff-only main
git cherry-pick -x <提交 SHA>    # 只挑需要的
git switch main
git merge --ff-only source
git push origin main source
git switch branch && git merge main
```

### 吸收 expressive（移植型）

```bash
git fetch expressive
git switch expressive
git merge --ff-only main
git checkout expressive/main -- <具体文件路径>    # 按文件取，不整提交
# 在本地改到能编译、改到符合本仓库的框架
git add <文件> && git commit -m "port(expressive): <功能名>"
git switch main && git merge --ff-only expressive
git push origin main expressive
git switch branch && git merge main
```

### 冲突在哪解决

择优冲突在 `source` / `pro` / `expressive` 上解决，不要留到 `main`。上游提交与自己的实现打架的，按「这个文件由谁主导」决定，主导文件可对 `main` 侧整体保留。

## 五、规矩

1. 打包只从 `main`，`branch` / `source` / `pro` / `expressive` 绝不出包。
2. `main` 上不写自己的代码，只做合并。
3. 择优分支上不写自己的原创代码，只放来源提交或移植内容的原样副本 + 必要适配。
4. 吸收一律用 `cherry-pick -x`（能整提交时）或 `checkout <源头>/main -- <路径>`（只能按文件时），保留来源信息。
5. 每次同步完成打 tag：`sync-YYYYMMDD`，标签说明里写明来源仓库、分支、HEAD 短 SHA。
6. 不对 `main` 使用 `--force`。确需移动择优分支用 `--force-with-lease`。
7. 同步前先看差异量再动手：

   ```bash
   git log --oneline --no-merges main..source/main | head -30
   git diff main...expressive/main --stat | tail -5
   ```
