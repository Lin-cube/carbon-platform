<template>
  <div class="campus-wrap">
    <!-- 左侧控制面板 -->
    <div class="overlay-panel left-panel" :class="{ collapsed: panelCollapsed }">
      <div class="panel-title">
        <span>3D 园区监控</span>
        <button v-if="quality" class="q-badge" type="button"
                :title="`渲染画质：${qualityLabel(quality)}（${quality.reason}）`">
          {{ qualityLabel(quality) }}
        </button>
        <button class="collapse-btn" type="button"
                :title="panelCollapsed ? '展开面板' : '收起面板'"
                @click="panelCollapsed = !panelCollapsed">
          <el-icon><component :is="panelCollapsed ? 'ArrowDown' : 'ArrowUp'" /></el-icon>
        </button>
      </div>

      <div class="field">
        <label>园区</label>
        <el-select v-model="siteId" size="small" style="width: 100%" @change="rebuild">
          <el-option v-for="s in sites" :key="s.siteId"
                     :label="`${s.siteId}（${s.buildingCount} 栋）`" :value="s.siteId" />
        </el-select>
      </div>

      <div class="field">
        <label>建筑着色</label>
        <el-radio-group v-model="colorBy" size="small" @change="applyColors">
          <el-radio-button value="grade">能耗评级</el-radio-button>
          <el-radio-button value="eui">能耗强度</el-radio-button>
          <el-radio-button value="anomaly">异常率</el-radio-button>
        </el-radio-group>

        <!-- 当前模式提示：说明这个颜色到底代表什么 -->
        <div class="mode-hint">
          <div class="mode-hint-head">
            当前：<b>{{ modeHint.title }}</b>
            <span class="mode-hint-unit">{{ modeHint.unit }}</span>
          </div>
          <p class="mode-hint-desc">{{ modeHint.desc }}</p>
        </div>
      </div>

      <div class="field">
        <label>按高度表示</label>
        <el-radio-group v-model="heightBy" size="small" @change="rebuild">
          <el-radio-button value="area">建筑面积</el-radio-button>
          <el-radio-button value="energy">用电量</el-radio-button>
        </el-radio-group>
      </div>

      <el-divider style="margin: 12px 0" />

      <div class="legend">
        <div class="legend-title">图例</div>
        <div v-for="l in legendItems" :key="l.label" class="legend-item">
          <span class="swatch" :style="{ background: l.color }"></span>
          <span class="legend-label">{{ l.label }}</span>
          <span class="legend-count">{{ l.count }}</span>
        </div>
      </div>

      <el-divider style="margin: 12px 0" />

      <div class="hint">
        <div>· 左键拖拽旋转，滚轮缩放</div>
        <div>· 悬停高亮，点击聚焦查看详情</div>
        <div>· 高度为示意比例，非真实楼高</div>
      </div>

      <!-- 口径说明：避免把年化指标误读成"当前时间范围的数据" -->
      <div class="scope-note">
        <b>数据口径</b>
        建筑着色基于<b>年化指标</b>（面积 / 强度 / 评级 / 异常率，来自建筑档案）；
        顶部时间范围只作用于详情抽屉、趋势图与 AI 解读，不改变建筑着色。
      </div>
    </div>

    <!-- 右侧统计 -->
    <div class="overlay-panel right-panel" :class="{ 'hidden-by-drawer': !!selected }">
      <div class="panel-title">{{ siteId || '--' }} 园区概览</div>
      <div class="stat-row">
        <span>建筑数</span><b>{{ blobs.length }}</b>
      </div>
      <div class="stat-row">
        <el-tooltip placement="left" raw-content
                    content="按建筑实际数据小时数年化到 8760 小时<br/>（非选定区间的实际累计值）">
          <span class="dotted">年化用电量</span>
        </el-tooltip>
        <b>{{ fmtNum(siteStat.electricity) }} kWh</b>
      </div>
      <div class="stat-row">
        <el-tooltip placement="left" raw-content
                    content="按建筑实际数据小时数年化到 8760 小时">
          <span class="dotted">年化碳排</span>
        </el-tooltip>
        <b>{{ fmtNum(siteStat.carbon) }} kgCO₂</b>
      </div>
      <div class="stat-row">
        <span>异常建筑</span>
        <b :class="{ warn: siteStat.anomalyCount > 0 }">{{ siteStat.anomalyCount }} 栋</b>
      </div>
      <div class="stat-row">
        <span>平均评级</span><b>{{ siteStat.avgGrade }}</b>
      </div>
    </div>

    <!-- ══════════ 右侧建筑详情抽屉（玻璃拟态） ══════════ -->
    <transition name="drawer">
      <aside v-if="selected" class="detail-drawer">
        <header class="dd-head">
          <div class="dd-head-main">
            <div class="dd-title">{{ selected.buildingId }}</div>
            <div class="dd-sub">
              <span class="dd-site">{{ selected.siteId }}</span>
              <span class="dd-dot">·</span>
              <span>{{ selected.buildingTypeCn || '未标注类型' }}</span>
            </div>
          </div>
          <button class="dd-close" type="button" @click="selected = null">
            <el-icon><Close /></el-icon>
          </button>
        </header>

        <div class="dd-badges">
          <span class="dd-badge grade" :style="{ background: colorOf(selected) }">
            评级 {{ selected.energyGrade || '未评级' }}
          </span>
          <span class="dd-badge soft">
            数据 {{ selected.dataGrade || '--' }} 级 ·
            {{ selected.dataGrade === 'A' ? '独立计量' : '站点分摊' }}
          </span>
        </div>

        <div v-loading="detailLoading" class="dd-body">
          <!-- 区间指标 -->
          <div class="dd-section-title">统计区间 {{ rangeText }}</div>
          <div class="dd-metrics">
            <div class="dd-metric">
              <span class="dd-metric-k">用电量</span>
              <span class="dd-metric-v">
                {{ detailMetrics?.electricityKwh != null
                  ? fmtNum(detailMetrics.electricityKwh) : '--' }}
                <i>kWh</i>
              </span>
            </div>
            <div class="dd-metric">
              <span class="dd-metric-k">
                空调能耗
                <el-tooltip placement="top" raw-content :content="hvacTip">
                  <el-icon class="dd-help"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
              <span class="dd-metric-v" :class="{ muted: hvacMissing }">
                {{ hvacText }}<i v-if="!hvacMissing">kWh</i>
              </span>
            </div>
            <div class="dd-metric">
              <span class="dd-metric-k">碳排放</span>
              <span class="dd-metric-v">
                {{ detailMetrics?.carbonKg != null ? fmtNum(detailMetrics.carbonKg) : '--' }}
                <i>kgCO₂</i>
              </span>
            </div>
            <div class="dd-metric">
              <span class="dd-metric-k">异常小时</span>
              <span class="dd-metric-v" :class="{ warn: (detailMetrics?.anomalyHours ?? 0) > 0 }">
                {{ detailMetrics?.anomalyHours != null
                  ? fmtNum(detailMetrics.anomalyHours) : '--' }}
                <i>h</i>
              </span>
            </div>
          </div>

          <!-- 建筑档案 -->
          <div class="dd-section-title">建筑档案</div>
          <dl class="dd-list">
            <div>
              <dt>建筑面积</dt>
              <dd>{{ detail?.area_m2 != null ? fmtNum(Number(detail.area_m2), 0) + ' m²' : '--' }}</dd>
            </div>
            <div>
              <dt>能耗强度</dt>
              <dd>
                {{ detail?.eui_kwh_m2_year != null
                  ? Number(detail.eui_kwh_m2_year).toFixed(1) + ' kWh/m²·年' : '--' }}
              </dd>
            </div>
            <div>
              <dt>年化电耗</dt>
              <dd>{{ detail?.electricity_kwh_year != null
                ? fmtNum(Number(detail.electricity_kwh_year)) + ' kWh' : '--' }}</dd>
            </div>
            <div>
              <dt>年化碳排</dt>
              <dd>{{ detail?.carbon_kg_year != null
                ? fmtNum(Number(detail.carbon_kg_year)) + ' kg' : '--' }}</dd>
            </div>
            <div>
              <dt>异常事件</dt>
              <dd :class="{ warn: (anomalyTotal ?? 0) > 0 }">
                {{ anomalyTotal != null ? anomalyTotal + ' 次' : '--' }}
              </dd>
            </div>
            <div>
              <dt>数据质量</dt>
              <dd>{{ detail?.quality_rate != null
                ? (Number(detail.quality_rate) * 100).toFixed(2) + '% 缺失/异常' : '--' }}</dd>
            </div>
          </dl>

          <!-- 历史趋势 -->
          <div class="dd-section-title">月度用电趋势</div>
          <div v-if="trendOption" class="dd-chart">
            <EChart :option="trendOption as never" height="132px" />
          </div>
          <div v-else class="dd-empty">该区间暂无月度数据</div>
        </div>

        <footer class="dd-foot">
          <button class="dd-action" type="button" @click="gotoEnergyDetail">
            <el-icon><TrendCharts /></el-icon>查看能耗详情
          </button>
          <button class="dd-action" type="button" @click="gotoDiagnosis">
            <el-icon><Warning /></el-icon>查看异常
          </button>
          <button class="dd-action primary" type="button" @click="openAiForBuilding">
            <el-icon><MagicStick /></el-icon>AI 分析此建筑
          </button>
        </footer>
      </aside>
    </transition>

    <!-- AI 单栋分析：LLM + 该建筑真实聚合数据（/ai/insight?scope=diagnosis） -->
    <BuildingAiPanel v-model="aiDialogVisible" :building-id="aiBuildingId"
                     :site-id="selected?.siteId" :start-date="app.startDate"
                     :end-date="app.endDate" />

    <!-- 3D 画布 -->
    <div ref="canvasHost" class="canvas-host"></div>

    <!-- 悬停提示：跟随鼠标，不拦截事件 -->
    <div v-if="hoverTip" class="hover-tip"
         :style="{ left: hoverTip.x + 16 + 'px', top: hoverTip.y + 14 + 'px' }">
      <div class="hover-tip-name">{{ hoverTip.name }}</div>
      <div class="hover-tip-meta">
        <span>{{ hoverTip.type }}</span>
        <span class="hover-tip-grade">评级 {{ hoverTip.grade }}</span>
      </div>
    </div>

    <!-- 右下角：视角工具 + AI 解读竖向堆叠，避免互相遮挡 -->
    <div class="corner-stack" :class="{ shifted: !!selected }">
      <div class="view-tools">
      <el-tooltip content="恢复默认视角" placement="left">
        <button class="tool-btn" type="button" @click="resetView">
          <el-icon><Aim /></el-icon>
        </button>
      </el-tooltip>
    </div>

    <!-- 右下角 AI 解读（可折叠，不遮挡画布） -->
      <div class="ai-float" :class="{ collapsed: !aiOpen }">
      <div class="ai-float-head" @click="aiOpen = !aiOpen">
        <span class="ai-float-title">
          <el-icon><MagicStick /></el-icon>
          AI 园区解读
          <span v-if="siteId" class="ai-float-site">{{ siteId }}</span>
        </span>
        <el-icon class="ai-float-toggle"><component :is="aiOpen ? 'ArrowDown' : 'ArrowUp'" /></el-icon>
      </div>
      <div v-show="aiOpen" class="ai-float-body">
        <AiInsight
          scope="campus"
          title="当前园区"
          :site-id="siteId"
          :show-meta="false"
          @focus-building="onFocusBuilding"
        />
      </div>
      </div>
    </div>

    <!-- 加载/空状态 -->
    <div v-if="loading" class="canvas-mask"><el-icon class="is-loading" :size="26"><Loading /></el-icon>
      <div>正在构建 3D 场景…</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, reactive, computed, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { Close, Loading, Aim, TrendCharts, Warning, QuestionFilled } from '@element-plus/icons-vue'
