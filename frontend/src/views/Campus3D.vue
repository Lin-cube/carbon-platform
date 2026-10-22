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
        <div>· 点击楼栋查看详情</div>
        <div>· 粒子流表示用能强度</div>
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
import { Close, Loading } from '@element-plus/icons-vue'
import { energyApi } from '@/api'
import AiInsight from '@/components/AiInsight.vue'
import { MagicStick, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import { fmtNum, gradeColor } from '@/utils/echarts'
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
      { label: 'A 优秀', color: '#22c55e' }, { label: 'B 良好', color: '#84cc16' },
      { label: 'C 一般', color: '#eab308' }, { label: 'D 较差', color: '#f97316' },
      { label: 'E 差', color: '#ef4444' }, { label: '未评级', color: '#9ca3af' }
    ]
  }
  if (colorBy.value === 'anomaly') {
    return [
      { label: '正常（<2%）', color: '#22c55e' },
      { label: '轻度（2-5%）', color: '#eab308' },
      { label: '偏高（5-10%）', color: '#f97316' },
      { label: '严重（>10%）', color: '#ef4444' }
    ]
  }
  return [
    { label: '低强度', color: '#22c55e' }, { label: '中低', color: '#84cc16' },
    { label: '中等', color: '#eab308' }, { label: '较高', color: '#f97316' },
    { label: '高强度', color: '#ef4444' }
  ]
})

// ---------------------------------------------------------------- Three.js
let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let rafId = 0
let resizeObserver: ResizeObserver | null = null
/** 楼栋 mesh 与数据的映射（用于射线拾取） */
const meshMap = new Map<THREE.Mesh, BuildingRow>()
let particleSystems: THREE.Points[] = []

function initThree() {
  if (!canvasHost.value) return
  const host = canvasHost.value
  const w = host.clientWidth
  const h = host.clientHeight

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x0b1220)
  scene.fog = new THREE.Fog(0x0b1220, 260, 620)

  camera = new THREE.PerspectiveCamera(48, w / h, 0.5, 3000)
  camera.position.set(120, 110, 150)

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  renderer.setSize(w, h)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  host.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.maxPolarAngle = Math.PI * 0.47
  controls.minDistance = 30
  controls.maxDistance = 700

  // 光照
  scene.add(new THREE.AmbientLight(0xffffff, 0.55))
  const dir = new THREE.DirectionalLight(0xffffff, 1.0)
  dir.position.set(160, 260, 120)
  dir.castShadow = true
  dir.shadow.mapSize.set(2048, 2048)
  dir.shadow.camera.near = 1
  dir.shadow.camera.far = 900
  const d = 320
  Object.assign(dir.shadow.camera, { left: -d, right: d, top: d, bottom: -d })
  scene.add(dir)
  const hemi = new THREE.HemisphereLight(0x8ec5ff, 0x0b1220, 0.45)
  scene.add(hemi)

  // 地面网格
  const grid = new THREE.GridHelper(1400, 70, 0x1e3a5f, 0x14263f)
  ;(grid.material as THREE.Material).transparent = true
  ;(grid.material as THREE.Material).opacity = 0.55
  scene.add(grid)

  // 地面反射底板
  const plane = new THREE.Mesh(
    new THREE.PlaneGeometry(1400, 1400),
    new THREE.MeshStandardMaterial({ color: 0x0d1626, roughness: 0.95, metalness: 0.1 })
  )
  plane.rotation.x = -Math.PI / 2
  plane.position.y = -0.6
  plane.receiveShadow = true
  scene.add(plane)

  renderer.domElement.addEventListener('click', onCanvasClick)
  window.addEventListener('resize', onResize)
  resizeObserver = new ResizeObserver(onResize)
  resizeObserver.observe(host)

  animate()
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

function onCanvasClick(ev: MouseEvent) {
  if (!renderer || !camera) return
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const hits = raycaster.intersectObjects([...meshMap.keys()], false)
  if (hits.length > 0) {
    const mesh = hits[0].object as THREE.Mesh
    const b = meshMap.get(mesh) || null
    selected.value = b
    // 高亮选中楼栋
    meshMap.forEach((_, m) => {
      const mat = m.material as THREE.MeshStandardMaterial
      mat.emissiveIntensity = 0
    })
    const mat = mesh.material as THREE.MeshStandardMaterial
    mat.emissive = new THREE.Color(0x2f7dff)
    mat.emissiveIntensity = 0.55
  } else {
    selected.value = null
  }
}

function clearScene() {
  if (!scene) return
  meshMap.forEach((_, mesh) => {
    scene!.remove(mesh)
    mesh.geometry.dispose()
    ;(mesh.material as THREE.Material).dispose()
  })
  meshMap.clear()
  for (const ps of particleSystems) {
    scene.remove(ps)
    ps.geometry.dispose()
    ;(ps.material as THREE.Material).dispose()
  }
  particleSystems = []
}

/** 计算某栋楼的颜色 */
function colorOf(b: BuildingRow): string {
  if (colorBy.value === 'anomaly') {
    const r = (b.anomalyRate ?? 0) * 100
    if (r >= 10) return '#ef4444'
    if (r >= 5) return '#f97316'
    if (r >= 2) return '#eab308'
    return '#22c55e'
  }
  if (colorBy.value === 'eui') {
    const e = b.euiKwhM2Year ?? 0
    if (e >= 200) return '#ef4444'
    if (e >= 150) return '#f97316'
    if (e >= 100) return '#eab308'
    if (e >= 60) return '#84cc16'
    return '#22c55e'
  }
  return gradeColor(b.energyGrade)
}

