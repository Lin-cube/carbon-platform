import * as THREE from 'three'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'
import { PALETTE } from './palette'

/**
 * 微缩建筑模型工厂。
 *
 * <h3>视觉构成</h3>
 * 底座 → 主体 → 楼层分隔带 → 规则窗户 / 玻璃幕墙 → 屋顶 → 屋顶设备。
 * 建筑类型决定层数、窗格密度、玻璃占比与屋顶形态，使其不是"一个方块"。
 *
 * <h3>性能设计</h3>
 * <ul>
 *   <li><b>底座 + 主体 + 楼层带 + 屋顶合并成一个 geometry</b>（顶点色区分材质差异），
 *       每栋建筑的主体只占 <b>1 次 draw call</b>；</li>
 *   <li><b>窗户用 InstancedMesh</b>，几何体与玻璃材质全局共享；</li>
 *   <li>评级强调（屋顶边缘 + 底座光环）是独立小物体，便于换色；</li>
 *   <li>所有资源登记在 dispose() 里，切换园区不泄漏。</li>
 * </ul>
 *
 * <h3>数据诚实性</h3>
 * 建筑<b>没有真实高度、尺寸与经纬度</b>。这里的高度由"建筑面积 → 层数"推导，
 * 是<b>示意比例</b>，不是真实楼高；平面位置由上层网格布局决定。
 */

export interface BuildingModelInput {
  buildingId: string
  buildingTypeCn?: string | null
  areaM2?: number | null
}

export interface BuildingModel {
  group: THREE.Group
  /** 主体合并网格（含底座、楼层带、屋顶） */
  body: THREE.Mesh
  /** 窗户实例网格 */
  windows: THREE.InstancedMesh | null
  /** 屋顶边缘强调条（随评级变色） */
  accent: THREE.Mesh
  /** 底座光环（随评级变色，半透明） */
  halo: THREE.Mesh
  /** 是否设置了异常脉冲（外层可做动画） */
  hasPulse: boolean
  dispose: () => void
}

/** 建筑类型 → 形态参数（层数比例、窗密度、玻璃占比、屋顶形态） */
interface TypeProfile {
  /** 层高系数（相对） */
  floorScale: number
  /** 每面窗列数 */
  winCols: number
  /** 玻璃幕墙占比 0~1（越大越像办公楼） */
  glassRatio: number
  /** 屋顶形态 */
  roof: 'flat' | 'equip' | 'saw'
}

function profileOf(type?: string | null): TypeProfile {
  const t = (type || '').trim()
  if (t.includes('办公')) return { floorScale: 1.15, winCols: 5, glassRatio: 0.72, roof: 'equip' }
  if (t.includes('教学') || t.includes('教育')) {
    return { floorScale: 1.0, winCols: 4, glassRatio: 0.42, roof: 'flat' }
  }
  if (t.includes('宿舍') || t.includes('住宅')) {
    return { floorScale: 0.95, winCols: 3, glassRatio: 0.3, roof: 'flat' }
  }
  if (t.includes('医疗')) return { floorScale: 1.05, winCols: 4, glassRatio: 0.5, roof: 'equip' }
  if (t.includes('工业') || t.includes('厂房')) {
    return { floorScale: 0.7, winCols: 6, glassRatio: 0.22, roof: 'saw' }
  }
  if (t.includes('商业') || t.includes('零售')) {
    return { floorScale: 0.9, winCols: 5, glassRatio: 0.6, roof: 'flat' }
  }
  if (t.includes('仓储') || t.includes('停车')) {
    return { floorScale: 0.65, winCols: 5, glassRatio: 0.12, roof: 'flat' }
  }
  return { floorScale: 1.0, winCols: 4, glassRatio: 0.38, roof: 'flat' }
}

