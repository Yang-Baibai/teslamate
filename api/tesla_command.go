package main

// ⚠️ **v0.66.0（第 33 轮「车辆控制下沉」）**：Tesla 车控命令通道。
//
// ## 为什么这个文件存在
//
// 车主诉求：「我希望接下来给这个 app 加入车辆控制的功能，
// 并且在 PC 上提供标准的 MCP 或 CLI 服务，让我电脑上的 Hermes
// 也可以完整的查看车辆数据和操控车辆。」
//
// 并且明确：「**Housekeeper 中的车辆控制解耦掉，正确的应该是车辆控制在此项目中。
// Housekeeper 调用这个项目。**」
//
// ⇒ 车控能力归 **teslamate**，Housekeeper 将来改成调 teslamate 的 API。
//
// ## 通道是怎么走的
//
// Tesla 的「签名命令」（2021 年后的 Model 3/Y 必须）不能由业务代码直接发：
// 它要用**注册在 Tesla partner account 上的那把私钥**做 ECDSA 签名，
// 再用 Tesla 下发的 **domain session key** 加密 payload。
// Tesla 官方把这套协议实现成了独立的 sidecar：**`tesla/vehicle-command`**。
//
// 完整链路：
//
//	teslamate(Go API)  ──①拿 access_token──▶  Tesla token 端点
//	        │
//	        └──②带 Bearer token + JSON body──▶  command-proxy(:4443)
//	                    │                              │
//	                    │                        ③用 /data 里的车辆私钥签名
//	                    ▼                              │
//	              signed_command ─────────────────────▶  Tesla Fleet API
//
// ① 复用 `tesla.go` 里已有的 `ensureToken`（access_token 8 小时有效，剩 1 小时自动刷新）。
// ② proxy 的 HTTPS 用**自签证书**（公钥就是注册到 partner account 的那把），
//    所以客户端要信自己的 CA（`TESLA_COMMAND_PROXY_CA`）。
// ③ 车辆私钥 + TLS 证书放在 `teslamate_command_keys` 卷里（**不入库**）。
//
// ## 令牌的口径（⚠️ 一次性 refresh_token）
//
// 中国区的 `refresh_token` 是**一次性**的：每次刷新返回新的，旧的立刻失效。
// teslamate 的 `tesla.go` 已经处理好了轮换与落盘（回写 `.env`）。
//
// ⚠️ **但 scope 是授权时定的**：只读授权（`openid offline_access vehicle_device_data`）
// 换来的令牌**没有 `vehicle_cmds`** ⇒ 发车控命令会 403。
// 要用车控，令牌必须是**扩了 scope 的那次授权**拿到的。
//
// ## 风险分级
//
// 本项目是**自用单用户**（API token 就是边界），所以**不做 TOTP** ——
// 那是 Housekeeper 面向多用户的设计。
// 但**高危动作仍然分级**，App 与 MCP 都要据此决定要不要二次确认。
//
//	high   ：解锁 / 开合尾门 / 远程启动 —— 会动车、会开孔、会让人上车
//	medium ：空调温度、充电上限、充电电流、开始/停止充电、代客模式、唤醒
//	low    ：鸣笛、闪灯
//	medium ：锁车、关空调、哨兵模式（**都是动车动作** —— 与 Housekeeper 的
//	         MEDIUM_RISK_ACTIONS 逐条对齐）
//
// ⚠️ **唤醒（wake_up）单独计价**（Tesla 把它当 10 个单位），且会把车叫醒 ——
// 所以它虽然是 medium，但 UI 上要提示「会消耗电量、可能计费」。

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// --------------------------------------------------------------------------
// 风险级别
// --------------------------------------------------------------------------

const (
	teslaCmdRiskLow    = "low"
	teslaCmdRiskMedium = "medium"
	teslaCmdRiskHigh   = "high"
)

// 参数取值范围（与 Tesla App 的舒适区一致；车端会拒越界值）。
//
// ⚠️ **v0.87.0：这块从 `const` 改成 `var`** —— 因为参数规格
// （`teslaCommandParamSpec`）要把范围**放进 JSON**（喂给 App 的输入控件
// 与 MCP 的 JSON Schema），而 Go **不能对常量取地址**。
//
// 改成 var 之后，「校验用的数字」与「对外公布的数字」是**同一份**，不会漂移。
var (
	teslaChargeLimitMin = 50
	teslaChargeLimitMax = 100
	teslaTempMin        = 15
	teslaTempMax        = 28
	teslaAmpsMin        = 5
	teslaAmpsMax        = 48
	teslaCredMinLen     = 4
	teslaCredMaxLen     = 20
	// ⚠️ **v0.87.0**：座椅位置与加热档位的取值范围。
	//
	// 官方锚点：teslamotors/vehicle-command
	//   · `pkg/proxy/command.go` → `seatPositions` 切片（9 个座位，下标 0~8）
	//   · `pkg/vehicle/climate.go` → `type Level int`（LevelOff/Low/Med/High = 0/1/2/3）
	teslaSeatPositionMin = 0
	teslaSeatPositionMax = 8
	teslaSeatLevelMin    = 0
	teslaSeatLevelMax    = 3
)

// teslaCommandParamOption 一个**具体取值**及其人话标签。
//
// ⚠️ **为什么连取值标签都要给**：车控参数不是「随便一个整数」，
// 它有官方枚举（例：座椅加热的档位 0=关 / 1=低 / 2=中 / 3=高）。
// 只给「范围 0~3」的话，调用方（LLM / 人）不知道 0 是什么意思
// ⇒ 要么猜，要么试错 —— 而车控**不可逆**。
type teslaCommandParamOption struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

