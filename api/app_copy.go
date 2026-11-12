package main

// ---------- 服务端「界面文案」的宽度口径（v0.144.0） ----------
//
// ## 为什么要有这个文件
//
// ⚠️ **啰嗦棘轮的「长度」判据只咬客户端 .kt** —— 服务端下发的中文文案**没人管长度**：
//
//	客户端闸门 5 扫的是 app-android 下 .kt 的字面量；
//	v0.90.0 只把「星号粗体漏到界面」那条补到了服务端 .go，**长度那条没有**。
//	⇒ ADR #41 把文案搬到服务端之后，啰嗦棘轮就**管不到它们了**（盲区）。
//
// ⚠️ **实证 = v0.142.0 自己造的那条**：车外温度的 caveat（原来在
//
//	api/range_estimate_http.go 里拼出来）视觉宽度 **78**
//	⇒ 由 App 的 StatusScreen.kt **常驻**印成一整行琥珀字
//	⇒ 用户看到的是一堵字墙，而 client-gates 34/0 全绿。
//
// ## 宽度口径（与客户端棘轮同一个数、同一套算法）
//
//	汉字 / 全角 = 2，半角（ASCII 数字、字母、标点）= 1，**空格不算**。
//	阈值 **44** = 22 个全角字（客户端 scripts/client-gates.sh 闸门 5 用的是同一个数）。
//
// ⚠️ **这个常量是唯一的真相**：客户端棘轮的 44 与它相等
//
//	（Hermes 闸门 26 跨文件比对）⇒ 谁想放宽服务端文案，都得同时改两个地方。
//
// ⚠️ **这里只放「常驻界面文案」**：App 用独立 Text(...) 直接印出来的那种。
//
//	折进 ⓘ 的长说明（电池衰减的 method、爬坡模型的口径…）走客户端的 ⓘ 预算（160），
//	**不在本轮范围内** ⇒ 已记进 CHANGELOG v0.144.0 段的「已知未收口」。
const appCopyWidthLimit = 44

// staleOutsideTempCaveat 车外温度读数**不是当前的**时，续航口径那句。
//
// ⚠️ **v0.144.0**：原来是 78 宽度的长句（还夹着「positions 里的读数停在停车那一刻」
//
//	这种只有开发者看得懂的实现细节）⇒ 现在只留三件事实：
//	现在读不到 / 没按气温修正 / 所以可能偏乐观。
//	⇒ 「上次读到多少、什么时候」**不丢**：在 outside_temp_last / outside_temp_at
//	  与飞书里（App 侧还没显示 ⇒ 记进已知未收口，下一个 App 版本补）。
const staleOutsideTempCaveat = "车外温度读不到 ⇒ 未按气温修正 · 可能偏乐观"

// tempCaveatLowUntrustedTmpl 低温（≤10°C）且样本不足时那句（%d = 该温度档的样本条数）。
//
// ⚠️ **v0.146.0**：这两条原先在 range_estimate.go 里拼 ⇒ 违反「服务端文案一律进 app_copy.go」
// （AGENTS.md 的通则 + ADR #41）⇒ 本轮搬进来，让「服务端文案的唯一定义处」名副其实。
//
// ⚠️ **客户端有一份逐字副本**（app-android/.../TempCaveatCopy.kt）—— 那是**本地兜底**：
// 服务端挂掉时 App 要自己把口径说出来，不能哑着。
// ⚠️ **v0.146.0 修掉的缺陷**：本地兜底原来在「车外温度读不到」时**返回 null**
// ⇒ 界面一句口径提示都没有 ⇒ 用户以为那个续航数是按气温修正过的 ⇒ **假安全**。
// 两份不许漂 ⇒ **客户端闸门 35 跨文件逐字比对**。
const tempCaveatLowUntrustedTmpl = "低温未修正：≤10°C 只有 %d 条样本，这个数不可信"

// tempCaveatNoLowCorrectionTmpl 低温区间（10~22°C）不做低温修正时那句（%d = 样本条数）。
const tempCaveatNoLowCorrectionTmpl = "未做低温修正（≤10°C 样本 %d 条）"

// copyVisualWidth 视觉宽度（与客户端 vwidth 同一口径：汉字/全角=2，半角=1，空格不计）。
func copyVisualWidth(s string) int {
	w := 0
	for _, c := range s {
		switch {
		case c == ' ' || c == '\t' || c == '\n' || c == '\r':
			// 空格不算
		case c >= '\u2e80' && c <= '\u9fff':
			w += 2
		case c >= '\uff00' && c <= '\uffef':
			w += 2
		default:
			w += 1
		}
	}
	return w
}
