#!/usr/bin/env bash
# ================================================================
# 一键部署（在本地 Mac 上执行）
#
# 用法：
#   ./deploy/one-click.sh <服务器IP> [--port 2222] [--rebuild] [--skip-db]
#
# 示例：
#   ./deploy/one-click.sh 47.243.100.20
#   ./deploy/one-click.sh 47.243.100.20 --rebuild      # 强制重新打包
#   ./deploy/one-click.sh 47.243.100.20 --skip-db      # 不传数据库（已有数据时用）
#   ./deploy/one-click.sh 47.243.100.20 --port 2222    # SSH 走非标准端口
#
# 前置条件：
#   1. 你的公钥已加到服务器 ~/.ssh/authorized_keys
#   2. 本机能免密登录：ssh root@<IP> 能直接进
#
# 做的事：
#   1. 检查 SSH 连通性与系统环境
#   2. 本地打包（若包不存在或 --rebuild）
#   3. 上传部署包
#   4. 服务器上解压 + 执行 server-setup.sh
#   5. 验证服务与数据
# ================================================================
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

SERVER="${1:-}"
if [ -z "$SERVER" ]; then
    echo "用法: $0 <服务器IP> [--port 2222] [--rebuild] [--skip-db]"
    exit 1
fi
shift || true

REBUILD=0
SKIP_DB=0
SSH_PORT=22
while [ $# -gt 0 ]; do
    case "$1" in
        --rebuild) REBUILD=1; shift ;;
        --skip-db) SKIP_DB=1; shift ;;
        --port)    SSH_PORT="$2"; shift 2 ;;
        *)         shift ;;
    esac
done

SSH_OPTS=(-o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new -o BatchMode=yes)
SSH="ssh ${SSH_OPTS[*]} -p $SSH_PORT root@$SERVER"
REMOTE_DIR="/opt/carbon-platform"

green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
warn()  { printf '\033[33m%s\033[0m\n' "$*"; }

# ════════════════════════════════════════════════════════════════
bold "════════ 1/6 检查 SSH 连通性 ════════"
if ! $SSH "echo ok" >/dev/null 2>&1; then
    red "  ❌ 无法免密登录 root@$SERVER"
    echo
    echo "  排查顺序："
    echo "    1. 手动试一次：ssh root@$SERVER"
    echo "    2. 若提示 password，说明公钥没加成功，重新在 VNC 里执行："
    echo "       mkdir -p ~/.ssh && chmod 700 ~/.ssh"
    echo "       echo 'ssh-ed25519 AAAA...你的公钥' >> ~/.ssh/authorized_keys"
    echo "       chmod 600 ~/.ssh/authorized_keys"
    echo "    3. 若提示 Connection refused / timeout，检查雨云安全组是否放行 22 端口"
    exit 1
fi
green "  ✅ SSH 免密登录成功"

bold "════════ 2/6 检查服务器环境 ════════"
$SSH 'bash -s' <<'REMOTE_CHECK'
. /etc/os-release 2>/dev/null || true
echo "  系统:  ${PRETTY_NAME:-unknown}"
echo "  内核:  $(uname -r)"
echo "  内存:  $(free -h | awk '/^Mem:/{print $2"（可用 "$7"）"}')"
echo "  磁盘:  $(df -h / | awk 'NR==2{print $2"（可用 "$4"）"}')"
echo "  架构:  $(uname -m)"
echo "  已装:  $(command -v java >/dev/null && echo -n "java " ; \
              command -v nginx >/dev/null && echo -n "nginx " ; \
              command -v mysql >/dev/null && echo -n "mysql " ; \
              command -v python3 >/dev/null && echo -n "python3 "; echo)"
if grep -qi ubuntu /etc/os-release 2>/dev/null; then
    echo "  OS_CHECK=ubuntu"
else
    echo "  OS_CHECK=other"
fi
REMOTE_CHECK

# 系统必须是 Ubuntu，否则 server-setup.sh 的 apt 命令会失败
if ! $SSH "grep -qi ubuntu /etc/os-release" 2>/dev/null; then
    red "  ❌ 服务器不是 Ubuntu，server-setup.sh 用的是 apt 包管理"
    warn "     如果是 CentOS/Debian，需要改造脚本；建议在雨云控制台重装为 Ubuntu 22.04"
    exit 1
fi

