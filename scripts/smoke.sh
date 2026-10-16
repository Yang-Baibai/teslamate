#!/usr/bin/env bash
# 项目健康冒烟测试——改了 api/ 或 compose 后必跑，全部通过才算部署成功。
# 用法：bash scripts/smoke.sh            （在本机，API 于 localhost:8090）
#       REMOTE_HOST=http://100.x.x.x:8090 bash scripts/smoke.sh   （远程验证）
# 退出码：0=全过 1=有失败（CI 可直接用）
set -uo pipefail
cd "$(dirname "$0")/.."

B="${REMOTE_HOST:-http://localhost:8090}"
TOKEN=$(grep '^API_TOKEN=' .env | cut -d= -f2)
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

# 5. 充电列表含损耗率
CH=$(curl -s -m 8 -H "Authorization: Bearer $TOKEN" "$B/api/v1/cars/1/charges?limit=3")
echo "$CH" | python -c "import json,sys;d=json.load(sys.stdin);assert len(d)>0 and 'loss_percent' in d[0]" 2>/dev/null \
  && ok "charges 含 loss_percent" || fail "charges 含 loss_percent"

# 6. 统计三档都出 summary
for p in week month year; do
  curl -s -m 8 -H "Authorization: Bearer $TOKEN" "$B/api/v1/cars/1/stats?period=$p" \
    | python -c "import json,sys;d=json.load(sys.stdin);assert d.get('summary')" 2>/dev/null \
    && ok "stats $p" || fail "stats $p"
done

# 7. 电池健康：铭牌容量口径
BAT=$(curl -s -m 5 -H "Authorization: Bearer $TOKEN" $B/api/v1/cars/1/battery)
NOM=$(grep '^NOMINAL_BATTERY_KWH=' .env | cut -d= -f2)
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
