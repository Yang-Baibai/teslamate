package com.teslamate.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslamate.app.Charge
import com.teslamate.app.Drive
import com.teslamate.app.Haptics
import com.teslamate.app.firstSegment
import com.teslamate.app.fmt
import com.teslamate.app.fmtDate
import com.teslamate.app.fmtKwh100
import com.teslamate.app.medianOf
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **路线余量表** —— 出发前 5 分钟最需要的那张表。
 *
 * ## 为什么必须有它
 *
 * 长途自驾党的原话：
 * > 「出发前 5 分钟，打开 App 就能看到一条这样的表：
 * >   起点 100% … 120 km 62% ← 石化大道西（你在这充过 3 次）典型 55 kW …
 * >   430 km 15% ⚠ 到此只剩 15%，低于你设的 20% 保底 … 结论：全程需充 1 次」
 *
 * 现有 App 里唯一跟「长途」有关的东西是**过去的**行程 ——
 * 有功率曲线、有电量曲线，但那都是**已经跑完的那一趟**。
 * 出发前要问的是「**下一次**跑这条线会怎样」，而 App 答不了。
 *
 * ## 数据从哪来（全部是**真实历史**，不编）
 *
 * - **线路模板**：从历史行程里找「走过 ≥200 km 的长途」，按起止围栏名分组。
 * - **余量剖面**：用那条线路**历史上真实的充电记录**拼 ——
 *   每段行程的起点/终点电量、电量掉到多少、在哪个站充的、充到多少。
 *   于是剖面里那些跳升就是**真实的充电**，不是模型算出来的。
 * - **沿途站点**：按里程表对齐到路线公里数，配上该站的历史典型功率与耗时。
 *
 * ## 为什么不做成「输入目的地 → 规划」
 *
 * 那需要路径规划 API（高德）+ 沿途 POI，而且**没有历史数据的线路就规划不出来**。
 * 而实测：真正决策过的站，90% 是**自己充过的**。
 * 所以先用**已有历史**做这张表 —— 数据全在、可复算、零新依赖。
 *
 * @see buildRouteTemplates 找出历史长途模板
 * @see buildRoutePlan 用模板 + 历史充电算出余量表
 */

/**
 * ⚠️ **v0.34.0（第 21 轮一线车主复审 P1-3）：保底电量集中成一个常量。**
 *
 * 原来「20% 保底」散落在**四处**硬编码（结论句、剖面参考线、
 * 「低于 20% 保底」提示、「留 20% 保底：到不了」提示）——
 * 改一处漏三处就会自相矛盾。
 *
 * ⚠️ **本轮先集中，不做成设置项。**
 * 复审要求「保底电量可配置」—— 那需要一层设置持久化 + 一处全局状态，
 * 属于独立一轮的改动；而当前**四处硬编码的数是同一个**（都是 20），
 * 所以集中之后**行为完全不变**，只是以后改一处就够。
 */
const val RESERVE_SOC = 20

/**
 * ⚠️ **v0.34.0（第 21 轮口径审计 P0-2 + 独立复算）：爬升系数的口径守卫。**
 *
 * 只有服务端明确下发 `basis == "calib_net"`（标定法净值，与能耗底档同源）
 * 时才给爬升修正。
 *
 * 旧的那套系数是**车轮功率毛耗电**口径 —— 混用会三重重复计量
 * （回充扣两次、该档典型爬升算两次、既上又下时给净系数），
 * 独立复算实测**高估 +12%**、造成 **4 次结论翻转**。
 */
const val CLIMB_BASIS_CALIB_NET = "calib_net"

/**
 * ⚠️ **v0.34.0：小于这么长的段不给爬升修正（公里）。**
 *
 * 独立复算实测 `drives.ascent` 在**短途**严重失真
 * （以「只在里程表前进的点对」求正高差之和为参考，虚高 **98.7%**）。
 * 几公里的段上坡度噪声与真实爬升完全不成比例 ——
 * 那里**不给**修正比给一个噪声值好。
 */
const val CLIMB_MIN_GAP_KM = 20.0

/** 一条历史长途线路的模板。 */
data class RouteTemplate(
    /** 分组键（起点键 + 终点键） */
    val key: String,
    val fromLabel: String,
    val toLabel: String,
    /** 该线路的典型里程（km，中位数）—— 只用于文案 */
    val distanceKm: Double,
    /** 出发时的电量（%） */
    val startSoc: Int?,
    /** 到达时的电量（%） */
    val endSoc: Int?,
    /**
     * ⚠️ **v0.26.0：这里不再是「走过几次」，而是「这一趟被充电切成了几段」。**
     *
     * 原来这个字段叫 `occurrences`（同一条线路跑过几次），
     * 改成 journey 之后它装的是**段数** ——
     * 如果文案还写「走过 N 次」，就会把「一次旅程的 11 段」说成「走过 11 次」。
     * 所以字段改名，文案也一起改（见 [conclusion]）。
     */
    val legCount: Int,
    /**
     * ⚠️ **v0.30.0（长途自驾党 P1-2）：全部段数**（含充电站内挪车微行程）。
     *
     * [legCount] 是 `meaningfulLegCount`（**≥2 公里**的段）——
     * 62 公里那条 journey 实测 131 段，其中 60 段不到 2 公里。
     *
     * 原来 `RouteJourney.summary()` 显示 **全部**段数（191），
     * 而 `conclusion()` 显示 **≥2 公里**的段数（131）——
     * 同一个 journey 在两个地方显示不同段数，用户会以为有一个是错的。
     * 现在两个都给出，并说清差别。
     */
    val totalLegCount: Int = legCount,
    /** 沿途充电站数（不含到达后的那次） */
    val stopCount: Int,
    /** 最近一次走的时刻 */
    val lastDrivenAt: Instant?,
    /** 最近一次的起点里程表读数（km）—— 用来把充电站按里程表对齐到路线公里数 */
    val startOdoKm: Double?,
    // ⚠️ v0.14.0（口径审计 P0-4）：下面三个是**最近一次**那趟的真实值。
    //
    // 原来剖面是「中位数起点 + 某一次的充电跳升 + 中位数终点」的三明治 ——
    // 三者不同源，却以「按这条线路上真实发生过的充电推算」呈现。
    // 现在剖面**只用最近那一趟**的真实值，端点与充电点同源。
    /** 最近一次的真实里程（km） */
    val lastDistanceKm: Double? = null,
    /**
     * ⚠️ **v0.30.0（长途自驾党 P0-1）：这段线路的**真实均速**（km/h）。**
     *
     * 原来规划链路的计划车速**硬编码成 90 km/h** ——
     * 「到下一站剩 34%」是按一个跟这趟毫无关系的速度算的。
     * 实测：6227 公里那条 **64 km/h**，616 公里那条 **42 km/h**。
     */
    val avgSpeedKph: Double? = null,
    /**
     * ⚠️ **v0.31.0：这一趟是不是「回到了同一个地方」。**
     *
     * 实测：6227 公里那条的起点与终点是**同一个小区**
     * （「永丰社区」与「新湖路」相距 **300 米**，被地理编码拆成两个名字），
     * 原来显示成「永丰社区 → 新湖路」的**单程**线路 —— 读起来像「从 A 开到 B」，
     * 实际是**往返**。
     */
    val isRoundTrip: Boolean = false,
    /** 最近一次的出发电量（%） */
    val lastStartSoc: Int? = null,
    /** 最近一次的到达电量（%） */
    val lastEndSoc: Int? = null,
    /**
     * ⚠️ v0.24.0：**最近那一趟的 drive id** ——
     * 用来拉它的**真实逐公里 SOC 剖面**（`/drives/{id}/soc_profile`）。
     */
    val lastDriveId: Int? = null,
    /**
     * ⚠️ **v0.26.0：整段 journey 的全部 drive id。**
     *
     * 用来向服务端要**整条路线**的真实逐公里剖面
     * （`GET /cars/{id}/route_profile?drive_ids=…`），
     * 而不是只拿最后一段 —— 后者会让剖面只覆盖旅程的最后 1/12。
     */
    val legIds: List<Int> = emptyList(),
    /** 这一段 journey 的每一趟（按里程顺序） */
    val legs: List<JourneyLeg> = emptyList(),
    /**
     * ⚠️ **v0.26.0：沿途充电站 —— 已经按行程边界归属好了。**
     *
     * 原来是在 [buildRoutePlan] 里用**里程窗口**去捞充电记录，
     * 结果捞到的是**上一趟的收尾充电**（落在本趟的起点里程上）。
     * 现在归属在 [buildJourneys] 里做：充电落在**两段之间的缺口**里才算沿途站。
     */
    val stops: List<JourneyStop> = emptyList(),
)