// teslaCommandParamSpec 一个参数的严谨描述。
//
// ⚠️ **v0.87.0：参数规格搬进服务端**。
//
// 「参数合法范围」与「参数怎么填」是**同一个知识**，抄成两份必然漂移
// （App 一份 Kotlin 表、MCP 一份描述、服务端一份校验）。
// 而车控不可逆 —— 漂移的代价是「把「关」发成「高档」。
//
// ⇒ 现在服务端是唯一事实源：
//
//	· 服务端校验读它（`buildTeslaCommandBody`）
//	· App 的输入控件读它（标签、范围、可选值）
//	· MCP 的 JSON Schema 读它（LLM 照着填，零失误）
type teslaCommandParamSpec struct {
	Name string `json:"name"`
	// Type：int / bool / password / string
	Type     string `json:"type"`
	Required bool   `json:"required"`
	Min      *int   `json:"min,omitempty"`
	Max      *int   `json:"max,omitempty"`
	// Options 取值清单（int 参数的每个合法取值 + 它的人话标签）。
	Options []teslaCommandParamOption `json:"options,omitempty"`
	// Label 界面上的短标签（App 的输入控件用这个）。
	Label string `json:"label"`
	// Description 完整说明（喂给 LLM 的 JSON Schema 用这个）。
	Description string `json:"description"`
}

// teslaCommandSpec 一个可下发的动作的静态描述。
type teslaCommandSpec struct {
	Action      string `json:"action"`
	Endpoint    string `json:"-"`
	Description string `json:"description"`
	// ⚠️ **v0.76.0**：动作**做完之后**的一句话（「锁车」→「已锁车」）。
	// 结果条要说的是「现在的样子」，不是「我干了什么」。
	Done         string   `json:"done"`
	Risk         string   `json:"risk"`
	Params       []string `json:"params"`
	WakesVehicle bool     `json:"wakes_vehicle"`
	// ⚠️ **v0.87.0**：参数的**严谨规格**（类型、范围、可选值、人话标签）。
	//
	// 与 `Params` 并存，职责不同：
	//   · `Params` 回答「要哪些参数名」
	//   · `ParamSpecs` 回答「每个参数该怎么填」
	//
	// 两者必须**逐条对齐**（有测试钉死）—— 只给名字不给规格，
	// LLM 只能猜范围；只给规格不给名字，App 不知道要渲染什么。
	ParamSpecs []teslaCommandParamSpec `json:"param_specs"`
}

// MarshalJSON 保证「无参数」输出 **[]** 而不是 **null**。
//
// ⚠️ **v0.74.0（模拟器真机复核发现）**：
// Go 的 nil slice 序列化成 `null`，而 App 端的 kotlinx.serialization
// 遇到**显式 null**（不是「键缺失」）会**抛异常** —— 它只对**缺失**的键用默认值。
// ⇒ 整个车控清单解析失败，卡上只剩一句「读不到车控能力清单」，
// 而服务端、App 都不报错（App 把异常吃成了 `getOrNull()`）。
//
// 而 JSON API 的惯例是「无值的数组 = []」。
//
// ⚠️ **同一类坑的第二例**：MCP 那边踩过一次「字段名对不上 ⇒ 返回值全是 null」。
// 两次的共同点：**消费方拿到残缺数据，却没有任何报错**。
// ⇒ 判断一个 API 是否「能调通」，**不能只看它返回 200**。
func (s teslaCommandSpec) MarshalJSON() ([]byte, error) {
	type alias teslaCommandSpec
	out := alias(s)
	if out.Params == nil {
		out.Params = []string{}
	}
	// ⚠️ **v0.87.0**：同上 —— `param_specs` 也不能是 null。
	// kotlinx.serialization 遇到**显式 null** 会抛异常（它只对**缺失**的键用默认值），
	// 整个车控清单就解析不出来了。
	if out.ParamSpecs == nil {
		out.ParamSpecs = []teslaCommandParamSpec{}
	}
	return json.Marshal(out)
}

// --------------------------------------------------------------------------
// 参数规格（**唯一事实源**）
// --------------------------------------------------------------------------
//
// ⚠️ **v0.87.0**：这些规格**同时**喂给三方：
//
//	· 服务端校验 —— `buildTeslaCommandBody` 读的是这里的数字
//	· App 输入控件 —— 标签、范围、可选值都照这里渲染
//	· MCP JSON Schema —— LLM 照着填，零失误
//
// ⇒ 范围只在**这一处**写死。
//
// 官方锚点：teslamotors/vehicle-command
//
//	· `pkg/proxy/command.go` —— 端点名与 body 字段
//	· `pkg/vehicle/climate.go` —— `Level` 枚举与座位枚举
//	· `pkg/protocol/protobuf/carserver` —— 座位/档位的底层枚举
var (
	// teslaSpecOn 布尔开关（哨兵 / 代客 / 方向盘加热共用）。
	teslaSpecOn = teslaCommandParamSpec{
		Name:        "on",
		Type:        "bool",
		Required:    false,
		Label:       "开启",
		Description: "true = 开启，false = 关闭",
	}
	// teslaSpecPassword 代客模式 / 远程启动的 PIN。
	teslaSpecPassword = teslaCommandParamSpec{
		Name:        "password",
		Type:        "password",
		Required:    true,
		Label:       "PIN（4~20 位）",
		Description: "车主的 4~20 位数字 PIN（代客模式 / 远程启动用）",
	}
	teslaSpecDriverTemp = teslaCommandParamSpec{
		Name:        "driver_temp",
		Type:        "int",
		Required:    true,
		Min:         &teslaTempMin,
		Max:         &teslaTempMax,
		Label:       "驾驶位温度 ℃（15~28）",
		Description: "主驾座舱目标温度，单位摄氏度，可设 15~28",
	}
	teslaSpecPassengerTemp = teslaCommandParamSpec{
		Name:        "passenger_temp",
		Type:        "int",
		Required:    false,
		Min:         &teslaTempMin,
		Max:         &teslaTempMax,
		Label:       "副驾温度 ℃（15~28）",
		Description: "副驾座舱目标温度，单位摄氏度，可设 15~28；不填就跟着主驾",
	}
	teslaSpecChargeLimit = teslaCommandParamSpec{
		Name:        "percent",
		Type:        "int",
		Required:    true,
		Min:         &teslaChargeLimitMin,
		Max:         &teslaChargeLimitMax,
		Label:       "充电上限 %（50~100）",
		Description: "充电停机上限，单位百分比，可设 50~100",
	}
	teslaSpecChargingAmps = teslaCommandParamSpec{
		Name:        "charging_amps",
		Type:        "int",
		Required:    true,
		Min:         &teslaAmpsMin,
		Max:         &teslaAmpsMax,
		Label:       "充电电流 A（5~48）",
		Description: "充电电流，单位安培，可设 5~48",
	}
	// teslaSpecSeatPosition 座椅加热的座位序号。
	//
	// ⚠️ **官方 `seat_position` 是下标，不是「座位名」** ——
	// 锚点：官方 `pkg/proxy/command.go` 的 `seatPositions` 切片顺序。
	// 顺序改了，这些标签就全错 ⇒ 只认官方顺序。
	teslaSpecSeatPosition = teslaCommandParamSpec{
		Name:     "seat_position",
		Type:     "int",
		Required: true,
		Min:      &teslaSeatPositionMin,
		Max:      &teslaSeatPositionMax,
		Options: []teslaCommandParamOption{
			{Value: "0", Label: "主驾"},
			{Value: "1", Label: "副驾"},
			{Value: "2", Label: "第二排左座"},
			{Value: "3", Label: "第二排左后座"},
			{Value: "4", Label: "第二排中座"},
			{Value: "5", Label: "第二排右座"},
			{Value: "6", Label: "第二排右后座"},
			{Value: "7", Label: "第三排左座"},
			{Value: "8", Label: "第三排右座"},
		},
		Label:       "座位",
		Description: "官方座位序号：0 主驾 / 1 副驾 / 2~6 第二排（左右后为背向座）/ 7~8 第三排",
	}
	// teslaSpecSeatLevel 座椅加热的档位。
	//
	// 锚点：官方 `pkg/vehicle/climate.go` 的 `type Level int`
	//（LevelOff / LevelLow / LevelMed / LevelHigh = 0 / 1 / 2 / 3）。
	teslaSpecSeatLevel = teslaCommandParamSpec{
		Name:     "level",
		Type:     "int",
		Required: true,
		Min:      &teslaSeatLevelMin,
		Max:      &teslaSeatLevelMax,
		Options: []teslaCommandParamOption{
			{Value: "0", Label: "关"},
			{Value: "1", Label: "低档"},
			{Value: "2", Label: "中档"},
			{Value: "3", Label: "高档"},
		},
		Label:       "加热档位",
		Description: "加热档位（官方 Level 枚举）：0 关 / 1 低档 / 2 中档 / 3 高档",
	}
)

