-- ============================================================================
-- 碳能智控运维平台 —— MySQL 8 数据库结构
-- ============================================================================
-- 设计说明：
--   1. 统一能耗数据集（unified_energy_dataset）按【月分区】存储。
--      实测 600 万行级数据在 MySQL 中不分区会导致查询与导入缓慢；
--      按 RANGE 分区（年份*100+月份）可使"按时间范围查询"走分区裁剪，
--      直接服务于申报表"核心页面平均响应时间 ≤ 2 秒"的验收指标。
--   2. 建筑维表 / 站点维表独立存储，看板与 MCP 工具只需 JOIN 维表即可拿到元数据。
--   3. 异常记录单独建索引与视图，支撑"异常扫描"MCP 工具与告警看板。
--   4. 审计留痕表（sys_oper_log）满足申报表"审计留痕"要求。
--
-- 执行方式：
--   mysql -u root < data-pipeline/sql/01_schema.sql
-- ============================================================================

DROP DATABASE IF EXISTS carbon_energy;
CREATE DATABASE carbon_energy
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;
USE carbon_energy;

-- ---------------------------------------------------------------------------
-- 1. 站点维表（园区）
-- ---------------------------------------------------------------------------
CREATE TABLE dim_site (
    site_id         VARCHAR(32)     NOT NULL COMMENT '园区/站点编号',
    site_name_cn    VARCHAR(64)              COMMENT '园区中文名',
    building_count  INT DEFAULT 0            COMMENT '建筑数量',
    PRIMARY KEY (site_id)
) ENGINE=InnoDB COMMENT='园区维表';

-- ---------------------------------------------------------------------------
-- 2. 建筑维表
-- ---------------------------------------------------------------------------
CREATE TABLE dim_building (
    building_id         VARCHAR(96)  NOT NULL COMMENT '建筑编号（BDG2 building_id）',
    site_id             VARCHAR(32)  NOT NULL COMMENT '所属园区',
    building_type       VARCHAR(64)           COMMENT '建筑类型（英文）',
    building_type_cn    VARCHAR(32)           COMMENT '建筑类型（中文）',
    data_grade          CHAR(1)               COMMENT '数据分级 A=独立计量 B=站点分摊',
    meter_source        VARCHAR(32)           COMMENT '计量来源',
    area_m2             DECIMAL(12,2)         COMMENT '建筑面积(m2)',
    floors              DECIMAL(5,1)          COMMENT '楼层数',
    year_built          DECIMAL(6,1)          COMMENT '建成年份',
    occupants           DECIMAL(10,1)         COMMENT '设计人数',
    electricity_kwh_year DECIMAL(16,2)        COMMENT '年用电量(kWh)',
    eui_kwh_m2_year     DECIMAL(10,2)         COMMENT '年能耗强度(kWh/m2·年)',
    carbon_kg_year      DECIMAL(16,2)         COMMENT '年碳排放(kgCO2)',
    cop_mean            DECIMAL(6,3)          COMMENT '平均COP',
    energy_grade        VARCHAR(8)            COMMENT '能耗评级 A-E',
    anomaly_rate        DECIMAL(6,4)          COMMENT '异常率',
    quality_rate        DECIMAL(6,4)          COMMENT '数据质量缺陷率',
    PRIMARY KEY (building_id),
    KEY idx_site (site_id),
    KEY idx_type (building_type_cn),
    KEY idx_grade (energy_grade),
    KEY idx_eui (eui_kwh_m2_year)
) ENGINE=InnoDB COMMENT='建筑维表';

