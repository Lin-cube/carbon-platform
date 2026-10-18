<template>
  <div>
    <!-- 筛选栏 -->
    <div class="page-card mb-12">
      <div class="page-title">异常诊断</div>
      <div class="filter-bar" style="margin-bottom: 0">
        <span class="text-dim">时间范围</span>
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="开始" end-placeholder="结束" style="width: 240px"
                        @change="onRangeChange" />

        <span class="text-dim">园区</span>
        <el-select v-model="siteId" placeholder="全部园区" clearable style="width: 140px"
                   @change="onSiteChange">
          <el-option v-for="s in sites" :key="s.siteId" :label="`${s.siteId}（${s.buildingCount}栋）`"
                     :value="s.siteId" />
        </el-select>

        <span class="text-dim">建筑</span>
        <el-select v-model="buildingId" placeholder="全部建筑" filterable clearable style="width: 250px">
          <el-option v-for="b in buildings" :key="b.buildingId"
                     :label="`${b.buildingId}（${b.buildingTypeCn || '未分类'}）`" :value="b.buildingId" />
        </el-select>

        <span class="text-dim">异常类型</span>
        <el-select v-model="anomalyType" placeholder="全部类型" clearable style="width: 140px">
          <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>

        <span class="text-dim">严重度</span>
        <el-select v-model="severity" placeholder="全部" clearable style="width: 110px">
          <el-option label="高" value="高" />
          <el-option label="中" value="中" />
          <el-option label="低" value="低" />
        </el-select>

        <span class="text-dim">最短持续(h)</span>
        <el-input-number v-model="minDuration" :min="0" :max="720" :step="1" controls-position="right"
                         style="width: 120px" placeholder="不限" />

        <el-button type="primary" :icon="Search" :loading="loading" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>
      </div>

      <div class="filter-bar mt-12" style="margin-bottom: 0">
        <span class="text-dim">每页条数</span>
        <el-select v-model="pageSize" style="width: 100px" @change="search">
          <el-option :value="10" label="10" />
          <el-option :value="20" label="20" />
          <el-option :value="50" label="50" />
          <el-option :value="100" label="100" />
        </el-select>
        <!-- 实测：当前 2016 数据集里 severity=高 的事件数为 0，保留选项以便后续数据接入 -->
        <span class="text-dim" style="font-size: 12px">
          提示：当前数据集（2016）中「高」严重度事件为 0，选它可能查不到结果
        </span>
      </div>
    </div>

    <!-- 事件列表（后端分页） -->
    <div class="page-card">
      <div class="chart-title">
        异常事件清单 <span class="src-badge src-labeled">算法标注</span>
        <span class="text-dim" style="font-weight: 400">
          · 点击任意行或「诊断」按钮查看归因证据链
        </span>
      </div>

      <el-table :data="events" size="small" v-loading="loading" max-height="560"
                highlight-current-row @row-click="onRowClick">
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="buildingId" label="建筑编号" min-width="190" show-overflow-tooltip />
        <el-table-column prop="siteId" label="园区" width="100" />
        <el-table-column prop="buildingTypeCn" label="建筑类型" width="126" />
        <el-table-column prop="startTs" label="开始时间" width="150" sortable />
        <el-table-column prop="durationHours" label="持续(h)" width="96" align="right" sortable />
        <el-table-column prop="anomalyType" label="异常类型" width="112" />
        <el-table-column prop="severity" label="严重度" width="94">
          <template #default="{ row }">
            <el-tag :type="severityTag(row.severity)" size="small" effect="dark">
              {{ row.severity || '未分级' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="peakValue" label="峰值" width="104" align="right" sortable>
          <template #default="{ row }">{{ fmtNum(row.peakValue) }}</template>
        </el-table-column>
        <el-table-column prop="baselineValue" label="基线" width="104" align="right" sortable>
          <template #default="{ row }">{{ fmtNum(row.baselineValue) }}</template>
        </el-table-column>
        <el-table-column prop="deviationPct" label="偏离%" width="100" align="right" sortable>
          <template #default="{ row }">
            <span :style="{ color: (row.deviationPct ?? 0) > 100 ? '#ef4444' : undefined,
                            fontWeight: (row.deviationPct ?? 0) > 100 ? 600 : 400 }">
              {{ row.deviationPct ?? '--' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="152" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small"
                       @click.stop="openDiagnose(row)">诊断</el-button>
            <el-button link type="warning" size="small"
                       @click.stop="openSuggest(row)">优化建议</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 服务端分页：total 来自接口返回 -->
      <el-pagination class="mt-12" layout="total, sizes, prev, pager, next, jumper"
                     :total="total" :page-size="pageSize" :current-page="pageNum"
                     :page-sizes="[10, 20, 50, 100]"
                     @current-change="onPageChange" @size-change="onPageSizeChange" background />

      <el-empty v-if="!loading && !events.length" description="没有符合条件的异常事件，可放宽严重度或时长条件" />
    </div>

    <!-- 诊断抽屉 -->
    <el-drawer v-model="diagnoseVisible" size="62%" :title="`异常诊断 · ${current?.buildingId || ''}`">
      <div v-loading="diagLoading">
        <template v-if="diagnose">
          <div class="filter-bar">
            <el-tag :type="diagnose.hasAnomaly ? 'danger' : 'success'" effect="dark">
              {{ diagnose.hasAnomaly ? '存在能耗异常' : '未检出明显异常' }}
            </el-tag>
            <span class="text-dim">统计区间 {{ diagnose.timeRange }}</span>
            <span class="text-dim">建筑 {{ (diagnose.buildingIds || []).join('、') }}</span>
          </div>

          <!-- 异常时段 vs 正常时段 -->
          <div class="chart-title mt-12">异常时段 / 正常时段对比</div>
          <el-table :data="diagnose.anomalyVsNormal || []" size="small" border class="mb-12">
            <el-table-column label="时段" width="110">
              <template #default="{ row }">
                <el-tag :type="String(row.group).includes('异常') ? 'danger' : 'success'" size="small"
                        effect="plain">
                  {{ row.group }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="小时数" width="96" align="right">
              <template #default="{ row }">{{ fmtInt(num(row, 'hours')) }}</template>
            </el-table-column>
            <el-table-column label="均电耗" min-width="110" align="right">
              <template #default="{ row }">{{ fmtNum(num(row, 'avgKwh')) }} kWh</template>
            </el-table-column>
            <el-table-column label="室外温度" min-width="108" align="right">
              <template #default="{ row }">{{ num(row, 'avgTemp') ?? '--' }} ℃</template>
            </el-table-column>
            <el-table-column label="人员密度" min-width="112" align="right">
              <template #default="{ row }">{{ num(row, 'avgOccupancy') ?? '--' }} 人/100㎡</template>
            </el-table-column>
            <el-table-column label="COP" width="88" align="right">
              <template #default="{ row }">{{ num(row, 'avgCop') ?? '--' }}</template>
            </el-table-column>
            <el-table-column label="温差" width="92" align="right">
              <template #default="{ row }">{{ num(row, 'avgDeltaT') ?? '--' }} ℃</template>
            </el-table-column>
          </el-table>
          <div class="text-dim mb-12" style="font-size: 12px">
            说明：室外温度、人员密度、供回水温度与 COP 中，供回水温度与人员密度来自仿真/推算链路，
            判定时需结合现场实际（申报表第 17 条：AI 输出不直接作为最终决策依据）。
          </div>

          <!-- 归因证据链 -->
          <div class="chart-title">归因分析（证据链）</div>
          <el-empty v-if="!(diagnose.attributions || []).length" description="未发现明显异常归因" />
          <el-card v-for="(a, i) in diagnose.attributions || []" :key="i" shadow="never"
                   class="attr-card mb-12">
            <div class="attr-head">
              <span class="attr-cause">{{ i + 1 }}. {{ a.cause }}</span>
              <el-tag :type="confidenceTag(a.confidence)" size="small" effect="dark">
                置信度 {{ a.confidence }}
              </el-tag>
              <el-tag v-if="a.needsManualReview" type="warning" size="small" effect="plain">
                需人工复核
              </el-tag>
            </div>
            <div class="attr-row">
              <span class="attr-key">证据</span>
              <span class="attr-val">{{ a.evidence }}</span>
            </div>
            <div class="attr-row">
              <span class="attr-key">建议</span>
              <span class="attr-val">{{ a.suggestion }}</span>
            </div>
          </el-card>

          <!-- 能效画像 -->
          <div class="chart-title mt-12">能效画像</div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item v-for="f in profileRows(diagnose.efficiencyProfile)" :key="f.key"
                                  :label="f.label">
              {{ f.text }}
            </el-descriptions-item>
          </el-descriptions>
        </template>
        <el-empty v-else-if="!diagLoading" description="暂无诊断结果" />
      </div>
    </el-drawer>

    <!-- 优化建议抽屉 -->
    <el-drawer v-model="suggestVisible" size="58%" :title="`优化建议 · ${current?.buildingId || ''}`">
      <div v-loading="sugLoading">
        <template v-if="suggest">
          <el-alert v-if="suggest.disclaimer" type="warning" :closable="false" show-icon
                    class="mb-12" title="使用须知" :description="suggest.disclaimer" />

          <el-empty v-if="!(suggest.suggestions || []).length"
                    description="该建筑在查询区间内无有效数据，暂无法给出优化建议" />

          <el-card v-for="(s, i) in suggest.suggestions || []" :key="i" shadow="never"
                   class="attr-card mb-12">
            <div class="attr-head">
              <span class="attr-cause">{{ i + 1 }}. {{ s.title }}</span>
              <el-tag :type="priorityTag(s.priority)" size="small" effect="dark">
                优先级 {{ s.priority }}
              </el-tag>
              <el-tag v-if="s.needsManualReview" type="warning" size="small" effect="plain">
                需人工复核
              </el-tag>
            </div>
            <div class="attr-row">
              <span class="attr-key">依据</span>
              <span class="attr-val">{{ s.evidence }}</span>
            </div>
            <div class="attr-row">
              <span class="attr-key">措施</span>
              <span class="attr-val">{{ s.actions }}</span>
            </div>
            <div class="attr-row">
              <span class="attr-key">原理</span>
              <span class="attr-val text-dim">{{ s.basis }}</span>
            </div>
          </el-card>

          <div class="chart-title mt-12">能效画像</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item v-for="f in profileRows(suggest.efficiencyProfile)" :key="f.key"
                                  :label="f.label">
              {{ f.text }}
            </el-descriptions-item>
          </el-descriptions>
        </template>
        <el-empty v-else-if="!sugLoading" description="暂无优化建议" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { analyticsApi, energyApi } from '@/api'
import { useAppStore } from '@/stores/app'
import { fmtNum, fmtInt } from '@/utils/echarts'
import type {
  AnomalyEvent, BuildingRow, DiagnoseResult, DimensionOption, SiteRow, SuggestResult
} from '@/types/api'

const app = useAppStore()
const route = useRoute()

const loading = ref(false)
const sites = ref<SiteRow[]>([])
const buildings = ref<BuildingRow[]>([])
const typeOptions = ref<DimensionOption[]>([])
const events = ref<AnomalyEvent[]>([])

const range = ref<[string, string]>([app.startDate, app.endDate])
const siteId = ref('')
const buildingId = ref('')
const anomalyType = ref('')
const severity = ref('')
const minDuration = ref<number | undefined>(undefined)
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const diagnoseVisible = ref(false)
const suggestVisible = ref(false)
const diagLoading = ref(false)
const sugLoading = ref(false)
const diagnose = ref<DiagnoseResult | null>(null)
const suggest = ref<SuggestResult | null>(null)
const current = ref<AnomalyEvent | null>(null)

// ---------------------------------------------------------------- 展示辅助
function severityTag(v: string | null) {
  switch (v) {
    case '高': return 'danger' as const
    case '中': return 'warning' as const
    case '低': return 'info' as const
    default: return 'info' as const
  }
}

function confidenceTag(v: string | null | undefined) {
  switch (v) {
    case '高': return 'success' as const
    case '中': return 'warning' as const
    default: return 'info' as const
  }
}

function priorityTag(v: string | null | undefined) {
  switch (v) {
    case '高': return 'danger' as const
    case '中': return 'warning' as const
    default: return 'success' as const
  }
}

/** anomalyVsNormal / efficiencyProfile 在后端是 Map 结构，取数时统一做空值保护 */
function num(row: Record<string, unknown> | undefined, key: string): number | null {
  const v = row?.[key]
  return typeof v === 'number' ? v : null
}

/** 能效画像字段中文名与单位（按后端 selectEfficiencyProfile 的字段名映射） */
const profileMeta: { key: string; label: string; unit?: string; int?: boolean }[] = [
  { key: 'buildingTypeCn', label: '建筑类型' },
  { key: 'dataGrade', label: '数据分级' },
  { key: 'energyGrade', label: '能耗评级' },
  { key: 'areaM2', label: '建筑面积', unit: '㎡' },
  { key: 'electricityKwh', label: '区间用电量', unit: 'kWh' },
  { key: 'hvacKwh', label: '空调能耗', unit: 'kWh' },
  { key: 'carbonKg', label: '碳排放', unit: 'kg' },
  { key: 'euiYear', label: 'EUI', unit: 'kWh/㎡·a' },
  { key: 'copAvg', label: '平均 COP' },
  { key: 'copMin', label: '最低 COP' },
  { key: 'supplyTempAvg', label: '平均供水温度', unit: '℃' },
  { key: 'returnTempAvg', label: '平均回水温度', unit: '℃' },
  { key: 'deltaTAvg', label: '平均温差', unit: '℃' },
  { key: 'daytimeAvgKwh', label: '日间均电耗', unit: 'kWh/h' },
  { key: 'nightAvgKwh', label: '夜间均电耗', unit: 'kWh/h' },
  { key: 'anomalyHours', label: '异常小时', unit: 'h', int: true },
  { key: 'qualityHours', label: '数据质量缺陷小时', unit: 'h', int: true },
  { key: 'evaluatedHours', label: '评估小时数', unit: 'h', int: true }
]

function profileRows(profile?: Record<string, unknown>) {
  if (!profile) return []
  return profileMeta
    .filter((m) => profile[m.key] !== undefined && profile[m.key] !== null)
    .map((m) => {
      const raw = profile[m.key]
      const text = typeof raw === 'number'
        ? (m.int ? fmtInt(raw) : fmtNum(raw, 2)) + (m.unit ? ` ${m.unit}` : '')
        : `${raw}${m.unit ? ` ${m.unit}` : ''}`
      return { key: m.key, label: m.label, text }
    })
}

// ---------------------------------------------------------------- 数据加载
async function loadEvents() {
  loading.value = true
  try {
    const res = await analyticsApi.scanAnomalies({
      startDate: app.startDate,
      endDate: app.endDate,
      siteId: siteId.value || undefined,
      buildingId: buildingId.value || undefined,
      anomalyType: anomalyType.value || undefined,
      severity: severity.value || undefined,
      minDuration: minDuration.value ?? undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    // events 在 API 类型里是 unknown[]，按 AnomalyEvent 使用
    events.value = (res.events || []) as AnomalyEvent[]
    total.value = res.total || 0
  } catch {
    events.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  pageNum.value = 1
  loadEvents()
}

function onPageChange(p: number) {
  pageNum.value = p
  loadEvents()
}

function onPageSizeChange(s: number) {
  pageSize.value = s
  pageNum.value = 1
  loadEvents()
}

function onSiteChange() {
  // 切换园区后清空建筑，防止传入不属于该园区的建筑
  buildingId.value = ''
  loadBuildings()
}

function onRangeChange(v: [string, string] | null) {
  if (v && v.length === 2) app.setRange(v[0], v[1])
}

function onReset() {
  app.setRange('2016-01-01', '2016-12-31')
  range.value = [app.startDate, app.endDate]
  siteId.value = ''
  buildingId.value = ''
  anomalyType.value = ''
  severity.value = ''
  minDuration.value = undefined
  pageNum.value = 1
  pageSize.value = 20
  loadBuildings()
  loadEvents()
}

async function loadBuildings() {
  try {
    buildings.value = await energyApi.buildings({ siteId: siteId.value || undefined, limit: 500 })
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------------------------------------------------------------- 诊断 / 建议
async function openDiagnose(row: AnomalyEvent) {
  current.value = row
  diagnose.value = null
  diagnoseVisible.value = true
  diagLoading.value = true
  try {
    diagnose.value = await analyticsApi.diagnose({
      buildingId: row.buildingId,
      startDate: app.startDate,
      endDate: app.endDate
    })
  } catch {
    diagnose.value = null
  } finally {
    diagLoading.value = false
  }
}

async function openSuggest(row: AnomalyEvent) {
  current.value = row
  suggest.value = null
  suggestVisible.value = true
  sugLoading.value = true
  try {
    suggest.value = await analyticsApi.suggest({
      buildingId: row.buildingId,
      startDate: app.startDate,
      endDate: app.endDate
    })
  } catch {
    suggest.value = null
  } finally {
    sugLoading.value = false
  }
}

function onRowClick(row: AnomalyEvent) {
  openDiagnose(row)
}

onMounted(async () => {
  try {
    const [s, dim] = await Promise.all([
      energyApi.sites(),
      energyApi.dimensions('anomaly_type')
    ])
    sites.value = s
    typeOptions.value = dim.options || []
  } catch {
    /* 忽略 */
  }

  // 3D 园区页通过 /anomaly?buildingId=xxx 跳转过来，需要预选该建筑
  const q = route.query.buildingId
  if (typeof q === 'string' && q) {
    buildingId.value = q
    ElMessage.info(`已按 3D 园区传入的建筑预选：${q}`)
  }

  await loadBuildings()
  await loadEvents()
})
</script>

<style scoped>
.attr-card :deep(.el-card__body) { padding: 12px 14px; }

.attr-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.attr-cause { font-weight: 600; font-size: 13px; color: #1f2937; }

.attr-row {
  display: flex;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.7;
  margin-top: 4px;
}

.attr-key {
  flex: 0 0 34px;
  color: #6b7280;
  font-weight: 600;
}

.attr-val { flex: 1; color: #374151; word-break: break-word; }

:deep(.el-pagination) { justify-content: flex-end; }
:deep(.el-table .cell) { font-variant-numeric: tabular-nums; }
</style>