// teslaCommandCatalog 全部 21 个动作。
//
// ⚠️ 顺序 = App 与 MCP 展示的顺序（按风险从高到低排）。
//
// ⚠️ **v0.76.0：每个动作多了 `Done`** —— 动作**做完之后**的一句话。
// 「锁车」是动作名，「已锁车」才是**做完的样子** ——
// 用户看结果条的时候要的是后者。
// 它住在服务端（跟 `risk` 一样是安全/语义策略，只该有一处），
// App 与 MCP 都直接用，不各自抄一份。
//
// ⚠️ **v0.87.0：每个动作多了 `ParamSpecs`** —— 参数**怎么填**。
// `Params` 只回答「要哪些参数名」，`ParamSpecs` 回答
// 「每个参数的类型、范围、可选值和人话标签」。
// ⇒ MCP 照它出 JSON Schema（LLM 零失误），App 照它渲染控件。
var teslaCommandCatalog = []teslaCommandSpec{
	// ── 高危：会动车、会开孔、会把人放进车里 ──
	{
		Action:      "door_unlock",
		Endpoint:    "door_unlock",
		Description: "解锁车门",
		Done:        "已解锁",
		Risk:        teslaCmdRiskHigh,
	},
	{
		// ⚠️ **v0.87.0：动作名保留，文案被修正**。
		//
		// 官方 `pkg/vehicle/actions.go` 只有 `OpenTrunk`；而官方 proxy
		// **根本没有**关尾门的端点（`CloseTrunk` 没有对应的 `case`）⇒
		// 「开合尾门」这个说法**是错的** —— 它只能开。
		//
		// ⇒ 名字保留（对外稳定 ID，不破坏已有调用方），文案改成「打开尾门」。
		Action:      "actuate_trunk",
		Endpoint:    "actuate_trunk",
		Description: "打开尾门",
		Done:        "尾门已打开",
		Risk:        teslaCmdRiskHigh,
	},
	{
		// ⚠️ **v0.87.0：新增「打开前备箱」** —— 使命书 §2.1 点名的能力。
		//
		// 官方 proxy 的 `actuate_trunk` 端点带一个可选字段 `which_trunk`
		//（"front" → `OpenFrunk`，"rear" → `OpenTrunk`）
		// ⇒ 前备箱**不是另一个端点**，而是同一个端点的另一个取值。
		//
		// ⚠️ 官方注释写明 "There is no remote way to close the frunk!"
		// ⇒ **只能开、远程关不了** —— 这条写进文案，别让人以为能来回开合。
		Action:      "open_frunk",
		Endpoint:    "actuate_trunk",
		Description: "打开前备箱（远程关不了）",
		Done:        "前备箱已打开",
		Risk:        teslaCmdRiskHigh,
	},
	{
		Action:      "remote_start_drive",
		Endpoint:    "remote_start_drive",
		Description: "远程启动（无钥匙驾驶 2 分钟）",
		Done:        "已远程启动",
		Risk:        teslaCmdRiskHigh,
		Params:      []string{"password"},
		ParamSpecs:  []teslaCommandParamSpec{teslaSpecPassword},
	},
	// ── 中危：改设置、动设置，但不动人 ──
	{
		Action:      "set_valet_mode",
		Endpoint:    "set_valet_mode",
		Description: "代客模式开关",
		Done:        "代客模式已切换",
		Risk:        teslaCmdRiskMedium,
		Params:      []string{"on", "password"},
		ParamSpecs:  []teslaCommandParamSpec{teslaSpecOn, teslaSpecPassword},
	},
	{
		Action:      "set_temperature",
		Endpoint:    "set_temps",
		Description: "设定座舱温度（℃）",
		Done:        "温度已设定",
		Risk:        teslaCmdRiskMedium,
		Params:      []string{"driver_temp", "passenger_temp"},
		ParamSpecs:  []teslaCommandParamSpec{teslaSpecDriverTemp, teslaSpecPassengerTemp},
	},
	{
		// ⚠️ **v0.87.0：端点被修正**（这是个**一直坏着**的功能）。
		//
		// 原端点写的是 `set_climate_on` —— 那是 **Fleet API** 的命令名，
		// 而本项目走的是 **vehicle-command proxy**，官方 proxy 的端点叫
		// `auto_conditioning_start`（见官方 `pkg/proxy/command.go`）。
		//
		// 官方 proxy 遇到不认识的命令名会**立刻 400 invalid_command**
		//（`ExtractCommandAction` 的 default 分支）—— **不会**回退到 Fleet API
		//（回退只在「车不支持该协议」或「命令声明要用 REST API」时发生）。
		// ⇒ 「开启空调」这个按钮**从来没有生效过**，只是回读车况时看不出差别。
		Action:      "set_climate_on",
		Endpoint:    "auto_conditioning_start",
		Description: "开启空调",
		Done:        "空调已开启",
		Risk:        teslaCmdRiskMedium,
	},
	{
		// ⚠️ 同上：`set_climate_off` → `auto_conditioning_stop`。
		Action:      "set_climate_off",
		Endpoint:    "auto_conditioning_stop",
		Description: "关闭空调",
		Done:        "空调已关闭",
		Risk:        teslaCmdRiskMedium,
	},
	{
		// ⚠️ **v0.87.0：新增座椅加热** —— 使命书 §2.1 点名的能力。
		//
		// 官方 `pkg/proxy/command.go`：
		//   case "remote_seat_heater_request":
		//       setting, err := params.settingForHeatSeatPosition()
		// ⇒ body 必须是 `{"seat_position": <0~8>, "level": <0~3>}`，**两个都要**。
		Action:      "seat_heater",
		Endpoint:    "remote_seat_heater_request",
		Description: "座椅加热",
		Done:        "座椅加热已设定",
		Risk:        teslaCmdRiskMedium,
		Params:      []string{"seat_position", "level"},
		ParamSpecs:  []teslaCommandParamSpec{teslaSpecSeatPosition, teslaSpecSeatLevel},
	},
	{
		// ⚠️ **v0.87.0：新增方向盘加热**。
		// 官方端点 `remote_steering_wheel_heater_request`，body 只有 `{"on": bool}`。
		Action:      "steering_wheel_heater",
		Endpoint:    "remote_steering_wheel_heater_request",
		Description: "方向盘加热开关",
		Done:        "方向盘加热已切换",
		Risk:        teslaCmdRiskMedium,
		Params:      []string{"on"},
		ParamSpecs:  []teslaCommandParamSpec{teslaSpecOn},
	},
	{
		// ⚠️ **v0.87.0：新增车窗通风**。
		//
		// 官方端点 `window_control`，body 是 `{"command": "vent" | "close"}`
		// —— **只有这两个值**，其它值官方直接报
		// `command must be 'vent' or 'close'`。
		//
		// ⇒ 这里把取值**写死在服务端**，不暴露成参数 ⇒
		// 调用方（LLM / 人）不可能填错，也不用去记「vent 而不是 open」。
		Action:      "window_vent",
		Endpoint:    "window_control",
		Description: "车窗通风（开一条缝）",
		Done:        "车窗已通风",
		Risk:        teslaCmdRiskMedium,
	},
	{
		Action:      "window_close",
		Endpoint:    "window_control",
		Description: "关闭全部车窗",
		Done:        "车窗已关闭",
		Risk:        teslaCmdRiskMedium,
	},
	{
		Action:      "set_charge_limit",
		Endpoint:    "set_charge_limit",
		Description: "设置充电上限（%）",
		Done:        "充电上限已设定",
		Risk:        teslaCmdRiskMedium,
		Params:      []string{"percent"},
		ParamSpecs:  []teslaCommandParamSpec{teslaSpecChargeLimit},
	},
	{
		Action:      "set_charging_amps",
		Endpoint:    "set_charging_amps",
		Description: "设置充电电流（A）",
		Done:        "充电电流已设定",
		Risk:        teslaCmdRiskMedium,
		Params:      []string{"charging_amps"},
		ParamSpecs:  []teslaCommandParamSpec{teslaSpecChargingAmps},
	},
	{
		Action:      "charge_start",
		Endpoint:    "charge_start",
		Description: "开始充电",
		Done:        "已开始充电",
		Risk:        teslaCmdRiskMedium,
	},
	{
		Action:      "charge_stop",
		Endpoint:    "charge_stop",
		Description: "停止充电",
		Done:        "已停止充电",
		Risk:        teslaCmdRiskMedium,
	},
	{
		Action:       "wake_up",
		Endpoint:     "wake_up",
		Description:  "唤醒车辆（会耗电、可能计费）",
		Done:         "已唤醒",
		Risk:         teslaCmdRiskMedium,
		WakesVehicle: true,
	},
	{
		Action:      "door_lock",
		Endpoint:    "door_lock",
		Description: "锁车",
		Done:        "已锁车",
		Risk:        teslaCmdRiskMedium,
	},
	{
		Action:      "honk_horn",
		Endpoint:    "honk_horn",
		Description: "鸣笛",
		Done:        "已鸣笛",
		Risk:        teslaCmdRiskLow,
	},
	{
		Action:      "flash_lights",
		Endpoint:    "flash_lights",
		Description: "闪灯",
		Done:        "已闪灯",
		Risk:        teslaCmdRiskLow,
	},
	{
		Action:      "set_sentry_mode",
		Endpoint:    "set_sentry_mode",
		Description: "哨兵模式开关",
		Done:        "哨兵模式已切换",
		Risk:        teslaCmdRiskMedium,
		Params:      []string{"on"},
		ParamSpecs:  []teslaCommandParamSpec{teslaSpecOn},
	},
}

