package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// --------------------------------------------------------------------------
// 用户反馈（v0.100.0）
//
// ## 为什么要有它
//
// 迭代闭环里最贵的一环是「**用户发现问题 → 说清楚问题在哪 → 开发者复现 → 修**」。
// 前两环全靠**记忆 + 口头转述**：用户得先记住「哪个页面、什么现象」，
// 再在聊天里复述一遍；而开发者只有读到那句话才知道该看哪里。
//
// ⇒ 反馈**通道长在产品里**：
// 用户在 App 里记一条（自动带上「哪个页面 / 哪个版本 / 哪台车」，可附**截图**），
// 迭代时再逐条取出来修 —— **不用再复述，也不用再靠记忆**。
//
// ## 数据存在哪
//
// 存在**我们自己的表**（`tm_user_feedback`），不动 TeslaMate 的 schema
// （理由同 `tm_charge_limits`：TeslaMate 升级可能重写它的表结构）。
// 截图存在**独立目录**（容器内 `/media`，宿主上是仓库的 `media/`）。
//
// ## 状态：四个，不是两个
//
//	new   还没评估
//	doing 评估过 = 要处理（下一轮做）
//	skip  评估过 = 不处理（**必须写理由**）
//	done  已修好
//
// ⚠️ 为什么要有 doing / skip：
// 「最新的反馈」如果只有「未处理 / 已处理」两态，
// 开发者每轮都要**重新判断一遍**同一批老问题 ——
// 而「这条不是缺陷，是既有限制」这种结论**该被记下来**，不该每轮重想。
// ⇒ 评估结论（要处理 / 不处理 + 理由）是**数据**，不是每轮的口头判断。
//
// ## 五个端点
//
//	GET  /api/v1/feedback               → 读反馈列表（新的在前）
//	POST /api/v1/feedback               → 提交一条
//	POST /api/v1/feedback/{id}/status   → 改状态 / 写评估结论
//	POST /api/v1/feedback/{id}/image    → 传一张截图
//	GET  /api/v1/feedback/{id}/image    → 看截图
// --------------------------------------------------------------------------

// 反馈的四种状态。
const (
	feedbackStatusNew   = "new"   // 还没评估
	feedbackStatusDoing = "doing" // 评估过：要处理
	feedbackStatusSkip  = "skip"  // 评估过：不处理（必须写理由）
	feedbackStatusDone  = "done"  // 已修好
)

// feedbackMediaDir 图片目录（容器内 /media，宿主上是仓库的 media/）。
func feedbackMediaDir() string {
	d := strings.TrimSpace(getenv("FEEDBACK_MEDIA_DIR", "/media"))
	if d == "" {
		d = "/media"
	}
	return d
}

// sqlEnsureUserFeedback 建表 / 补列。
//
// ⚠️ **分多条执行**：pgx 的 Exec 走扩展协议，**一条里不能塞多个语句**。
// （建表语句与 ALTER 分开 ⇒ 老库升级时也能补上新列。）
var feedbackSchema = []string{
	`CREATE TABLE IF NOT EXISTS tm_user_feedback (
    id            BIGSERIAL   PRIMARY KEY,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    message       TEXT        NOT NULL,
    -- ⚠️ 以下三列是**上下文**（问题出在哪个页面 / 哪个 App 版本 / 哪台车），
    --   不是内容本身 ⇒ 允许为空，也**允许被截断**（见 normalizeFeedbackContext）。
    screen        TEXT        NOT NULL DEFAULT '',
    app_version   TEXT        NOT NULL DEFAULT '',
    car_id        SMALLINT,
    car_name      TEXT        NOT NULL DEFAULT '',
    source        TEXT        NOT NULL DEFAULT 'app',
    status        TEXT        NOT NULL DEFAULT 'new',
    handled_at    TIMESTAMPTZ
)`,
	// v0.101.0 新增：评估结论 + 截图
	`ALTER TABLE tm_user_feedback ADD COLUMN IF NOT EXISTS decision_note TEXT NOT NULL DEFAULT ''`,
	`ALTER TABLE tm_user_feedback ADD COLUMN IF NOT EXISTS image_path TEXT NOT NULL DEFAULT ''`,
}

