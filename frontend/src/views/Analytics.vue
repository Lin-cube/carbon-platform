<template>
  <div>
    <!-- 全局筛选：三个页签共用园区与时间范围 -->
    <div class="page-card mb-12">
      <div class="filter-bar" style="margin-bottom: 0">
        <span class="text-dim">园区</span>
        <el-select v-model="app.siteId" placeholder="全部园区" clearable style="width: 150px"
                   @change="reloadActive">
          <el-option v-for="s in sites" :key="s.siteId" :label="`${s.siteId}（${s.buildingCount}栋）`"
                      :value="s.siteId" />
        </el-select>

        <span class="text-dim">时间范围</span>
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="开始" end-placeholder="结束" style="width: 240px"
                        @change="onRangeChange" />

        <el-button type="primary" :icon="Refresh" :loading="loading" @click="reloadActive">刷新</el-button>
        <el-button :icon="Download" @click="onExport">导出报表</el-button>

        <span class="text-dim" style="margin-left: auto">
          历史数据 · {{ app.startDate }} ~ {{ app.endDate }}
        </span>
      </div>
    </div>

    <!-- 分段式导航 -->
    <div class="seg-nav page-card">
      <button v-for="t in tabList" :key="t.name" type="button"
              class="seg-item" :class="{ active: activeTab === t.name }"
              @click="switchTab(t.name)">
        {{ t.label }}
      </button>
    </div>

    <!-- ============================================================ 时段汇总 -->
    <section v-show="activeTab === 'period'" class="mt-12">
      <div class="filter-bar">
        <span class="text-dim">统计周期</span>
        <el-radio-group v-model="periodType" size="small" @change="loadPeriod">
          <el-radio-button value="year">年</el-radio-button>
          <el-radio-button value="quarter">季</el-radio-button>
          <el-radio-button value="month">月</el-radio-button>
          <el-radio-button value="week">周</el-radio-button>
          <el-radio-button value="day">日</el-radio-button>
        </el-radio-group>
        <span class="text-dim">共 {{ period?.buckets?.length || 0 }} 个时段</span>
      </div>

      <div class="kpi-row">
        <div class="kpi-card">
          <div class="kpi-label">区间总用电量</div>
          <div class="kpi-value">{{ fmtNum(period?.total) }}<span class="kpi-unit">kWh</span></div>
          <div class="kpi-sub">时段均值 {{ fmtNum(period?.average) }} kWh</div>
        </div>
        <div class="kpi-card risk">
          <div class="kpi-label">峰值</div>
          <div class="kpi-value">{{ fmtNum(period?.peak) }}<span class="kpi-unit">kWh</span></div>
          <div class="kpi-sub">出现在 {{ period?.peakLabel || '--' }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">谷值</div>
          <div class="kpi-value">{{ fmtNum(period?.valley) }}<span class="kpi-unit">kWh</span></div>
          <div class="kpi-sub">出现在 {{ period?.valleyLabel || '--' }}</div>
        </div>
        <div class="kpi-card risk">
          <div class="kpi-label">峰谷差率</div>
          <div class="kpi-value">{{ period?.peakValleyRatio ?? '--' }}<span class="kpi-unit">%</span></div>
          <div class="kpi-sub">（峰-谷）/ 峰 × 100%</div>
        </div>
      </div>

      <div class="chart-box mb-12">
        <div class="chart-title">
          分时段用电量与室外温度 <span class="src-badge src-measured">实测</span>
        </div>
        <EChart :option="periodOption" height="320px" :loading="loading" />
      </div>

      <el-table :data="period?.buckets || []" size="small" max-height="300" v-loading="loading"
                class="mb-12" :default-sort="{ prop: 'label', order: 'ascending' }">
        <el-table-column prop="label" label="时段" width="110" sortable />
        <el-table-column prop="electricityKwh" label="用电量(kWh)" min-width="130" align="right" sortable>
          <template #default="{ row }">{{ fmtNum(row.electricityKwh) }}</template>
        </el-table-column>
        <el-table-column prop="hvacKwh" label="空调能耗(kWh)" min-width="130" align="right" sortable>
          <template #default="{ row }">{{ fmtNum(row.hvacKwh) }}</template>
        </el-table-column>
        <el-table-column prop="carbonKg" label="碳排(kg)" min-width="118" align="right" sortable>
          <template #default="{ row }">{{ fmtNum(row.carbonKg) }}</template>
        </el-table-column>
        <el-table-column prop="copAvg" label="平均COP" width="100" align="right" sortable>
          <template #default="{ row }">{{ row.copAvg ?? '--' }}</template>
        </el-table-column>
        <el-table-column prop="airTempAvg" label="平均气温(℃)" width="120" align="right" sortable>
          <template #default="{ row }">{{ row.airTempAvg ?? '--' }}</template>
        </el-table-column>
        <el-table-column prop="anomalyHours" label="异常小时" width="100" align="right" sortable />
        <el-table-column prop="momChange" label="环比(%)" width="104" align="right" sortable>
          <template #default="{ row }">
            <span :style="{ color: (row.momChange ?? 0) > 0 ? '#c26b62' : '#4f9d77' }">
              {{ row.momChange == null ? '--' : (row.momChange > 0 ? '+' : '') + row.momChange }}
            </span>
          </template>
        </el-table-column>
      </el-table>

      <div class="grid-2">
        <div class="chart-box">
          <div class="chart-title">
            尖峰平谷电量结构 <span class="src-badge src-measured">实测</span>
          </div>
          <EChart :option="tariffOption" height="280px" :loading="loading" />
        </div>
        <div class="chart-box">
          <div class="chart-title">分时电价时段明细</div>
          <el-table :data="period?.tariffBuckets || []" size="small" max-height="280" v-loading="loading">
            <el-table-column prop="name" label="时段类型" width="96" />
            <el-table-column prop="hours" label="对应钟点" min-width="140" />
            <el-table-column prop="electricityKwh" label="电量(kWh)" min-width="126" align="right">
              <template #default="{ row }">{{ fmtNum(row.electricityKwh) }}</template>
            </el-table-column>
            <el-table-column prop="percentage" label="占比(%)" width="98" align="right" />
            <el-table-column prop="avgHourlyKwh" label="小时均值" width="104" align="right">
              <template #default="{ row }">{{ fmtNum(row.avgHourlyKwh) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </section>

    <!-- ============================================================ COP 计算 -->
    <section v-show="activeTab === 'cop'" class="mt-12">
      <div class="filter-bar">
        <span class="text-dim">建筑（必选）</span>
        <el-select v-model="copBuildingId" filterable placeholder="请选择建筑" style="width: 300px"
                   @change="loadCop">
          <el-option v-for="b in buildings" :key="b.buildingId"
                      :label="`${b.buildingId}（${b.buildingTypeCn || '未分类'}）`" :value="b.buildingId" />
        </el-select>
        <el-button type="primary" :icon="Search" :loading="loading" :disabled="!copBuildingId"
                   @click="loadCop">计算 COP</el-button>
        <span class="text-dim">后端要求必须指定单栋建筑</span>
      </div>

      <!-- 无 COP 数据的建筑占多数：数据现状而非故障 -->
      <el-alert v-if="cop && cop.copAvg == null" type="info" :closable="false" show-icon
                class="mb-12" title="该建筑在本区间没有可用的冷冻水计量，无法计算 COP">
        <div>{{ cop.gradeReason || '查询区间内无有效供回水/制冷量数据。' }}</div>
        <div class="text-dim mt-12" style="font-size: 12px">
          说明：BDG2 数据集中只有少数园区安装了可用的冷冻水表计，18 个园区里约 5 个能算出 COP，
          其余建筑均会返回"无数据"——这是数据采集现状，属于预期结果，不代表系统异常。
          可改用"Hog_office_Sydney"等有冷水计量的建筑查看完整 COP 分析。
        </div>
      </el-alert>

      <div class="kpi-row">
        <!-- 平均 COP：视觉重点 -->
        <div class="kpi-card cop-hero">
          <div class="kpi-label">平均 COP</div>
          <div class="kpi-value hero">{{ cop?.copAvg ?? '--' }}</div>
          <div class="kpi-sub">{{ cop?.buildingId || '未选择建筑' }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">最高 COP</div>
          <div class="kpi-value">{{ cop?.copMax ?? '--' }}</div>
          <div class="kpi-sub">区间内小时最大值</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">最低 COP</div>
          <div class="kpi-value">{{ cop?.copMin ?? '--' }}</div>
          <div class="kpi-sub">区间内小时最小值</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">标准差</div>
          <div class="kpi-value">{{ cop?.copStd ?? '--' }}</div>
          <div class="kpi-sub">越小说明运行越稳定</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">系统能效比</div>
          <div class="kpi-value">{{ cop?.systemCop ?? '--' }}</div>
          <div class="kpi-sub">制冷量 / 空调总电耗</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">平均供回水温差</div>
          <div class="kpi-value">{{ cop?.deltaTAvg ?? '--' }}<span class="kpi-unit">℃</span></div>
          <div class="kpi-sub">供水 {{ cop?.supplyTempAvg ?? '--' }} / 回水 {{ cop?.returnTempAvg ?? '--' }} ℃</div>
        </div>
      </div>

      <el-alert v-if="cop && cop.copGrade" :type="gradeAlertType(cop.copGrade)" :closable="false"
                show-icon class="mb-12" :title="`能效评级：${cop.copGrade}`"
                :description="cop.gradeReason || ''" />

      <div class="grid-2">
        <div class="chart-box">
          <div class="chart-title">
            COP 随室外温度变化 <span class="src-badge src-simulated">仿真</span>
          </div>
          <EChart :option="copTempOption" height="300px" :loading="loading" />
        </div>
        <div class="chart-box">
          <div class="chart-title">
            COP 分布直方图 <span class="src-badge src-simulated">仿真</span>
          </div>
          <EChart :option="copDistOption" height="300px" :loading="loading" />
        </div>
      </div>

      <el-empty v-if="cop && cop.copAvg == null" class="mt-12"
                description="该建筑无可计算的 COP 数据（详见上方说明）" />
      <el-empty v-else-if="!copBuildingId" class="mt-12" description="请选择一栋建筑后计算 COP" />
    </section>

    <!-- ============================================================ 异常分析 -->
    <section v-show="activeTab === 'anomaly'" class="mt-12">
      <!-- 能耗异常与数据质量缺陷是两个刻意分开的概念，避免混淆 -->
      <el-alert type="warning" :closable="false" show-icon class="mb-12"
                title="「能耗异常」与「数据质量缺陷」是两个独立概念，不可相加">
        <div style="font-size: 12px; line-height: 1.7">
          <b>能耗异常</b>：数据本身可信，但用能显著高于同期基线（算法标注，需要运维排查）；<br />
          <b>数据质量缺陷</b>：表计缺失、停抄或明显错误导致的无效小时，属于采集问题，不参与能效评价。<br />
          异常率的分母是<b>评估小时数</b>（有效数据小时），数据质量缺陷小时已从评估基数中剔除。
        </div>
      </el-alert>

      <div class="kpi-row">
        <div class="kpi-card">
          <div class="kpi-label">评估小时数</div>
          <div class="kpi-value">{{ fmtInt(anomaly?.evaluatedHours) }}<span class="kpi-unit">h</span></div>
          <div class="kpi-sub">有效数据小时（已剔除缺陷小时）</div>
        </div>
        <div class="kpi-card risk">
          <div class="kpi-label">能耗异常小时数</div>
          <div class="kpi-value">{{ fmtInt(anomaly?.anomalyHours) }}<span class="kpi-unit">h</span></div>
          <div class="kpi-sub">用能显著高于基线</div>
        </div>
        <div class="kpi-card risk">
          <div class="kpi-label">异常率</div>
          <div class="kpi-value">{{ anomaly?.anomalyRate ?? '--' }}<span class="kpi-unit">%</span></div>
          <div class="kpi-sub">异常小时 / 评估小时</div>
        </div>
        <div class="kpi-card risk">
          <div class="kpi-label">数据质量缺陷小时</div>
          <div class="kpi-value">{{ fmtInt(anomaly?.qualityHours) }}<span class="kpi-unit">h</span></div>
          <div class="kpi-sub">占比 {{ anomaly?.qualityRate ?? '--' }}%（采集问题）</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">异常事件数</div>
          <div class="kpi-value">{{ fmtInt(anomaly?.eventCount) }}<span class="kpi-unit">个</span></div>
          <div class="kpi-sub">连续异常小时聚合而成</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">事件平均 / 最长持续</div>
          <div class="kpi-value">{{ anomaly?.avgDurationHours ?? '--' }}<span class="kpi-unit">h</span></div>
          <div class="kpi-sub">最长 {{ anomaly?.maxDurationHours ?? '--' }} 小时</div>
        </div>
      </div>

      <div class="grid-2 mb-12">
        <div class="chart-box">
          <div class="chart-title">
            异常类型构成 <span class="src-badge src-labeled">算法标注</span>
          </div>
          <EChart v-if="(anomaly?.byType || []).length" :option="byTypeOption" height="280px" :loading="loading" />
          <el-empty v-else description="暂无异常类型数据" :image-size="72" />
        </div>
        <div class="chart-box">
          <div class="chart-title">
            异常小时与事件数趋势 <span class="src-badge src-labeled">算法标注</span>
          </div>
          <EChart v-if="(anomaly?.trend || []).length" :option="anomalyTrendOption" height="280px" :loading="loading" />
          <el-empty v-else description="暂无趋势数据" :image-size="72" />
        </div>
      </div>

      <div class="chart-box">
        <div class="chart-title">
          异常小时数 TOP 建筑 <span class="src-badge src-labeled">算法标注</span>
        </div>
        <el-table :data="anomaly?.topBuildings || []" size="small" max-height="380" v-loading="loading">
          <el-table-column type="index" label="#" width="46" />
          <el-table-column prop="buildingId" label="建筑编号" min-width="180" show-overflow-tooltip />
          <el-table-column prop="siteId" label="园区" width="94" />
          <el-table-column prop="buildingTypeCn" label="建筑类型" width="118" />
          <el-table-column prop="anomalyHours" label="异常小时" width="102" align="right" sortable />
          <el-table-column prop="anomalyRate" label="异常率(%)" width="106" align="right" sortable>
            <template #default="{ row }">{{ ratioToPct(row.anomalyRate) }}</template>
          </el-table-column>
          <el-table-column prop="peakDeviationPct" label="峰值偏离(%)" width="118" align="right" sortable>
            <template #default="{ row }">
              <span :class="{ 'dev-high': (row.peakDeviationPct ?? 0) > 100 }">
                {{ row.peakDeviationPct ?? '--' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="dominantType" label="主要异常类型" width="126" />
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="goDiagnose(row)">诊断</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!(anomaly?.topBuildings || []).length && !loading"
                  description="暂无异常建筑数据" :image-size="72" />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Download, Search } from '@element-plus/icons-vue'
import EChart from '@/components/EChart.vue'
import { analyticsApi, energyApi } from '@/api'
import { useDataVersionRefresh } from '@/composables/useDataVersionRefresh'
import { useAppStore } from '@/stores/app'
import { fmtNum, fmtInt, type EChartsOption } from '@/utils/echarts'
import type {
  AnomalyAnalysisVO, BuildingRow, CopAnalysisVO, PeriodSummaryVO, SiteRow
} from '@/types/api'

const app = useAppStore()
const router = useRouter()
const loading = ref(false)
const activeTab = ref('period')
const sites = ref<SiteRow[]>([])
const buildings = ref<BuildingRow[]>([])
const range = ref<[string, string]>([app.startDate, app.endDate])

const periodType = ref<'year' | 'quarter' | 'month' | 'week' | 'day'>('month')
const copBuildingId = ref('')

const period = ref<PeriodSummaryVO | null>(null)
const cop = ref<CopAnalysisVO | null>(null)
const anomaly = ref<AnomalyAnalysisVO | null>(null)

const tabList = [
  { name: 'period', label: '时段汇总' },
  { name: 'cop', label: 'COP 计算' },
  { name: 'anomaly', label: '异常分析' }
]

/**
 * 后端 selectAnomalyTopBuildings 返回的 anomalyRate 是"占比小数"（SUM(duration)/8760），
 * 而顶层 anomalyRate 是百分数（4.74 表示 4.74%）。这里统一按百分数展示。
 */
function ratioToPct(v: number | null | undefined): string {
  if (v == null) return '--'
  return (v * 100).toFixed(2)
}

function gradeAlertType(grade: string | null | undefined) {
  switch (grade) {
    case '优秀':
    case '良好': return 'success' as const
    case '一般': return 'warning' as const
    case '偏低': return 'error' as const
    default: return 'info' as const
  }
}

/** 异常项跳转到现有诊断页 */
function goDiagnose(row: { buildingId: string }) {
  router.push({ path: '/anomaly', query: { buildingId: row.buildingId } })
}

// ---------------------------------------------------------------- 时段汇总
const periodOption = computed<EChartsOption>(() => {
  const bs = period.value?.buckets || []
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (p: unknown) => {
        const i = (p as { dataIndex?: number }[])?.[0]?.dataIndex ?? 0
        const b = bs[i]
        if (!b) return ''
        const mom = b.momChange == null ? '--' : `${b.momChange > 0 ? '+' : ''}${b.momChange}%`
        return [
          `<b>${b.label}</b>`,
          `用电量：${fmtNum(b.electricityKwh)} kWh`,
          `空调能耗：${fmtNum(b.hvacKwh)} kWh`,
          `平均气温：${b.airTempAvg ?? '--'} ℃`,
          `平均 COP：${b.copAvg ?? '--'}`,
          `异常小时：${fmtInt(b.anomalyHours)} h`,
          `环比：${mom}`
        ].join('<br/>')
      }
    },
    legend: { data: ['用电量', '平均室外温度'], top: 4, right: 10 },
    grid: { left: 62, right: 56, top: 42, bottom: 44 },
    xAxis: {
      type: 'category',
      data: bs.map((b) => b.label),
      axisLabel: { rotate: bs.length > 16 ? 40 : 0, fontSize: 11 }
    },
    yAxis: [
      { type: 'value', name: 'kWh', axisLabel: { formatter: (v: number) => fmtNum(v, 0) } },
      { type: 'value', name: '℃', splitLine: { show: false } }
    ],
    dataZoom: bs.length > 24 ? [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 6 }] : undefined,
    series: [
      {
        name: '用电量', type: 'bar', barMaxWidth: 34,
        data: bs.map((b) => b.electricityKwh),
        itemStyle: { color: '#2A9D8F', borderRadius: [3, 3, 0, 0] }
      },
      {
        name: '平均室外温度', type: 'line', yAxisIndex: 1, smooth: false,
        showSymbol: false, data: bs.map((b) => b.airTempAvg),
        lineStyle: { width: 1.8, type: 'dashed' }, itemStyle: { color: '#E0A84C' }
      }
    ]
  }
})