/** 线路上的一个充电站（按路线公里数对齐）。 */
data class RouteStop(
    val name: String,
    /** 在这条线路上的第几公里 */
    val kmAlongRoute: Double,
    /** 到站时的电量（%） */
    val socBefore: Int?,
    /** 充完后的电量（%） */
    val socAfter: Int?,
    /** 该站历史典型功率（kW） */
    val typicalPowerKw: Double?,
    /** 该站历史「充到 80%」典型耗时（分钟） */
    val minutesTo80: Int?,
    /**
     * ⚠️ v0.14.0（长途自驾党 P0-2）：从**本次到站电量**充到 [socAfter] 的典型耗时（分钟）。
     *
     * 出发前真正要回答的是「我到站 16%，充到 60% 要等多久」，
     * 而不是写死的「充到 80% 要多久」—— 后者跟这次决策毫无关系。
     */
    val minutesToTarget: Int? = null,
    /** 该站历史充电次数 */
    val sessions: Int,
    /**
     * ⚠️ **v0.25.0（长途充电规划专家 P1-2）：到下一个充电站还有多少公里。**
     *
     * 原来只给「在第几公里」，不给「到下一站多远」——
     * 而后者才是出发前真正要回答的那句：「我充到 80%，**够不够开到下一个**？」
     *
     * 数据来自相邻充电点的里程表差（`start_odometer_km`）——
     * 实测本车真实节奏：**中位 128 公里，最远一段 351 公里**。
     */
    val gapToNextKm: Double? = null,
    /**
     * ⚠️ **v0.25.0：充到 [socAfter] 之后，到下一站还能剩多少电（%）。**
     *
     * 算法：`到下一站需要的度数 = 站间距离 × 本车实测 Wh/km`，
     * 再换算成电量的百分点。
     * 与 20% 保底对齐 —— 低于 20% 就该提示「这一段不够，得再充一次」。
     */
    val socAtNextStop: Int? = null,
    /**
     * ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-1）：下一站的**真实**到站电量（上次实测）。**
     *
     * 复审的原话：
     * > 「App 手里有真值却不用。`/route_profile` 早就给出这条线逐 5 公里的真实 SOC；
     * > 它把真值画进曲线，却拿一个模型估算摆在旁边，还只给估算。」
     *
     * 一个**没有真值在旁边**的估算，等于没有 —— 用户不知道该信哪个。
     * 现在把「上次实际到站剩 Y%」与估算并排给出。
     */
    val nextStopRealSoc: Int? = null,
    /**
     * ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-3）：这一跳要消耗多少个电量点。**
     *
     * 这是「处方」的原料：`gapKm × 实测 Wh/km ÷ 可用容量`。
     * 原来这个数只以「到下一站剩 X%」的形式出现过（**差值**），
     * 用户没法据此回答「那我该充到多少」。
     */
    val gapNeedPct: Int? = null,
    /**
     * ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-3）：留 [RESERVE_SOC]% 保底，本站至少要充到多少。**
     *
     * `= gapNeedPct + RESERVE_SOC`。
     *
     * ⚠️ **> 100 就表示「这一跳无解」** ——
     * 复审实测：修文县 → G210那 351 公里要 **88 个点**，
     * 而 App 当时写的是「这段必须再充一次」。
     * 用户照做（充到 100%）**还是到不了**（只剩 −2%）——
     * **这是一个会让人白等 20 分钟、然后仍然趴窝的错误建议。**
     *
     * 现在 >100 时改口成「这一跳充满也到不了，得在前面加一站」。
     */
    val requiredSocHere: Int? = null,
    /**
     * ⚠️ **v0.36.0（第 21 轮一线车主复审 C8）：这一跳的耗电估计**误差几个点**。**
     *
     * 复审原话：「这个 App 要么**给区间**，要么把上次实际值并排放。
     * 否则它比官方更不可信 —— 因为它看起来更精确。」
     *
     * 口径：能耗模型**自己**的残差 90 分位（`ConsumptionModel.residualP90WhPerKm`）
     * 换算成这一段的电量百分点 —— 不是拍脑袋写的数。
     */
    val gapErrorPoints: Int? = null,
    /** 下一站的名字（用于「到 G210 车河镇」这样的说法）。 */
    val nextStopName: String? = null,
    /**
     * ⚠️ **v0.38.0（第 23 轮车主复审 P0-2）：到**这个站**时的真实电量。**
     *
     * ## 为什么不能只用 [socBefore]
     *
     * `socBefore` 是**插枪电量**（`charging_processes.start_battery_level`），
     * 而「到站电量」是**车开到这儿时**的电量（那一趟行程的 `end_soc`）。
     *
     * 两者平时只差 1 个点，但**合并充电会话**时会差很多：
     * 一次插枪被 TeslaMate 切成两个 process 时，
     * `socBefore` 取的是**后一场**的插枪电量 —— 那已经是「第一次充完之后」的电量。
     * 实测 36 个站里 **12 站**的详情行「到站 X%」是错的，最大差 **57 个点**。
     *
     * ⚠️ 这就是「同一个站的两行自相矛盾」的来源：
     * 决策行用「到站电量」（对），详情行用「插枪电量」（错）。
     *
     * 现在**统一到「到站电量」**（复审独立复核：对 `positions.battery_level`
     * @ `drives.end_position_id` 逐站对账 **36/36 = 0**）。
     */
    val arrivalSoc: Int? = null,
    /**
     * ⚠️ **v0.36.0（第 21 轮一线车主复审 C4）：到**终点**还有多少公里。**
     *
     * 最后一个站之后往往还有 **146 公里**，而终点不是充电站 ——
     * 那一段以前**一个字都不显示**。
     */
    val distanceToEndKm: Double? = null,
    /** ⚠️ v0.36.0：按模型估算，到终点时剩多少电（%）。 */
    val estSocAtEnd: Int? = null,
    /**
     * ⚠️ **v0.31.0：从上一个站到这一站，这一段爬了多高（米）。**
     *
     * 官方 Tesla App **从不给**这个数 —— 它只有总里程和平均电耗。
     *
     * 实测这条川藏滇线里，站间爬升从 **0 米到 4,186 米**（差 4,186 倍）——
     * 用「平均爬升」会同时错报平路段和最陡段，所以必须**逐段**给。
     */
    val gapAscentM: Int? = null,
    /** ⚠️ v0.33.0：这一段的下降（米） */
    val gapDescentM: Int? = null,
    /**
     * ⚠️ **v0.33.0：这一段的爬升能耗修正（Wh/km，可为负）。**
     *
     * 加性模型：`c_up × 上坡坡度(m/km) − c_regen × 下坡坡度(m/km)`。
     *
     * ⚠️ **v0.34.0（第 21 轮视觉评审 P2-3）：这里原来写「+83.6 Wh/km（+49%）」，与本文件
     * 另一处的「+88.8 Wh/km」矛盾。**按上线公式实测是 **+88.8 Wh/km（+50%）**，
     * 统一成后者。
     *
     * 实测那条川藏滇线里 206 公里那段爬了 4,186 米 → 修正 **+88.8 Wh/km（+50%）**，
     * 折合 **−28 个电量百分点** —— 没有它，界面会说「看着够」而实际到不了。
     */
    val gapClimbWhPerKm: Double? = null,
    /**
     * ⚠️ **v0.32.0（第 20 轮规划复审 P1-3）：这一段用的是哪一档 Wh/km。**
     *
     * 原来只显示「按 X km/h 档」—— 措辞不准（档是 60–90，X 是选档速度）。
     * 现在直接给**档名**（「90–110」），并说明是不是**借用**邻档。
     */
    val gapBucketLabel: String? = null,
    /** 借用邻档（不是本档实测） */
    val gapSpeedBorrowed: Boolean = false,
    /**
     * ⚠️ **v0.30.0（长途自驾党 P0-1）：算 [socAtNextStop] 用到的车速（km/h）。**
     *
     * 原来**硬编码 90 km/h** —— 与这趟毫无关系。
     * 现在用**这段线路历史的真实均速**
     * （6227 公里那条 **64 km/h**、616 公里那条 **42 km/h**）。
     *
     * ⚠️ 这个数**只决定用哪一档 Wh/km**：
     * 90–110 档 183.0 Wh/km vs 110–130 档 197.4 Wh/km —— 差 **7.9%**，
     * 在 128 公里的站距上就是 **2.8 个百分点**的到达电量差。
     */
    val assumedSpeedKph: Double? = null,
    /** 实际用的 Wh/km（界面要把它和车速一起标出来 —— 不交代前提就只是个数字） */
    val assumedWhPerKm: Double? = null,
    /**
     * ⚠️ **v0.34.0（第 21 轮视觉评审 P0-2）：含爬升修正的**总**能耗。**
     *
     * 原来 `assumedWhPerKm` 直接装的是「基数 + 爬升修正」的**总数**，
     * 而它前面的标签写的是**基数档名**（如「0–60 档」）——
     * 于是同一个档位在 App 里出现了三个值：文档 17.2、实测基数 17.8、界面 26.7。
     * 读者若知道档位表，会认为 App 的档位值**错了 50%**。
     *
     * 现在：[assumedWhPerKm] 只放**档位基数**（与档位表同源），
     * [assumedWhPerKmTotal] 放**总数**，界面上两者分开标（「含爬升修正后 X」）。
     */
    val assumedWhPerKmTotal: Double? = null,
    /**
     * ⚠️ **v0.34.0（第 21 轮视觉评审 P1-3）：爬升修正在这一段**多耗多少度 / 多少百分点。**
     *
     * 只给 Wh/km 的话，读者没法换算成「会让我少多少电」——
     * 而那才是「该不该提前充」的依据。
     */
    val gapClimbKwh: Double? = null,
    /** 同上，换算成电量百分点（负数 = 更费电）。 */
    val gapClimbPct: Double? = null,
) {
    /**
     * ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-2）：这一跳**没有解**吗。**
     *
     * 充满也只有 100 个点，而这一跳要 `need + 保底` 个点 ——
     * `> 100` 就意味着**在本站怎么充都到不了**。
     */
    val gapImpossible: Boolean get() = (requiredSocHere ?: 0) > 100
}

/** 余量剖面上的一个点。 */
data class RouteSocPoint(
    /** 路线上的公里数 */
    val km: Double,
    /** 电量（%） */
    val soc: Int,
    /** true = 这是充电造成的跳升 */
    val isCharge: Boolean = false,
)

/**
 * ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-4）：站与站之间的**一跳**。**
 *
 * 出发前 5 分钟真正要读的是「**下一段**」而不是「整条 6227 公里」。
 * 这个结构把那一跳需要的全部信息装在一起：
 * 从哪到哪、多远、上次实际到站剩多少、模型估多少、要充到多少才够。
 */
data class RouteHop(
    /** 起点站名 */
    val fromName: String,
    /** 终点站名 */
    val toName: String,
    /** 这一跳的公里数 */
    val km: Double,
    /** **上次实测**到站时的电量（%）—— 真值 */
    val realSoc: Int?,
    /** 模型估算的到站电量（%）—— 可能是负数（表示到不了） */
    val estSoc: Int?,
    /** 这一跳要消耗多少个电量点 */
    val needPct: Int?,
    /** 留 20% 保底，本站至少要充到多少（> 100 = **这一跳无解**） */
    val requiredSoc: Int?,
)

