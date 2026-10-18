#!/usr/bin/env bash
# 双击启动碳能智控运维平台（macOS）
#
# 使用方式（任选其一）：
#   1. 在「访达」里双击本文件
#   2. 终端执行：open carbon-platform/启动.command
#   3. 终端执行：./scripts/start-all.sh daemon
#
# 说明：本脚本会打开一个终端窗口，启动 4 个服务后自动用浏览器打开系统首页。
# 关闭终端窗口不会停止服务；要停止请执行 ./scripts/start-all.sh stop

set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

clear
echo "════════════════════════════════════════════════════════════"
echo "  碳能智控运维平台 · 启动中"
echo "════════════════════════════════════════════════════════════"
echo

# 检查前置依赖
echo "[检查] 运行环境"
for cmd in java mvn node pnpm; do
  if command -v "$cmd" >/dev/null 2>&1; then
    printf "  ✅ %-6s %s\n" "$cmd" "$("$cmd" --version 2>&1 | head -1 | cut -c1-40)"
  else
    printf "  ❌ %-6s 未安装，无法启动\n" "$cmd"
    echo
    echo "请先安装缺失的依赖，参考 docs/环境搭建说明.md"
    read -r -p "按回车退出..."
    exit 1
  fi
done
if [ -x ".venv/bin/python" ]; then
  printf "  ✅ %-6s %s\n" "python" "$(.venv/bin/python -V 2>&1)"
else
  printf "  ⚠️  %-6s 未找到 .venv（RAG 服务将不可用，不影响其它功能）\n" "python"
fi
echo

# 启动
./scripts/start-all.sh daemon
START_RC=$?
echo

if [ $START_RC -ne 0 ]; then
  echo "⚠️  部分服务启动失败，请查看 .logs/ 目录下的日志"
  read -r -p "按回车退出..."
  exit 1
fi

# 等待前端可访问
echo "[等待] 前端就绪..."
for _ in $(seq 1 30); do
  if curl -sS --max-time 2 -o /dev/null http://127.0.0.1:5173/ 2>/dev/null; then
    break
  fi
  sleep 2
done

echo
echo "════════════════════════════════════════════════════════════"
echo "  ✅ 启动完成，正在打开浏览器"
echo "════════════════════════════════════════════════════════════"
echo
echo "  系统首页    http://127.0.0.1:5173"
echo "  接口文档    http://127.0.0.1:8080/swagger-ui/index.html"
echo "  RAG 服务    http://127.0.0.1:8000/health"
echo
echo "  登录账号"
echo "    管理员     admin    / admin123"
echo "    运维人员   operator / operator123"
echo "    只读用户   viewer   / viewer123"
echo
echo "  ─────────────────────────────────────────────"
echo "  停止服务：在本目录执行 ./scripts/start-all.sh stop"
echo "  查看日志：.logs/backend.log  .logs/frontend.log  .logs/rag.log"
echo "  ─────────────────────────────────────────────"
echo

sleep 1
open http://127.0.0.1:5173 2>/dev/null || true

echo "（本窗口可以关闭，服务会继续在后台运行）"
echo
