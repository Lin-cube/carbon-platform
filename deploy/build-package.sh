#!/usr/bin/env bash
# ================================================================
# 本地打包（在你这台 Mac 上执行，不是在服务器上）
#
# 用法：
#   ./deploy/build-package.sh
#
# 产出：
#   deploy/out/carbon-platform-deploy-<日期>.tar.gz   （约 320MB）
#   以及同目录下的 SHA256 校验文件
#
# 为什么在本地构建而不是服务器上构建：
#   1. 服务器可能是 2 核 2G，mvn package 和 vite build 会吃掉 1.5G+ 内存，
#      容易 OOM 甚至把 MySQL 挤挂；
#   2. 本地有完整的 Maven 缓存和 node_modules，构建只要 1~2 分钟；
#   3. 服务器只需要运行，不需要构建工具链，更安全。
# ================================================================
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

OUT_DIR="$PROJECT_DIR/deploy/out"
STAGE="$OUT_DIR/stage"
DATE=$(date +%Y%m%d)
PKG="carbon-platform-deploy-$DATE.tar.gz"

green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }

bold "════════ 1/6 检查环境 ════════"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
export PATH="/usr/local/bin:/opt/homebrew/bin:/opt/homebrew/opt/mysql@8.4/bin:$HOME/.npm-global/bin:$PATH"

# node 不一定在 Homebrew 目录（可能是官方安装包放在 /usr/local/bin，
# 也可能是 nvm/fnm 管理的路径）。这里显式解析，避免出现
# "vue-tsc: exec: node: not found" 这类问题（实测踩过）。
if ! command -v node >/dev/null 2>&1; then
    for c in /usr/local/bin/node /opt/homebrew/bin/node \
             "$HOME"/.nvm/versions/node/*/bin/node \
             "$HOME"/.local/share/fnm/node-versions/*/installation/bin/node; do
        if [ -x "$c" ]; then
            export PATH="$(dirname "$c"):$PATH"
            break
        fi
    done
fi
command -v node >/dev/null || { red "缺少 node（前端构建需要）"; exit 1; }
echo "  node=$(node -v) at $(command -v node)"

command -v mvn  >/dev/null || { red "缺少 mvn"; exit 1; }
command -v pnpm >/dev/null || { red "缺少 pnpm"; exit 1; }
command -v mysqldump >/dev/null || { red "缺少 mysqldump（MySQL 8.4 不在 PATH？）"; exit 1; }
echo "  JAVA_HOME=$JAVA_HOME"
echo "  mvn=$(mvn -v 2>/dev/null | head -1)"
echo "  pnpm=$(pnpm -v)"

bold "════════ 2/6 构建后端 jar ════════"
cd backend
# 注意：必须跳过测试（测试需要连数据库，打包环境未必可用）
mvn -s ../scripts/maven-settings.xml -B -q clean package -DskipTests
JAR=$(ls target/*.jar | grep -v original | head -1)
echo "  jar: $JAR ($(du -h "$JAR" | cut -f1))"
cd ..

bold "════════ 3/6 构建前端 dist ════════"
cd frontend
pnpm run build 2>&1 | tail -3
echo "  dist: $(du -sh dist | cut -f1)"
cd ..

bold "════════ 4/6 导出数据库 ════════"
mkdir -p "$STAGE"
mysqldump -u root --port=3307 --protocol=tcp \
    --single-transaction --quick --set-gtid-purged=OFF \
    --default-character-set=utf8mb4 \
    carbon_energy 2>/dev/null | gzip -1 > "$STAGE/carbon_energy.sql.gz"
echo "  数据库: $(du -h "$STAGE/carbon_energy.sql.gz" | cut -f1)（解压后约 2.8GB）"

bold "════════ 5/6 组装部署包 ════════"
# 后端
cp "backend/$JAR" "$STAGE/backend.jar"
# 前端
rm -rf "$STAGE/dist"; cp -r frontend/dist "$STAGE/dist"
# RAG：代码 + 语料 + 已建好的索引（省去服务器上重新构建索引的时间与内存）
rm -rf "$STAGE/rag-service"; mkdir -p "$STAGE/rag-service"
cp -r rag-service/src "$STAGE/rag-service/src"
# 清掉 Python 字节码缓存（本地是 3.14 的 .pyc，服务器上不通用还占空间）
find "$STAGE/rag-service/src" -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null || true
find "$STAGE/rag-service/src" -name "*.pyc" -delete 2>/dev/null || true
cp -r rag-service/corpus "$STAGE/rag-service/corpus"
cp -r rag-service/data "$STAGE/rag-service/data"
cp rag-service/requirements.txt "$STAGE/rag-service/requirements.txt" 2>/dev/null || true
# systemd 单元与 nginx 配置
cp -r deploy/systemd "$STAGE/systemd"
cp -r deploy/nginx "$STAGE/nginx"
# 部署脚本（服务器上要执行）
cp deploy/server-setup.sh "$STAGE/server-setup.sh"
chmod +x "$STAGE/server-setup.sh"

# 密钥：生成 systemd 兼容版本（去掉 export 前缀）
mkdir -p "$STAGE/secrets"
if [ -f secrets/local.env ]; then
    sed -E 's/^[[:space:]]*export[[:space:]]+//' secrets/local.env \
        > "$STAGE/secrets/local.env.systemd"
    chmod 600 "$STAGE/secrets/local.env.systemd"
    echo "  ✅ 已生成 secrets/local.env.systemd（去 export，供 systemd 使用）"
    # 安全检查：确认里面没有残留的占位符
    if grep -q "在此填入" "$STAGE/secrets/local.env.systemd"; then
        red "  ❌ 密钥文件里还是占位符，请先填好 secrets/local.env"
        exit 1
    fi
else
    red "  ❌ 找不到 secrets/local.env，无法打包密钥"
    exit 1
fi

# 部署说明
cp deploy/README.md "$STAGE/DEPLOY.md" 2>/dev/null || true

tar -C "$STAGE" -czf "$OUT_DIR/$PKG" .
cd "$OUT_DIR" && shasum -a 256 "$PKG" > "$PKG.sha256"

bold "════════ 6/6 完成 ════════"
echo "  部署包: $OUT_DIR/$PKG"
echo "  大小:   $(du -h "$OUT_DIR/$PKG" | cut -f1)"
echo "  校验:   $(cat "$OUT_DIR/$PKG.sha256")"
echo
echo "上传到服务器："
echo "  scp $OUT_DIR/$PKG root@<服务器IP>:/root/"
echo
echo "然后在服务器上执行："
echo "  cd /root && tar -xzf $PKG -C /opt/carbon-platform --strip-components=0"
echo "  bash /opt/carbon-platform/server-setup.sh"