/** 整条线路的余量表。 */
data class RoutePlan(
    val template: RouteTemplate,
    /** 余量剖面（按公里数升序） */
    val profile: List<RouteSocPoint>,
    /** 沿途充电站（按公里数升序） */
    val stops: List<RouteStop>,
    /**
     * ⚠️ v0.14.0（口径审计 P0-4）：**终点处**的充电站。
     *
     * 它不是「沿途」的 —— 它在终点之后。混进沿途列表会让人以为
     * 「到了城东路得先充一次才能到」，而实际上那是到达之后的动作。
     */
    val endpointStops: List<RouteStop> = emptyList(),
    /**
     * ⚠️ **v0.31.0：整条线路的毛爬升 / 毛下降（米）。**
     *
     * 官方 App 给不了的数 —— 它只有总里程和平均电耗，
     * 不会告诉你「你翻了多少山」。
     *
     * 实测那条 6227 公里的川藏滇线：毛爬升 **51,791 米**（≈ 5.9 座珠峰）、
     * 毛下降 **48,513 米**。
     *
     * ⚠️ **v0.34.0（第 21 轮视觉评审 P2-4）：这里原来写「毛下降 62,554 米、净爬升 −750 米」，
     * 与实际不符**（那两行是 v0.31.0 早期用**逐点高差**算的，
     * 换成 drives.ascent/descent 口径后实测是 51,791 / 48,513）。
     *
     * ⚠️ **净爬升可以是负的** —— 所以做能耗修正时必须
     * 「上坡按毛爬升算、下坡按毛下降抵扣」，不能用净值。
     */
    val climbAscentM: Int = 0,
    /** 毛下降（米） */
    val climbDescentM: Int = 0,
    /**
     * ⚠️ **v0.34.0（第 21 轮视觉评审 P1-8）：爬升修正**实际生效**了吗。**
     *
     * 爬升模型可能加载失败（服务端 500 / 超时 → `climbModel = null`）。
     * 前提行原来是无条件写「已按实测爬升系数做加性修正」——
     * 那时这句话是**假的**。
     */
    val climbCorrectionApplied: Boolean = false,
    /**
     * ⚠️ **v0.36.0：≤10°C 的样本条数**（低温修正是否可信的判据）。
     *
     * 原来卡片上**写死**了「本车 ≤10°C 只有 1 条样本」——
     * 那个数当时是对的，但它是**硬编码**的：等冬天跑出更多低温行程，
     * 这句话就会变成**假话**（而且没有任何人会去改它）。
     *
     * 现在取自能耗模型的实测值（`ConsumptionModel.lowTempSamples`）。
     */
    val lowTempSamples: Int? = null,
    /**
     * ⚠️ **v0.36.0（第 21 轮一线车主复审 C8）：余量估计的实测误差（电量百分点）。**
     *
     * 口径：能耗模型**自己**的残差 90 分位，换算到**最长那一跳**的里程上。
     * 复审原话：「要么给区间，要么把上次实际值并排放」——
     * 这里的数不是拍脑袋写的，是模型自己的不确定度。
     */
    val modelErrorPoints: Int? = null,
) {
    /**
     * 爬升折合**多少座珠穆朗玛峰**（珠峰 8,849 m）。
     *
     * 只给**一个**对比锚点 —— 给三个锚点就是装饰。
     */
    val climbEverests: Double
        get() = if (climbAscentM > 0) climbAscentM / 8849.0 else 0.0

    /**
     * ⚠️ **v0.39.0（第 23 轮车主复审 C6）：全程最低点的**落点**。**
     *
     * 原来只说「全程最低 5%（第 1237 公里）」——
     * 而那个公里数**不在任何一个站上**，用户看到 5% 也不知道「我在哪一站少充了」。
     * 复审原话：「我看到 5% 也不知道它在哪儿 —— 这是"半句话"，比没有更让人卡。」
     *
     * 现在算出它属于**哪一跳**：找出 `kmAlongRoute ≤ 最低点公里` 的最后一个站，
     * 落点就是「离开那个站之后」。
     */
    val lowestHopFrom: String? get() {
        val lowest = lowestPoint?.first ?: return null
        val before = stops.filter { it.kmAlongRoute <= lowest }.maxByOrNull { it.kmAlongRoute }
        return before?.name
    }

    /**
     * ⚠️ **v0.39.0（第 23 轮车主复审 P2-2）：这条线上有**几跳无解**。**
     *
     * 折叠态只显示**最长那一跳**的无解提示，而实测那条线有 **2 跳**无解
     * （建设南路 req 103、修文县 req 108）—— 另一跳被藏起来了。
     */
    val impossibleHopCount: Int get() = stops.count { (it.requiredSocHere ?: 0) > 100 }

    /**
     * ⚠️ **v0.39.0（第 23 轮车主复审 P2-3）：**需要处理**的那几跳。**
     *
     * 复审的批评很直接：
     * > 「展开态是 36 站按里程排的清单，**没有「下一站」锚点、没有跳转**。
     * > 出发前 5 分钟要在 15 屏里找「下一站」，和 v0.34 的问题性质相同。」
     *
     * 全量列表不能删（数据一点没丢是原则），但**要处理的那几跳必须能一眼找到**：
     * ① 这一跳**充满也到不了**（必须提前加一站）；
     * ② 估算到站**低于保底**；
     * ③ 估算说够、但**上次实测低于保底**（模型偏乐观的跳）。
     *
     * 实测那条线有 36 跳，其中**只有 4 跳**需要处理 —— 摘要就是这 4 跳。
     */
    val actionableHops: List<RouteHop>
        get() {
            val out = mutableListOf<RouteHop>()
            for (i in stops.indices) {
                val s = stops[i]
                val gap = s.gapToNextKm ?: continue
                if (gap < 2.0) continue
                val est = s.socAtNextStop
                val real = s.nextStopRealSoc
                val impossible = (s.requiredSocHere ?: 0) > 100
                val estLow = est != null && est < RESERVE_SOC
                val realLowHidden = real != null && real < RESERVE_SOC && (est ?: 0) >= RESERVE_SOC
                if (!impossible && !estLow && !realLowHidden) continue
                out += RouteHop(
                    fromName = s.name,
                    toName = stops.getOrNull(i + 1)?.name ?: "终点",
                    km = gap,
                    realSoc = s.nextStopRealSoc,
                    estSoc = est,
                    needPct = s.gapNeedPct,
                    requiredSoc = s.requiredSocHere,
                )
            }
            return out
        }

    /** 最低点（不含充电跳升后的值） */
    val lowestSoc: Int? get() = profile.minByOrNull { it.soc }?.soc

    /**
     * ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-4）：最长的一跳。**
     *
     * 出发前要准备的不是「整条 6227 公里」，而是**最长那一跳**
     * —— 复审的原话：「我**真的会读**的：最长一段 351 公里」。
     *
     * 口径与 [stationRhythm] 一致：只取**站与站之间**、且 **≥2 公里**的间距
     * （<2 公里的是同一个桩被拆成两行，不是真的站间距）。
     */
    val longestHop: RouteHop?
        get() {
            var best: RouteHop? = null
            for (i in stops.indices) {
                val s = stops[i]
                val gap = s.gapToNextKm ?: continue
                if (gap < 2.0) continue
                if (best != null && gap <= best.km) continue
                val nxt = stops.getOrNull(i + 1)
                best = RouteHop(
                    fromName = s.name,
                    toName = nxt?.name ?: "终点",
                    km = gap,
                    // ⚠️ **v0.37.0（单测抓到的真 bug）：真值必须取 `nextStopRealSoc`。**
                    //
                    // 原来这里写的是 `nxt?.socBefore` —— 那是**插枪电量**，
                    // 正是 v0.36.0 修掉的那个错口径（C1）。
                    // 而 `nextStopRealSoc` 才是**下一站的到站电量**（已修正过）。
                    //
                    // JVM 单测 `RoutePlanLogicTest.决策句给出真值与估算` 当场抓到了它。
                    realSoc = s.nextStopRealSoc,
                    estSoc = s.socAtNextStop,
                    needPct = s.gapNeedPct,
                    requiredSoc = s.requiredSocHere,
                )
            }
            return best
        }

    /**
     * ⚠️ **v0.29.0（复审 P0-3）：最低点要带上它的公里数。**
     *
     * 原来 `lowestSoc`（全局最低）与 `belowFloorKm`（第一个跌破 20% 的点）
     * 是**两个不同的点**，而结论句把它们**拼在一起**：
     * 「第 227 公里只剩 5%」—— 但第 227 公里真实是 **18%**，
     * 5% 是整条路线的最低点（在第 1237 公里）。
     * **两个数来自不同位置，却被写成了同一句话。**
     */
    val lowestPoint: Pair<Double, Int>? get() =
        profile.minByOrNull { it.soc }?.let { it.km to it.soc }

    /**
     * 第一个跌破 20% 保底的点（公里 + **该点的**电量）。
     *
     * ⚠️ v0.29.0：公里与电量必须来自**同一个点**（见 [lowestPoint] 的说明）。
     */
    val firstBelowFloor: Pair<Double, Int>? get() =
        profile.firstOrNull { it.soc < 20 }?.let { it.km to it.soc }

    /** 低于 20% 保底的公里数 */
    val belowFloorKm: Double? get() = firstBelowFloor?.first

    /** 一句话结论 */
    fun conclusion(): String {
        val s = template
        val parts = mutableListOf<String>()
        // ⚠️ **v0.31.0：往返要明说。**
        // 起点与终点是同一个地方时，写「永丰社区 → 永丰社区」会被读成「开了个来回」，
        // 但读者不知道这是**6122 公里的往返**；写成「永丰社区（往返）」才准确。
        parts += if (s.isRoundTrip) {
            "${s.fromLabel}（往返）"
        } else {
            "${s.fromLabel} → ${s.toLabel}"
        }
        parts += "${s.distanceKm.roundToInt()} 公里"
        // ⚠️ **v0.34.0（第 21 轮一线车主复审 P2-2）：段数删掉。**
        //
        // 原来写「191 段行程（其中 131 段超过 2 公里）」——
        // 复审原话：「**我不看段数**」。
        // 那两个数是**内部口径**（段数 ≠ 充电次数），对用户没有决策价值，
        // 只会占掉一行。
        if (s.stopCount > 0) parts += "$s.stopCount 次沿途充电"
        return parts.joinToString(" · ")
    }

    /**
     * ⚠️ v0.14.0（长途自驾党 P0-1）：**决策句** —— 出发前唯一要问的那句。
     *
     * 原来这张卡只给线路身份（「A → B · 252 公里 · 走过 1 次」），
     * 从来没回答过「这次能不能一次开到？要在哪充？充多久？」
     * —— 数据都算出来了，只差翻译成一句话。
     *
     * ⚠️ v0.20.0（车主 P0-2）：**「不用中途充电」这句话是错的**。
     *
     * 原来的 `stops.isEmpty()` 分支写「不用中途充电」——
     * 但 `stops` 的含义是「**这条线路上你充电过的站**」，
     * 不是「这里需要充电」。你在这条线上一次都没充过电，
     * 结论就变成「不用中途充电」—— **把「没有数据」说成了「不需要」**。
     *
     * 车主原话：「出发前一晚我规划这条 252 公里的长途，结论句第一句是
     * 「不用中途充电」，于是我不安排任何补电；后半句我当成了脚注。」
     *
     * 现在：
     * ① 先说**电量结论**（到站剩多少 / 哪一公里跌破保底）—— 这才是决策依据；
     * ② 没有补电点时明说「这条线路上没有你的充电记录，中途补电给不出位置」，
     *    而不是假装「不需要充」。
     */
    /**
     * ⚠️ **v0.24.0（长途充电规划专家 P0-3）：决策句必须带**来源限定**。**
     *
     * 原来输出的是「需充 2 次」「第 252 公里只剩 16%」——
     * 读起来像是「**这次**要充 2 次」，但它 100% 来自**单趟历史 drive** 的实测值。
     *
     * 专家实测复刻：那条 252 公里的线路，App 会显示「需充 2 次」——
     * 而那 2 次其实是**上一趟的起点充电**（充电必然打断行程，
     * 实测 78/78 场充电都落在某趟 drive 的起点或终点附近，最近距离 ≤ 0.022 km）。
     *
     * **后果**：用户读到「需充 2 次」会真的去安排两次补电，
     * 而这条线真实的历史节奏是**平均每 128 km 充一次**（42 场）。
     * 这个数字会直接导致**错误的补电计划**。
     *
     * 现在每句都带「按 X 那趟实测」前缀 ——
     * 把「历史」和「预测」分开说，让用户自己判断可信度。
     */
    fun verdict(): String {
        // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-7 / 一线车主 P0-4）：决策句必须**短**。**
        //
        // 原来这一句是 **85 字**（含站间距分布 + 两个低点），
        // 在 329dp 宽上折成 **4 行**（fs=1.0）/ **8~9 行**（fs=2.0）——
        // 而它的设计意图（`RoutePlan.kt:1021`）明明写着
        // 「结论句直接写在摘要行里 —— 出发前 5 分钟只读这一行就够」。
        //
        // 现在拆成三段：
        // - [verdict()] —— **决策句**（一行）：真值 + 估算并排；
        // - [verdictDetail()] —— **细节行**：来源限定 + 全程最低；
        // - [stationRhythm()] —— **站间距分布**：只留在**展开卡**里。
        //
        // ⚠️ **v0.35.0**：折叠态的主角（44sp）已经显示了「最长一段 + 站名」，
        // 所以这里**不再重复**那部分，只说电量。
        val hop = longestHop
        if (hop != null) {
            val sb = StringBuilder()
            hop.realSoc?.let { sb.append("上次到站剩 ").append(it).append("%") }
            // ⚠️ **v0.36.0（第 21 轮一线车主复审 C3）：这一跳**无解**时不报估算。**
            //
            // 「估算剩 8%」会被读成「到了那儿剩 8%，紧但能到」——
            // 而真相是「**充满也到不了**」（这一跳要 88 个点 + 20 保底 = 108 > 100）。
            // 复审原话：「我会读成'到了那儿剩 8%，有点紧但能到'。」
            val req = hop.requiredSoc
            val impossible = req != null && req > 100
            if (!impossible) {
                hop.estSoc?.let { soc ->
                    if (sb.isNotEmpty()) sb.append(" · ")
                    if (soc >= 0) {
                        sb.append("估算剩 ").append(soc).append("%")
                    } else {
                        sb.append("估算到不了（差 ").append(-soc).append(" 个点）")
                    }
                }
            }
            if (sb.isEmpty()) sb.append("这条线路上没有站间数据")
            return sb.toString()
        }
        val low = lowestSoc ?: return "算不出结论（没有足够的电量记录）"
        return if (stops.isEmpty()) {
            "能一次开到 · 预计到达 $low%"
        } else {
            "这条线路上没有你的充电记录，中途补电给不出位置"
        }
    }

    /**
     * ⚠️ **v0.34.0（第 21 轮一线车主复审 P2-4）：两个「低点」并排会打架。**
     *
     * 原来结论句里既有「第 227 公里只剩 18%（低于 20% 保底）」
     * 又有「全程最低 5%（第 1237 公里）」——
     * 读者会读成「227 公里最危险」，而 5% 才是真的。
     * 现在**只留最低点**，跌破 20% 的事交给逐站那行。
     */
    fun verdictDetail(): String {
        val sb = StringBuilder()
        // ⚠️ **v0.36.0（第 21 轮视觉评审 P1-5）：细节行只留**最低点**，日期挪到展开卡。**
        //
        // 原来写「2026-10-07 那趟实测 · 全程最低 5%（第 1237 公里）」——
        // 复审实测在 360dp 屏上占 **2 行**，而日期在展开卡里已经有了
        // （「按这条线路上真实发生过的充电推算（最近一次 2026-10-07）」）。
        //
        // ⚠️ **车主复审明确说「全程最低 5%（第 1237 公里）」是他会读的两个数之一** ——
        // 所以这条**保留**，只砍掉重复的日期。
        lowestPoint?.let { (km, soc) ->
            sb.append("全程最低 ").append(soc).append("%（第 ").append(km.roundToInt()).append(" 公里")
            // ⚠️ **v0.39.0（第 23 轮车主复审 C6）：给**落点**。**
            lowestHopFrom?.let { from ->
                sb.append(" · ").append(from).append("之后")
            }
            sb.append("）")
        }
        return sb.toString()
    }

    /**
     * ⚠️ **v0.30.0（长途自驾党 P0-2）：「平均每 N 公里停一次」必须换成真实站间距。**
     *
     * 原来 `gapKm = 总里程 ÷ 次数` —— **平均数掩盖了分布**。
     * 实测：6227 公里那条的真实站间距是 **13～351 公里**（中位 183），
     * 616 公里那条是 **13～189 公里**（中位 133）。
     * 按「平均每 164 公里」准备，会在**最长那 351 公里**那段毫无准备。
     *
     * ⚠️ **v0.32.0（第 20 轮规划复审 P1-2）：口径再收紧三处。**
     *
     * ① 只取**站与站之间**的间距 —— 原来把「起点→首站」「末站→终点」
     *    两个**半段**也算进去，于是 616 公里那条的中位被拉到 88。
     * ② 剔除 **< 2 公里**的间距 —— 那是**同一个站充了两次**。
     * ③ 中位改用 [medianOf]（偶数取两中位平均）——
     *    项目在 `Stats.kt` 已统一成这个口径，这里原是**第四种**实现。
     *
     * ⚠️ **v0.34.0：整句移出折叠态**（只留在展开卡）——
     * 它是**证据**（分布），不是**决策**（那一跳）。
     */
    fun stationRhythm(): String {
        if (stops.size < 2) return ""
        val stationKms = stops.map { it.kmAlongRoute }.sorted()
        val gaps = stationKms.zipWithNext { a, b -> b - a }.filter { it >= 2.0 }
        if (gaps.isEmpty()) return ""
        val maxGap = gaps.max()
        val minGap = gaps.min()
        val medGap = medianOf(gaps.map { it }) ?: gaps.sorted()[gaps.size / 2]
        return "站与站之间 ${minGap.roundToInt()}～${maxGap.roundToInt()} 公里，" +
            "中位 ${medGap.roundToInt()}；最长一段 ${maxGap.roundToInt()} 公里"
    }
}

/** 线路分组的键：起止各取「围栏名优先、否则地址第一段」。 */
private fun endpointKey(geofence: String?, place: String?): String =
    geofence?.takeIf { it.isNotBlank() } ?: place.firstSegment() ?: "?"

/**
 * 从历史行程里挑出**历史长途 journey**。
 *
 * ⚠️ **v0.26.0（第 17 轮长途自驾党 P0-1）：单位从「一趟 drive」改成「一段 journey」。**
 *
 * ## 为什么必须改
 *
 * 原来按「起止地名」分组、取**最近那一趟** —— 这在数据上是错的：
 * **充电必然打断行程**（实测 78/78 场充电都落在某趟 drive 的起点或终点附近，
 * 最近距离 ≤ 0.022 km），所以**一趟 drive 内部永远不会发生充电**。
 *
 * 后果（复刻 App 的实际输出）：
 * 1. 「沿途充电站」列出来的其实是**上一趟的收尾充电** ——
 *    它落在本趟的起点里程上，被里程窗口捞进来了。
 * 2. 本趟**真正**的那次充电落在终点里程上，被判成「终点处的充电」而剔除。
 * 3. 「站间距离」「到下一站剩多少电」全建立在**错位的站**上。
 *
 * 「11 条 ≥200 km 的线路」其实是**同一次长途被充电切碎的 11 段**。
 *
 * 现在用 [buildJourneys]：按**里程连续** + **「回家了」**两个判据切出连续出门，
 * 沿途站按**行程边界**归属 —— 详见 [RouteJourney.kt]。
 *
 * @param minDistanceKm 多远算「长途」（默认 **200 km**）
 * @return 按「最近一次走的时刻」倒序排列
 */