-- ---------------------------------------------------------------------------
-- 3. 统一能耗数据集（主表，按月分区）
-- ---------------------------------------------------------------------------
-- 【性能设计说明】验收指标要求"核心页面平均响应时间 ≤ 2 秒"。
-- 实测（600 万行，本机 MySQL 8.4）：
--     单建筑时段查询（走 idx_building_ts）      0.035s  ✅
--     异常记录查询（走 idx_anomaly）            0.069s  ✅
--     按建筑异常 TOP N（走 idx_anomaly）        0.502s  ✅
--     站点月度汇总                              0.617s  ✅
--     按建筑类型分组统计（跨大表 JOIN + 扫描）  3.458s  ❌ 超标
--
-- 结论：**跨大量行的聚合查询**无法靠索引解决，必须走预聚合表
-- （agg_building_daily / agg_building_monthly，见本文件末尾）。
-- 规范：看板与报表一律查预聚合表；MCP 明细查询工具才查事实表。
-- 另注意：分区裁剪要求 WHERE 直接写分区列（如 stat_year=2016）：
--   写表达式 stat_year*100+stat_month BETWEEN ... 不会触发裁剪。
CREATE TABLE fact_energy_hourly (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    building_id         VARCHAR(96)  NOT NULL COMMENT '建筑编号',
    site_id             VARCHAR(32)  NOT NULL COMMENT '园区编号',
    ts                  DATETIME     NOT NULL COMMENT '监测时间（小时级）',
    -- 时间维度（冗余存储，避免查询时反复函数计算，提升查询性能）
    stat_date           DATE         NOT NULL COMMENT '日期',
    stat_year           SMALLINT     NOT NULL COMMENT '年',
    stat_month          TINYINT      NOT NULL COMMENT '月',
    stat_day            TINYINT      NOT NULL COMMENT '日',
    stat_hour           TINYINT      NOT NULL COMMENT '小时 0-23',
    stat_weekday        TINYINT      NOT NULL COMMENT '星期 0=周一',
    is_weekend          TINYINT      NOT NULL COMMENT '是否周末 0/1',
    season              VARCHAR(8)            COMMENT '季节',
    -- 能耗数据
    electricity_kwh     DECIMAL(18,4)         COMMENT '电力能耗(kWh)',
    water_m3            DECIMAL(18,4)         COMMENT '水耗(m3)',
    hvac_kwh            DECIMAL(18,4)         COMMENT '空调系统能耗(kWh)',
    gas_kwh             DECIMAL(18,4)         COMMENT '燃气能耗(kWh)',
    solar_kwh           DECIMAL(18,4)         COMMENT '光伏发电量(kWh)',
    hvac_electricity_kwh DECIMAL(18,4)        COMMENT '空调系统电耗(kWh,推算)',
    carbon_kg           DECIMAL(18,4)         COMMENT '碳排放(kgCO2,推算)',
    cop                 DECIMAL(6,3)          COMMENT '机组COP(仿真)',
    chw_supply_temp_c   DECIMAL(6,2)          COMMENT '空调出水温度(℃,仿真)',
    chw_return_temp_c   DECIMAL(6,2)          COMMENT '空调回水温度(℃,仿真)',
    -- 影响因素
    air_temperature_c   DECIMAL(6,2)          COMMENT '环境温度(℃)',
    humidity_pct        DECIMAL(6,2)          COMMENT '相对湿度(%RH,推算)',
    occupancy_density   DECIMAL(12,3)          COMMENT '人员密度(人/100m2,仿真)',
    wind_speed_ms       DECIMAL(6,2)          COMMENT '风速(m/s)',
    cloud_cover         DECIMAL(5,2)          COMMENT '云量',
    -- 设备信息与标注
    equipment_status    VARCHAR(12)           COMMENT '设备状态 正常/异常/离线/未评估',
    anomaly_flag        TINYINT               COMMENT '能耗异常 1/0/-1(未评估)',
    anomaly_type        VARCHAR(16)           COMMENT '异常类型',
    severity            VARCHAR(8)            COMMENT '严重度',
    data_quality_flag   TINYINT               COMMENT '数据质量缺陷 1/0/-1',
    data_quality_type   VARCHAR(16)           COMMENT '数据质量缺陷类型',
    baseline            DECIMAL(18,4)         COMMENT '基线值（同星期几同小时历史中位数）',
    zscore              DECIMAL(14,3)         COMMENT '偏差得分（残差/阈值上限，>1 即越限）',
    meter_source        VARCHAR(32)           COMMENT '计量来源',
    data_grade          CHAR(1)               COMMENT '数据分级 A/B',
    PRIMARY KEY (id, stat_year, stat_month),
    -- 核心查询索引：按建筑+时间（最高频）、按时间+异常（告警看板）
    KEY idx_building_ts (building_id, ts),
    KEY idx_ts (ts),
    KEY idx_anomaly (anomaly_flag, ts),
    KEY idx_site_ts (site_id, ts),
    KEY idx_quality (data_quality_flag, ts),
    -- 覆盖索引：异常统计查询只需扫描索引，避免 600 万次回表
    KEY idx_anomaly_cover (anomaly_flag, stat_year, ts, data_quality_flag)
) ENGINE=InnoDB COMMENT='统一能耗数据集（小时级）'
PARTITION BY RANGE (stat_year * 100 + stat_month) (
    PARTITION p201601 VALUES LESS THAN (201602),
    PARTITION p201602 VALUES LESS THAN (201603),
    PARTITION p201603 VALUES LESS THAN (201604),
    PARTITION p201604 VALUES LESS THAN (201605),
    PARTITION p201605 VALUES LESS THAN (201606),
    PARTITION p201606 VALUES LESS THAN (201607),
    PARTITION p201607 VALUES LESS THAN (201608),
    PARTITION p201608 VALUES LESS THAN (201609),
    PARTITION p201609 VALUES LESS THAN (201610),
    PARTITION p201610 VALUES LESS THAN (201611),
    PARTITION p201611 VALUES LESS THAN (201612),
    PARTITION p201612 VALUES LESS THAN (201701),
    PARTITION p201701 VALUES LESS THAN (201702),
    PARTITION p201702 VALUES LESS THAN (201703),
    PARTITION p201703 VALUES LESS THAN (201704),
    PARTITION p201704 VALUES LESS THAN (201705),
    PARTITION p201705 VALUES LESS THAN (201706),
    PARTITION p201706 VALUES LESS THAN (201707),
    PARTITION p201707 VALUES LESS THAN (201708),
    PARTITION pmax    VALUES LESS THAN MAXVALUE
);

