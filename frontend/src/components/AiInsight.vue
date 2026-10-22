<template>
  <div class="chart-box ai-insight-box">
    <div class="chart-title ai-insight-title">
      <span>
        {{ title }}
        <el-tag v-if="insight" size="small"
                :type="insight.mode === 'llm' ? 'success' : 'info'"
                effect="plain" class="ml-8">
          {{ insight.mode === 'llm' ? `${insight.provider} / ${insight.model}` : '规则模式' }}
        </el-tag>
        <el-tag v-if="insight?.cached" size="small" type="info" effect="plain" class="ml-8">
          缓存命中
        </el-tag>
      </span>
      <el-button size="small" text type="primary" :loading="loading" @click="load(true)">
        {{ loading ? '生成中…' : '重新生成' }}
      </el-button>
    </div>

    <div v-loading="loading" class="ai-insight-body">
      <template v-if="insight">
        <p v-for="(para, i) in paragraphs" :key="i" class="ai-insight-para">{{ para }}</p>

        <!-- 异常建筑清单：由前端渲染成可点击标签。
             不让模型输出链接，是因为模型可能拼错建筑名，而这里的数据直接来自 SQL。 -->
        <div v-if="insight.focusBuildings?.length" class="ai-focus">
          <span class="ai-focus-label">重点关注：</span>
          <el-tag v-for="b in insight.focusBuildings" :key="b.buildingId"
                  size="small" type="warning" effect="plain" class="ai-focus-tag"
                  @click="emit('focus-building', b.buildingId)">
            {{ b.buildingId }}
            <span class="ai-focus-hours">{{ b.anomalyHours }}h</span>
          </el-tag>
        </div>

        <div v-if="showMeta" class="ai-insight-meta">
          生成于 {{ insight.generatedAt }}　·　提示词 {{ insight.promptChars }} 字
          　·　仅传输聚合指标，不含原始明细
          　·　缓存 {{ Math.round((insight.ttlSeconds || 600) / 60) }} 分钟
        </div>
      </template>
      <div v-else-if="!loading" class="ai-insight-empty">{{ emptyHint }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * AI 文字解读卡片（可复用）。
 *
 * <p>三个页面共用：
 * <ul>
 *   <li>监控看板：scope="dashboard"</li>
 *   <li>3D 园区：scope="campus" + siteId</li>
 *   <li>异常诊断：scope="diagnosis" + buildingId</li>
 * </ul>
 *
 * <p>成本控制：请求只带筛选条件，后端用 SQL 聚合后拼出 200~400 字提示词，
 * 相同条件 10 分钟内走缓存，不重复产生 token 费用。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from '@/api/request'
import { useAppStore } from '@/stores/app'

const props = withDefaults(defineProps<{
  scope?: 'dashboard' | 'campus' | 'diagnosis'
  siteId?: string
  buildingId?: string
  startDate?: string
  endDate?: string
  title?: string
  showMeta?: boolean
  /** 是否挂载即自动加载 */
  auto?: boolean
}>(), {
  scope: 'dashboard',
  siteId: '',
  buildingId: '',
  startDate: '',
  endDate: '',
  title: 'AI 看板解读',
  showMeta: true,
  auto: true
})

const emit = defineEmits<{
  (e: 'focus-building', buildingId: string): void
}>()

interface InsightVO {
  text: string
  mode: string
  cached: boolean
  provider: string
  model: string
  promptChars: number
  generatedAt: string
  ttlSeconds?: number
  focusBuildings?: Array<{
    buildingId: string
    buildingTypeCn?: string
    anomalyHours: number
    energyGrade?: string
  }>
}

const app = useAppStore()
const insight = ref<InsightVO | null>(null)
const loading = ref(false)

const paragraphs = computed(() =>
  (insight.value?.text || '')
    .split(/\n{2,}|\n/)
    .map((t: string) => t.trim())
    .filter(Boolean)
)

/**
 * 缺少必要上下文时不请求。
 * campus 必须选园区、diagnosis 必须选建筑，否则取到的是全局数据，
 * 会得出与页面不符的结论（实测：3D 页未选园区时解读却说「456 栋建筑」）。
 */
const emptyHint = computed(() => {
  if (props.scope === 'campus' && !props.siteId) return '请先在左侧选择一个园区'
  if (props.scope === 'diagnosis' && !props.buildingId) return '请先选择一栋建筑进行诊断'
  return '暂无解读'
})

function ready(): boolean {
  if (props.scope === 'campus') return !!props.siteId
  if (props.scope === 'diagnosis') return !!props.buildingId
  return true
}

async function load(force = false) {
  if (!ready() || loading.value) return
  loading.value = true
  try {
    const res = await request<InsightVO>({
      url: '/ai/insight',
      method: 'post',
      data: {
        scope: props.scope,
        startDate: props.startDate || app.startDate,
        endDate: props.endDate || app.endDate,
        siteId: props.siteId || undefined,
        buildingId: props.buildingId || undefined
      }
    })
    insight.value = res
    if (force) ElMessage.success('解读已更新')
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

/** 数据版本变化（导入新数据后）自动重新解读 */
watch(() => app.dataVersion, () => { void load() })
/** 筛选条件变化时重新解读 */
watch(() => [props.siteId, props.buildingId, props.startDate, props.endDate],
  () => { void load() })

onMounted(() => {
  if (props.auto) void load()
})

defineExpose({ load })
</script>

<style scoped>
.ai-insight-box {
  grid-column: 1 / -1;
  margin-top: 0;
}
.ai-insight-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ml-8 {
  margin-left: 8px;
}
.ai-insight-body {
  min-height: 90px;
  padding: 4px 2px;
}
.ai-insight-para {
  margin: 0 0 12px;
  line-height: 1.9;
  font-size: 14px;
  color: #2c3e50;
  text-indent: 2em;
}
.ai-focus {
  margin: 4px 0 10px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}
.ai-focus-label {
  font-size: 13px;
  color: #909399;
  margin-right: 6px;
}
.ai-focus-tag {
  cursor: pointer;
  margin: 0 6px 6px 0;
}
.ai-focus-tag:hover {
  opacity: 0.8;
}
.ai-focus-hours {
  margin-left: 4px;
  opacity: 0.75;
}
.ai-insight-meta {
  font-size: 12px;
  color: #909399;
  border-top: 1px dashed #ebeef5;
  padding-top: 8px;
}
.ai-insight-empty {
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
}
</style>