import { energyApi, analyticsApi } from '@/api'
import { useAppStore } from '@/stores/app'
import AiInsight from '@/components/AiInsight.vue'
import BuildingAiPanel from '@/components/BuildingAiPanel.vue'
import EChart from '@/components/EChart.vue'
import { MagicStick, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import { fmtNum, gradeColor } from '@/utils/echarts'
import {
  PALETTE, GRADE_COLOR, EUI_COLOR, ANOMALY_COLOR, NEUTRAL,
} from '@/three/palette'
import { createEnvironment, type CampusEnvironment } from '@/three/environment'
import { detectQuality, qualityLabel, type QualityProfile } from '@/three/quality'
import {
  createBuildingModel, applyGradeColor, setBuildingEmphasis,
  footprintOf, type BuildingModel,
} from '@/three/buildingModel'
import type { BuildingRow, SiteRow } from '@/types/api'
import { useDataVersionRefresh } from '@/composables/useDataVersionRefresh'

const router = useRouter()
const app = useAppStore()

const canvasHost = ref<HTMLDivElement>()
const sites = ref<SiteRow[]>([])
const siteId = ref('')
/** AI 解读悬浮面板是否展开 */
const aiOpen = ref(true)
/** 左侧控制面板是否折叠（窄屏默认折叠，给沙盘让空间） */
const panelCollapsed = ref(false)

/** 点击 AI 解读里的异常建筑 → 跳到异常诊断页并自动诊断该建筑 */
function onFocusBuilding(buildingId: string) {
  router.push({ path: '/anomaly', query: { buildingId, ...dateQuery() } })
}
/** 跳转异常页时带上当前时间范围，保证口径一致 */
function dateQuery(): Record<string, string> {
  return { startDate: app.startDate, endDate: app.endDate }
}

const buildings = ref<BuildingRow[]>([])
const loading = ref(false)
const colorBy = ref<'grade' | 'eui' | 'anomaly'>('grade')
const heightBy = ref<'area' | 'energy'>('area')
const selected = ref<BuildingRow | null>(null)
/** 用普通数组而非 reactive，避免把 THREE.Object3D 变成响应式代理（会严重掉帧） */
const blobs = ref<BuildingRow[]>([])

const siteStat = reactive({
  electricity: 0, carbon: 0, anomalyCount: 0, avgGrade: '--'
})

/** 各档位在当前园区内的建筑数量（真实数据，用于图例提示） */
function countByBand(kind: 'grade' | 'eui' | 'anomaly'): number[] {
  const list = buildings.value
  if (kind === 'grade') {
    const order = ['A', 'B', 'C', 'D', 'E', '未评级']
    const m = new Map<string, number>(order.map((k) => [k, 0]))
    for (const b of list) {
      const g = b.energyGrade && b.energyGrade !== '未评级' ? b.energyGrade : '未评级'
      m.set(g, (m.get(g) ?? 0) + 1)
    }
    return order.map((k) => m.get(k) ?? 0)
  }
  if (kind === 'eui') {
    // 末尾多留一格给「无数据」，否则图例数量之和会小于建筑总数，看着像丢数据
    const counts = new Array(EUI_COLOR.length + 1).fill(0)
    for (const b of list) {
      const e = b.euiKwhM2Year
      if (e == null) {
        counts[EUI_COLOR.length] += 1
        continue
      }
      const i = EUI_COLOR.findIndex((seg) => e < seg.max)
      counts[i < 0 ? EUI_COLOR.length - 1 : i] += 1
    }
    return counts
  }
  const counts = new Array(ANOMALY_COLOR.length).fill(0)
  for (const b of list) {
    const r = b.anomalyRate ?? 0
    const i = ANOMALY_COLOR.findIndex((seg) => r < seg.max)
    counts[i < 0 ? ANOMALY_COLOR.length - 1 : i] += 1
  }
  return counts
}

const legendItems = computed(() => {
  if (colorBy.value === 'grade') {
    const c = countByBand('grade')
    return [
      { label: 'A 优秀', color: GRADE_COLOR.A, count: c[0] },
      { label: 'B 良好', color: GRADE_COLOR.B, count: c[1] },
      { label: 'C 一般', color: GRADE_COLOR.C, count: c[2] },
      { label: 'D 较差', color: GRADE_COLOR.D, count: c[3] },
      { label: 'E 差', color: GRADE_COLOR.E, count: c[4] },
      { label: '未评级', color: NEUTRAL, count: c[5] },
    ]
  }
  if (colorBy.value === 'anomaly') {
    const c = countByBand('anomaly')
    return [
      { label: '正常（<2%）', color: ANOMALY_COLOR[0].color, count: c[0] },
      { label: '轻度（2-5%）', color: ANOMALY_COLOR[1].color, count: c[1] },
      { label: '偏高（5-10%）', color: ANOMALY_COLOR[2].color, count: c[2] },
      { label: '严重（>10%）', color: ANOMALY_COLOR[3].color, count: c[3] },
    ]
  }
  const c = countByBand('eui')
  const items = [
    { label: '低强度（<60）', color: EUI_COLOR[0].color, count: c[0] },
    { label: '中低（60-100）', color: EUI_COLOR[1].color, count: c[1] },
    { label: '中等（100-150）', color: EUI_COLOR[2].color, count: c[2] },
    { label: '较高（150-200）', color: EUI_COLOR[3].color, count: c[3] },
    { label: '高强度（>200）', color: EUI_COLOR[4].color, count: c[4] },
  ]
  if (c[5] > 0) items.push({ label: '无数据', color: NEUTRAL, count: c[5] })
  return items
})

/**
 * 当前着色模式的口径说明。
 * 写清楚"这个颜色代表什么、依据什么算的"，避免观者误读。
 */
const modeHint = computed(() => {
  if (colorBy.value === 'eui') {
    return {
      title: '能耗强度',
      unit: 'kWh/m²·年',
      desc: '面积归一化后的能耗水平，阈值 60 / 100 / 150 / 200。单位面积用能越高颜色越暖。',
    }
  }
  if (colorBy.value === 'anomaly') {
    return {
      title: '异常率',
      unit: '异常小时 ÷ 有效小时',
      desc: '异常小时占有效数据小时的比例，阈值 2% / 5% / 10%。占比越高颜色越暖。',
    }
  }
  return {
    title: '能耗评级',
    unit: 'A ~ E',
    desc: '按同建筑类型的 EUI 分位划分（0.2 / 0.4 / 0.6 / 0.8 分位），A 最优。未评级为中性灰。',
  }
})

// ---------------------------------------------------------------- Three.js
let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let rafId = 0
let resizeObserver: ResizeObserver | null = null
/** 主体 mesh 与数据的映射（射线拾取用） */
const meshMap = new Map<THREE.Mesh, BuildingRow>()
/** buildingId → 模型（用于换色与释放） */
const modelMap = new Map<string, BuildingModel>()
/** 园区环境（地面/道路/树木） */
let env: CampusEnvironment | null = null
/** 承载建筑与环境的根节点，重建时整体替换 */
let campusGroup: THREE.Group | null = null
/** 设备画质档位（自动探测，影响渲染开销但不影响业务数据） */
const quality = ref<QualityProfile | null>(null)
let qualityProfile: QualityProfile | null = null
/** WebGL 上下文是否丢失 */
const contextLost = ref(false)
/** 默认视角参数，供「恢复默认视角」使用 */
let defaultCam = { pos: new THREE.Vector3(120, 110, 150), target: new THREE.Vector3(0, 0, 0) }

function initThree() {
  if (!canvasHost.value) return
  const host = canvasHost.value
  const w = host.clientWidth
  const h = host.clientHeight

  // ── 场景：淡蓝灰背景 + 远处雾，拉出通透的空间层次 ──
  scene = new THREE.Scene()
  scene.background = new THREE.Color(PALETTE.background)
  scene.fog = new THREE.Fog(PALETTE.fog, PALETTE.fogNear, PALETTE.fogFar)

  camera = new THREE.PerspectiveCamera(42, w / h, 0.5, 4000)

  // ── 渲染器：抗锯齿 + 柔和阴影 + ACES 色调映射（浅色场景更通透）──
  // preserveDrawingBuffer 仅在 ?capture=1 时开启：
  // 无头浏览器截图需要它才能拿到 WebGL 帧内容；正常工作时不开启（有性能代价）
  const captureMode = typeof location !== 'undefined' && location.search.includes('capture')
  // 截图时禁用过渡：无头浏览器用虚拟时钟推进，动画可能停在中间帧，
  // 会让面板看起来"半透明"。加个类彻底关掉过渡，保证截图确定性。
  if (captureMode) {
    // 用全局 <style> 注入（scoped 样式作用不到 html 层级）
    const st = document.createElement('style')
    st.textContent = '*,*::before,*::after{transition:none!important;animation:none!important}'
    document.head.appendChild(st)
  }
  // ── 画质档位：按设备能力自动降级（软件渲染 / 移动端 / 低配）──
  qualityProfile = detectQuality()
  quality.value = qualityProfile
  const q = qualityProfile

  renderer = new THREE.WebGLRenderer({
    antialias: q.antialias, alpha: false, preserveDrawingBuffer: captureMode,
  })
  renderer.setSize(w, h)
  // 像素比上限由画质档位决定（高配 2 / 移动 1.5 / 软件渲染 1）
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, q.pixelRatio))
  renderer.shadowMap.enabled = q.shadows
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.04
  host.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.075
  // 限制俯仰：不允许钻到地面以下，也不允许完全水平（保持"沙盘"观感）
  controls.minPolarAngle = Math.PI * 0.12
  controls.maxPolarAngle = Math.PI * 0.46
  controls.minDistance = 40
  controls.maxDistance = 900
  controls.enablePan = true
  controls.panSpeed = 0.8

  // ── 光照：柔和自然光 ──────────────────────────────────────────
  // 半球光负责整体环境色（天空偏冷、地面反弹偏暖）
  const hemi = new THREE.HemisphereLight(0xf3f7f8, 0xcfd8d3, 1.05)
  scene.add(hemi)

  // 主方向光：产生柔和阴影，角度偏低让建筑有体积感
  const sun = new THREE.DirectionalLight(0xfffaf0, 1.55)
  sun.position.set(180, 300, 150)
  sun.castShadow = true
  // 阴影贴图 1024 足够，2048 在笔记本上开销翻倍且观感提升有限
  sun.castShadow = q.shadows
  sun.shadow.mapSize.set(q.shadowMapSize, q.shadowMapSize)
  sun.shadow.camera.near = 1
  sun.shadow.camera.far = 1200
  const sd = 360
  sun.shadow.camera.left = -sd
  sun.shadow.camera.right = sd
  sun.shadow.camera.top = sd
  sun.shadow.camera.bottom = -sd
  sun.shadow.bias = -0.0006
  sun.shadow.normalBias = 0.6
  scene.add(sun)

  // 补一盏很弱的反向光，避免背光面死黑
  const fill = new THREE.DirectionalLight(0xdfe9f0, 0.35)
  fill.position.set(-160, 120, -140)
  scene.add(fill)

  // 建筑与环境的根节点
  campusGroup = new THREE.Group()
  campusGroup.name = 'campus-root'
  scene.add(campusGroup)

  // WebGL 上下文丢失（显卡驱动重置、切换显卡、休眠唤醒）：给出提示而不是白屏
  renderer.domElement.addEventListener('webglcontextlost', onContextLost)
  renderer.domElement.addEventListener('webglcontextrestored', onContextRestored)

  renderer.domElement.addEventListener('click', onCanvasClick)
  renderer.domElement.addEventListener('pointermove', onPointerMove)
  window.addEventListener('resize', onResize)
  resizeObserver = new ResizeObserver(onResize)
  resizeObserver.observe(host)

  animate()

  // CAPTURE-SNAPSHOT 临时：?capture=1 时把画布转成 <img> 覆盖在页面上。
  // 无头 Chrome 不合成 WebGL 图层，只有转成图片才能截图验证；验证后删除。
  if (captureMode) {
    setTimeout(() => {
      try {
        const url = renderer!.domElement.toDataURL('image/png')
        // 只盖在画布容器里，保持抽屉/面板可见
        const img = document.createElement('img')
        img.src = url
        img.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;'
          + 'object-fit:contain;z-index:0;background:#eaeef0'
        host.appendChild(img)
      } catch (e) {
        console.error('snapshot failed', e)
      }
    }, 2500)
  }
}

