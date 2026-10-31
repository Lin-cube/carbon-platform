<template>
  <div>
    <div class="page-card wizard-step mb-12">
      <div class="page-title-row">
        <div class="page-title">数据导入与治理 · 五步向导</div>
        <!-- 审计记录入口：原来只能走完向导第 5 步才能看到历史批次，
             不导入就没法查看。这里提供常驻入口，随时可进。 -->
        <el-button v-if="step !== 4" text type="primary" @click="openAudit">
          查看历史导入批次 →
        </el-button>
        <el-button v-else text type="primary" @click="step = 0">← 返回导入向导</el-button>
      </div>
      <el-steps :active="step" finish-status="success" align-center>
        <el-step title="数据源选择" description="上传 CSV / Excel" />
        <el-step title="字段映射" description="自动识别 + 人工确认" />
        <el-step title="清洗规则" description="缺失/负值/去重" />
        <el-step title="预览导入" description="先预览后落库" />
        <el-step title="审计留痕" description="批次可追溯" />
      </el-steps>
    </div>

    <!-- ============ 步骤 1：数据源选择 ============ -->
    <div v-show="step === 0" class="page-card wizard-step">
      <div class="page-title">步骤一 · 选择数据源</div>

      <el-upload ref="uploadRef" drag :auto-upload="false" :limit="1" accept=".csv,.xlsx,.xls"
                 :on-change="onFileChange" class="uploader">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">
            支持 CSV / XLSX / XLS；CSV 自动识别 UTF-8 与 GBK 编码；单次上限 20 万行。
            字段名建议使用标准列名（如 建筑编号 / 监测时间 / 电力能耗）。
          </div>
        </template>
      </el-upload>

      <div v-if="uploadResult" class="mt-12">
        <!-- 文件信息卡：文件名 / 大小 / 格式 / 解析状态 -->
        <div class="file-info">
          <el-icon class="file-icon"><Document /></el-icon>
          <div class="file-meta">
            <div class="file-name">{{ uploadResult.fileName }}</div>
            <div class="file-sub">
              <span class="file-badge">{{ (uploadResult.fileType || '文件').toUpperCase() }}</span>
              <span>{{ fmtSize(uploadResult.fileSize) }}</span>
              <span>{{ uploadResult.totalRows }} 行 · {{ uploadResult.columns?.length }} 列</span>
              <span class="parse-ok"><span class="parse-dot"></span>已解析</span>
            </div>
          </div>
        </div>

        <el-alert v-for="(w, i) in uploadResult.warnings" :key="i" type="warning"
                  :closable="false" show-icon class="mt-12" :title="w" />

        <div class="chart-box mt-12">
          <div class="chart-title">数据预览（前 {{ uploadResult.previewRows?.length }} 行）</div>
          <el-table :data="previewTable" size="small" max-height="300" border>
            <el-table-column v-for="(h, i) in uploadResult.headers" :key="i"
                             :prop="'c' + i" :label="h" min-width="120" show-overflow-tooltip />
          </el-table>
        </div>
      </div>

      <div class="step-actions">
        <el-button type="primary" :disabled="!uploadResult" @click="step = 1">
          下一步：字段映射
        </el-button>
      </div>
    </div>

    <!-- ============ 步骤 2：字段映射 ============ -->
    <div v-show="step === 1" class="page-card wizard-step">
      <div class="page-title">步骤二 · 字段映射</div>
      <el-alert type="info" :closable="false" show-icon class="mb-12"
                title="系统已根据列名与数据特征自动匹配目标字段，请确认或调整。标记「必填」的字段必须映射。" />

      <el-alert v-if="duplicateMappings.length" type="warning" :closable="false" show-icon class="mb-12"
                :title="`存在重复映射：${duplicateMappings.join('、')} 被多个源列映射到同一目标字段，请调整。`" />
      <el-alert v-if="missingRequired.length" type="error" :closable="false" show-icon class="mb-12"
                :title="`必填字段未映射：${missingRequired.join('、')}。`" />

      <el-table :data="uploadResult?.columns || []" size="small" border>
        <el-table-column prop="sourceColumn" label="源列名" min-width="130" />
        <el-table-column prop="inferredType" label="推断类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain"
                    :type="row.inferredType === 'number' ? 'success'
                           : row.inferredType === 'datetime' ? 'warning'
                           : row.inferredType === 'empty' ? 'danger' : 'info'">
              {{ typeLabel(row.inferredType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="样例值" min-width="170">
          <template #default="{ row }">
            <span class="text-dim">{{ (row.samples || []).join(' | ') || '（空列）' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="目标字段" width="215">
          <template #default="{ row }">
            <el-select v-model="mapping[row.sourceColumn]" size="small" clearable
                       placeholder="忽略该列" style="width: 100%">
              <el-option v-for="f in targetFields" :key="f.column"
                         :label="fieldLabel(f)" :value="f.column" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="自动匹配" width="86" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.confidence >= 0.95" size="small" type="success">高</el-tag>
            <el-tag v-else-if="row.confidence >= 0.7" size="small" type="warning">中</el-tag>
            <el-tag v-else-if="row.confidence > 0" size="small" type="info">低</el-tag>
            <span v-else class="text-dim">未匹配</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="匹配依据" min-width="200" show-overflow-tooltip />
      </el-table>

      <div class="step-actions">
        <el-button @click="step = 0">上一步</el-button>
        <el-button type="primary" :disabled="missingRequired.length > 0 || duplicateMappings.length > 0"
                   @click="step = 2">下一步：清洗规则</el-button>
      </div>
    </div>

    <!-- ============ 步骤 3：清洗规则 ============ -->
    <div v-show="step === 2" class="page-card wizard-step">
      <div class="page-title">步骤三 · 清洗规则</div>

      <div class="rule-grid">
        <!-- 缺失值处理 -->
        <div class="rule-card">
          <div class="rule-head">缺失值处理</div>
          <el-radio-group v-model="cleanRules.missingStrategy">
            <el-radio value="none">保留为空</el-radio>
            <el-radio value="zero">填 0</el-radio>
            <el-radio value="drop">整行剔除</el-radio>
          </el-radio-group>
          <div class="rule-conseq">决定空值如何进入数据库：保留为空会留 NULL，填 0 可能低估，整行剔除会减少有效行。</div>
        </div>

        <!-- 去重策略 -->
        <div class="rule-card">
          <div class="rule-head">去重策略</div>
          <el-radio-group v-model="cleanRules.dedupStrategy">
            <el-radio value="byBuildingTime">按「建筑+时间」去重（推荐）</el-radio>
            <el-radio value="none">不去重</el-radio>
          </el-radio-group>
          <div class="rule-conseq">重复导入同一时段的记录会被覆盖/跳过，避免同一行被统计两次。</div>
        </div>

        <!-- 数值类型 / 负值 -->
        <div class="rule-card">
          <div class="rule-head">数值类型与负值</div>
          <div class="rule-switch">
            <span>剔除负值能耗</span>
            <el-switch v-model="cleanRules.dropNegative" />
          </div>
          <div class="rule-conseq">能耗不可能为负，负值通常为表计或采集异常，剔除避免污染统计。</div>
        </div>

        <!-- 文本整理 -->
        <div class="rule-card">
          <div class="rule-head">文本整理</div>
          <div class="rule-switch">
            <span>去除首尾空格</span>
            <el-switch v-model="cleanRules.trimString" />
          </div>
          <div class="rule-conseq">建议开启，避免「 建筑A」与「建筑A」被当作不同建筑。</div>
        </div>

        <!-- 电量单位 -->
        <div class="rule-card">
          <div class="rule-head">电量单位</div>
          <el-radio-group v-model="cleanRules.electricityUnit">
            <el-radio value="kwh">kWh</el-radio>
            <el-radio value="mwh">MWh（自动 ×1000）</el-radio>
          </el-radio-group>
          <div class="rule-conseq">MWh 会在导入时自动 ×1000 转换为 kWh，统一单位口径。</div>
        </div>

        <!-- 建筑编号前缀 -->
        <div class="rule-card">
          <div class="rule-head">建筑编号前缀</div>
          <el-input v-model="cleanRules.buildingIdPrefix" style="width: 100%"
                    placeholder="例如 Demo（源数据无园区前缀时自动补齐）" />
          <div class="rule-conseq">源数据建筑编号缺少园区前缀时自动补齐，避免跨园区建筑编号冲突。</div>
        </div>
      </div>

      <div class="step-actions">
        <el-button @click="step = 1">上一步</el-button>
        <el-button type="primary" :loading="loading" @click="doPreview">下一步：预览导入</el-button>
      </div>
    </div>

    <!-- ============ 步骤 4：预览导入 ============ -->
    <div v-show="step === 3" class="page-card wizard-step">
      <div class="page-title">步骤四 · 预览导入（尚未写入数据库）</div>

      <div v-if="result" class="kpi-row">
        <div class="kpi-card">
          <div class="kpi-label">总行数</div>
          <div class="kpi-value">{{ result.totalRows }}<span class="kpi-unit">行</span></div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">有效</div>
          <div class="kpi-value ok">{{ result.successRows }}<span class="kpi-unit">行</span></div>
        </div>
        <div class="kpi-card risk">
          <div class="kpi-label">失败</div>
          <div class="kpi-value">{{ result.failedRows }}<span class="kpi-unit">行</span></div>
        </div>
        <div class="kpi-card risk">
          <div class="kpi-label">待清洗</div>
          <div class="kpi-value">{{ result.skippedRows }}<span class="kpi-unit">行</span></div>
        </div>
      </div>

      <el-alert v-if="result && result.failedRows > 0" type="warning" :closable="false" show-icon
                class="mb-12"
                :title="`有 ${result.failedRows} 行解析失败，请检查下方明细后再决定是否导入`" />

      <div v-if="result?.errors?.length" class="chart-box mb-12">
        <div class="chart-title">错误 / 跳过明细（最多展示 100 条）</div>
        <el-table :data="result.errors" size="small" max-height="240" border>
          <el-table-column prop="rowNum" label="行号" width="80" />
          <el-table-column prop="field" label="字段" width="130" />
          <el-table-column prop="message" label="原因" min-width="220" />
          <el-table-column prop="value" label="原始值" min-width="140" show-overflow-tooltip />
        </el-table>
      </div>

      <div v-if="result" class="chart-box">
        <div class="chart-title">字段解析成功数</div>
        <el-table :data="fieldStatRows" size="small" border>
          <el-table-column prop="field" label="目标字段" min-width="180" />
          <el-table-column prop="count" label="成功解析行数" width="140" align="right" />
          <el-table-column label="覆盖率" min-width="180">
            <template #default="{ row }">
              <el-progress :percentage="pct(row.count)" :stroke-width="12" />
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="loading" class="import-progress">
        <el-progress :percentage="0" :indeterminate="true" :show-text="false" :stroke-width="3" />
        <span class="text-dim">正在处理…（后端无阶段进度接口，仅显示进行中状态，不假装百分比）</span>
      </div>

      <div class="step-actions">
        <el-button @click="step = 2">上一步</el-button>
        <el-button type="primary" :loading="loading"
                   :disabled="!result || result.successRows === 0" @click="doImport">
          确认导入（写入数据库）
        </el-button>
      </div>
    </div>

    <!-- ============ 步骤 5：审计留痕 ============ -->
    <div v-show="step === 4" class="page-card wizard-step">
      <div class="page-title">步骤五 · 导入结果与审计留痕</div>

      <el-result v-if="result" :icon="result.status === '成功' ? 'success' : 'warning'"
                 :title="`导入${result.status}`"
                 :sub-title="`批次号 ${result.batchNo} · 成功 ${result.successRows} 行 · 失败 ${result.failedRows} 行 · 跳过 ${result.skippedRows} 行`" />

      <el-alert type="info" :closable="false" show-icon class="mb-12"
                title="导入的数据按 A 级（实测）登记；anomaly_flag = -1（未评估）是因为异常检测需要 52 周历史基线，新数据历史不足无法判定。如需参与异常分析，请导入至少 52 周历史后重新运行标注脚本。" />

      <div class="chart-box">
        <div class="chart-title-row">
          <div class="chart-title">历史导入批次（审计留痕）</div>
          <el-button size="small" type="primary" plain :loading="exporting" @click="exportLogs">
            导出审计记录（Excel）
          </el-button>
        </div>
        <el-table :data="logs" size="small" border v-loading="loading">
          <el-table-column prop="batchNo" label="批次号" min-width="195" />
          <el-table-column prop="fileName" label="文件名" min-width="170" show-overflow-tooltip />
          <el-table-column prop="fileType" label="类型" width="80" />
          <el-table-column prop="totalRows" label="总行" width="78" align="right" />
          <el-table-column prop="successRows" label="成功" width="78" align="right" />
          <el-table-column prop="failedRows" label="失败" width="78" align="right" />
          <el-table-column prop="status" label="状态" width="102">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === '成功' ? 'success'
                             : row.status === '预览通过' ? 'info' : 'warning'">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <!-- 是否入库：用户最容易误解的一列。「预览通过」只是演练，数据没写库；
               「成功 / 部分成功」才是真的落库。用独立列显著区分。 -->
          <el-table-column label="是否入库" width="112" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.dryRun === 1 || row.dryRun === true"
                      size="small" type="info" effect="plain">未入库（预览）</el-tag>
              <el-tag v-else size="small" type="success">已入库</el-tag>
            </template>
          </el-table-column>
          <!-- 库中现有行数：批次追溯的直观证据。导入后应等于成功行数，回滚后变为 0。 -->
          <el-table-column label="库中行数" width="96" align="right">
            <template #default="{ row }">
              <span v-if="row.dryRun === 1 || row.dryRun === true" class="text-muted">—</span>
              <el-tag v-else-if="row.rowsInFact > 0" size="small" type="success" effect="plain">
                {{ row.rowsInFact }}
              </el-tag>
              <el-tag v-else size="small" type="info" effect="plain">0</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operator" label="操作人" width="96" />
          <el-table-column prop="createdAt" label="时间" width="160" />
          <el-table-column label="操作" width="86" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showLogDetail(row.id)">
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="step-actions">
        <el-button type="primary" @click="resetWizard">再导入一批</el-button>
      </div>
    </div>

    <!-- 审计详情 -->
    <el-dialog v-model="logDetailVisible" title="导入批次详情" width="660px">
      <el-descriptions v-if="logDetail" :column="1" border size="small">
        <el-descriptions-item label="批次号">{{ logDetail.batchNo }}</el-descriptions-item>
        <el-descriptions-item label="文件名">{{ logDetail.fileName }}</el-descriptions-item>
        <el-descriptions-item label="字段映射">
          <pre class="json-pre">{{ pretty(logDetail.fieldMapping) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="清洗规则">
          <pre class="json-pre">{{ pretty(logDetail.cleanRules) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="错误明细">
          <pre class="json-pre">{{ pretty(logDetail.errorDetail) || '（无）' }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="操作人">{{ logDetail.operator }}</el-descriptions-item>
        <div class="mt-12" v-if="logDetail && logDetail.dryRun !== 1 && logDetail.dryRun !== true">
          <el-alert type="warning" :closable="false" show-icon class="mb-12"
                    title="回滚会删除该批次写入的全部数据行，并同步刷新预聚合表。操作不可撤销，但审计记录会保留（状态改为「已回滚」）。" />
          <el-button type="danger" :loading="rollbacking" @click="doRollback">
            回滚该批次（删除这批数据）
          </el-button>
        </div>

        <el-descriptions-item label="时间">{{ logDetail.createdAt }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { ElMessage, type UploadFile, type UploadInstance, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
// 注意：必须用具名导出的 request()，它返回【已解包】的业务数据。
// default 导出是裸 axios 实例（service），返回值仍裹着 {code,message,data} 一层，
// 用它会导致 res.columns 为 undefined（实测踩过）。
import { request } from '@/api/request'

interface ColumnInfo {
  sourceColumn: string
  index: number
  inferredType: string
  sampleNonEmpty: number
  samples: string[]
  suggestedTarget: string | null
  confidence: number
  reason: string
}
interface UploadResult {
  fileId: string
  fileName: string
  fileType: string
  fileSize: number
  columns: ColumnInfo[]
  totalRows: number
  previewRows: string[][]
  headers: string[]
  warnings: string[]
}
interface TargetField { column: string; label: string; required: boolean; unit: string }
interface ImportResult {
  batchNo: string
  fileName: string
  totalRows: number
  successRows: number
  failedRows: number
  skippedRows: number
  status: string
  errors: { rowNum: number; field: string; message: string; value: string }[]
  fieldStats: Record<string, number>
  dryRun: boolean
  startDate?: string
  endDate?: string
}
interface ImportLog {
  id: number
  batchNo: string
  fileName: string
  fileType: string
  totalRows: number
  successRows: number
  failedRows: number
  status: string
  /** 1/true = 仅预览未写库；0/false = 已写入数据库 */
  dryRun?: number | boolean
  /** 该批次在事实表中现有行数（回滚后为 0） */
  rowsInFact?: number
  operator: string
  createdAt: string
}

const step = ref(0)
const loading = ref(false)
const uploadRef = ref<UploadInstance>()
const uploadResult = ref<UploadResult | null>(null)
const targetFields = ref<TargetField[]>([])
const mapping = reactive<Record<string, string>>({})
const result = ref<ImportResult | null>(null)
const logs = ref<ImportLog[]>([])
const logDetailVisible = ref(false)
const logDetail = ref<Record<string, unknown> | null>(null)
const rollbacking = ref(false)
const exporting = ref(false)

const router = useRouter()
const app = useAppStore()
const selectedFile = ref<File | null>(null)

const cleanRules = reactive({
  trimString: true,
  dropNegative: true,
  missingStrategy: 'none',
  buildingIdPrefix: '',
  electricityUnit: 'kwh',
  dedupStrategy: 'byBuildingTime'
})

/** 预览表格：二维数组 -> 带列 prop 的对象数组 */
const previewTable = computed(() => {
  if (!uploadResult.value) return []
  return (uploadResult.value.previewRows || []).map((row) => {
    const o: Record<string, string> = {}
    row.forEach((v, i) => (o['c' + i] = v))
    return o
  })
})

const fieldStatRows = computed(() => {
  const stats = result.value?.fieldStats || {}
  return Object.entries(stats).map(([field, count]) => ({ field, count }))
})

function pct(count: number) {
  const total = result.value?.totalRows || 1
  return Math.min(100, Math.round((count / total) * 100))
}

function typeLabel(t: string) {
  return t === 'number' ? '数值' : t === 'datetime' ? '时间' : t === 'empty' ? '空列' : '文本'
}

/** 文件大小格式化（B/KB/MB） */
function fmtSize(bytes?: number) {
  if (!bytes || bytes <= 0) return '未知大小'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/** 被多个源列映射到同一目标字段的重复项 */
const duplicateMappings = computed(() => {
  const seen = new Set<string>()
  const dups: string[] = []
  for (const target of Object.values(mapping)) {
    if (!target) continue
    if (seen.has(target)) dups.push(target)
    seen.add(target)
  }
  return [...new Set(dups)]
})

/** 必填目标字段中尚未被映射的 */
const missingRequired = computed(() => {
  const mapped = new Set(Object.values(mapping).filter(Boolean))
  return targetFields.value
    .filter((f) => f.required && !mapped.has(f.column))
    .map((f) => f.label)
})

function fieldLabel(f: TargetField) {
  const unit = f.unit && f.unit !== '-' ? ` (${f.unit})` : ''
  return `${f.label}${f.required ? ' *' : ''}${unit}`
}

function onFileChange(file: UploadFile) {
  if (file.raw) {
    selectedFile.value = file.raw
    doUpload()
  }
}

async function doUpload() {
  if (!selectedFile.value) return
  loading.value = true
  try {
    const fd = new FormData()
    fd.append('file', selectedFile.value)
    const res = await request<UploadResult>({
      url: '/import/upload',
      method: 'post',
      data: fd,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    uploadResult.value = res
    // 用自动映射建议预填
    Object.keys(mapping).forEach((k) => delete mapping[k])
    for (const c of res.columns) {
      if (c.suggestedTarget) mapping[c.sourceColumn] = c.suggestedTarget
    }
    result.value = null
    ElMessage.success(`解析完成：${res.totalRows} 行、${res.columns.length} 列`)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function buildMappings() {
  return Object.entries(mapping)
    .filter(([, target]) => !!target)
    .map(([sourceColumn, targetField]) => ({ sourceColumn, targetField }))
}

function buildRules() {
  return {
    trimString: cleanRules.trimString,
    dropNegative: cleanRules.dropNegative,
    missingStrategy: cleanRules.missingStrategy,
    buildingIdPrefix: cleanRules.buildingIdPrefix || undefined,
    electricityUnit: cleanRules.electricityUnit,
    dedupStrategy: cleanRules.dedupStrategy
  }
}

async function doPreview() {
  if (!uploadResult.value) return
  loading.value = true
  try {
    result.value = await request<ImportResult>({
      url: '/import/execute',
      method: 'post',
      data: {
        fileId: uploadResult.value.fileId,
        mappings: buildMappings(),
        cleanRules: buildRules(),
        dryRun: true
      }
    })
    step.value = 3
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function doImport() {
  if (!uploadResult.value) return
  loading.value = true
  try {
    result.value = await request<ImportResult>({
      url: '/import/execute',
      method: 'post',
      data: {
        fileId: uploadResult.value.fileId,
        mappings: buildMappings(),
        cleanRules: buildRules(),
        dryRun: false
      }
    })
    ElMessage.success(`导入完成：成功 ${result.value?.successRows ?? 0} 行`)
    // ★ 通知全站数据已变更：看板 / 3D园区 / 异常诊断的 AI 解读会因此重新生成，
    //   避免停留在旧数据的结论上（这正是「实时更新」的实现方式）。
    app.bumpDataVersion()
    step.value = 4
    await loadLogs()
    await autoSwitchRange(result.value)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}



/**
 * 导入完成后，主动询问是否把全局日期范围切到本批次的数据范围。
 *
 * <p>【为什么需要】前端全局日期默认是 2016 年（BDG2 数据集的范围）。
 * 如果用户导入的是 2019 年的数据，看板按 2016 年查，自然一片空白，
 * 用户会误以为「数据没导入进去」。实测过这个误解。
 *
 * <p>本函数从批次追溯接口取回该批次的真实起止日期，让用户一键跳转查看。
 */
/**
 * 导入成功后自动把全局时间范围切到本批次数据的区间。
 *
 * 【为什么需要】全局范围默认写死 2016-01-01~2016-12-31，
 * 导入 2017 数据后看板/能耗查询仍按 2016 过滤，用户会误以为没导入成功。
 *
 * 策略：当前范围与本批次不重叠时自动切换（无需用户手动改日期）；
 * 重叠时保持用户当前选择不动，只提示可前往看板。
 */
async function autoSwitchRange(res?: ImportResult) {
  const start = res?.startDate ? String(res.startDate).slice(0, 10) : ''
  const end = res?.endDate ? String(res.endDate).slice(0, 10) : ''
  if (!start || !end) return

  // 日期区间是否重叠（字符串 YYYY-MM-DD 可直接比较）
  const overlap = app.startDate <= end && start <= app.endDate
  if (!overlap) {
    app.setRange(start, end)
    ElMessage.success(`已自动切换时间范围到 ${start} ~ ${end}`)
  }

  try {
    await ElMessageBox.confirm(
      `本批次数据覆盖 ${start} 至 ${end}。` +
      (overlap
        ? `当前时间范围已包含该区间，未做改动。`
        : `已自动把全局时间范围切换到该区间。`) +
      `\n\n是否前往监控看板查看新数据？`,
      '导入成功',
      { type: 'success', confirmButtonText: '前往看板', cancelButtonText: '留在本页' }
    )
    router.push('/dashboard')
  } catch {
    /* 留在本页 */
  }
}

/** 跳到审计页（第 5 步）并刷新列表 */
async function openAudit() {
  step.value = 4
  await loadLogs()
}

/** 导出审计记录为 Excel（3 个 Sheet） */
async function exportLogs() {
  exporting.value = true
  try {
    const blob = await request<Blob>({
      url: '/import/logs/export',
      method: 'get',
      responseType: 'blob'
    })
    const b = blob instanceof Blob ? blob : new Blob([blob as unknown as BlobPart])
    const url = URL.createObjectURL(b)
    const a = document.createElement('a')
    a.href = url
    a.download = `导入审计记录_${new Date().toISOString().slice(0, 10)}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('审计记录已导出')
  } catch {
    ElMessage.error('导出失败，请查看后端日志')
  } finally {
    exporting.value = false
  }
}

async function doRollback() {
  if (!logDetail.value || !logDetail.value.id) return
  try {
    await ElMessageBox.confirm(
      '确认删除该批次写入的全部数据行？此操作不可撤销。',
      '回滚确认',
      { type: 'warning', confirmButtonText: '确认回滚', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  rollbacking.value = true
  try {
    const res = await request<{ deletedRows: number; affectedBuildings: number }>({
      url: `/import/logs/${logDetail.value.id}/rollback`,
      method: 'post'
    })
    ElMessage.success(`已回滚：删除 ${res.deletedRows} 行，影响 ${res.affectedBuildings} 栋建筑`)
    logDetail.value = null
    await loadLogs()
  } finally {
    rollbacking.value = false
  }
}

async function loadLogs() {
  try {
    logs.value = await request<ImportLog[]>({
      url: '/import/logs',
      method: 'get',
      params: { limit: 30 }
    })
  } catch { /* 忽略 */ }
}

async function showLogDetail(id: number) {
  try {
    logDetail.value = await request<Record<string, unknown>>({
      url: `/import/logs/${id}`,
      method: 'get'
    })
    logDetailVisible.value = true
  } catch { /* 忽略 */ }
}

function pretty(v: unknown) {
  if (!v) return '（无）'
  try {
    const o = typeof v === 'string' ? JSON.parse(v) : v
    return JSON.stringify(o, null, 2)
  } catch {
    return String(v)
  }
}

function resetWizard() {
  step.value = 0
  uploadResult.value = null
  result.value = null
  selectedFile.value = null
  uploadRef.value?.clearFiles()
  Object.keys(mapping).forEach((k) => delete mapping[k])
}

onMounted(async () => {
  try {
    targetFields.value = await request<TargetField[]>({
      url: '/import/target-fields',
      method: 'get'
    })
  } catch { /* 忽略 */ }
  await loadLogs()
})
</script>

<style scoped>
/* ── 主工作区居中 ── */
.wizard-step { max-width: 980px; margin-left: auto; margin-right: auto; }

/* ── Stepper 配色：已完成薄荷绿对勾 / 当前深绿 / 未开始灰 ── */
:deep(.el-step__head.is-process) { color: #2c755c; border-color: #2c755c; }
:deep(.el-step__title.is-process) { color: #2c755c; font-weight: 600; }
:deep(.el-step__head.is-success) { color: #7fb98c; border-color: #7fb98c; }
:deep(.el-step__title.is-success) { color: #7fb98c; }
:deep(.el-step__head.is-wait) { color: #b8c2bd; border-color: #b8c2bd; }
:deep(.el-step__title.is-wait) { color: #b8c2bd; }

/* ── 上传框醒目 ── */
.uploader { max-width: 720px; margin: 0 auto; }
.uploader :deep(.el-upload-dragger) {
  border: 2px dashed #c6d9cd;
  border-radius: 16px;
  padding: 42px 24px;
  background: #fafcfb;
  transition: border-color 0.18s ease, background 0.18s ease;
}
.uploader :deep(.el-upload-dragger:hover) {
  border-color: #35866b;
  background: #f2f8f5;
}

/* ── 文件信息卡 ── */
.file-info {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--carbon-border, #e3e9e4);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--shadow-sm);
}
.file-icon { font-size: 26px; color: #35866b; flex-shrink: 0; }
.file-name { font-size: 14px; font-weight: 600; color: var(--carbon-text, #253d35); }
.file-sub { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12px; color: var(--carbon-text-dim, #5d6e66); margin-top: 3px; }
.file-badge { padding: 0 6px; border-radius: 4px; background: #eef3f0; color: #2f7a61; font-size: 11px; }
.parse-ok { display: inline-flex; align-items: center; gap: 5px; color: #2f7a61; }
.parse-dot { width: 7px; height: 7px; border-radius: 50%; background: #4f9d77; }

/* ── 清洗规则卡片 ── */
.rule-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.rule-card {
  padding: 16px;
  border: 1px solid var(--carbon-border, #e3e9e4);
  border-radius: 14px;
  background: #fff;
  box-shadow: var(--shadow-xs);
}
.rule-head { font-size: 13px; font-weight: 600; color: var(--carbon-text, #253d35); margin-bottom: 10px; }
.rule-switch { display: flex; align-items: center; justify-content: space-between; font-size: 13px; color: var(--carbon-text-regular, #46584f); }
.rule-conseq { margin-top: 10px; font-size: 12px; line-height: 1.7; color: var(--carbon-text-mute, #7b8b84); }

/* ── 导入进度条 ── */
.import-progress { margin-bottom: 14px; padding: 12px 14px; border-radius: 12px; background: #f6f9f7; border: 1px solid #e4ece7; }
.import-progress .text-dim { display: block; margin-top: 6px; font-size: 12px; }

/* ── KPI 语义色 ── */
.kpi-card { border-radius: 14px; }
.kpi-value.ok { color: #4f9d77; }
.kpi-card.risk .kpi-value { color: #c26b62; }

.page-title-row,
.chart-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.uploader { max-width: 700px; }
.step-actions {
  margin-top: 18px;
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}
.ml8 { margin-left: 8px; font-size: 12px; }
.json-pre {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

@media (max-width: 760px) {
  .rule-grid { grid-template-columns: 1fr; }
}

.page-title-row,
.chart-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.uploader { max-width: 700px; }
.step-actions {
  margin-top: 18px;
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}
.ml8 { margin-left: 8px; font-size: 12px; }
.json-pre {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, Menlo, Consolas, monospace;
}
</style>
