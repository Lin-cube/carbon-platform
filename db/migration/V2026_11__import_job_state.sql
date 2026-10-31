-- 导入批次状态机：让"导入中/派生待重算/失败"可被发现、可恢复、可重算
-- 只新增列，不修改既有列，非破坏性。
ALTER TABLE data_import_log
  ADD COLUMN file_id        VARCHAR(64)  NULL COMMENT '上传文件 id，用于重复提交保护' AFTER batch_no,
  ADD COLUMN start_date     DATE         NULL COMMENT '本次导入数据的最早日期' AFTER status,
  ADD COLUMN end_date       DATE         NULL COMMENT '本次导入数据的最晚日期' AFTER start_date,
  ADD COLUMN replaced_rows  INT          NOT NULL DEFAULT 0 COMMENT '覆盖掉的同主键历史行数' AFTER end_date,
  ADD COLUMN derived_status VARCHAR(16)  NULL COMMENT '派生数据状态 PENDING/OK/FAILED' AFTER replaced_rows,
  ADD COLUMN error_message  TEXT         NULL COMMENT '失败原因' AFTER derived_status,
  ADD COLUMN updated_at     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at,
  ADD INDEX idx_import_file_status (file_id, status);