-- ---------------------------------------------------------------------------
-- 4. 异常事件表（由事实表聚合而来，供告警看板与 MCP 异常扫描工具快速查询）
-- ---------------------------------------------------------------------------
CREATE TABLE fact_anomaly_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    building_id     VARCHAR(96)  NOT NULL,
    site_id         VARCHAR(32)  NOT NULL,
    start_ts        DATETIME     NOT NULL COMMENT '异常开始时间',
    end_ts          DATETIME     NOT NULL COMMENT '异常结束时间',
    duration_hours  INT          NOT NULL COMMENT '持续小时数',
    anomaly_type    VARCHAR(16)  NOT NULL COMMENT '异常类型',
    severity        VARCHAR(8)            COMMENT '严重度',
    peak_value      DECIMAL(16,4)         COMMENT '峰值',
    baseline_value  DECIMAL(16,4)         COMMENT '同期基线',
    deviation_pct   DECIMAL(10,2)         COMMENT '偏离百分比',
    handled         TINYINT DEFAULT 0     COMMENT '是否已处理',
    handle_note     VARCHAR(512)          COMMENT '处理说明（人工复核记录）',
    PRIMARY KEY (id),
    KEY idx_building (building_id, start_ts),
    KEY idx_type (anomaly_type),
    KEY idx_handled (handled)
) ENGINE=InnoDB COMMENT='异常事件表（聚合后）';

