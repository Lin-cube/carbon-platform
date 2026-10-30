-- AI 内部查询工具配套索引（2026-10-30）
-- 说明：fact_anomaly_event 原仅有 idx_building(building_id,start_ts)，
--       按园区/日期查询异常事件会全表扫描（66,893 行）。
--       本脚本补三个索引，供 getBuildingAnomalies / getTopAnomalyBuildings 使用。
-- 幂等：已存在索引时执行会报错，可先 SHOW INDEX FROM fact_anomaly_event 确认。

ALTER TABLE fact_anomaly_event ADD INDEX idx_site_start (site_id, start_ts);
ALTER TABLE fact_anomaly_event ADD INDEX idx_start (start_ts);
ALTER TABLE fact_anomaly_event ADD INDEX idx_duration (duration_hours);

-- AI 工具调用 Token 用量记录（2026-10-30）
ALTER TABLE ai_tool_call_log
  ADD COLUMN prompt_tokens INT DEFAULT 0,
  ADD COLUMN completion_tokens INT DEFAULT 0,
  ADD COLUMN total_tokens INT DEFAULT 0;
