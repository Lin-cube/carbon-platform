# 部署指南 · 雨云（Rainyun）香港服务器

> 面向本项目的完整部署流程。按顺序做，大约 40~60 分钟（含数据传输）。
>
> **架构**：Nginx（80/443）→ 前端静态文件 + `/api` 反代 → Spring Boot（127.0.0.1:8080）→ MySQL（127.0.0.1:3306）+ RAG（127.0.0.1:8000）

---

## 一、买之前先确认这几件事

### 1.1 选哪个区域：香港

雨云[官方选购指南](https://forum.rainyun.com/t/topic/3290/1)写明：

> 中国香港：**免备案**，采用 BGP 网络，三网直连……选购指引：无域名备案、小型网站应用建议选择本区域。

**结论：选香港是对的。** 香港机房不需要 ICP 备案，域名买完解析就能用。
（如果选国内宿迁，域名必须先备案，周期 2~4 周，答辩前来不及。）

### 1.2 配置：2 核 4G（不建议 2 核 2G）

| 配置 | 内存占用估算 | 结论 |
|---|---|---|
| 2 核 2G | 系统 200 + MySQL 400 + JDK 500 + RAG 120 + Nginx 30 ≈ **1.25G** | ⚠️ 能跑但没余量，构建/备份时会卡 |
| **2 核 4G** | 同上 ≈ 1.25G，剩余 2.7G | ✅ **推荐**，香港 2核4G 约 43 元/月 |

本项目实测内存（在本机 16G 环境测的，可作参考）：

| 组件 | 实测 RSS |
|---|---|
| 后端（`-Xmx384m -XX:+UseSerialGC`） | 251~274 MB |
| MySQL 8.4 | 169~210 MB |
| RAG（uvicorn 单 worker） | 65 MB |
| **合计（不含系统）** | **约 500~550 MB** |

所以 **2G 理论上够**，但服务器还要跑 SSH、系统更新、certbot，留点余量更稳。

### 1.3 磁盘：默认 30GB 够用

| 内容 | 大小 |
|---|---|
| 数据库（数据 + 索引） | 2.8 GB |
| MySQL 程序 + binlog | 约 1.5 GB |
| JDK 21 | 约 300 MB |
| Python venv | 约 500 MB |
| 前端 dist + 后端 jar | 65 MB |
| RAG 索引 | 29 MB |
| 系统 + 日志 | 约 3 GB |
| **合计** | **约 8.5 GB** |

30GB 系统盘有充足余量。雨云支持随时扩容（10G 约 3 元/月）。

### 1.4 系统镜像：选 **Ubuntu 22.04 或 24.04 LTS**

雨云官方建议"想装宝塔选 CentOS"，但**本项目推荐 Ubuntu**，原因：

| 考虑 | 说明 |
|---|---|
| CentOS 7 已 EOL | 2024 年 6 月停止维护，不再有安全更新 |
| 本项目脚本 | 全部基于 `apt`（Ubuntu/Debian），`server-setup.sh` 直接可用 |
| 宝塔 | Ubuntu 也能装，但**本方案不需要宝塔**（见下） |

### 1.5 要不要装宝塔？

**本方案不需要宝塔。** 原因：

| 宝塔 | 本方案（纯命令行） |
|---|---|
| 多占约 150MB 内存 | 无额外开销 |
| 面板端口要对外开放（安全风险） | 只开 22/80/443 |
| MySQL 版本要手选（选错 5.7 会崩） | `apt install mysql-server` 直接给 8.0 |
| SSL 一键申请 | `certbot --nginx` 一条命令，自动续期 |

`server-setup.sh` 已经把宝塔能做的事都脚本化了。**如果你想用宝塔**，跳过脚本第 2、7 步，在面板里装 Nginx + MySQL 8.0，其余照做。

### 1.6 安全组 / 防火墙：只开 4 个端口

| 端口 | 用途 | 必须 |
|---|---|---|
| 22 | SSH | ✅ |
| 80 | HTTP（证书验证也要用） | ✅ |
| 443 | HTTPS | ✅ |
| 8080 / 8000 / 3306 | **绝对不要对外开放** | ❌ |

后端与 RAG 都绑定 `127.0.0.1`，MySQL 只监听本地，外部无法直连。

---

## 二、部署流程（6 步）

### 第 1 步：买服务器和域名

**服务器**：雨云 → 云服务器 → 香港 → 2核4G → Ubuntu 22.04 → 30GB。
记下 **公网 IP** 和 **root 密码**。

**域名**：随便哪家买（阿里云/腾讯云/Namesilo 都行），买完做一条 DNS 记录：

| 类型 | 主机记录 | 记录值 | TTL |
|---|---|---|---|
| A | `@`（或 `www`） | 你的服务器公网 IP | 600 |

验证生效（本机执行，可能等 1~10 分钟）：

```bash
dig +short 你的域名.com
# 应输出你的服务器 IP
```

### 第 2 步：本地打包

在你自己的电脑上（**不是在服务器上**）：

```bash
cd <项目根目录>
./deploy/build-package.sh
```

产出 `deploy/out/carbon-platform-deploy-<日期>.tar.gz`（约 320MB）。

**为什么在本地构建**：服务器可能是 2核2G，`mvn package` 和 `vite build` 会吃掉 1.5GB+ 内存，容易 OOM 甚至把 MySQL 挤挂。本地有缓存，1~2 分钟搞定。

### 第 3 步：上传

```bash
# 用 root 上传（首次部署还没有 carbon 用户）
scp deploy/out/carbon-platform-deploy-*.tar.gz root@你的服务器IP:/root/
```

320MB，视上行带宽 2~10 分钟。

> **传得慢？** 可以先在本地把包压得更小，或分两步传数据库（先传其余 60MB 把服务跑起来，再单独传 db）。
> 也可以先 `scp` 到本地能访问的中转（如对象存储），服务器上 `wget`。

### 第 4 步：服务器上执行初始化

```bash
ssh root@你的服务器IP

# 解压
mkdir -p /opt/carbon-platform
tar -xzf /root/carbon-platform-deploy-*.tar.gz -C /opt/carbon-platform

# 执行初始化（装 JDK/Python/MySQL/Nginx、导数据、配 systemd）
bash /opt/carbon-platform/server-setup.sh
```

脚本会依次做 7 件事，每步都有输出：

```
0/7 检查系统      → 打印系统版本、内存、磁盘
1/7 创建用户目录  → carbon 用户 + /opt/carbon-platform
2/7 装软件        → JDK 21 (Adoptium) / Python / Nginx / MySQL 8.0
3/7 配置数据库    → 建库建用户（随机密码）、导入 2.8GB 数据
4/7 Python 环境   → venv + pip 装依赖（清华镜像）
5/7 接入密钥      → 写入数据库连接、生成随机 JWT 密钥
6/7 启动服务      → systemd enable + start
7/7 配置 Nginx    → 站点配置 + reload
```

**导入数据库这一步最慢**，约 3~10 分钟（取决于磁盘性能）。看到 `fact_energy_hourly = 5,991,840 行` 就成功了。

### 第 5 步：换域名 + 开 HTTPS

```bash
# 换域名
sed -i 's/YOUR_DOMAIN.COM/你的域名.com/g' /etc/nginx/sites-available/carbon-platform.conf
nginx -t && systemctl reload nginx

# 申请免费证书（自动改 nginx 配置 + 配置自动续期）
apt install -y certbot python3-certbot-nginx
certbot --nginx -d 你的域名.com
```

certbot 会问是否把 HTTP 重定向到 HTTPS，**选重定向（Redirect）**。

完成后访问 `https://你的域名.com` 应该能打开登录页。

### 第 6 步：验证

```bash
# 后端健康
curl -s http://127.0.0.1:8080/api/system/health

# 数据行数
mysql -uroot -N -e "SELECT COUNT(*) FROM carbon_energy.fact_energy_hourly;"
# 应为 5991840

# RAG 服务
curl -s http://127.0.0.1:8000/health

# 服务状态
systemctl status carbon-backend carbon-rag --no-pager --lines=0

# 外网访问（在你自己电脑上）
curl -I https://你的域名.com
```

浏览器打开 → 用 `admin / admin123` 登录 → 能看到看板就成功了。

---

## 三、部署后必做

### 3.1 改演示账号密码

```sql
-- 服务器上执行
mysql -uroot carbon_energy
```

```sql
-- 密码是 BCrypt 哈希，不能直接写明文。
-- 最简单的方式：在页面「系统管理」里改（如果实现了），
-- 或者用后端提供的接口重置。
-- 临时方案：先生成一个已知密码的 BCrypt 值
```

> **注意**：本项目当前**没有做用户管理界面**，账号在数据库 `sys_user` 表里。
> 最简单的做法是先在本地生成 BCrypt 哈希再 update。如果你需要，我可以加一个改密码接口。

### 3.2 确认大模型 Key 已生效

```bash
grep CARBON_LLM_KEY /opt/carbon-platform/secrets/local.env.systemd
# 如果显示「在此填入」，需要编辑该文件填入真实 Key，然后：
systemctl restart carbon-backend carbon-rag
```

### 3.3 配置日志轮转（防止日志撑爆磁盘）

```bash
cat > /etc/logrotate.d/carbon-platform <<'EOF'
/opt/carbon-platform/logs/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
EOF
```

### 3.4 数据库自动备份

```bash
mkdir -p /opt/backup
cat > /etc/cron.daily/carbon-backup <<'EOF'
#!/bin/bash
DATE=$(date +%Y%m%d)
mysqldump -uroot --single-transaction --quick carbon_energy \
  | gzip -1 > /opt/backup/carbon_energy_$DATE.sql.gz
# 只保留最近 7 天
find /opt/backup -name "carbon_energy_*.sql.gz" -mtime +7 -delete
EOF
chmod +x /etc/cron.daily/carbon-backup
```

每天凌晨自动备份，约 250MB/份，保留 7 天 = 1.7GB。

### 3.5 开启 swap（2G 机器必做）

```bash
# 2G 内存强烈建议开 2G swap，防止构建或备份时 OOM
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

---

## 四、日常运维

### 重启 / 停止 / 查看状态

```bash
systemctl restart carbon-backend carbon-rag
systemctl stop carbon-backend carbon-rag
systemctl status carbon-backend carbon-rag --no-pager --lines=0
```

### 看日志

```bash
tail -f /opt/carbon-platform/logs/backend.log
tail -f /opt/carbon-platform/logs/rag.log
journalctl -u carbon-backend -f          # systemd 层日志（启动失败时看这个）
```

### 更新代码

```bash
# 本地
cd <项目根目录>
./deploy/build-package.sh
scp deploy/out/carbon-platform-deploy-*.tar.gz root@服务器IP:/root/

# 服务器
cd /root && tar -xzf carbon-platform-deploy-*.tar.gz -C /opt/carbon-platform
cp /opt/carbon-platform/systemd/*.service /etc/systemd/system/
systemctl daemon-reload
systemctl restart carbon-backend carbon-rag
```

> **数据库不用重导**（脚本会检测到表已存在而跳过）。如果这次改了表结构，需要手动执行 ALTER。

---

## 五、常见问题

### 502 Bad Gateway

后端没起来。看日志：

```bash
journalctl -u carbon-backend -n 50 --no-pager
tail -50 /opt/carbon-platform/logs/backend.err.log
```

最常见原因：
- **MySQL 没起来** → `systemctl status mysql`
- **数据库密码不对** → 检查 `/opt/carbon-platform/secrets/local.env.systemd` 里的 `CARBON_DB_PASSWORD`
- **内存不够 OOM** → `dmesg | grep -i "killed process"`

### 前端白屏 / 刷新 404

nginx 的 `try_files` 没生效。检查配置里是否有：

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

### 上传文件报 413

nginx 的 `client_max_body_size` 太小。本方案已设 100m，若改过后端限制记得同步改 nginx。

### AI 问答很慢或超时

- 检查 nginx `proxy_read_timeout`（本方案 120s）
- 检查服务器能否访问 `api.deepseek.com`：`curl -I https://api.deepseek.com`
- 香港机房访问境外 API 通常没问题，但如果超时可以考虑换 `CARBON_LLM_BASE`

### 数据库导入中断

```bash
# 看导入是否完整
mysql -uroot -N -e "SELECT COUNT(*) FROM carbon_energy.fact_energy_hourly;"
# 不等于 5991840 就重新导入：
mysql -uroot -e "DROP DATABASE carbon_energy;"
# 然后重新执行 server-setup.sh（会重跑第 3 步）
```

---

## 六、成本估算

| 项 | 费用 |
|---|---|
| 雨云香港 2核4G | 约 43 元/月 |
| 域名 `.com` | 约 60~80 元/年 |
| SSL 证书 | 免费（Let's Encrypt） |
| **首年合计** | **约 580 元** |

流量不限，答辩演示完全不担心超额。

---

## 七、这个方案没做什么（如实说明）

| 项 | 说明 |
|---|---|
| 没有 Docker | 单机部署，systemd 足够；容器化对 2核4G 是负担 |
| 没有 Redis | 单实例 + 数据仅导入时变化，进程内缓存够用 |
| 没有主从复制 | 单库够用；备份靠 cron + mysqldump |
| 没有 CI/CD | 手动 `build-package.sh` + `scp`，这个规模不值得搭流水线 |
| 没有监控告警 | 可以用雨云自带的监控，或后续加 Prometheus |
| 后端单实例 | 重启会中断几秒，答辩场景可接受 |