-- ---------------------------------------------------------------------------
-- 5. 数据导入审计（申报表"审计留痕"要求）
-- ---------------------------------------------------------------------------
CREATE TABLE data_import_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no        VARCHAR(64)  NOT NULL COMMENT '导入批次号',
    file_name       VARCHAR(255)          COMMENT '源文件名',
    file_type       VARCHAR(16)           COMMENT 'CSV/JSON/EXCEL',
    total_rows      INT                   COMMENT '总记录数',
    success_rows    INT                   COMMENT '成功数',
    failed_rows     INT                   COMMENT '失败数',
    field_mapping   JSON                  COMMENT '字段映射方案（AI 批量规划结果）',
    clean_rules     JSON                  COMMENT '清洗规则',
    status          VARCHAR(16)           COMMENT '状态 成功/部分成功/失败',
    error_detail    TEXT                  COMMENT '错误详情',
    operator        VARCHAR(64)           COMMENT '操作人',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_batch (batch_no),
    KEY idx_created (created_at)
) ENGINE=InnoDB COMMENT='数据导入审计日志';

-- ---------------------------------------------------------------------------
-- 6. 系统操作日志（RBAC 审计留痕）
-- ---------------------------------------------------------------------------
CREATE TABLE sys_oper_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    title           VARCHAR(64)           COMMENT '模块标题',
    business_type   VARCHAR(16)           COMMENT '操作类型 查询/新增/修改/删除/导入/导出',
    method          VARCHAR(255)          COMMENT '方法名称',
    request_method  VARCHAR(8)            COMMENT 'HTTP 方法',
    oper_name       VARCHAR(64)           COMMENT '操作人',
    oper_url        VARCHAR(255)          COMMENT '请求URL',
    oper_ip         VARCHAR(64)           COMMENT '操作IP',
    oper_param      TEXT                  COMMENT '请求参数',
    json_result     TEXT                  COMMENT '返回结果',
    status          TINYINT               COMMENT '状态 0正常 1异常',
    error_msg       TEXT                  COMMENT '错误消息',
    cost_ms         BIGINT                COMMENT '耗时(毫秒)',
    oper_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_oper_time (oper_time),
    KEY idx_oper_name (oper_name)
) ENGINE=InnoDB COMMENT='操作日志（审计留痕）';

-- ---------------------------------------------------------------------------
-- 7. 用户与角色（轻量 RBAC，不引入若依全家桶）
-- ---------------------------------------------------------------------------
CREATE TABLE sys_user (
    user_id         BIGINT       NOT NULL AUTO_INCREMENT,
    username        VARCHAR(64)  NOT NULL COMMENT '登录名',
    nickname        VARCHAR(64)           COMMENT '姓名',
    password        VARCHAR(128) NOT NULL COMMENT 'BCrypt 密文',
    dept            VARCHAR(64)           COMMENT '部门',
    phone           VARCHAR(20),
    status          TINYINT DEFAULT 1     COMMENT '1正常 0停用',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='系统用户';

CREATE TABLE sys_role (
    role_id         BIGINT       NOT NULL AUTO_INCREMENT,
    role_key        VARCHAR(64)  NOT NULL COMMENT '角色标识 admin/operator/viewer',
    role_name       VARCHAR(64)  NOT NULL COMMENT '角色名称',
    remark          VARCHAR(255),
    PRIMARY KEY (role_id),
    UNIQUE KEY uk_role_key (role_key)
) ENGINE=InnoDB COMMENT='系统角色';

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB COMMENT='用户角色关联';

-- ---------------------------------------------------------------------------
-- 8. MCP 工具调用日志（可解释性 + 指标2 评测依据）
-- ---------------------------------------------------------------------------
CREATE TABLE ai_tool_call_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    session_id      VARCHAR(64)           COMMENT '会话ID',
    question        TEXT                  COMMENT '用户原始问题',
    tool_name       VARCHAR(64)           COMMENT 'MCP 工具名',
    tool_args       JSON                  COMMENT '工具入参',
    tool_result_rows INT                  COMMENT '返回行数',
    elapsed_ms      BIGINT                COMMENT '工具耗时',
    answer          TEXT                  COMMENT '最终回答',
    citations       JSON                  COMMENT '知识库引用（RAG 溯源）',
    confidence      DECIMAL(5,4)          COMMENT '置信度',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id),
    KEY idx_tool (tool_name),
    KEY idx_created (created_at)
) ENGINE=InnoDB COMMENT='AI 工具调用日志';