const tariffOption = computed<EChartsOption>(() => {
  const tb = period.value?.tariffBuckets || []
  const colors: Record<string, string> = {
    尖峰: '#C26B62', 高峰: '#D18F5F', 平段: '#D9B65C', 低谷: '#4F9D77'
  }
  return {
    tooltip: {
      trigger: 'item',
      formatter: (p: unknown) => {
        const it = p as { name: string; value: number; percent: number; dataIndex: number }
        const t = tb[it.dataIndex]
        return `${it.name}<br/>电量：${fmtNum(it.value)} kWh<br/>占比：${it.percent}%` +
          `<br/>钟点：${t?.hours || '--'}<br/>小时均值：${fmtNum(t?.avgHourlyKwh)} kWh`
      }
    },
    legend: { orient: 'vertical', right: 8, top: 'center', itemHeight: 10 },
    series: [{
      type: 'pie', radius: ['42%', '68%'], center: ['38%', '50%'],
      data: tb.map((t) => ({
        name: t.name, value: t.electricityKwh || 0,
        itemStyle: { color: colors[t.name] || '#2A9D8F' }
      })),
      label: { formatter: '{b}\n{d}%', fontSize: 11 },
      emphasis: { label: { show: true, fontSize: 13, fontWeight: 'bold' } }
    }]
  }
})

