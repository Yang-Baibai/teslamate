#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# 用户反馈**分诊清单**（每轮迭代的第一件事）
# ═══════════════════════════════════════════════════════════════════
#
# ## 干什么用
#
# 迭代的输入不只是「上一轮留下的 TODO」，还有**用户在 App 里报回来的问题**
# （`tm_user_feedback` 表 + `media/feedback/` 里的截图）。
# ⇒ 每轮迭代**开工前先跑这个脚本**，把待评估的反馈逐条读完、写下结论，
#   属于本轮板块的就做掉，不属于的也要**写下理由**（别让下一轮重新纠结同一件事）。
#
# ⚠️ 这个脚本**只读**，不写任何状态 ⇒ 永远退出 0（它不是闸门）。
#    要写结论用：`POST /api/v1/feedback/{id}/status`
#    （`doing` / `skip`+理由 / `done` / `new`=重新打开）。
#
# ⚠️ **评估理由最多 256 个字**（服务端 `feedbackContextMax*4`）⇒
#    超了会被**截断在半句上**（v0.107.0 实测踩到）⇒ 写之前先数长度。
#
# ## 退出码
#
#   0 = 永远（读不到反馈时也退 0，但会打印警告 —— 那是**服务不可达**，不是「没有反馈」）
set -uo pipefail
cd "$(dirname "$0")/.."

B="${REMOTE_HOST:-http://localhost:8090}"
# ⚠️ 必须去掉行尾的 \r：.env 在 Windows 上是 CRLF，`cut` 会把 \r 带进值里
TOKEN=$(grep '^API_TOKEN=' .env | cut -d= -f2 | tr -d '\r')
export PYTHONIOENCODING=utf-8

if [ -z "$TOKEN" ]; then
  echo "❌ .env 里没有 API_TOKEN —— 读不到反馈（服务不可达 ≠ 没有反馈）"
  exit 0
fi

curl -s -m 15 -H "Authorization: Bearer $TOKEN" "$B/api/v1/feedback?limit=200" \
| python -c "
import json, sys
from pathlib import Path

try:
    d = json.load(sys.stdin)
except Exception as e:
    print('⚠️ 读不到反馈（服务不可达？）：%s' % e)
    sys.exit(0)
if isinstance(d, dict) and d.get('error'):
    print('⚠️ 读不到反馈（服务不可达？）：%s' % d['error'])
    sys.exit(0)

items = d.get('items') or []
LABEL = {'new': '待评估', 'doing': '要处理', 'skip': '不处理', 'done': '已修好'}
buckets = {}
for i in items:
    buckets.setdefault(i.get('status') or 'new', []).append(i)

print('══ 用户反馈清单（共 %d 条）══' % len(items))
for st in ('new', 'doing', 'done', 'skip'):
    group = buckets.get(st) or []
    if not group:
        continue
    print()
    print('── %s（%d）──' % (LABEL[st], len(group)))
    for i in group:
        img = (i.get('image_path') or '').strip()
        if img:
            img = str(Path('media') / 'feedback' / Path(img).name)
        print('  #%s | %s | 页面 %s | v%s | %s'
              % (i.get('id'),
                 (i.get('created_at') or '')[:16].replace('T', ' '),
                 i.get('screen') or '（未记录）',
                 i.get('app_version') or '—',
                 i.get('car_name') or '—'))
        print('     %s' % (i.get('message') or '').replace('\n', ' '))
        print('     图：%s' % (img or '无'))
        note = (i.get('decision_note') or '').strip()
        if note:
            print('     结论：%s' % note)
"