-- ---------------------------------------------------------------------------
-- 9. 标准问答测试集（申报表指标2：准确率 ≥ 85% 的评测依据）
-- ---------------------------------------------------------------------------
CREATE TABLE ai_eval_qa (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    qa_no           INT          NOT NULL COMMENT '题号',
    category        VARCHAR(32)           COMMENT '类别 数据查询/异常诊断/运维知识/统计报表',
    question        TEXT         NOT NULL COMMENT '标准问题',
    expected_answer TEXT                  COMMENT '标准答案要点',
    expected_tools  VARCHAR(255)          COMMENT '期望调用的MCP工具',
    actual_answer   TEXT                  COMMENT '系统实际回答',
    is_correct      TINYINT               COMMENT '是否正确 1/0',
    reviewer        VARCHAR(64)           COMMENT '评审人',
    reviewed_at     DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_qa_no (qa_no)
) ENGINE=InnoDB COMMENT='AI 问答标准测试集';

-- ============================================================================
-- 视图：A 级建筑异常概览（供告警看板直接查询，避免重复写复杂条件）
-- ============================================================================
CREATE OR REPLACE VIEW v_anomaly_overview AS
SELECT
    f.building_id,
    b.building_type_cn,
    f.site_id,
    f.stat_date,
    COUNT(*)                        AS anomaly_hours,
    SUM(f.electricity_kwh)          AS anomaly_energy_kwh,
    MAX(f.zscore)                   AS max_zscore,
    GROUP_CONCAT(DISTINCT f.anomaly_type ORDER BY f.anomaly_type) AS anomaly_types
FROM fact_energy_hourly f
JOIN dim_building b ON b.building_id = f.building_id
WHERE f.anomaly_flag = 1
GROUP BY f.building_id, b.building_type_cn, f.site_id, f.stat_date;

-- ============================================================================
-- 视图：建筑月度能耗汇总（供报表导出模块直接使用）
-- ============================================================================
CREATE OR REPLACE VIEW v_building_monthly AS
SELECT
    f.building_id,
    b.building_type_cn,
    f.site_id,
    f.stat_year,
    f.stat_month,
    SUM(f.electricity_kwh)  AS electricity_kwh,
    SUM(f.hvac_kwh)         AS hvac_kwh,
    SUM(f.water_m3)         AS water_m3,
    SUM(f.carbon_kg)        AS carbon_kg,
    AVG(f.cop)              AS cop_avg,
    AVG(f.air_temperature_c) AS air_temp_avg,
    SUM(CASE WHEN f.anomaly_flag = 1 THEN 1 ELSE 0 END) AS anomaly_hours
FROM fact_energy_hourly f
JOIN dim_building b ON b.building_id = f.building_id
GROUP BY f.building_id, b.building_type_cn, f.site_id, f.stat_year, f.stat_month;


-- ============================================================================
-- 预聚合表（看板与报表专用，保证 ≤2 秒响应）
-- ============================================================================

