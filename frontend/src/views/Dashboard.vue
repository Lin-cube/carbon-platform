<template>
  <div>
    <!-- 筛选栏 -->
    <div class="page-card mb-12">
      <div class="filter-bar" style="margin-bottom: 0">
        <span class="text-dim">园区</span>
        <el-select v-model="app.siteId" placeholder="全部园区" clearable style="width: 150px"
                   @change="loadAll">
          <el-option v-for="s in sites" :key="s.siteId" :label="`${s.siteId}（${s.buildingCount}栋）`"
                     :value="s.siteId" />
        </el-select>

        <span class="text-dim">时间范围</span>
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="开始" end-placeholder="结束" style="width: 250px"
                        @change="onRangeChange" />

        <el-radio-group v-model="granularity" size="small" @change="loadSeries">
          <el-radio-button value="daily">日</el-radio-button>
          <el-radio-button value="monthly">月</el-radio-button>
        </el-radio-group>

        <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button :icon="Download" @click="onExport">导出报表</el-button>
      </div>
    </div>

    <!-- KPI 指标卡 -->
    <div class="kpi-row">
      <div class="kpi-card">
        <div class="kpi-label">总用电量</div>
        <div class="kpi-value">{{ fmtNum(overview?.electricityKwh) }}<span class="kpi-unit">kWh</span></div>
        <div class="kpi-sub">区间 {{ overview?.timeRange || '--' }}</div>
      </div>
      <div class="kpi-card green">
        <div class="kpi-label">空调系统能耗</div>
        <div class="kpi-value">{{ fmtNum(overview?.hvacKwh) }}<span class="kpi-unit">kWh</span></div>
        <div class="kpi-sub">占用电 {{ hvacRatio }}%</div>
      </div>
      <div class="kpi-card orange">
        <div class="kpi-label">碳排放量</div>
        <div class="kpi-value">{{ fmtNum(overview?.carbonKg) }}<span class="kpi-unit">kgCO₂</span></div>
        <div class="kpi-sub">因子 0.5810 kgCO₂/kWh</div>
      </div>
      <div class="kpi-card red">
        <div class="kpi-label">能耗异常率</div>
        <div class="kpi-value">{{ overview?.anomalyRate ?? '--' }}<span class="kpi-unit">%</span></div>
        <div class="kpi-sub">异常 {{ fmtInt(overview?.anomalyHours) }} 小时</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">建筑 / 园区</div>
        <div class="kpi-value">{{ overview?.buildingCount ?? '--' }}<span class="kpi-unit">栋</span></div>
        <div class="kpi-sub">园区 {{ overview?.siteCount ?? '--' }} 个</div>
      </div>
      <div class="kpi-card green">
        <div class="kpi-label">平均 COP</div>
        <div class="kpi-value">{{ overview?.copAvg ?? '--' }}</div>
        <div class="kpi-sub">仅含冷冻水计量建筑</div>
      </div>
    </div>

    <!-- 主趋势图 -->
    <div class="chart-box mb-12">
      <div class="chart-title">
        能耗趋势与室外温度
        <span class="src-badge src-measured">实测</span>
      </div>
      <EChart :option="trendOption" height="330px" :loading="loading" />
    </div>

    <div class="grid-2 mb-12">
      <div class="chart-box">
        <div class="chart-title">园区能耗对比 <span class="src-badge src-measured">实测</span></div>
        <EChart :option="siteOption" height="300px" :loading="loading" />
      </div>
      <div class="chart-box">
        <div class="chart-title">建筑类型能耗结构 <span class="src-badge src-measured">实测</span></div>
        <EChart :option="typeOption" height="300px" :loading="loading" />
      </div>
    </div>

    <div class="grid-2 mb-12">
      <div class="chart-box">
        <div class="chart-title">分时用电占比（尖峰平谷） <span class="src-badge src-measured">实测</span></div>
        <EChart :option="tariffOption" height="300px" :loading="loading" />
      </div>
      <div class="chart-box">
        <div class="chart-title">异常事件趋势 <span class="src-badge src-labeled">算法标注</span></div>
        <EChart :option="anomalyOption" height="300px" :loading="loading" />
      </div>
    </div>

    <!-- 能耗评级分布 + 异常建筑 TOP -->
    <div class="grid-2">
      <div class="chart-box">
        <div class="chart-title">建筑能耗评级分布 <span class="src-badge src-derived">推算</span></div>
        <EChart :option="gradeOption" height="280px" :loading="loading" />
      </div>
      <div class="chart-box">
        <div class="chart-title">异常小时数 TOP 建筑 <span class="src-badge src-labeled">算法标注</span></div>
        <el-table :data="anomaly?.topBuildings?.slice(0, 10) || []" size="small" height="280"
                  v-loading="loading">
          <el-table-column type="index" label="#" width="46" />
          <el-table-column prop="buildingId" label="建筑" min-width="150" show-overflow-tooltip />
          <el-table-column prop="buildingTypeCn" label="类型" width="96" />
          <el-table-column prop="anomalyHours" label="异常小时" width="88" align="right" sortable />
          <el-table-column label="峰值偏离" width="92" align="right">
            <template #default="{ row }">
              <span :class="{ 'text-danger': (row.peakDeviationPct ?? 0) > 100 }">
                {{ row.peakDeviationPct ?? '--' }}%
              </span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <AiInsight
        scope="dashboard"
        title="AI 看板解读"
        :start-date="range[0]"
        :end-date="range[1]"
        :site-id="app.siteId"
        :building-type="app.buildingType"
        @focus-building="onFocusBuilding"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Download } from '@element-plus/icons-vue'
