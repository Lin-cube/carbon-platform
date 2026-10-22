# 开发规范（供 AI 助手与协作者参考）

## 每次改动后的固定动作

> ✅ **当前策略（用户 2026-10-22 明确要求）：改完就提交，并推送到 GitHub。**
> 不需要每次询问，直接执行。

**改完代码后的固定动作：**

```bash
./scripts/save.sh "改了什么" --push
```

它的行为是：安全检查（5 项）→ `git add -A` → `git commit` → `git push` → 验证密钥未泄露。

> 历史沿革：早期自动推送 → 2026-10 中改为只提交不推送 → 2026-10-22 恢复推送。
> 若用户以后又要求暂停推送，把 `--push` 去掉即可（`save.sh` 仍然只做安全检查 + commit）。

---

## 推送前的安全红线（脚本已自动执行）

| 检查项 | 要求 |
|---|---|
| `secrets/local.env` 是否被跟踪 | **必须为 0**，只允许 `secrets/local.env.example` |
| 代码/文档中的 `sk-` 密钥 | **必须为 0** |
| 代码/文档中的真实密码 | **必须为 0**（演示账号 `admin123` 除外） |
| 本机绝对路径 `/Users/<name>` | **必须为 0** |
| `.mysql/` `.venv/` `node_modules/` `target/` | **必须为 0** |

**推送后还要验证一次**（脚本会提示）：

```bash
curl -o /dev/null -w "%{http_code}\n" \
  https://raw.githubusercontent.com/Lin-cube/carbon-platform/main/secrets/local.env
# 必须返回 404
```

---

## 密钥管理约定

| 位置 | 内容 | 是否入库 |
|---|---|---|
| `secrets/local.env` | 真实密钥（DeepSeek Key、JWT Secret、DB 密码） | ❌ 已 gitignore |
| `secrets/local.env.example` | 配置模板，只有占位符 | ✅ 入库 |
| `application.yml` | 只写 `${ENV_VAR:默认值}` | ✅ 入库 |

**新增任何配置项**时的做法：
1. 在 `application.yml` 里用 `${ENV:默认值}` 形式；
2. 在 `secrets/local.env.example` 里加注释说明；
3. **绝不**把真实值写进 `application.yml` 或任何会被提交的文件。

---

## 代码约定

### 后端

| 项 | 约定 |
|---|---|
| Java 版本 | 21（`pom.xml` 的 `java.version`） |
| 改代码后编译 | `cd backend && mvn -s ../scripts/maven-settings.xml clean compile` |
| **编译前必须先停服务** | 否则旧 JVM 占用 `target/classes`，会报 `Unresolved compilation problem` |
| SQL 位置 | `backend/src/main/resources/mapper/*.xml`（手写） |
| 聚合查询 | 优先走预聚合表，见 `docs/deliverables/02-技术方案文档.md` |

### 前端

| 项 | 约定 |
|---|---|
| 构建必须含类型检查 | `pnpm run build`（不是 `build:only`） |
| 请求封装 | 一律用 `import { request } from '@/api/request'`（具名导出） |
| **不要用** `import request from '@/api/request'` | 那是裸 axios 实例，不解包信封，会导致 `res.xxx` 为 undefined |

### Python

| 项 | 约定 |
|---|---|
| 虚拟环境 | `.venv/bin/python`，不要用系统 Python |
| RAG 语料改动后 | 必须重建索引：`.venv/bin/python rag-service/src/build_index.py` 并重启 RAG 服务 |

---

## 文档约定

改功能时同步更新对应文档，避免代码与文档脱节：

| 改动 | 需更新 |
|---|---|
| 接口变化 | `docs/接口契约与分工.md`、`docs/data_dictionary.md` |
| 数据字段变化 | `docs/data_dictionary.md` |
| 性能/接口数量变化 | `README.md` 的指标表、`docs/deliverables/04-压测报告.md` |
| 部署相关 | `docs/deliverables/09-服务器配置评估.md`、`10-香港服务器宝塔部署方案.md` |
| 启动方式 | `docs/deliverables/14-启动速查.md` |
| RAG 语料/检索 | `docs/deliverables/07-RAG知识库说明.md` |

---

## 提交信息格式

```
<类型>(<范围>): <简短说明>

<正文：改了什么、为什么、影响>
<验证方式>
```

类型：`feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `chore`

示例：

```
fix(import): 补全数据质量诊断能力

- 单位不一致：增加列中位数预扫描，差 300 倍即告警
- 时间解析：先试格式再当时间戳，修复紧凑日期被解析成 2033 年的问题
- 跳过行补齐诊断说明

验证：11 行坏数据，修复前入库 7 行含错误单位，修复后入库 5 行且 6 条问题均给出依据
```

---

## 环境依赖（新机器上先确认）

```bash
java -version    # 21+
mvn -v           # 3.9+
node -v          # 20+
pnpm -v
python3 --version # 3.12+
mysql --version  # 8.0+
```

缺任何一个都会导致启动失败。具体安装步骤见 `docs/环境搭建说明.md`。

---

## 启动命令（记住这两个就够了）

```bash
./scripts/start-all.sh daemon    # 完整启动（用系统 / 演示）
./scripts/start-all.sh dev       # 只起依赖，8080 留给 IDE 调试后端
./scripts/start-all.sh status    # 看状态
./scripts/start-all.sh stop      # 停止
```

详细说明见 `docs/deliverables/14-启动速查.md`。
