#!/usr/bin/env bash
# ================================================================
# 服务器初始化（在服务器上执行，Ubuntu 22.04 / 24.04）
#
# 用法：
#   tar -xzf carbon-platform-deploy-*.tar.gz -C /opt/carbon-platform
#   sudo bash /opt/carbon-platform/server-setup.sh
#
# 做的事：
#   1. 创建 carbon 用户与目录
#   2. 安装 JDK 21 / Python 3 / MySQL 8.0 / Nginx
#   3. 建 Python venv 并装依赖
#   4. 导入数据库
#   5. 安装 systemd 服务
#   6. 输出后续步骤（域名 + SSL）
#
# 幂等：重复执行不会破坏已有数据（导入数据库会先检查表是否存在）。
# ================================================================
set -euo pipefail

APP_DIR="/opt/carbon-platform"
DB_NAME="carbon_energy"
DB_USER="carbon"
DB_PASS_FILE="$APP_DIR/secrets/db_password.txt"

green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
warn()  { printf '\033[33m%s\033[0m\n' "$*"; }

[ "$(id -u)" = "0" ] || { red "请用 root 执行：sudo bash $0"; exit 1; }

bold "════════ 0/7 检查系统 ════════"
. /etc/os-release 2>/dev/null || true
echo "  系统: ${PRETTY_NAME:-unknown}"
echo "  内存: $(free -h | awk '/^Mem:/{print $2}')"
echo "  磁盘: $(df -h / | awk 'NR==2{print $2" (可用 "$4")"}')"
TOTAL_MEM_MB=$(free -m | awk '/^Mem:/{print $2}')
if [ "$TOTAL_MEM_MB" -lt 1800 ]; then
    warn "  内存 ${TOTAL_MEM_MB}MB 偏小，建议至少 2GB；若为 2GB 请确认已开 swap"
fi

bold "════════ 1/7 创建运行用户与目录 ════════"
id carbon >/dev/null 2>&1 || useradd -r -m -d "$APP_DIR" -s /bin/bash carbon
mkdir -p "$APP_DIR"/{logs,dist,secrets,rag-service}
chown -R carbon:carbon "$APP_DIR"
echo "  ✅ 用户 carbon，目录 $APP_DIR"

bold "════════ 2/7 安装基础软件 ════════"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq

# ---- JDK 21（Adoptium 官方源）----
if ! java -version 2>&1 | grep -q '"21'; then
    apt-get install -y -qq wget apt-transport-https gpg >/dev/null
    mkdir -p /etc/apt/keyrings
    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor > /etc/apt/keyrings/adoptium.gpg
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
        > /etc/apt/sources.list.d/adoptium.list
    apt-get update -qq
    apt-get install -y -qq temurin-21-jdk >/dev/null
fi
echo "  ✅ JDK: $(java -version 2>&1 | head -1)"

# ---- Python ----
apt-get install -y -qq python3 python3-venv python3-pip >/dev/null
echo "  ✅ Python: $(python3 --version)"

# ---- Nginx ----
command -v nginx >/dev/null || apt-get install -y -qq nginx >/dev/null
echo "  ✅ Nginx: $(nginx -v 2>&1 | sed 's/nginx version: //')"

# ---- MySQL 8.0 ----
if ! command -v mysql >/dev/null; then
    apt-get install -y -qq mysql-server >/dev/null
fi
MYSQL_VER=$(mysql --version | grep -oE '[0-9]+\.[0-9]+' | head -1)
echo "  ✅ MySQL: $MYSQL_VER"
if [ "${MYSQL_VER%%.*}" -lt 8 ]; then
    red "  ❌ MySQL 版本低于 8.0，本项目用了窗口函数 LAG() 与 CTE，必须 8.0+"
    exit 1
fi
systemctl enable --now mysql >/dev/null 2>&1 || true

bold "════════ 3/7 配置数据库 ════════"
# 生成随机密码（首次）
if [ ! -f "$DB_PASS_FILE" ]; then
    openssl rand -base64 24 | tr -d '/+=' | head -c 24 > "$DB_PASS_FILE"
    chmod 600 "$DB_PASS_FILE"
    chown carbon:carbon "$DB_PASS_FILE"
fi
DB_PASS=$(cat "$DB_PASS_FILE")

mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS ${DB_NAME}
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL
echo "  ✅ 数据库 ${DB_NAME} 与用户 ${DB_USER} 就绪"

# 导入数据（仅在表为空时）
TABLE_CNT=$(mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}';")
if [ "$TABLE_CNT" -eq 0 ]; then
    echo "  正在导入数据（约 2.8GB，视磁盘性能需 3~10 分钟）..."
    gunzip -c "$APP_DIR/carbon_energy.sql.gz" | mysql -uroot "$DB_NAME"
    ROWS=$(mysql -uroot -N -e "SELECT FORMAT(COUNT(*),0) FROM ${DB_NAME}.fact_energy_hourly;")
    echo "  ✅ 导入完成，fact_energy_hourly = $ROWS 行"
