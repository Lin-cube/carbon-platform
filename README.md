# 碳能智控运维平台

> Carbon Energy Intelligent Control & O&M Platform
>
> 面向建筑能源管理场景的一体化 Web 平台：**能耗数据治理 → 多维查询统计 → 3D 园区监控 → AI 智慧问答与诊断**。

<p>
  <img alt="Java" src="https://img.shields.io/badge/JDK-21-orange">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3.5-42b883">
  <img alt="TypeScript" src="https://img.shields.io/badge/TypeScript-5.7-3178c6">
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.4-4479A1">
  <img alt="Python" src="https://img.shields.io/badge/Python-3.14-3776AB">
  <img alt="License" src="https://img.shields.io/badge/Data%20License-CC--BY%204.0-lightgrey">
</p>

---

## 一、这是什么

一套可运行的建筑能源管理系统，基于开源数据集 **BDG2（Building Data Genome Project 2）** 构建，包含三项核心能力：

| # | 能力 | 说明 |
|---|---|---|
| ① | **能耗数据导入与治理** | 五步向导（上传 → 字段映射 → 清洗 → 预览 → 审计），字段映射由大模型语义识别，失败自动降级为规则引擎 |
| ② | **实时监控与可视化看板** | Three.js 3D 园区（按能耗评级/强度/异常率着色）、6 类分析图表、园区→建筑→表计三级状态监控、异常告警 |
| ③ | **AI 智慧问答与诊断** | 「小E」助手基于自建 RAG 知识库回答问题并标注出处；7 类 MCP 数据工具；异常归因诊断与运维优化建议 |

### 核心数据

| 项 | 值 |
|---|---|
| 数据规模 | **5,991,840** 条逐小时记录（2016-01-01 ~ 2017-07-01，13,140 小时） |
| 覆盖范围 | **456** 栋建筑 / **18** 个园区 / 15 类建筑类型 |
| 数据集字段 | **45** 个（含数据来源三态标注：实测 / 推算 / 仿真） |
| 数据库 | 18 张表（含 5 张预聚合表） |
| 后端接口 | **30** 个 |
| 前端页面 | **8** 个 |
| RAG 知识库 | **8,921** 条语料（自建运维知识 47 条 + 设计规范 8,805 条 + 项目文档 69 条） |
| 代码规模 | Java 67 文件 / 7,029 行，Vue+TS 19 文件 / 4,594 行，Python 9 文件 / 2,618 行 |

---

## 二、技术栈

### 前端

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5 | 组合式 API，8 个页面 |
| TypeScript | 5.7 | 类型安全（`vue-tsc` 构建时校验） |
| Vite | 6.0 | 开发服务器 + 生产构建（three/echarts/element 分包） |
| Vue Router | 4.5 | 路由与登录守卫 |
| Pinia | 2.3 | 状态管理 |
| Element Plus | 2.9 | UI 组件库 |
| ECharts | 5.6 | 6 类图表（按需引入） |
| Three.js | 0.172 | 3D 园区模型 |
| axios | 1.7 | HTTP 客户端（统一拦截器） |

### 后端

| 技术 | 版本 | 用途 |
|---|---|---|
| JDK | 21 (Temurin) | LTS |
| Spring Boot | 3.5.6 | Web / DI / 自动配置 |
| Spring Security | 3.5.6 | JWT 无状态认证 + RBAC |
| jjwt | 0.12.6 | Token 签发校验 |
| MyBatis-Plus | 3.5.9 | ORM（复杂聚合手写 SQL） |
| MySQL | 8.4 | 主库（项目内独立实例，端口 3307） |
| EasyExcel | 4.0.3 | 5 Sheet 报表流式导出 |
| springdoc-openapi | 2.9.0 | OpenAPI 3 + Swagger UI |
| Lombok | 1.18.46 | 样板代码消除（版本已上调以兼容新版 IDE 语言服务器） |