/** 尺寸推导：面积 → 占地边长与层数（示意比例，非真实楼高） */
export function footprintOf(areaM2: number | null | undefined) {
  const area = Math.max(areaM2 ?? 800, 120)
  // 占地：面积开方后压缩到合理视觉区间。
  // 系数与上下限经过实测调整：原来 0.085 / 6.5~15 会让建筑变成瘦高塔，
  // 0.115 / 8~19 更接近"微缩建筑沙盘"的敦实比例。
  const side = THREE.MathUtils.clamp(Math.sqrt(area) * 0.115, 8, 19)
  const w = side
  const d = side * (0.76 + ((area % 7) / 7) * 0.22)   // 轻微长宽比差异
  // 层数：由面积推层高感，3~9 层（太高会显得像塔楼）
  const floors = THREE.MathUtils.clamp(Math.round(Math.sqrt(area) / 16) + 2, 3, 9)
  return { w, d, floors }
}

// ── 全局共享的几何体与材质（跨建筑复用，减少显存与创建开销）──────────
let sharedWindowGeo: THREE.BoxGeometry | null = null
let sharedGlassMat: THREE.MeshStandardMaterial | null = null

function getWindowGeo(): THREE.BoxGeometry {
  if (!sharedWindowGeo) sharedWindowGeo = new THREE.BoxGeometry(1, 1, 0.28)
  return sharedWindowGeo
}

function getGlassMat(): THREE.MeshStandardMaterial {
  if (!sharedGlassMat) {
    sharedGlassMat = new THREE.MeshStandardMaterial({
      color: PALETTE.glass,
      roughness: 0.22,
      metalness: 0.45,
      transparent: true,
      opacity: 0.82,
    })
  }
  return sharedGlassMat
}

/**
 * 创建一个微缩建筑模型。
 *
 * @param input 建筑基础信息（来自 /api/energy/buildings，字段不做任何改造）
 * @param gradeColor 该建筑当前评级色（十六进制字符串），只用于强调条与光环
 */