fun buildRouteTemplates(
    drives: List<Drive>,
    charges: List<Charge>,
    minDistanceKm: Double = 200.0,
): List<RouteTemplate> =
    buildJourneys(drives, charges, minDistanceKm).map { j ->
        RouteTemplate(
            key = j.key,
            fromLabel = j.fromLabel,
            toLabel = j.toLabel,
            distanceKm = j.distanceKm,
            // ⚠️ 端点取**整段旅程**的两端：
            // 起点 SOC 来自**首段**（出发状态），终点 SOC 来自**末段**（到达状态）。
            startSoc = j.legs.first().startSoc,
            endSoc = j.legs.last().endSoc,
            // ⚠️ v0.28.0（复审 P1-10）：用「有效段数」（排除充电站内挪车微行程）。
            legCount = j.meaningfulLegCount,
            // ⚠️ v0.30.0（P1-2）：**全部**段数也带上（两个数都要能显示）
            totalLegCount = j.legCount,
            stopCount = j.midStopCount,
            lastDrivenAt = j.endDate,
            startOdoKm = j.startKm,
            lastDistanceKm = j.distanceKm,
            // ⚠️ v0.30.0（P0-1）：真实均速（不再硬编码 90 km/h）
            avgSpeedKph = j.avgSpeedKph,
            // ⚠️ v0.31.0：是不是往返
            isRoundTrip = j.isRoundTrip,
            lastStartSoc = j.legs.first().startSoc,
            lastEndSoc = j.legs.last().endSoc,
            lastDriveId = j.legs.last().driveId,
            legIds = j.legs.map { it.driveId },
            legs = j.legs,
            stops = j.stops,
        )
    }


/**
 * 用模板 + 历史充电 + 站点统计，算出这条线路的余量表。
 *
 * 余量剖面**不是模型算的**，而是把这条线路上**历史上真实发生过的充电**
 * 按里程表对齐到路线公里数上拼出来的：
 *   - 起点：模板的 start_soc
 *   - 每个站：到站时的 socBefore → 充完的 socAfter（一次跳升）
 *   - 终点：模板的 end_soc
 */
