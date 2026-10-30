<template>
  <div>
    <!-- 筛选区：分两组 -->
    <div class="page-card mb-12">
      <div class="page-title">多条件能耗查询</div>

      <div class="filter-group">
        <div class="filter-group-label">筛选条件</div>
        <div class="filter-bar">
          <span class="text-dim">园区</span>
          <el-select v-model="app.siteId" placeholder="全部园区" clearable style="width: 150px"
                     @change="onSiteChange">
            <el-option v-for="s in sites" :key="s.siteId" :label="`${s.siteId}（${s.buildingCount}栋）`"
                        :value="s.siteId" />
          </el-select>

          <span class="text-dim">建筑类型</span>
          <el-select v-model="app.buildingType" placeholder="全部类型" clearable style="width: 150px"
                     @change="loadBuildings">
            <el-option v-for="t in typeOptions" :key="t.value" :label="`${t.label}（${t.count}）`"
                        :value="t.value" />
          </el-select>

          <span class="text-dim">建筑</span>
          <el-select v-model="buildingIds" multiple filterable clearable collapse-tags
                     collapse-tags-tooltip :max-collapse-tags="2" placeholder="全部建筑（可多选）"
                     style="width: 280px">
            <el-option v-for="b in buildings" :key="b.buildingId"
                        :label="`${b.buildingId}（${b.buildingTypeCn || '未分类'}）`" :value="b.buildingId" />
          </el-select>
          <span v-if="buildingIds.length" class="text-dim sel-count">
            已选 {{ buildingIds.length }} 栋
          </span>
        </div>
      </div>

      <div class="filter-group">
        <div class="filter-group-label">查询范围</div>
        <div class="filter-bar">
          <span class="text-dim">时间范围</span>
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始" end-placeholder="结束" style="width: 240px"
                          @change="onRangeChange" />

          <span class="text-dim">粒度</span>
          <!-- 后端约束：hourly 只允许 1 栋建筑，见 loadAll 的前置校验 -->
          <el-radio-group v-model="granularity" size="small" @change="onGranularityChange">
            <el-radio-button value="hourly">小时</el-radio-button>
            <el-radio-button value="daily">日</el-radio-button>
            <el-radio-button value="monthly">月</el-radio-button>
          </el-radio-group>

          <span class="text-dim">指标</span>
          <el-select v-model="metric" style="width: 130px">
            <el-option label="用电量" value="electricity" />
            <el-option label="空调能耗" value="hvac" />
            <el-option label="用水量" value="water" />
            <el-option label="碳排" value="carbon" />
          </el-select>

          <span class="text-dim">仅看异常</span>
          <el-switch v-model="onlyAnomaly" />

          <div class="filter-actions">
            <el-button type="primary" :icon="Search" :loading="loading" @click="loadAll">查询</el-button>
            <el-button :icon="Refresh" @click="onReset">重置</el-button>
          </div>
        </div>

        <el-tag v-if="granularity === 'hourly'" type="warning" effect="plain" size="small" class="mt-12">
          小时粒度仅支持单栋建筑
        </el-tag>
      </div>
    </div>

    <!-- 查询失败提示 -->
    <el-alert v-if="loadError" type="warning" :closable="false" show-icon class="mb-12"
              title="查询失败" description="请检查服务状态或放宽筛选条件后重试。" />

    <!-- KPI 指标卡 -->
    <div class="kpi-row">
      <div class="kpi-card">
        <div class="kpi-label">建筑数</div>
        <div class="kpi-value">{{ fmtInt(summary.length) }}<span class="kpi-unit">栋</span></div>
        <div class="kpi-sub">{{ rangeText }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">总用电量</div>
        <div class="kpi-value">{{ fmtNum(totals.electricity) }}<span class="kpi-unit">kWh</span></div>
        <div class="kpi-sub">粒度 {{ granularityCn }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">总空调能耗</div>
        <div class="kpi-value">{{ fmtNum(totals.hvac) }}<span class="kpi-unit">kWh</span></div>
        <div class="kpi-sub">冷热量口径</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">总碳排</div>
        <div class="kpi-value">{{ fmtNum(totals.carbon) }}<span class="kpi-unit">kgCO₂</span></div>
        <div class="kpi-sub">因子 0.5810 kgCO₂/kWh</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均 COP</div>
        <div class="kpi-value">{{ copAvg ?? '--' }}</div>
        <div class="kpi-sub">仅含 {{ copCount }} 栋有冷冻水计量</div>
      </div>
    </div>

    <!-- 主趋势图 -->
    <div class="chart-box mb-12">
      <div class="chart-title">
        {{ metricCn }}趋势
        <span class="src-badge src-measured">实测</span>
        <span v-if="shownBuildings.length > 1" class="text-dim" style="font-weight: 400">
          · 每栋一条曲线（最多 8 栋，图例可点按显隐）
        </span>
      </div>
      <el-alert v-if="trendCapTip" type="info" :closable="false" show-icon class="mb-12"
                :title="trendCapTip" />
      <el-alert v-if="focusNotShown" type="warning" :closable="false" show-icon class="mb-12"
                title="当前联动的建筑不在趋势图前 8 栋中，可在表格继续查看其数值。" />
      <EChart :option="trendOption" height="330px" :loading="loading" />
    </div>

    <div class="grid-2 mb-12">
      <div class="chart-box">
        <div class="chart-title">
          各建筑用电量 TOP 15 <span class="src-badge src-measured">实测</span>
        </div>
        <EChart :option="barOption" height="320px" :loading="loading" />
      </div>

      <div class="chart-box">
        <div class="chart-title">
          汇总明细 <span class="src-badge src-measured">实测</span><span class="src-badge src-simulated">COP为仿真</span>
          <span class="text-dim" style="font-weight: 400">· {{ rangeText }}</span>
        </div>

        <div class="table-toolbar">
          <el-input v-model="filterKeyword" placeholder="搜索建筑编号 / 类型" clearable size="small"
                    :prefix-icon="Search" style="width: 220px" />
          <span class="text-dim" style="font-size: 12px">{{ filteredSummary.length }} / {{ summary.length }} 行</span>
          <el-button size="small" :icon="Download" @click="onExportCsv">导出明细</el-button>
        </div>

        <!-- 小屏横向滚动容器，避免整页溢出 -->
        <div class="table-scroll">
          <el-table :data="pagedSummary" size="small" height="296" v-loading="loading"
                    :default-sort="{ prop: 'electricityKwh', order: 'descending' }"
                    highlight-current-row @row-click="onRowClick"
                    :row-class-name="rowClass">
            <el-table-column prop="buildingId" label="建筑编号" min-width="170" show-overflow-tooltip fixed />
            <el-table-column prop="buildingTypeCn" label="建筑类型" width="104" />
            <el-table-column prop="electricityKwh" label="区间用电量" width="132" align="right" sortable>
              <template #default="{ row }">{{ fmtNum(row.electricityKwh) }}</template>
            </el-table-column>
            <el-table-column prop="hvacKwh" label="空调能耗" width="112" align="right" sortable>
              <template #default="{ row }">{{ fmtNum(row.hvacKwh) }}</template>
            </el-table-column>
            <el-table-column prop="carbonKg" label="碳排(kg)" width="112" align="right" sortable>
              <template #default="{ row }">{{ fmtNum(row.carbonKg) }}</template>
            </el-table-column>
            <el-table-column prop="copAvg" label="平均COP" width="98" align="right" sortable>
              <template #default="{ row }">{{ row.copAvg ?? '--' }}</template>
            </el-table-column>
            <el-table-column prop="eui" label="EUI" width="92" align="right" sortable>
              <template #default="{ row }">{{ row.eui ?? '--' }}</template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination v-if="filteredSummary.length > pageSize" class="mt-12"
                       layout="total, prev, pager, next" :total="filteredSummary.length"
                       :page-size="pageSize" v-model:current-page="pageNum" size="small" background />
      </div>
    </div>

    <el-empty v-if="!loading && !summary.length" description="当前条件下没有查询到数据，请放宽筛选条件" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search, Download } from '@element-plus/icons-vue'
import EChart from '@/components/EChart.vue'
import { energyApi } from '@/api'
import { useAppStore } from '@/stores/app'
import { fmtNum, fmtInt, type EChartsOption } from '@/utils/echarts'
import type {
  BuildingRow, BuildingSummary, DimensionOption, SeriesPoint, SiteRow, TimeSeriesVO
} from '@/types/api'

const app = useAppStore()
const loading = ref(false)
const loadError = ref(false)
const sites = ref<SiteRow[]>([])
const typeOptions = ref<DimensionOption[]>([])
const buildings = ref<BuildingRow[]>([])
const route = useRoute()
const buildingIds = ref<string[]>([])
const granularity = ref<'hourly' | 'daily' | 'monthly'>('daily')
const metric = ref<'electricity' | 'hvac' | 'water' | 'carbon'>('electricity')
const onlyAnomaly = ref(false)
const range = ref<[string, string]>([app.startDate, app.endDate])
const series = ref<TimeSeriesVO | null>(null)
const pageNum = ref(1)
const pageSize = 20
/** 表格关键字搜索 */
const filterKeyword = ref('')
/** 表格行点击 → 趋势图联动聚焦的楼栋 */
const focusBuildingId = ref<string | null>(null)

const summary = computed<BuildingSummary[]>(() => series.value?.summary || [])
const granularityCn = computed(() =>
  ({ hourly: '小时', daily: '日', monthly: '月' })[granularity.value])
const metricCn = computed(() =>
  ({ electricity: '用电量', hvac: '空调能耗', water: '用水量', carbon: '碳排' })[metric.value])
const rangeText = computed(() => `${range.value?.[0] || '--'} ~ ${range.value?.[1] || '--'}`)

/** 主趋势图绘制字段：与指标下拉联动 */
const metricField: Record<string, keyof SeriesPoint> = {
  electricity: 'electricityKwh',
  hvac: 'hvacKwh',
  water: 'waterM3',
  carbon: 'carbonKg'
}

/** 需要绘制的建筑：最多 8 条曲线保证可读性 */
const shownBuildings = computed(() => (series.value?.buildings || []).slice(0, 8))
const trendCapTip = computed(() => {
  const total = series.value?.buildings?.length || 0
  return total > shownBuildings.value.length
    ? `当前命中 ${total} 栋建筑，趋势图仅绘制前 ${shownBuildings.value.length} 栋（曲线过多会影响可读性），完整明细见右侧汇总表。`
    : ''
})
/** 联动聚焦的建筑是否在趋势图内 */
const focusNotShown = computed(() =>
  !!focusBuildingId.value && !shownBuildings.value.includes(focusBuildingId.value))

// ---------------------------------------------------------------- KPI 聚合
const sumBy = (key: 'electricityKwh' | 'hvacKwh' | 'carbonKg') =>
  summary.value.reduce((s, r) => s + (r[key] || 0), 0)

const totals = computed(() => ({
  electricity: sumBy('electricityKwh'),
  hvac: sumBy('hvacKwh'),
  carbon: sumBy('carbonKg')
}))

/** 平均 COP：跳过 null（多数建筑无冷冻水计量，不能按 0 参与平均） */
const copCount = computed(() => summary.value.filter((s) => s.copAvg != null).length)
const copAvg = computed(() => {
  const valid = summary.value.filter((s) => s.copAvg != null) as (BuildingSummary & { copAvg: number })[]
  if (!valid.length) return null
  return +(valid.reduce((s, r) => s + r.copAvg, 0) / valid.length).toFixed(3)
})

// ---------------------------------------------------------------- 图表配置
/** 多建筑可分辨配色：绿/蓝/青/琥珀/紫/红/靛/棕（禁止全绿） */
const palette = [
  '#35866B',   // 绿
  '#4A7BB8',   // 蓝
  '#2AA9A0',   // 青
  '#D99A3E',   // 琥珀
  '#8E6BC0',   // 紫
  '#C26B62',   // 软红
  '#5B8FA8',   // 靛蓝
  '#A0785C'    // 棕
]

const unitCn = computed(() =>
  metric.value === 'carbon' ? 'kgCO₂' : metric.value === 'water' ? 'm³' : 'kWh')

const trendOption = computed<EChartsOption>(() => {
  const pts = series.value?.points || []
  const field = metricField[metric.value] || 'electricityKwh'
  // 多建筑时时间轴取并集并排序，缺失点补 null（折线断开，不画成 0）
  const times = [...new Set(pts.map((p) => p.time))].sort()
  const focus = focusBuildingId.value
  const list = shownBuildings.value.map((b, i) => {
    const map = new Map<string, number | null>()
    for (const p of pts) {
      if (p.buildingId !== b) continue
      map.set(p.time, (p[field] as number | null) ?? null)
    }
    const isFocus = !!focus
    const focused = focus === b
    return {
      name: b,
      type: 'line' as const,
      smooth: false,              // 保留真实波峰/异常尖峰，不平滑抹掉
      showSymbol: false,
      connectNulls: false,
      data: times.map((t) => map.get(t) ?? null),
      lineStyle: { width: isFocus ? (focused ? 3 : 1.2) : 2 },
      opacity: isFocus ? (focused ? 1 : 0.22) : 1,
      z: focused ? 10 : 2,
      itemStyle: { color: palette[i % palette.length] }
    }
  })

  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      valueFormatter: (v) => fmtNum(v as number) + ' ' + unitCn.value
    },
    legend: { type: 'scroll', top: 4, right: 10, data: shownBuildings.value },
    grid: { left: 60, right: 28, top: 46, bottom: times.length > 60 ? 56 : 40 },
    xAxis: {
      type: 'category',
      data: times,
      boundaryGap: false,
      axisLabel: { formatter: (v: string) => (v.length > 10 ? v.slice(5, 16) : v.slice(5)) }
    },
    yAxis: {
      type: 'value',
      name: unitCn.value,
      axisLabel: { formatter: (v: number) => fmtNum(v, 0) }
    },
    // 点位过多时开启缩放，否则 X 轴标签会糊成一片
    dataZoom: times.length > 60
      ? [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 8 }]
      : undefined,
    series: list
  }
})

