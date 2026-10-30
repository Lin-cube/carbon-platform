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

    <div class="page-card chat-card">
      <!-- ══════════ 空对话欢迎页 ══════════ -->
      <div v-if="messages.length === 0" class="welcome">
        <div class="welcome-logo">
          <el-icon :size="34"><MagicStick /></el-icon>
        </div>
        <h1 class="welcome-title">你好，我是小E</h1>
        <p class="welcome-sub">你的建筑能源智能分析助手</p>

        <div class="cap-grid">
          <button v-for="c in capabilities" :key="c.label" type="button" class="cap-card"
                  @click="askExample(c.q)">
            <span class="cap-icon"><el-icon :size="20"><component :is="c.icon" /></el-icon></span>
            <span class="cap-label">{{ c.label }}</span>
            <span class="cap-desc">{{ c.desc }}</span>
          </button>
        </div>
      </div>

      <!-- ══════════ 对话区 ══════════ -->
      <template v-else>
        <div ref="listEl" class="chat-list">
          <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
            <div class="avatar" :class="m.role">
              <el-icon v-if="m.role === 'user'"><User /></el-icon>
              <el-icon v-else><MagicStick /></el-icon>
            </div>

            <div class="bubble" :class="[m.role, { failed: !!m.error }]">
              <!-- 用户 / 助手文本（纯文本，保留换行） -->
              <div class="bubble-text">{{ m.text }}</div>

              <!-- 助手结构化信息 -->
              <template v-if="m.res">
                <!-- 工具执行状态卡 -->
                <div v-if="m.res.toolCalls?.length" class="tool-card">
                  <div class="meta-label">执行工具</div>
                  <div v-for="(t, ti) in m.res.toolCalls" :key="ti" class="tool-row">
                    <span class="tool-dot" :class="t.success === false ? 'fail' : 'ok'"></span>
                    <span class="tool-name mono">{{ t.tool }}</span>
                    <span class="tool-status" :class="t.success === false ? 'fail' : 'ok'">
                      {{ t.success === false ? '失败' : '成功' }}
                    </span>
                    <span v-if="t.elapsedMs != null" class="tool-ms">{{ t.elapsedMs }}ms</span>
                  </div>
                </div>

                <!-- 数据证据卡 -->
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

                <!-- 知识库引用 -->
                <el-collapse v-if="m.res.citations?.length" class="meta-collapse">
                  <el-collapse-item name="cite">
                    <template #title>
                      <span class="meta-label">知识库引用（{{ m.res.citations.length }} 条）</span>
                    </template>
                    <div v-for="(c, ci) in m.res.citations" :key="ci" class="cite-item">
                      <div class="cite-src">
                        <a v-if="c.url" class="cite-link" :href="c.url"
                           target="_blank" rel="noopener noreferrer">{{ c.source }}</a>
                        <template v-else>{{ c.source }}</template>
                        <span v-if="c.page != null" class="text-dim">· 第 {{ c.page }} 页</span>
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

              <!-- 失败重试 -->
              <div v-if="m.error" class="retry-row">
                <el-button link type="primary" size="small" @click="retryLast">重试</el-button>
              </div>

              <!-- 复制（仅助手消息） -->
              <button v-if="m.role === 'assistant'" class="copy-btn" type="button"
                      :title="'复制回答'" @click="copyText(m.text)">
                <el-icon :size="13"><DocumentCopy /></el-icon>
              </button>
            </div>
          </div>
        </div>

        <!-- 示例问题（对话开始后显示） -->
        <div class="examples">
          <span class="text-dim" style="font-size: 12px">示例问题</span>
          <el-tag v-for="(q, i) in examples" :key="i" class="example-tag" effect="plain"
                  @click="pickExample(q)">
            {{ q }}
          </el-tag>
        </div>
      </template>

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
        <span v-if="!ctxBuildingId && !ctxRange" class="text-dim" style="font-size: 12px">
          不选则由后端按问题文本自动解析
        </span>
      </div>

      <!-- 当前上下文小标签 -->
      <div v-if="ctxBuildingId || ctxRange" class="ctx-tags">
        <el-tag v-if="ctxBuildingId" size="small" closable @close="ctxBuildingId = ''">
          🏢 {{ ctxBuildingId }}
        </el-tag>
        <el-tag v-if="ctxRange" size="small" closable @close="ctxRange = null">
          📅 {{ ctxRange[0] }} ~ {{ ctxRange[1] }}
        </el-tag>
      </div>

      <!-- 输入框：浅色玻璃卡片，固定于对话区底部 -->
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
import { Delete, Promotion, DocumentCopy } from '@element-plus/icons-vue'
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
/** 上一次提问（用于失败重试） */
const lastQuestion = ref('')