fun buildRoutePlan(
    template: RouteTemplate,
    charges: List<Charge>,
    stationStats: Map<String, StationStat>,
    /**
     * ⚠️ v0.24.0：**真实逐公里 SOC 剖面**（来自 `/drives/{id}/soc_profile`）。
     *
     * 原来剖面只有 2~6 个点，中间是**直线插值** ——
     * 山区长途（爬升 1500~2600 m）误差实测达 **13 公里**：
     * 真实跌破 20% 保底在第 244 公里，插值算出来是 231 公里。
     * 用户按 231 公里规划，到 244 公里时电已经见底 —— **会导致危险决策**。
     *
     * 有真实剖面就用它；没有（端点没拉到）才回落到插值。
     */
    socProfile: List<com.teslamate.app.SocProfilePoint>? = null,
    /**
     * ⚠️ **v0.25.0：到下一站剩多少电**要用的两个输入。
     *
     * - [consumption]：本车**实测**能耗模型（按车速分桶的 Wh/km）——
     *   不用「官方标称」，因为标称比实测乐观 50%。
     * - [batteryCapacityKwh]：本车**可用**电池容量（服务端按实测反推）。
     */
    consumption: com.teslamate.app.ConsumptionModel? = null,
    batteryCapacityKwh: Double? = null,
    /** 计划车速（km/h）—— 决定用哪一档 Wh/km。 */
    planSpeedKph: Double? = null,
    /**
     * ⚠️ **v0.33.0（第 20 轮爬升能耗建模评审）：本车的爬升能耗系数。**
     *
     * 没有它就**不给**爬升修正（宁可少算一个修正，也不能编一个系数）。
     *
     * 口径：**加性** —— `whEff = 基础 Wh/km + c_up × 上坡坡度(m/km) − c_regen × 下坡坡度(m/km)`
     * （重力位能 m·g·h 是绝对量，与车速无关，所以不能是乘性）。
     */
    climbModel: com.teslamate.app.ClimbModel? = null,
): RoutePlan? {
    val startOdo = template.startOdoKm ?: return null
    // ⚠️ v0.14.0（口径审计 P0-4）：里程窗口用**最近那一趟的真实里程**，不用中位数。
    // 中位数可能比那一趟长或短，窗口就会捞到别次行程的充电。
    val runDistance = template.lastDistanceKm ?: template.distanceKm
    if (runDistance <= 0) return null

    // ⚠️ **v0.26.0（第 17 轮长途自驾党 P0-1）：沿途站不再用里程窗口「捞」。**
    //
    // 原来这段是：`charges.filter { odo in [startOdo-5, startOdo+runDistance+5] }`。
    // 而实测 **78/78 场充电都落在某趟 drive 的起点或终点附近**（≤0.022 km）——
    // 于是：
    //   · 落在**起点**里程上的那次充电（**上一趟的收尾**）被捞进来，里程是 0
    //     —— 于是「沿途第一个站在第 0 公里」；
    //   · 这一趟**真正**的那次充电落在**终点**里程上，又被「终点处的充电」规则剔除。
    //
    // 结论：**沿途站列表列的是上一趟的起点站**，
    // 而「站间距离」「到下一站剩多少电」全部建立在**错位的站**上。
    //
    // 现在：归属在 [buildJourneys] 里做好了 —— 充电落在**两段之间的缺口**里才算沿途站。
    val journeyStops = template.stops

    // ⚠️ **v0.31.0：爬升。**
    //
    // 数据来源：`drives.ascent` / `drives.descent`（客户端已经有的字段）。
    // 口径：实测 drives.ascent **逐条**等于「相邻点正高差之和」（439/439，最大差 0 米），
    // 但含 GPS 量化噪声（长途大段偏差 0.8%~2.3%，短途中位虚高 17.1%）——
    // 所以**只适合长途场景**（本函数本来就只处理 ≥200 km 的线路）。
    //
    // ⚠️ **净爬升可以是负的**（实测那条线终点比起点低 750 米），
    // 所以逐段给「上坡多少」，**不用净值**。
    val journeyStartKm = template.legs.firstOrNull()?.startKm ?: 0.0
    val legRanges = template.legs.map {
        Triple(
            it.startKm - journeyStartKm,
            it.endKm - journeyStartKm,
            it.ascentM ?: 0,
        )
    }
    val descentRanges = template.legs.map {
        Triple(
            it.startKm - journeyStartKm,
            it.endKm - journeyStartKm,
            it.descentM ?: 0,
        )
    }
    /** 某段里程区间内的爬升（按重叠比例分摊）。 */
    fun ascentBetween(fromKm: Double, toKm: Double): Int {
        if (toKm <= fromKm) return 0
        var sum = 0.0
        for ((s, e, a) in legRanges) {
            val ov = minOf(e, toKm) - maxOf(s, fromKm)
            if (ov <= 0.0 || e <= s) continue
            sum += a * (ov / (e - s))
        }
        return sum.toInt()
    }
    /** 某段里程区间内的下降（按重叠比例分摊）。 */
    fun descentBetween(fromKm: Double, toKm: Double): Int {
        if (toKm <= fromKm) return 0
        var sum = 0.0
        for ((s, e, a) in descentRanges) {
            val ov = minOf(e, toKm) - maxOf(s, fromKm)
            if (ov <= 0.0 || e <= s) continue
            sum += a * (ov / (e - s))
        }
        return sum.toInt()
    }
    val routeAscentM = template.legs.sumOf { it.ascentM ?: 0 }
    val routeDescentM = template.legs.sumOf { it.descentM ?: 0 }

    // ⚠️ **v0.25.0（长途充电规划专家 P1-2）：站间距离 + 「到下一站剩多少电」。**
    //
    // 原来只给「在第几公里」，不给「到下一站多远」——
    // 而后者才是出发前真正要回答的那句：
    // 「我充到 80%，**够不够开到下一个**？」
    //
    // 站间距离来自相邻充电点的里程差（实测本车真实节奏：中位 128 公里，最远 351 公里）。
    // 「到下一站剩多少」用**本车实测** Wh/km（不是官方标称 —— 标称乐观 50%）
    // 与**可用容量**换算，并与 20% 保底对齐。
    val whPerKm = consumption?.let { m ->
        planSpeedKph?.let { m.whSample(it)?.bucket?.whPerKm }
    }
    /**
     * 站名的显示规则（⚠️ v0.29.0 复审 P0-5 定下的口径）：
     * `poi_name`（车主认得的「金田服务区」）→ 围栏名 → 地址首段。
     *
     * ⚠️ v0.34.0：抽成函数是为了让**下一站的名字**能用同一套规则 ——
     * 否则「到下一站剩 13%」不知道说的是哪个站。
     */
    fun stopDisplayName(c: Charge): String =
        // ⚠️ **v0.36.0（第 21 轮一线车主复审 P1-2）：优先用服务端的**完整标签**。**
        //
        // 原来这里回退到 `place.firstSegment()` —— 而第一段经常是**路名**
        // （「G210」「G227;G318」「沪聂线」「梧硕高速」），
        // 复审原话：「"G210" 我在地图上根本搜不到」。
        // 现在服务端的 `place_label` 会在是路名时带上地名（「G210 车河镇」）。
        c.place_label?.takeIf { it.isNotBlank() }
            ?: c.poi_name?.takeIf { it.isNotBlank() }
            ?: c.geofence_name?.takeIf { it.isNotBlank() }
            ?: c.place.firstSegment() ?: "充电站"
    val stops = journeyStops.mapIndexed { idx, js ->
        val c = js.charge
        val stat = stationStats[stationKey(c)]
        // 到达后的那次充电没有「下一站」。
        val next = journeyStops.getOrNull(idx + 1)?.takeIf { !js.isEndpoint }
        val gapKm = next?.let { it.kmAlong - js.kmAlong }?.takeIf { it > 0.5 }
        // ⚠️ **v0.32.0（第 20 轮规划复审 P1-3）：这一段的能耗必须按**这一段**的速度算。**
        //
        // 原来用**整条线路**的均速选 Wh/km 档 —— 而最长的那几段恰恰是高速段：
        // 实测 6227 公里那条整条均速 66 km/h（落 60–90 档 174.3 Wh/km），
        // 而 351 公里那段真实均速 **96 km/h**（90–110 档 183.0）、
        // 229 公里那段 **112.7 km/h**（110–130 档 197.4）。
        //
        // 用整条均速算 = **系统性乐观 4.78%**，在长途最长的几段上结论直接反转：
        // 实测 37 段里有 **8 段**在数学上到不了下一站，
        // 而界面说「看着够」。
        val gapLegs = if (gapKm != null) {
            template.legs.filter { leg ->
                val s = leg.startKm - journeyStartKm
                val e = leg.endKm - journeyStartKm
                e > js.kmAlong && s < js.kmAlong + gapKm
            }
        } else {
            emptyList()
        }
        val gapMinutes = gapLegs.sumOf { it.durationMin ?: 0 }
        val gapSegSpeed = if (gapLegs.isNotEmpty() && gapMinutes > 0) {
            gapLegs.sumOf { it.distanceKm } / (gapMinutes / 60.0)
        } else {
            null
        }
        val gapSample = gapSegSpeed?.let { v -> consumption?.whSample(v) }
        // 优先用**这一段**的档；这一段没数据时回落到整条线路的速度。
        val gapWhBase = gapSample?.bucket?.whPerKm ?: whPerKm
        // ⚠️ **v0.33.0：爬升修正（加性）。**
        //
        // 实测本车 c_up ≈ 6.4 Wh/m、c_regen ≈ 3.2 Wh/m（服务端用 99 趟拟合，
        // 隐含传动效率 78% / 回充效率 64%，都在物理区间内）。
        //
        // 实测影响：那条川藏滇线里 206 公里那段爬了 4,186 米 →
        // 修正 **+88.8 Wh/km（+51%）** → 折合 **−26 个电量百分点**。
        // 原来没有这个修正，界面会说「看着够」，实际到不了。
        //
        // ⚠️ **口径提示（诚实起见）**：系数是用**车轮功率**口径拟合的，
        // 而底档（`whSample`）用的是**标定法**口径 —— 两者不完全同源，
        // 所以这个修正量**略偏保守**（实测两套口径的车速档值在同一量级：
        // 90–110 档 标定法 182.7 vs 车轮功率 184.2）。
        // 要完全同源，需要把系数也拟合成标定法口径（见 docs/backlog-r19-r20.md）。
        // ⚠️ **v0.34.0（第 21 轮独立复算 P0）：爬升取的是**上一段**，却除以**本段**里程。**
        //
        // 原来：`ascentBetween(上一站 → 本站)` ÷ `gapKm`（本站 → 下一站）——
        // **分子分母不同源**。
        // 独立复算实测（用服务端真实 place_key 重建 36 个站间段）：
        // 仅这一项就造成 **4 次判定翻转**、累计耗电偏差 **+16.6 kWh**；
        // 最坏的一段是 54 公里，被按上一段的 4,186 米算出 **+341.5 Wh/km** →
        // 到站 **7.4%**（界面会写「到不了，必须再充一次」），而真值是 **33.9%**（OK）。
        //
        // 现在：爬升与下降都取**本段**区间（本站 → 下一站）。
        val gapAscentM = if (next != null && gapKm != null) {
            ascentBetween(js.kmAlong, next.kmAlong)
        } else {
            0
        }
        val gapDescentM = if (next != null && gapKm != null) {
            descentBetween(js.kmAlong, next.kmAlong)
        } else {
            0
        }
        // ⚠️ **v0.34.0（第 21 轮口径审计 P0-2 + 独立复算）：delta-grade 修正。**
        //
        // 公式：`修正 = c_up_cal × (g_up − g_up_typ(该档)) + c_regen_cal × (g_dn − g_dn_typ(该档))`
        //
        // **为什么必须是 delta 而不是绝对坡度**：
        // 能耗底档是该车速档趟次的**中位数**，它**已经包含**该档的典型爬升；
        // 用绝对坡度等于把典型值再叠一遍 —— 独立复算实测**高估 +12.08%**，
        // 在「到站电量 vs 20% 保底线」上造成 **4 次结论翻转**。
        // delta 形式在「该段坡度 = 该档典型坡度」时修正恰好为 0，
        // **结构上不可能**系统性抬高。
        //
        // 实测（客户端真实可用样本 88 趟）：
        //   旧公式（绝对坡度 + 车轮口径系数）  偏差 +12.08%  RMSE 37.13  翻转 4
        //   新公式（delta + 标定法口径系数）     偏差  −0.84%  RMSE 24.12  翻转 0
        //
        // ⚠️ **口径守卫**：系数必须是 `basis == "calib_net"`（与能耗底档同源）。
        // 不是就不给修正 —— **宁可少算一个修正，也不能混口径**。
        // ⚠️ **最小 gap 门槛**：< [CLIMB_MIN_GAP_KM] 公里的段不给爬升修正 ——
        // 那里坡度噪声与真实爬升完全不成比例。
        val climbTyp = climbModel?.typical_grade?.firstOrNull { tg ->
            gapSample?.bucket?.minKph == tg.min_kph
        }
        val gapClimbWh = if (gapKm != null && gapKm >= CLIMB_MIN_GAP_KM
            && climbModel != null && climbModel.basis == CLIMB_BASIS_CALIB_NET
            && climbTyp != null
        ) {
            val gradeUp = gapAscentM / gapKm
            val gradeDown = gapDescentM / gapKm
            climbModel.c_up_calib_net_wh_per_m * (gradeUp - climbTyp.grade_up_typ_m_per_km) +
                climbModel.c_regen_calib_net_wh_per_m * (gradeDown - climbTyp.grade_down_typ_m_per_km)
        } else {
            null
        }
        val gapWh = if (gapWhBase != null) gapWhBase + (gapClimbWh ?: 0.0) else null
        val gapSpeedForDisplay = gapSegSpeed ?: planSpeedKph
        // 充到 socAfter 后，到下一站还剩多少电（%）
        //
        // ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-3）：把「这一跳要几个点」单独存出来。**
        // 原来它只以**差值**（`到下一站剩 X%`）出现过，用户没法据此回答
        // 「那我该充到多少」—— 而那才是出发前真正要决定的事。
        val needPct = if (gapKm != null && gapWh != null && batteryCapacityKwh != null
            && batteryCapacityKwh > 0
        ) {
            val needKwh = gapKm * gapWh / 1000.0
            (needKwh / batteryCapacityKwh * 100).roundToInt()
        } else {
            null
        }
        val socAtNext = if (needPct != null && c.end_soc != null) {
            (c.end_soc - needPct).coerceIn(-100, 100)
        } else {
            null
        }
        // ⚠️ **v0.34.0（P0-3）：处方 —— 留 20% 保底，本站至少要充到多少。**
        // >100 就说明**这一跳没有解**（充满也到不了保底），
        // 那就不能说「再充一次」，得说「得在前面加一站」。
        val requiredHere = needPct?.let { it + RESERVE_SOC }
        // ⚠️ **v0.36.0（第 21 轮一线车主复审 C1）：真值改用**到站电量**。**
        //
        // 原来用 `next.socBefore`（**插枪**电量）—— 实测 35 站里 **6 站取错场次**
        // （合并时按里程稳定排序 → 同一里程的两场里后一场排在前 →
        // `socBefore` 留的是后一场的插枪电量），
        // 而且错的方向一致地**让人更放心**（梧硕高速显示 27%、真值 8%；
        // 长征大道显示 94%、真值 39%；高山别庄显示 100%、真值 43%）。
        //
        // 现在用**到站电量**（那一趟行程的 `end_soc`），它不受合并顺序、
        // 0 度空会话被 API 过滤、插枪前还走过一段这三件事影响。
        val nextRealSoc = next?.arrivalSoc ?: next?.socBefore
        val nextName = next?.let { stopDisplayName(it.charge) }
        // ⚠️ **v0.36.0（第 21 轮一线车主复审 C4）：终点前那一段也要给余量。**
        //
        // 最后一个站之后往往还有 **146 公里**（6227 公里那条）/
        // **88 公里**（616 公里那条），而终点不是充电站 →
        // 永远没有「下一站」→ 那一段**一个字都不显示**。
        //
        // ⚠️ **v0.38.0（第 23 轮车主复审 P0-1）：只给**最后一站**，不能每站都给！**
        //
        // 原来写的是 `if (endGapKm > 0.5)` —— 而 `endGapKm` 是**每一站到终点的距离**，
        // 所以**每一站都非空** ⇒ 36 个站每个都多渲染一行：
        //     乐驿（第 276 公里）：距终点 5951 公里 · 估到终点剩 **-1508%**
        //     梧州大酒店：距终点 5760 公里 · 估到终点剩 **-1434%**
        // 那是**假数据**（−1508% 在物理上不存在），而且让展开态多了 36 行（+44%）。
        // 现在只给**最后一个沿途站**。
        val lastRouteStopIdx = journeyStops.indexOfLast { !it.isEndpoint }
        val endGapKm = runDistance - js.kmAlong
        val endNeedPct = if (endGapKm > 0.5 && whPerKm != null
            && batteryCapacityKwh != null && batteryCapacityKwh > 0
        ) {
            ((endGapKm * whPerKm / 1000.0) / batteryCapacityKwh * 100).roundToInt()
        } else {
            null
        }
        val estSocAtEnd = if (endNeedPct != null) {
            (js.socAfter ?: c.end_soc ?: 100) - endNeedPct
        } else {
            null
        }
        RouteStop(
            // ⚠️ **v0.29.0（复审 P0-5）：`poi_name` 排到最前。**
            //
            // 原来 `geofence_name` 优先 —— 而本机 `geofences` 只有 1 行、叫「家」，
            // 于是**所有**在家充的电都显示成「家」，
            // 路上的服务区却显示成一长串地址（「臣田社区, 西乡街道, 宝安区, 深圳市…」）。
            //
            // `poi_name` 才是车主认得的名字（「金田服务区」「合江荔乡服务区」），
            // 所以它排第一；围栏名（如果有意义的地名）排第二；地址首段兜底。
            name = stopDisplayName(c),
            kmAlongRoute = js.kmAlong,
            // ⚠️ **v0.28.0（复审 P0-①）：必须用**合并后**的电量。**
            //
            // 原来用代表记录的 `c.start_soc / c.end_soc` —— 而代表记录可能只是
            // 一次插枪被切成的**尾段**（0.1 kWh 那种），
            // 于是显示成「94% → 94%」（等于没充，实际充了 36.6 度）。
            // 实测 10 个合并行里 **8 行是错的**：修文县那行真实是 36%→96%
            // 却显示成 76%→96% —— 差 40 个百分点，**剖面的低点被抹平**，
            // 可能漏报「跌破 20% 保底」。
            socBefore = js.socBefore ?: c.start_soc,
            socAfter = js.socAfter ?: c.end_soc,
            typicalPowerKw = stat?.typicalPowerDcKw ?: stat?.typicalPowerKw,
            minutesTo80 = stat?.typicalMinutesTo80,
            sessions = stat?.sessions ?: 1,
            // ⚠️ v0.14.0（长途自驾党 P0-2）：「从**本次到站电量**充到 socAfter」要多久。
            // 原来只有写死的「充到 80% 约 N 分钟」——
            // 我到站 16%、充到 60% 就走，这两个数毫无关系。
            //
            // ⚠️ **v0.31.0（第 20 轮充电系统数据专家 P0-1）：优先用**实测**。**
            //
            // 该站的**实测**样本（服务端逐点直读）与折算值差很多：
            // 实测 30 场中位 **+33%**、最大 **+185%**（折算偏大）。
            //
            // ⚠️ 只在**充到 80%**这个区间用实测 ——
            // 实测值就是「从起点到 80%」的分钟数，换个终点就得外推，
            // 而外推会重新引入折算的偏差。所以非 80% 的终点仍用折算。
            minutesToTarget = stat?.let {
                val kw = it.typicalPowerDcKw ?: it.typicalPowerKw
                val from = c.start_soc
                val to = c.end_soc
                if (from == null || to == null) null
                else {
                    val measured = if (to == 80) it.measuredMedianNear(from) else null
                    measured ?: it.minutesTo(from, to, kw)
                }
            },
            gapToNextKm = gapKm,
            socAtNextStop = socAtNext,
            // ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-1 / P0-3）**
            nextStopRealSoc = nextRealSoc,
            gapNeedPct = needPct,
            requiredSocHere = requiredHere,
            nextStopName = nextName,
            // ⚠️ v0.38.0（P0-2）：到站电量统一口径
            arrivalSoc = js.arrivalSoc,
            // ⚠️ v0.36.0（C8）：这一跳的估计误差（模型自己的残差 90 分位）
            gapErrorPoints = if (gapKm != null) {
                consumption?.socErrorPoints(gapKm, batteryCapacityKwh)
            } else {
                null
            },
            distanceToEndKm = if (idx == lastRouteStopIdx && endGapKm > 0.5) endGapKm else null,
            estSocAtEnd = if (idx == lastRouteStopIdx) estSocAtEnd else null,
            // ⚠️ **v0.30.0（长途自驾党 P0-1）：把车速与实际用的 Wh/km 带出去。**
            //
            // 「到下一站剩 34%」不交代前提就只是个数字 ——
            // 它是按「这段线路历史均速 → 对应 Wh/km 档」算的，
            // **没做**温度修正、**已按实测**爬升系数做加性修正、**没留**安全余量。
            // ⚠️ **v0.34.0（第 21 轮视觉评审 P2-4）：原文写「没做爬升修正」——过时了**，
            // v0.33.0 起就做了爬升修正。
            assumedSpeedKph = gapSpeedForDisplay,
            // ⚠️ **v0.34.0（第 21 轮视觉评审 P0-2）：基数与总数**必须同源可对账。**
            // 基数（档位值）与总数（含爬升修正）分开两个字段。
            assumedWhPerKm = gapWhBase,
            assumedWhPerKmTotal = gapWh,
            // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-3）：爬升修正同时给度与百分点。**
            gapClimbKwh = if (gapKm != null && gapClimbWh != null) {
                gapClimbWh * gapKm / 1000.0
            } else {
                null
            },
            gapClimbPct = if (gapKm != null && gapClimbWh != null
                && batteryCapacityKwh != null && batteryCapacityKwh > 0
            ) {
                gapClimbWh * gapKm / 1000.0 / batteryCapacityKwh * 100.0
            } else {
                null
            },
            // ⚠️ v0.31.0：从上一个站到这一站爬了多高
            gapAscentM = gapAscentM,
            gapDescentM = gapDescentM,
            gapClimbWhPerKm = gapClimbWh,
            // ⚠️ v0.32.0：这一段用到的 Wh/km 档（界面要交代「按哪一档算的」）
            gapBucketLabel = gapSample?.bucket?.label,
            gapSpeedBorrowed = gapSample?.exact == false,
        )
    }

    // ⚠️ v0.14.0（口径审计 P0-4）：**终点处的充电不算「沿途站点」**。
    //
    // 原来它被当成沿途站，于是剖面末端竖起一道「16% → 66%」的墙，
    // 末段沿原路折返 —— 读者看到的是「到终点时电量涨到 66%」，
    // 而结论行却写「全程最低 16%（在第 252 公里）」，两个数互相打架。
    //
    // ⚠️ v0.26.0：判据从「里程是否超过终点 5 公里」改成
    // **「这个充电是不是落在最后一段之后的缺口里」**（journey 切分时就已经知道了）。
    val routeStops = stops.filterIndexed { i, _ -> !journeyStops[i].isEndpoint }
    val endpointStops = stops.filterIndexed { i, _ -> journeyStops[i].isEndpoint }

    // 剖面：起点 → (每个**沿途**站：到站电量 → 充完电量) → 终点
    //
    // ⚠️ v0.14.0：端点用**最近那一趟**的真实电量（lastStartSoc/lastEndSoc），
    // 不用中位数 —— 否则就是「中位数起点 + 单次充电点 + 中位数终点」的三明治。
    //
    // ⚠️ v0.24.0：**优先用真实逐公里剖面**（服务端从 positions 的 162 万个采样点算出），
    // 没有时才回落到「端点 + 充电跳升」的插值 ——
    // 因为插值在山区的误差达 13 公里，会让人按错误的公里数规划补电。
    val profile = ArrayList<RouteSocPoint>(routeStops.size * 2 + 2)
    val real = socProfile?.takeIf { it.size >= 3 }
    if (real != null) {
        // 真实剖面：逐 5 公里的实测电量
        real.forEach { p ->
            profile += RouteSocPoint(p.km, p.soc.roundToInt())
        }
        // 充电跳升：把「充完的电量」插在对应公里处（图上看得见那一跳）
        routeStops.forEach { s ->
            s.socAfter?.let { profile += RouteSocPoint(s.kmAlongRoute, it, isCharge = true) }
        }
        profile.sortBy { it.km }
    } else {
        (template.lastStartSoc ?: template.startSoc)?.let { profile += RouteSocPoint(0.0, it) }
        for (s in routeStops) {
            // ⚠️ **v0.38.0（第 23 轮车主复审 P0-2）：用**到站电量**，不用插枪电量。**
            // 插值剖面在合并充电会话时会画出错的低点（实测最大差 57 个点）。
            (s.arrivalSoc ?: s.socBefore)?.let { profile += RouteSocPoint(s.kmAlongRoute, it) }
            s.socAfter?.let { profile += RouteSocPoint(s.kmAlongRoute, it, isCharge = true) }
        }
        (template.lastEndSoc ?: template.endSoc)?.let { profile += RouteSocPoint(runDistance, it) }
    }

    if (profile.size < 2) return null
    return RoutePlan(
        template, profile, routeStops, endpointStops,
        // ⚠️ v0.31.0：整条线路的爬升
        climbAscentM = routeAscentM,
        climbDescentM = routeDescentM,
        // ⚠️ v0.36.0：前提行要如实说「有没有做爬升修正」
        climbCorrectionApplied = climbModel != null,
        // ⚠️ v0.36.0：低温样本数（动态，不再写死）
        lowTempSamples = consumption?.lowTempSamples,
        // ⚠️ v0.36.0（C8）：模型自己的误差带（换算到最长那一跳）
        modelErrorPoints = consumption?.socErrorPoints(
            stops.maxOfOrNull { it.gapToNextKm ?: 0.0 } ?: 0.0,
            batteryCapacityKwh,
        ),
    )
}

