<template>
  <div>
    <div class="page-card mb-12">
      <div class="page-title">数据导入与治理 · 五步向导</div>
      <el-steps :active="step" finish-status="success" align-center>
        <el-step title="数据源选择" description="上传 CSV / Excel" />
        <el-step title="字段映射" description="自动识别 + 人工确认" />
        <el-step title="清洗规则" description="缺失/负值/去重" />
        <el-step title="预览导入" description="先预览后落库" />
        <el-step title="审计留痕" description="批次可追溯" />
      </el-steps>
    </div>

    <!-- ============ 步骤 1：数据源选择 ============ -->
    <div v-show="step === 0" class="page-card">
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
        <el-alert type="success" :closable="false" show-icon>
          <template #title>
            已解析 <b>{{ uploadResult.fileName }}</b>：
            {{ uploadResult.totalRows }} 行数据、{{ uploadResult.columns?.length }} 列
          </template>
        </el-alert>

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
    <div v-show="step === 1" class="page-card">
      <div class="page-title">步骤二 · 字段映射</div>
      <el-alert type="info" :closable="false" show-icon class="mb-12"
                title="系统已根据列名与数据特征自动匹配目标字段，请确认或调整。标记「必填」的字段必须映射。" />

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
        <el-button type="primary" @click="step = 2">下一步：清洗规则</el-button>
      </div>
    </div>

    <!-- ============ 步骤 3：清洗规则 ============ -->
    <div v-show="step === 2" class="page-card">
      <div class="page-title">步骤三 · 清洗规则</div>

      <el-form :model="cleanRules" label-width="150px" style="max-width: 760px">
        <el-form-item label="去除首尾空格">
          <el-switch v-model="cleanRules.trimString" />
          <span class="ml8 text-dim">建议开启，避免「 建筑A」与「建筑A」被当作不同建筑</span>
        </el-form-item>

        <el-form-item label="剔除负值能耗">
          <el-switch v-model="cleanRules.dropNegative" />
          <span class="ml8 text-dim">能耗不可能为负，负值通常为表计或采集异常</span>
        </el-form-item>

        <el-form-item label="缺失值处理">
          <el-radio-group v-model="cleanRules.missingStrategy">
            <el-radio value="none">保留为空</el-radio>
            <el-radio value="zero">填 0</el-radio>
            <el-radio value="drop">整行剔除</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="建筑编号前缀">
          <el-input v-model="cleanRules.buildingIdPrefix" style="width: 330px"
                    placeholder="例如 Demo（源数据无园区前缀时自动补齐）" />
        </el-form-item>

        <el-form-item label="电量单位">
          <el-radio-group v-model="cleanRules.electricityUnit">
            <el-radio value="kwh">kWh</el-radio>
            <el-radio value="mwh">MWh（自动 ×1000）</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="去重策略">
          <el-radio-group v-model="cleanRules.dedupStrategy">
            <el-radio value="byBuildingTime">按「建筑+时间」去重（推荐）</el-radio>
            <el-radio value="none">不去重</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <div class="step-actions">
        <el-button @click="step = 1">上一步</el-button>
        <el-button type="primary" :loading="loading" @click="doPreview">下一步：预览导入</el-button>
      </div>
    </div>

    <!-- ============ 步骤 4：预览导入 ============ -->
    <div v-show="step === 3" class="page-card">
      <div class="page-title">步骤四 · 预览导入（尚未写入数据库）</div>

      <div v-if="result" class="kpi-row">
        <div class="kpi-card">
          <div class="kpi-label">源数据行数</div>
          <div class="kpi-value">{{ result.totalRows }}</div>
        </div>
        <div class="kpi-card green">
          <div class="kpi-label">可导入</div>
          <div class="kpi-value">{{ result.successRows }}</div>
        </div>
        <div class="kpi-card red">
          <div class="kpi-label">解析失败</div>
          <div class="kpi-value">{{ result.failedRows }}</div>
        </div>
        <div class="kpi-card orange">
          <div class="kpi-label">按规则跳过</div>
          <div class="kpi-value">{{ result.skippedRows }}</div>
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

      <div class="step-actions">
        <el-button @click="step = 2">上一步</el-button>
        <el-button type="primary" :loading="loading"
                   :disabled="!result || result.successRows === 0" @click="doImport">
          确认导入（写入数据库）
        </el-button>
      </div>
    </div>

    <!-- ============ 步骤 5：审计留痕 ============ -->
    <div v-show="step === 4" class="page-card">
      <div class="page-title">步骤五 · 导入结果与审计留痕</div>

      <el-result v-if="result" :icon="result.status === '成功' ? 'success' : 'warning'"
                 :title="`导入${result.status}`"
                 :sub-title="`批次号 ${result.batchNo} · 成功 ${result.successRows} 行 · 失败 ${result.failedRows} 行 · 跳过 ${result.skippedRows} 行`" />

      <el-alert type="info" :closable="false" show-icon class="mb-12"
                title="导入数据默认标记为 B 级（站点分摊）且 anomaly_flag = -1（未评估），因其非本系统标准计量口径；如需纳入异常分析请在数据治理流程中另行确认。" />

      <div class="chart-box">
        <div class="chart-title">历史导入批次（审计留痕）</div>
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
        <el-descriptions-item label="操作人">{{ logDetail.operator }}</el-descriptions-item>
        <el-descriptions-item label="时间">{{ logDetail.createdAt }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
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
    step.value = 4
    await loadLogs()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
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
