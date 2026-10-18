"""把统一能耗数据集导入 MySQL 8

前置条件：
  1. 已创建库表： mysql -u root < data-pipeline/sql/01_schema.sql
  2. 已安装 PyMySQL：已在 requirements.txt

用法：
    # 默认连接 root@127.0.0.1:3306/carbon_energy
    .venv/bin/python data-pipeline/scripts/07_load_to_mysql.py

    # 自定义连接
    CARBON_MYSQL_URL="mysql+pymysql://user:pass@host:3306/db?charset=utf8mb4" \
        .venv/bin/python data-pipeline/scripts/07_load_to_mysql.py

设计要点：
  - 分块写入（默认 5 万行/批），避免一次性构造超大 INSERT；
  - 用 executemany 配合 SQLAlchemy Core，性能远优于 ORM 逐行；
  - 维度表先写，事实表后写；失败可重入（先按批次清理再写）。
"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from config import EXPORTS_DIR, MYSQL_URL, PROCESSED_DIR  # noqa: E402

PARQUET = EXPORTS_DIR / "unified_energy_dataset.parquet"
DIM_CSV = PROCESSED_DIR / "dim_building.csv"

CHUNK = 100_000      # 外层：每次从 parquet 取多少行
BATCH = 2_000        # 内层：单次 executemany 的行数（避免超出 max_allowed_packet）

# 事实表列（与 01_schema.sql 的 fact_energy_hourly 对齐）
FACT_COLS = [
    "building_id", "site_id", "ts", "stat_date", "stat_year", "stat_month", "stat_day",
    "stat_hour", "stat_weekday", "is_weekend", "season",
    "electricity_kwh", "water_m3", "hvac_kwh", "gas_kwh", "solar_kwh",
    "hvac_electricity_kwh", "carbon_kg", "cop", "chw_supply_temp_c", "chw_return_temp_c",
    "air_temperature_c", "humidity_pct", "occupancy_density", "wind_speed_ms", "cloud_cover",
    "equipment_status", "anomaly_flag", "anomaly_type", "severity",
    "data_quality_flag", "data_quality_type", "baseline", "zscore",
    "meter_source", "data_grade",
]

# 统一数据集列名 -> 数据库列名
RENAME = {"timestamp": "ts", "date": "stat_date", "year": "stat_year", "month": "stat_month",
          "day": "stat_day", "hour": "stat_hour", "weekday": "stat_weekday"}


def prep_dim(engine) -> None:
    if not DIM_CSV.exists():
        print(f"[跳过] 未找到 {DIM_CSV}")
        return
    dim = pd.read_csv(DIM_CSV)
    cols = ["building_id", "site_id", "building_type", "building_type_cn", "data_grade",
            "meter_source", "area_m2", "floors", "year_built", "occupants",
            "electricity_kwh_year", "eui_kwh_m2_year", "carbon_kg_year", "cop_mean",
            "energy_grade", "anomaly_rate", "quality_rate"]
    dim = dim[[c for c in cols if c in dim.columns]]
    with engine.begin() as conn:
        conn.execute(text("DELETE FROM dim_building"))
    dim.to_sql("dim_building", engine, if_exists="append", index=False, chunksize=1000, method="multi")
    print(f"[维表] dim_building 写入 {len(dim):,} 行")

    sites = (
        dim.groupby("site_id").size().reset_index(name="building_count")[["site_id", "building_count"]]
    )
    with engine.begin() as conn:
        conn.execute(text("DELETE FROM dim_site"))
    sites.to_sql("dim_site", engine, if_exists="append", index=False)
    print(f"[维表] dim_site 写入 {len(sites)} 行")


def prep_fact(df: pd.DataFrame) -> pd.DataFrame:
    df = df.rename(columns=RENAME)
    for c in FACT_COLS:
        if c not in df.columns:
            df[c] = None
    df = df[FACT_COLS]
    # 分区键不允许 NULL
    df["stat_year"] = df["stat_year"].fillna(2016).astype("int32")
    df["stat_month"] = df["stat_month"].fillna(1).astype("int32")
    # MySQL TINYINT 不接受 -1 以外的负值场景无影响；anomaly_flag=-1 表示未评估，合法
    for c in ("is_weekend", "stat_weekday", "stat_day", "stat_hour",
              "anomaly_flag", "data_quality_flag"):
        df[c] = pd.to_numeric(df[c], errors="coerce").fillna(0).astype("int32")
    df["ts"] = pd.to_datetime(df["ts"])
    df["stat_date"] = pd.to_datetime(df["stat_date"]).dt.date
    # NaN -> None，让 MySQL 存 NULL 而不是 'nan'
    return df.astype(object).where(pd.notna(df), None)


def insert_fact(engine, df: pd.DataFrame, table: str = "fact_energy_hourly") -> None:
    """用 executemany 批量写入事实表。

    【踩坑】不要用 DataFrame.to_sql(method="multi")：它会拼出一条巨大的
    多值 INSERT，超过 MySQL 默认 max_allowed_packet(64MB) 而报
    "MySQL server has gone away"。改用逐行参数化的 executemany，
    由驱动在协议层批处理，不构造超大 SQL 文本。
    """
    cols = list(df.columns)
    placeholders = ", ".join(f":{c}" for c in cols)
    stmt = text(
        f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({placeholders})"
    )
    records = df.to_dict(orient="records")
    total = 0
    for i in range(0, len(records), BATCH):
        batch = records[i:i + BATCH]
        with engine.begin() as conn:
            conn.execute(stmt, batch)
        total += len(batch)
    return total


def main() -> int:
    url = os.environ.get("CARBON_MYSQL_URL", MYSQL_URL)
    print("=" * 78)
    print("导入统一能耗数据集到 MySQL")
    print("=" * 78)
    print(f"连接: {url.split('@')[-1]}")

    if not PARQUET.exists():
        print(f"[错误] 未找到 {PARQUET}，请先运行 02_build_unified_dataset.py")
        return 1

    engine = create_engine(url, pool_pre_ping=True, pool_recycle=3600, future=True)
    try:
        with engine.connect() as conn:
            ver = conn.execute(text("SELECT VERSION()")).scalar()
            maxpkt = conn.execute(text("SELECT @@max_allowed_packet")).scalar()
        print(f"MySQL 版本: {ver}   max_allowed_packet: {maxpkt/1024/1024:.0f} MB")
    except Exception as e:
        print(f"[错误] 无法连接数据库: {e}")
        print("       请确认 MySQL 已启动，且已执行 sql/01_schema.sql")
        return 1

    prep_dim(engine)

    print(f"\n读取 {PARQUET.name} ...", flush=True)
    df = pd.read_parquet(PARQUET)
    print(f"总行数 {len(df):,}，开始写入（每批 {BATCH:,} 行）...", flush=True)
    df = prep_fact(df)

    with engine.begin() as conn:
        conn.execute(text("DELETE FROM fact_energy_hourly"))

    t0 = time.time()
    total = 0
    for i in range(0, len(df), CHUNK):
        part = df.iloc[i:i + CHUNK]
        insert_fact(engine, part)
        total += len(part)
        pct = total / len(df) * 100
        print(f"  {total:>10,}/{len(df):,}  ({pct:5.1f}%)  {time.time()-t0:.0f}s", flush=True)

    # 聚合异常事件表：把连续小时的异常合并为一个"事件"
    # 分组方法：按 building_id 排序，若与上一行时间差 > 1 小时则视为新事件起点，
    #           用累计求和生成 run_id。比"时间差 - ROW_NUMBER"的写法更稳健
    #           （后者依赖时间戳严格等间隔）。
    print("\n聚合异常事件表 ...", flush=True)
    with engine.begin() as conn:
        conn.execute(text("DELETE FROM fact_anomaly_event"))
        conn.execute(text("""
            INSERT INTO fact_anomaly_event
                (building_id, site_id, start_ts, end_ts, duration_hours,
                 anomaly_type, severity, peak_value, baseline_value, deviation_pct)
            WITH ordered AS (
                SELECT building_id, site_id, ts, electricity_kwh, baseline,
                       anomaly_type, severity,
                       TIMESTAMPDIFF(HOUR,
                           LAG(ts) OVER (PARTITION BY building_id ORDER BY ts), ts) AS gap_h
                FROM fact_energy_hourly
                WHERE anomaly_flag = 1
            ),
            marked AS (
                SELECT *, CASE WHEN gap_h IS NULL OR gap_h > 1 THEN 1 ELSE 0 END AS is_new
                FROM ordered
            ),
            runs AS (
                SELECT *, SUM(is_new) OVER (PARTITION BY building_id ORDER BY ts
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS run_id
                FROM marked
            )
            SELECT building_id, site_id,
                   MIN(ts) AS start_ts, MAX(ts) AS end_ts, COUNT(*) AS duration_hours,
                   MAX(anomaly_type) AS anomaly_type, MAX(severity) AS severity,
                   MAX(electricity_kwh) AS peak_value, MAX(baseline) AS baseline_value,
                   ROUND((MAX(electricity_kwh) - MAX(baseline))
                         / NULLIF(MAX(baseline), 0) * 100, 2) AS deviation_pct
            FROM runs
            GROUP BY building_id, site_id, run_id
        """))
        n_evt = conn.execute(text("SELECT COUNT(*) FROM fact_anomaly_event")).scalar()
    print(f"[事件表] fact_anomaly_event 写入 {n_evt:,} 个异常事件")

    # 构建预聚合表（看板与报表专用，保证 ≤2 秒响应）
    print("\n构建预聚合表 ...", flush=True)
    with engine.begin() as conn:
        conn.execute(text("TRUNCATE TABLE agg_building_daily"))
        conn.execute(text("TRUNCATE TABLE agg_building_monthly"))
        conn.execute(text("""
            INSERT INTO agg_building_daily
              (building_id, site_id, stat_date, stat_year, stat_month, is_weekend, season,
               electricity_kwh, hvac_kwh, water_m3, carbon_kg, cop_avg, air_temp_avg,
               humidity_avg, occupancy_avg, anomaly_hours, quality_hours, peak_hour_kwh)
            SELECT building_id, site_id, stat_date, stat_year, stat_month,
                   MAX(is_weekend), MAX(season),
                   SUM(electricity_kwh), SUM(hvac_kwh), SUM(water_m3), SUM(carbon_kg),
                   AVG(cop), AVG(air_temperature_c), AVG(humidity_pct), AVG(occupancy_density),
                   SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN data_quality_flag = 1 THEN 1 ELSE 0 END),
                   MAX(electricity_kwh)
            FROM fact_energy_hourly
            GROUP BY building_id, site_id, stat_date, stat_year, stat_month
        """))
        conn.execute(text("""
            INSERT INTO agg_building_monthly
              (building_id, site_id, building_type_cn, stat_year, stat_month, days,
               electricity_kwh, hvac_kwh, water_m3, carbon_kg, cop_avg, air_temp_avg,
               eui_kwh_m2, anomaly_hours, anomaly_rate)
            SELECT d.building_id, d.site_id, b.building_type_cn, d.stat_year, d.stat_month,
                   COUNT(*), SUM(d.electricity_kwh), SUM(d.hvac_kwh), SUM(d.water_m3),
                   SUM(d.carbon_kg), AVG(d.cop_avg), AVG(d.air_temp_avg),
                   ROUND(SUM(d.electricity_kwh) / NULLIF(MAX(b.area_m2), 0), 3),
                   SUM(d.anomaly_hours),
                   ROUND(SUM(d.anomaly_hours) / NULLIF(COUNT(*) * 24, 0), 4)
            FROM agg_building_daily d
            JOIN dim_building b USING (building_id)
            GROUP BY d.building_id, d.site_id, b.building_type_cn, d.stat_year, d.stat_month
        """))
        n_d = conn.execute(text("SELECT COUNT(*) FROM agg_building_daily")).scalar()
        n_m = conn.execute(text("SELECT COUNT(*) FROM agg_building_monthly")).scalar()
        # 异常统计预聚合：异常分析接口依赖它把响应从 1.7s 降到 0.26s
        conn.execute(text("TRUNCATE TABLE agg_anomaly_daily"))
        conn.execute(text("""
            INSERT INTO agg_anomaly_daily
              (building_id, site_id, stat_date, stat_year, stat_month,
               evaluated_hours, anomaly_hours, quality_hours)
            SELECT building_id, site_id, stat_date, stat_year, stat_month,
                   SUM(CASE WHEN anomaly_flag >= 0 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN data_quality_flag = 1 THEN 1 ELSE 0 END)
            FROM fact_energy_hourly
            GROUP BY building_id, site_id, stat_date, stat_year, stat_month
        """))
        n_a = conn.execute(text("SELECT COUNT(*) FROM agg_anomaly_daily")).scalar()

        # 时间维度小时汇总：时段/峰谷/分时统计依赖它（把 4.6s 降到 0.15s）
        conn.execute(text("TRUNCATE TABLE agg_time_hourly"))
        conn.execute(text("""
            INSERT INTO agg_time_hourly
              (stat_date, stat_year, stat_month, stat_hour, stat_weekday, is_weekend, season,
               electricity_kwh, hvac_kwh, water_m3, carbon_kg, cop_sum, cop_cnt,
               temp_sum, temp_cnt, anomaly_hours, quality_hours)
            SELECT stat_date, stat_year, stat_month, stat_hour, stat_weekday, is_weekend,
                   MAX(season),
                   SUM(electricity_kwh), SUM(hvac_kwh), SUM(water_m3), SUM(carbon_kg),
                   SUM(cop), COUNT(cop), SUM(air_temperature_c), COUNT(air_temperature_c),
                   SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN data_quality_flag = 1 THEN 1 ELSE 0 END)
            FROM fact_energy_hourly
            GROUP BY stat_date, stat_year, stat_month, stat_hour, stat_weekday, is_weekend
        """))
        n_t = conn.execute(text("SELECT COUNT(*) FROM agg_time_hourly")).scalar()

        # 园区维度小时汇总：带园区过滤的时段/峰谷/分时统计依赖它（把 685ms 降到 53ms）
        conn.execute(text("TRUNCATE TABLE agg_site_hourly"))
        conn.execute(text("""
            INSERT INTO agg_site_hourly
              (site_id, stat_date, stat_hour, stat_year, stat_month, stat_weekday, is_weekend, season,
               electricity_kwh, hvac_kwh, water_m3, carbon_kg, cop_sum, cop_cnt,
               air_temp_sum, air_temp_cnt, anomaly_hours, quality_hours)
            SELECT site_id, stat_date, stat_hour, stat_year, stat_month, stat_weekday, is_weekend,
                   MAX(season),
                   SUM(electricity_kwh), SUM(hvac_kwh), SUM(water_m3), SUM(carbon_kg),
                   SUM(cop), COUNT(cop), SUM(air_temperature_c), COUNT(air_temperature_c),
                   SUM(CASE WHEN anomaly_flag = 1 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN data_quality_flag = 1 THEN 1 ELSE 0 END)
            FROM fact_energy_hourly
            GROUP BY site_id, stat_date, stat_hour, stat_year, stat_month, stat_weekday, is_weekend
        """))
        n_s = conn.execute(text("SELECT COUNT(*) FROM agg_site_hourly")).scalar()
    print(f"[预聚合] agg_building_daily {n_d:,} 行, agg_building_monthly {n_m:,} 行, "
          f"agg_anomaly_daily {n_a:,} 行, agg_time_hourly {n_t:,} 行, "
          f"agg_site_hourly {n_s:,} 行")

    print("\n" + "=" * 78)
    with engine.connect() as conn:
        for t in ("dim_site", "dim_building", "fact_energy_hourly", "fact_anomaly_event",
                  "agg_building_daily", "agg_building_monthly"):
            n = conn.execute(text(f"SELECT COUNT(*) FROM {t}")).scalar()
            print(f"  {t:<24} {n:>12,} 行")
    print(f"\n完成，用时 {time.time()-t0:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