// sqlInsertUserFeedback 写一条反馈并回读它。
//
// ⚠️ RETURNING 的列**必须与 scanFeedback 的扫描顺序逐位一致** ——
// 少一列不会编译报错，只会在运行时炸（pgx: number of field descriptions
// must equal number of destinations）。
const sqlInsertUserFeedback = `
INSERT INTO tm_user_feedback (message, screen, app_version, car_id, car_name, source)
VALUES ($1, $2, $3, $4, $5, $6)
RETURNING id, created_at, message, screen, app_version, car_id, car_name, status,
          decision_note, image_path, handled_at`

// sqlListUserFeedback 读反馈列表（新的在前）。
//
// ⚠️ `$1 = ”` 是「不筛状态」—— 传空串时列出全部（新的仍然在前）。
const sqlListUserFeedback = `
SELECT id, created_at, message, screen, app_version, car_id, car_name, status,
       decision_note, image_path, handled_at
  FROM tm_user_feedback
 WHERE ($1 = '' OR status = $1)
 ORDER BY created_at DESC, id DESC
 LIMIT $2`

// sqlSetUserFeedbackStatus 改状态（+ 评估结论）。
const sqlSetUserFeedbackStatus = `
UPDATE tm_user_feedback
   SET status = $2,
       decision_note = CASE WHEN $2 = 'new' THEN '' ELSE $3 END,
       handled_at = CASE WHEN $2 = 'done' THEN now() ELSE NULL END
 WHERE id = $1
RETURNING id, created_at, message, screen, app_version, car_id, car_name, status,
          decision_note, image_path, handled_at`

// sqlSetUserFeedbackImage 记下截图路径。
const sqlSetUserFeedbackImage = `
UPDATE tm_user_feedback SET image_path = $2
 WHERE id = $1
RETURNING id, created_at, message, screen, app_version, car_id, car_name, status,
          decision_note, image_path, handled_at`

// --------------------------------------------------------------------------
// 模型
// --------------------------------------------------------------------------

// UserFeedback 一条用户反馈。
//
// ⚠️ 字段名与 App / MCP 一一对应 ⇒ 改一处就要改三处（这是刻意的：
// 键名写错不会报错、只会静默变 null —— 本仓库的老教训）。
type UserFeedback struct {
	ID         int64     `json:"id"`
	CreatedAt  time.Time `json:"created_at"`
	Message    string    `json:"message"`
	Screen     string    `json:"screen"`
	AppVersion string    `json:"app_version"`
	// ⚠️ 用**指针**：`null` = 提交时没有选车（不是「车号是 0」）。
	CarID   *int   `json:"car_id"`
	CarName string `json:"car_name"`
	Status  string `json:"status"`
	// DecisionNote 评估结论（skip 时必填）。
	DecisionNote string `json:"decision_note"`
	// ImagePath 截图的**相对路径**（相对 media 目录；空 = 没图）。
	ImagePath string `json:"image_path"`
	// ImageURL 截图的**取图地址**（空 = 没图）。
	ImageURL  string     `json:"image_url"`
	HandledAt *time.Time `json:"handled_at"`
}

// UserFeedbackList 反馈列表。
type UserFeedbackList struct {
	Items []UserFeedback `json:"items"`
	Total int            `json:"total"`
}

// userFeedbackCreateReq 提交一条反馈的请求体。
//
// ⚠️ `car_id` 用 **指针**：`null` = 「没有车」，
// 而 `0` 是「车号是 0」—— 用 int 分不清这两者（数据库里 car_id 可空）。
type userFeedbackCreateReq struct {
	Message    string `json:"message"`
	Screen     string `json:"screen"`
	AppVersion string `json:"app_version"`
	CarID      *int   `json:"car_id"`
	CarName    string `json:"car_name"`
	Source     string `json:"source"`
}

