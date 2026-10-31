-- 异常处置闭环：状态 + 处理人 + 处理时间 + 乐观锁 + 复检预留 + 操作审计
-- 复用已有 handled / handle_note，只做增量扩展，非破坏性。

ALTER TABLE fact_anomaly_event
  ADD COLUMN handle_status  VARCHAR(16) NOT NULL DEFAULT '未处理'
      COMMENT '未处理/处理中/已处理/已忽略/待复检' AFTER handle_note,
  ADD COLUMN handle_by      VARCHAR(64) NULL COMMENT '处理人' AFTER handle_status,
  ADD COLUMN handle_at      DATETIME    NULL COMMENT '处理时间' AFTER handle_by,
  ADD COLUMN verify_status  VARCHAR(16) NULL COMMENT '复检预留：待复检/已复检' AFTER handle_at,
  ADD COLUMN verify_at      DATETIME    NULL COMMENT '复检时间' AFTER verify_status,
  ADD COLUMN handle_version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER verify_at,
  ADD COLUMN updated_at     DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER handle_version,
  ADD INDEX idx_handle_status (handle_status);

-- 处理操作审计：谁在什么时候把状态从什么改成什么、备注写了什么
CREATE TABLE IF NOT EXISTS anomaly_handle_log (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  event_id    BIGINT      NOT NULL COMMENT 'fact_anomaly_event.id',
  action      VARCHAR(16) NOT NULL COMMENT 'HANDLE / VERIFY',
  from_status VARCHAR(16) NULL,
  to_status   VARCHAR(16) NOT NULL,
  note        VARCHAR(512) NULL,
  operator    VARCHAR(64) NULL,
  created_at  DATETIME    NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_event (event_id, created_at),
  KEY idx_operator (operator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常人工处置操作日志';
