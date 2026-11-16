# 分支说明

> 2026-11-16 定。本仓库 `donglihu/Eta-por` 采用三分支制。
> 与 `docs/BRANCH_AND_RELEASE.md` 的关系：那份是上游 Oo0520 的规矩（主线 `test/beta-on-latest`），本仓库不适用，以本文为准。

## 一、三条分支

| 分支 | 角色 | 起点 | 推送时机 |
|---|---|---|---|
| `main` | 开发主线，唯一发布线 | 现有 `58a1fae` | 随时 |
| `up` | 上游 `Mangi-11/Eta` 的择优暂存 | `main` | 仅同步时 |
| `port` | 二创源 `Oo0520/Eta-por` 的择优暂存 | `main` | 仅同步时 |

三级仓库关系：

```
Mangi-11/Eta        上游 A，官方功能
   └─ Oo0520/Eta-por   二创 B
        └─ donglihu/Eta-por   本仓库 C（main 为开发主线）
```

## 二、为什么这样分

`up` 和 `port` 都从 `main` 拉起，挑完的提交合回 `main` 走 **fast-forward**：不产生合并提交，不会因为历史分叉反复冲突。

规则只有一条：**上游提交只允许出现在 `up` / `port` 上，`main` 上永远只有自己的提交和快进带入的成果。**

反例（不要做）：直接在 `main` 上 `cherry-pick` 上游提交。这会让 `main` 与上游的分叉点不断变旧，下次同步时 Git 把整段区间都当成新内容，冲突量成倍放大。

## 三、日常操作

### 自己开发

```bash
git switch main
# 改代码 → 提交 → 推送
git push origin main
```

### 吸收上游 A（Mangi-11/Eta）

```bash
git fetch up-mangi
git switch up
git merge --ff-only main              # up 跟上 main 最新处
git cherry-pick -x <A 的提交 SHA>     # 只挑需要的
git switch main
git merge --ff-only up                # 快进回主线
git push origin main up
```

### 吸收二创 B（Oo0520/Eta-por）

```bash
git fetch up-port
git switch port
git merge --ff-only main
git cherry-pick -x <B 的提交 SHA>
git switch main
git merge --ff-only port
git push origin main port
```

### 冲突在哪解决

在 `up` / `port` 上解决，不要留到 `main`。上游两个提交之间自己打架的，按上游最新意图处理；上游提交与自己的实现打架的，按「这个文件由谁主导」决定，主导文件可对 `main` 侧整体保留。

## 四、remote 对照

| remote | 地址 | 权限 |
|---|---|---|
| `origin` | `github.com/donglihu/Eta-por` | 读写 |
| `up-mangi` | `github.com/Mangi-11/Eta` | 只 fetch |
| `up-port` | `github.com/Oo0520/Eta-por` | 只 fetch |

## 五、规矩

1. 打包只从 `main`，`up` / `port` 绝不出包。
2. `up` / `port` 上不写自己的代码，只放上游提交的原样副本。
3. 吸收上游一律用 `cherry-pick -x`，把来源 SHA 写进提交信息，便于回溯「这行是谁的」。
4. 每次同步完成打 tag：`sync-YYYYMMDD`，标签说明里写明上游 HEAD 的短 SHA。
5. 不对 `main` 使用 `--force`。确需移动 `up` / `port` 用 `--force-with-lease`。
6. 同步前先看差异量再动手：
   ```bash
   git log --oneline --no-merges main..up-mangi/main | head -30
   git diff main...up-mangi/main --stat | tail -5
   ```

## 六、旧分支处置

| 分支 | 现状 | 建议 |
|---|---|---|
| `ownmain` | 与 `main` 同一提交 `58a1fae`，无独立内容 | 可删 |
| `Mangi-11Eta` | 与 `main` 同一提交 `58a1fae`，命名不规范 | 可删 |

删除前确认无本地分支跟踪它们：

```bash
git branch -r --contains 58a1faec
```