import EChart from '@/components/EChart.vue'
import AiInsight from '@/components/AiInsight.vue'
import { energyApi, analyticsApi, systemApi } from '@/api'
import { request } from '@/api/request'
import { useAppStore } from '@/stores/app'
import { fmtNum, fmtInt, type EChartsOption } from '@/utils/echarts'
import type {
  OverviewVO, TimeSeriesVO, BreakdownVO, SiteRow, PeriodSummaryVO, AnomalyAnalysisVO
} from '@/types/api'

const app = useAppStore()
const router = useRouter()

/** 点击 AI 解读里的异常建筑 → 跳到异常诊断页并自动诊断该建筑 */
function onFocusBuilding(buildingId: string) {
  router.push({ path: '/anomaly', query: { buildingId } })
}
const loading = ref(false)
const sites = ref<SiteRow[]>([])
const overview = ref<OverviewVO | null>(null)
const series = ref<TimeSeriesVO | null>(null)
const bySite = ref<BreakdownVO | null>(null)
const byType = ref<BreakdownVO | null>(null)
const period = ref<PeriodSummaryVO | null>(null)
const anomaly = ref<AnomalyAnalysisVO | null>(null)
const granularity = ref<'daily' | 'monthly'>('daily')
const range = ref<[string, string]>([app.startDate, app.endDate])
const gradeDist = ref<{ grade: string; count: number }[]>([])




const query = () => ({
  startDate: app.startDate,
  endDate: app.endDate,
  siteId: app.siteId || undefined
})

const hvacRatio = computed(() => {
  const e = overview.value?.electricityKwh || 0
  const h = overview.value?.hvacKwh || 0
  return e > 0 ? ((h / e) * 100).toFixed(1) : '--'
})

function onRangeChange(v: [string, string] | null) {
  if (v && v.length === 2) {
    app.setRange(v[0], v[1])
    loadAll()
  }
}

// ---------------------------------------------------------------- 图表配置
const baseGrid = { left: 56, right: 24, top: 40, bottom: 44 }