export function createBuildingModel(
  input: BuildingModelInput,
  gradeColor: string,
): BuildingModel {
  const prof = profileOf(input.buildingTypeCn)
  const { w, d, floors } = footprintOf(input.areaM2)

  const floorH = 2.5 * prof.floorScale
  const bodyH = floors * floorH
  const plinthH = 1.2
  const roofH = 0.7
  const group = new THREE.Group()
  group.name = `building-${input.buildingId}`

  // ── 主体合并几何体：底座 / 主体 / 楼层带 / 屋顶 ─────────────────
  const parts: THREE.BufferGeometry[] = []

  const paint = (geo: THREE.BufferGeometry, hex: number) => {
    const c = new THREE.Color(hex)
    const n = geo.attributes.position.count
    const arr = new Float32Array(n * 3)
    for (let i = 0; i < n; i++) {
      arr[i * 3] = c.r
      arr[i * 3 + 1] = c.g
      arr[i * 3 + 2] = c.b
    }
    geo.setAttribute('color', new THREE.BufferAttribute(arr, 3))
    // 合并要求属性一致：删掉 uv 避免部分图元没有 uv 导致失败
    geo.deleteAttribute('uv')
    return geo
  }

  const push = (geo: THREE.BufferGeometry, hex: number, tx: number, ty: number, tz: number) => {
    paint(geo, hex)
    geo.translate(tx, ty, tz)
    parts.push(geo)
  }

  // 底座（比主体略大一圈）
  push(new THREE.BoxGeometry(w + 1.5, plinthH, d + 1.5), PALETTE.base, 0, plinthH / 2, 0)

  // 主体（四面墙同色，靠光照拉开体积；顶点色用于和楼层带区分）
  push(new THREE.BoxGeometry(w, bodyH, d), PALETTE.wall, 0, plinthH + bodyH / 2, 0)
  // 主体四角加一圈薄柱，强化体积轮廓（浅阴影色）
  const postR = 0.22
  for (const sx of [-1, 1]) {
    for (const sz of [-1, 1]) {
      const g = new THREE.BoxGeometry(postR * 2, bodyH, postR * 2)
      push(g, PALETTE.wallShade,
        sx * (w / 2 - postR), plinthH + bodyH / 2, sz * (d / 2 - postR))
    }
  }

  // 楼层分隔带：每层一道薄薄的横向条（评级色由 accent 体现，这里用中性带）
  for (let f = 1; f < floors; f++) {
    const y = plinthH + f * floorH
    const g = new THREE.BoxGeometry(w + 0.24, 0.16, d + 0.24)
    push(g, PALETTE.band, 0, y, 0)
  }

  // 屋顶
  push(new THREE.BoxGeometry(w + 0.5, roofH, d + 0.5),
    PALETTE.roof, 0, plinthH + bodyH + roofH / 2, 0)

  // 屋顶设备（办公楼/医疗）或锯齿屋顶（工业）
  if (prof.roof === 'equip') {
    const eqW = w * 0.34
    const eqD = d * 0.3
    push(new THREE.BoxGeometry(eqW, 1.1, eqD), PALETTE.base,
      w * 0.16, plinthH + bodyH + roofH + 0.55, d * 0.12)
    push(new THREE.BoxGeometry(eqW * 0.5, 0.7, eqD * 0.6), PALETTE.band,
      -w * 0.18, plinthH + bodyH + roofH + 0.35, -d * 0.14)
  } else if (prof.roof === 'saw') {
    const teeth = 4
    for (let i = 0; i < teeth; i++) {
      const g = new THREE.BoxGeometry(w / teeth * 0.72, 0.9, d * 0.9)
      g.rotateZ(Math.PI * 0.06)
      push(g, PALETTE.roof,
        -w / 2 + (i + 0.5) * (w / teeth), plinthH + bodyH + roofH + 0.5, 0)
    }
  }

  const mergedGeo = mergeGeometries(parts, false) ?? new THREE.BoxGeometry(w, bodyH, d)
  for (const p of parts) p.dispose()

  const bodyMat = new THREE.MeshStandardMaterial({
    vertexColors: true,
    roughness: 0.78,
    metalness: 0.06,
  })
  const body = new THREE.Mesh(mergedGeo, bodyMat)
  body.castShadow = true
  body.receiveShadow = true
  body.position.y = 0
  group.add(body)

  // ── 窗户：InstancedMesh ─────────────────────────────────────────
  const winRows = Math.max(floors - 1, 2)
  const colsPerFace = prof.winCols
  const winW = Math.min(1.5, (w / colsPerFace) * 0.52)
  const winH = Math.min(1.15, floorH * 0.42)

  const matrices: THREE.Matrix4[] = []
  const dummy = new THREE.Object3D()

  const addFace = (faceWidth: number, axis: 'x' | 'z', sign: 1 | -1, offset: number, cols: number) => {
    for (let r = 0; r < winRows; r++) {
      for (let c = 0; c < cols; c++) {
        const u = (c - (cols - 1) / 2) * (faceWidth / cols)
        const y = plinthH + (r + 0.75) * floorH
        if (axis === 'z') {
          dummy.position.set(u, y, sign * (d / 2 + offset))
          dummy.rotation.set(0, 0, 0)
        } else {
          dummy.position.set(sign * (w / 2 + offset), y, u)
          dummy.rotation.set(0, Math.PI / 2, 0)
        }
        dummy.scale.set(winW, winH, 1)
        dummy.updateMatrix()
        matrices.push(dummy.matrix.clone())
      }
    }
  }

  // 玻璃占比高 → 窗更宽、列数更多
  const glassCols = Math.max(1, Math.round(colsPerFace * (0.7 + prof.glassRatio * 0.6)))
  addFace(w, 'z', 1, 0.1, glassCols)
  addFace(w, 'z', -1, 0.1, glassCols)
  const sideCols = Math.max(1, Math.round(glassCols * 0.75))
  addFace(d, 'x', 1, 0.1, sideCols)
  addFace(d, 'x', -1, 0.1, sideCols)

  let windows: THREE.InstancedMesh | null = null
  if (matrices.length > 0) {
    windows = new THREE.InstancedMesh(getWindowGeo(), getGlassMat(), matrices.length)
    matrices.forEach((m, i) => windows!.setMatrixAt(i, m))
    windows.instanceMatrix.needsUpdate = true
    windows.castShadow = false
    windows.receiveShadow = false
    group.add(windows)
  }

  // ── 评级强调：屋顶边缘一圈 + 底座光环 ────────────────────────────
  // 只做「屋顶边缘」的边框：四条细边条合并成一个几何体，
  // 避免整块彩色顶盖盖住屋顶（需求要求评级只影响局部）
  const accentColor = new THREE.Color(gradeColor)
  const barT = 0.34          // 边条截面
  const barLen = w + 0.7
  const barLenD = d + 0.7
  const accentParts: THREE.BufferGeometry[] = []
  const edgeY = plinthH + bodyH + roofH + 0.1
  const mkBar = (bw: number, bd: number, x: number, z: number) => {
    const g = new THREE.BoxGeometry(bw, barT, bd)
    g.translate(x, edgeY, z)
    return g
  }
  accentParts.push(
    mkBar(barLen, barT, 0, (barLenD - barT) / 2),
    mkBar(barLen, barT, 0, -(barLenD - barT) / 2),
    mkBar(barT, barLenD - barT * 2, (barLen - barT) / 2, 0),
    mkBar(barT, barLenD - barT * 2, -(barLen - barT) / 2, 0),
  )
  const accentGeo = mergeGeometries(accentParts, false) ?? new THREE.BoxGeometry(barLen, barT, barT)
  for (const g of accentParts) g.dispose()
  const accentMat = new THREE.MeshStandardMaterial({
    color: accentColor,
    emissive: accentColor,
    emissiveIntensity: 0.45,
    roughness: 0.45,
    metalness: 0.1,
  })
  const accent = new THREE.Mesh(accentGeo, accentMat)
  group.add(accent)

  const haloGeo = new THREE.RingGeometry(
    Math.max(w, d) * 0.62, Math.max(w, d) * 0.78, 40,
  )
  const haloMat = new THREE.MeshBasicMaterial({
    color: accentColor,
    transparent: true,
    opacity: 0.3,
    side: THREE.DoubleSide,
    depthWrite: false,
  })
  const halo = new THREE.Mesh(haloGeo, haloMat)
  halo.rotation.x = -Math.PI / 2
  halo.position.y = 0.12
  group.add(halo)

  const hasPulse = false

  return {
    group,
    body,
    windows,
    accent,
    halo,
    hasPulse,
    dispose: () => {
      mergedGeo.dispose()
      bodyMat.dispose()
      accentGeo.dispose()
      accentMat.dispose()
      haloGeo.dispose()
      haloMat.dispose()
      // 窗户几何体与玻璃材质是全局共享的，不在这里 dispose
    },
  }
}