const barOption = computed<EChartsOption>(() => {
  const items = [...summary.value]
    .sort((a, b) => (b.electricityKwh || 0) - (a.electricityKwh || 0))
    .slice(0, 15)
    .reverse()
  const top = items.length - 1
  return {
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'shadow' },
      valueFormatter: (v) => fmtNum(v as number) + ' kWh'
    },
    grid: { left: 8, right: 66, top: 12, bottom: 24, containLabel: true },
    xAxis: { type: 'value', axisLabel: { formatter: (v: number) => fmtNum(v, 0) } },
    yAxis: {
      type: 'category', data: items.map((i) => i.buildingId),
      axisLabel: { fontSize: 11, width: 120, overflow: 'truncate' }
    },
    series: [{
      type: 'bar',
      data: items.map((i, idx) => ({
        value: i.electricityKwh || 0,
        itemStyle: { color: idx === top ? '#35866B' : '#2A9D8F', borderRadius: [0, 3, 3, 0] }
      })),
      barMaxWidth: 14,
      label: {
        show: true, position: 'right', fontSize: 10,
        formatter: (p: unknown) => fmtNum((p as { value: number }).value, 1)
      }
    }]
  }
})

// ---------------------------------------------------------------- 表格搜索 + 分页
const filteredSummary = computed(() => {
  const kw = filterKeyword.value.trim().toLowerCase()
  if (!kw) return summary.value
  return summary.value.filter((r) =>
    r.buildingId.toLowerCase().includes(kw) ||
    (r.buildingTypeCn || '').toLowerCase().includes(kw))
})