// userFeedbackStatusReq 改状态 / 写评估结论的请求体。
type userFeedbackStatusReq struct {
	Status string `json:"status"`
	Note   string `json:"note"`
}

// --------------------------------------------------------------------------
// 校验（纯函数，可单测）
// --------------------------------------------------------------------------

const (
	// feedbackMessageMax 正文字数上限（**按 rune 数**，中文一个字算一个）。
	feedbackMessageMax = 2000
	// feedbackContextMax 上下文字段长度上限（页面名 / 版本号 / 车名）。
	feedbackContextMax = 64
	// feedbackImageMax 截图大小上限（字节）。
	feedbackImageMax = 5 << 20 // 5 MB

	// feedbackDecisionMax 评估结论长度上限（**按 rune 数**）。
	//
	// ⚠️ **v0.132.0（真机复核轮次发现）**：原来这里没有独立上限，
	// 结论被硬截到 `feedbackContextMax*4` = **256 字** ——
	// 那是拿「上下文字段」（64 字）的上限**凑**的，结论根本写不下那么多字。
	// ⇒ 一次真实的真机复核回写，就把三条结论**静默截成了半句话**：
	// 「…真机复核通过（2026-11-04 · 真机 <真机序列号> · App 」
	// 而调用方拿到的是 **200 OK** ⇒ 写结论的人**以为写进去了**。
	//
	// ⚠️ 为什么必须放开：结论的读者是**下一轮的 agent**，
	// 它会把半句话当成完整结论继续往下推 ⇒ 越写越歪。
	feedbackDecisionMax = 4000

	// feedbackDecisionTruncMark 超长截断时**必须**留的痕迹。
	//
	// ⚠️ 与本文件里**正文超长直接 400 拒绝**是同一条原则：
	// 「截掉一半等于让用户以为自己说清楚了，其实没有」——
	// 结论同理，只是这里**不能拒绝**（结论是内部数据，拒了会让反馈卡住），
	// 所以改成**截断但留痕**：读的人一眼知道这句话没写全。
	feedbackDecisionTruncMark = "…（结论过长，已截断）"
)

// normalizeFeedbackMessage 校验并裁剪正文。
//
// ⚠️ 空内容**不是**「提交空反馈」—— 那是误触 ⇒ 直接 400。
// ⚠️ 超长**直接拒绝**而不是悄悄截断：反馈是用户特意写的东西，
// 截掉一半等于让用户以为自己说清楚了，其实没有。
func normalizeFeedbackMessage(raw string) (string, error) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", errors.New("反馈内容不能为空")
	}
	if n := len([]rune(s)); n > feedbackMessageMax {
		return "", fmt.Errorf("反馈内容太长了（最多 %d 字，现在 %d 字）", feedbackMessageMax, n)
	}
	return s, nil
}

// normalizeFeedbackContext 裁剪上下文字段。
//
// ⚠️ 上下文**截断**而不是报错：它只是「问题出在哪个页面」这类线索，
// 长得离谱只可能是客户端塞了脏数据，不该把用户的反馈整条挡下来。
func normalizeFeedbackContext(raw string) string {
	s := strings.TrimSpace(raw)
	r := []rune(s)
	if len(r) <= feedbackContextMax {
		return s
	}
	return string(r[:feedbackContextMax])
}

// validFeedbackStatus 校验状态取值。
//
// ⚠️ 只认四个值：多一个状态就要在 App / MCP / 界面三处各认一次，
// 而三处一定会有一个不认 ⇒ 反馈就"消失"了。
func validFeedbackStatus(s string) bool {
	switch s {
	case feedbackStatusNew, feedbackStatusDoing, feedbackStatusSkip, feedbackStatusDone:
		return true
	}
	return false
}

