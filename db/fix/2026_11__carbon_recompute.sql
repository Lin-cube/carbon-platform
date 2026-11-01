-- P0 修复：把与声明因子 0.5810 不符的碳排行重算为 (电+气)×0.5810
-- 可回滚：先把原值备份到 fact_energy_hourly_carbon_bak
-- 注意：只改 carbon_kg，不动电量/燃气等任何原始能耗。

CREATE TABLE IF NOT EXISTS fact_energy_hourly_carbon_bak (
  id BIGINT NOT NULL,
  stat_year INT NOT NULL,
  stat_month INT NOT NULL,
  building_id VARCHAR(96) NOT NULL,
  ts DATETIME NOT NULL,
  old_carbon_kg DECIMAL(18,4) NULL,
  fixed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id, stat_year, stat_month),
  KEY idx_bak_building (building_id, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='碳排重算前的原值备份（可回滚）';

INSERT IGNORE INTO fact_energy_hourly_carbon_bak
  (id, stat_year, stat_month, building_id, ts, old_carbon_kg)
SELECT id, stat_year, stat_month, building_id, ts, carbon_kg
FROM fact_energy_hourly
WHERE ABS(carbon_kg - (COALESCE(electricity_kwh,0) + COALESCE(gas_kwh,0)) * 0.5810) > 0.01;

UPDATE fact_energy_hourly
SET carbon_kg = ROUND((COALESCE(electricity_kwh,0) + COALESCE(gas_kwh,0)) * 0.5810, 4)
WHERE ABS(carbon_kg - (COALESCE(electricity_kwh,0) + COALESCE(gas_kwh,0)) * 0.5810) > 0.01;