/**
 * WebGL 上下文丢失处理。
 *
 * <p>触发场景：显卡驱动重置、笔记本切换独显/核显、系统休眠唤醒、
 * 浏览器回收过多上下文。默认行为是画布永久黑屏，必须显式处理。
 */
function onContextLost(ev: Event) {
  ev.preventDefault()      // 阻止默认行为，浏览器才可能触发 restored
  contextLost.value = true
  ElMessage.warning('3D 渲染上下文丢失，正在尝试恢复…')
}

function onContextRestored() {
  contextLost.value = false
  // three 会在上下文恢复后重建 GPU 资源，这里只需重绘一帧
  markDirty()
  ElMessage.success('3D 场景已恢复')
}

function onResize() {
  if (!canvasHost.value || !renderer || !camera) return
  const w = canvasHost.value.clientWidth
  const h = canvasHost.value.clientHeight
  if (w === 0 || h === 0) return
  camera.aspect = w / h
  camera.updateProjectionMatrix()
  renderer.setSize(w, h)
}

const raycaster = new THREE.Raycaster()
const pointer = new THREE.Vector2()

/** 当前悬停的建筑模型（用于恢复上一个的高亮） */
let hoveredModel: BuildingModel | null = null

function pickAt(ev: PointerEvent | MouseEvent): BuildingRow | null {
  if (!renderer || !camera) return null
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const hits = raycaster.intersectObjects([...meshMap.keys()], false)
  if (hits.length === 0) return null
  return meshMap.get(hits[0].object as THREE.Mesh) ?? null
}