// ---------------------------------------------------------------- COP
const copTempOption = computed<EChartsOption>(() => {
  const rows = cop.value?.tempRelations || []
  return {
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'shadow' },
      formatter: (p: unknown) => {
        const i = (p as { dataIndex?: number }[])?.[0]?.dataIndex ?? 0
        const r = rows[i]
        if (!r) return ''
        return `${r.tempRange}<br/>平均 COP：${r.copAvg ?? '--'}<br/>样本小时：${fmtInt(r.hours)} h`
      }
    },
    grid: { left: 52, right: 22, top: 26, bottom: 34 },
    xAxis: { type: 'category', data: rows.map((r) => r.tempRange) },
    yAxis: { type: 'value', name: 'COP', min: 0 },
    series: [{
      type: 'bar', barMaxWidth: 40,
      data: rows.map((r) => ({
        value: r.copAvg,
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: (r.copAvg ?? 0) < 3 ? '#C26B62' : (r.copAvg ?? 0) < 3.8 ? '#D9B65C' : '#4F9D77'
        }
      })),
      label: {
        show: true, position: 'top', fontSize: 11,
        formatter: (p: unknown) =>
          ((p as { value: number | null }).value == null ? '--' : String((p as { value: number }).value))
      }
    }]
  }
})

const copDistOption = computed<EChartsOption>(() => {
  const rows = cop.value?.distribution || []
  return {
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'shadow' },
      formatter: (p: unknown) => {
        const i = (p as { dataIndex?: number }[])?.[0]?.dataIndex ?? 0
        const r = rows[i]
        if (!r) return ''
        return `COP ${r.range}<br/>小时数：${fmtInt(r.hours)} h<br/>占比：${r.percentage ?? '--'}%`
      }
    },
    grid: { left: 52, right: 22, top: 26, bottom: 34 },
    xAxis: { type: 'category', data: rows.map((r) => r.range) },
    yAxis: { type: 'value', name: '小时', axisLabel: { formatter: (v: number) => fmtInt(v) } },
    series: [{
      type: 'bar', barMaxWidth: 40,
      data: rows.map((r) => r.hours),
      itemStyle: { color: '#35866B', borderRadius: [4, 4, 0, 0] },
      label: {
        show: true, position: 'top', fontSize: 11,
        formatter: (p: unknown) => `${rows[(p as { dataIndex: number }).dataIndex]?.percentage ?? 0}%`
      }
    }]
  }
})

