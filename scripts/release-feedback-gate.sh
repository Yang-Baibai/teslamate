#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# 发版前的**用户反馈闸门**（v0.101.0）
# ═══════════════════════════════════════════════════════════════════
#
# ## 规则
#
# **每次版本更新之前，必须先读当前「待评估」的用户反馈，逐条评估并判断是否处理**，
# 然后才允许发版。
#
# ## 为什么要有这道闸门
#
# 反馈通道长进产品之后，「用户报的问题」就成了**发版的输入**，
# 而不是一个「想起来才看」的东西。
#
# ⇒ 没有闸门：反馈会攒着，每一轮迭代都从零开始看同一批老问题；
#   有闸门：每条反馈都必须有一个**明确的结论**
#   （要处理 doing / 不处理 skip + 理由 / 已修好 done / 重新打开 new）。
#
# ⚠️ **评估结论是数据，不是每轮的口头判断** ——
# 「这条不是缺陷，是既有限制」这种判断必须**写下来**，
# 否则下一轮还会有人重新纠结同一件事。
#
# ## 怎么评估
#
#   · MCP 工具：`teslamate_feedback`（读）+ `teslamate_feedback_decision`（写结论）
#   · 或直接调 API：`POST /api/v1/feedback/{id}/status`
#     body: `{"status":"doing"}` / `{"status":"skip","note":"理由"}`
#
# ## 退出码
#
#   0 = 没有待评估的反馈（可以发版）
#   1 = 还有没评估的（**禁止发版**）
#   2 = 读不到反馈（服务不可达 ⇒ 不知道有没有待评估 ⇒ 同样禁止发版）
set -uo pipefail
cd "$(dirname "$0")/.."

B="${REMOTE_HOST:-http://localhost:8090}"
# ⚠️ 必须去掉行尾的 \r：.env 在 Windows 上是 CRLF，`cut` 会把 \r 带进值里
# ⇒ Authorization 头变成 "Bearer xxx\r" ⇒ 401，后面每条断言都会假失败。
TOKEN=$(grep '^API_TOKEN=' .env | cut -d= -f2 | tr -d '\r')

# ⚠️ **必须显式指定 python 的 IO 编码 = UTF-8**（与 smoke.sh / client-gates.sh 一致）：
#    这台机器默认 GBK，而反馈正文是中文 ⇒ 乱码会让「有没有待评估」判错。
export PYTHONIOENCODING=utf-8

echo "══ 用户反馈闸门 ══"

if [ -z "$TOKEN" ]; then
  echo "❌ .env 里没有 API_TOKEN —— 读不到反馈，禁止发版"
  exit 2
fi

RAW=$(curl -s -m 15 -H "Authorization: Bearer $TOKEN" "$B/api/v1/feedback?status=new&limit=100")
if [ -z "$RAW" ]; then
  echo "❌ 读不到反馈（服务不可达？）—— 不知道有没有待评估的反馈，禁止发版"
  exit 2
fi

# 解析 + 打印（一条一行，附上下文；带图的给出宿主上的路径）
OUT=$(echo "$RAW" | python -c "
import json, sys
from pathlib import Path
try:
    d = json.load(sys.stdin)
except Exception as e:
    print('PARSE_ERROR: %s' % e)
    sys.exit(3)
if isinstance(d, dict) and d.get('error'):
    print('API_ERROR: %s' % d['error'])
    sys.exit(3)
items = d.get('items') or []
print('COUNT: %d' % len(items))
for i in items:
    img = (i.get('image_path') or '').strip()
    if img:
        img = str(Path('media') / 'feedback' / Path(img).name)
    print('ITEM: #%s | %s | %s | 页面 %s | v%s | %s | 图 %s' % (
        i.get('id'),
        (i.get('created_at') or '')[:16].replace('T', ' '),
        (i.get('message') or '').replace('\n', ' '),
        i.get('screen') or '（未记录）',
        i.get('app_version') or '—',
        i.get('car_name') or '—',
        img or '无',
    ))
" 2>&1)
RC=$?

if [ "$RC" != "0" ]; then
  echo "$OUT"
  echo "❌ 读反馈失败 —— 禁止发版"
  exit 2
fi

COUNT=$(echo "$OUT" | sed -n 's/^COUNT: //p')
if [ -z "$COUNT" ]; then
  echo "$OUT"
  echo "❌ 解析反馈条数失败 —— 禁止发版"
  exit 2
fi

if [ "$COUNT" -gt 0 ]; then
  echo "$OUT" | grep -v '^COUNT:' | sed 's/^ITEM: /  /'
  echo ""
  echo "❌ 有 $COUNT 条反馈还没评估 —— **禁止发版**。"
  echo "   逐条评估并写下结论后再发版："
  echo "     · 要处理   → status=doing"
  echo "     · 不处理   → status=skip  且**必须写理由**"
  echo "     · 已修好   → status=done"
  echo "   工具：MCP 的 teslamate_feedback_decision，或 POST /api/v1/feedback/{id}/status"
  exit 1
fi

echo "  ✓ 没有待评估的用户反馈（可以发版）"
exit 0