/** 悬停：轻微高亮 + 记录鼠标位置供 Tooltip 使用 */
let hoverRaf = 0
function onPointerMove(ev: PointerEvent) {
  // 用 rAF 节流，避免 mousemove 高频触发射线拾取
  if (hoverRaf) return
  hoverRaf = requestAnimationFrame(() => {
    hoverRaf = 0
    if (!renderer) return
    const b = pickAt(ev)
    const model = b ? modelMap.get(b.buildingId) ?? null : null

    if (model === hoveredModel) {
      if (b) updateHoverTip(ev, b)
      return
    }
    // 恢复上一个
    if (hoveredModel) setBuildingEmphasis(hoveredModel, { highlight: false })
    hoveredModel = model
    if (model) {
      setBuildingEmphasis(model, { highlight: true })
      updateHoverTip(ev, b!)
      renderer.domElement.style.cursor = 'pointer'
    } else {
      hoverTip.value = null
      renderer.domElement.style.cursor = 'grab'
    }
    markDirty()
  })
}

/** Tooltip 数据（模板里渲染成跟随鼠标的小卡片） */
const hoverTip = ref<{ x: number; y: number; name: string; type: string; grade: string } | null>(null)

function updateHoverTip(ev: PointerEvent, b: BuildingRow) {
  hoverTip.value = {
    x: ev.clientX, y: ev.clientY,
    name: b.buildingId,
    type: b.buildingTypeCn || '未标注类型',
    grade: b.energyGrade || '未评级',
  }
}

function onCanvasClick(ev: MouseEvent) {
  const b = pickAt(ev)
  selected.value = b
  if (!b) {
    // 点击空白：清除聚焦，其他建筑恢复
    setFocusTarget(null)
    return
  }
  setFocusTarget(b.buildingId)
}

/** 选中某栋：其他建筑降低视觉权重，相机平滑聚焦 */
function setFocusTarget(buildingId: string | null) {
  for (const [id, model] of modelMap) {
    const dim = !!buildingId && id !== buildingId
    setBuildingEmphasis(model, { dim, highlight: id === buildingId })
  }
  const model = buildingId ? modelMap.get(buildingId) : null
  if (model) focusCameraOn(model.group.position)
  markDirty()
}

/** 相机平滑聚焦到某点（简单 lerp 缓动，不用外部补间库） */
let focusAnim = 0
function focusCameraOn(target: THREE.Vector3) {
  if (!camera || !controls) return
  cancelAnimationFrame(focusAnim)
  const startPos = camera.position.clone()
  const startTarget = controls.target.clone()
  // 聚焦距离收敛：原来 *0.55 会把镜头怼得太近，看不清建筑与周边关系
  const dist = THREE.MathUtils.clamp(
    camera.position.distanceTo(target) * 0.62, 150, 460)
  const dir = camera.position.clone().sub(target).normalize()
  const endPos = target.clone().add(dir.multiplyScalar(dist)).add(new THREE.Vector3(0, 18, 0))
  const t0 = performance.now()
  const dur = 620

  const step = () => {
    if (!camera || !controls) return
    const p = Math.min(1, (performance.now() - t0) / dur)
    // easeOutCubic
    const e = 1 - Math.pow(1 - p, 3)
    camera.position.lerpVectors(startPos, endPos, e)
    controls.target.lerpVectors(startTarget, target, e)
    controls.update()
    markDirty()
    if (p < 1) focusAnim = requestAnimationFrame(step)
  }
  focusAnim = requestAnimationFrame(step)
}

function clearScene() {
  if (!scene) return
  colorTween = null
  for (const model of modelMap.values()) {
    model.group.removeFromParent()
    model.dispose()
  }
  modelMap.clear()
  meshMap.clear()

  if (env) {
    env.group.removeFromParent()
    env.dispose()
    env = null
  }
  if (campusGroup) {
    campusGroup.removeFromParent()
    campusGroup = null
  }
}

/** 计算某栋楼的颜色 */
function colorOf(b: BuildingRow): string {
  if (colorBy.value === 'anomaly') {
    const r = b.anomalyRate ?? 0
    for (const seg of ANOMALY_COLOR) if (r < seg.max) return seg.color
    return NEUTRAL
  }
  if (colorBy.value === 'eui') {
    const e = b.euiKwhM2Year
    if (e == null) return NEUTRAL
    for (const seg of EUI_COLOR) if (e < seg.max) return seg.color
    return NEUTRAL
  }
  return GRADE_COLOR[b.energyGrade || '未评级'] ?? NEUTRAL
}

/** 构建园区（环境 + 微缩建筑）。顺序：先算布局，再建环境与建筑。 */
function buildBuildings() {
  if (!scene || !campusGroup) return
  clearScene()

  // clearScene 会把 campusGroup 置空，这里重新挂一个根节点
  campusGroup = new THREE.Group()
  campusGroup.name = 'campus-root'
  scene.add(campusGroup)

  const list = buildings.value
  if (list.length === 0) return

  // 网格布局（示意布局：本项目无真实经纬度，不冒充地理模型）
  const cols = Math.ceil(Math.sqrt(list.length))
  const rows = Math.ceil(list.length / cols)
  const gap = 44

  const worldW = (cols - 1) * gap
  const worldD = (rows - 1) * gap
  // 沙盘半径：容纳所有建筑并留出绿化带
  const radius = Math.max(worldW, worldD) / 2 + 62

  // ── 环境（地面/道路/草坪/树木）──
  env = createEnvironment({ radius, treeCount: undefined, density: qualityProfile?.treeScale ?? 1 })
  campusGroup.add(env.group)

  // ── 建筑 ──
  const maxEnergy = Math.max(...list.map((b) => b.electricityKwhYear || 1), 1)

  list.forEach((b, i) => {
    const row = Math.floor(i / cols)
    const col = i % cols
    const x = (col - (cols - 1) / 2) * gap
    const z = (row - (rows - 1) / 2) * gap

    const gradeHex = colorOf(b)
    const model = createBuildingModel(
      { buildingId: b.buildingId, buildingTypeCn: b.buildingTypeCn, areaM2: b.areaM2 },
      gradeHex,
    )
    model.group.position.set(x, 0, z)

    // 「按高度表示」保留原有能力，但只做**整体体量缩放**，
    // 不把能耗值当真实楼高（真实高度数据本项目没有）。
    if (heightBy.value === 'energy') {
      const ratio = Math.sqrt((b.electricityKwhYear || 0) / maxEnergy)
      const k = 0.78 + ratio * 0.5      // 0.78 ~ 1.28，克制，不夸张
      model.group.scale.set(1, k, 1)
    }

    campusGroup!.add(model.group)
    modelMap.set(b.buildingId, model)
    meshMap.set(model.body, b)
  })

  // 默认视角：38° 俯视，正对园区中心
  applyDefaultCamera(radius)
  markDirty()
}

