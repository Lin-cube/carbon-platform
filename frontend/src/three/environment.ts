import * as THREE from 'three'
import { PALETTE } from './palette'

/**
 * 浅色沙盘环境：地面底板、草坪、道路、步道、树木。
 *
 * <p>性能设计：
 * <ul>
 *   <li>树木用 <b>InstancedMesh</b>：几百棵树只占 2 次 draw call（树冠 + 树干）；</li>
 *   <li>地面/草坪/道路共用少量几何体，全部静态，不参与动画；</li>
 *   <li>所有几何体与材质都在返回值里登记，便于统一 dispose。</li>
 * </ul>
 *
 * <p>布局说明（重要）：本项目的建筑<b>没有真实经纬度</b>，园区是示意布局。
 * 因此环境只提供"沙盘感"的路网与绿化，不假装是真实地理模型。
 */
export interface CampusEnvironment {
  group: THREE.Group
  dispose: () => void
}

export function createEnvironment(opts: {
  /** 沙盘半径（由建筑网格规模决定） */
  radius: number
  /** 树木数量，默认按半径推算 */
  treeCount?: number
  /** 树木密度系数（画质降级时 <1，减少实例数量） */
  density?: number
}): CampusEnvironment {
  const group = new THREE.Group()
  group.name = 'campus-environment'

  const disposables: Array<{ dispose: () => void }> = []
  const track = <T extends { dispose: () => void }>(o: T): T => {
    disposables.push(o)
    return o
  }

  const R = opts.radius

  // ── 1. 地面底板：带厚度的圆角沙盘盘体 ────────────────────────────
  const plateH = 6
  const plateGeo = track(new THREE.CylinderGeometry(R, R * 1.005, plateH, 96, 1, false))
  const plateMat = track(new THREE.MeshStandardMaterial({
    color: PALETTE.ground,
    roughness: 0.92,
    metalness: 0.02,
  }))
  const plate = new THREE.Mesh(plateGeo, plateMat)
  plate.position.y = -plateH / 2
  plate.receiveShadow = true
  group.add(plate)

  // 盘体侧面加一圈稍深的描边，强化"微缩模型"的边界
  const rimGeo = track(new THREE.TorusGeometry(R * 0.998, 0.9, 8, 128))
  const rimMat = track(new THREE.MeshStandardMaterial({
    color: PALETTE.groundSide, roughness: 0.9, metalness: 0.05,
  }))
  const rim = new THREE.Mesh(rimGeo, rimMat)
  rim.rotation.x = Math.PI / 2
  rim.position.y = -plateH + 0.4
  group.add(rim)

  // ── 2. 草坪：若干大块不规则区域（用圆角多边形）────────────────────
  const lawnGroup = new THREE.Group()
  const lawnMat = track(new THREE.MeshStandardMaterial({
    color: PALETTE.lawn, roughness: 1, metalness: 0,
  }))
  const lawnMatAlt = track(new THREE.MeshStandardMaterial({
    color: PALETTE.lawnAlt, roughness: 1, metalness: 0,
  }))
  // 四块分布在四周的草坪，避开中央建筑群
  const lawns: Array<[number, number, number, number, THREE.Material]> = [
    [-R * 0.62, -R * 0.55, R * 0.42, R * 0.3, lawnMat],
    [R * 0.6, -R * 0.5, R * 0.36, R * 0.26, lawnMatAlt],
    [-R * 0.58, R * 0.6, R * 0.34, R * 0.24, lawnMatAlt],
    [R * 0.62, R * 0.58, R * 0.4, R * 0.28, lawnMat],
  ]
  for (const [x, z, w, d, mat] of lawns) {
    const g = track(new THREE.PlaneGeometry(w, d, 1, 1))
    const m = new THREE.Mesh(g, mat)
    m.rotation.x = -Math.PI / 2
    m.position.set(x, 0.06, z)
    m.receiveShadow = true
    lawnGroup.add(m)
  }
  group.add(lawnGroup)

  // ── 3. 环形主干道 + 十字步道 ──────────────────────────────────────
  const roadGroup = new THREE.Group()
  const roadMat = track(new THREE.MeshStandardMaterial({
    color: PALETTE.road, roughness: 1, metalness: 0,
  }))
  // 环形路
  const ringGeo = track(new THREE.RingGeometry(R * 0.72, R * 0.8, 96))
  const ring = new THREE.Mesh(ringGeo, roadMat)
  ring.rotation.x = -Math.PI / 2
  ring.position.y = 0.04
  ring.receiveShadow = true
  roadGroup.add(ring)
  // 十字步道
  const crossGeo = track(new THREE.PlaneGeometry(R * 1.9, R * 0.07))
  for (const rot of [0, Math.PI / 2]) {
    const c = new THREE.Mesh(crossGeo, roadMat)
    c.rotation.x = -Math.PI / 2
    c.rotation.z = rot
    c.position.y = 0.04
    c.receiveShadow = true
    roadGroup.add(c)
  }
  group.add(roadGroup)

  // ── 4. 树木：InstancedMesh（树冠 + 树干各一次 draw call）──────────
  const density = opts.density ?? 1
  const count = opts.treeCount
    ?? Math.max(24, Math.round(Math.min(360, R / 2.2) * density))
  const crownGeo = track(new THREE.ConeGeometry(2.3, 7.4, 6))
  const crownMat = track(new THREE.MeshStandardMaterial({
    color: PALETTE.treeLeaf, roughness: 0.95, metalness: 0,
  }))
  const trunkGeo = track(new THREE.CylinderGeometry(0.34, 0.44, 2.6, 5))
  const trunkMat = track(new THREE.MeshStandardMaterial({
    color: PALETTE.treeTrunk, roughness: 1, metalness: 0,
  }))

  const crowns = new THREE.InstancedMesh(crownGeo, crownMat, count)
  const trunks = new THREE.InstancedMesh(trunkGeo, trunkMat, count)
  crowns.castShadow = true
  crowns.receiveShadow = true
  trunks.castShadow = true

  const dummy = new THREE.Object3D()
  const color = new THREE.Color()
  let placed = 0
  let guard = 0
  while (placed < count && guard < count * 12) {
    guard++
    // 在盘体上随机取点，避开中央建筑区与环形路
    const a = Math.random() * Math.PI * 2
    const rr = R * (0.82 + Math.random() * 0.14)
    const x = Math.cos(a) * rr
    const z = Math.sin(a) * rr

    const s = 0.85 + Math.random() * 0.7
    dummy.position.set(x, 1.3 * s, z)
    dummy.scale.set(s, s, s)
    dummy.rotation.set(0, Math.random() * Math.PI, 0)
    dummy.updateMatrix()
    trunks.setMatrixAt(placed, dummy.matrix)

    dummy.position.set(x, (2.6 + 3.1) * s, z)
    dummy.updateMatrix()
    crowns.setMatrixAt(placed, dummy.matrix)

    // 树冠颜色轻微抖动，避免一片死色
    color.setHex(PALETTE.treeLeaf)
    color.offsetHSL(0, (Math.random() - 0.5) * 0.06, (Math.random() - 0.5) * 0.06)
    crowns.setColorAt(placed, color)

    placed++
  }
  crowns.count = placed
  trunks.count = placed
  crowns.instanceMatrix.needsUpdate = true
  trunks.instanceMatrix.needsUpdate = true
  if (crowns.instanceColor) crowns.instanceColor.needsUpdate = true
  group.add(trunks, crowns)

  return {
    group,
    dispose: () => {
      for (const d of disposables) d.dispose()
      // InstancedMesh 自身不需要 dispose（几何体/材质已在上面的列表里）
    },
  }
}