// ---------------------------------------------------------------- 异常分析
const byTypeOption = computed<EChartsOption>(() => {
  const rows = anomaly.value?.byType || []
  // 灰绿 / 琥珀 / 珊瑚红：保留风险语义的低饱和配色
  const colors = ['#C26B62', '#D18F5F', '#D9B65C', '#8FA88C', '#7A93C4']
  return {
    tooltip: {
      trigger: 'item',
      formatter: (p: unknown) => {
        const it = p as { name: string; value: number; percent: number; dataIndex: number }
        const r = rows[it.dataIndex]
        return `${it.name}<br/>小时数：${fmtInt(it.value)} h（${it.percent}%）` +
          `<br/><span style="font-size:11px">${r?.description || ''}</span>`
      }
    },
    legend: { orient: 'vertical', right: 8, top: 'center', itemHeight: 10 },
    series: [{
      type: 'pie', radius: ['42%', '68%'], center: ['38%', '50%'],
      data: rows.map((r, i) => ({
        name: r.type, value: r.count,
        itemStyle: { color: colors[i % colors.length] }
      })),
      label: { formatter: '{b}\n{d}%', fontSize: 11 },
      emphasis: { label: { show: true, fontSize: 13, fontWeight: 'bold' } }
    }]
  }
})

const anomalyTrendOption = computed<EChartsOption>(() => {
  const t = anomaly.value?.trend || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['异常小时数', '事件数'], top: 2, right: 8 },
    grid: { left: 56, right: 24, top: 36, bottom: t.length > 60 ? 52 : 34 },
    xAxis: {
      type: 'category', data: t.map((d) => d.date),
      axisLabel: { formatter: (v: string) => v.slice(5) }
    },
    yAxis: { type: 'value', name: 'h / 个' },
    dataZoom: t.length > 60 ? [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 6 }] : undefined,
    series: [
      {
        name: '异常小时数', type: 'bar', barMaxWidth: 14,
        data: t.map((d) => d.anomalyHours), itemStyle: { color: '#D18F5F' }
      },
      {
        name: '事件数', type: 'line', smooth: true, showSymbol: false,
        data: t.map((d) => d.eventCount), itemStyle: { color: '#2A9D8F' }
      }
    ]
  }
})