### AI / RAG

| 技术 | 用途 |
|---|---|
| Python 3.14 + FastAPI | RAG 检索服务（端口 8000） |
| jieba + rank_bm25 | 中文分词 + BM25 关键词检索 |
| scikit-learn（字符 n-gram TF-IDF） | 语义近似向量，零模型下载 |
| DeepSeek API（`deepseek-chat`） | 答案生成（OpenAI 兼容接口，可替换为魔搭/DashScope） |

### 数据管道

pandas · numpy · pyarrow · SQLAlchemy · PyMySQL（7 个脚本，独立于运行时）

### 关键选型说明

| 决策 | 理由 |
|---|---|
| 不用 RuoYi 脚手架 | 官方版基于 Spring Boot 2.x / JDK 8，与本项目 JDK 21 冲突；其代码生成、定时任务等模块用不到 |
| MyBatis-Plus 而非 JPA | 报表场景大量复杂聚合查询，手写 SQL 更可控 |
| springdoc 而非 Knife4j | Knife4j 最新版捆绑 springdoc 2.3.0，在 Spring Boot 3.5 上报 `NoSuchMethodError`，接口文档不可用（已实测踩坑） |
| 自建 RAG 而非 RAGFlow | 语料已是结构化问答对，RAGFlow 的 PDF 解析强项用不上；且需 Docker + ≥4GB 内存 |
| 不用 Redis | 单实例部署 + 数据仅在导入时变化，进程内缓存已足够（详见 `docs/deliverables/09-服务器配置评估.md`） |

---

## 三、快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | `java -version` 确认 |
| Maven | 3.9+ | |
| Node.js | 20+ | 含 pnpm |
| Python | 3.12+ | 用于数据管道与 RAG 服务 |
| MySQL | 8.0+ | **必须 8.0**，项目用了窗口函数与 CTE |

### 一键启动

```bash
# 1. 克隆
git clone <your-repo-url> carbon-platform
cd carbon-platform

# 2. 配置密钥（模板见 secrets/local.env.example）
mkdir -p secrets
cp secrets/local.env.example secrets/local.env
# 编辑 secrets/local.env，填入你的大模型 API Key

# 3. 构建 Python 虚拟环境
python3 -m venv .venv
.venv/bin/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple \
  pandas numpy pyarrow sqlalchemy pymysql jieba rank_bm25 scikit-learn scipy \
  "fastapi" "uvicorn[standard]"

# 4. 初始化数据库（详见 docs/环境搭建说明.md）
./scripts/mysql.sh init          # 建库 + 建表
.venv/bin/python data-pipeline/scripts/07_load_to_mysql.py   # 导入 599 万行

# 5. 构建 RAG 索引（可选，缺索引时知识问答不可用）
.venv/bin/python rag-service/src/build_index.py

# 6. 启动
./scripts/start-all.sh daemon
```

打开 **http://127.0.0.1:5173**，用 `admin / admin123` 登录。

### 两种启动模式

| 命令 | 用途 |
|---|---|
| `./scripts/start-all.sh daemon` | 完整启动 4 个服务，`launchctl` 托管，关终端不停止 |
| `./scripts/start-all.sh dev` | 只启动 MySQL / 前端 / RAG，**8080 留给 IDE** 调试后端 |
| `./scripts/start-all.sh status` | 查看状态 |
| `./scripts/start-all.sh stop` | 停止全部并释放端口 |

### 服务与端口

| 服务 | 端口 | 地址 |
|---|---|---|
| MySQL | 3307 | 项目内独立实例（不动系统 3306） |
| 后端 | 8080 | 接口文档 http://127.0.0.1:8080/swagger-ui/index.html |
| 前端 | 5173 | **系统界面 http://127.0.0.1:5173** |
| RAG | 8000 | http://127.0.0.1:8000/health |

### 演示账号

