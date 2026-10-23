<template>
  <el-dialog
    :model-value="modelValue"
    width="680px"
    align-center
    @update:model-value="emit('update:modelValue', $event)"
    @open="load(false)"
  >
    <template #header>
      <div class="bap-head">
        <span class="bap-title">
          <el-icon><MagicStick /></el-icon>
          AI 建筑分析
        </span>
        <span class="bap-sub">
          {{ buildingId }}<template v-if="siteId"> · {{ siteId }}</template>
        </span>
      </div>
    </template>

    <div class="bap-body">
      <div class="bap-range">
        <span class="bap-range-label">统计区间</span>
        <span class="bap-range-val">{{ startDate }} 至 {{ endDate }}</span>
      </div>

      <div v-if="loading" class="bap-loading">
        <el-icon class="is-loading" :size="18"><Loading /></el-icon>
        <span>正在基于该建筑的真实数据生成分析…</span>
      </div>

      <template v-else-if="insight">
        <div class="bap-text">
          <p v-for="(para, i) in paragraphs" :key="i">{{ para }}</p>
        </div>

        <div class="bap-meta">
          <span class="bap-mode" :class="insight.mode">{{ modeLabel }}</span>
          <span>提示词 {{ insight.promptChars }} 字</span>
          <span v-if="insight.cached">缓存命中</span>
          <span>{{ insight.provider }} / {{ insight.model }}</span>
        </div>
      </template>

      <div v-else class="bap-empty">
        暂时无法生成分析（后端未返回结果），请稍后重试。
      </div>
    </div>

    <template #footer>
      <div class="bap-foot">
        <div class="bap-note">
          分析只使用该建筑的聚合指标（电量 / 温度 / 湿度 / 异常小时 / 面积 / 评级），
          不发送原始明细；数字与日期均经一致性校验，不合规会自动降级为规则版并标注。
        </div>
        <div class="bap-actions">
          <el-button size="small" :loading="loading" @click="load('refresh')">重新生成</el-button>
          <el-button size="small" type="primary" @click="goAssistant">去小E助手追问</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 单栋建筑 AI 分析面板。
 *
 * <p>【为什么用 /ai/insight 而不是 /ai/chat】
 * 查证后确认：/ai/chat 的数据类问题走的是后端本地规则引擎（SQL 结果填模板），
 * 而且 ChatRequest.buildingId 字段虽然声明了却从未被使用——
 * 也就是说用它问「这栋楼怎么样」，回答其实是全库统计，跟这栋楼无关。
 *
 * <p>/ai/insight?scope=diagnosis 才是真正的「LLM + 该建筑真实聚合数据」：
 * 带建筑过滤、带证据链、数字有校验。所以单栋深度分析用这个；
 * 需要自由追问（知识类问题、RAG 检索）时跳转到小E 助手页。
 *
 * <p>页面上展示的所有内容都来自后端返回，前端不做任何数据拼接。
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Loading, MagicStick } from '@element-plus/icons-vue'
import { request } from '@/api/request'

/** /api/ai/insight 的返回结构（与 AiInsight.vue 保持一致） */
interface InsightVO {
  text: string
  mode: string
  cached: boolean
  provider: string
  model: string
  promptChars: number
  generatedAt: string
}

const props = defineProps<{
  modelValue: boolean
  buildingId: string
  siteId?: string
  startDate: string
  endDate: string
}>()

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
const router = useRouter()

const loading = ref(false)
const insight = ref<InsightVO | null>(null)

/** 按空行拆段，前端只做排版，不改内容 */
const paragraphs = computed(() => {
  const t = insight.value?.text || ''
  return t.split(/\n+/).map((s) => s.trim()).filter(Boolean)
})

const modeLabel = computed(() => {
  const m = insight.value?.mode
  if (m === 'llm') return '大模型生成'
  if (m === 'rule-fallback') return '规则兜底（数字/日期校验未通过）'
  if (m === 'rule') return '规则版'
  return m || '--'
})

async function load(force: boolean | 'refresh') {
  if (!props.buildingId) return
  loading.value = true
  try {
    const res = await request<InsightVO>({
      url: '/ai/insight',
      method: 'post',
      data: {
        scope: 'diagnosis',
        startDate: props.startDate,
        endDate: props.endDate,
        buildingId: props.buildingId,
        // 首次打开展示缓存（快）；点「重新生成」才真正清缓存重跑
        refresh: force === 'refresh' ? '1' : undefined,
      },
    })
    insight.value = res
  } catch {
    insight.value = null
  } finally {
    loading.value = false
  }
}

function goAssistant() {
  emit('update:modelValue', false)
  router.push({ path: '/assistant', query: { buildingId: props.buildingId } })
}

watch(() => props.buildingId, () => {
  if (props.modelValue) void load(false)
})
</script>

<style scoped>
.bap-head {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.bap-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: #253d35;
}
.bap-sub {
  font-size: 12px;
  color: #7b8b84;
}

.bap-body {
  max-height: 56vh;
  overflow-y: auto;
}

.bap-range {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 11px;
  margin-bottom: 12px;
  border-radius: 9px;
  background: rgba(53, 134, 107, 0.06);
  font-size: 12px;
}
.bap-range-label {
  color: #7b8b84;
}
.bap-range-val {
  color: #2f7a61;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.bap-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 0;
  font-size: 12.5px;
  color: #7b8b84;
}

.bap-text p {
  margin: 0 0 11px;
  font-size: 13px;
  line-height: 1.92;
  color: #253d35;
  text-align: justify;
}
.bap-text p:last-child {
  margin-bottom: 0;
}

.bap-meta {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid rgba(37, 61, 53, 0.07);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 10.5px;
  color: #93a49c;
}
.bap-mode {
  padding: 1px 7px;
  border-radius: 5px;
  background: rgba(53, 134, 107, 0.1);
  color: #2f7a61;
}
.bap-mode.rule-fallback,
.bap-mode.rule {
  background: rgba(180, 87, 76, 0.1);
  color: #b4574c;
}

.bap-empty {
  padding: 36px 0;
  text-align: center;
  font-size: 12.5px;
  color: #93a49c;
}

.bap-foot {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}
.bap-note {
  flex: 1;
  font-size: 10.5px;
  line-height: 1.65;
  color: #93a49c;
  text-align: left;
}
.bap-actions {
  flex-shrink: 0;
  display: flex;
  gap: 8px;
}
</style>
