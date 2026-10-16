#!/usr/bin/env bash
# 每周自动备份（由 Windows 计划任务调用，详见 docs/operations.md §9）
#
# 与 backup-db.sh 的分工：
#   - backup-db.sh    单次 pg_dump，语义不变（手动跑也用它）
#   - backup-weekly.sh 计划任务入口 = 跑一次备份 + 轮转清理 + 写日志
#
# 为什么必须有日志：计划任务的输出默认被系统丢弃，出错时无处可查。
# 所有 stdout/stderr 追加进 backups/backup.log。
#
# 轮转策略：保留最近 $KEEP 份（默认 8 份 ≈ 两个月）。
# ⚠️ db-baseline-* 永不删除——那是基线种子，灾难恢复时要从它开始。
#    2026-10-16 建立自动备份时特意加了这条保护。
#
# 环境变量：
#   BACKUP_KEEP=8   保留份数
set -uo pipefail
cd "$(dirname "$0")/.."

KEEP="${BACKUP_KEEP:-8}"
LOG="backups/backup.log"
mkdir -p backups

log() { echo "$*" | tee -a "$LOG"; }

log "──────── $(date '+%Y-%m-%d %H:%M:%S') 计划任务触发（KEEP=$KEEP）"

# 前置检查：docker 不在或容器没跑，直接记失败退出，不要产出一堆错误刷屏
if ! command -v docker >/dev/null 2>&1; then
  log "❌ 找不到 docker 命令（本计划任务依赖 Docker Desktop）"
  exit 1
fi
if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'teslamate-database-1'; then
  log "❌ 容器 teslamate-database-1 未运行（Docker Desktop 是否已启动？）"
  exit 1
fi

# 执行备份
if bash scripts/backup-db.sh >> "$LOG" 2>&1; then
  log "✅ 备份成功"
else
  log "❌ 备份失败，详见上方日志"
  exit 1
fi

# 轮转：只清理 db-YYYYMMDD*.sql，保留 db-baseline-* 与当前最新一份
mapfile -t OLD < <(
  ls -1t backups/db-[0-9]*.sql 2>/dev/null | tail -n +$((KEEP + 1))
)
if [ "${#OLD[@]}" -gt 0 ]; then
  log "轮转清理（保留最近 $KEEP 份，db-baseline-* 永不动）："
  for f in "${OLD[@]}"; do
    rm -f "$f" && log "  已删除 $f"
  done
else
  log "轮转：无需清理"
fi

log "当前备份清单："
ls -1t backups/*.sql 2>/dev/null | head -5 | while read -r f; do
  log "  $f ($(du -h "$f" | cut -f1))"
done
log "──────── 完成"
