package main

import (
	"context"
	"log"
	"strconv"
	"sync"
	"time"
)

// ---------- 「当时的上限」记录（v0.79.0） ----------
//
// ## 为什么需要它
//
// 「这次充电充满了吗？」这个问题，**只有一个**能精确回答它的依据：
// **结束电量 vs 那场会话当时的充电上限**。
//
// ⚠️ 为什么不能用「现在的上限」（`charge_limit_soc`）代替？
// 因为上限**会变**，而且**变化方向不确定**：
//
//	结束电量 92%、现在上限 95%  ⇒ 当时上限可能是 90%（当时到了）或 100%（当时没到）
//	结束电量 92%、现在上限 90%  ⇒ 当时上限可能是 90%（当时到了）或 80%（当时没到）
//
// ⇒ 两种情况都推不出来。**必须把当时的上限记下来。**
//
// v0.79.0 之前的历史会话没有这个记录 ⇒ 只能给「近似」结论或直说不知道。
// 从 v0.79.0 起，充电期间每轮轮询都会记一次 ⇒ 判定从此可靠。
//
// ## 数据从哪来
//
// TeslaMate 一直在把 `charge_limit_soc` 发到 MQTT
// （`teslamate/cars/1/charge_limit_soc`），
// 而我们自己的 Fleet API 轮询也能拿到同一份数据（`charge_state.charge_limit_soc`）。
// 这里走后者 —— 已经在轮询了，不额外占 API 额度。

// ensureChargeLimitTable 建我们自己的表。
//
// ⚠️ 这是**本项目**的表，不动 TeslaMate 的 schema
//
//	（TeslaMate 升级可能重写它的表结构，我们的表要独立）。
const sqlEnsureChargeLimitTable = `
CREATE TABLE IF NOT EXISTS tm_charge_limits (
    charge_id   integer     PRIMARY KEY,
    car_id      smallint    NOT NULL,
    limit_soc   smallint    NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now()
)`

// sqlUpsertChargeLimit 记一次「某场会话观测到的上限」。
//
// ⚠️ 用 UPSERT 覆盖：**用户在充电中途改上限时，后一次观测才是结束时的上限**。
const sqlUpsertChargeLimit = `
INSERT INTO tm_charge_limits (charge_id, car_id, limit_soc, observed_at)
VALUES ($1, $2, $3, now())
ON CONFLICT (charge_id) DO UPDATE
   SET limit_soc = EXCLUDED.limit_soc,
       observed_at = EXCLUDED.observed_at`

// sqlActiveChargeID 找当前**进行中**的充电会话。
const sqlActiveChargeID = `
SELECT id FROM charging_processes
 WHERE car_id = $1 AND end_date IS NULL
 ORDER BY start_date DESC LIMIT 1`

// sqlCarIDByVID 用 Fleet API 的数字车 id 找本项目用的 car_id。
//
// ⚠️ 两者不是一回事：Fleet 的 id 是 16 位大整数（如 1126241815185179），
// 而 `cars.id` 是 TeslaMate 的 smallint（1）。
const sqlCarIDByVID = `SELECT id FROM cars WHERE vid = $1`

// sqlSessionChargeLimit 取某场会话**当时**记录的充电上限。
const sqlSessionChargeLimit = `
SELECT limit_soc FROM tm_charge_limits WHERE charge_id = $1`

// sessionChargeLimit 取某场会话当时记录的充电上限（%）。
//
// ⚠️ **独立查询，不进主查询的 CTE** ——
// 主查询里引用一张可能不存在的表，会让**整个 status 端点 500**。
// 独立查询则只影响这一项：查不到就当「没有记录」。
//
// ⚠️ 查不到时**绝不用现在的上限顶替** ——
// 现在的上限推不出当时的（上限会变且方向不确定），顶替就是造假。
func sessionChargeLimit(ctx context.Context, chargeID int) *int {
	if db == nil {
		return nil
	}
	c, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	var v int
	if err := db.QueryRow(c, sqlSessionChargeLimit, chargeID).Scan(&v); err != nil {
		return nil
	}
	return &v
}

// ensureChargeLimitTable 建表（启动时调用一次）。
//
// ⚠️ 建表失败**不致命** —— 那样只是「判定退回近似」，不该让整个 API 起不来。
func ensureChargeLimitTable(ctx context.Context) {
	if db == nil {
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if _, err := db.Exec(ctx, sqlEnsureChargeLimitTable); err != nil {
		log.Printf("⚠️  建 tm_charge_limits 失败：%v（充电结局将退回近似判定）", err)
		return
	}
	log.Printf("✅ tm_charge_limits 就绪（充电期间会记录当时的充电上限）")
}

// chargeLimitRecorder 负责把「观测到的充电上限」写进表。
//
// ⚠️ 单飞 + 限流：一轮轮询只记一次，且两次记录至少隔 30 秒 ——
// 2 分钟的轮询本来就不密，但万一以后调密了，别把 DB 写爆。
type chargeLimitRecorder struct {
	mu       sync.Mutex
	lastAt   time.Time
	lastSoc  int
	lastSeen bool
}

var limitRecorder = &chargeLimitRecorder{}

// recordChargeLimitIfCharging 在「正在充电」时记录当前观测到的上限。
//
// ⚠️ 只在**有进行中的会话**时记录 —— 上限只有绑到某场会话上才有意义。
// ⚠️ 不阻塞轮询：写库失败只记日志，绝不让车况采集因为它失败。
func recordChargeLimitIfCharging(ctx context.Context, fleetVID string, limitSoc *int) {
	if db == nil || limitSoc == nil || *limitSoc <= 0 {
		return
	}
	limitRecorder.mu.Lock()
	if limitRecorder.lastSeen && time.Since(limitRecorder.lastAt) < 30*time.Second {
		limitRecorder.mu.Unlock()
		return
	}
	limitRecorder.lastAt = time.Now()
	limitRecorder.lastSoc = *limitSoc
	limitRecorder.lastSeen = true
	limitRecorder.mu.Unlock()

	go func() {
		c, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()

		vid, err := strconv.ParseInt(fleetVID, 10, 64)
		if err != nil {
			return
		}
		var carID int
		if err := db.QueryRow(c, sqlCarIDByVID, vid).Scan(&carID); err != nil {
			return // 找不到车 ⇒ 不记（不是错误）
		}
		var chargeID int
		if err := db.QueryRow(c, sqlActiveChargeID, carID).Scan(&chargeID); err != nil {
			return // 没有进行中的会话 ⇒ 这轮不用记（不是错误）
		}
		if _, err := db.Exec(c, sqlUpsertChargeLimit, chargeID, carID, *limitSoc); err != nil {
			log.Printf("⚠️  记当时充电上限失败（会话 %d，上限 %d%%）：%v", chargeID, *limitSoc, err)
		}
	}()
}
