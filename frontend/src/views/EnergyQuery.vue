<template>
  <div>
    <!-- 多条件筛选栏 -->
    <div class="page-card mb-12">
      <div class="page-title">多条件能耗查询</div>
      <div class="filter-bar" style="margin-bottom: 0">
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
                   style="width: 260px">
          <el-option v-for="b in buildings" :key="b.buildingId"
                     :label="`${b.buildingId}（${b.buildingTypeCn || '未分类'}）`" :value="b.buildingId" />
        </el-select>

        <span class="text-dim">时间范围</span>
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="开始" end-placeholder="结束" style="width: 240px"
                        @change="onRangeChange" />

        <el-button type="primary" :icon="Search" :loading="loading" @click="loadAll">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>
      </div>

      <div class="filter-bar mt-12" style="margin-bottom: 0">
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

        <el-tag v-if="granularity === 'hourly'" type="warning" effect="plain" size="small">
          小时粒度仅支持单栋建筑
        </el-tag>
      </div>
    </div>

    <!-- KPI 指标卡（由 summary 数组聚合而来） -->
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
      <div class="kpi-card green">
        <div class="kpi-label">总空调能耗</div>
        <div class="kpi-value">{{ fmtNum(totals.hvac) }}<span class="kpi-unit">kWh</span></div>
        <div class="kpi-sub">占用电 {{ hvacRatio }}%</div>
      </div>
      <div class="kpi-card orange">
        <div class="kpi-label">总碳排</div>
        <div class="kpi-value">{{ fmtNum(totals.carbon) }}<span class="kpi-unit">kgCO₂</span></div>
        <div class="kpi-sub">因子 0.5810 kgCO₂/kWh</div>
      </div>
      <div class="kpi-card red">
        <div class="kpi-label">平均 COP</div>
        <div class="kpi-value">{{ copAvg ?? '--' }}</div>
        <div class="kpi-sub">仅含 {{ copCount }} 栋有冷冻水计量的建筑</div>
      </div>
    </div>

    <!-- 主趋势图 -->
    <div class="chart-box mb-12">
      <div class="chart-title">
        {{ metricCn }}趋势
        <span class="src-badge src-measured">实测</span>
        <span v-if="shownBuildings.length > 1" class="text-dim" style="font-weight: 400">
          · 每栋一条曲线（最多 8 栋）
        </span>
      </div>
      <el-alert v-if="trendCapTip" type="info" :closable="false" show-icon class="mb-12"
                :title="trendCapTip" />
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
        <!-- 行数 > 20 时前端分页，避免一次渲染 500 行 -->
        <el-table :data="pagedSummary" size="small" height="320" v-loading="loading"
                  :default-sort="{ prop: 'electricityKwh', order: 'descending' }">
          <el-table-column prop="buildingId" label="建筑编号" min-width="170" show-overflow-tooltip />
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
        <el-pagination v-if="summary.length > pageSize" class="mt-12"
                       layout="total, prev, pager, next" :total="summary.length"
                       :page-size="pageSize" v-model:current-page="pageNum" small background />
      </div>
    </div>

    <el-empty v-if="!loading && !summary.length" description="当前条件下没有查询到数据，请放宽筛选条件" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EChart from '@/components/EChart.vue'
import { energyApi } from '@/api'
import { useAppStore } from '@/stores/app'
import { fmtNum, fmtInt, type EChartsOption } from '@/utils/echarts'
import type {
  BuildingRow, BuildingSummary, DimensionOption, SeriesPoint, SiteRow, TimeSeriesVO
} from '@/types/api'

const app = useAppStore()
const loading = ref(false)
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

// ---------------------------------------------------------------- KPI 聚合
const sumBy = (key: 'electricityKwh' | 'hvacKwh' | 'carbonKg') =>
  summary.value.reduce((s, r) => s + (r[key] || 0), 0)

const totals = computed(() => ({
  electricity: sumBy('electricityKwh'),
  hvac: sumBy('hvacKwh'),
  carbon: sumBy('carbonKg')
}))

const hvacRatio = computed(() => {
  const e = totals.value.electricity
  return e > 0 ? ((totals.value.hvac / e) * 100).toFixed(1) : '--'
})

/** 平均 COP：跳过 null（多数建筑无冷冻水计量，不能按 0 参与平均） */
const copCount = computed(() => summary.value.filter((s) => s.copAvg != null).length)
const copAvg = computed(() => {
  const valid = summary.value.filter((s) => s.copAvg != null) as (BuildingSummary & { copAvg: number })[]
  if (!valid.length) return null
  return +(valid.reduce((s, r) => s + r.copAvg, 0) / valid.length).toFixed(3)
})

// ---------------------------------------------------------------- 图表配置
const palette = ['#2f7dff', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6',
  '#06b6d4', '#ec4899', '#84cc16']

const trendOption = computed<EChartsOption>(() => {
  const pts = series.value?.points || []
  const field = metricField[metric.value] || 'electricityKwh'
  // 多建筑时时间轴取并集并排序，缺失点补 null（折线断开，不画成 0）
  const times = [...new Set(pts.map((p) => p.time))].sort()
  const list = shownBuildings.value.map((b, i) => {
    const map = new Map<string, number | null>()
    for (const p of pts) {
      if (p.buildingId !== b) continue
      map.set(p.time, (p[field] as number | null) ?? null)
    }
    return {
      name: b,
      type: 'line' as const,
      smooth: true,
      showSymbol: false,
      connectNulls: false,
      data: times.map((t) => map.get(t) ?? null),
      lineStyle: { width: 2 },
      itemStyle: { color: palette[i % palette.length] }
    }
  })

  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
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
      name: metric.value === 'carbon' ? 'kgCO₂' : metric.value === 'water' ? 'm³' : 'kWh',
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
      data: items.map((i) => i.electricityKwh || 0),
      barMaxWidth: 14,
      itemStyle: { color: '#2f7dff', borderRadius: [0, 3, 3, 0] },
      label: {
        show: true, position: 'right', fontSize: 10,
        // 形参用 unknown 再断言，避免 ECharts formatter 回调的逆变类型报错
        formatter: (p: unknown) => fmtNum((p as { value: number }).value, 1)
      }
    }]
  }
})

// ---------------------------------------------------------------- 前端分页
const pagedSummary = computed(() => {
  if (summary.value.length <= pageSize) return summary.value
  const start = (pageNum.value - 1) * pageSize
  return summary.value.slice(start, start + pageSize)
})

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
  pageNum.value = 1
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
:deep(.el-pagination) { justify-content: flex-end; }
:deep(.el-table .cell) { font-variant-numeric: tabular-nums; }
</style>