# ════════════════════════════════════════════════════════════════
bold "════════ 3/6 本地打包 ════════"
PKG=$(ls -t deploy/out/carbon-platform-deploy-*.tar.gz 2>/dev/null | head -1 || true)
if [ "$REBUILD" = "1" ] || [ -z "$PKG" ]; then
    ./deploy/build-package.sh
    PKG=$(ls -t deploy/out/carbon-platform-deploy-*.tar.gz | head -1)
else
    echo "  复用已有包: $PKG ($(du -h "$PKG" | cut -f1))"
    echo "  如需重新构建：$0 $SERVER --rebuild"
fi

# --skip-db：从包里剔除数据库导出，减小上传体积
if [ "$SKIP_DB" = "1" ]; then
    warn "  --skip-db：本次不上传数据库（服务器需已有数据）"
    SEND_PKG="${PKG%.tar.gz}-nodb.tar.gz"
    tar -tzf "$PKG" | grep -v "carbon_energy.sql.gz" | tar -czf "$SEND_PKG" -T - 2>/dev/null || true
    if [ -s "$SEND_PKG" ]; then
        PKG="$SEND_PKG"
        echo "  已生成不含数据库的包: $(du -h "$PKG" | cut -f1)"
    else
        warn "  剔除数据库失败，改用完整包"
        PKG=$(ls -t deploy/out/carbon-platform-deploy-*.tar.gz | grep -v nodb | head -1)
    fi
fi

# ════════════════════════════════════════════════════════════════
bold "════════ 4/6 上传 ════════"
PKG_SIZE=$(du -h "$PKG" | cut -f1)
echo "  包大小: $PKG_SIZE"
echo "  正在上传（视上行带宽需要几分钟）..."
if command -v rsync >/dev/null 2>&1; then
    # rsync 支持断点续传，网络中断后重跑本脚本即可继续
    rsync -az --info=progress2 -e "ssh ${SSH_OPTS[*]} -p $SSH_PORT" \
        "$PKG" "root@$SERVER:/root/"
else
    scp "${SSH_OPTS[@]}" -P "$SSH_PORT" "$PKG" "root@$SERVER:/root/"
fi
green "  ✅ 上传完成"

# ════════════════════════════════════════════════════════════════
bold "════════ 5/6 服务器初始化 ════════"
PKG_NAME=$(basename "$PKG")
$SSH "mkdir -p $REMOTE_DIR && tar -xzf /root/$PKG_NAME -C $REMOTE_DIR && bash $REMOTE_DIR/server-setup.sh"

# ════════════════════════════════════════════════════════════════
bold "════════ 6/6 验证 ════════"
sleep 3
$SSH 'bash -s' <<'REMOTE_VERIFY'
echo "  ── 服务状态 ──"
systemctl is-active carbon-backend carbon-rag 2>/dev/null | sed 's/^/    /'

echo "  ── 后端健康 ──"
HEALTH=$(curl -sS --max-time 10 http://127.0.0.1:8080/api/system/health 2>/dev/null || echo "FAIL")
echo "    ${HEALTH:0:120}"

echo "  ── RAG 健康 ──"
RAG=$(curl -sS --max-time 10 http://127.0.0.1:8000/health 2>/dev/null || echo "FAIL")
echo "    ${RAG:0:120}"

echo "  ── 数据行数 ──"
mysql -uroot -N -e "SELECT CONCAT('    fact_energy_hourly = ', FORMAT(COUNT(*),0)) FROM carbon_energy.fact_energy_hourly;" 2>/dev/null || echo "    (数据库未就绪)"

echo "  ── 内存占用 ──"
ps -o rss=,comm= -C java,uvicorn,mysqld 2>/dev/null | awk '{printf "    %-40s %.0f MB\n", $2, $1/1024}' | head -5
REMOTE_VERIFY

echo
green "════════ 部署完成 ════════"
cat <<TIP

服务器 IP: $SERVER

现在可以做的：

【1】内网验证（在服务器上）
    curl -s http://127.0.0.1:8080/api/system/health

【2】浏览器验证（在你 Mac 上，用 IP 先访问）
    http://$SERVER
    → 应该能看到登录页，用 admin / admin123 登录

【3】域名买好后（再执行这两步）
    ssh root@$SERVER
    sed -i 's/YOUR_DOMAIN.COM/你的域名.com/g' /etc/nginx/sites-available/carbon-platform.conf
    nginx -t && systemctl reload nginx
    apt install -y certbot python3-certbot-nginx
    certbot --nginx -d 你的域名.com

【4】看日志
    ssh root@$SERVER 'tail -f /opt/carbon-platform/logs/backend.log'

⚠️  安全提醒：当前 admin/admin123 是公开的演示账号，域名上线后务必改掉。

TIP
