# 分支说明

> 2026-11-16 定，2026-11-16 修订（`up`→`source`、`port`→`pro`，新增开发分支 `branch`）。本仓库 `donglihu/Eta-por` 采用四分支制。
> 与 `docs/BRANCH_AND_RELEASE.md` 的关系：那份是上游 Oo0520 的规矩（主线 `test/beta-on-latest`），本仓库不适用，以本文为准。

## 一、四条分支

| 分支 | 角色 | 起点 | 推送时机 |
|---|---|---|---|
| `main` | 稳定分支，唯一出包线，只汇聚不开发 | 现有主线 | 合并时 |
| `branch` | 开发分支，日常改动全在这里 | `main` | 随时 |
| `source` | 同步源头 A：`Mangi-11/Eta` 的择优暂存 | `main` | 仅同步时 |
| `pro` | 同步源头 B：`Oo0520/Eta-por` 的择优暂存 | `main` | 仅同步时 |

## 二、同步源头说明

本仓库由 `Oo0520/Eta-por` fork 而来，最终源头是 `Mangi-11/Eta`。两条上游性质不同，各自对应一条暂存分支：

| 分支 | 同步源头 | 地址 | 内容性质 |
|---|---|---|---|
| `source` | `Mangi-11/Eta`（上游官方） | https://github.com/Mangi-11/Eta | 官方功能与修复，无二创内容 |
| `pro` | `Oo0520/Eta-por`（二创源） | https://github.com/Oo0520/Eta-por | 二创功能，散落在 `feat/*`、`fix/*`、`test/*` 分支 |
| `branch` | 本仓库自研 | — | 自己写的功能、修复、重构，改完合回 `main` |

三级仓库关系：

```
Mangi-11/Eta            上游官方（fork=False）
   └─ Oo0520/Eta-por       二创源（fork 自 Mangi-11/Eta）
        └─ donglihu/Eta-por   本仓库（fork 自 Oo0520/Eta-por）
```

remote 与分支对应：

| remote | 地址 | 权限 | 对应分支 |
|---|---|---|---|
| `origin` | `github.com/donglihu/Eta-por` | 读写 | `main` / `branch` / `source` / `pro` |
| `source` | `github.com/Mangi-11/Eta` | 只 fetch | `source` |
| `pro` | `github.com/Oo0520/Eta-por` | 只 fetch | `pro` |

同步前先加好 remote：

```bash
git remote add source https://github.com/Mangi-11/Eta.git
git remote add pro    https://github.com/Oo0520/Eta-por.git
```

## 三、数据怎么流

```
   source（上游官方择优）─┐
                          ├─ merge --ff-only ─►  main  ◄─ merge ─  branch（开发）
   pro（二创源择优）──────┘                        │                     ▲
                                                   │                     │
                                                   └── merge main ───────┘
                                                      （branch 跟上 main）
```

四条规则：

1. `main` 上不写代码，只接收合并，保证任何时刻都能出包。
2. 日常开发只在 `branch` 上，改完合 `main`。
3. 上游提交只允许出现在 `source` / `pro` 上，`main` 上永远只有自己的提交和快进带入的成果。
4. `source` / `pro` 从 `main` 拉起，合回走 fast-forward，不产生合并提交，避免分叉点变旧导致冲突放大。

反例（不要做）：直接在 `main` 上 `cherry-pick` 上游提交，或直接在 `main` 上改代码。前者让 `main` 与上游的分叉点不断变旧，下次同步冲突倍增；后者让 `main` 失去「随时可出包」的性质。

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

### 吸收上游官方（Mangi-11/Eta）

```bash
git fetch source
git switch source
git merge --ff-only main              # source 跟上 main 最新处
git cherry-pick -x <上游提交 SHA>      # 只挑需要的
git switch main
git merge --ff-only source
git push origin main source
git switch branch
git merge main                        # 开发分支跟上
```

### 吸收二创源（Oo0520/Eta-por）

```bash
git fetch pro
git switch pro
git merge --ff-only main
git cherry-pick -x <二创提交 SHA>
git switch main
git merge --ff-only pro
git push origin main pro
git switch branch
git merge main
```

### 冲突在哪解决

上游择优的冲突在 `source` / `pro` 上解决，不要留到 `main`。上游提交与自己的实现打架的，按「这个文件由谁主导」决定，主导文件可对 `main` 侧整体保留。

## 五、规矩

1. 打包只从 `main`，`branch` / `source` / `pro` 绝不出包。
2. `main` 上不写自己的代码，只做合并。
3. `source` / `pro` 上不写自己的代码，只放上游提交的原样副本。
4. 吸收上游一律用 `cherry-pick -x`，把来源 SHA 写进提交信息，便于回溯「这行是谁的」。
5. 每次同步完成打 tag：`sync-YYYYMMDD`，标签说明里写明对应上游 HEAD 的短 SHA 与来源分支。
6. 不对 `main` 使用 `--force`。确需移动 `source` / `pro` / `branch` 用 `--force-with-lease`。
7. 同步前先看差异量再动手：

   ```bash
   git log --oneline --no-merges main..source/main | head -30
   git diff main...source/main --stat | tail -5
   ```
