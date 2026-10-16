# TeslaMate 手机 API 服务

Go 1.23 编写的只读 REST API，是 Android 客户端（`../app-android/`）的唯一后端。
容器名 `teslamate-api-1`，端口 8090，接入 Tailscale 后手机在家/在外通用。

## 文件职责（4 个 .go 文件，共约 1000 行）

| 文件 | 职责 | 改动频率 |
|---|---|---|
| `main.go` | 配置读取、pgx 连接池、路由注册、Bearer 鉴权中间件、panic 恢复 | 低（加端点才动） |
| `handlers.go` | 全部 HTTP 处理器 + 数据清洗（sanitizeCharge）+ 静态图代理 + LRU 缓存 | 高（加字段就动） |
| `queries.go` | **全部 SQL 常量**。想改查询口径只看这一个文件 | 高 |
| `models.go` | 全部响应结构体（json tag 即对 App 的契约） | 高 |
| `Dockerfile` | 多阶段构建（goproxy.cn 拉依赖 → alpine 运行，产物 ~15MB） | 极低 |

## 端点总览

完整字段说明见 [`../docs/api-spec.md`](../docs/api-spec.md)。速查：

```
GET /healthz                       健康检查（免鉴权）
GET /api/v1/cars                   车辆列表
GET /api/v1/cars/{id}/status       实时车况（含四轮胎压）
GET /api/v1/cars/{id}/drives       行程列表
GET /api/v1/drives/{id}/track      行程轨迹（≤1500 点降采样）
GET /api/v1/cars/{id}/charges      充电列表（含损耗率/桩型）
GET /api/v1/charges/{id}/curve     充电曲线
GET /api/v1/cars/{id}/stats        周/月/年聚合
GET /api/v1/cars/{id}/battery      电池健康（铭牌容量口径）
GET /api/v1/app/latest             App 自更新元数据
GET /api/v1/app/staticmap          高德静态图中转（LRU 缓存 30min）
GET /apk/…                         APK 下载（免鉴权，DownloadManager 无法带 header）
```

## 关键设计约束（改代码前必读）

1. **只读**：本服务对数据库只有 SELECT。任何写操作都属于越界。
2. **时间**：PostgreSQL 全部 `timestamp without time zone`，存 UTC。SQL 里聚合用
   `(ts AT TIME ZONE 'UTC') AT TIME ZONE 'Asia/Shanghai'`。出参 ISO-8601 带 Z。
3. **坐标双轨**：出参同时给 `lat/lng`（WGS-84 原始）与 `map_lat/map_lng`（GCJ-02 纠偏，
   由 TeslaMate 中文仪表盘装的 SQL 函数 `wgs84_to_gcj02_*` 计算）。地图类消费 map_*，
   两套不可混用（~500m 偏移）。
4. **高德的坑**：静态图参数错时高德返回 **HTTP 200 + JSON body**。handler 用 `buf[0]=='{'`
   嗅探并转 502。别把这种响应体当 PNG 缓存。
5. ** correlated subquery 陷阱**：PG 对含参数的关联子查询会报 `42P18 could not determine
   data type`。聚合一律用 CTE + JOIN（见 `sqlCharges` 的 `ch_agg`）。
6. **占位值清洗**：非超充直流桩的电压/电流是特斯拉 API 的握手占位（2V/0A），
   `sanitizeCharge` 把 V<10、A<1 归 nil，App 显示"—"。**绝不向客户端返回疑似错误的真值。**

## 构建与部署

```bash
docker compose build api && docker compose up -d api   # 重建
docker compose logs -f api                              # 每请求都有耗时日志
```

## 冒烟测试（改完必跑）

```bash
TOKEN=$(grep '^API_TOKEN=' ../.env | cut -d= -f2)
B=http://localhost:8090

curl -s $B/healthz                                                # 期望 {"status":"ok"}
curl -s $B/api/v1/cars | grep -o error                            # 期望 {"error":"token 无效"}
curl -s -H "Authorization: Bearer $TOKEN" $B/api/v1/cars/1/status | python -m json.tool | head
curl -s -o /tmp/m.png -w "%{http_code} %{content_type} %{size_download}\n" \
  -H "Authorization: Bearer $TOKEN" \
  "$B/api/v1/app/staticmap?points=113.86%2C22.58%3B113.8674%2C22.572459"
# 期望 200 image/png 数万字节；若是 502，看 body 里高德的 info/infocode
```

更多端点示例与字段契约：[`../docs/api-spec.md`](../docs/api-spec.md)。
