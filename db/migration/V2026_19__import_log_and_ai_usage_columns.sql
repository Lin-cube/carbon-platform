-- 补齐线上缺失的列（本地已有、线上未同步）
-- data_import_log：导入流程 INSERT 会写入 file_id / derived_status，
--   缺列会导致「数据导入」直接失败。
ALTER TABLE data_import_log
  ADD COLUMN file_id        VARCHAR(64)  NULL,
  ADD COLUMN start_date     DATE         NULL,
  ADD COLUMN end_date       DATE         NULL,
  ADD COLUMN replaced_rows  INT          NOT NULL DEFAULT 0,
  ADD COLUMN derived_status VARCHAR(16)  NULL,
  ADD COLUMN error_message  TEXT         NULL,
  ADD COLUMN updated_at     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP;

-- ai_tool_call_log：AI 工具调用日志会记录 token 用量，缺列会导致写入失败
ALTER TABLE ai_tool_call_log
  ADD COLUMN prompt_tokens     INT NULL DEFAULT 0,
  ADD COLUMN completion_tokens INT NULL DEFAULT 0,
  ADD COLUMN total_tokens      INT NULL DEFAULT 0;
