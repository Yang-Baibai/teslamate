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

// ⚠️ **v0.197.0**：**车外温度那三条口径**搬去了 **app_copy_temp.go** ——
//   客户端有一份**逐字副本**（TempCaveatCopy.kt）⇒ 客户端闸门 35 比对的是
//   「有副本的那一份」⇒ 服务端那一份必须**只装**它们 ⇒ 拆文件
//   （结构上精确，不依赖按名字筛选）。
// ⚠️ 其余常驻界面文案仍在本文件；宽度上限 appCopyWidthLimit 也在本文件
//   （Hermes 闸门 26 的判据②**两个文件一起扫**）。

// ── ⚠️ **v0.197.0**：App **常驻**印的服务端句子（骨架搬进这里）──────────────────
//
// ⚠️ **为什么这批骨架要进 app_copy.go**：
//
//	v0.196.0 实测（模拟器 1440×3200@600，车况页电池卡）：
//	「充电习惯：平均从 36% 开始充 · 12 次从 10% 以下开始充 · 上次充电是今天」
//	视觉宽度 **60** ⇒ App 用独立 Text(...) 常驻印出来 ⇒ **折成两行**；
//	同一张卡上的衰减结论（**55** 宽）折行后还留下「过 0)」的**孤字尾行**。
//
//	⇒ 根因：**服务端拼出来的句子没人管宽度** ——
//	  Hermes 闸门 26 的判据②只查 app_copy.go 里**声明的常量**，
//	  而 fmt.Sprintf 拼出来的句子（充电习惯 / 累计循环 / 衰减结论）
//	  **一个判据都没有**（ADR #83 的盲区）。
//
//	⇒ 修法（三件事）：
//	  ① **结论与依据分层** —— headline 只说结论（短）；
//	    依据（95% 区间 / 测到的值）挪进 note
//	    （ⓘ 与体检报告都印它 ⇒ **事实不丢**）；
//	  ② **短句另给一个字段**（usage_headline）⇒ App 常驻印短句、全文折进明细；
//	  ③ **骨架搬进 app_copy.go** ⇒ 闸门 26 的判据②**自动盖住**这些句子
//	    （它扫的就是这个文件里的中文文案 ⇒ 不依赖拼法）。
//
// ⚠️ **宽度按「最坏情况」留余量**：数字位数会变
//
//	（场次 88 → 9999、百分比 36 → 100、速率 -0.052 → -99.999）⇒
//	骨架留了余量，并由 api/app_copy_test.go 用**极端值**渲染一遍钉死。
const appCopyUsageHeadlineTmpl = "充电习惯：平均从 %d%% 开始充"

const appCopyDecayDetectableTmpl = "连续衰减：每 1000 公里 %s 度"

const appCopyDecayWeak = "连续衰减：与 0 区分不开"

const appCopyDecayNotDetectable = "连续衰减：检不出趋势"

const appCopyDecayNoSamples = "样本不足以判定衰减趋势"

const appCopyDecayCiTmpl = "95%% 区间 %s ~ %s"

const appCopyDecayCiCrossZeroTmpl = "95%% 区间 %s ~ %s，跨过 0"

const appCopyDecayWeakEvidenceTmpl = "测到 %s 度/1000 公里 · 95%% 区间 %s ~ %s"

// ── ⚠️ **v0.198.0**：当前充电状态（官方 charge_state）的文案零件 ───────────────
//
// ⚠️ 枚举**只认官方列出来的**（与 api/charge_outcome.go 同一份：
//
//	Charging / Complete / Stopped / NoPower / Disconnected / Unknown）
//	⇒ 不认识的**一律当 unknown**，**不猜**（反幻觉，ADR #46）。
//
// ⚠️ 「枪还插着」不是猜的：官方枚举里 Stopped / NoPower 说的就是
//
//	「枪还插着但没在充」（车端亲口说的）⇒ 写进文案里，用户才知道
//	**不是自己拔了枪**。
const appCopyChargeStateCharging = "正在充电"

const appCopyChargeStateComplete = "充电完成"

const appCopyChargeStateStopped = "已停止充电（枪还插着）"

const appCopyChargeStateNoPower = "充电桩没供电（枪还插着）"

const appCopyChargeStateDisconnected = "未插枪"

const appCopyChargeStateUnknown = "充电状态不明"

// 数字零件（拼成**明细行** ⇒ 整行 ≤ noteLineWidthLimit = 96）。
const appCopyChargeSocTmpl = "当前 %d%%"

// ⚠️ 车睡着时（读数是「断线前的最后已知值」）⇒ **不许说「当前」** ——
//
//	那是**事实错误**（用户会以为这是此刻的电量）。
const appCopyChargeSocLastTmpl = "上次 %d%%"

const appCopyChargeLimitTmpl = "上限 %d%%"

const appCopyChargePowerTmpl = "%s kW"

const appCopyChargeRemainTmpl = "剩 %d 分钟"

// ── ⚠️ **v0.198.0**：**读不到**时的那一句 ──────────────────────────────────
//
// ⚠️ 为什么要有这三句（而不是「整张卡不出现」）：
//
//	车离线 / 休眠时，车端**确实**没有任何实时数据（实测：Tesla 连 wake_up
//	都发不出去，vehicle_data 直接 408）⇒ 那是**真实状态**，不是故障。
//	⇒ 但如果界面**什么都不显示**，用户会以为「这个功能坏了」或者
//	「App 没装好」⇒ 不如明说「现在读不到，因为车在离线」。
//
//	⇒ 措辞在**服务端**（ADR #41）⇒ App 与飞书同一句。
const appCopyChargeUnavailableAsleep = "车在休眠，读不到充电状态"

const appCopyChargeUnavailableOffline = "车离线，读不到充电状态"

const appCopyChargeUnavailableNoData = "还没拿到实时数据"

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

// noteLineWidthLimit ⓘ 里**一行**的宽度上限（ⓘ 是用户主动点开的详情，比常驻文案宽松）。
//
// ⚠️ **v0.147.0**：这是**服务端下发的 ⓘ 文案**（电池衰减的 caveat / method）的行宽上限。
//
//	实测（2026-11-12）：caveat 是服务端拼好下发的**整段**，视觉宽度 **750**
//	（12 个话题用「；」挤成一段）⇒ 展开就是一堵字墙；
//	而客户端的 ⓘ 预算（160）**只扫客户端调用点的字面量** ⇒ 服务端这一半**一个判据都没有**
//	⇒ 又一次「闸门跑绿 ≠ 它在检查东西」。
//	⇒ 现在服务端每个分句、客户端每一行都 ≤ 96；两端同一个数（客户端闸门 36 跨文件比对）。
const noteLineWidthLimit = 96
