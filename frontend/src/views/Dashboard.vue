<template>
  <div>
    <!-- 轻量欢迎信息 -->
    <div class="dash-head">
      <div class="dash-head-main">
        <span class="dash-head-title">能源全景监控</span>
      </div>
      <div class="dash-head-sub">
        当前园区 <b>{{ app.siteId || '全部园区' }}</b> · 数据区间
        <b>{{ range[0] }}</b> ~ <b>{{ range[1] }}</b> · 未标注为实时
      </div>
    </div>

    <!-- 查询失败提示 -->
    <el-alert v-if="loadError" type="warning" :closable="false" show-icon class="mb-12"
              title="数据加载失败"
              description="部分数据未能获取，请检查服务状态后点击「刷新」重试。" />

    <!-- 筛选工具条 -->
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
        <el-button type="primary" plain :loading="aiReportLoading" @click="onAiReport">
          AI 解读
        </el-button>
      </div>
    </div>

    <!-- KPI 指标卡：首次加载显示骨架，加载完显示真实值 -->
    <div v-if="firstLoading" class="kpi-row">
      <div v-for="i in 6" :key="i" class="kpi-card kpi-skeleton">
        <el-skeleton animated :rows="2" />
      </div>
    </div>
    <div v-else class="kpi-row">
      <div class="kpi-card">
        <div class="kpi-label">总用电量</div>
        <div class="kpi-value">{{ fmtNum(overview?.electricityKwh) }}<span class="kpi-unit">kWh</span></div>
        <div class="kpi-sub">区间 {{ overview?.timeRange || '--' }}</div>
      </div>

      <div class="kpi-card">
        <div class="kpi-label">
          空调系统能耗
          <el-tooltip placement="bottom" raw-content
                      content="该数值为<b>冷量/热量</b>（热力学能），与电量不是同一口径，<br/>不能直接相除求占比。<br/><br/>BDG2 原始数据中只有部分建筑提供冷/热/蒸汽分项表计，<br/>其余建筑只有电表，因此该合计仅覆盖少数楼栋。">
            <el-icon class="kpi-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
        <div class="kpi-value">{{ fmtNum(overview?.hvacKwh) }}<span class="kpi-unit">kWh</span></div>
        <div class="kpi-sub">
          覆盖 {{ overview?.hvacBuildingCount ?? '--' }} / {{ overview?.buildingCount ?? '--' }} 栋
          <span class="kpi-sub-note">· 冷热量口径</span>
        </div>
      </div>

      <div class="kpi-card">
        <div class="kpi-label">碳排放量</div>
        <div class="kpi-value">{{ fmtNum(overview?.carbonKg) }}<span class="kpi-unit">kgCO₂</span></div>
        <div class="kpi-sub">因子 0.5810 kgCO₂/kWh</div>
      </div>

      <div class="kpi-card">
        <div class="kpi-label">能耗异常率</div>
        <div class="kpi-value">
          {{ anomalyRatePct }}<span class="kpi-unit">%</span>
        </div>
        <div class="kpi-sub">异常 {{ fmtInt(overview?.anomalyHours) }} 小时</div>
      </div>

      <div class="kpi-card">
        <div class="kpi-label">建筑 / 园区</div>
        <div class="kpi-value">{{ overview?.buildingCount ?? '--' }}<span class="kpi-unit">栋</span></div>
        <div class="kpi-sub">园区 {{ overview?.siteCount ?? '--' }} 个</div>
      </div>

      <div class="kpi-card">
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
      <EChart v-if="hasSeries" :option="trendOption" height="330px" :loading="loading" />
      <el-empty v-else description="暂无趋势数据" :image-size="72" />
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
        title="AI 能源洞察"
        :start-date="range[0]"
        :end-date="range[1]"
        :site-id="app.siteId"
        :building-type="app.buildingType"
        @focus-building="onFocusBuilding"
      />
    </div>
  </div>

    <!-- AI 报表解读：narrative 由大模型生成（/api/ai/report） -->
    <el-dialog v-model="aiReportVisible" title="AI 报表解读" width="640px" append-to-body>
      <div v-if="aiReportLoading" class="ai-report-loading">
        <el-skeleton :rows="5" animated />
      </div>
      <template v-else>
        <div class="ai-report-meta">
          <el-tag size="small" :type="aiReportMode === 'llm' ? 'success' : 'info'" effect="plain">
            {{ aiReportMode === 'llm' ? '大模型生成' : '模板生成' }}
          </el-tag>
          <span class="ai-report-range">{{ aiReportRange }}</span>
        </div>
        <p class="ai-report-text">{{ aiReportText || '暂无叙述' }}</p>
      </template>
      <template #footer>
        <el-button @click="aiReportVisible = false">关闭</el-button>
      </template>
    </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Download } from '@element-plus/icons-vue'