const pagedSummary = computed(() => {
  if (filteredSummary.value.length <= pageSize) return filteredSummary.value
  const start = (pageNum.value - 1) * pageSize
  return filteredSummary.value.slice(start, start + pageSize)
})

// ---------------------------------------------------------------- 行点击联动
function onRowClick(row: BuildingSummary) {
  focusBuildingId.value = focusBuildingId.value === row.buildingId ? null : row.buildingId
}
function rowClass({ row }: { row: BuildingSummary }) {
  return row.buildingId === focusBuildingId.value ? 'linked-row' : ''
}

// ---------------------------------------------------------------- 明细导出（前端 CSV，不碰后端接口）
function onExportCsv() {
  if (!filteredSummary.value.length) {
    ElMessage.warning('当前没有可导出的明细数据')
    return
  }
  const head = ['建筑编号', '建筑类型', '区间用电量(kWh)', '空调能耗(kWh)', '碳排(kgCO₂)', '平均COP', 'EUI']
  const rows = filteredSummary.value.map((r) => [
    r.buildingId, r.buildingTypeCn || '', r.electricityKwh ?? '', r.hvacKwh ?? '',
    r.carbonKg ?? '', r.copAvg ?? '', r.eui ?? ''
  ])
  const csv = '\ufeff' + [head, ...rows]
    .map((row) => row.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `能耗明细_${rangeText.value.replace(/[~ ]+/g, '-')}.csv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${filteredSummary.value.length} 行明细`)
}