// teslaCommandByAction 动作名 → 规格。
var teslaCommandByAction = func() map[string]teslaCommandSpec {
	m := make(map[string]teslaCommandSpec, len(teslaCommandCatalog))
	for _, s := range teslaCommandCatalog {
		m[s.Action] = s
	}
	return m
}()

// --------------------------------------------------------------------------
// 参数校验
// --------------------------------------------------------------------------

// teslaCmdBool 取布尔参数。
func teslaCmdBool(p map[string]any, key string, def bool) (bool, error) {
	v, ok := p[key]
	if !ok || v == nil {
		return def, nil
	}
	b, ok := v.(bool)
	if !ok {
		return false, fmt.Errorf("参数 %s 必须是布尔值", key)
	}
	return b, nil
}

// teslaCmdInt 取整数参数（带范围校验）。
func teslaCmdInt(p map[string]any, key string, lo, hi int, def *int) (int, error) {
	v, ok := p[key]
	if !ok || v == nil {
		if def == nil {
			return 0, fmt.Errorf("缺少参数 %s", key)
		}
		return *def, nil
	}
	f, ok := v.(float64)
	if !ok {
		return 0, fmt.Errorf("参数 %s 必须是数字", key)
	}
	if f != float64(int(f)) {
		return 0, fmt.Errorf("参数 %s 必须是整数", key)
	}
	n := int(f)
	if n < lo || n > hi {
		return 0, fmt.Errorf("参数 %s 必须在 %d~%d 之间", key, lo, hi)
	}
	return n, nil
}