| 账号 | 密码 | 权限 |
|---|---|---|
| `admin` | `admin123` | 管理员（全部） |
| `operator` | `operator123` | 运维人员 |
| `viewer` | `viewer123` | 只读 |

---

## 四、系统架构

```
┌──────────────────────────────────────────────────────────┐
│  浏览器                                                    │
│  Vue 3.5 + TS + Vite 6 + Element Plus                     │
│  ECharts 5.6（图表） + Three.js 0.172（3D 园区）            │
└────────────────────────┬─────────────────────────────────┘
                         │ HTTP / JWT
┌────────────────────────▼─────────────────────────────────┐
│  Spring Boot 3.5 + JDK 21（:8080，30 个接口）              │
│  Security + MyBatis-Plus + EasyExcel + springdoc          │
└──────────┬──────────────────────────────┬────────────────┘
           │ JDBC                         │ HTTP
┌──────────▼──────────┐      ┌────────────▼─────────────────┐
│  MySQL 8.4（:3307） │      │  Python FastAPI（:8000）      │
│  18 表 / 599 万行    │      │  RAG 检索 + DeepSeek 生成     │
│  含 5 张预聚合表     │      │  BM25 + 字符 n-gram 向量      │
└─────────────────────┘      └──────────────────────────────┘

离线数据管道：Python + pandas + numpy + pyarrow（7 个脚本）
```

### AI 问答的三级路由

系统的问答不是"全丢给大模型"，而是按问题类型分流：

```
用户提问
   ├─ 数据类问题  ──→ 本地统计引擎 ──→ 查 MySQL ──→ 真实数值（大模型不参与）
   │   "Eagle 园区 2016 年用电量多少"
   │
   ├─ 知识类问题  ──→ RAG 检索 ──→ 知识库片段 ──→ DeepSeek 组织成答案
   │   "离心机喘振怎么处理"                        （带出处引用）
   │
   └─ 知识库未覆盖 ──→ 相关度 < 阈值 ──→ 直接拒答
       "怎么做红烧肉"                      （不调用大模型，不返回无关引用）
```

**为什么这样设计**：两个引擎能力边界不同。数据类问题需要查库，RAG 没有数据库权限；
知识类问题需要专业知识，本地统计答不了。只配一种模式会导致另一类问题失准。
**安全边界写在代码里**（相关度阈值 2.5），不依赖大模型自觉，换任何模型行为一致。

### 性能设计：5 张预聚合表

跨百万行的聚合查询无法靠索引解决，必须预聚合：

| 表 | 粒度 | 行数 | 服务的查询 |
|---|---|---|---|
| `agg_building_daily` | 建筑 × 日 | 249,888 | 日趋势、看板 KPI、类型/园区分解 |
| `agg_building_monthly` | 建筑 × 月 | 8,664 | 月度趋势 |
| `agg_anomaly_daily` | 建筑 × 日 | 249,888 | 异常分析 |
| `agg_time_hourly` | 日期 × 小时 | 13,140 | 峰谷定位、分时电价（无过滤） |
| `agg_site_hourly` | 园区 × 日 × 小时 | 236,520 | 同上（带园区过滤） |

实测：最慢接口从 **4.6s 降到约 300ms**。

---

## 五、功能清单

### 8 个前端页面

| 页面 | 路径 | 内容 |
|---|---|---|
| 登录 | `/login` | 账号密码 + 演示账号一键填充 |
| 监控看板 | `/dashboard` | 6 张 KPI 卡 + 趋势图 + 结构图 + 评级分布 |
| 3D 园区 | `/campus3d` | 楼栋按评级/强度/异常率着色，高度映射面积或电量，点击看详情 |
| 能耗查询 | `/energy` | 多维筛选 + 小时/日/月粒度 + 异常标记 |
| 统计分析 | `/analytics` | 时段汇总 / COP 计算 / 异常分析 三个 Tab |
| 异常诊断 | `/anomaly` | 事件列表 + 归因证据链 + 优化建议 |
| 小E 助手 | `/assistant` | 对话式问答，显示引用、工具链、置信度、耗时 |
| 数据导入 | `/import` | 五步向导 |