/**
 * 中位数（偶数样本取两个中间值的平均，与教科书一致）。
 *
 * ⚠️ v0.14.0（口径审计 P1-4）：实现搬到 `com.teslamate.app.medianOf`，
 * 与 ChargeStats / ConsumptionModel 共用同一份 —— 以前这里有第四个实现。
 */
private fun medianOf(values: List<Double>): Double? = com.teslamate.app.medianOf(values)

/**
 * 找出要显示的线路模板。
 *
 * ⚠️ v0.18.0（长途自驾党 P0-1）：**可选择线路**。
 *
 * 原来 `pickRouteTemplate` 直接取 `templates.firstOrNull()` ——
 * 卡片永远显示「**上次**开的长途」，不是「我这次要开的那条」。
 * 出发前 5 分钟，App 替你猜一条线路是没用的。
 *
 * 现在：传了 [selectedKey] 就用它；没传才回落到「最近走过的那条」。
 */
fun pickRouteTemplate(
    templates: List<RouteTemplate>,
    selectedKey: String? = null,
): RouteTemplate? =
    selectedKey?.let { k -> templates.firstOrNull { it.key == k } } ?: templates.firstOrNull()

/**
 * 线路选择 chips（横向可滑）。
 *
 * ⚠️ v0.18.0（长途自驾党 P0-1）：多条线路时给用户**选**的能力。
 * 只在有 2 条以上线路时出现 —— 单一线路给选择器是噪音。
 */
@Composable
fun RoutePlanChips(
    templates: List<RouteTemplate>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (templates.size < 2) return
    val accents = LocalAccentColors.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // ⚠️ v0.20.0（车主 P1-4）：**末尾留白** ——
        // 原来末项会被卡片右边缘**竖着切掉一半**（只看得见一个字），
        // 用户分不清是「还有更多」还是渲染坏了。
        contentPadding = PaddingValues(end = 12.dp),
    ) {
        items(templates) { t ->
            val selected = t.key == selectedKey
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (selected) accents.energyBlue.copy(alpha = 0.16f) else accents.cardInset,
                // ⚠️ 选中态再加一道主色描边 —— 光靠底色深浅，在浅色模式下差别太弱。
                //
                // ⚠️ **v0.36.0（第 21 轮视觉评审 P2-6）：描边改**不透明**。**
                // 原来 `energyBlue.copy(alpha = 0.55f)` 压在选中底上只有 **2.17:1**
                // —— 低于非文字图形的 3:1，**这道描边自己就看不清**。
                border = if (selected) {
                    androidx.compose.foundation.BorderStroke(1.dp, accents.energyBlue)
                } else {
                    null
                },
                modifier = Modifier
                    // ⚠️ v0.20.0（a11y 评审 P0-3）：**用 selectable 而不是 clickable** ——
                    // 「选我要走的那条线」是这个 App 的核心功能，
                    // 而选中态原来只有底色 + 描边（**只靠颜色**，违反 WCAG 1.3.1），
                    // TalkBack 也不知道哪条被选中。
                    // `selectable` 给出 Role.Tab + selected 语义。
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(t.key) },
                    )
                    // ⚠️ 触控目标：原来 chip 高只有 28dp（16dp 文字 + 12dp 内距），
                    // 远低于 WCAG 2.5.8 的 44dp。
                    .heightIn(min = 44.dp)
                    .semantics(mergeDescendants = true) { },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ⚠️ v0.20.0（a11y 评审 P0-3）：**选中项加勾** ——
                    // 非颜色的选中标记（WCAG 1.3.1），色觉障碍用户也读得出。
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = accents.energyBlue,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        // ⚠️ **v0.36.0（第 21 轮一线车主复审 C5）：chip 要带公里数。**
                        //
                        // 原来只写「永丰社区 → 石化大道西」+「13 段」——
                        // 复审实测 5 个 chip 里 **4 个重名或近似**，
                        // 而「13 段 / 131 段」是**内部口径**，复审原话：「我不看段数」。
                        // 现在换成「起 → 止 · N 公里」，一眼能分出 616 / 255 / 6227 公里。
                        "${t.fromLabel.firstSegment() ?: t.fromLabel} → " +
                            "${t.toLabel.firstSegment() ?: t.toLabel} · " +
                            "${t.distanceKm.roundToInt()} 公里",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        // ⚠️ v0.20.0（a11y 评审 P0-3）：允许两行 ——
                        // 原来 maxLines=1，2 倍字号下截成「广昆高速 → 城…」。
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * ⚠️ v0.14.0（苹果 HIG P1 + 新手车主 P1-2）：**折叠态的一行摘要**。
 *
 * 「记录」页首屏不该被一整张线路规划卡吃掉 —— 车主打开「记录」多数是想看
 * 「我最近干了什么」。但把规划入口整个删掉又不行，
 * 出发前 5 分钟确实要它。
 *
 * 折中：默认一行，**结论句直接写在行里**（不折进去），
 * 点一下才展开完整剖面与站点。
 */