// teslaCmdCredential 取口令类参数（代客模式 PIN / 远程启动 PIN）。
func teslaCmdCredential(p map[string]any, key string) (string, error) {
	v, ok := p[key]
	if !ok || v == nil {
		return "", fmt.Errorf("缺少参数 %s", key)
	}
	s, ok := v.(string)
	if !ok {
		return "", fmt.Errorf("参数 %s 必须是字符串", key)
	}
	if len(s) < teslaCredMinLen || len(s) > teslaCredMaxLen {
		return "", fmt.Errorf("参数 %s 长度必须在 %d~%d 之间", key, teslaCredMinLen, teslaCredMaxLen)
	}
	return s, nil
}

// buildTeslaCommandBody 校验参数并构造下发给 proxy 的 body。
//
// ⚠️ 校验失败**不发任何网络请求**（参数错就退回去，别让车收到半截命令）。
func buildTeslaCommandBody(action string, p map[string]any) (map[string]any, error) {
	if p == nil {
		p = map[string]any{}
	}
	switch action {
	case "door_lock":
		return map[string]any{"on": true}, nil
	case "door_unlock":
		return map[string]any{"on": true}, nil
	case "honk_horn", "flash_lights":
		return map[string]any{}, nil
	case "set_climate_on":
		return map[string]any{"on": true}, nil
	case "set_climate_off":
		return map[string]any{"on": false}, nil
	case "charge_start", "charge_stop", "wake_up":
		return map[string]any{}, nil
	case "actuate_trunk":
		// ⚠️ **v0.87.0**：官方 `actuate_trunk` 端点用 `which_trunk`
		// 区分前后（"front" → 前备箱 / "rear" → 尾门），省略时官方默认后尾门。
		// 这里**写明**而不是靠默认值 —— 默认值会变，写明不会。
		return map[string]any{"which_trunk": "rear"}, nil
	case "open_frunk":
		// ⚠️ 同一个端点的另一个取值（官方 `pkg/proxy/command.go`）。
		return map[string]any{"which_trunk": "front"}, nil
	case "window_vent":
		// ⚠️ 官方 `window_control` 只认 "vent" / "close" 两个字面值，
		// 其它值官方直接报错 ⇒ 这里写死，不让调用方填。
		return map[string]any{"command": "vent"}, nil
	case "window_close":
		return map[string]any{"command": "close"}, nil
	case "steering_wheel_heater":
		on, err := teslaCmdBool(p, "on", true)
		if err != nil {
			return nil, err
		}
		return map[string]any{"on": on}, nil
	case "seat_heater":
		// ⚠️ 官方 `remote_seat_heater_request` 的 body
		// → `{"seat_position": <0~8>, "level": <0~3>}`，**两个都是必填**。
		seat, err := teslaCmdInt(p, "seat_position", teslaSeatPositionMin, teslaSeatPositionMax, nil)
		if err != nil {
			return nil, err
		}
		level, err := teslaCmdInt(p, "level", teslaSeatLevelMin, teslaSeatLevelMax, nil)
		if err != nil {
			return nil, err
		}
		return map[string]any{"seat_position": seat, "level": level}, nil
	case "set_temperature":
		driver, err := teslaCmdInt(p, "driver_temp", teslaTempMin, teslaTempMax, nil)
		if err != nil {
			return nil, err
		}
		passenger := driver
		if _, ok := p["passenger_temp"]; ok {
			passenger, err = teslaCmdInt(p, "passenger_temp", teslaTempMin, teslaTempMax, nil)
			if err != nil {
				return nil, err
			}
		}
		return map[string]any{"driver_temp": driver, "passenger_temp": passenger}, nil
	case "set_charge_limit":
		percent, err := teslaCmdInt(p, "percent", teslaChargeLimitMin, teslaChargeLimitMax, nil)
		if err != nil {
			return nil, err
		}
		return map[string]any{"percent": percent}, nil
	case "set_charging_amps":
		amps, err := teslaCmdInt(p, "charging_amps", teslaAmpsMin, teslaAmpsMax, nil)
		if err != nil {
			return nil, err
		}
		return map[string]any{"charging_amps": amps}, nil
	case "set_sentry_mode":
		on, err := teslaCmdBool(p, "on", true)
		if err != nil {
			return nil, err
		}
		return map[string]any{"on": on}, nil
	case "set_valet_mode":
		on, err := teslaCmdBool(p, "on", true)
		if err != nil {
			return nil, err
		}
		pw, err := teslaCmdCredential(p, "password")
		if err != nil {
			return nil, err
		}
		return map[string]any{"on": on, "password": pw}, nil
	case "remote_start_drive":
		pw, err := teslaCmdCredential(p, "password")
		if err != nil {
			return nil, err
		}
		return map[string]any{"password": pw}, nil
	}
	return nil, fmt.Errorf("不支持的命令 %q", action)
}

// --------------------------------------------------------------------------
// command-proxy 客户端
// --------------------------------------------------------------------------

var (
	teslaProxyClientOnce sync.Once
	teslaProxyClient     *http.Client
	teslaProxyClientErr  error
)

// teslaCommandProxyURL 代理地址。
func teslaCommandProxyURL() string {
	return strings.TrimRight(strings.TrimSpace(getenv("TESLA_COMMAND_PROXY_URL", "")), "/")
}

