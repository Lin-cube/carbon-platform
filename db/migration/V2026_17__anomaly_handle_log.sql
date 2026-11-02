-- 异常人工处置操作日志表
-- 背景：处置闭环开发时本地已建，但未同步到线上库，
-- 导致 /api/anomalies/{id} 与 /history 返回 500（抽屉里显示「系统内部错误」）。
CREATE TABLE IF NOT EXISTS anomaly_handle_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  event_id    BIGINT       NOT NULL COMMENT 'fact_anomaly_event.id',
  action      VARCHAR(16)  NOT NULL COMMENT 'HANDLE / VERIFY',
  from_status VARCHAR(16)  NULL,
  to_status   VARCHAR(16)  NOT NULL,
  note        VARCHAR(512) NULL,
  operator    VARCHAR(64)  NULL,
  created_at  DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_event (event_id, created_at),
  KEY idx_operator (operator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常人工处置操作日志';
