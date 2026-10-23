<template>
  <div class="campus-wrap">
    <!-- 左侧控制面板 -->
    <div class="overlay-panel left-panel">
      <div class="panel-title">3D 园区监控</div>

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
          <span class="swatch" :style="{ background: l.color }"></span>{{ l.label }}
        </div>
      </div>

      <el-divider style="margin: 12px 0" />

      <div class="hint">
        <div>· 左键拖拽旋转，滚轮缩放</div>
        <div>· 悬停高亮，点击聚焦查看详情</div>
        <div>· 高度为示意比例，非真实楼高</div>
      </div>
    </div>

    <!-- 右侧统计 -->
    <div class="overlay-panel right-panel">
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

    <!-- 楼栋详情 -->
    <transition name="slide">
      <div v-if="selected" class="overlay-panel detail-panel">
        <div class="panel-title">
          {{ selected.buildingId }}
          <el-icon class="close-btn" @click="selected = null"><Close /></el-icon>
        </div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="建筑类型">{{ selected.buildingTypeCn || '--' }}</el-descriptions-item>
          <el-descriptions-item label="建筑面积">{{ fmtNum(selected.areaM2, 0) }} m²</el-descriptions-item>
          <el-descriptions-item label="年用电量">{{ fmtNum(selected.electricityKwhYear) }} kWh</el-descriptions-item>
          <el-descriptions-item label="能耗强度">{{ selected.euiKwhM2Year ?? '--' }} kWh/m²·年</el-descriptions-item>
          <el-descriptions-item label="能耗评级">
            <el-tag :color="gradeColor(selected.energyGrade)" effect="dark" size="small"
                    style="border: none; color: #fff">
              {{ selected.energyGrade || '未评级' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="数据分级">
            <el-tag size="small" :type="selected.dataGrade === 'A' ? 'success' : 'warning'" effect="plain">
              {{ selected.dataGrade }} 级 · {{ selected.dataGrade === 'A' ? '独立计量' : '站点分摊' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="异常率">
            {{ selected.anomalyRate != null ? (selected.anomalyRate * 100).toFixed(2) + '%' : '--' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-button size="small" type="primary" style="width: 100%; margin-top: 10px"
                   @click="gotoAnomaly">查看异常诊断</el-button>
      </div>
    </transition>

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

    <!-- 右下角视角工具 -->
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

    <!-- 加载/空状态 -->
    <div v-if="loading" class="canvas-mask"><el-icon class="is-loading" :size="26"><Loading /></el-icon>
      <div>正在构建 3D 场景…</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, reactive, computed, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { Close, Loading, Aim } from '@element-plus/icons-vue'
import { energyApi } from '@/api'
import AiInsight from '@/components/AiInsight.vue'
import { MagicStick, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import { fmtNum, gradeColor } from '@/utils/echarts'
import {
  PALETTE, GRADE_COLOR, EUI_COLOR, ANOMALY_COLOR, NEUTRAL,
} from '@/three/palette'
import { createEnvironment, type CampusEnvironment } from '@/three/environment'
import {
  createBuildingModel, applyGradeColor, setBuildingEmphasis,
  footprintOf, type BuildingModel,
} from '@/three/buildingModel'
import type { BuildingRow, SiteRow } from '@/types/api'

const router = useRouter()

const canvasHost = ref<HTMLDivElement>()
const sites = ref<SiteRow[]>([])
const siteId = ref('')
/** AI 解读悬浮面板是否展开 */
const aiOpen = ref(true)

/** 点击 AI 解读里的异常建筑 → 跳到异常诊断页并自动诊断该建筑 */
function onFocusBuilding(buildingId: string) {
  router.push({ path: '/anomaly', query: { buildingId } })
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

const legendItems = computed(() => {
  if (colorBy.value === 'grade') {
    return [
      { label: 'A 优秀', color: GRADE_COLOR.A },
      { label: 'B 良好', color: GRADE_COLOR.B },
      { label: 'C 一般', color: GRADE_COLOR.C },
      { label: 'D 较差', color: GRADE_COLOR.D },
      { label: 'E 差', color: GRADE_COLOR.E },
      { label: '未评级', color: NEUTRAL },
    ]
  }
  if (colorBy.value === 'anomaly') {
    return [
      { label: '正常（<2%）', color: ANOMALY_COLOR[0].color },
      { label: '轻度（2-5%）', color: ANOMALY_COLOR[1].color },
      { label: '偏高（5-10%）', color: ANOMALY_COLOR[2].color },
      { label: '严重（>10%）', color: ANOMALY_COLOR[3].color },
    ]
  }
  return [
    { label: '低强度（<60）', color: EUI_COLOR[0].color },
    { label: '中低（60-100）', color: EUI_COLOR[1].color },
    { label: '中等（100-150）', color: EUI_COLOR[2].color },
    { label: '较高（150-200）', color: EUI_COLOR[3].color },
    { label: '高强度（>200）', color: EUI_COLOR[4].color },
  ]
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
  renderer = new THREE.WebGLRenderer({
    antialias: true, alpha: false, preserveDrawingBuffer: captureMode,
  })
  renderer.setSize(w, h)
  // 设备像素比上限 2：高分屏不至于把填充率拉满
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.shadowMap.enabled = true
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
  sun.shadow.mapSize.set(1024, 1024)
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
        const img = document.createElement('img')
        img.src = url
        img.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;'
          + 'object-fit:contain;z-index:9999;background:#ffffff'
        document.body.appendChild(img)
      } catch (e) {
        console.error('snapshot failed', e)
      }
    }, 2500)
  }
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
  const dist = Math.max(60, camera.position.distanceTo(target) * 0.55)
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
  env = createEnvironment({ radius })
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

  if (needsRender || moving) {
    renderer.render(scene, camera)
    needsRender = false
  }
}

// ---------------------------------------------------------------- 交互
function applyColors() {
  const list = buildings.value
  for (const b of list) {
    const model = modelMap.get(b.buildingId)
    if (model) applyGradeColor(model, colorOf(b))
  }
  markDirty()
}

async function rebuild() {
  if (!siteId.value) return
  loading.value = true
  try {
    const list = await energyApi.buildings({ siteId: siteId.value, limit: 300 })
    buildings.value = list
    blobs.value = list

    siteStat.electricity = list.reduce((s, b) => s + (b.electricityKwhYear || 0), 0)
    siteStat.carbon = list.reduce(
      (s, b) => s + (b.electricityKwhYear || 0) * 0.581, 0)
    siteStat.anomalyCount = list.filter((b) => (b.anomalyRate ?? 0) > 0.05).length

    const grades = list.map((b) => b.energyGrade).filter((g): g is string => !!g && g !== '未评级')
    const order = ['A', 'B', 'C', 'D', 'E']
    siteStat.avgGrade = grades.length
      ? order[Math.round(grades.reduce((s, g) => s + order.indexOf(g), 0) / grades.length)] || '--'
      : '--'

    await nextTick()
    buildBuildings()
  } finally {
    loading.value = false
  }
}

function gotoAnomaly() {
  router.push({ path: '/anomaly', query: { buildingId: selected.value?.buildingId } })
}

onMounted(async () => {
  await nextTick()

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

/* 右下角视角工具条 */
.view-tools {
  position: absolute;
  right: 18px;
  bottom: 18px;
  z-index: 12;
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
  border-bottom: 1px dashed rgba(255, 255, 255, 0.35);
  cursor: help;
}

/* 右下角 AI 解读悬浮卡 */
.ai-float {
  position: absolute;
  right: 16px;
  bottom: 16px;
  width: 460px;
  max-height: 46vh;
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.97);
  border-radius: 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.18);
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
  border-bottom: 1px solid #ebeef5;
  background: linear-gradient(90deg, #f0f7ff, #ffffff);
  flex-shrink: 0;
}
.ai-float-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2d3d;
  display: flex;
  align-items: center;
  gap: 6px;
}
.ai-float-site {
  font-weight: 400;
  color: #409eff;
  font-size: 12px;
}
.ai-float-toggle {
  color: #909399;
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
  background: #0b1220;
}

.canvas-host { width: 100%; height: 100%; }

.overlay-panel {
  position: absolute;
  z-index: 10;
  background: rgba(15, 27, 47, 0.9);
  border: 1px solid #1e3a5f;
  border-radius: 8px;
  padding: 13px 15px;
  color: #dbe7f7;
  backdrop-filter: blur(8px);
  box-shadow: 0 6px 22px rgba(0, 0, 0, 0.4);
}

.left-panel { left: 14px; top: 14px; width: 232px; }
.right-panel { right: 14px; top: 14px; width: 208px; }
.detail-panel { right: 14px; bottom: 14px; width: 300px; }

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #7dd3fc;
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
  font-size: 11px;
  color: #8ba0c0;
  margin-bottom: 5px;
}

.legend-title { font-size: 11px; color: #8ba0c0; margin-bottom: 6px; }
.legend-item {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  padding: 2.5px 0;
  color: #c3d4ea;
}
.swatch { width: 11px; height: 11px; border-radius: 2px; flex-shrink: 0; }

.hint { font-size: 11px; color: #7c95bb; line-height: 1.85; }

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  padding: 5px 0;
  border-bottom: 1px dashed #1e3a5f;
}
.stat-row:last-child { border-bottom: none; }
.stat-row span { color: #8ba0c0; }
.stat-row b { color: #e6edf7; font-variant-numeric: tabular-nums; }
.stat-row b.warn { color: #f97316; }

.canvas-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #7dd3fc;
  font-size: 13px;
  background: rgba(11, 18, 32, 0.75);
  z-index: 20;
}

.slide-enter-active, .slide-leave-active { transition: all 0.22s ease; }
.slide-enter-from, .slide-leave-to { opacity: 0; transform: translateY(14px); }

:deep(.el-descriptions__label) { width: 84px; font-size: 12px; }
:deep(.el-descriptions__content) { font-size: 12px; }
</style>