/**
 * 设置默认相机：38° 俯视 + 45° 方位角。
 *
 * <p>没有真实地理朝向，这里固定一个观感最舒服的角度；
 * 「恢复默认视角」按钮复用同一套参数。
 */
function applyDefaultCamera(radius: number) {
  if (!camera || !controls) return
  const elevation = THREE.MathUtils.degToRad(36)
  const azimuth = THREE.MathUtils.degToRad(45)
  const dist = radius * 1.78

  const pos = new THREE.Vector3(
    Math.cos(elevation) * Math.cos(azimuth) * dist,
    Math.sin(elevation) * dist,
    Math.cos(elevation) * Math.sin(azimuth) * dist,
  )
  const target = new THREE.Vector3(0, 6, 0)

  camera.position.copy(pos)
  controls.target.copy(target)
  controls.update()

  defaultCam = { pos: pos.clone(), target: target.clone() }
}

/** 恢复默认视角 */
function resetView() {
  if (!camera || !controls) return
  camera.position.copy(defaultCam.pos)
  controls.target.copy(defaultCam.target)
  controls.update()
}

/**
 * 渲染循环。
 *
 * <p>【性能要点】原实现每帧都在 CPU 上遍历粒子更新位置，并全速重绘，
 * 即使画面完全静止。现在：
 * <ul>
 *   <li>删除粒子系统（业务意义不足且持续消耗 CPU）；</li>
 *   <li>只在「相机在动 / 有阻尼未收敛 / 场景被标记为脏」时才真正 render；</li>
 *   <li>阻尼收敛后自动停机，笔记本电脑不会持续发热。</li>
 * </ul>
 */
let needsRender = true

function markDirty() {
  needsRender = true
}

function animate() {
  rafId = requestAnimationFrame(animate)
  if (!renderer || !scene || !camera) return

  // OrbitControls 的阻尼需要持续 update 才能收敛
  const damping = controls?.enableDamping
  let moving = false
  if (controls) {
    controls.update()
    // controls.update() 在阻尼未收敛时会持续改变相机位置
    if (damping) {
      moving = !controls.target.equals((controls as any)._lastTarget ?? controls.target)
        || camera.position.distanceToSquared(
          (controls as any)._lastCamPos ?? camera.position) > 1e-6
      ;(controls as any)._lastCamPos = camera.position.clone()
      ;(controls as any)._lastTarget = controls.target.clone()
    }
  }

  // 颜色过渡期间必须持续重绘
  const tweening = stepColorTween(performance.now())
  if (tweening) needsRender = true

  if (needsRender || moving) {
    renderer.render(scene, camera)
    needsRender = false
  }
}

// ---------------------------------------------------------------- 交互
// ---------------------------------------------------------------- 建筑详情
/**
 * 建筑详情数据全部来自已有接口：
 *   · energyApi.buildingDetail  → 类型 / 面积 / EUI / 年化碳排 / 数据质量
 *   · energyApi.overview        → 选定区间的用电 / 空调 / 碳排 / 异常小时
 *   · analyticsApi.scanAnomalies→ 异常事件总数
 *   · energyApi.query           → 月度历史趋势
 * 不构造任何假数据；接口没给的字段一律显示「--」或「未单独计量」。
 */
interface DetailMetrics {
  electricityKwh: number | null
  hvacKwh: number | null
  carbonKg: number | null
  anomalyHours: number | null
  totalHours: number | null
}

const detail = ref<Record<string, unknown> | null>(null)
const detailLoading = ref(false)
const detailMetrics = ref<DetailMetrics | null>(null)
const anomalyTotal = ref<number | null>(null)
const trendOption = ref<Record<string, unknown> | null>(null)
/** AI 单栋分析弹窗 */
const aiDialogVisible = ref(false)
const aiBuildingId = ref('')

const rangeText = computed(() => `${app.startDate} ~ ${app.endDate}`)

/**
 * 空调能耗的展示口径。
 *
 * <p>hvacKwh 为 0 或 null 有两种可能：原始 BDG2 数据里这栋楼就没有
 * 冷/热/蒸汽分项表计（456 栋中占 385 栋），或表计未通过质量校验。
 * 无论哪种，界面都必须说明"数据源没有"，而不是让用户以为系统漏了。
 */
const hvacText = computed(() => {
  const v = detailMetrics.value?.hvacKwh
  if (v == null || v === 0) return '无分项计量'
  return `${fmtNum(v)} kWh`
})

const hvacMissing = computed(() => hvacText.value === '无分项计量')

/** 空调能耗缺失时的说明文案（放在 tooltip 里） */
const hvacTip = computed(() =>
  hvacMissing.value
    ? '该建筑在原始数据（BDG2）中没有冷/热/蒸汽分项表计，<br/>'
      + '因此无法给出空调能耗，只能按整栋电表计量。<br/><br/>'
      + '全部 456 栋中有 71 栋提供分项表计。'
    : '该数值为冷量/热量（热力学能），与电量不是同一口径。')

async function loadDetail(b: BuildingRow | null) {
  if (!b) {
    detail.value = null
    detailMetrics.value = null
    anomalyTotal.value = null
    trendOption.value = null
    return
  }
  detailLoading.value = true
  const start = app.startDate
  const end = app.endDate
  try {
    const [d, ov, scan, series] = await Promise.all([
      energyApi.buildingDetail(b.buildingId).catch(() => null),
      energyApi.overview({
        startDate: start, endDate: end, buildingId: b.buildingId,
      }).catch(() => null),
      analyticsApi.scanAnomalies({
        startDate: start, endDate: end, buildingId: b.buildingId,
        pageNum: 1, pageSize: 1,
      }).catch(() => null),
      energyApi.query({
        startDate: start, endDate: end, buildingId: b.buildingId,
        granularity: 'monthly',
      }).catch(() => null),
    ])

    detail.value = (d as Record<string, unknown>) ?? null

    const ovd = (ov as Record<string, number> | null) ?? null
    detailMetrics.value = ovd
      ? {
          electricityKwh: ovd.electricityKwh ?? null,
          hvacKwh: ovd.hvacKwh ?? null,
          carbonKg: ovd.carbonKg ?? null,
          anomalyHours: ovd.anomalyHours ?? null,
          totalHours: ovd.totalHours ?? null,
        }
      : null

    const sc = scan as { total?: number } | null
    anomalyTotal.value = sc?.total ?? null

    // 月度趋势
    const pts = ((series as { points?: Array<Record<string, unknown>> } | null)?.points) || []
    if (pts.length > 0) {
      trendOption.value = {
        grid: { left: 8, right: 8, top: 10, bottom: 4, containLabel: true },
        xAxis: {
          type: 'category',
          data: pts.map((p) => String(p.time).slice(5)),
          axisLine: { lineStyle: { color: '#d7dfe1' } },
          axisTick: { show: false },
          axisLabel: { color: '#7b8b84', fontSize: 10 },
        },
        yAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef2f3' } },
          axisLabel: { color: '#7b8b84', fontSize: 10 } },
        tooltip: { trigger: 'axis' },
        series: [{
          type: 'line',
          smooth: true,
          symbolSize: 4,
          data: pts.map((p) => p.electricityKwh ?? 0),
          itemStyle: { color: '#35866b' },
          areaStyle: { color: 'rgba(53,134,107,0.10)' },
        }],
      }
    } else {
      trendOption.value = null
    }
  } finally {
    detailLoading.value = false
  }
}