/** 更新一栋建筑的评级强调色与光环色 */
export function applyGradeColor(model: BuildingModel, gradeColor: string) {
  const c = new THREE.Color(gradeColor)
  const am = model.accent.material as THREE.MeshStandardMaterial
  am.color.copy(c)
  am.emissive.copy(c)
  ;(model.halo.material as THREE.MeshBasicMaterial).color.copy(c)
}

/** 高亮 / 变暗（选中与聚焦时使用） */
export function setBuildingEmphasis(
  model: BuildingModel,
  opts: { highlight?: boolean; dim?: boolean },
) {
  const bodyMat = model.body.material as THREE.MeshStandardMaterial
  if (opts.highlight) {
    bodyMat.emissive.setHex(PALETTE.highlight)
    bodyMat.emissiveIntensity = 0.16
  } else {
    bodyMat.emissiveIntensity = 0
  }
  const dim = !!opts.dim
  bodyMat.transparent = dim
  bodyMat.opacity = dim ? 0.38 : 1
  if (model.windows) {
    const gm = model.windows.material as THREE.MeshStandardMaterial
    // 共享材质，不能按栋改透明度；这里通过 visible 控制
    model.windows.visible = !dim
  }
  ;(model.halo.material as THREE.MeshBasicMaterial).opacity = dim ? 0.1 : 0.3
}