// normalizeFeedbackDecision 校验「状态 + 评估结论」这一对。
//
// ⚠️ **skip 必须写理由** —— 「评估后判断不处理」而没有理由，
// 等于把判断藏起来 ⇒ 下一轮还会有人重新纠结同一件事。
// ⚠️ `new`（重新打开）会**清空**理由：它表示「回到未评估」，理由不该留着。
func normalizeFeedbackDecision(status, note string) (string, string, error) {
	s := strings.TrimSpace(status)
	if !validFeedbackStatus(s) {
		return "", "", errors.New("status 只能是 new / doing / skip / done")
	}
	n := strings.TrimSpace(note)
	if s == feedbackStatusSkip && n == "" {
		return "", "", errors.New("判断为「不处理」时必须写理由")
	}
	if s == feedbackStatusNew {
		n = ""
	}
	if r := []rune(n); len(r) > feedbackDecisionMax {
		keep := feedbackDecisionMax - len([]rune(feedbackDecisionTruncMark))
		if keep < 0 {
			keep = 0
		}
		n = string(r[:keep]) + feedbackDecisionTruncMark
	}
	return s, n, nil
}

// detectImageType 从**魔数**判断图片类型。
//
// ⚠️ **不看客户端给的文件名与 Content-Type** ——
// 那两个都是客户端说了算的（可以随手改成 .exe / .html）。
// ⇒ 只认我们自己认识的几种格式，且**只按内容**决定存成什么扩展名。
func detectImageType(data []byte) (string, bool) {
	switch {
	case len(data) >= 3 && bytes.Equal(data[:3], []byte{0xFF, 0xD8, 0xFF}):
		return "jpg", true
	case len(data) >= 8 && bytes.Equal(data[:8], []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}):
		return "png", true
	case len(data) >= 12 && bytes.Equal(data[:4], []byte("RIFF")) && bytes.Equal(data[8:12], []byte("WEBP")):
		return "webp", true
	case len(data) >= 6 && (bytes.Equal(data[:6], []byte("GIF87a")) || bytes.Equal(data[:6], []byte("GIF89a"))):
		return "gif", true
	}
	return "", false
}

// --------------------------------------------------------------------------
// 处理器
// --------------------------------------------------------------------------

// scanFeedback 按固定顺序扫一行反馈（**列表 / 单条 / 改状态**共用）。
func scanFeedback(row interface {
	Scan(dest ...any) error
}) (UserFeedback, error) {
	var (
		f         UserFeedback
		carID     *int
		handledAt *time.Time
	)
	if err := row.Scan(&f.ID, &f.CreatedAt, &f.Message, &f.Screen,
		&f.AppVersion, &carID, &f.CarName, &f.Status,
		&f.DecisionNote, &f.ImagePath, &handledAt); err != nil {
		return f, err
	}
	f.CarID = carID
	f.HandledAt = handledAt
	f.ImageURL = feedbackImageURL(f.ID, f.ImagePath)
	return f, nil
}

// feedbackImageURL 有图时给出取图地址。
func feedbackImageURL(id int64, imagePath string) string {
	if strings.TrimSpace(imagePath) == "" {
		return ""
	}
	return fmt.Sprintf("/api/v1/feedback/%d/image", id)
}

