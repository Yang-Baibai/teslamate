# 架构决策记录（ADR）索引

> 每条决策的完整背景在 `CHANGELOG.md` 对应版本段，本文只做**按主题的快速索引**，
> 回答"为什么当年不这么做 / 现在想换方案要先读什么"。

| # | 主题 | 决策 | 否决的替代方案 | 否决原因 | 详见 |
|---|---|---|---|---|---|
| 1 | 手机端数据获取 | 自研 Go API 容器中转 | App 直连 PostgreSQL | 密钥暴露面、口径无法统一、schema 耦合 | architecture.md §2.1 |
| 2 | 功能边界 | 只读，不做远程控车 | 接入 Tesla 命令 API | 需要账号级令牌放手机端，风险不可控 | architecture.md §2.2 |
| 3 | 坐标方案 | SQL 函数双轨输出 WGS-84 + GCJ-02 | 客户端纠偏 | 纠偏算法要维护两份；SQL 函数已被 Grafana 仪表盘共用 | architecture.md §2.3 |
| 4 | Grafana 汉化 | 整镜像替换（wjsall 中文版） | 挂载汉化 JSON | 镜像自带配套插件与 provisioning，升级一条命令 | CHANGELOG v0.2.2 之前 |
| 5 | 仪表盘 SQL 升级 | `SQL_COMPAT_REVISION` 机制，镜像与数据库双向校验 | 裸跑 SQL | 曾出"镜像更新了 SQL 没跑"的事故 | CHANGELOG 2026-10-11 |
| 6 | **地图渲染** | **高德静态图 API + 服务端中转** | (a) osmdroid + 高德瓦片 (b) 高德 Android SDK (c) OSM | (a) osmdroid 在 Compose/国产 ROM 下瓦片源被默认 Mapnik 覆盖，OSM 国内被墙（根因：logcat 见 Mapnik 404）(b) 需企业认证+签名绑定 (c) GFW | CHANGELOG v0.3.0 |
| 7 | API Key 安全 | Key 只存服务端 `.env`，App 经 `/app/staticmap` 中转 | Key 打进 APK | 反编译即泄漏；配额被盗刷 | CHANGELOG v0.3.0 |
| 8 | App 更新 | 自建 `/app/latest` + DownloadManager | 应用商店/公网托管 | 私人 App 无商店渠道；公网托管要服务器 | CHANGELOG v0.1.4 |
| 9 | 远程访问 | Tailscale 组网 | 路由器端口映射 / frp | 无公网 IPv4；端口映射暴露面大 | CHANGELOG 2026-10-12 |
| 10 | 字体缩放 | App 内封顶 fontScale 1.15 | 全布局响应式重写 | 小米官方文档证实大字号档会布局错乱；封顶是微信/抖音通行做法 | CHANGELOG v0.2.2 |
| 11 | 健康度分母 | 铭牌容量（NOMINAL_BATTERY_KWH 环境变量） | 历史实测最大值 / 满电续航反推 | 两者都随衰减漂移，健康度虚高且不可比（98% vs 85.2%） | CHANGELOG v0.5.0；data-dictionary.md §2 |
| 12 | 充电费用 | 界面移除 | 显示"未记录" | cost 全库 NULL，展示价值为零；配 tou_rates 后可恢复 | CHANGELOG v0.5.0 |
| 13 | 图表实现 | Compose Canvas 自绘 | Vico/MPAndroidChart | 4 种图够了；少一个依赖；完全掌控中文样式 | CHANGELOG v0.2.0 |
| 14 | 顶栏 | 普通 TopAppBar（64dp 固定） | Large/MediumTopAppBar | 折叠式在 density 2.5 屏上折叠区高达 270px+，用户感知为"异常空白" | CHANGELOG v0.4.0 |
| 15 | V/A 占位值 | 服务端归 null，客户端显示"—" | 原样展示 | 2V/0A 是特斯拉 API 握手占位，展示会动摇用户对 App 的信任 | CHANGELOG v0.1.3 |
| 16 | 构建目录 | `C:\tm-app\teslamate-app`（ASCII 路径） | 直接在工作区构建 | 工作区含中文用户名 + OneDrive 同步，Gradle 数万小文件会锁死 | README「两个目录」 |
| 17 | **项目定位** | **长期单用户自用（一台车 + 家人）** | (a) 多车支持抽象 (b) 对外分享/多租户 | 用户 2026-10-16 明确确认。API 已天然按 `car_id` 分层，将来要加车无需重构；**当前刻意不提前抽象**——单用户场景下的多租户鉴权、只读数据库角色、限流全是无人使用的复杂度 | 2026-10-16 决策 |
| 18 | 数据库/Grafana 口令 | **维持现状不轮换**（用户 2026-10-16 决定） | 换随机强口令 | 局域网自用可接受；轮换需同步改 `.env` + 库内 `ALTER` + 三个服务重启，收益不匹配风险。注意：**口令字面值已随 `operations.md` 进入 git 历史**，所以它不再构成秘密边界——真正的边界是"不把 8090/3000/4000 暴露到公网" | `operations.md` §5 |
| 19 | 数据库备份 | **每周日 03:00 计划任务** + 保留最近 8 份 | (a) 每月一次 (b) 不自动只手动 (c) 保留全部 | 2026-10-12 建基线后到 10-16 整整 4 天没备份过，期间 DB 从 189MB 涨到 243MB。周期取 7 天而非 30 天是因为这是**唯一有状态资产**；保留 8 份 ≈ 两个月滚动，避开 OneDrive 无限堆积。`db-baseline-*` 由 glob `db-[0-9]*.sql` 天然排除，永不轮转删除 | `operations.md` §9 |
| 20 | **车况提醒** | **走本机 Hermes 推送到微信/飞书**，App 不做本地通知 | (a) App 内 WorkManager 通知 (b) FCM 云推送 (c) 服务端直接调 IM API | (a) 手机可能连不上家里这台机器（外出/Tailscale 卡住），且国产 ROM 会杀后台任务；App 一旦没有通知权限就无法提醒「人在外地」的场景。(b) 为私有部署申请 Firebase 项目、塞 google-services.json 是纯粹的复杂度。(c) IM API 各家鉴权/限流各异，Hermes 已经把飞书/微信都封装好了。**服务端监听**还有一个好处：这台电脑 7×24 开着，是唯一可靠的执行环境 | `operations.md` §10 |
| 21 | 告警判定口径 | **只在 API 层实现一次**（`api/alerts.go`），App 与推送脚本都只读结果 | 两边各写一份阈值 | 各写一份必然漂移——会出现「App 说胎压正常但微信推了告警」。阈值本身也由 API 返回（`tpms_min_bar`/`tpms_max_bar`），连说明文字都不会两边不一致 | `data-dictionary.md` §3.5 |

## 决策变更规则

- 推翻某条决策前：读对应 CHANGELOG 段落了解当时踩的坑，评估坑是否已消失。
- 新增决策：在本表加一行 + CHANGELOG 详细段，两者互相引用。