// ---------------------------------------------------------------- 交互
function onSiteChange() {
  // 切换园区后原建筑已不在候选范围，清空避免"选了 A 园区 + B 园区建筑"查出空结果
  buildingIds.value = []
  loadBuildings()
}

function onRangeChange(v: [string, string] | null) {
  if (v && v.length === 2) {
    app.setRange(v[0], v[1])
  }
}

function onGranularityChange(v: string | number | boolean | undefined) {
  if (v === 'hourly' && buildingIds.value.length !== 1) {
    ElMessage.warning('小时粒度需要且仅需要选择 1 栋建筑；查询前请先选定单栋建筑。')
  }
}

function onReset() {
  app.siteId = ''
  app.buildingType = ''
  buildingIds.value = []
  granularity.value = 'daily'
  metric.value = 'electricity'
  onlyAnomaly.value = false
  filterKeyword.value = ''
  focusBuildingId.value = null
  app.setRange('2016-01-01', '2016-12-31')
  range.value = [app.startDate, app.endDate]
  loadBuildings()
  loadAll()
}

// ---------------------------------------------------------------- 数据加载
async function loadBuildings() {
  try {
    buildings.value = await energyApi.buildings({
      siteId: app.siteId || undefined,
      buildingType: app.buildingType || undefined,
      limit: 500
    })
  } catch {
    /* 拦截器已提示 */
  }
}