/** 选中变化时加载详情 */
watch(selected, (v) => { void loadDetail(v) })

/** 打开「AI 分析此建筑」：进入小E 助手的多轮对话 */
function openAiForBuilding() {
  if (!selected.value) return
  aiBuildingId.value = selected.value.buildingId
  aiDialogVisible.value = true
}

/** 三个详情入口，全部落到已有路由 */
function gotoEnergyDetail() {
  if (!selected.value) return
  router.push({ path: '/energy', query: { buildingId: selected.value.buildingId } })
}
function gotoDiagnosis() {
  if (!selected.value) return
  router.push({ path: '/anomaly', query: { buildingId: selected.value.buildingId, ...dateQuery() } })
}

/**
 * 颜色过渡状态。
 *
 * <p>切换着色模式时，如果直接改材质颜色会"闪一下"，观感廉价且容易让人
 * 以为数据跳变。这里把每栋建筑的强调色从当前值 lerp 到目标值，
 * 持续 380ms；过渡期间持续请求重绘，结束后停止。
 *
 * <p>【避免数据状态混乱】过渡只影响"颜色表现"，`colorBy` 的值在点击瞬间
 * 就已切换，图例与模式提示立即更新——也就是说文案永远领先于动画，
 * 不会出现"图例说明是 A 模式、建筑却还显示 B 模式颜色"的错乱。
 */
interface ColorTween {
  from: Map<string, THREE.Color>
  to: Map<string, THREE.Color>
  t0: number
}
let colorTween: ColorTween | null = null
const COLOR_TWEEN_MS = 380

/** 切换着色模式：启动颜色过渡 */
function applyColors() {
  if (!scene) return
  const from = new Map<string, THREE.Color>()
  const to = new Map<string, THREE.Color>()
  for (const b of buildings.value) {
    const model = modelMap.get(b.buildingId)
    if (!model) continue
    const cur = (model.accent.material as THREE.MeshStandardMaterial).color
    from.set(b.buildingId, cur.clone())
    to.set(b.buildingId, new THREE.Color(colorOf(b)))
  }
  colorTween = { from, to, t0: performance.now() }
  markDirty()
}

/**
 * 在渲染循环里推进颜色过渡。
 * @returns 是否仍在过渡中（是则需要继续重绘）
 */
function stepColorTween(now: number): boolean {
  if (!colorTween) return false
  const p = Math.min(1, (now - colorTween.t0) / COLOR_TWEEN_MS)
  // easeInOutCubic：起步与收尾都柔和
  const e = p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2

  for (const [id, fromC] of colorTween.from) {
    const model = modelMap.get(id)
    const toC = colorTween.to.get(id)
    if (!model || !toC) continue
    const c = fromC.clone().lerp(toC, e)
    const am = model.accent.material as THREE.MeshStandardMaterial
    am.color.copy(c)
    am.emissive.copy(c)
    ;(model.halo.material as THREE.MeshBasicMaterial).color.copy(c)
  }

  if (p >= 1) {
    colorTween = null
    return false
  }
  return true
}

/**
 * 单次请求的建筑条数上限（后端 max-page-size=500）。
 * 只用于「知道真实有多少栋」，不等于渲染上限。
 */
const BUILDING_LIMIT = 500

/**
 * 渲染上限：每栋建筑约 3~4 个 draw call（主体 + 强调条 + 光环 + 窗户实例），
 * 300 栋约 1k draw call，是当前质量档位下的可接受上限。
 * 超过则按年用电量降序取前 N 栋并提示，避免无条件加载无限建筑。
 */
const RENDER_CAP = 300

/** 把建筑列表写入状态并重算园区统计（不发请求、不碰 Three.js） */
function applyBuildingList(list: BuildingRow[]) {
  buildings.value = list
  blobs.value = list

  siteStat.electricity = list.reduce((s, b) => s + (b.electricityKwhYear || 0), 0)
  siteStat.carbon = list.reduce((s, b) => s + (b.electricityKwhYear || 0) * 0.581, 0)
  siteStat.anomalyCount = list.filter((b) => (b.anomalyRate ?? 0) > 0.05).length

  const grades = list.map((b) => b.energyGrade).filter((g): g is string => !!g && g !== '未评级')
  const order = ['A', 'B', 'C', 'D', 'E']
  siteStat.avgGrade = grades.length
    ? order[Math.round(grades.reduce((s, g) => s + order.indexOf(g), 0) / grades.length)] || '--'
    : '--'

  // 刷新后把选中项换成新对象，避免详情抽屉停留在旧评级/旧用电量
  if (selected.value) {
    const hit = list.find((b) => b.buildingId === selected.value!.buildingId)
    selected.value = hit ?? null
  }
}

/** 拉取当前园区建筑，并施加渲染上限保护 */
async function fetchBuildings(): Promise<BuildingRow[]> {
  const list = await energyApi.buildings({ siteId: siteId.value, limit: BUILDING_LIMIT })
  const capped = list.length >= BUILDING_LIMIT
  if (list.length > RENDER_CAP) {
    const sorted = [...list].sort(
      (a, b) => (b.electricityKwhYear || 0) - (a.electricityKwhYear || 0))
    ElMessage.warning(
      `本园区共 ${list.length}${capped ? '+' : ''} 栋建筑，` +
      `为保证 3D 流畅仅渲染年用电量最高的 ${RENDER_CAP} 栋`)
    return sorted.slice(0, RENDER_CAP)
  }
  return list
}

async function rebuild() {
  if (!siteId.value) return
  // 截图模式：?mode=eui|anomaly 直接以该着色模式构建，便于回归对比
  if (typeof location !== 'undefined' && location.search.includes('capture')) {
    const m = new URLSearchParams(location.search).get('mode')
    if (m === 'eui' || m === 'anomaly' || m === 'grade') colorBy.value = m
  }
  loading.value = true
  try {
    const list = await fetchBuildings()
    applyBuildingList(list)

    await nextTick()
    buildBuildings()

    // 截图模式：?capture=1&sel=<buildingId> 自动选中一栋，便于回归验证详情抽屉
    if (typeof location !== 'undefined' && location.search.includes('capture')) {
      const want = new URLSearchParams(location.search).get('sel')
      if (want) {
        const hit = list.find((x) => x.buildingId === want)
        if (hit) {
          selected.value = hit
          setFocusTarget(hit.buildingId)
          // ?ai=1 时自动打开 AI 分析弹窗，便于回归截图
          if (new URLSearchParams(location.search).get('ai') === '1') {
            aiBuildingId.value = hit.buildingId
            aiDialogVisible.value = true
          }
        }
      }
    }
  } finally {
    loading.value = false
  }
}

/**
 * 数据变更后的增量刷新。
 *
 * - 建筑集合未变（最常见：已有建筑导入了新能耗/新评级）
 *   → 只更新评级颜色与统计，**复用现有 Three.js 模型**，不重建场景；
 * - 集合变化（新增/删除建筑）
 *   → 重建场景。clearScene 不触碰 camera/controls，相机角度与缩放自动保留；
 *     选中项在 applyBuildingList 中按 buildingId 重新映射。
 */
async function refreshBuildings() {
  if (!siteId.value || !scene) return
  const list = await fetchBuildings()
  const prevIds = new Set(modelMap.keys())
  const nextIds = new Set(list.map((b) => b.buildingId))
  const sameSet = prevIds.size === nextIds.size
    && [...nextIds].every((id) => prevIds.has(id))

  applyBuildingList(list)

  // 高度按面积时，建筑集合不变 ⇒ 体量也不会变，只需换色
  if (sameSet && heightBy.value === 'area') {
    applyColors()
    markDirty()
    return
  }

  await nextTick()
  buildBuildings()
}

// 导入新数据后（keep-alive 缓存场景）增量刷新建筑与评级
useDataVersionRefresh(() => refreshBuildings())

function gotoAnomaly() {
  router.push({ path: '/anomaly', query: { buildingId: selected.value?.buildingId, ...dateQuery() } })
}