const aiStatus = ref<'loading' | 'up' | 'down'>('loading')
const aiMode = ref('')
const healthMessage = ref('正在检测 AI 服务…')

const ctxBuildingId = ref('')
const ctxRange = ref<[string, string] | null>([app.startDate, app.endDate])

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

/** 欢迎页四张能力卡片 */
const capabilities = [
  { label: '能耗查询', icon: 'Search', desc: '按园区/时段汇总用电与碳排', q: '统计Eagle园区2016下半年的用电情况' },
  { label: 'COP 分析', icon: 'Odometer', desc: '机组能效与供回水温差', q: 'Hog_office_Sydney 的机组能效如何？' },
  { label: '异常诊断', icon: 'Warning', desc: '扫描异常事件与证据链', q: '2016年有哪些能耗异常？' },
  { label: '节能建议', icon: 'TrendCharts', desc: '数据驱动的优化措施', q: 'Hog_office_Sydney 有哪些可落地的优化建议？' }
]

const statusText = computed(() => ({
  loading: 'AI 服务检测中…',
  up: 'AI 服务可用',
  down: 'AI 服务不可用'
})[aiStatus.value])

const canSend = computed(() => !!question.value.trim() && !sending.value)

// ---------------------------------------------------------------- 展示辅助
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
  const hit = buildings.value.find((b) => q.includes(b.buildingId))
  if (hit) ctxBuildingId.value = hit.buildingId
}

/** 能力卡片：沿用示例问题交互（填充 + 设置上下文）后立即发送 */
function askExample(q: string) {
  pickExample(q)
  send()
}

function clearChat() {
  messages.value = []
  sessionId.value = uuid()
  question.value = ''
  lastQuestion.value = ''
}