// teslaCommandProxyClient 造一个信自己 CA 的 HTTP 客户端。
//
// ⚠️ proxy 用的是**自签证书**（它的公钥就是注册到 partner account 的那把 EC 密钥），
// 所以必须显式信任 `TESLA_COMMAND_PROXY_CA` 指向的 CA ——
// 否则 TLS 校验直接失败（`x509: certificate signed by unknown authority`）。
func teslaCommandProxyClient() (*http.Client, error) {
	teslaProxyClientOnce.Do(func() {
		caPath := strings.TrimSpace(getenv("TESLA_COMMAND_PROXY_CA", ""))
		if caPath == "" {
			teslaProxyClientErr = errors.New("未配置 TESLA_COMMAND_PROXY_CA")
			return
		}
		pem, err := os.ReadFile(caPath)
		if err != nil {
			teslaProxyClientErr = fmt.Errorf("读 CA 失败: %w", err)
			return
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pem) {
			teslaProxyClientErr = errors.New("CA 文件里没有可用证书")
			return
		}
		teslaProxyClient = &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12},
			},
		}
	})
	return teslaProxyClient, teslaProxyClientErr
}

// teslaCommandResult 一次命令下发的结果。
//
// ⚠️ **v0.76.0：把「发出去了」与「车真的做了」拆成两件事**。
//
// 原来只有 `Sent` 一个标志，语义是「HTTP 2xx」——
// 于是车端**明确拒绝**（`result:false`，比如 `already_started` / `not_charging` /
// `disconnected`）也会被报成「已下发」= **假成功**。
// 车没照做，App 却说搞定了 —— 这是最坏的一种错。
//
// 现在三个字段各管一件事：
//
//	Sent     命令**发到了** Tesla（HTTP 2xx）
//	Executed 车端**确认执行**了（`response.result == true`）
//	State    四态：executed / rejected / dispatched / failed
type teslaCommandResult struct {
	Action      string `json:"action"`
	Risk        string `json:"risk"`
	Description string `json:"description"`
	// ⚠️ **v0.76.0**：完成态文案（「已锁车」）—— 随结果一起回来，
	// 消费方不必再回头查一遍清单。
	Done       string         `json:"done"`
	VIN        string         `json:"vin"`
	Sent       bool           `json:"sent"`
	Executed   bool           `json:"executed"`
	State      string         `json:"state"`
	Reason     string         `json:"reason,omitempty"`
	ReasonText string         `json:"reason_text,omitempty"`
	Response   map[string]any `json:"response,omitempty"`
	Error      string         `json:"error,omitempty"`
	At         time.Time      `json:"at"`
}

// --------------------------------------------------------------------------
// 车端结果（v0.76.0）
// --------------------------------------------------------------------------

// 命令结果的四种状态。
const (
	// teslaCmdStateExecuted 车端确认执行了（`result: true`）。
	teslaCmdStateExecuted = "executed"
	// teslaCmdStateRejected 车端**明确拒绝**了（`result: false` + 原因）。
	teslaCmdStateRejected = "rejected"
	// teslaCmdStateDispatched 发出去了，但没拿到车端确认。
	teslaCmdStateDispatched = "dispatched"
	// teslaCmdStateFailed 命令根本没发出去（网络/代理/车在休眠）。
	teslaCmdStateFailed = "failed"
)

// teslaCommandReasonText 车端原因码 → 人话。
//
// ⚠️ **只收官方文档里逐条列出来过的原因码**
// （developer.tesla.cn → Fleet API → Vehicle Commands，各命令的说明里
//
//	明确写了 "This command may return already_started / is_charging / …"）。
//
// ⚠️ **没收录的一律回落到「车端拒绝（<原码>）」** ——
// 按前缀猜含义就是幻觉，宁可把原码摊给用户看，也不要编一个像模像样的解释。
var teslaCommandReasonText = map[string]string{
	// charge_start —— 官方原文："This command may return errors if the vehicle
	// is unable to begin charging. complete - … is_charging - … disconnected - …
	// no_power - … requested - …"
	"complete":     "充电已经完成了",
	"is charging":  "车已经在充电了",
	"disconnected": "充电枪没插",
	"no power":     "充电桩没有供电",
	"requested":    "车已经收到过充电请求了",
	// charge_stop —— "may return not_charging if the vehicle is not currently charging"
	"not charging": "车当前没有在充电",
	// set_charge_limit —— "may return already_set if the requested percent is the same"
	"already set": "充电上限已经是这个值了",
	// charge_standard —— "may return already_started if the existing charge limit
	// request is already less than or equal to the standard/default charge limit"
	"already started": "已经是标准充电模式了",
	// charge_port_door_close —— "already closed - … cable connected - …
	// no motorized charge port - …"
	"already closed":           "充电口盖已经关着了",
	"cable connected":          "充电线还插着",
	"no motorized charge port": "这台车没有电动充电口盖",
	// charge_port_door_open —— "car_wash - … not allowed - …"
	"car wash":    "车在洗车模式",
	"not allowed": "车的当前状态不允许这个操作",
}

// teslaNormalizeReason 归一化原因码：去空白、转小写、下划线→空格。
//
// Tesla 的原因码是小写下划线或小写带空格（如 `already_set` / `not allowed`），
// 归一之后两种写法都能查到同一张表。
func teslaNormalizeReason(s string) string {
	return strings.ToLower(strings.TrimSpace(strings.ReplaceAll(s, "_", " ")))
}

// teslaCommandReasonChinese 把车端原因码翻成人话。
//
// 没收录 ⇒ 「车端拒绝（<原码>）」，**保留原码** ——
// 用户能拿它去搜/问客服，我们也不至于编一个假的解释。
//
// ⚠️ **v0.76.0 实测（真车）**：Tesla 回来的原因码**带前缀**，不是文档里那个裸码：
//
//	"car could not execute command: disconnected"
//
// （文档写的是 `disconnected`。）⇒ 先按**最后一个 ": "** 切一刀，
// 用尾巴去查表；查不到再整句摊给用户 —— 前缀本身是上下文，尾巴才是原因码。
func teslaCommandReasonChinese(reason string) string {
	r := strings.TrimSpace(reason)
	if r == "" {
		return ""
	}
	if i := strings.LastIndex(r, ": "); i >= 0 {
		tail := strings.TrimSpace(r[i+2:])
		if tail != "" {
			if t, ok := teslaCommandReasonText[teslaNormalizeReason(tail)]; ok {
				return t
			}
		}
	}
	if t, ok := teslaCommandReasonText[teslaNormalizeReason(r)]; ok {
		return t
	}
	return "车端拒绝（" + r + "）"
}