onMounted(async () => {
  await nextTick()

  panelCollapsed.value = window.innerWidth < 1180

  // 【为什么单独 try】initThree() 依赖 WebGL。若浏览器禁用了硬件加速、
  // 显卡驱动异常，或运行在无头环境里，它会抛异常。
  // 原实现把 initThree() 放在 try 外面，一旦抛错整个 onMounted 中断，
  // 结果是：园区下拉永远是「请选择」、园区概览全是 0、右侧 AI 解读变空白。
  // 实测在无头 Chrome 中复现。现在即使 3D 初始化失败，数据与文字解读仍可用。
  try {
    initThree()
  } catch (e) {
    console.error('3D 场景初始化失败（可能是 WebGL 不可用）：', e)
    ElMessage.warning('3D 场景初始化失败，可能是浏览器禁用了 WebGL；数据面板仍可正常使用')
  }

  try {
    sites.value = await energyApi.sites()
    if (sites.value.length > 0) {
      // 默认选建筑数最多的园区（演示效果最好）
      const best = [...sites.value].sort((a, b) => b.buildingCount - a.buildingCount)[0]
      siteId.value = best.siteId
      await rebuild()
    }
  } catch (e) {
    console.error('园区数据加载失败：', e)
  }
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  cancelAnimationFrame(focusAnim)
  cancelAnimationFrame(hoverRaf)
  window.removeEventListener('resize', onResize)
  resizeObserver?.disconnect()
  if (renderer) {
    renderer.domElement.removeEventListener('click', onCanvasClick)
    renderer.domElement.removeEventListener('pointermove', onPointerMove)
    renderer.dispose()
    renderer.domElement.remove()
  }
  clearScene()
  controls?.dispose()
  renderer = null
  scene = null
  camera = null
  controls = null
})
</script>

<style scoped>
/* 指标名旁的帮助图标 */
.dd-help {
  margin-left: 3px;
  font-size: 11px;
  color: #b6c3bd;
  cursor: help;
  vertical-align: middle;
}

/* 面板折叠 */
.left-panel.collapsed > *:not(.panel-title) {
  display: none;
}
.left-panel.collapsed {
  padding-bottom: 12px;
}

/* 画质徽标 */
.q-badge {
  padding: 1px 7px;
  border-radius: 6px;
  border: 1px solid rgba(53, 134, 107, 0.2);
  background: rgba(53, 134, 107, 0.08);
  color: #2f7a61;
  font-size: 10px;
  font-family: inherit;
  cursor: help;
}

/* 折叠按钮 */
.collapse-btn {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 6px;
  background: rgba(37, 61, 53, 0.05);
  color: #5d6e66;
  cursor: pointer;
  transition: background 0.16s ease;
}
.collapse-btn:hover { background: rgba(37, 61, 53, 0.1); }

/* 当前着色模式提示 */
.mode-hint {
  margin-top: 9px;
  padding: 9px 10px;
  border-radius: 10px;
  background: rgba(53, 134, 107, 0.06);
  border: 1px solid rgba(53, 134, 107, 0.12);
}
.mode-hint-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 11.5px;
  color: #5d6e66;
}
.mode-hint-head b {
  font-size: 12px;
  color: #2f7a61;
  font-weight: 600;
}
.mode-hint-unit {
  font-size: 10.5px;
  color: #8b9d95;
}
.mode-hint-desc {
  margin: 5px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: #6f8085;
  overflow-wrap: break-word;
}

/* 数据口径说明 */
.scope-note {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  background: rgba(37, 61, 53, 0.04);
  border: 1px dashed rgba(37, 61, 53, 0.14);
  font-size: 10.5px;
  line-height: 1.75;
  color: #6f8085;
  overflow-wrap: break-word;
}
.scope-note b { color: #46584f; }

/* 图例：标签 + 数量 */
.legend-label { flex: 1; }
.legend-count {
  font-size: 11px;
  color: #8b9d95;
  font-variant-numeric: tabular-nums;
}

/* 浅色面板里的表单控件轻微收敛 */
.left-panel :deep(.el-select__wrapper) {
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 0 0 1px rgba(53, 134, 107, 0.14) inset;
}
.left-panel :deep(.el-radio-button__inner) {
  background: rgba(255, 255, 255, 0.7);
  border-color: rgba(53, 134, 107, 0.16);
  color: #46584f;
  box-shadow: none;
}
.left-panel :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: #327f66;
  border-color: #327f66;
  color: #fff;
  box-shadow: none;
}
.left-panel :deep(.el-divider) {
  border-color: rgba(37, 61, 53, 0.08);
}

/* ══════════════ 右侧建筑详情抽屉 ══════════════ */
.detail-drawer {
  position: absolute;
  top: 14px;
  right: 14px;
  bottom: 14px;
  width: 396px;
  z-index: 20;
  display: flex;
  flex-direction: column;
  border-radius: 20px;
  /* 0.93：即使浏览器不支持 backdrop-filter（或软件渲染），文字也保持清晰 */
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(255, 255, 255, 0.85);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 24px 60px -26px rgba(37, 61, 53, 0.34),
    0 6px 18px -10px rgba(37, 61, 53, 0.12);
  backdrop-filter: blur(18px) saturate(120%);
  -webkit-backdrop-filter: blur(18px) saturate(120%);
  overflow: hidden;
}

.dd-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 18px 18px 12px;
}
.dd-head-main { flex: 1; min-width: 0; }
.dd-title {
  font-size: 15.5px;
  font-weight: 600;
  color: #253d35;
  line-height: 1.35;
  word-break: break-all;
}
.dd-sub {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #5d6e66;
}
.dd-site {
  padding: 1px 7px;
  border-radius: 6px;
  background: rgba(53, 134, 107, 0.1);
  color: #2f7a61;
}
.dd-dot { color: #b6c3bd; }
.dd-close {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 9px;
  background: rgba(37, 61, 53, 0.05);
  color: #5d6e66;
  cursor: pointer;
  transition: background 0.16s ease;
}
.dd-close:hover { background: rgba(37, 61, 53, 0.1); }

.dd-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 18px 12px;
}
.dd-badge {
  padding: 3px 10px;
  border-radius: 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: #ffffff;
  letter-spacing: 0.02em;
}
.dd-badge.soft {
  background: rgba(37, 61, 53, 0.06);
  color: #46584f;
  font-weight: 400;
}

