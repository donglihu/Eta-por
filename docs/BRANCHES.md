# 分支说明

> 2026-11-16 定，2026-11-16 修订（`up`→`source`、`port`→`pro`）。本仓库 `donglihu/Eta-por` 采用三分支制。
> 与 `docs/BRANCH_AND_RELEASE.md` 的关系：那份是上游 Oo0520 的规矩（主线 `test/beta-on-latest`），本仓库不适用，以本文为准。

## 一、三条分支

| 分支 | 角色 | 起点 | 推送时机 |
|---|---|---|---|
| `main` | 开发主线，唯一发布线 | 现有主线 | 随时 |
| `source` | 同步源头 A：`Mangi-11/Eta` 的择优暂存 | `main` | 仅同步时 |
| `pro` | 同步源头 B：`Oo0520/Eta-por` 的择优暂存 | `main` | 仅同步时 |

## 二、同步源头说明

本仓库由 `Oo0520/Eta-por` fork 而来，最终源头是 `Mangi-11/Eta`。两条上游性质不同，各自对应一条暂存分支：

| 分支 | 同步源头 | 地址 | 内容性质 |
|---|---|---|---|
| `source` | `Mangi-11/Eta`（上游官方） | https://github.com/Mangi-11/Eta | 官方功能与修复，无二创内容 |
| `pro` | `Oo0520/Eta-por`（二创源） | https://github.com/Oo0520/Eta-por | 二创功能，散落在 `feat/*`、`fix/*`、`test/*` 分支 |

三级仓库关系：

```
Mangi-11/Eta            上游官方（fork=False）
   └─ Oo0520/Eta-por       二创源（fork 自 Mangi-11/Eta）
        └─ donglihu/Eta-por   本仓库（fork 自 Oo0520/Eta-por，main 为开发主线）
```

remote 与分支一一对应：

| remote | 地址 | 权限 | 对应分支 |
|---|---|---|---|
| `origin` | `github.com/donglihu/Eta-por` | 读写 | `main` / `source` / `pro` |
| `source` | `github.com/Mangi-11/Eta` | 只 fetch | `source` |
| `pro` | `github.com/Oo0520/Eta-por` | 只 fetch | `pro` |

同步前先加好 remote：

```bash
git remote add source https://github.com/Mangi-11/Eta.git
git remote add pro    https://github.com/Oo0520/Eta-por.git
```

## 三、为什么这样分

`source` 和 `pro` 都从 `main` 拉起，挑完的提交合回 `main` 走 **fast-forward**：不产生合并提交，不会因为历史分叉反复冲突。

规则只有一条：**上游提交只允许出现在 `source` / `pro` 上，`main` 上永远只有自己的提交和快进带入的成果。**

反例（不要做）：直接在 `main` 上 `cherry-pick` 上游提交。这会让 `main` 与上游的分叉点不断变旧，下次同步时 Git 把整段区间都当成新内容，冲突量成倍放大。

## 四、日常操作

### 自己开发

```bash
git switch main
# 改代码 → 提交 → 推送
git push origin main
```

### 吸收上游官方（Mangi-11/Eta）

```bash
git fetch source
git switch source
git merge --ff-only main              # source 跟上 main 最新处
git cherry-pick -x <上游提交 SHA>      # 只挑需要的
git switch main
git merge --ff-only source            # 快进回主线
git push origin main source
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
```

### 冲突在哪解决

在 `source` / `pro` 上解决，不要留到 `main`。上游两个提交之间自己打架的，按上游最新意图处理；上游提交与自己的实现打架的，按「这个文件由谁主导」决定，主导文件可对 `main` 侧整体保留。

## 五、规矩

1. 打包只从 `main`，`source` / `pro` 绝不出包。
2. `source` / `pro` 上不写自己的代码，只放上游提交的原样副本。
3. 吸收上游一律用 `cherry-pick -x`，把来源 SHA 写进提交信息，便于回溯「这行是谁的」。
4. 每次同步完成打 tag：`sync-YYYYMMDD`，标签说明里写明对应上游 HEAD 的短 SHA 与来源分支。
5. 不对 `main` 使用 `--force`。确需移动 `source` / `pro` 用 `--force-with-lease`。
6. 同步前先看差异量再动手：

   ```bash
   git log --oneline --no-merges main..source/main | head -30
   git diff main...source/main --stat | tail -5
   ```
