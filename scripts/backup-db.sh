#!/usr/bin/env bash
# 数据库备份（pg_dump）。建议 Windows 任务计划每月跑一次 + 任何动库操作前手动跑。
# 用法：bash scripts/backup-db.sh [备注]
# 产物：$BACKUP_DIR/db-YYYYMMDD[-备注].sql（默认 /c/tm-backups，**不在 OneDrive 内**）
set -euo pipefail
cd "$(dirname "$0")/.."

NOTE="${1:-}"
STAMP=$(date +%Y%m%d)
# ⚠️ 备份目录**必须在 OneDrive 同步路径之外**。
# 原来写在仓库里的 backups/ —— 而仓库在 C:\Users\<用户>\OneDrive\文档\ 下，
# 于是 200MB 级的 pg_dump 全文（含**全部精确位置历史**、充电记录、行程，
# 以及 private.tokens 里 Cloak 加密的 TeslaMate 凭据）会被 OneDrive
# **自动上传到云端** —— 与「私有部署、不上云」的初衷正好相反。
# 改成写到 C:\tm-backups（不在任何同步盘里），用 TM_BACKUP_DIR 可覆盖。
BACKUP_DIR="${TM_BACKUP_DIR:-/c/tm-backups}"
OUT="$BACKUP_DIR/db-${STAMP}${NOTE:+-}${NOTE}.sql"

mkdir -p "$BACKUP_DIR"
echo "→ pg_dump 到 $OUT（库大时需数十秒）"
docker exec teslamate-database-1 pg_dump -U teslamate -d teslamate > "$OUT"

SIZE=$(stat -c%s "$OUT")
if [ "$SIZE" -lt 10000 ]; then
  echo "❌ 备份只有 ${SIZE} 字节，疑似失败，已删除"
  rm "$OUT"; exit 1
fi
echo "✅ 备份完成：$OUT（$((SIZE/1024/1024)) MB）"
echo "   恢复方法见 docs/operations.md §2.2"
ls -lt "$BACKUP_DIR"/ | head -4