### 数据导入后的完整闭环

导入完成后，数据会同步到所有相关页面（这是本项目特意补齐的一环）：

| 位置 | 是否可见 |
|---|---|
| 建筑列表 / 查询下拉框 | ✅ 自动登记新建筑 |
| 园区列表（3D 页面） | ✅ 自动登记新园区 |
| 3D 园区楼栋 | ✅ 含类型、面积、能耗评级 |
| 能耗查询（小时/日/月） | ✅ |
| 监控看板（KPI/趋势/分解/评级分布） | ✅ 自动刷新预聚合表 |
| 统计分析 | ✅ |
| **异常诊断** | ❌ 设计如此（新数据无 52 周历史基线，无法判定异常） |

**写入语义：按「建筑 + 时刻」覆盖**，重复导入同一时间段不会产生重复行。
导入时自动回填建筑类型、建筑面积，并按同类型分位数重算能耗评级 A–E
（数据覆盖不足 30 天不评级，避免年化 EUI 失真）。

---

## 六、数据说明

### 数据来源

本项目使用开源学术数据集 **BDG2（Building Data Genome Project 2）**，采用 **CC-BY 4.0** 许可：

```
Miller, C., Kathirgamanathan, A., Picchetti, B. et al.
The Building Data Genome Project 2, energy meter data from the
ASHRAE Great Energy Predictor III competition.
Sci Data 7, 368 (2020). https://doi.org/10.1038/s41597-020-00712-x
```

数据集本身已脱敏（建筑名称为化名），不含真实地址与企业内部信息。

### 字段来源三态标注

数据字典与接口返回都会标注每个字段的来源，使用时必须区分：

| 来源 | 字段 | 说明 |
|---|---|---|
| **实测** | 电力、空调、水耗、室外温度、建筑面积、建筑类型 | 来自原始表计或气象站 |
| **推算** | 湿度（露点经 Magnus 公式）、碳排放、空调电耗 | 由实测数据经确定性公式得到 |
| **仿真** | 供回水温度、COP、人员密度 | BDG2 无相应实测，按物理模型生成，**不得表述为实测** |
| **标注** | 异常标志、设备状态、严重度、数据质量标志 | 算法判定结果 |

### 已知数据限制（如实披露）

| 字段 | 缺失率 | 原因 |
|---|---|---|
| `hvac_kwh` | 43.19% | 取决于园区是否有可用的冷冻水/蒸汽/热水表计 |
| `water_m3` | 80.27% | BDG2 水表覆盖本身较少 |
| `cop` / `chw_supply_temp_c` | 95.41% | 18 个园区中仅 5 个存在计量口径可用的冷冻水表计 |
| `electricity_kwh` | 3.98% | 部分建筑无电表 |

**数据分级**：A 级 431 栋（有独立电表，参与异常评估）；B 级 25 栋（电耗按面积分摊估算，`anomaly_flag = -1` 不参与评估）。

---

## 七、验收指标与实测结果

| 指标 | 目标 | 实测 | 状态 |
|---|---|---|---|
| 核心页面响应时间（P95） | ≤ 2 秒 | 核心接口 **30/30 通过**，最慢约 300ms | ✅ |
| 异常识别召回率 | ≥ 90% | **98.68%** | ✅ |
| 异常识别误报率 | ≤ 10% | **3.29%** | ✅ |
| AI 问答可用准确率 | ≥ 85% | 见下方口径说明 | ✅/⚠️ |

### 关于 AI 问答准确率的诚实说明

这个数字必须分组看，否则会误导：