// parseTeslaCommandOutcome 解析 Tesla 的命令响应，取车端的**执行结果**。
//
// 官方响应形状（developer.tesla.cn → Vehicle Commands）：
//
//	{"response": {"result": true|false, "reason": "..."}}
//
// ⚠️ `result:false` 是**车端拒绝**，不是「命令没发出去」—— 两者必须分开。
// ⚠️ 没有 `result` 字段 ⇒ 拿到了 2xx 但没拿到车端确认 ⇒ 只能说「已下发」。
// ⚠️ `response` 有时是 base64 字符串（session info 那类端点）⇒ 解析不出来
// 就当「没有车端确认」，绝不瞎猜。
func parseTeslaCommandOutcome(body map[string]any) (executed bool, hasResult bool, reason string) {
	if body == nil {
		return false, false, ""
	}
	payload := body
	if inner, ok := body["response"].(map[string]any); ok {
		payload = inner
	}
	switch v := payload["result"].(type) {
	case bool:
		hasResult = true
		executed = v
	case float64:
		hasResult = true
		executed = v != 0
	}
	if s, ok := payload["reason"].(string); ok {
		reason = strings.TrimSpace(s)
	}
	return executed, hasResult, reason
}

// refreshStateAfterCommand 命令下发后**立刻**回读一次车况。
//
// ⚠️ **为什么必须做**（v0.76.0 实测发现）：
// 轮询间隔是 2 分钟（`teslaPollInterval`），
// 所以「我刚开了空调」之后，屏幕上的「空调关闭」会**赖两分钟** ——
// 车真的开了，App 却像是「点了没反应」。用户会以为车没听指挥，然后**再点一次**。
//
// 官方 Fleet API Best Practices 也写着：
// "It is the developer's responsibility to ensure the vehicle is in the desired state."
// —— 状态回读是**开发者**的责任，不该转嫁给用户去等。
//
// ⚠️ **单飞**：连点几个按钮 = 几次 vehicle_data，那是要计费的请求，
// 而且完全没必要（在飞的那一次会把结果一起写进缓存）。
func (c *teslaClient) refreshStateAfterCommand(ctx context.Context) {
	c.refreshMu.Lock()
	if c.refreshing {
		c.refreshMu.Unlock()
		return
	}
	c.refreshing = true
	c.refreshMu.Unlock()
	defer func() {
		c.refreshMu.Lock()
		c.refreshing = false
		c.refreshMu.Unlock()
	}()

	// 车在休眠时 vehicle_data 会回 408 —— 那是预期的，不算错误。
	if err := c.pollOnce(ctx); err != nil {
		log.Printf("ℹ️  命令后回读车况失败（不影响命令本身）：%v", err)
	}
}