// ---------------------------------------------------------------- 数据加载
async function loadPeriod() {
  loading.value = true
  try {
    period.value = await analyticsApi.periodSummary({
      periodType: periodType.value,
      startDate: app.startDate,
      endDate: app.endDate,
      siteId: app.siteId || undefined
    })
  } catch {
    period.value = null
  } finally {
    loading.value = false
  }
}

async function loadCop() {
  if (!copBuildingId.value) {
    ElMessage.warning('COP 计算必须选择一栋建筑')
    return
  }
  loading.value = true
  try {
    cop.value = await analyticsApi.cop({
      buildingId: copBuildingId.value,
      startDate: app.startDate,
      endDate: app.endDate
    })
  } catch {
    cop.value = null
  } finally {
    loading.value = false
  }
}

async function loadAnomaly() {
  loading.value = true
  try {
    anomaly.value = await analyticsApi.anomaly({
      startDate: app.startDate,
      endDate: app.endDate,
      siteId: app.siteId || undefined
    })
  } catch {
    anomaly.value = null
  } finally {
    loading.value = false
  }
}

/** 只重新加载当前页签的数据 */
function reloadActive() {
  if (activeTab.value === 'period') loadPeriod()
  else if (activeTab.value === 'cop') { if (copBuildingId.value) loadCop() }
  else loadAnomaly()
}