-- 建筑日汇总（约 456 栋 × 547 天 ≈ 25 万行）
CREATE TABLE agg_building_daily (
    building_id         VARCHAR(96)  NOT NULL,
    site_id             VARCHAR(32)  NOT NULL,
    stat_date           DATE         NOT NULL,
    stat_year           SMALLINT     NOT NULL,
    stat_month          TINYINT      NOT NULL,
    is_weekend          TINYINT      NOT NULL,
    season              VARCHAR(8),
    electricity_kwh     DECIMAL(18,3)         COMMENT '当日用电量',
    hvac_kwh            DECIMAL(18,3)         COMMENT '当日空调能耗',
    water_m3            DECIMAL(18,3),
    carbon_kg           DECIMAL(18,3),
    cop_avg             DECIMAL(8,3)          COMMENT '当日平均COP',
    air_temp_avg        DECIMAL(8,2),
    humidity_avg        DECIMAL(8,2),
    occupancy_avg       DECIMAL(12,3),
    anomaly_hours       INT DEFAULT 0         COMMENT '当日异常小时数',
    quality_hours       INT DEFAULT 0         COMMENT '当日数据质量缺陷小时数',
    peak_hour_kwh       DECIMAL(18,3)         COMMENT '当日峰值小时能耗',
    PRIMARY KEY (building_id, stat_date),
    KEY idx_site_date (site_id, stat_date),
    KEY idx_date (stat_date),
    KEY idx_year_month (stat_year, stat_month)
) ENGINE=InnoDB COMMENT='建筑日能耗汇总（预聚合）';

-- 建筑月汇总（约 456 栋 × 18 月 ≈ 8200 行）
CREATE TABLE agg_building_monthly (
    building_id         VARCHAR(96)  NOT NULL,
    site_id             VARCHAR(32)  NOT NULL,
    building_type_cn    VARCHAR(32),
    stat_year           SMALLINT     NOT NULL,
    stat_month          TINYINT      NOT NULL,
    days                INT                   COMMENT '当月有效天数',
    electricity_kwh     DECIMAL(18,3),
    hvac_kwh            DECIMAL(18,3),
    water_m3            DECIMAL(18,3),
    carbon_kg           DECIMAL(18,3),
    cop_avg             DECIMAL(8,3),
    air_temp_avg        DECIMAL(8,2),
    eui_kwh_m2          DECIMAL(12,3)         COMMENT '当月能耗强度',
    anomaly_hours       INT DEFAULT 0,
    anomaly_rate        DECIMAL(8,4),
    PRIMARY KEY (building_id, stat_year, stat_month),
    KEY idx_site_ym (site_id, stat_year, stat_month),
    KEY idx_ym (stat_year, stat_month),
    KEY idx_type_ym (building_type_cn, stat_year, stat_month)
) ENGINE=InnoDB COMMENT='建筑月能耗汇总（预聚合）';


-- 异常统计预聚合表（按 建筑 × 日期 汇总异常小时数）
-- 【为什么需要】异常分析要统计"评估小时数/异常小时数/质量缺陷小时数"，
-- 直接在 600 万行事实表上做条件求和实测需 1.1~1.7 秒，叠加其他子查询后
-- 接口超过 2 秒验收线。本表仅约 25 万行，可把该统计降到毫秒级。
CREATE TABLE agg_anomaly_daily (
    building_id       VARCHAR(96) NOT NULL,
    site_id           VARCHAR(32) NOT NULL,
    stat_date         DATE        NOT NULL,
    stat_year         SMALLINT    NOT NULL,
    stat_month        TINYINT     NOT NULL,
    evaluated_hours   INT DEFAULT 0  COMMENT '已评估小时数（anomaly_flag>=0）',
    anomaly_hours     INT DEFAULT 0  COMMENT '能耗异常小时数',
    quality_hours     INT DEFAULT 0  COMMENT '数据质量缺陷小时数',
    event_count       INT DEFAULT 0  COMMENT '当日异常事件数',
    PRIMARY KEY (building_id, stat_date),
    KEY idx_site_date (site_id, stat_date),
    KEY idx_date (stat_date),
    KEY idx_year (stat_year)
) ENGINE=InnoDB COMMENT='异常统计日汇总（预聚合）';

