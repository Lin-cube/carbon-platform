#!/usr/bin/env bash
# 一键提交并推送（带推送前安全检查）
#
# 用法：
#   ./scripts/save.sh "fix: 修复了什么问题"           # 只提交到本地（默认）
#   ./scripts/save.sh "fix: ..." --push               # 提交并推送到 GitHub
#   ./scripts/save.sh                                  # 用时间戳作为默认提交信息
#
# 默认【只提交，不推送】。推送必须显式加 --push。
# （用户 2026-10 明确要求：后续不要自动上传 GitHub）
#
# 做的事：
#   1. 安全检查（密钥 / 真实密码 / 本机绝对路径 / 大目录）
#   2. git add -A && commit
#   3. 仅在带 --push 时：git push + 验证 secrets/local.env 在远程 404
#
# 任何一项安全检查不通过就【中止】。

set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

PUSH=0
MSG=""
for arg in "$@"; do
  case "$arg" in
    --push) PUSH=1 ;;
    *) [ -z "$MSG" ] && MSG="$arg" ;;
  esac
done
MSG="${MSG:-chore: 更新（$(date '+%Y-%m-%d %H:%M')）}"
REPO="Lin-cube/carbon-platform"
BRANCH="main"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }

bold "════════ 推送前安全检查 ════════"

FAIL=0

# ── 1. secrets/local.env 不能被跟踪 ──────────────────────────────
if git ls-files --error-unmatch "secrets/local.env" >/dev/null 2>&1; then
  red "  ❌ secrets/local.env 已被 git 跟踪！"
  yellow "     执行：git rm --cached secrets/local.env"
  FAIL=1
else
  green "  ✅ secrets/local.env 未被跟踪"
fi

# ── 2. 密钥不能出现在代码/文档中 ─────────────────────────────────
HITS=$(git grep -InE 'sk-[a-zA-Z0-9]{20,}' -- . 2>/dev/null | grep -v 'local\.env\.example' | head -3)
if [ -n "$HITS" ]; then
  red "  ❌ 发现疑似 API Key："
  echo "$HITS" | sed -E 's/(sk-[a-zA-Z0-9]{6})[a-zA-Z0-9]*/\1***/g' | sed 's/^/     /'
  FAIL=1
else
  green "  ✅ 代码与文档中无 sk- 密钥"
fi

# ── 3. 不能有真实密码（演示账号除外）────────────────────────────
HITS=$(git grep -InE '(password|passwd|secret)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{6,}' -- . 2>/dev/null \
  | grep -viE 'admin123|operator123|viewer123|example|placeholder|change-in-production|please-override|your|在此填入' \
  | head -3)
if [ -n "$HITS" ]; then
  red "  ❌ 发现疑似真实密码："
  echo "$HITS" | sed -E 's/([:=][[:space:]]*["'"'"'])[^"'"'"']{4,}/\1***/g' | sed 's/^/     /'
  FAIL=1
else
  green "  ✅ 无真实密码（演示账号白名单除外）"
fi

# ── 4. 不能有本机绝对路径 ───────────────────────────────────────
# 用字符类而非字面量：既不会命中本脚本自身（"/Users/" 后面跟的是 "["），
# 也不写死具体用户名，换机器不用改脚本。
HITS=$(git grep -InE '/Users/[A-Za-z0-9._-]+/' -- '*.md' '*.sh' '*.yml' '*.json' '*.py' '*.xml' 2>/dev/null | head -3)
if [ -n "$HITS" ]; then
  red "  ❌ 发现本机绝对路径（会泄露用户名）："
  echo "$HITS" | sed 's/^/     /'
  yellow "     处理：把路径改成相对路径，或写成 <项目根目录> 这样的占位符"
  FAIL=1
else
  green "  ✅ 无本机绝对路径"
fi

# ── 5. 大目录不能被跟踪 ─────────────────────────────────────────
BIGBAD=""
for d in .mysql .venv node_modules target rag-service/data .logs; do
  n=$(git ls-files "$d" 2>/dev/null | wc -l | tr -d ' ')
  [ "$n" != "0" ] && BIGBAD="$BIGBAD $d($n)"
done
if [ -n "$BIGBAD" ]; then
  red "  ❌ 以下目录被跟踪了：$BIGBAD"
  FAIL=1
else
  green "  ✅ 大目录均已排除"
fi

if [ "$FAIL" = "1" ]; then
  echo
  red "安全检查未通过，已中止推送。请先处理上面标 ❌ 的问题。"
  exit 1
fi

# ── 提交 ────────────────────────────────────────────────────────
echo
bold "════════ 提交 ════════"
if [ -z "$(git status --porcelain)" ]; then
  yellow "  工作区干净，没有需要提交的改动"
else
  git add -A
  echo "  变更文件："
  git diff --cached --name-status | head -20 | sed 's/^/    /'
  n=$(git diff --cached --name-only | wc -l | tr -d ' ')
  [ "$n" -gt 20 ] && echo "    ...（共 $n 个文件）"
  echo
  git -c core.quotepath=false commit -q -m "$MSG" || { red "  提交失败"; exit 1; }
  green "  ✅ 已提交：$(git log --oneline -1)"
fi

# ── 推送（仅在带 --push 时执行）─────────────────────────────────
if [ "$PUSH" != "1" ]; then
  echo
  bold "════════ 完成（未推送）════════"
  LOCAL_HEAD=$(git rev-parse HEAD)
  REMOTE_HEAD=$(git ls-remote origin "refs/heads/$BRANCH" 2>/dev/null | cut -f1 || true)
  if [ -n "$REMOTE_HEAD" ] && [ "$LOCAL_HEAD" != "$REMOTE_HEAD" ]; then
    yellow "  本地领先远程若干提交（按当前策略不自动推送）"
    echo "  本地: $(git rev-parse --short HEAD)"
    echo "  远程: $(echo "$REMOTE_HEAD" | cut -c1-7)"
    echo "  如需推送：./scripts/save.sh \"\" --push"
  else
    green "  已提交到本地仓库"
  fi
  exit 0
fi

echo
bold "════════ 推送 ════════"
LOCAL_HEAD=$(git rev-parse HEAD)
REMOTE_HEAD=$(git ls-remote origin "refs/heads/$BRANCH" 2>/dev/null | cut -f1)

if [ "$LOCAL_HEAD" = "$REMOTE_HEAD" ]; then
  yellow "  本地与远程已一致，无需推送"
else
  if ! git push origin "$BRANCH" 2>&1 | tail -5 | sed 's/^/  /'; then
    red "  推送失败，请检查网络或 SSH 配置"
    exit 1
  fi
  green "  ✅ 已推送到 https://github.com/$REPO"
fi

# ── 推送后验证密钥未泄露 ────────────────────────────────────────
echo
bold "════════ 推送后安全验证 ════════"
SCODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 \
  "https://raw.githubusercontent.com/$REPO/$BRANCH/secrets/local.env" 2>/dev/null || echo "000")
if [ "$SCODE" = "404" ]; then
  green "  ✅ secrets/local.env 在远程返回 404（未泄露）"
elif [ "$SCODE" = "000" ]; then
  yellow "  ⚠️ 无法访问 GitHub（网络问题），请手动验证："
  yellow "     https://github.com/$REPO/blob/$BRANCH/secrets/local.env"
else
  red "  🚨 严重：secrets/local.env 在远程可访问（http=${SCODE}）！"
  red "     立即到 DeepSeek 控制台作废该 Key 并重新生成"
  exit 2
fi

echo
green "完成。仓库地址：https://github.com/$REPO"
