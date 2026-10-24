# 文档索引

> 按"你现在要做什么"选文档，不必通读。

## 我要了解这个项目

| 我想知道 | 看这里 |
|---|---|
| 这是什么、怎么跑起来、目录有哪些 | [../README.md](../README.md) |
| 整体怎么搭的、为什么这么搭 | [architecture.md](architecture.md) |
| 现在跑的是什么版本、每个数字什么意思 | [data-dictionary.md](data-dictionary.md) |

## 我要动手改

| 我要改 | 先看 | 再看 |
|---|---|---|
| 手机 App 界面 | [../../app-android/README.md](../../app-android/README.md) | 改完跑 `scripts/smoke.sh` |
| API / SQL / 字段口径 | [api-spec.md](api-spec.md) → [../../api/README.md](../../api/README.md) | 改完跑 `scripts/smoke.sh` |
| 这个字段到底怎么算的 | [data-dictionary.md](data-dictionary.md) §2 | — |
| 某个功能当初为什么这么做 | [adr.md](adr.md) | CHANGELOG 对应版本段 |
| 服务启停/备份/升级/发版/排查 | [operations.md](operations.md) | 或直接用 `scripts/` |

## 我是终端用户（只想用 App）

| 我的问题 | 看这里 |
|---|---|
| 怎么装、怎么配、字段什么意思 | [手机客户端使用说明.md](手机客户端使用说明.md) |
| 车况页面那些数字代表什么 | 同上 §5「几个容易看不懂的指标」 |

## 文档分工

| 文件 | 性质 | 更新时机 |
|---|---|---|
| `README.md` | 总纲：结构 + 索引 | 加服务/加目录时 |
| `architecture.md` | 架构与设计理由（**说"为什么"**） | 架构性变更时（较慢） |
| `data-dictionary.md` | **数据口径单一事实源** | 改任何计算公式时（必改） |
| `api-spec.md` | API 契约（字段级） | 加/删/改接口时 |
| `adr.md` | 决策索引（含被否决的方案与原因） | 推翻或新增架构决策时 |
| `operations.md` | 运维手册（**说"怎么做"**） | 加运维动作时 |
| `CHANGELOG.md` | 变更流水（做了什么/为什么/影响/回滚） | **每次改动都写** |
| `手机客户端使用说明.md` | 端用户手册 | 用户可见行为变化时 |
| `../../api/README.md` | API 代码导览与约束 | 改 API 代码结构时 |

## 三条规矩

1. **口径改动必须三方同步**：`data-dictionary.md` + `api-spec.md` + `CHANGELOG.md`。
2. **改完必跑** `bash scripts/smoke.sh`，14 项全过才算完。
3. **决策不埋在流水里**：重要的架构选择进 `adr.md`，一行索引 + 指向 CHANGELOG 详情。