-- 时间维度小时汇总（跨建筑求和，约 1.3 万行）
-- 【为什么需要】时段汇总/峰谷分析/分时电价统计需要"按小时跨建筑求和"，
-- 在 600 万行事实表上分组实测需 1.98s(峰谷) + 2.36s(分时)，
-- 叠加后使统计①接口达到 4.6s，严重超出 2 秒验收线。
-- 本表把时间维度预先聚合，实测降到 0.036s / 0.037s。
-- 注意：本表不含 building_id / site_id 维度，因此仅在"无建筑、无园区过滤"时可用，
-- 一旦按建筑或园区过滤，必须回退事实表（见 AnalyticsMapper.xml 的分支逻辑）。
CREATE TABLE agg_time_hourly (
    stat_date         DATE        NOT NULL,
    stat_year         SMALLINT    NOT NULL,
    stat_month        TINYINT     NOT NULL,
    stat_hour         TINYINT     NOT NULL,
    stat_weekday      TINYINT     NOT NULL,
    is_weekend        TINYINT     NOT NULL,
    season            VARCHAR(8),
    electricity_kwh   DECIMAL(20,3),
    hvac_kwh          DECIMAL(20,3),
    water_m3          DECIMAL(20,3),
    carbon_kg         DECIMAL(20,3),
    cop_sum           DECIMAL(20,3)  COMMENT 'COP 求和（配合 cop_cnt 求均值）',
    cop_cnt           INT,
    temp_sum          DECIMAL(20,3)  COMMENT '温度求和（配合 temp_cnt 求均值）',
    temp_cnt          INT,
    anomaly_hours     INT,
    quality_hours     INT,
    PRIMARY KEY (stat_date, stat_hour),
    KEY idx_hour (stat_hour),
    KEY idx_year_hour (stat_year, stat_hour),
    KEY idx_ym (stat_year, stat_month)
) ENGINE=InnoDB COMMENT='时间维度小时汇总（跨建筑求和，供时段/峰谷/分时统计）';

-- 园区×日期×小时汇总（18 园区 × 13,140 小时 ≈ 23.7 万行）
-- 【为什么需要】agg_time_hourly 只做跨建筑汇总、没有 site_id 列，
-- 因此"带园区过滤"的时段/峰谷/分时统计会回退到 600 万行事实表。
-- 实测：month + siteId 的时段汇总接口耗时 685ms（其中分时/小时汇总两段各约 300ms），
-- 本表把园区维度预先聚合后降到 53ms（13 倍）。
-- 【查询路径】无过滤 → agg_time_hourly；仅园区过滤 → agg_site_hourly；
--             按建筑过滤 → fact_energy_hourly（建筑维度太细，无法再预聚合）
CREATE TABLE agg_site_hourly (
    site_id         VARCHAR(32) NOT NULL,
    stat_date       DATE        NOT NULL,
    stat_hour       TINYINT     NOT NULL,
    stat_year       SMALLINT    NOT NULL,
    stat_month      TINYINT     NOT NULL,
    stat_weekday    TINYINT     NOT NULL,
    is_weekend      TINYINT     NOT NULL,
    season          VARCHAR(8),
    electricity_kwh DECIMAL(20,3),
    hvac_kwh        DECIMAL(20,3),
    water_m3        DECIMAL(20,3),
    carbon_kg       DECIMAL(20,3),
    cop_sum         DECIMAL(20,3),
    cop_cnt         INT,
    air_temp_sum    DECIMAL(20,3),
    air_temp_cnt    INT,
    anomaly_hours   INT,
    quality_hours   INT,
    PRIMARY KEY (site_id, stat_date, stat_hour),
    KEY idx_date_hour (stat_date, stat_hour),
    KEY idx_site_date (site_id, stat_date),
    KEY idx_hour (stat_hour)
) ENGINE=InnoDB COMMENT='园区×日期×小时汇总，供带园区过滤的时段/峰谷/分时统计';