import EChart from '@/components/EChart.vue'
import AiInsight from '@/components/AiInsight.vue'
import { energyApi, analyticsApi, aiApi } from '@/api'
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
const loadError = ref(false)
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

/** 首次加载（用于 KPI 骨架屏） */
const firstLoading = computed(() => loading.value && overview.value === null)
/** 趋势图是否有数据 */
const hasSeries = computed(() => (series.value?.points?.length ?? 0) > 0)
/**
 * 能耗异常率百分比。
 * 后端 overview.anomalyRate 是「比率」（0.0448 = 4.48%），
 * 直接拼 "%" 会显示成 0.0448%，这里 ×100 转成百分比。
 */
const anomalyRatePct = computed(() => {
  const r = overview.value?.anomalyRate
  return r != null ? (r * 100).toFixed(2) : '--'
})

const query = () => ({
  startDate: app.startDate,
  endDate: app.endDate,
  siteId: app.siteId || undefined
})

function onRangeChange(v: [string, string] | null) {
  if (v && v.length === 2) {
    app.setRange(v[0], v[1])
    loadAll()
  }
}

// ---------------------------------------------------------------- 图表配色（与浅色设计系统协调）
const TEAL = '#2A9D8F'          // 青绿：用电量主色
const TEAL_DARK = '#35866B'     // 品牌绿：强调
const AMBER = '#E0A84C'         // 琥珀：室外温度

/** 建筑类型占比的环图配色：柔和、可区分 */
const TYPE_COLORS = [
  '#35866B', '#4B9C8F', '#5B8FA8', '#7A93C4', '#9A7FBE', '#C2766A',
  '#D9B65C', '#C2A878', '#8FA88C', '#94A3B8', '#6E9E79', '#B08A5A',
  '#C48FA0', '#7FB69B', '#A78A6E'
]

/** 能耗评级配色（与 3D 园区一致） */
const GRADE_COLORS: Record<string, string> = {
  A: '#4F9D77', B: '#7FB98C', C: '#D3B25C', D: '#D18F5F', E: '#C26B62'
}

// ---------------------------------------------------------------- 图表配置
const baseGrid = { left: 56, right: 24, top: 40, bottom: 44 }

const trendOption = computed<EChartsOption>(() => {
  const pts = series.value?.points || []
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
        lineStyle: { width: 2, color: TEAL },
        itemStyle: { color: TEAL },
        areaStyle: { color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(42, 157, 143, 0.20)' },
            { offset: 1, color: 'rgba(42, 157, 143, 0.02)' }
          ]
        } } },
      { name: '室外温度', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false,
        data: temp, lineStyle: { width: 1.6, type: 'dashed', color: AMBER },
        itemStyle: { color: AMBER } }
    ]
  }
})

const siteOption = computed<EChartsOption>(() => {
  const items = (bySite.value?.items || []).slice(0, 12).reverse()
  const top = items.length - 1   // reverse 后最大值在数组末尾（ECharts 里最上面）
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' },
      valueFormatter: (v: any) => fmtNum(Number(v)) + ' kWh' },
    grid: { left: 70, right: 40, top: 16, bottom: 24 },
    xAxis: { type: 'value', axisLabel: { formatter: (v: number) => fmtNum(v, 0) } },
    yAxis: { type: 'category', data: items.map((i) => i.label) },
    series: [{
      type: 'bar', data: items.map((i, idx) => ({
        value: i.value,
        itemStyle: { color: idx === top ? TEAL_DARK : TEAL, borderRadius: [0, 4, 4, 0] }
      })), barMaxWidth: 16,
      label: { show: true, position: 'right', fontSize: 10,
        formatter: (p: any) => fmtNum(Number(p.value), 1) }
    }]
  }
})

const typeOption = computed<EChartsOption>(() => {
  const items = (byType.value?.items || []).filter((i) => i.value > 0)
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} kWh（{d}%）' },
    legend: { type: 'scroll', orient: 'vertical', right: 6, top: 'center',
              itemHeight: 9, itemWidth: 9, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['42%', '68%'], center: ['36%', '50%'],
      data: items.map((i, idx) => ({
        name: i.label, value: i.value,
        itemStyle: { color: TYPE_COLORS[idx % TYPE_COLORS.length] }
      })),
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 12, fontWeight: 'bold' } }
    }]
  }
})