function switchTab(name: string) {
  activeTab.value = name
  // 懒加载：进入页签时才发请求
  if (name === 'period' && !period.value) loadPeriod()
  else if (name === 'cop' && copBuildingId.value && !cop.value) loadCop()
  else if (name === 'anomaly' && !anomaly.value) loadAnomaly()
}

function onRangeChange(v: [string, string] | null) {
  if (v && v.length === 2) {
    app.setRange(v[0], v[1])
    reloadActive()
  }
}

async function onExport() {
  try {
    await analyticsApi.exportReport(
      {
        startDate: app.startDate,
        endDate: app.endDate,
        siteId: app.siteId || undefined,
        periodType: periodType.value
      },
      `碳能智控_统计分析报表_${app.startDate}_${app.endDate}.xlsx`
    )
    ElMessage.success('报表已开始下载')
  } catch {
    ElMessage.error('报表导出失败')
  }
}

// 导入新数据后按当前标签页重新加载
useDataVersionRefresh(async () => {
  await loadPeriod()
  if (activeTab.value === 'cop' && copBuildingId.value) await loadCop()
  if (activeTab.value === 'anomaly') await loadAnomaly()
})

onMounted(async () => {
  try {
    const [s, bs] = await Promise.all([
      energyApi.sites(),
      energyApi.buildings({ limit: 500 })
    ])
    sites.value = s
    buildings.value = bs
  } catch {
    /* 忽略 */
  }
  await loadPeriod()
})
</script>