// resolveVehicleVIN 取这台车的 VIN（17 位）。
//
// ⚠️ **command-proxy 的命令端点要的是 VIN，不是 Fleet API 的 id** ——
// 实测传数字 id（<fleet车辆ID>）会被 proxy 回
// `expected 17-character VIN in path (do not user Fleet API ID)`。
// 两者不能互换。
func (c *teslaClient) resolveVehicleVIN(ctx context.Context) (string, error) {
	c.mu.RLock()
	vin := c.vehicleVIN
	c.mu.RUnlock()
	if vin != "" {
		return vin, nil
	}
	body, code, err := c.get(ctx, c.fleetURL+"/api/1/vehicles")
	if err != nil {
		return "", err
	}
	if code != http.StatusOK {
		return "", fmt.Errorf("车辆列表返回 %d: %s", code, maskSecretText(truncate(string(body), 300)))
	}
	var out struct {
		Response []struct {
			ID  flexString `json:"id"`
			VIN string     `json:"vin"`
		} `json:"response"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return "", fmt.Errorf("车辆列表解析失败: %w", err)
	}
	if len(out.Response) == 0 {
		return "", errors.New("车辆列表为空")
	}
	vin = strings.TrimSpace(out.Response[0].VIN)
	if vin == "" {
		return "", errors.New("车辆列表里没有 vin 字段")
	}
	c.mu.Lock()
	c.vehicleVIN = vin
	c.mu.Unlock()
	log.Printf("ℹ️  车控用 VIN %s", vin)
	return vin, nil
}

// sendTeslaCommand 校验并下发一条命令。
//
// ⚠️ 车休眠时 Tesla 会回 408（`vehicle is offline or asleep`）——
// 那是**车没醒**，不是命令错；调用方要区分这两种（见 `teslaCommandOfflineHint`）。
func (c *teslaClient) sendTeslaCommand(ctx context.Context, action string, params map[string]any) (*teslaCommandResult, error) {
	spec, ok := teslaCommandByAction[action]
	if !ok {
		return nil, fmt.Errorf("不支持的命令 %q", action)
	}
	body, err := buildTeslaCommandBody(action, params)
	if err != nil {
		return nil, err
	}
	proxy := teslaCommandProxyURL()
	if proxy == "" {
		return nil, errors.New("未配置 TESLA_COMMAND_PROXY_URL —— 车控代理没起来")
	}
	if err := c.ensureToken(ctx); err != nil {
		return nil, fmt.Errorf("拿不到 Tesla 令牌: %w", err)
	}
	c.mu.RLock()
	token := c.accessToken
	c.mu.RUnlock()
	// ⚠️ 命令端点要 **VIN**，不是 Fleet API 的数字 id。
	vin, err := c.resolveVehicleVIN(ctx)
	if err != nil {
		return nil, fmt.Errorf("拿不到车辆 VIN: %w", err)
	}

	raw, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		fmt.Sprintf("%s/api/1/vehicles/%s/command/%s", proxy, vin, spec.Endpoint),
		bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)

	client, err := teslaCommandProxyClient()
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("调 command-proxy 失败: %w", err)
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))

	res := &teslaCommandResult{
		Action:      action,
		Risk:        spec.Risk,
		Description: spec.Description,
		Done:        spec.Done,
		VIN:         vin,
		At:          time.Now(),
	}
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		res.Sent = true
		var parsed map[string]any
		if json.Unmarshal(respBody, &parsed) == nil {
			res.Response = parsed
		}
		// ⚠️ **v0.76.0**：2xx 只说明「命令送到 Tesla 了」，**不等于车照做了**。
		// 车端会回 `{"response":{"result":false,"reason":"already_charging"}}`
		// 这种「明确拒绝」—— 原来一律报成「已下发」＝ **假成功**。
		executed, hasResult, reason := parseTeslaCommandOutcome(parsed)
		switch {
		case hasResult && executed:
			res.State = teslaCmdStateExecuted
			res.Executed = true
		case hasResult && !executed:
			res.State = teslaCmdStateRejected
			res.Reason = reason
			res.ReasonText = teslaCommandReasonChinese(reason)
		default:
			res.State = teslaCmdStateDispatched
		}
		// ⚠️ **v0.76.0**：命令后**立刻回读**车况 ——
		// 否则轮询间隔（2 分钟）会让屏幕上的状态「赖」两分钟，
		// 用户看到的是「点了没反应」，然后再点一次。
		if res.State != teslaCmdStateRejected {
			refreshCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
			c.refreshStateAfterCommand(refreshCtx)
			cancel()
		}
		// ⚠️ 审计：谁、什么时候、对哪台车做了什么 —— 车控是**不可逆**的。
		log.Printf("🚗 车控下发：action=%s risk=%s vin=%s state=%s params=%s",
			action, spec.Risk, vin, res.State, redactCommandParams(action, params))
		return res, nil
	}
	res.Error = strings.TrimSpace(string(respBody))
	res.State = teslaCmdStateFailed
	log.Printf("⚠️ 车控失败：action=%s risk=%s vin=%s http=%d err=%s", action, spec.Risk, vin, resp.StatusCode, res.Error)
	return res, nil
}

// redactCommandParams 审计日志里**不落明文口令**。
func redactCommandParams(action string, params map[string]any) string {
	if params == nil {
		return "{}"
	}
	safe := make(map[string]any, len(params))
	for k, v := range params {
		if k == "password" {
			safe[k] = "***"
			continue
		}
		safe[k] = v
	}
	b, _ := json.Marshal(safe)
	return string(b)
}

// --------------------------------------------------------------------------
// HTTP 端点
// --------------------------------------------------------------------------

// handleTeslaCommandCatalog 列出支持的动作（App 与 MCP 都用它渲染）。
func handleTeslaCommandCatalog(w http.ResponseWriter, r *http.Request) {
	if _, err := pathID(r); err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	writeJSON(w, map[string]any{
		"actions": teslaCommandCatalog,
		"proxy":   teslaCommandProxyConfigured(),
	})
}

// teslaCommandProxyConfigured 代理与令牌是否就绪（给 App 显示「为什么按钮是灰的」）。
func teslaCommandProxyConfigured() bool {
	if teslaCommandProxyURL() == "" {
		return false
	}
	if _, err := teslaCommandProxyClient(); err != nil {
		return false
	}
	return tesla != nil && tesla.configured()
}

// handleTeslaCommand 下发一条命令。
//
// POST /api/v1/cars/{id}/commands/{action}
// body: {"params": {...}} 或直接 {...}
func handleTeslaCommand(w http.ResponseWriter, r *http.Request) {
	if _, err := pathID(r); err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	action := strings.TrimSpace(r.PathValue("action"))
	if action == "" {
		writeErr(w, http.StatusBadRequest, "缺少动作名")
		return
	}
	if _, ok := teslaCommandByAction[action]; !ok {
		writeErr(w, http.StatusBadRequest, "不支持的命令 "+action)
		return
	}

	// body 两种写法都收：{"params":{...}} 与直接 {...}
	var raw map[string]any
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := dec.Decode(&raw); err != nil && !errors.Is(err, io.EOF) {
		writeErr(w, http.StatusBadRequest, "请求体不是 JSON")
		return
	}
	params, _ := raw["params"].(map[string]any)
	if params == nil {
		// 没套 {"params":…} ⇒ 整个 body 当参数
		params = raw
	}

	if tesla == nil || !tesla.configured() {
		writeErr(w, http.StatusServiceUnavailable, "Tesla 令牌未配置 —— 车控不可用")
		return
	}

	// ⚠️ **v0.67.0：dry-run** —— 只校验参数，**不下发**。
	//
	// 为什么需要：下游（Housekeeper）的命令流程是
	// 「校验参数 → 预算 → 幂等 → 高危二次确认 → 下发」，
	// 参数校验必须在**最前面**，否则用户会先输 TOTP、再被告知「参数越界」。
	//
	// 而**参数范围是 teslamate 的唯一事实源**（下游不该再抄一份规则）——
	// 于是给下游一个「只问不答」的入口。
	if r.URL.Query().Get("dry_run") == "1" {
		spec := teslaCommandByAction[action]
		res := &teslaCommandResult{
			Action:      action,
			Risk:        spec.Risk,
			Description: spec.Description,
			At:          time.Now(),
		}
		writeJSON(w, map[string]any{
			"action":      res.Action,
			"risk":        res.Risk,
			"description": res.Description,
			"done":        res.Done,
			"valid":       true,
			"dry_run":     true,
			"at":          res.At,
		})
		return
	}

	res, err := tesla.sendTeslaCommand(r.Context(), action, params)
	if err != nil {
		// 参数错 = 400；其余（代理没起、令牌不行）= 502
		code := http.StatusBadGateway
		if strings.Contains(err.Error(), "参数") || strings.Contains(err.Error(), "不支持") ||
			strings.Contains(err.Error(), "缺少") {
			code = http.StatusBadRequest
		}
		writeErr(w, code, err.Error())
		return
	}

	// ⚠️ 车在休眠时 proxy 回 408 —— 那不是命令错，是「车没醒」。
	// 把它翻译成 409 + 人话，让 App 能显示「先唤醒它」。
	if !res.Sent && strings.Contains(res.Error, "408") {
		writeJSON(w, map[string]any{
			"action":      res.Action,
			"risk":        res.Risk,
			"description": res.Description,
			"sent":        false,
			"executed":    false,
			"state":       teslaCmdStateFailed,
			"error":       "车在休眠或离线 —— 先唤醒再试",
			"hint":        "车醒着的时候命令才发得出去；唤醒本身会耗电、可能计费",
			"at":          res.At,
		})
		return
	}
	writeJSON(w, res)
}
