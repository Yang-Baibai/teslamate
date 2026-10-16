#!/usr/bin/env bash
# 数据库备份（pg_dump）。建议 Windows 任务计划每月跑一次 + 任何动库操作前手动跑。
# 用法：bash scripts/backup-db.sh [备注]
# 产物：backups/db-YYYYMMDD[-备注].sql
set -euo pipefail
cd "$(dirname "$0")/.."

NOTE="${1:-}"
STAMP=$(date +%Y%m%d)
OUT="backups/db-${STAMP}${NOTE:+-}${NOTE}.sql"

mkdir -p backups
echo "→ pg_dump 到 $OUT（库大时需数十秒）"
docker exec teslamate-database-1 pg_dump -U teslamate -d teslamate > "$OUT"

SIZE=$(stat -c%s "$OUT")
if [ "$SIZE" -lt 10000 ]; then
  echo "❌ 备份只有 ${SIZE} 字节，疑似失败，已删除"
  rm "$OUT"; exit 1
fi
echo "✅ 备份完成：$OUT（$((SIZE/1024/1024)) MB）"
echo "   恢复方法见 docs/operations.md §2.2"
ls -lt backups/ | head -4