.dd-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 18px 8px;
}
.dd-section-title {
  margin: 14px 0 9px;
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: #7b8b84;
  text-transform: uppercase;
}
.dd-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.dd-metric {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.66);
  border: 1px solid rgba(53, 134, 107, 0.1);
}
.dd-metric-k {
  display: block;
  font-size: 11.5px;
  color: #7b8b84;
  margin-bottom: 5px;
}
.dd-metric-v {
  font-size: 15px;
  font-weight: 600;
  color: #253d35;
  font-variant-numeric: tabular-nums;
}
.dd-metric-v i {
  margin-left: 3px;
  font-size: 10.5px;
  font-weight: 400;
  font-style: normal;
  color: #8b9d95;
}
.dd-metric-v.muted { font-size: 12.5px; font-weight: 500; color: #8b9d95; }
.dd-metric-v.warn { color: #b4574c; }

.dd-list {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr;
  gap: 1px;
  background: rgba(37, 61, 53, 0.06);
  border-radius: 12px;
  overflow: hidden;
}
.dd-list > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 12px;
  background: rgba(255, 255, 255, 0.7);
}
.dd-list dt {
  font-size: 12px;
  color: #7b8b84;
}
.dd-list dd {
  margin: 0;
  font-size: 12.5px;
  font-weight: 500;
  color: #253d35;
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.dd-list dd.warn { color: #b4574c; }

.dd-chart {
  padding: 8px 6px 4px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.66);
  border: 1px solid rgba(53, 134, 107, 0.08);
}
.dd-empty {
  padding: 18px;
  text-align: center;
  font-size: 12px;
  color: #8b9d95;
  border-radius: 12px;
  background: rgba(37, 61, 53, 0.03);
}

.dd-foot {
  display: flex;
  gap: 8px;
  padding: 12px 18px 16px;
  border-top: 1px solid rgba(37, 61, 53, 0.07);
}
.dd-action {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 36px;
  padding: 0 8px;
  border-radius: 10px;
  border: 1px solid rgba(53, 134, 107, 0.18);
  background: rgba(255, 255, 255, 0.8);
  color: #2f7a61;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease;
}
.dd-action:hover {
  background: #ffffff;
  border-color: rgba(53, 134, 107, 0.4);
}
.dd-action.primary {
  background: #327f66;
  border-color: #327f66;
  color: #ffffff;
}
.dd-action.primary:hover { background: #2c755c; }

/* 抽屉滑入 */
.drawer-enter-active, .drawer-leave-active {
  transition: transform 0.34s cubic-bezier(0.22, 0.61, 0.36, 1), opacity 0.28s ease;
}
.drawer-enter-from, .drawer-leave-to {
  transform: translateX(24px);
  opacity: 0;
}

/* 小屏：抽屉铺满右侧、降低宽度 */
@media (max-width: 1280px) {
  .detail-drawer { width: 352px; }
}
@media (max-width: 900px) {
  .detail-drawer {
    left: 12px;
    right: 12px;
    width: auto;
    top: auto;
    height: 62%;
    border-radius: 18px;
  }
}

/* 减少动态效果偏好 */
@media (prefers-reduced-motion: reduce) {
  .drawer-enter-active, .drawer-leave-active { transition: none; }
}

/* 悬停提示：玻璃拟态小卡片 */
.hover-tip {
  position: fixed;
  z-index: 30;
  pointer-events: none;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(37, 61, 53, 0.1);
  box-shadow: 0 8px 24px -10px rgba(37, 61, 53, 0.28);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}
.hover-tip-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #253d35;
  margin-bottom: 3px;
}
.hover-tip-meta {
  display: flex;
  gap: 10px;
  font-size: 11.5px;
  color: #5d6e66;
}
.hover-tip-grade {
  color: #35866b;
}

/* 详情抽屉打开时，AI 卡与视角工具左移让位，避免遮挡 */
/* 抽屉打开时整组左移让位；小屏下隐藏 AI 卡但保留视角按钮 */
@media (max-width: 1400px) {
  .corner-stack.shifted { right: 424px; }
  .corner-stack.shifted .ai-float { display: none; }
}
@media (max-width: 900px) {
  .corner-stack { right: 12px; bottom: 12px; }
  .ai-float { width: calc(100vw - 24px); }
  .corner-stack.shifted { right: 12px; }
}

/* 右下角视角工具条 */
.corner-stack {
  position: absolute;
  right: 16px;
  bottom: 16px;
  z-index: 11;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  transition: right 0.3s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.corner-stack.shifted { right: 424px; }

.view-tools {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tool-btn {
  width: 38px;
  height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  border: 1px solid rgba(255, 255, 255, 0.6);
  background: rgba(255, 255, 255, 0.82);
  color: #35866b;
  cursor: pointer;
  box-shadow: 0 6px 18px -8px rgba(37, 61, 53, 0.35);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: background 0.18s ease, transform 0.12s ease;
}
.tool-btn:hover {
  background: #ffffff;
}
.tool-btn:active {
  transform: translateY(1px);
}

.dotted {
  border-bottom: 1px dashed rgba(53, 134, 107, 0.45);
  cursor: help;
}

/* 右下角 AI 解读悬浮卡 */
.ai-float {
  width: min(460px, calc(100vw - 32px));
  max-height: 46vh;
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.93);
  border: 1px solid rgba(206, 219, 210, 0.85);
  border-radius: 14px;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 14px 36px -18px rgba(37, 61, 53, 0.28);
  backdrop-filter: blur(10px) saturate(118%);
  -webkit-backdrop-filter: blur(10px) saturate(118%);
  z-index: 10;
  overflow: hidden;
  transition: max-height 0.25s ease;
}
.ai-float.collapsed {
  max-height: 44px;
}
.ai-float-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  cursor: pointer;
  border-bottom: 1px solid rgba(37, 61, 53, 0.07);
  background: linear-gradient(90deg, #f0f6f2, #ffffff);
  flex-shrink: 0;
}
.ai-float-title {
  font-size: 13px;
  font-weight: 600;
  color: #253d35;
  display: flex;
  align-items: center;
  gap: 6px;
}
.ai-float-title .el-icon {
  color: #35866b;   /* 绿色强调图标 */
}
.ai-float-site {
  font-weight: 400;
  color: #35866b;
  font-size: 12px;
}
.ai-float-toggle {
  color: #7b8b84;
}
.ai-float-body {
  overflow-y: auto;
  padding: 8px 12px 12px;
}
/* 覆盖组件内部的卡片外壳，融入悬浮面板 */
.ai-float-body :deep(.chart-box) {
  box-shadow: none;
  border: none;
  padding: 0;
  margin: 0;
  background: transparent;
}
.ai-float-body :deep(.chart-title) {
  display: none;
}
.ai-float-body :deep(.ai-insight-para) {
  font-size: 13px;
  line-height: 1.8;
  margin-bottom: 10px;
}

.campus-wrap {
  position: relative;
  width: 100%;
  height: calc(100vh - 88px);
  border-radius: 8px;
  overflow: hidden;
  background: #eaeef0;   /* 浅色沙盘底色（WebGL 失败时的回退背景） */
}

.canvas-host { width: 100%; height: 100%; }

.overlay-panel {
  position: absolute;
  z-index: 10;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(206, 219, 210, 0.85);
  border-radius: 16px;
  padding: 14px 16px;
  color: #253d35;
  backdrop-filter: blur(18px) saturate(118%);
  -webkit-backdrop-filter: blur(18px) saturate(118%);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 18px 44px -22px rgba(37, 61, 53, 0.3),
    0 4px 12px -8px rgba(37, 61, 53, 0.1);
}

.left-panel { left: 14px; top: 14px; width: 244px; }
.right-panel { right: 14px; top: 14px; width: 214px; }
/* 旧详情面板已由右侧玻璃抽屉取代 */
.detail-panel { right: 14px; bottom: 14px; width: 300px; }
/* 抽屉打开时，右上角概览让位，避免被遮挡 */
.right-panel.hidden-by-drawer { opacity: 0; pointer-events: none; }

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #253d35;
  gap: 8px;
  margin-bottom: 11px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.close-btn { cursor: pointer; color: #8ba0c0; }
.close-btn:hover { color: #ef4444; }

.field { margin-bottom: 11px; }
.field label {
  display: block;
  font-size: 11.5px;
  font-weight: 500;
  color: #5d6e66;
  margin-bottom: 6px;
  letter-spacing: 0.02em;
}

.legend-title { font-size: 11px; color: #7b8b84; margin-bottom: 6px; }
.legend-item {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  padding: 2.5px 0;
  color: #46584f;
}
.swatch { width: 11px; height: 11px; border-radius: 2px; flex-shrink: 0; }

.hint { font-size: 11px; color: #7b8b84; line-height: 1.9; }

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 12px;
  padding: 8px 0;
  border-bottom: 1px solid rgba(37, 61, 53, 0.08);
}
.stat-row:last-child { border-bottom: none; }
.stat-row span {
  color: #6f8085;
  flex-shrink: 0;
  overflow-wrap: break-word;
}
.stat-row b {
  color: #253d35;
  font-size: 16px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.stat-row b.warn { color: #c26b62; }

.canvas-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #5d6e66;
  font-size: 13px;
  background: rgba(247, 249, 246, 0.86);
  z-index: 20;
}

.slide-enter-active, .slide-leave-active { transition: all 0.22s ease; }
.slide-enter-from, .slide-leave-to { opacity: 0; transform: translateY(14px); }

:deep(.el-descriptions__label) { width: 84px; font-size: 12px; }
:deep(.el-descriptions__content) { font-size: 12px; }
</style>