const trendOption = computed<EChartsOption>(() => {
  const pts = series.value?.points || []
  // 按时间聚合（多建筑求和）
  const map = new Map<string, { kwh: number; temp: number; n: number }>()
  for (const p of pts) {
    const cur = map.get(p.time) || { kwh: 0, temp: 0, n: 0 }
    cur.kwh += p.electricityKwh || 0
    cur.temp += p.airTemperatureC || 0
    cur.n += 1
    map.set(p.time, cur)
  }
  const times = [...map.keys()].sort()
  const kwh = times.map((t) => +(map.get(t)!.kwh).toFixed(1))
  const temp = times.map((t) => +(map.get(t)!.temp / Math.max(map.get(t)!.n, 1)).toFixed(1))

  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { data: ['用电量', '室外温度'], top: 4, right: 10 },
    grid: baseGrid,
    xAxis: { type: 'category', data: times, boundaryGap: false,
      axisLabel: { formatter: (v: string) => v.length > 7 ? v.slice(5) : v } },
    yAxis: [
      { type: 'value', name: 'kWh', axisLabel: { formatter: (v: number) => fmtNum(v, 0) } },
      { type: 'value', name: '℃', splitLine: { show: false } }
    ],
    dataZoom: times.length > 60
      ? [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 8 }]
      : undefined,
    series: [
      { name: '用电量', type: 'line', smooth: true, showSymbol: false, data: kwh,
        areaStyle: { opacity: 0.18 }, lineStyle: { width: 2 }, itemStyle: { color: '#2f7dff' } },
      { name: '室外温度', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false,
        data: temp, lineStyle: { width: 1.6, type: 'dashed' }, itemStyle: { color: '#f59e0b' } }
    ]
  }
})

const siteOption = computed<EChartsOption>(() => {
  const items = (bySite.value?.items || []).slice(0, 12).reverse()
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' },
      valueFormatter: (v: any) => fmtNum(Number(v)) + ' kWh' },
    grid: { left: 70, right: 40, top: 16, bottom: 24 },
    xAxis: { type: 'value', axisLabel: { formatter: (v: number) => fmtNum(v, 0) } },
    yAxis: { type: 'category', data: items.map((i) => i.label) },
    series: [{
      type: 'bar', data: items.map((i) => i.value), barMaxWidth: 16,
      itemStyle: {
        color: '#2f7dff', borderRadius: [0, 3, 3, 0],
        // 最高值高亮
        opacity: 1
      },
      label: { show: true, position: 'right', fontSize: 10,
        formatter: (p: any) => fmtNum(Number(p.value), 1) }
    }]
  }
})

const typeOption = computed<EChartsOption>(() => {
  const items = (byType.value?.items || []).filter((i) => i.value > 0)
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} kWh（{d}%）' },
    legend: { type: 'scroll', orient: 'vertical', right: 6, top: 'center', itemHeight: 9 },
    series: [{
      type: 'pie', radius: ['42%', '68%'], center: ['36%', '50%'],
      data: items.map((i) => ({ name: i.label, value: i.value })),
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 12, fontWeight: 'bold' } }
    }]
  }
})

const tariffOption = computed<EChartsOption>(() => {
  const tb = period.value?.tariffBuckets || []
  const colors: Record<string, string> = {
    '尖峰': '#ef4444', '高峰': '#f97316', '平段': '#eab308', '低谷': '#22c55e'
  }
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' },
      valueFormatter: (v: any) => fmtNum(Number(v)) + ' kWh' },
    grid: { left: 60, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: tb.map((t) => t.name) },
    yAxis: { type: 'value', axisLabel: { formatter: (v: number) => fmtNum(v, 0) } },
    series: [{
      type: 'bar', barMaxWidth: 46,
      data: tb.map((t) => ({
        value: t.electricityKwh,
        itemStyle: { color: colors[t.name] || '#2f7dff', borderRadius: [4, 4, 0, 0] }
      })),
      label: { show: true, position: 'top', fontSize: 11,
        formatter: (p: any) => `${tb[p.dataIndex]?.percentage ?? 0}%` }
    }]
  }
})