// handleUserFeedbackList 读反馈列表。
//
// GET /api/v1/feedback?limit=50&status=new
func handleUserFeedbackList(w http.ResponseWriter, r *http.Request) {
	if db == nil {
		writeErr(w, http.StatusServiceUnavailable, "服务端没有连上数据库")
		return
	}
	status := strings.TrimSpace(r.URL.Query().Get("status"))
	if status != "" && !validFeedbackStatus(status) {
		writeErr(w, http.StatusBadRequest, "status 只能是 new / doing / skip / done")
		return
	}
	limit := clampLimit(r.URL.Query().Get("limit"), 50)
	if limit > 200 {
		limit = 200
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	rows, err := db.Query(ctx, sqlListUserFeedback, status, limit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()

	out := UserFeedbackList{Items: []UserFeedback{}}
	for rows.Next() {
		f, err := scanFeedback(rows)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		out.Items = append(out.Items, f)
	}
	if err := rows.Err(); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	out.Total = len(out.Items)
	writeJSON(w, out)
}

// handleUserFeedbackCreate 提交一条反馈。
//
// POST /api/v1/feedback
func handleUserFeedbackCreate(w http.ResponseWriter, r *http.Request) {
	if db == nil {
		writeErr(w, http.StatusServiceUnavailable, "服务端没有连上数据库")
		return
	}
	var req userFeedbackCreateReq
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := dec.Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		writeErr(w, http.StatusBadRequest, "请求体不是 JSON")
		return
	}

	msg, err := normalizeFeedbackMessage(req.Message)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}

	var carID *int
	if req.CarID != nil && *req.CarID > 0 {
		id := *req.CarID
		carID = &id
	}
	source := normalizeFeedbackContext(req.Source)
	if source == "" {
		source = "app"
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	f, err := scanFeedback(db.QueryRow(ctx, sqlInsertUserFeedback,
		msg,
		normalizeFeedbackContext(req.Screen),
		normalizeFeedbackContext(req.AppVersion),
		carID,
		normalizeFeedbackContext(req.CarName),
		source,
	))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, f)
}

// handleUserFeedbackStatus 改一条反馈的状态 / 写评估结论。
//
// POST /api/v1/feedback/{id}/status   {"status":"doing","note":"…"}
func handleUserFeedbackStatus(w http.ResponseWriter, r *http.Request) {
	if db == nil {
		writeErr(w, http.StatusServiceUnavailable, "服务端没有连上数据库")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, http.StatusBadRequest, "反馈 id 无效")
		return
	}
	var req userFeedbackStatusReq
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := dec.Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		writeErr(w, http.StatusBadRequest, "请求体不是 JSON")
		return
	}
	status, note, err := normalizeFeedbackDecision(req.Status, req.Note)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	f, err := scanFeedback(db.QueryRow(ctx, sqlSetUserFeedbackStatus, id, status, note))
	if err != nil {
		// 找不到就是找不到 —— 不要编一条「已处理」的假记录出来。
		writeErr(w, http.StatusNotFound, "找不到这条反馈")
		return
	}
	writeJSON(w, f)
}

// handleUserFeedbackImageUpload 给一条反馈传一张截图。
//
// POST /api/v1/feedback/{id}/image   （multipart/form-data，字段名 image）
func handleUserFeedbackImageUpload(w http.ResponseWriter, r *http.Request) {
	if db == nil {
		writeErr(w, http.StatusServiceUnavailable, "服务端没有连上数据库")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, http.StatusBadRequest, "反馈 id 无效")
		return
	}
	// ⚠️ **只接受 multipart**：这条路由不解析 JSON。
	if err := r.ParseMultipartForm(feedbackImageMax); err != nil {
		writeErr(w, http.StatusBadRequest, "请用 multipart/form-data 上传图片")
		return
	}
	file, header, err := r.FormFile("image")
	if err != nil {
		writeErr(w, http.StatusBadRequest, "没有收到图片（字段名应为 image）")
		return
	}
	defer file.Close()
	if header != nil && header.Size > feedbackImageMax {
		writeErr(w, http.StatusBadRequest, "图片太大了（最多 5 MB）")
		return
	}

	// ⚠️ 先读进内存再判类型：魔数要看**文件头**，而 multipart 的分块可能
	// 跨在头部中间 ⇒ 一次读齐最省事（上限 5 MB，内存完全够）。
	data, err := io.ReadAll(io.LimitReader(file, feedbackImageMax+1))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取图片失败")
		return
	}
	if len(data) > feedbackImageMax {
		writeErr(w, http.StatusBadRequest, "图片太大了（最多 5 MB）")
		return
	}
	ext, ok := detectImageType(data)
	if !ok {
		writeErr(w, http.StatusBadRequest, "这不像是图片（只认 JPG / PNG / WebP / GIF）")
		return
	}

	// ⚠️ **文件名我们自己生成**（绝不用客户端给的那个）：
	// 客户端的文件名可以带 ../ 、可以带 .exe ⇒ 直接用就是路径穿越漏洞。
	rel := filepath.Join("feedback", fmt.Sprintf("%d.%s", id, ext))
	dir := filepath.Join(feedbackMediaDir(), "feedback")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeErr(w, http.StatusInternalServerError, "图片目录建不出来")
		return
	}
	if err := os.WriteFile(filepath.Join(dir, fmt.Sprintf("%d.%s", id, ext)), data, 0o644); err != nil {
		writeErr(w, http.StatusInternalServerError, "图片保存失败")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	f, err := scanFeedback(db.QueryRow(ctx, sqlSetUserFeedbackImage, id, rel))
	if err != nil {
		writeErr(w, http.StatusNotFound, "找不到这条反馈")
		return
	}
	writeJSON(w, f)
}

