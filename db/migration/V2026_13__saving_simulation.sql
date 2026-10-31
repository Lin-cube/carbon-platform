-- 节能潜力情景模拟结果（独立存储，绝不写回事实表/聚合表/统计指标）
CREATE TABLE IF NOT EXISTS energy_saving_simulation (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  building_id  VARCHAR(96)  NOT NULL,
  site_id      VARCHAR(32)  NULL,
  scenario     VARCHAR(32)  NOT NULL COMMENT 'REDUCE_RATIO / COP_IMPROVE',
  start_date   DATE         NOT NULL,
  end_date     DATE         NOT NULL,
  sufficient   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=结果可用 0=数据不足',
  params_json  JSON         NULL COMMENT '假设参数',
  baseline_json JSON        NULL COMMENT '历史基准',
  result_json  JSON         NULL COMMENT '测算结果',
  basis_json   JSON         NULL COMMENT '计算依据与限制',
  ai_narrative TEXT         NULL COMMENT 'AI 解释（人工可编辑，非结论）',
  ai_mode      VARCHAR(16)  NULL COMMENT 'llm / template',
  created_by   VARCHAR(64)  NULL,
  created_at   DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_building (building_id, created_at),
  KEY idx_scenario (scenario, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节能潜力情景模拟（只读分析结果，不影响真实统计）';
