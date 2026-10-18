<template>
  <div>
    <!-- AI 服务状态条 -->
    <div class="page-card mb-12">
      <div class="filter-bar" style="margin-bottom: 0">
        <span class="status-pill" :class="aiStatus">
          <span class="dot"></span>
          {{ statusText }}
        </span>
        <el-tag v-if="aiMode" size="small" effect="plain" :type="aiMode === 'mock' ? 'warning' : 'success'">
          模式 {{ aiMode }}
        </el-tag>
        <span class="text-dim" style="font-size: 12px">{{ healthMessage }}</span>
        <el-button class="ml-auto" size="small" :icon="Delete" @click="clearChat">清空对话</el-button>
      </div>

      <!-- mock 模式说明：本地联调模式，答案同样由后端真实统计计算得出 -->
      <el-alert v-if="aiMode === 'mock'" type="info" closable show-icon class="mt-12"
                title="当前为本地联调模式（carbon.ai.mode = mock）">
        <div style="font-size: 12px; line-height: 1.8">
          该模式下小E 的答案<b>不是预设话术</b>：它由后端真实调用 MCP 工具（能耗查询 / 时段汇总 /
          异常扫描 / COP 计算 / 问题诊断 / 优化建议）读取数据库统计结果后生成，只是没有经过
          RAG 知识库检索与大模型润色。<br />
          接入角色 B 的 RAGFlow / FastAPI 服务时，只需把配置项 <span class="mono">carbon.ai.mode</span>
          改为 <span class="mono">http</span>，本页面无需改动。
        </div>
      </el-alert>
    </div>

    <div class="page-card">
      <!-- 可滚动消息区 -->
      <div ref="listEl" class="chat-list">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="avatar" :class="m.role">
            <el-icon v-if="m.role === 'user'"><User /></el-icon>
            <el-icon v-else><MagicStick /></el-icon>
          </div>

          <div class="bubble" :class="[m.role, { failed: !!m.error }]">
            <!-- 用户消息 / 纯文本 -->
            <div class="bubble-text">{{ m.text }}</div>

            <!-- 助手结构化信息 -->
            <template v-if="m.res">
              <div v-if="m.res.toolCalls?.length" class="meta-row">
                <span class="meta-label">调用工具</span>
                <el-tag v-for="(t, ti) in m.res.toolCalls" :key="ti" size="small" effect="plain"
                        :type="t.success ? 'success' : 'danger'" class="tool-tag">
                  {{ t.tool }}
                </el-tag>
              </div>

              <el-collapse v-if="hasEvidence(m.res)" class="meta-collapse">
                <el-collapse-item name="ev">
                  <template #title>
                    <span class="meta-label">数据证据（{{ evidenceRows(m.res).length }} 项）</span>
                  </template>
                  <div v-for="e in evidenceRows(m.res)" :key="e.key" class="kv-row">
                    <span class="kv-key mono">{{ e.key }}</span>
                    <span class="kv-val mono">{{ e.value }}</span>
                  </div>
                </el-collapse-item>
              </el-collapse>

              <el-collapse v-if="m.res.citations?.length" class="meta-collapse">
                <el-collapse-item name="cite">
                  <template #title>
                    <span class="meta-label">知识库引用（{{ m.res.citations.length }} 条）</span>
                  </template>
                  <div v-for="(c, ci) in m.res.citations" :key="ci" class="cite-item">
                    <div class="cite-src">
                      {{ c.source }}
                      <span class="text-dim">· 第 {{ c.page }} 页</span>
                    </div>
                    <div class="cite-snippet">{{ c.snippet }}</div>
                  </div>
                </el-collapse-item>
              </el-collapse>

              <div class="footer-row">
                <span v-if="m.res.confidence != null">
                  置信度 <b>{{ fmtConfidence(m.res.confidence) }}</b>
                </span>
                <span v-if="m.res.elapsedMs != null">耗时 <b>{{ m.res.elapsedMs }}</b> ms</span>
                <span v-if="m.res.dataSource">数据来源 <b>{{ m.res.dataSource }}</b></span>
                <span class="text-dim">会话 {{ sessionId.slice(0, 8) }}</span>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- 示例问题 -->
      <div class="examples">
        <span class="text-dim" style="font-size: 12px">示例问题</span>
        <el-tag v-for="(q, i) in examples" :key="i" class="example-tag" effect="plain"
                @click="pickExample(q)">
          {{ q }}
        </el-tag>
      </div>

      <!-- 上下文选择 + 输入区 -->
      <div class="context-bar">
        <span class="text-dim">上下文</span>
        <el-select v-model="ctxBuildingId" placeholder="不限建筑" filterable clearable
                   style="width: 240px" size="small">
          <el-option v-for="b in buildings" :key="b.buildingId"
                     :label="`${b.buildingId}（${b.buildingTypeCn || '未分类'}）`" :value="b.buildingId" />
        </el-select>
        <el-date-picker v-model="ctxRange" type="daterange" value-format="YYYY-MM-DD" size="small"
                        start-placeholder="开始" end-placeholder="结束" style="width: 230px" />
        <span class="text-dim" style="font-size: 12px">
          不选则由后端按问题文本自动解析时间与建筑
        </span>
      </div>

      <div class="input-bar">
        <el-input v-model="question" type="textarea" :rows="2" resize="none"
                  placeholder="用中文提问，例如：2016年有哪些能耗异常？（Enter 发送，Shift+Enter 换行）"
                  @keydown.enter.exact.prevent="send" />
        <el-button type="primary" :icon="Promotion" :loading="sending" :disabled="!canSend"
                   class="send-btn" @click="send">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Promotion } from '@element-plus/icons-vue'