else
    echo "  ⏭  数据库已有 $TABLE_CNT 张表，跳过导入（如需重导请先手动 drop database）"
fi

bold "════════ 4/7 配置 Python 环境 ════════"
if [ ! -x "$APP_DIR/venv/bin/python" ]; then
    python3 -m venv "$APP_DIR/venv"
fi
# 用国内镜像加速
"$APP_DIR/venv/bin/pip" install -q --upgrade pip \
    -i https://pypi.tuna.tsinghua.edu.cn/simple 2>/dev/null || true
if [ -f "$APP_DIR/rag-service/requirements.txt" ]; then
    "$APP_DIR/venv/bin/pip" install -q -r "$APP_DIR/rag-service/requirements.txt" \
        -i https://pypi.tuna.tsinghua.edu.cn/simple
else
    "$APP_DIR/venv/bin/pip" install -q \
        jieba rank_bm25 scikit-learn scipy numpy \
        fastapi "uvicorn[standard]" \
        -i https://pypi.tuna.tsinghua.edu.cn/simple
fi
echo "  ✅ venv 就绪: $("$APP_DIR/venv/bin/python" --version)"

bold "════════ 5/7 接入密钥配置 ════════"
ENV_FILE="$APP_DIR/secrets/local.env.systemd"
touch "$ENV_FILE"; chmod 600 "$ENV_FILE"; chown carbon:carbon "$ENV_FILE"

# 数据库连接信息（systemd EnvironmentFile 格式，不能有 export）
if ! grep -q "CARBON_DB_URL" "$ENV_FILE"; then
    cat >> "$ENV_FILE" <<ENV

# ── 数据库（由 server-setup.sh 自动写入）──
CARBON_DB_URL=jdbc:mysql://127.0.0.1:3306/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true
CARBON_DB_USER=${DB_USER}
CARBON_DB_PASSWORD=${DB_PASS}
ENV
    echo "  ✅ 已写入数据库连接配置"
fi

if ! grep -q "^CARBON_JWT_SECRET" "$ENV_FILE"; then
    JWT=$(openssl rand -base64 48 | tr -d '\n/+=' | head -c 48)
    echo "CARBON_JWT_SECRET=${JWT}" >> "$ENV_FILE"
    echo "  ✅ 已生成随机 JWT 密钥"
fi

if grep -q "在此填入" "$ENV_FILE"; then
    warn "  ⚠️  大模型 API Key 仍是占位符，AI 功能会降级（规则引擎）"
    warn "     编辑 $ENV_FILE 填入 CARBON_LLM_KEY 后执行 systemctl restart carbon-backend carbon-rag"
fi
chown carbon:carbon "$ENV_FILE"

bold "════════ 6/7 安装并启动服务 ════════"
cp "$APP_DIR/systemd/carbon-backend.service" /etc/systemd/system/
cp "$APP_DIR/systemd/carbon-rag.service"     /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now carbon-backend carbon-rag
sleep 5
systemctl --no-pager --lines=0 status carbon-backend carbon-rag | sed 's/^/  /'

bold "════════ 7/7 配置 Nginx ════════"
if [ -f "$APP_DIR/nginx/carbon-platform.conf" ]; then
    cp "$APP_DIR/nginx/carbon-platform.conf" /etc/nginx/sites-available/carbon-platform.conf
    ln -sf /etc/nginx/sites-available/carbon-platform.conf /etc/nginx/sites-enabled/
    rm -f /etc/nginx/sites-enabled/default
    nginx -t && systemctl reload nginx
    echo "  ✅ Nginx 已配置（记得把配置里的 YOUR_DOMAIN.COM 换成你的域名）"
fi

echo
green "════════ 部署完成 ════════"
cat <<TIP

接下来手动做 3 件事：

【1】验证后端是否正常
    curl -s http://127.0.0.1:8080/api/system/health
    应返回 JSON（含 code:200）

【2】换域名
    sed -i 's/YOUR_DOMAIN.COM/你的域名/g' /etc/nginx/sites-available/carbon-platform.conf
    nginx -t && systemctl reload nginx
    并在域名服务商处把 A 记录指向本机公网 IP

【3】申请免费 SSL（HTTPS）
    apt install -y certbot python3-certbot-nginx
    certbot --nginx -d 你的域名
    # certbot 会自动改 nginx 配置并配置 90 天自动续期

查看日志：
    journalctl -u carbon-backend -f
    tail -f /opt/carbon-platform/logs/backend.log
    tail -f /opt/carbon-platform/logs/rag.log

重启：
    systemctl restart carbon-backend carbon-rag

TIP