// handleUserFeedbackImageGet 看一条反馈的截图。
//
// GET /api/v1/feedback/{id}/image
func handleUserFeedbackImageGet(w http.ResponseWriter, r *http.Request) {
	if db == nil {
		writeErr(w, http.StatusServiceUnavailable, "服务端没有连上数据库")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, http.StatusBadRequest, "反馈 id 无效")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	rows, err := db.Query(ctx, sqlListUserFeedback, "", 200)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()

	var (
		imagePath string
		found     bool
	)
	for rows.Next() {
		f, err := scanFeedback(rows)
		if err != nil {
			continue
		}
		if f.ID == id {
			imagePath = f.ImagePath
			found = true
			break
		}
	}
	if !found {
		writeErr(w, http.StatusNotFound, "找不到这条反馈")
		return
	}
	if strings.TrimSpace(imagePath) == "" {
		writeErr(w, http.StatusNotFound, "这条反馈没有图片")
		return
	}

	// ⚠️ 路径只认**我们自己存的相对路径**，且取 Base ⇒
	// 即使库里被塞进 "../../etc/passwd" 也出不了 media 目录。
	name := filepath.Base(imagePath)
	path := filepath.Join(feedbackMediaDir(), "feedback", name)
	data, err := os.ReadFile(path)
	if err != nil {
		writeErr(w, http.StatusNotFound, "图片文件不在了")
		return
	}
	ext := strings.TrimPrefix(filepath.Ext(name), ".")
	switch ext {
	case "jpg":
		w.Header().Set("Content-Type", "image/jpeg")
	case "png":
		w.Header().Set("Content-Type", "image/png")
	case "webp":
		w.Header().Set("Content-Type", "image/webp")
	case "gif":
		w.Header().Set("Content-Type", "image/gif")
	default:
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	// ⚠️ 图片是**用户自己的截图** ⇒ 别让中间层缓存串味。
	w.Header().Set("Cache-Control", "private, no-store")
	w.Write(data)
}

// ensureUserFeedbackTable 建表（启动时调用一次）。
//
// ⚠️ 建表失败**不致命** —— 那样只是「反馈功能不可用」，
// 不该让整个 API 起不来（车况、充电历史都跟它无关）。
func ensureUserFeedbackTable(ctx context.Context) {
	if db == nil {
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	for i, stmt := range feedbackSchema {
		if _, err := db.Exec(ctx, stmt); err != nil {
			log.Printf("⚠️  建/补 tm_user_feedback 失败（第 %d 条）：%v（反馈功能将不可用）", i+1, err)
			return
		}
	}
	log.Printf("✅ tm_user_feedback 就绪（用户反馈会落库）")
}
