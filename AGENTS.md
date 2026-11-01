# AGENTS.md

> ⚠️ **本文件是给 AI agent 看的**（人类请看 [`docs/INDEX.md`](docs/INDEX.md)）。
> 两者指向同一套规矩；改动一边记得同步另一边。

## 这是什么

TeslaMate（开源采集）+ 自写 Go API + 自写 Android App 的**自托管车况系统**，
外加一套走飞书/微信的主动提醒。**一个车主、一台车**，不是多租户产品。

## 接手先读（按顺序）

1. [`docs/INDEX.md`](docs/INDEX.md) —— **目录地图 / 数据流 / 工作流 / 常用命令 / 八条红线**
2. [`docs/CHANGELOG.md`](docs/CHANGELOG.md) 顶部 3 条 —— 最近改了什么、为什么
3. `api/main.go` 的路由表 —— 服务端能干什么
4. `app-android/app/src/main/java/com/teslamate/app/Api.kt` —— App 能取什么
5. `docs/adr.md` —— 哪些事**已经决定过**，别再讨论一遍

## 三条不可破的规则

1. **判定只在服务端做。** App / MCP / 推送脚本都只读服务端结果，
   绝不在客户端各算一套阈值（否则三处漂移，用户看到互相矛盾的数字）。
2. **宁可说「不知道」，不可编。** 遥测没接上 ≠ 车没故障；查不到上限 ≠ 现在的上限。
   阈值型断言要**钉具体数值**，不能只钉「有没有超过 N」（v0.98.0 的教训：
   计时器被时区吃掉 8 小时，旧断言只问「> 30 分钟」⇒ 511 分钟也放行）。
3. **发版前必须先读未处理的用户反馈并逐条评估。**
   `bash scripts/release.sh` 的第一步是 `scripts/release-feedback-gate.sh` ——
   有「待评估」的反馈就**禁止发版**（服务读不到 ⇒ 同样禁止）。
   评估用 MCP 的 `teslamate_feedback_decision` 或 `POST /api/v1/feedback/{id}/status`
   （`skip` 必须写理由）。

## 用户反馈闭环（v0.100.0 起）

```
App「设置 → 帮助与反馈 → 用户反馈」  →  POST /api/v1/feedback（可带截图）
        ↓ 自动附带：来源页面 / App 版本 / 车辆
     tm_user_feedback 表（+ media/feedback/ 里的截图）
        ↓
   迭代开始：teslamate_feedback（读）→ 逐条 teslamate_feedback_decision（写评估）
        ↓
   发版闸门：没有「待评估」的反馈才放行
```

## ⚠️ 每轮迭代的**第一件事**：先分诊待评估的反馈（v0.105.0 起）

> **用户要求（2026-11-01）**：这个定时任务**每次执行**都要先检查一遍待处理的问题列表，
> 属于自己板块的**处理并反馈**，不属于的也要**写下理由**。

```bash
bash scripts/feedback-triage.sh    # 只读清单：待评估 / 要处理 / 已修好 / 不处理
```

- **待评估（`new`）的每一条都要有结论**：`doing`（要处理）/ `skip`（不处理，**必须写理由**）/ `done`（已修好）。
  写结论用 `POST /api/v1/feedback/{id}/status`（或 MCP 的 `teslamate_feedback_decision`）。
- **要处理（`doing`）的** = 上一轮承诺过要做的 ⇒ 本轮要么做掉，要么**改结论**（别让它一直挂着）。
- **截图要看**：`media/feedback/<id>.jpg`（清单脚本会给出路径）——
  「很难理解」「不需要这个图标」这类反馈**看图才知道指的是哪一块**。
- ⚠️ **反馈的结论是数据，不是每轮的口头判断** ⇒ 评估理由要写进 `decision_note`，
  下一轮才不会重新纠结同一件事。
- ⚠️ 属于**别的板块**的（例如「要新做一个分段控件」这种功能新增），
  **不要**草率标 `skip`（那等于把需求判死）⇒ 标 `doing` + 在理由里写清「属功能轮次，UI 打磨轮不做」。

## 常用命令（**必须用 Git Bash**）

```bash
cd /c/tm-ws
bash scripts/server-gates.sh     # Go 语法 / 密钥扫描 / 仓库卫生
bash scripts/client-gates.sh     # 客户端 20 道闸门（啰嗦棘轮等）
bash scripts/smoke.sh            # 冒烟（48 项）
python mcp/check_tool_fields.py  # MCP 字段映射自检
```

**构建 App 必须走 ASCII 路径**（Gradle 拒绝含非 ASCII 字符的项目路径）：

```powershell
gradle -p C:\tm-ws\app-android assembleDebug --console=plain --offline
```

**跑 JVM 单测**（同样走 ASCII 路径；⚠️ `GRADLE_USER_HOME` 要固定到 `C:\gradle\gradle-home`）：

```bash
cd /c/tm-ws && export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot' \
 && export GRADLE_USER_HOME='C:\gradle\gradle-home' \
 && /c/gradle/gradle-8.10.2/bin/gradle.bat -p /c/tm-ws/app-android testDebugUnitTest --console=plain --offline
```

⚠️ **以前记的「JVM 单测在本机起不来（worker 报 `ClassNotFoundException: GradleWorkerMain`）」是错的**
—— 根因是**路径**（走了 OneDrive 里的真实路径），走 ASCII 联接路径就正常
（2026-11-02 实测：212 个测试全绿）。⇒ App 侧改动**必须**能跑单测，
把「没跑成单测」写进 CHANGELOG 是不合格的。

⚠️ 改了 `.env` 之后 `docker compose restart` **不算数**（容器持有创建时的快照），
必须 `docker compose up -d --force-recreate api`。

## 改代码前先看

- `docs/INDEX.md` §6「红线」表
- `docs/adr.md`（架构决策，别推翻已决定的事）
- `scripts/client-gates.sh`（客户端的硬规矩，尤其是**啰嗦棘轮**：
  新增长说明块要么短、要么折进 ⓘ、要么登记进 `scripts/verbose-baseline.txt` 并在 CHANGELOG 写明理由）

## 交付前

```bash
bash scripts/server-gates.sh && bash scripts/client-gates.sh && bash scripts/smoke.sh
```

⚠️ 改完记得同步 `scripts/release.sh` 的版本号与 `.env` 的 `APP_VERSION_*`
（客户端闸门会校验 **APK 的 versionCode 与服务端通告一致**）。