const anomalyOption = computed<EChartsOption>(() => {
  const t = anomaly.value?.trend || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['异常小时数', '事件数'], top: 2, right: 8 },
    grid: baseGrid,
    xAxis: { type: 'category', data: t.map((d) => d.date),
      axisLabel: { formatter: (v: string) => v.slice(5) } },
    yAxis: { type: 'value' },
    dataZoom: t.length > 60 ? [{ type: 'inside' }] : undefined,
    series: [
      { name: '异常小时数', type: 'bar', stack: 'a', data: t.map((d) => d.anomalyHours),
        itemStyle: { color: '#f97316' } },
      { name: '事件数', type: 'line', smooth: true, showSymbol: false,
        data: t.map((d) => d.eventCount), itemStyle: { color: '#2f7dff' } }
    ]
  }
})

const gradeOption = computed<EChartsOption>(() => {
  const order = ['A', 'B', 'C', 'D', 'E']
  const colors: Record<string, string> = {
    A: '#22c55e', B: '#84cc16', C: '#eab308', D: '#f97316', E: '#ef4444'
  }
  const map = new Map(gradeDist.value.map((g) => [g.grade, g.count]))
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 50, right: 20, top: 24, bottom: 30 },
    xAxis: { type: 'category', data: order },
    yAxis: { type: 'value', name: '栋' },
    series: [{
      type: 'bar', barMaxWidth: 40,
      data: order.map((g) => ({
        value: map.get(g) || 0,
        itemStyle: { color: colors[g], borderRadius: [4, 4, 0, 0] }
      })),
      label: { show: true, position: 'top', fontSize: 11 }
    }]
  }
})

// ---------------------------------------------------------------- 数据加载
async function loadAll() {
  loading.value = true
  try {
    const q = query()
    const [ov, st, ps, an] = await Promise.all([
      energyApi.overview({ ...q, granularity: granularity.value }),
      energyApi.query({ ...q, granularity: granularity.value }),
      analyticsApi.periodSummary({ periodType: 'month', ...q }),
      analyticsApi.anomaly(q)
    ])
    overview.value = ov
    series.value = st
    period.value = ps
    anomaly.value = an

    const [bs, bt] = await Promise.all([
      energyApi.breakdown({ ...q, metric: 'electricity' }, 'site'),
      energyApi.breakdown({ ...q, metric: 'electricity' }, 'building_type')
    ])
    bySite.value = bs
    byType.value = bt
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function loadSeries() {
  loading.value = true
  try {
    series.value = await energyApi.query({ ...query(), granularity: granularity.value })
  } finally {
    loading.value = false
  }
}

async function onExport() {
  try {
    await analyticsApi.exportReport(
      { startDate: app.startDate, endDate: app.endDate, siteId: app.siteId || undefined },
      `碳能智控_能耗报表_${app.startDate}_${app.endDate}.xlsx`
    )
    ElMessage.success('报表已开始下载')
  } catch {
    ElMessage.error('报表导出失败')
  }
}

onMounted(async () => {
  try {
    const [s, buildings] = await Promise.all([
      energyApi.sites(),
      energyApi.buildings({ limit: 500 })
    ])
    sites.value = s
    // 能耗评级分布（从前 500 栋统计；完整分布可由后端提供专门接口）
    const m = new Map<string, number>()
    for (const b of buildings) {
      if (!b.energyGrade || b.energyGrade === '未评级') continue
      m.set(b.energyGrade, (m.get(b.energyGrade) || 0) + 1)
    }
    gradeDist.value = [...m.entries()].map(([grade, count]) => ({ grade, count }))
  } catch { /* 忽略 */ }
  await loadAll()
})
</script>

<style scoped>








.text-danger { color: #ef4444; font-weight: 600; }
</style>
