#!/usr/bin/env bash
# 一键启动开发环境（MySQL + 后端 + 前端 + RAG 服务）
#
# 用法:
#   ./scripts/start-all.sh          前台启动（Ctrl+C 结束全部）
#   ./scripts/start-all.sh -d       后台启动（日志写入 .logs/）
#   ./scripts/start-all.sh daemon   完整启动（launchctl 托管，关闭终端不停止）【日常推荐】
#   ./scripts/start-all.sh dev      只启动依赖（MySQL/前端/RAG），后端留给 IDE【写代码时用】
#   ./scripts/start-all.sh stop     停止后台启动的服务
#   ./scripts/start-all.sh status   查看各服务状态
#
# 端口约定:
#   MySQL   3307（项目独立实例，见 scripts/mysql.sh）
#   后端     8080（Spring Boot）
#   前端     5173（Vite dev server，/api 反向代理到 8080）
#   RAG     8000（FastAPI 知识库检索服务，后端 carbon.ai.mode=hybrid 时使用）
#
# RAG 服务说明:
#   首次使用需先构建索引: .venv/bin/python rag-service/src/build_index.py
#   未构建索引时本脚本会提示并跳过 RAG 服务；后端会自动降级为纯本地统计引擎。

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 加载本地密钥（大模型 API Key 等）。
# secrets/local.env 已被 .gitignore 忽略且权限为 600，密钥不进入代码库。
if [ -f "$PROJECT_DIR/secrets/local.env" ]; then
  # shellcheck disable=SC1091
  set +u; source "$PROJECT_DIR/secrets/local.env"; set -u
  echo "[密钥] 已加载 secrets/local.env（LLM provider=${CARBON_LLM_PROVIDER:-none}）"
fi
LOG_DIR="$PROJECT_DIR/.logs"
BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"
RAG_LOG="$LOG_DIR/rag.log"
MVN_SETTINGS="$PROJECT_DIR/scripts/maven-settings.xml"

mkdir -p "$LOG_DIR"

port_up() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

wait_port() {
  local port="$1" name="$2" tries="${3:-40}"
  for _ in $(seq 1 "$tries"); do
    if port_up "$port"; then echo "  ✅ $name 已就绪 (端口 $port)"; return 0; fi
    sleep 2
  done
  echo "  ⚠️  $name 在端口 $port 等待超时，请查看日志"; return 1
}

start_mysql() {
  echo "[1/4] MySQL"
  if port_up 3307; then echo "  ✅ 已在运行 (端口 3307)"; else
    "$PROJECT_DIR/scripts/mysql.sh" start
  fi
}

start_backend() {
  echo "[2/4] 后端 Spring Boot"
  if port_up 8080; then echo "  ✅ 已在运行 (端口 8080)"; return 0; fi
  ( cd "$PROJECT_DIR/backend" \
    && nohup mvn -s "$MVN_SETTINGS" -B spring-boot:run > "$BACKEND_LOG" 2>&1 & )
  wait_port 8080 "后端"
}

start_frontend() {
  echo "[3/4] 前端 Vite"
  if port_up 5173; then echo "  ✅ 已在运行 (端口 5173)"; return 0; fi
  ( cd "$PROJECT_DIR/frontend" \
    && nohup pnpm run dev > "$FRONTEND_LOG" 2>&1 & )
  wait_port 5173 "前端"
}

start_rag() {
  echo "[4/4] RAG 知识库服务 FastAPI"
  if port_up 8000; then echo "  ✅ 已在运行 (端口 8000)"; return 0; fi
  if [ ! -f "$PROJECT_DIR/rag-service/data/bm25.pkl" ]; then
    echo "  ⚠️  未找到 RAG 索引，跳过。请先执行："
    echo "      .venv/bin/python rag-service/src/build_index.py"
    echo "      （后端将自动降级为纯本地统计引擎，不影响其它功能）"
    return 0
  fi
  ( cd "$PROJECT_DIR/rag-service/src" \
    && nohup "$PROJECT_DIR/.venv/bin/python" -m uvicorn app:app \
         --host 127.0.0.1 --port 8000 > "$RAG_LOG" 2>&1 & )
  wait_port 8000 "RAG 服务"
}