| 测试集 | 题量 | 措辞特点 | TOP1 | 可信度 |
|---|---|---|---|---|
| A 撰写测试集 | 24 | 与语料用词一致 | 100% | ❌ 同一人写语料和题目，不可引用 |
| B 留出集（已调优） | 36 | 口语化，曾用于补同义词 | 97% | ⚠️ 偏乐观 |
| **C 留出集（真留出）** | **37** | **全新口语措辞，写完即测** | **57%（TOP3 78%）** | ✅ **真实泛化能力** |

**结论**：按术语提问接近 100%；用户口语提问时 TOP1 约 57%、TOP3 约 78%。
这是关键词检索方案的固有上限，要进一步突破需引入语义向量模型。

> AI 生成类接口因调用外部大模型，响应 2.0~3.2s，**单列统计，不计入「核心页面」指标**。
> 详见 `docs/deliverables/04-压测报告.md`。

---

## 八、目录结构

```
carbon-platform/
├── backend/                      Spring Boot 后端（30 个接口）
│   ├── src/main/java/com/carbon/platform/
│   │   ├── common/               通用（结果封装、异常、JWT、审计切面）
│   │   ├── system/               认证授权（RBAC）
│   │   ├── energy/               能耗查询 + 数据导入（五步向导）
│   │   ├── analytics/            三大统计分析 + 报表导出
│   │   └── ai/                   AI 客户端（本地统计 / RAG / 混合路由）+ LLM 客户端
│   └── src/main/resources/
│       ├── application.yml       配置（密钥全部走环境变量）
│       └── mapper/               手写 SQL（含区间聚合与预聚合表选择）
├── frontend/                     Vue 3 + TS 前端（8 个页面）
│   └── src/{api,components,views,router,stores,types,utils}
├── rag-service/                  RAG 知识库服务（FastAPI）
│   ├── corpus/                   自建运维语料（6 个主题文件，47 条）
│   ├── src/{build_index,retriever,app}.py
│   └── data/                     索引产物（不入库，可重建）
├── data-pipeline/                离线数据管道（7 个脚本）
│   ├── scripts/                  01_profile … 07_load_to_mysql
│   ├── src/                      配置与算法库
│   └── sql/01_schema.sql         18 张表建表脚本
├── data/samples/                 演示用 CSV（可直接试导入向导）
├── docs/                         文档（详见下方索引）
├── scripts/                      启停脚本、MySQL 管理、压测、评测
└── secrets/                      本地密钥（已 gitignore，模板见 local.env.example）
```

---

## 九、文档索引

| 文档 | 内容 |
|---|---|
| [`docs/deliverables/00-项目概要介绍.md`](docs/deliverables/00-项目概要介绍.md) | 项目背景、痛点、能力与成果 |
| [`01-需求分析文档.md`](docs/deliverables/01-需求分析文档.md) | 核心需求、用户场景、需求追溯矩阵 |
| [`02-技术方案文档.md`](docs/deliverables/02-技术方案文档.md) | 数据集设计、架构、算法、接口与数据库设计 |
| [`03-用户使用手册.md`](docs/deliverables/03-用户使用手册.md) | 安装部署、8 个页面操作、FAQ、术语表 |
| [`04-压测报告.md`](docs/deliverables/04-压测报告.md) | 32 个接口压测结果与优化记录 |
| [`06-技术栈审计报告.md`](docs/deliverables/06-技术栈审计报告.md) | 技术栈清单、主流度评估、冗余清理 |
| [`07-RAG知识库说明.md`](docs/deliverables/07-RAG知识库说明.md) | RAG 架构、检索算法、三组评测口径 |
| [`11-导入数据全链路说明.md`](docs/deliverables/11-导入数据全链路说明.md) | 导入后数据流向与各页面可见性 |
| [`12-DeepSeek与知识库的分工.md`](docs/deliverables/12-DeepSeek与知识库的分工.md) | 大模型与知识库各负责什么、拒答机制 |
| [`09-服务器配置评估.md`](docs/deliverables/09-服务器配置评估.md) | 实测资源占用与选型建议 |
| [`10-香港服务器宝塔部署方案.md`](docs/deliverables/10-香港服务器宝塔部署方案.md) | 生产部署完整步骤 |
| [`14-启动速查.md`](docs/deliverables/14-启动速查.md) | 每次启动/停止/排错速查 |
| [`13-VSCode爆红排查.md`](docs/deliverables/13-VSCode爆红排查.md) | IDE 报错排查（Lombok/Maven 导入） |
| [`docs/data_dictionary.md`](docs/data_dictionary.md) | 45 个字段的完整数据字典 |
| [`docs/接口契约与分工.md`](docs/接口契约与分工.md) | 前后端与 AI 服务的接口契约 |
| [`docs/环境搭建说明.md`](docs/环境搭建说明.md) | 环境搭建与踩坑记录 |
| [`docs/eval/`](docs/eval/) | 异常识别评测、字段映射评测、AI 问答评测 |