@Composable
fun RoutePlanSummaryRow(
    plan: RoutePlan,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** ⚠️ v0.18.0：全部线路模板（用于选择 chips）。 */
    templates: List<RouteTemplate> = emptyList(),
    /** ⚠️ v0.18.0：当前选中的线路 key。 */
    selectedKey: String? = null,
    /** ⚠️ v0.18.0：切换选中的线路。 */
    onSelectKey: (String) -> Unit = {},
) {
    val accents = LocalAccentColors.current
    val t = plan.template
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = accents.cardSurface,
        modifier = modifier
            .fillMaxWidth()
            .cardDepth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onClick),
                ) {
                    // ⚠️ **v0.34.0（第 21 轮一线车主复审 P2-3）：标题别塞两遍完整地址。**
                    //
                    // 往返线路的标题原来是「A → A」—— 同一串地址**重复两遍**，
                    // 读起来像 App 出 bug 了（复审原话：「我第一反应是 App 出 bug 了」）。
                    // 现在：往返只写一次 + 「（往返）」；单程写「A → B」。
                    // 末尾再挂总公里数，让「这条线有多长」一眼看到。
                    //
                    // ⚠️ **v0.36.0（第 21 轮视觉评审 P1-5）：去掉「线路余量表 ·」前缀。**
                    //
                    // 复审实测：标题在 360dp 屏上占 **2 行**，
                    // 而「线路余量表」这个技术名在展开卡里已经是 SectionCard 标题。
                    // 折叠态真正要认的是**这条线**（起止 + 公里数）。
                    val fromShort = t.fromLabel.firstSegment() ?: t.fromLabel
                    val toShort = t.toLabel.firstSegment() ?: t.toLabel
                    Text(
                        if (t.isRoundTrip) {
                            "$fromShort（往返）· ${t.distanceKm.roundToInt()} 公里"
                        } else {
                            "$fromShort → $toShort · ${t.distanceKm.roundToInt()} 公里"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // ⚠️ **v0.35.0（第 21 轮视觉评审 P1-6）：给记录页一个**真正的**视觉锚点。**
                    //
                    // 原来这一屏最大的数字是 **34sp**，而且在**折叠线以下**；
                    // 首屏的落点是一句 15sp 的灰字结论 —— **没有主角**。
                    //
                    // 现在把「**最长那一跳的公里数**」提成 **44sp 主角**：
                    // 它既是复审里车主说「我真的会读」的两个数之一，
                    // 也是这一屏唯一需要「一眼看到」的东西。
                    //
                    // ⚠️ 为什么不是把「实测满电续航」提成 44sp（评审的原建议）——
                    // 那张卡在折叠线**下面**，提上去会把**首屏**的主角挤到第二屏；
                    // 而且车主说出发前 5 分钟要读的是**最长一段**。
                    // 现在：记录页唯一 44sp = 最长那一跳；下面那张卡降到 26sp。
                    //
                    // ⚠️ **v0.36.0（第 21 轮视觉评审 P1-2）：大字号下**竖排**。**
                    //
                    // 复审实测 fs=2.0 @360dp 时右栏只剩 **113dp** ——
                    // 「公里 · 最长一段」和站名各折 2 行 = **4 行小字 vs 1 行大字**，
                    // 主角在自己的副标题里淹没了。
                    // 现在 fs ≥ 1.35 走竖排（数字独占一行，副标题全宽）。
                    val bigFont = LocalDensity.current.fontScale >= 1.35f
                    plan.longestHop?.let { hop ->
                        if (bigFont) {
                            Column {
                                Text(
                                    hop.km.roundToInt().toString(),
                                    fontSize = 44.sp,
                                    lineHeight = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.4).sp,
                                    style = MaterialTheme.typography.displayLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "公里 · 最长一段 · ${hop.fromName} → ${hop.toName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    hop.km.roundToInt().toString(),
                                    fontSize = 44.sp,
                                    lineHeight = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.4).sp,
                                    style = MaterialTheme.typography.displayLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "公里 · 最长一段",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "${hop.fromName} → ${hop.toName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-7）：决策句要短。**
                    //
                    // 原来这里是 85 字的一整句（折 4~9 行）；
                    // 现在 [RoutePlan.verdict] 只给**最长的一跳**（一行），
                    // 来源限定与全程最低挪到下面的细节行。
                    Text(
                        plan.verdict(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = accents.energyBlue,
                        fontWeight = FontWeight.Bold,
                    )
                    // ⚠️ **v0.36.0（第 21 轮一线车主复审 C3）：折叠态也要给「这一跳无解」。**
                    //
                    // 复审实测：折叠态的 44sp 主角**恰好就是**「充满也到不了」的那一跳
                    //（修文县 → G210，351 公里，要 88 个点 + 20 保底 = 108 > 100），
                    // 而折叠态只说「估算剩 8%」——
                    // 用户会读成「到了那儿剩 8%，有点紧但能到」。
                    //
                    // 而这句只在展开态第 28 行的处方行里 —— **点开之前根本看不到**。
                    plan.longestHop?.let { hop ->
                        val req = hop.requiredSoc
                        if (req != null && req > 100) {
                            Text(
                                buildString {
                                    append("这一跳要 ").append(hop.needPct).append(" 个点")
                                    append(" · 充满也到不了 —— 得在前面加一站")
                                    // ⚠️ **v0.39.0（第 23 轮车主复审 P2-2）：还有几跳无解要说清。**
                                    // 折叠态只显示**最长那一跳**的无解提示，
                                    // 而实测那条线有 **2 跳**无解 —— 另一跳被藏起来了。
                                    val others = plan.impossibleHopCount - 1
                                    if (others > 0) {
                                        append(" · 这条线上还有 ").append(others).append(" 跳无解")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = accents.warnAmberText,
                            )
                        }
                    }
                    plan.verdictDetail().takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ⚠️ **v0.34.0（第 21 轮视觉评审 2.4）：总爬升**移出折叠态**。**
                    //
                    // 复审原话：「全程爬升总计在服务区里没用 ——
                    // 我要的是"我前面这 200 公里爬多少米"，不是全程总计」。
                    // 而且它是记录页首屏里**唯一带 5 位数的数字**，会在一片灰字里
                    // 产生局部的视觉吸附。现在只在展开卡里给（逐段爬升才是决策量）。
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "展开线路余量表",
                    modifier = Modifier
                        // ⚠️ **v0.30.0（第 19 轮 HIG 评审 P1-6）：命中区 22dp → 24dp。**
                        // WCAG 2.5.8（Target Size Minimum）要求 ≥24dp；
                        // 22dp 差 2dp 不达标。
                        .size(24.dp)
                        .clickable(onClick = onClick),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⚠️ v0.18.0（长途自驾党 P0-1）：**线路选择 chips** ——
            // 卡片永远显示「上次开的那条」没用，出发前要选的是「我这次要开的那条」。
            // 只在有 2 条以上线路时出现。
            if (templates.size >= 2) {
                Spacer(Modifier.height(8.dp))
                RoutePlanChips(
                    templates = templates,
                    // ⚠️ 没手动选过时，**当前显示的那条**就是选中态 ——
                    // 否则用户看到卡片显示 A 路，chips 里却没有 A 路被选中。
                    selectedKey = selectedKey ?: plan.template.key,
                    onSelect = onSelectKey,
                )
            }
        }
    }
}

/**
 * 路线余量表卡片。
 *
 * ⚠️ v0.11.0（长途自驾党 P0-1）：这是「出发前 5 分钟」真正要看的那张表。
 *
 * 布局刻意做成**一张卡**而不是一个独立页面：
 * 它是「记录」的延伸（数据都来自历史记录），不是平级功能。
 */
@Composable
fun RoutePlanCard(
    plan: RoutePlan,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
) {
    val accents = LocalAccentColors.current
    val t = plan.template
    // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-1）：每站的证据行默认**收起**。**
    //
    // 实测那条川藏滇线有 **36 个站**；原来每站 5~6 行，
    // fs=2.0 时整张卡 **≈ 23.7 屏** —— 出发前 5 分钟在 23 屏里找「下一站」，
    // 必然放弃。现在默认只给决策行 + 处方行，证据收进「详情」。
    val expandedStops = remember { mutableStateMapOf<Int, Boolean>() }

    SectionCard("线路余量表", modifier) {
        if (onCollapse != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "收起",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onCollapse)
                        // ⚠️ **v0.36.0（第 21 轮视觉评审 P2-8）：命中区 24dp → 44dp。**
                        // 原来只有 `vertical = 4.dp`，高 = 16 + 8 = **恰好 24dp**，
                        // 而且无触感、无动效 —— 看起来像静态文字。
                        .padding(vertical = 14.dp),
                )
                Spacer(Modifier.weight(1f))
            }
        }
        Text(
            plan.conclusion(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // ⚠️ **v0.36.0（第 21 轮视觉评审 P1-1）：展开态也要保留**视觉锚点**。**
        //
        // 复审实测：`RoutePlanCard` 全文最大字号 = bodyMedium 15sp ——
        // **点开的瞬间，44sp 主角掉到 12sp**（折叠↔展开是二选一），
        // 这叫**层级断层**：iOS 的 disclosure 约定是展开后保留锚点。
        //
        // 现在展开态也给一个 **26sp** 的次级主角（复用 `BigStat` 的 `mid` 档，
        // 层级比 44 : 26 = 1.69×）。
        plan.longestHop?.let { hop ->
            BigStat(
                hop.km.roundToInt().toString(),
                "公里 · 最长一段 · ${hop.fromName} → ${hop.toName}",
                mid = true,
            )
        }
        // ⚠️ v0.14.0（长途自驾党 P0-1）：**决策句放在图的上方** ——
        // 出发前 5 分钟，读者只读这一行；图是证据，不是结论。
        Text(
            plan.verdict(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = accents.energyBlue,
        )
        // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-7）：细节拆出来。**
        // 决策句一行说清「最长那一跳」；来源限定与全程最低是**证据**，单独一行。
        plan.verdictDetail().takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⚠️ **v0.34.0（第 21 轮一线车主复审 P2-3）：站间距分布只在展开卡里给。**
        // 折叠态放不下（它和决策句加起来会折 8~9 行）。
        plan.stationRhythm().takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⚠️ **v0.32.0（第 20 轮规划复审 P2-1）：卡片级前提，只说一次。**
        //
        // 原来这段前提挂在**每个沿途站**下面 —— 36 站的那张卡把同一句话重复 37 遍。
        // 现在共同前提（未做气温修正）提到卡片级；**逐段**的档位信息留在各站那一行
        // （因为各段速度不同、命中的档也不同）。
        // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-8）：前提行必须**条件化**。**
        //
        // 原来这句是**静态断言**：「已按实测爬升系数做加性修正」——
        // 而爬升模型**可能加载失败**（`climbModel = null`，服务端 500 或超时）。
        // 那时界面仍然宣称做了爬升修正 —— **一句会骗人的前提**。
        //
        // ⚠️ **第 21 轮视觉评审 P1-2：20% 保底只说一次。**
        // 原来这里说一次、每站说两次（全卡 36 站 = 72 次）。
        // 现在只在**逐站**那一次说。
        // ⚠️ **v0.36.0：低温样本数改成**实测值**，不再写死。**
        //
        // 原来写死「本车 ≤10°C 只有 1 条样本」—— 当时是对的，
        // 但它是**硬编码**：等冬天跑出更多低温行程，这句话就成了假话。
        val lowTempTxt = plan.lowTempSamples?.let { n ->
            if (n == 0) "未做气温修正（本车没有 ≤10°C 的行程样本）"
            else "未做气温修正（本车 ≤10°C 有 $n 条行程样本，不足以支撑低温修正）"
        } ?: "未做气温修正"
        Text(
            "余量按各段真实均速取本车实测 Wh/km；" +
                (if (plan.climbCorrectionApplied) {
                    "已按实测爬升系数做加性修正"
                } else {
                    "未做爬升修正（爬升系数不可用）"
                }) +
                "；" + lowTempTxt +
                // ⚠️ **v0.36.0（第 21 轮一线车主复审 C8）：把模型的**误差带**说出来。**
                //
                // 复审原话：「这个 App 要么给区间，要么把上次实际值并排放。
                // 否则它比官方更不可信 —— 因为它看起来更精确。」
                //
                // 这里的数是**模型自己的残差 90 分位**换算出来的，不是拍脑袋写的。
                // ⚠️ **v0.39.0（第 23 轮数据自洽性审计 P2-5）：口径写准，别夸大。**
                //
                // 这个 ±N 是**模型自身残差**的 90 分位，
                // 而**端到端**的到站误差还包含爬升修正的误差 ——
                // 黑盒审计实测那条线：偏差中位 +4.5 点、最大 **+26 点**（偏乐观）。
                //
                // 所以只说「模型残差」，不说「实测误差」——
                // 否则就变成了**低报**风险。
                (plan.modelErrorPoints?.let { "；余量估计的模型残差约 ±$it 个点" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // ⚠️ Compose 的 Text 不解析 Markdown —— 之前写了 **粗体**，
            // 结果界面上直接出现两个星号。
            "按这条线路上真实发生过的充电推算（最近一次 ${t.lastDrivenAt?.fmtDate() ?: "—"}）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-4）：展开后总爬升不能消失。**
        //
        // 原来「爬升 51,791 米 · ≈ 5.9 座珠峰」**只在折叠态**渲染
        // （`RoutePlanSummaryRow` 是唯一渲染点）——
        // 展开卡片后整行消失，与它自己的注释「放在次级行（不是主角数字）」矛盾。
        if (plan.climbAscentM >= 200) {
            Text(
                "整条线路爬升 ${plan.climbAscentM} 米（下降 ${plan.climbDescentM} 米）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        // 余量剖面：X 轴是路线公里数，Y 轴是电量。
        // 剖面里的**跳升**就是充电 —— 不用另加图例，一眼能看懂。
        //
        // ⚠️ v0.14.0（口径审计 P1-3）：加**20% 保底线**。
        // 这张图唯一的决策阈值就是 20%，而原来它既不在轴上也不在网格线上 ——
        // niceTicks(84) 给出的刻度是 0 / 50，读者只能靠底部文字反推位置。
        val profileKmMax = plan.profile.maxOfOrNull { it.km } ?: t.distanceKm
        val weights = plan.profile.map { it.km.toFloat() }
        LineChart(
            values = plan.profile.map { it.soc.toFloat() },
            color = accents.energyBlue,
            fill = true,
            yUnit = "%",
            xWeights = weights,
            xLabels = listOf(
                "0",
                "${(profileKmMax / 2).roundToInt()}",
                "${profileKmMax.roundToInt()}",
            ),
            referenceValue = 20f,
            referenceLabel = "20% 保底",
            contentDescription = "余量剖面：从 ${plan.profile.firstOrNull()?.soc ?: "?"}% 到 ${plan.profile.lastOrNull()?.soc ?: "?"}%，" +
                "沿途 ${plan.stops.size} 次充电，20% 保底线已标出",
        )
        Text(
            "余量 (%) · 横轴 = 路线公里数 · 跳升处即充电",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ⚠️ v0.20.0（车主 P0-2）：**站点区永远渲染** ——
        // 原来 `if (stops.isNotEmpty() || endpointStops.isNotEmpty())` 让整块消失，
        // 于是「没有补电点」和「这张卡没这栏」在界面上**长得一模一样**。
        // 用户根本看不出自己看到的是 0 个站点还是渲染漏了。
        //
        // 现在无站时也把这一栏画出来，明说「没有可参考的补电点」。
        if (plan.stops.isEmpty() && plan.endpointStops.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "沿途充电站",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "这条线路上没有你的充电记录 —— 没有可参考的补电点。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            if (plan.stops.isNotEmpty()) {
                Text(
                    "沿途充电站",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ⚠️ **v0.39.0（第 23 轮车主复审 P2-3）：**需要处理的那几跳**放在列表最上面。**
                //
                // 复审原话：「出发前 5 分钟要在 15 屏里找"下一站"。」
                // 全量列表不删（数据一点没丢是原则），但**要动手的那几跳**必须一眼能找到。
                plan.actionableHops.takeIf { it.isNotEmpty() }?.let { hops ->
                    Text(
                        "需要注意的 ${hops.size} 跳",
                        style = MaterialTheme.typography.labelMedium,
                        color = accents.warnAmberText,
                    )
                    hops.forEach { h ->
                        Text(
                            buildString {
                                append(h.fromName).append(" → ").append(h.toName)
                                append(" · ").append(h.km.roundToInt()).append(" 公里")
                                val req = h.requiredSoc
                                when {
                                    req != null && req > 100 ->
                                        append(" · 充满也到不了（要 ").append(h.needPct).append(" 个点）")
                                    h.estSoc != null && h.estSoc < RESERVE_SOC ->
                                        append(" · 到站估 ").append(h.estSoc).append("%")
                                    h.realSoc != null && h.realSoc < RESERVE_SOC ->
                                        append(" · 上次实测只剩 ").append(h.realSoc).append("%")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = accents.warnAmberText,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                plan.stops.forEachIndexed { stopIdx, s ->
                    // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-1）：证据行默认**收起**。**
                    //
                    // 实测那条川藏滇线有 **36 个站**。原来每站 5~6 行
                    // （fs=2.0 时 36 × 436dp ≈ **23.7 屏**）——
                    // 出发前 5 分钟要在 23 屏里找「下一站」，**必然放弃**。
                    //
                    // 现在默认只给**决策行 + 处方行**（各 1 行），
                    // 「典型功率 / 折算分钟 / 爬升 / 档位」这些**证据**收进「详情」。
                    val open = expandedStops[stopIdx] == true
                    // ⚠️ **v0.36.0（第 21 轮视觉评审 P1-3）：照抄仓库已定型的 `CollapsibleSection` 模式。**
                    //
                    // 原来这里是**降级版**：箭头瞬时替换、内容硬切、无触感、
                    // 无语义状态、`contentDescription` 还把站名**念两遍**
                    // （相邻 Text 已经念过一次）。
                    // 而仓库的 `CollapsibleSection`（`Widgets.kt:879`）6 项全都有 —— **零新依赖**。
                    val stopCtx = LocalContext.current
                    val stopArrow by animateFloatAsState(
                        targetValue = if (open) 180f else 0f,
                        animationSpec = tween(Motion.ChevronRotate, easing = FastOutSlowInEasing),
                        label = "stopArrow",
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            // 起点处的站标「起点」，比「0 km」好懂
                            if (s.kmAlongRoute < 1.0) "起点" else "${s.kmAlongRoute.roundToInt()} 公里",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(62.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // ⚠️ **整行可点**（iOS disclosure 的惯例：点标题就展开）——
                                // 命中区比只有 24dp 的图标**大得多**，且不增加卡片高度。
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        Haptics.tick(stopCtx)
                                        if (open) expandedStops.remove(stopIdx) else expandedStops[stopIdx] = true
                                    }
                                    .semantics(mergeDescendants = true) {
                                        stateDescription = if (open) "已展开" else "已折叠"
                                    },
                            ) {
                                Text(
                                    s.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Filled.ExpandMore,
                                    // ⚠️ contentDescription = null：站名已由相邻 Text 提供，
                                    // 再写一遍会让 TalkBack **念两遍**
                                    // （同 `Widgets.kt:915-921` 写明「不要再犯」的同款错误）。
                                    contentDescription = null,
                                    modifier = Modifier
                                        // ⚠️ v0.30.0（HIG P1-6）：命中区 ≥24dp（WCAG 2.5.8 AA）。
                                        .size(24.dp)
                                        // ⚠️ v0.36.0：箭头**旋转**而不是瞬时替换
                                        // （同 `CollapsibleSection` 的做法）。
                                        .graphicsLayer { rotationZ = stopArrow },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // ── 决策行 ──────────────────────────────────────────
                            //
                            // ⚠️ **v0.34.0（第 21 轮一线车主复审 P0-1）：估算与**真值**并排。**
                            //
                            // 复审的原话：
                            // > 「App 手里有真值却不用 —— 它把真值画进曲线，
                            // > 却拿一个模型估算摆在旁边，还只给估算。」
                            // > 「一个估算没有真值在旁边，等于没有。」
                            //
                            // 实测那条川藏滇线：App 说「到下一站剩 -6%」，
                            // 而 2026-10-06 那趟**实际到站剩 13%** —— 差 19 个点。
                            // 并排放出来，用户立刻知道该信哪个。
                            //
                            // ⚠️ **第 21 轮视觉评审 P0-1：负电量不能当读数显示。**
                            // 电池的 SOC 不可能是负的 —— 「剩 -6%」在物理上不存在，
                            // 读者只会以为 App 算错了。负数改说成「还差 N 个点」。
                            val rawSoc = s.socAtNextStop
                            s.gapToNextKm?.let { gap ->
                                // ⚠️ **v0.39.0（第 23 轮车主复审 C7 + 视觉评审 P1-4）：
                                // **处方每一站都可见，但不新增行。**
                                //
                                // 原来：决策行 1 行 + 处方行 1 行（只有 11/35 跳有）⇒ 3~4 行/站，
                                // 36 站在 fs=2.0 时是 **15.5 屏**。
                                // 复审原话：「我站在桩前的那个问题永远是"充到多少"，
                                // 不是"能不能到"。24 跳现在什么都不给。」
                                //
                                // 现在：**固定 2 行/站** ——
                                //   L1（决策）：下一站 · 里程 · **充到多少**
                                //   L2（证据）：估算 · 真值
                                // 处方并进 L1 ⇒ 每站都看得见，版面还少了一半。
                                val need = s.gapNeedPct
                                val req = s.requiredSocHere
                                val impossible = req != null && req > 100
                                val l1 = buildString {
                                    append("下一站 ")
                                    s.nextStopName?.let { append(it).append(" · ") }
                                    append(gap.roundToInt()).append(" 公里")
                                    if (need != null && req != null) {
                                        append(" · ")
                                        if (impossible) {
                                            append("充满也到不了 · 得在前面加一站")
                                        } else {
                                            append("充到 ").append(req).append("%")
                                        }
                                    }
                                }
                                Text(
                                    l1,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (impossible) {
                                        accents.warnAmberText
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                // ── L2：估算 vs 真值 ─────────────────────────────────
                                val l2 = buildString {
                                    rawSoc?.let { soc ->
                                        if (soc >= 0) {
                                            append("估 ").append(soc).append("%")
                                            if (soc < RESERVE_SOC) {
                                                append(" ⚠ 低于 ").append(RESERVE_SOC).append("% 保底")
                                            }
                                        } else {
                                            append("估：到不了（还差 ").append(-soc).append(" 个点）")
                                        }
                                    }
                                    s.nextStopRealSoc?.let { real ->
                                        if (isNotEmpty()) append(" · ")
                                        append("实测 ").append(real).append("%")
                                        // ⚠️ **v0.38.0（第 23 轮车主复审 P1-1）：危险方向的提示。**
                                        //
                                        // 原来那句「比上次实测低 N 个点」的判据是 `估 − 真 ≤ −3`，
                                        // 即**只在模型偏保守（无害方向）时触发** ——
                                        // 实测 7/35 跳触发，而**会让人做错决定的 2 跳
                                        // （估 21/真 17、估 24/真 16）一次都没触发**。
                                        //
                                        // 现在改成：**估 ≥ 保底 但真 < 保底** → 直接告警。
                                        // 这条判据已被本机验证：11 个「低于 20% 保底」告警 **11/11 成立**、
                                        // 误报 **0** —— 所以它比「差值门槛」可信得多。
                                        if (real < RESERVE_SOC && (rawSoc ?: 0) >= RESERVE_SOC) {
                                            append(" ⚠ 实测也低于保底")
                                        }
                                    }
                                }
                                if (l2.isNotEmpty()) {
                                    Text(
                                        l2,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if ((rawSoc ?: 100) < RESERVE_SOC ||
                                            (s.nextStopRealSoc ?: 100) < RESERVE_SOC
                                        ) {
                                            accents.warnAmberText
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                            // ⚠️ **v0.36.0（第 21 轮一线车主复审 C4）：终点前那一段也要给余量。**
                            //
                            // 最后一个站之后往往还有 **146 公里**（6227 公里那条）/
                            // **88 公里**（616 公里那条），而终点不是充电站 →
                            // `gapToNextKm` 为 null → 那一段以前**一个字都不显示**。
                            s.distanceToEndKm?.let { endKm ->
                                Text(
                                    buildString {
                                        append("距终点 ").append(endKm.roundToInt()).append(" 公里")
                                        s.estSocAtEnd?.let { soc ->
                                            // ⚠️ **v0.38.0（第 23 轮车主复审 P0-1）：负数不显示成百分比。**
                                            // 「估到终点剩 -1508%」在物理上不存在，
                                            // 读者只会以为 App 算坏了 ——
                                            // 与「到下一站」的负值同一套说法：「还差 N 个点」。
                                            if (soc >= 0) {
                                                append(" · 估到终点剩 ").append(soc).append("%")
                                                if (soc < RESERVE_SOC) {
                                                    append(" ⚠ 低于 ").append(RESERVE_SOC).append("% 保底")
                                                }
                                            } else {
                                                append(" · 到终点还差 ").append(-soc).append(" 个点")
                                            }
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if ((s.estSocAtEnd ?: 100) < RESERVE_SOC) {
                                        accents.warnAmberText
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            // ── 证据行（默认收起）────────────────────────────────
                            //
                            // ⚠️ **v0.36.0（第 21 轮视觉评审 P1-3）：展开/收起要有过渡。**
                            // 原来 `if (open) { … }` 是**硬切** —— 内容瞬间出现/消失、
                            // 父容器高度瞬间跳变，把下方所有内容顶下去再弹回来。
                            // 现在照抄 `CollapsibleSection` 的动画规格（`Motion` token）。
                            AnimatedVisibility(
                                visible = open,
                                enter = fadeIn(Motion.FadeIn) + expandVertically(
                                    tween(Motion.ExpandHeight, easing = FastOutSlowInEasing),
                                ),
                                exit = fadeOut(tween(Motion.ExpandFadeOut)) + shrinkVertically(
                                    tween(Motion.ExpandHeight, easing = FastOutSlowInEasing),
                                ),
                            ) {
                                // ⚠️ v0.14.0（长途自驾党 P0-2）：到站多少 → 充到多少。
                                val detail = buildString {
                                    // ⚠️ **v0.38.0（P0-2）：「到站 X%」用**到站电量**。**
                                    // 用插枪电量（socBefore）时，合并过的站会差最多 57 个点 ——
                                    // 而决策行用的是到站电量 ⇒ **同一个站两行打架**。
                                    (s.arrivalSoc ?: s.socBefore)?.let { append("到站 $it%") }
                                    s.socAfter?.let {
                                        if (isNotEmpty()) append(" → ")
                                        append("充到 $it%")
                                    }
                                    s.typicalPowerKw?.let {
                                        if (isNotEmpty()) append(" · ")
                                        append("典型 ${it.roundToInt()} kW")
                                    }
                                    s.minutesToTarget?.let {
                                        if (isNotEmpty()) append(" · ")
                                        // ⚠️ **v0.30.0（长途自驾党 P0-5）：「约 N 分钟」必须标出它是**折算**。**
                                        //
                                        // ⚠️ **v0.32.0（第 20 轮规划复审 P2-5）：折算与实测的偏差中位是 −9%，**
                                        // 但区间是 **−58%（严重低估）~ +80%（高估）** ——
                                        // 真正危险的不是高估，是**低估**：
                                        // 黄金坪那个站说「典型 4 kW · 约 257 分钟」，
                                        // 同一场实测 **617 分钟** —— **低估 58%**。
                                        append("约 $it 分钟（按典型功率折算）")
                                    }
                                    // ⚠️ **v0.31.0：这一段爬了多高。**
                                    s.gapAscentM?.let { a ->
                                        if (a >= 50) {
                                            if (isNotEmpty()) append(" · ")
                                            append("这段爬了 $a 米")
                                        }
                                    }
                                    // ⚠️ **v0.33.0：爬升的能耗影响。**
                                    //
                                    // ⚠️ **v0.34.0（第 21 轮视觉评审 P1-3）：同时给**百分点**。**
                                    // 只给 Wh/km 的话，读者没法换算成「会让我少多少电」——
                                    // 而那才是「该不该提前充」的依据。
                                    s.gapClimbWhPerKm?.let { cw ->
                                        if (kotlin.math.abs(cw) >= 5.0) {
                                            if (isNotEmpty()) append(" · ")
                                            // ⚠️ **v0.38.0（第 23 轮数据自洽性审计 P2-2）：单位统一成 kWh/100km。**
                                            // 原来这里写「Wh/km」而同一屏的档位行写「kWh/100km」——
                                            // 同一个量纲在**同一屏**用两种写法，读者得自己换算
                                            // （−10 Wh/km 与 16.9 kWh/100km 并排）。
                                            // 项目自己的注释写着「Wh/km 在中文语境里像外星语」。
                                            append(
                                                "爬升修正 ${if (cw > 0) "+" else ""}" +
                                                    "${cw.fmtKwh100()} kWh/100km"
                                            )
                                            s.gapClimbKwh?.let { kwh ->
                                                s.gapClimbPct?.let { pct ->
                                                    append(
                                                        "（多耗 ${kwh.fmt(1)} 度 ≈ " +
                                                            "${pct.roundToInt()} 个百分点）"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (isNotEmpty()) append(" · ")
                                    // ⚠️ v0.14.0：只充过 1 次时明说，别让「典型」显得有统计支撑。
                                    append(if (s.sessions <= 1) "仅 1 次实测" else "${s.sessions} 次")
                                }
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // ⚠️ **v0.39.0（第 23 轮车主复审 C7）：处方**长版**在这里。**
                                //
                                // 决策行给的是短版（「充到 70%」）—— 每站都看得见；
                                // 这里是完整版：这一跳要几个点、误差带、上次实际充到多少。
                                s.gapNeedPct?.let { need ->
                                    val req = s.requiredSocHere ?: (need + RESERVE_SOC)
                                    val txt = if (req > 100) {
                                        "这一跳要 $need 个点 · 充满也到不了 —— 得在前面加一站"
                                    } else {
                                        // ⚠️ v0.36.0（视觉评审 P2-5）：加逗号 ——
                                        // 「留 20% 保底至少充到 108%」读起来是两个动作黏在一起。
                                        //
                                        // ⚠️ **v0.36.0（车主复审 C8）：给**误差带**。**
                                        // 复审原话：「要么给区间，要么把上次实际值并排放」——
                                        // 这里的误差是**模型自己的残差 90 分位**，不是拍脑袋的数。
                                        "这一跳要 $need 个点 · 留 $RESERVE_SOC% 保底，" +
                                            "至少充到 $req%" +
                                            (s.gapErrorPoints?.let { "（±$it 个点）" } ?: "") +
                                            (s.socAfter?.let { "（上次充到 $it%）" } ?: "")
                                    }
                                    Text(
                                        txt,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // ⚠️ **v0.34.0（第 21 轮视觉评审 P0-2）：档位标签与它后面的数字必须**同源**。**
                                //
                                // 原来这里显示的是「0–60 档 26.7 kWh/100km」——
                                // 而 26.7 = 基数 17.8 + 爬升修正 8.9。
                                // 读者知道档位表的话，会认为 App 的档位值**错了 50%**。
                                //
                                // 现在：**基数**跟着档名走，**总数**单独标「含爬升修正后 X」。
                                s.gapBucketLabel?.let { label ->
                                    val speedTxt = s.assumedSpeedKph?.roundToInt()?.let { "$it km/h" } ?: "该段"
                                    val base = s.assumedWhPerKm
                                    val total = s.assumedWhPerKmTotal
                                    Text(
                                        "这段均速 $speedTxt · $label 档" +
                                            "${base?.fmtKwh100() ?: "—"} kWh/100km" +
                                            (total?.let { t ->
                                                if (base != null && kotlin.math.abs(t - base) >= 0.5) {
                                                    "（含爬升修正后 ${t.fmtKwh100()}）"
                                                } else {
                                                    ""
                                                }
                                            } ?: "") +
                                            (if (s.gapSpeedBorrowed) "（借用邻档）" else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // ⚠️ v0.14.0（口径审计 P0-4）：终点处的充电**单独列出**，不混进「沿途」。
            if (plan.endpointStops.isNotEmpty()) {
                Text(
                    "到达后充过电",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plan.endpointStops.forEach { s ->
                    Text(
                        "${s.name}（终点）" +
                            (s.socBefore?.let { " · 到达时 $it%" } ?: "") +
                            (s.socAfter?.let { "，充到 $it%" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        plan.lowestPoint?.let { (km, soc) ->
            Spacer(Modifier.height(4.dp))
            // ⚠️ **v0.32.0（第 20 轮规划复审 P0-1）：公里与电量必须来自**同一个点**。**
            //
            // 原来这里写的是 `plan.lowestSoc`（全局最低）配 `plan.belowFloorKm`
            // （第一个跌破 20% 的公里）—— **两个不同的点被拼在了一起**：
            // 6227 公里那条会同时出现
            // 「全程最低 5%（第 1237 公里）」与「全程最低 5%（在第 227 公里）」。
            //
            // 这正是 v0.29.0 P0-3 修过的那类错，**只是在另一处又犯了一次**。
            // 现在两处都用 [lowestPoint]（公里与电量同源）。
            Text(
                if (soc < 20) {
                    "全程最低 $soc%（在第 ${km.roundToInt()} 公里，低于 20% 保底）"
                } else {
                    "全程最低 $soc%（在第 ${km.roundToInt()} 公里）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (soc < 20) accents.warnAmberText else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