stop_all() {
  echo "停止服务 ..."
  # 1) 取消 launchctl 注册（macOS 下最稳的常驻方式）
  for svc in carbon-backend carbon-frontend carbon-rag; do
    launchctl remove "$svc" 2>/dev/null || true
  done

  # 2) 按命令行杀（launchctl remove 只取消注册，派生的子进程可能仍存活）
  pkill -f "spring-boot:run" 2>/dev/null || true
  pkill -f "CarbonPlatformApplication" 2>/dev/null || true
  pkill -f "carbon-platform-backend" 2>/dev/null || true
  pkill -f "uvicorn app:app" 2>/dev/null || true
  pkill -f "vite" 2>/dev/null || true

  # 3) 按端口兜底强杀（pkill 按命令行匹配不一定命中 Java 的启动形式）
  #    注意：macOS 的 xargs 不支持 -r，这里用 read 逐行处理
  for p in 8080 5173 8000; do
    pids=$(lsof -ti "tcp:${p}" 2>/dev/null || true)
    if [ -n "$pids" ]; then
      echo "  释放端口 ${p}（PID: $(echo "$pids" | tr '\n' ' ')）"
      for pid in $pids; do
        kill -9 "$pid" 2>/dev/null || true
      done
    fi
  done

  sleep 1
  "$PROJECT_DIR/scripts/mysql.sh" stop || true
  echo "已停止（MySQL 亦已停止）"
}

status_all() {
  echo "服务状态:"
  port_up 3307 && echo "  ✅ MySQL   3307" || echo "  ⭕ MySQL   3307 未运行"
  port_up 8080 && echo "  ✅ 后端     8080" || echo "  ⭕ 后端     8080 未运行"
  port_up 5173 && echo "  ✅ 前端     5173" || echo "  ⭕ 前端     5173 未运行"
  if port_up 8000; then
    echo "  ✅ RAG     8000"
  elif [ -f "$PROJECT_DIR/rag-service/data/bm25.pkl" ]; then
    echo "  ⭕ RAG     8000 未运行"
  else
    echo "  ➖ RAG     8000 未构建索引（可选）"
  fi
  echo
  echo "访问地址: http://127.0.0.1:5173   （接口文档 http://127.0.0.1:8080/swagger-ui/index.html）"
  echo "RAG 服务: http://127.0.0.1:8000/health   （知识库检索，供 /api/ai/chat 知识类问题使用）"
  echo "演示账号: admin / admin123   operator / operator123   viewer / viewer123"
}

# 生成 launchctl 包装脚本（用绝对路径，避免后台进程找不到 mvn/pnpm）
write_wrappers() {
  local W="$LOG_DIR"
  cat > "$W/run-backend.sh" <<EOS
#!/bin/bash
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
cd "$PROJECT_DIR"
[ -f secrets/local.env ] && { set -a; source secrets/local.env; set +a; }
cd backend
exec /opt/homebrew/bin/mvn -s ../scripts/maven-settings.xml -B spring-boot:run
EOS
  # pnpm 用绝对路径：它常装在 ~/.npm-global/bin，不在 launchd 的默认 PATH 里。
  # 曾用 \$(command -v pnpm) 在脚本运行时解析，但 launchd 环境下解析为空，
  # 结果 exec 变成 "exec run dev"，报 "run: not found"（实测踩过）。
  local PNPM_BIN
  PNPM_BIN="$(command -v pnpm || true)"
  [ -z "$PNPM_BIN" ] && [ -x "$HOME/.npm-global/bin/pnpm" ] && PNPM_BIN="$HOME/.npm-global/bin/pnpm"
  [ -z "$PNPM_BIN" ] && PNPM_BIN="pnpm"
  cat > "$W/run-frontend.sh" <<EOS
#!/bin/bash
export PATH="$HOME/.npm-global/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
cd "$PROJECT_DIR/frontend"
exec "$PNPM_BIN" run dev
EOS
  cat > "$W/run-rag.sh" <<EOS
#!/bin/bash
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
cd "$PROJECT_DIR"
[ -f secrets/local.env ] && { set -a; source secrets/local.env; set +a; }
cd rag-service/src
exec ../../.venv/bin/python -m uvicorn app:app --host 127.0.0.1 --port 8000
EOS
  chmod +x "$W"/run-*.sh
}