/** 构建楼栋 */
function buildBuildings() {
  if (!scene) return
  clearScene()
  const list = buildings.value
  if (list.length === 0) return

  // 网格布局：接近正方形的行列
  const cols = Math.ceil(Math.sqrt(list.length))
  const gap = 34

  const maxArea = Math.max(...list.map((b) => b.areaM2 || 1), 1)
  const maxEnergy = Math.max(...list.map((b) => b.electricityKwhYear || 1), 1)

  list.forEach((b, i) => {
    const row = Math.floor(i / cols)
    const col = i % cols
    const x = (col - (cols - 1) / 2) * gap
    const z = (row - (Math.ceil(list.length / cols) - 1) / 2) * gap

    // 底面尺寸按面积开方（视觉上面积大 → 占地大）
    const areaRatio = Math.sqrt((b.areaM2 || 100) / maxArea)
    const baseW = 8 + areaRatio * 14
    const baseD = baseW * 0.82

    // 高度按所选维度
    let hRatio: number
    if (heightBy.value === 'energy') {
      hRatio = Math.sqrt((b.electricityKwhYear || 0) / maxEnergy)
    } else {
      hRatio = areaRatio
    }
    const height = 6 + hRatio * 46

    const geom = new THREE.BoxGeometry(baseW, height, baseD)
    const col3 = new THREE.Color(colorOf(b))
    const mat = new THREE.MeshStandardMaterial({
      color: col3, roughness: 0.42, metalness: 0.28,
      emissive: col3, emissiveIntensity: 0.04
    })
    const mesh = new THREE.Mesh(geom, mat)
    mesh.position.set(x, height / 2, z)
    mesh.castShadow = true
    mesh.receiveShadow = true
    mesh.userData.building = b
    scene!.add(mesh)
    meshMap.set(mesh, b)

    // 楼顶轮廓线
    const edges = new THREE.LineSegments(
      new THREE.EdgesGeometry(geom),
      new THREE.LineBasicMaterial({ color: 0x7dd3fc, transparent: true, opacity: 0.22 })
    )
    edges.position.copy(mesh.position)
    scene!.add(edges)
  })

  // 粒子流：用能越强粒子越多（可视化"能量流动"）
  const hot = list
    .map((b, i) => ({ b, i }))
    .sort((a, c) => (c.b.electricityKwhYear || 0) - (a.b.electricityKwhYear || 0))
    .slice(0, 12)

  for (const { b, i } of hot) {
    const row = Math.floor(i / cols)
    const col = i % cols
    const x = (col - (cols - 1) / 2) * gap
    const z = (row - (Math.ceil(list.length / cols) - 1) / 2) * gap
    const areaRatio = Math.sqrt((b.areaM2 || 100) / maxArea)
    const height = 6 + (heightBy.value === 'energy'
      ? Math.sqrt((b.electricityKwhYear || 0) / maxEnergy)
      : areaRatio) * 46

    const count = 90
    const positions = new Float32Array(count * 3)
    const speeds = new Float32Array(count)
    for (let k = 0; k < count; k++) {
      positions[k * 3] = x + (Math.random() - 0.5) * 16
      positions[k * 3 + 1] = Math.random() * (height + 30)
      positions[k * 3 + 2] = z + (Math.random() - 0.5) * 16
      speeds[k] = 0.25 + Math.random() * 0.6
    }
    const pg = new THREE.BufferGeometry()
    pg.setAttribute('position', new THREE.BufferAttribute(positions, 3))
    pg.setAttribute('speed', new THREE.BufferAttribute(speeds, 1))

    const pm = new THREE.PointsMaterial({
      color: new THREE.Color(colorOf(b)).offsetHSL(0, 0, 0.25),
      size: 1.5, transparent: true, opacity: 0.85,
      blending: THREE.AdditiveBlending, depthWrite: false
    })
    const points = new THREE.Points(pg, pm)
    points.userData.baseY = height
    scene!.add(points)
    particleSystems.push(points)
  }
}

function animate() {
  rafId = requestAnimationFrame(animate)
  const t = performance.now() * 0.001

  // 粒子上升动画
  for (const ps of particleSystems) {
    const pos = ps.geometry.getAttribute('position') as THREE.BufferAttribute
    const spd = ps.geometry.getAttribute('speed') as THREE.BufferAttribute
    const baseY = (ps.userData.baseY as number) || 40
    const arr = pos.array as Float32Array
    for (let k = 0; k < pos.count; k++) {
      arr[k * 3 + 1] += spd.getX(k) * 0.4
      if (arr[k * 3 + 1] > baseY + 34) {
        arr[k * 3 + 1] = 1 + Math.random() * 6
      }
    }
    pos.needsUpdate = true
    ;(ps.material as THREE.PointsMaterial).opacity =
      0.55 + Math.sin(t * 1.6 + baseY) * 0.25
  }

  controls?.update()
  if (renderer && scene && camera) {
    renderer.render(scene, camera)
  }
}

// ---------------------------------------------------------------- 交互
function applyColors() {
  meshMap.forEach((b, mesh) => {
    const col = new THREE.Color(colorOf(b))
    const mat = mesh.material as THREE.MeshStandardMaterial
    mat.color = col
    mat.emissive = col
    mat.emissiveIntensity = 0.04
  })
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
  window.removeEventListener('resize', onResize)
  resizeObserver?.disconnect()
  if (renderer) {
    renderer.domElement.removeEventListener('click', onCanvasClick)
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