/** 失败重试：重发上一次提问 */
function retryLast() {
  if (!lastQuestion.value) return
  question.value = lastQuestion.value
  send()
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
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
  lastQuestion.value = q
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
    push({ role: 'assistant', text: '请求失败：后端未能返回结果，请检查服务状态或稍后重试。', error: 'failed' })
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

onMounted(async () => {
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
.status-pill.up { background: #e4f2ea; color: #2f7a61; }
.status-pill.up .dot { background: #4f9d77; }
.status-pill.down { background: #f9ece9; color: #9b564e; }
.status-pill.down .dot { background: #c26b62; }
.ml-auto { margin-left: auto; }

/* ══════════ 欢迎页 ══════════ */
.welcome {
  text-align: center;
  padding: 40px 16px 28px;
}
.welcome-logo {
  width: 72px;
  height: 72px;
  margin: 0 auto 16px;
  border-radius: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(135deg, #35866b, #5b8fa8);
  box-shadow: 0 10px 26px -12px rgba(53, 134, 107, 0.5);
}
.welcome-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--carbon-text, #253d35);
}
.welcome-sub {
  margin: 8px 0 26px;
  font-size: 14px;
  color: var(--carbon-text-dim, #5d6e66);
}

.cap-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  max-width: 720px;
  margin: 0 auto;
}
.cap-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 18px 12px;
  border: 1px solid var(--carbon-border, #e3e9e4);
  border-radius: 14px;
  background: #ffffff;
  cursor: pointer;
  font-family: inherit;
  transition: transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s ease;
}
.cap-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: rgba(53, 134, 107, 0.4);
}
.cap-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--carbon-primary-soft, #ebf3f0);
  color: var(--carbon-primary, #35866b);
}
.cap-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--carbon-text, #253d35);
}
.cap-desc {
  font-size: 11.5px;
  color: var(--carbon-text-mute, #7b8b84);
  line-height: 1.5;
}

/* ══════════ 聊天区 ══════════ */
.chat-list {
  height: calc(100vh - 420px);
  min-height: 320px;
  overflow-y: auto;
  padding: 6px 4px 12px;
  max-width: 820px;
  margin: 0 auto;
  background: #fafbfd;
  border-radius: 10px;
}

.msg { display: flex; gap: 10px; margin-bottom: 14px; max-width: 820px; margin-left: auto; margin-right: auto; }
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
.avatar.assistant { background: #35866b; }
.avatar.user { background: #2a9d8f; }

.bubble {
  position: relative;
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 13.5px;
  line-height: 1.75;
  background: #fff;
  border: 1px solid var(--carbon-border, #e3e9e4);
  box-shadow: 0 1px 2px rgba(37, 61, 53, 0.04);
}
/* 用户消息：淡绿底 */
.bubble.user {
  background: #e6f4ec;
  border-color: #cde7d8;
  color: #2f5d4a;
}
.bubble.failed { border-color: #e7c4c0; background: #fbf0ee; }

.bubble-text { white-space: pre-wrap; word-break: break-word; }

/* 复制按钮 */
.copy-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--carbon-text-mute, #7b8b84);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.16s ease, background 0.16s ease;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.bubble:hover .copy-btn { opacity: 1; }
.copy-btn:hover { background: var(--carbon-primary-soft, #ebf3f0); color: var(--carbon-primary, #35866b); }

/* 工具执行状态卡 */
.tool-card {
  margin-top: 10px;
  padding: 9px 11px;
  border-radius: 10px;
  background: #f6f9f7;
  border: 1px solid #e4ece7;
}
.tool-row {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 3px 0;
  font-size: 12px;
}
.tool-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.tool-dot.ok { background: #4f9d77; }
.tool-dot.fail { background: #c26b62; }
.tool-name { font-weight: 600; color: #253d35; }
.tool-status.ok { color: #2f7a61; }
.tool-status.fail { color: #9b564e; }
.tool-ms { color: #7b8b84; }

.meta-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 10px; }
.meta-label { font-size: 12px; color: #5d6e66; font-weight: 600; }
.tool-tag { font-family: ui-monospace, Menlo, Consolas, monospace; }

.meta-collapse { margin-top: 6px; border-top: 1px dashed var(--carbon-border, #e3e9e4); }
.meta-collapse :deep(.el-collapse-item__header) { height: 32px; line-height: 32px; border-bottom: none; background: transparent; }
.meta-collapse :deep(.el-collapse-item__wrap) { border-bottom: none; background: transparent; }
.meta-collapse :deep(.el-collapse-item__content) { padding-bottom: 6px; }

.kv-row { display: flex; gap: 10px; font-size: 12px; line-height: 1.8; }
.kv-key { flex: 0 0 150px; color: #5d6e66; }
.kv-val { flex: 1; color: #253d35; word-break: break-all; }

.cite-item { padding: 6px 0; border-bottom: 1px dashed #eef1f5; }
.cite-item:last-child { border-bottom: none; }
.cite-src { font-size: 12px; font-weight: 600; color: #374151; }
.cite-link { color: #35866b; text-decoration: none; word-break: break-all; }
.cite-link:hover { text-decoration: underline; }
.cite-snippet { font-size: 12px; color: #5d6e66; line-height: 1.7; }

.footer-row {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--carbon-border, #e3e9e4);
  font-size: 11.5px;
  color: #5d6e66;
}
.retry-row { margin-top: 8px; }

.examples {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
  max-width: 820px;
  margin-left: auto;
  margin-right: auto;
}
.example-tag { cursor: pointer; }
.example-tag:hover { color: #35866b; border-color: #35866b; }

.context-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 12px;
  max-width: 820px;
  margin-left: auto;
  margin-right: auto;
}

.ctx-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
  max-width: 820px;
  margin-left: auto;
  margin-right: auto;
}

/* 输入框：浅色玻璃卡片 */
.input-bar {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid var(--carbon-border, #e3e9e4);
  box-shadow: 0 8px 24px -14px rgba(37, 61, 53, 0.28);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  max-width: 820px;
  margin-left: auto;
  margin-right: auto;
}
.input-bar :deep(.el-textarea__inner) {
  max-height: 120px;
  overflow-y: auto;
  background: transparent;
  box-shadow: none;
}
.send-btn { height: 54px; width: 96px; }

@media (max-width: 900px) {
  .cap-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (prefers-reduced-motion: reduce) {
  .cap-card, .copy-btn { transition: none; }
}
</style>
