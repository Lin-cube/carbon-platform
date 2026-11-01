-- 补 fact_anomaly_event 的处置/复检字段
-- 背景：本地在处置闭环开发时已加，但未同步到线上库，
-- 导致新 jar 启动后异常扫描接口报 Unknown column 'e.handle_status'。
ALTER TABLE fact_anomaly_event
  ADD COLUMN handle_status  VARCHAR(16)  NOT NULL DEFAULT '未处理' COMMENT '未处理/处理中/已处理/已忽略',
  ADD COLUMN handle_by      VARCHAR(64)  NULL,
  ADD COLUMN handle_at      DATETIME     NULL,
  ADD COLUMN handle_version INT          NOT NULL DEFAULT 0,
  ADD COLUMN verify_status  VARCHAR(16)  NULL COMMENT '复检状态',
  ADD COLUMN verify_at      DATETIME     NULL,
  ADD COLUMN updated_at     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