const tariffOption = computed<EChartsOption>(() => {
  const tb = period.value?.tariffBuckets || []
  const colors: Record<string, string> = {
    '尖峰': '#C26B62', '高峰': '#D18F5F', '平段': '#D9B65C', '低谷': '#4F9D77'
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
        itemStyle: { color: colors[t.name] || TEAL, borderRadius: [4, 4, 0, 0] }
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
        itemStyle: { color: '#D18F5F' } },
      { name: '事件数', type: 'line', smooth: true, showSymbol: false,
        data: t.map((d) => d.eventCount), itemStyle: { color: TEAL } }
    ]
  }
})

const gradeOption = computed<EChartsOption>(() => {
  const order = ['A', 'B', 'C', 'D', 'E']
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
        itemStyle: { color: GRADE_COLORS[g], borderRadius: [4, 4, 0, 0] }
      })),
      label: { show: true, position: 'top', fontSize: 11 }
    }]
  }
})

// ---------------------------------------------------------------- 数据加载
async function loadAll() {
  loading.value = true
  loadError.value = false
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
    loadError.value = true
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

// ── AI 报表解读（叙述由大模型生成）──
const aiReportVisible = ref(false)
const aiReportLoading = ref(false)
const aiReportText = ref('')
const aiReportMode = ref('mock')
const aiReportRange = ref('')

async function onAiReport() {
  aiReportVisible.value = true
  aiReportLoading.value = true
  aiReportText.value = ''
  try {
    const r = await aiApi.report({
      reportType: 'energy_summary',
      startDate: app.startDate,
      endDate: app.endDate,
      siteId: app.siteId || undefined,
      withNarrative: true
    })
    aiReportText.value = r.narrative || ''
    aiReportMode.value = r.mode || 'mock'
    aiReportRange.value = r.timeRange || ''
  } catch {
    aiReportText.value = ''
    ElMessage.error('AI 解读生成失败，请稍后重试')
  } finally {
    aiReportLoading.value = false
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
.ai-report-loading { padding: 8px 0; }
.ai-report-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.ai-report-range { font-size: 12px; color: #5d6e66; }
.ai-report-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.95;
  color: #253d35;
  white-space: pre-wrap;
}
/* ── 页面顶部欢迎信息 ── */
.dash-head {
  margin-bottom: 14px;
}
.dash-head-main {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dash-head-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--carbon-text, #253D35);
  letter-spacing: 0.01em;
}
.dash-head-sub {
  margin-top: 5px;
  font-size: 12.5px;
  color: var(--carbon-text-dim, #5D6E66);
}
.dash-head-sub b {
  color: var(--carbon-text, #253D35);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* ── KPI 卡：白卡 + 雾灰底 + 薄荷绿点缀，去掉高饱和彩色侧边框 ── */
.kpi-card {
  background: var(--carbon-panel, #FFFFFF);
  border: 1px solid var(--carbon-border, #E3E9E4);
  border-radius: 14px;
  padding: 16px 18px;
  box-shadow: var(--shadow-sm);
  position: relative;
  overflow: hidden;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}
.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
/* 顶部一条极淡的薄荷绿点缀线（非高饱和侧边） */
.kpi-card::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, var(--carbon-primary, #35866B), rgba(53, 134, 107, 0));
  opacity: 0.35;
}

.kpi-skeleton { height: 104px; }

.kpi-label {
  font-size: 12px;
  color: var(--carbon-text-dim, #5D6E66);
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.kpi-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--carbon-text, #253D35);
  font-variant-numeric: tabular-nums;
}
.kpi-unit {
  font-size: 12px;
  font-weight: 400;
  color: var(--carbon-text-mute, #7B8B84);
  margin-left: 4px;
}
.kpi-sub {
  font-size: 11px;
  color: var(--carbon-text-mute, #7B8B84);
  margin-top: 6px;
}
.kpi-help {
  margin-left: 2px;
  font-size: 12px;
  color: #A9B4B8;
  cursor: help;
  vertical-align: middle;
}
.kpi-sub-note {
  color: #A9B4B8;
}

.text-danger { color: #C26B62; font-weight: 600; }
</style>