---

## 十、开发与测试

```bash
# 后端编译
cd backend && mvn -s ../scripts/maven-settings.xml clean compile

# 前端构建（含类型检查）
cd frontend && pnpm run build

# 接口压测（核心接口 P95 ≤ 2s）
.venv/bin/python scripts/load_test.py --rounds 5

# AI 问答评测（24 题标准测试集）
.venv/bin/python scripts/build_qa_testset.py

# 字段映射评测（规则 vs LLM）
.venv/bin/python scripts/eval_field_mapping.py

# 异常检测评测（召回率 / 误报率）
.venv/bin/python data-pipeline/scripts/04_eval_anomaly.py
```

> **注意**：改后端代码前先停服务再编译，否则会出现 `Unresolved compilation problem`
> （旧进程占用 target/classes 导致类文件与新源码不一致）。正确顺序：
> `stop → mvn clean compile → 启动`。

---

## 十一、安全与隐私

| 项 | 处理方式 |
|---|---|
| 大模型 API Key | 仅存于 `secrets/local.env`（已 gitignore，权限 600），代码与配置中只读环境变量 |
| JWT 密钥 | 从 `CARBON_JWT_SECRET` 读取，默认值仅用于开发 |
| 数据库账号 | 从 `CARBON_DB_USER` / `CARBON_DB_PASSWORD` 读取 |
| 数据集 | BDG2 为公开脱敏数据集，无真实企业信息 |
| 演示账号 | `admin/admin123` 等仅为演示用，生产部署须修改 |

**克隆后请务必**：复制 `secrets/local.env.example` 为 `secrets/local.env` 并填入自己的密钥，
**不要提交 `secrets/` 目录**。

---

## 十二、已知限制

| # | 限制 | 说明 |
|---|---|---|
| 1 | 供回水温度、COP、人员密度为**仿真值** | BDG2 无相应实测，不可用于评价真实机组性能 |
| 2 | `cop` 缺失率 95.41% | 仅 5 个园区有可用冷冻水表计 |
| 3 | 新导入数据不参与异常检测 | 需 52 周历史基线才能判定，属设计选择 |
| 4 | 自建运维语料仅 47 条 | 覆盖面有限，口语提问 TOP1 准确率约 57% |
| 5 | 异常评测为注入式仿真 | 与真实用能异常形态存在差异 |
| 6 | AI 回答质量未做自动判分 | 目前只验证「检索是否命中」，生成质量需人工抽检 |

---

## 十三、许可

- **代码**：本项目代码可自由参考学习。
- **数据**：来自 BDG2，采用 [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/)，使用时须署名（见 §六 引用格式）。
- **知识库语料**：`rag-service/corpus/` 下为本项目自建内容（基于物理原理与行业通用实践整理），
  涉及国家标准但未获取原文的部分已明确标注「需核实」，不构成标准条文引用。

---

<p align="center">
  <sub>建筑能源管理 · 数据治理 · RAG 问答 · 3D 可视化</sub>
</p>
