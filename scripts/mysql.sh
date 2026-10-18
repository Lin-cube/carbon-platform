#!/usr/bin/env bash
# 项目独立 MySQL 实例管理（端口 3307，数据目录在项目内 .mysql/）
#
# 为什么用独立实例：本机已有一个 MySQL 8.4 服务在 3306 运行且 root 密码未知。
# 为了不改动既有环境，项目在 .mysql/ 下自建独立实例，端口 3307、root 空密码、
# 数据不落系统目录，删除 .mysql/ 即可完全清理。
#
# 用法:
#   ./scripts/mysql.sh start     启动
#   ./scripts/mysql.sh stop      停止
#   ./scripts/mysql.sh status    状态
#   ./scripts/mysql.sh cli       进入 mysql 命令行
#   ./scripts/mysql.sh init      首次初始化（建数据目录 + 建库表 + 导入数据）
#   ./scripts/mysql.sh reset     删除数据目录重新开始（危险）

set -euo pipefail

MYSQL_BIN="/opt/homebrew/opt/mysql@8.4/bin"
PORT=3307
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="$PROJECT_DIR/.mysql"
DATADIR="$BASE/data"
SOCK="$BASE/mysql.sock"
PIDFILE="$BASE/mysqld.pid"
LOGFILE="$BASE/log/error.log"
URL="mysql+pymysql://root@127.0.0.1:${PORT}/carbon_energy?charset=utf8mb4"

export PATH="$MYSQL_BIN:$PATH"

is_up() {
  mysql -u root --port="$PORT" --protocol=tcp -e "SELECT 1" >/dev/null 2>&1
}

case "${1:-status}" in
  start)
    if is_up; then echo "MySQL 已在运行 (端口 $PORT)"; exit 0; fi
    mkdir -p "$BASE/log" "$BASE/tmp"
    mysqld --datadir="$DATADIR" --port="$PORT" --socket="$SOCK" --mysqlx=OFF \
           --log-error="$LOGFILE" --pid-file="$PIDFILE" --bind-address=127.0.0.1 \
           >/dev/null 2>&1 &
    for _ in $(seq 1 30); do is_up && { echo "✅ MySQL 已启动 (端口 $PORT)"; exit 0; }; sleep 1; done
    echo "❌ 启动失败，请查看 $LOGFILE"; exit 1
    ;;
  stop)
    if [ -f "$PIDFILE" ]; then
      kill "$(cat "$PIDFILE")" 2>/dev/null || true
      for _ in $(seq 1 20); do is_up || { echo "✅ 已停止"; exit 0; }; sleep 1; done
      echo "⚠️ 仍未停止，尝试强制"; kill -9 "$(cat "$PIDFILE")" 2>/dev/null || true
    else
      echo "未找到 pid 文件，MySQL 可能未启动"
    fi
    ;;
  status)
    if is_up; then
      echo "✅ 运行中 (端口 $PORT)"
      mysql -u root --port="$PORT" --protocol=tcp -e \
        "SELECT table_name AS 表, table_rows AS 约行数 FROM information_schema.tables
         WHERE table_schema='carbon_energy' ORDER BY table_rows DESC;" 2>/dev/null | head -20
    else
      echo "⭕ 未运行"
    fi
    ;;
  cli)
    mysql -u root --port="$PORT" --protocol=tcp carbon_energy
    ;;
  init)
    if [ ! -d "$DATADIR/mysql" ]; then
      echo "初始化数据目录 ..."
      mkdir -p "$BASE/log" "$BASE/tmp"
      mysqld --initialize-insecure --datadir="$DATADIR" --basedir=/opt/homebrew/opt/mysql@8.4
    fi
    "$0" start
    echo "建库建表 ..."
    mysql -u root --port="$PORT" --protocol=tcp < "$PROJECT_DIR/data-pipeline/sql/01_schema.sql"
    echo "导入数据（约 600 万行，需数分钟）..."
    CARBON_MYSQL_URL="$URL" "$PROJECT_DIR/.venv/bin/python" \
      "$PROJECT_DIR/data-pipeline/scripts/07_load_to_mysql.py"
    ;;
  reset)
    read -r -p "确认删除 $DATADIR 下全部数据？[y/N] " ans
    [ "$ans" = "y" ] || { echo "已取消"; exit 0; }
    "$0" stop || true
    rm -rf "$BASE"
    echo "已删除，请重新执行 $0 init"
    ;;
  *)
    echo "用法: $0 {start|stop|status|cli|init|reset}"; exit 1
    ;;
esac