# 用 launchctl 常驻启动：进程归属 launchd，不随终端关闭或父进程退出被回收
start_daemon() {
  echo "[常驻] 通过 launchctl 注册服务（关闭终端不影响）"
  write_wrappers
  launchctl remove carbon-backend  2>/dev/null || true
  launchctl remove carbon-frontend 2>/dev/null || true
  launchctl remove carbon-rag      2>/dev/null || true
  launchctl submit -l carbon-backend  -o "$LOG_DIR/backend.log"  -e "$LOG_DIR/backend.err.log"  -- "$LOG_DIR/run-backend.sh"
  launchctl submit -l carbon-frontend -o "$LOG_DIR/frontend.log" -e "$LOG_DIR/frontend.err.log" -- "$LOG_DIR/run-frontend.sh"
  if [ -f "$PROJECT_DIR/rag-service/data/bm25.pkl" ]; then
    launchctl submit -l carbon-rag -o "$LOG_DIR/rag.log" -e "$LOG_DIR/rag.err.log" -- "$LOG_DIR/run-rag.sh"
  fi
  echo "  等待服务就绪 ..."
  wait_port 3307 "MySQL" 20 || true
  wait_port 8080 "后端" 60 || true
  wait_port 5173 "前端" 60 || true
  if [ -f "$PROJECT_DIR/rag-service/data/bm25.pkl" ]; then
    wait_port 8000 "RAG 服务" 40 || true
  fi
}

case "${1:-start}" in
  stop)   stop_all ;;
  daemon) start_mysql; start_daemon; echo; status_all ;;
  dev)
    # 开发模式：启动「依赖服务」，后端留给 IDE（VSCode / IDEA）自己跑。
    # 好处：① 不占 8080，避免 IDE 启动报端口冲突；② 可打断点调试；
    #       ③ 改代码后 IDE 自动重编译，比反复 mvn 快。
    echo "[开发模式] 启动依赖服务（MySQL + 前端 + RAG），后端请在 IDE 中启动"
    start_mysql
    write_wrappers
    for svc in carbon-frontend carbon-rag; do
      launchctl remove "$svc" 2>/dev/null || true
    done
    launchctl submit -l carbon-frontend -o "$LOG_DIR/frontend.log" \
      -e "$LOG_DIR/frontend.err.log" -- "$LOG_DIR/run-frontend.sh"
    if [ -f "$PROJECT_DIR/rag-service/data/bm25.pkl" ]; then
      launchctl submit -l carbon-rag -o "$LOG_DIR/rag.log" \
        -e "$LOG_DIR/rag.err.log" -- "$LOG_DIR/run-rag.sh"
    else
      echo "  ⚠️  未找到 RAG 索引，跳过 RAG 服务"
    fi
    wait_port 5173 "前端" 40 || true
    if [ -f "$PROJECT_DIR/rag-service/data/bm25.pkl" ]; then
      wait_port 8000 "RAG 服务" 30 || true
    fi
    if port_up 8080; then
      echo "  ⚠️  8080 已被占用，IDE 启动后端会失败。请先执行：$0 stop"
    else
      echo "  ✅ 8080 空闲，可以在 IDE 中启动后端了"
    fi
    echo
    status_all
    echo
    echo "下一步：VSCode 打开 CarbonPlatformApplication.java → 点 ▶ 运行"
    echo "        或命令行：cd backend && mvn -s ../scripts/maven-settings.xml spring-boot:run"
    ;;
  status) status_all ;;
  -d)
    start_mysql; start_backend; start_frontend; start_rag
    echo
    echo "后台启动完成，日志:"
    echo "  后端 $BACKEND_LOG"
    echo "  前端 $FRONTEND_LOG"
    echo "  RAG  $RAG_LOG"
    status_all
    ;;
  start)
    start_mysql; start_backend; start_frontend; start_rag
    echo
    status_all
    echo
    echo "按 Ctrl+C 结束本次会话（后台进程不会被自动杀掉，如需停止请执行 $0 stop）"
    wait
    ;;
  *)
    cat <<USAGE
用法: $0 {命令}

  daemon   完整启动（MySQL + 后端 + 前端 + RAG），launchctl 托管，关终端不停止  ← 日常最常用
  dev      只启动依赖（MySQL + 前端 + RAG），后端留给 IDE 调试              ← 写后端代码时用
  stop     停止全部服务
  status   查看状态
  -d       后台启动（nohup 方式，终端退出可能被回收，建议用 daemon）
  start    前台启动（Ctrl+C 结束）

USAGE
    exit 1
    ;;
esac