<style scoped>
/* ── 分段式导航 ── */
.seg-nav {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: 12px;
  background: #eef2ef;
  margin-bottom: 12px;
}
.seg-item {
  padding: 7px 20px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--carbon-text-dim, #5d6e66);
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
}
.seg-item:hover { color: var(--carbon-text, #253d35); }
.seg-item.active {
  background: #ffffff;
  color: var(--carbon-primary, #35866b);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(37, 61, 53, 0.12);
}

/* ── KPI 卡 ── */
.kpi-card {
  background: var(--carbon-panel, #fff);
  border: 1px solid var(--carbon-border, #e3e9e4);
  border-radius: 14px;
  padding: 14px 16px;
  box-shadow: var(--shadow-sm);
  position: relative;
  overflow: hidden;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}
.kpi-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.kpi-card::after {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 2px;
  background: linear-gradient(90deg, var(--carbon-primary, #35866b), rgba(53, 134, 107, 0));
  opacity: 0.35;
}
.kpi-card.risk::after {
  background: linear-gradient(90deg, #c26b62, rgba(194, 107, 98, 0));
}
.kpi-card.risk .kpi-value { color: #c26b62; }
/* COP 平均 COP 视觉重点 */
.kpi-card.cop-hero {
  background: linear-gradient(140deg, #f0f6f2, #ffffff);
  border-color: rgba(53, 134, 107, 0.28);
}
.kpi-card.cop-hero .kpi-value.hero {
  font-size: 30px;
  color: var(--carbon-primary, #35866b);
}
.kpi-label { font-size: 12px; color: var(--carbon-text-dim, #5d6e66); margin-bottom: 6px; display: flex; align-items: center; gap: 4px; }
.kpi-value { font-size: 22px; font-weight: 600; color: var(--carbon-text, #253d35); line-height: 1.2; font-variant-numeric: tabular-nums; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--carbon-text-mute, #7b8b84); margin-left: 4px; }
.kpi-sub { font-size: 11px; color: var(--carbon-text-mute, #7b8b84); margin-top: 5px; }

.dev-high { color: #c26b62; font-weight: 600; }

:deep(.el-table .cell) { font-variant-numeric: tabular-nums; }
:deep(.kpi-row) { margin-top: 4px; }
</style>