import { aiApi, energyApi } from '@/api'
import { useAppStore } from '@/stores/app'
import type { BuildingRow, ChatResponse } from '@/types/api'

const app = useAppStore()

interface ChatItem {
  role: 'user' | 'assistant'
  text: string
  res?: ChatResponse
  error?: string
}

const messages = ref<ChatItem[]>([])
const question = ref('')
const sending = ref(false)
const listEl = ref<HTMLDivElement>()
const buildings = ref<BuildingRow[]>([])

const aiStatus = ref<'loading' | 'up' | 'down'>('loading')
const aiMode = ref('')
const healthMessage = ref('正在检测 AI 服务…')

// 上下文（可选）
const ctxBuildingId = ref('')
const ctxRange = ref<[string, string] | null>([app.startDate, app.endDate])

/**
 * 会话 ID：每次页面加载生成一次，随每次提问发送，
 * 后端用它串联多轮上下文（本地 mock 模式下仅做日志与链路标识）。
 */
function uuid(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
const sessionId = ref(uuid())

const examples = [
  '2016年有哪些能耗异常？',
  '统计Eagle园区2016下半年的用电情况',
  'Hog_office_Sydney 的机组能效如何？',
  'Hog_office_Sydney 有哪些可落地的优化建议？',
  '2016年各园区的碳排放对比情况'
]

const statusText = computed(() => ({
  loading: 'AI 服务检测中…',
  up: 'AI 服务可用',
  down: 'AI 服务不可用'
})[aiStatus.value])

const canSend = computed(() => !!question.value.trim() && !sending.value)

// ---------------------------------------------------------------- 展示辅助
/** dataEvidence 是后端返回的键值对，统一转成可读文本渲染 */
function evidenceRows(res?: ChatResponse) {
  const ev = res?.dataEvidence || {}
  return Object.entries(ev).map(([key, value]) => ({
    key,
    value: typeof value === 'number'
      ? value.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
      : typeof value === 'object' ? JSON.stringify(value) : String(value)
  }))
}

function hasEvidence(res?: ChatResponse) {
  return evidenceRows(res).length > 0
}

/** 置信度后端以 0~1 小数返回，展示为百分比更直观 */
function fmtConfidence(v: number) {
  return v <= 1 ? `${(v * 100).toFixed(0)}%` : `${v}%`
}

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

function push(item: ChatItem) {
  messages.value.push(item)
  scrollToBottom()
}

function pickExample(q: string) {
  question.value = q
  // 问题里若显式提到某栋建筑，顺手把上下文建筑也选中，避免答非所问
  const hit = buildings.value.find((b) => q.includes(b.buildingId))
  if (hit) ctxBuildingId.value = hit.buildingId
}

function clearChat() {
  messages.value = []
  sessionId.value = uuid()
  question.value = ''
  greet()
}

function greet() {
  push({
    role: 'assistant',
    text: '你好，我是小E（建筑能源智能助手）。我可以查询能耗数据、做时段汇总与 COP 计算、'
      + '扫描异常事件、诊断问题并给出优化建议。\n'
      + '建议在下方选择建筑与时间范围后提问，答案会更精准。'
  })
}

// ---------------------------------------------------------------- 数据加载
async function loadStatus() {
  try {
    const [health, mode] = await Promise.all([aiApi.health(), aiApi.mode()])
    aiStatus.value = String(health?.status) === 'UP' ? 'up' : 'down'
    aiMode.value = (mode as string) || String(health?.mode || '')
    healthMessage.value = String(health?.message || '')
  } catch {
    aiStatus.value = 'down'
    aiMode.value = ''
    healthMessage.value = '无法连接后端 AI 接口，请确认服务已启动（/api/ai/health）'
  }
}

async function send() {
  const q = question.value.trim()
  if (!q || sending.value) return

  push({ role: 'user', text: q })
  question.value = ''
  sending.value = true
  try {
    const res = await aiApi.chat({
      question: q,
      sessionId: sessionId.value,
      buildingId: ctxBuildingId.value || undefined,
      startDate: ctxRange.value?.[0] || undefined,
      endDate: ctxRange.value?.[1] || undefined
    })
    if (res.ok) {
      push({ role: 'assistant', text: res.answer || '（空回答）', res })
    } else {
      push({
        role: 'assistant',
        text: res.error || '本次提问未能得到结果，请调整问题或稍后重试。',
        res,
        error: 'failed'
      })
      ElMessage.warning('本次提问返回失败状态')
    }
  } catch {
    // 拦截器已弹出错误提示，这里只补一条对话内的失败气泡
    push({ role: 'assistant', text: '请求失败：后端未能返回结果，请检查服务状态或稍后重试。', error: 'failed' })
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

onMounted(async () => {
  greet()
  try {
    buildings.value = await energyApi.buildings({ limit: 500 })
  } catch {
    /* 忽略 */
  }
  await loadStatus()
})
</script>

<style scoped>
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 12px;
  background: #f3f4f6;
  color: #6b7280;
}
.status-pill .dot { width: 8px; height: 8px; border-radius: 50%; background: #9ca3af; }
.status-pill.up { background: #dcfce7; color: #15803d; }
.status-pill.up .dot { background: #22c55e; }
.status-pill.down { background: #fee2e2; color: #b91c1c; }
.status-pill.down .dot { background: #ef4444; }
.ml-auto { margin-left: auto; }

.chat-list {
  height: calc(100vh - 400px);
  min-height: 320px;
  overflow-y: auto;
  padding: 4px 6px 8px;
  background: #fafbfd;
  border-radius: 6px;
}

.msg { display: flex; gap: 10px; margin-bottom: 14px; }
.msg.user { flex-direction: row-reverse; }

.avatar {
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 15px;
}
.avatar.assistant { background: #2f7dff; }
.avatar.user { background: #10b981; }

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13.5px;
  line-height: 1.75;
  background: #fff;
  border: 1px solid #e5e7eb;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}
.bubble.user { background: #2f7dff; border-color: #2f7dff; color: #fff; }
.bubble.failed { border-color: #fca5a5; background: #fef2f2; }

/* 保留后端回答里的换行，不合并成一段 */
.bubble-text { white-space: pre-wrap; word-break: break-word; }

.meta-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 10px; }
.meta-label { font-size: 12px; color: #6b7280; font-weight: 600; }
.tool-tag { font-family: ui-monospace, Menlo, Consolas, monospace; }

.meta-collapse { margin-top: 6px; border-top: 1px dashed #e5e7eb; }
.meta-collapse :deep(.el-collapse-item__header) { height: 32px; line-height: 32px; border-bottom: none; }
.meta-collapse :deep(.el-collapse-item__wrap) { border-bottom: none; }
.meta-collapse :deep(.el-collapse-item__content) { padding-bottom: 6px; }

.kv-row { display: flex; gap: 10px; font-size: 12px; line-height: 1.8; }
.kv-key { flex: 0 0 150px; color: #6b7280; }
.kv-val { flex: 1; color: #111827; word-break: break-all; }

.cite-item { padding: 6px 0; border-bottom: 1px dashed #eef1f5; }
.cite-item:last-child { border-bottom: none; }
.cite-src { font-size: 12px; font-weight: 600; color: #374151; }
.cite-snippet { font-size: 12px; color: #6b7280; line-height: 1.7; }

.footer-row {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #e5e7eb;
  font-size: 11.5px;
  color: #6b7280;
}

.examples { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.example-tag { cursor: pointer; }
.example-tag:hover { color: #2f7dff; border-color: #2f7dff; }

.context-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }

.input-bar { display: flex; gap: 10px; align-items: flex-end; margin-top: 10px; }
.send-btn { height: 54px; width: 96px; }
</style>