async function loadAll() {
  // 前置校验：后端对 hourly + 非单栋建筑直接返回 400，这里提前拦截给出可读提示
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择时间范围')
    return
  }
  if (granularity.value === 'hourly' && buildingIds.value.length !== 1) {
    ElMessage.warning('小时粒度数据量过大，请选择且仅选择 1 栋建筑；多建筑对比请改用日或月粒度。')
    return
  }

  loading.value = true
  loadError.value = false
  pageNum.value = 1
  focusBuildingId.value = null
  try {
    app.setRange(range.value[0], range.value[1])
    series.value = await energyApi.query({
      // 建筑多选在后端以逗号分隔的 buildingId 传递（DTO: "多个用逗号分隔"）
      buildingId: buildingIds.value.length ? buildingIds.value.join(',') : undefined,
      siteId: app.siteId || undefined,
      buildingType: app.buildingType || undefined,
      startDate: range.value[0],
      endDate: range.value[1],
      granularity: granularity.value,
      // 统一按 all 取数：KPI 与汇总行需要用电/空调/碳排/COP 全字段，
      // metric 只控制主趋势图画哪条曲线
      metric: 'all',
      onlyAnomaly: onlyAnomaly.value
    })
    if (!series.value?.summary?.length) {
      ElMessage.info('当前条件下没有查询到数据')
    }
  } catch {
    series.value = null
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    const [s, dim] = await Promise.all([
      energyApi.sites(),
      energyApi.dimensions('building_type')
    ])
    sites.value = s
    typeOptions.value = dim.options || []
  } catch {
    /* 忽略 */
  }
  await loadBuildings()

  // 支持从其他页面带建筑进来：/energy?buildingId=xxx
  // （3D 园区的「查看能耗详情」入口使用）
  const preset = route.query.buildingId
  if (typeof preset === 'string' && preset) {
    buildingIds.value = [preset]
  }

  await loadAll()
})
</script>

<style scoped>
/* ── 筛选分组 ── */
.filter-group { margin-bottom: 14px; }
.filter-group:last-of-type { margin-bottom: 0; }
.filter-group-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--carbon-text-dim, #5d6e66);
  margin-bottom: 8px;
}
.filter-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.sel-count { font-size: 12px; }

/* ── KPI 卡：紧凑白卡 + 薄荷绿点缀（与看板一致）── */
.kpi-card {
  background: var(--carbon-panel, #fff);
  border: 1px solid var(--carbon-border, #e3e9e4);
  border-radius: 14px;
  padding: 13px 16px;
  box-shadow: var(--shadow-sm);
  position: relative;
  overflow: hidden;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}
.kpi-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.kpi-card::after {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; height: 2px;
  background: linear-gradient(90deg, var(--carbon-primary, #35866b), rgba(53, 134, 107, 0));
  opacity: 0.35;
}
.kpi-value { font-size: 22px; font-weight: 600; color: var(--carbon-text, #253d35); line-height: 1.2; font-variant-numeric: tabular-nums; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--carbon-text-mute, #7b8b84); margin-left: 4px; }
.kpi-sub { font-size: 11px; color: var(--carbon-text-mute, #7b8b84); margin-top: 5px; }

/* ── 表格工具栏与横向滚动 ── */
.table-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.table-scroll { overflow-x: auto; }

:deep(.el-pagination) { justify-content: flex-end; }
:deep(.el-table .cell) { font-variant-numeric: tabular-nums; }
:deep(.el-table .linked-row) { background: rgba(53, 134, 107, 0.07); }
:deep(.el-table .linked-row:hover > td) { background: rgba(53, 134, 107, 0.1); }
</style>
