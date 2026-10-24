#!/usr/bin/env bash
# 项目健康冒烟测试——改了 api/ 或 compose 后必跑，全部通过才算部署成功。
# 用法：bash scripts/smoke.sh            （在本机，API 于 localhost:8090）
#       REMOTE_HOST=http://100.x.x.x:8090 bash scripts/smoke.sh   （远程验证）
# 退出码：0=全过 1=有失败（CI 可直接用）
set -uo pipefail
cd "$(dirname "$0")/.."

B="${REMOTE_HOST:-http://localhost:8090}"
# ⚠️ 必须去掉行尾的 \r：.env 在 Windows 上是 CRLF，`cut` 会把 \r 一起带进值里，
# 于是 Authorization 头变成 "Bearer xxx\r" → 401，后面每条断言都会假失败。
TOKEN=$(grep '^API_TOKEN=' .env | cut -d= -f2 | tr -d '\r')
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✓ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }

echo "══ 冒烟测试 → $B ══"

# 1. 健康检查（免鉴权）
[ "$(curl -s -m 5 $B/healthz | grep -o ok)" = "ok" ] && ok "healthz" || fail "healthz"

# 2. 鉴权：无 token 必须 401
[[ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 $B/api/v1/cars)" == "401" ]] \
  && ok "鉴权拒绝无 token" || fail "鉴权拒绝无 token"

# 3. 车辆列表
CARS=$(curl -s -m 5 -H "Authorization: Bearer $TOKEN" $B/api/v1/cars | python -c "import json,sys;print(len(json.load(sys.stdin)))" 2>/dev/null)
[[ "$CARS" -ge 1 ]] 2>/dev/null && ok "cars 列表（$CARS 台）" || fail "cars 列表"

# 4. 车况含胎压字段
S=$(curl -s -m 5 -H "Authorization: Bearer $TOKEN" $B/api/v1/cars/1/status)
echo "$S" | python -c "import json,sys;d=json.load(sys.stdin);assert 'tpms_fl' in d" 2>/dev/null \
  && ok "status 含 tpms 字段" || fail "status 含 tpms 字段"

# 4b. v0.7.0：服务端算好的告警判定字段（App 与 Hermes 推送脚本共用同一份口径）
echo "$S" | python -c "
import json,sys
d=json.load(sys.stdin)
for k in ('tpms_alert','tpms_alert_wheels','tpms_min_bar','tpms_max_bar','data_age_hours','data_stale'):
    assert k in d, 'missing '+k
assert isinstance(d['tpms_alert'], bool), 'tpms_alert must be bool'
assert isinstance(d['tpms_alert_wheels'], list), 'tpms_alert_wheels must be list'
assert d['tpms_min_bar'] < d['tpms_max_bar'], 'tpms range inverted'
assert d['data_age_hours'] >= 0, 'negative data age'" 2>/dev/null \
  && ok "status 含告警判定字段" || fail "status 含告警判定字段"

# 4c. v0.8.4：锁车状态（Tesla Fleet API）。
# 没配 Fleet API 凭据时 locked 必须是 null（未知），不能是 false（那会被误读成「没锁」）。
echo "$S" | python -c "
import json,sys
d=json.load(sys.stdin)
assert 'locked' in d, 'missing locked'
v=d['locked']
assert v is None or isinstance(v,bool), 'locked must be bool or null'
assert 'locked_at' in d, 'missing locked_at'
" 2>/dev/null \
  && ok "status 含 locked 字段（bool 或 null）" || fail "status 含 locked 字段（bool 或 null）"

# 4d. v0.8.5：Fleet API 实时胎压（Tesla Fleet API 的 vehicle_state）。
# 键必须存在；没配凭据 / 车休眠时值是 null（未知），不能缺键，也不能是 0。
echo "$S" | python -c "
import json,sys
d=json.load(sys.stdin)
for k in ('tpms_live_fl','tpms_live_fr','tpms_live_rl','tpms_live_rr'):
    assert k in d, 'missing '+k
    v=d[k]
    assert v is None or isinstance(v,(int,float)), k+' must be number or null'
assert 'tpms_live_at' in d, 'missing tpms_live_at'
" 2>/dev/null \
  && ok "status 含 tpms_live 实时胎压字段" || fail "status 含 tpms_live 实时胎压字段"

# 5. 充电列表含损耗率
CH=$(curl -s -m 8 -H "Authorization: Bearer $TOKEN" "$B/api/v1/cars/1/charges?limit=3")
echo "$CH" | python -c "import json,sys;d=json.load(sys.stdin);assert len(d)>0 and 'loss_percent' in d[0]" 2>/dev/null \
  && ok "charges 含 loss_percent" || fail "charges 含 loss_percent"

# 5b. v0.7.1：行程的充电关联字段（里程口径，不是时间窗）
# 旧口径 charges.date BETWEEN drives.start_date AND drives.end_date 恒为 false，
# 这里断言至少有一条行程能关联到充电，防止判据再次退化成恒 false。
DR=$(curl -s -m 8 -H "Authorization: Bearer $TOKEN" "$B/api/v1/cars/1/drives?limit=200")
echo "$DR" | python -c "
import json,sys
d=json.load(sys.stdin)
assert len(d) > 0, 'empty drives'
for k in ('has_supercharger','charge_after_trip'):
    assert k in d[0], 'missing '+k
    assert isinstance(d[0][k], bool), k+' must be bool'
assert any(x['has_supercharger'] for x in d), 'no drive matched a DC charge'
assert any(x['charge_after_trip'] for x in d), 'no drive matched a charge'
" 2>/dev/null \
  && ok "drives 含充电关联字段（里程口径）" || fail "drives 含充电关联字段（里程口径）"

# 6. 统计三档都出 summary
for p in week month year; do
  curl -s -m 8 -H "Authorization: Bearer $TOKEN" "$B/api/v1/cars/1/stats?period=$p" \
    | python -c "import json,sys;d=json.load(sys.stdin);assert d.get('summary')" 2>/dev/null \
    && ok "stats $p" || fail "stats $p"
done

# 7. 电池健康：铭牌容量口径
BAT=$(curl -s -m 5 -H "Authorization: Bearer $TOKEN" $B/api/v1/cars/1/battery)
NOM=$(grep '^NOMINAL_BATTERY_KWH=' .env | cut -d= -f2 | tr -d '\r')
echo "$BAT" | python -c "
import json,sys
d=json.load(sys.stdin)
assert d.get('nominal_capacity_kwh'), 'missing nominal'
assert abs(d['nominal_capacity_kwh'] - $NOM) < 0.01, 'nominal mismatch'
assert d.get('health_percent') and 50 < d['health_percent'] <= 100, 'health out of range'" 2>/dev/null \
  && ok "battery 铭牌口径（$NOM kWh）" || fail "battery 铭牌口径"

# 8. 静态图：PNG 且 Content-Type 正确
R=$(curl -s -o /tmp/smoke_map.png -w "%{http_code}|%{content_type}|%{size_download}" -m 15 \
  -H "Authorization: Bearer $TOKEN" \
  "$B/api/v1/app/staticmap?points=113.86%2C22.58%3B113.8674%2C22.572459")
[[ "$R" == 200\|image/png\|* ]] 2>/dev/null && [ "$(stat -c%s /tmp/smoke_map.png 2>/dev/null || echo 0)" -gt 10000 ] \
  && ok "staticmap PNG（$(echo $R | cut -d'|' -f3) B）" || fail "staticmap（$R）"

# 9. APK 分发：Content-Type 必须是 Android 包（v0.2.1 教训）
CT=$(curl -sI -m 5 $B/apk/teslamate-app.apk | grep -i content-type | tr -d '\r')
[[ "$CT" == *"vnd.android.package-archive"* ]] && ok "APK Content-Type 正确" || fail "APK Content-Type（$CT）"

# 10. 自更新元数据
L=$(curl -s -m 5 -H "Authorization: Bearer $TOKEN" $B/api/v1/app/latest)
echo "$L" | python -c "import json,sys;d=json.load(sys.stdin);assert d['version_code'].isdigit()" 2>/dev/null \
  && ok "app/latest 元数据" || fail "app/latest 元数据"

echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ] && echo "✅ 冒烟通过" || echo "❌ 有失败项，禁止发版"
exit "$FAIL"
