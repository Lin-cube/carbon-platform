/**
 * 3D 园区视觉调色板（浅色沙盘模式）。
 *
 * <p>设计原则：
 * <ul>
 *   <li>建筑主体一律浅灰白 / 淡蓝玻璃 / 柔和青灰，<b>不整栋高饱和着色</b>；</li>
 *   <li>能耗评级只通过「楼层分隔带 + 屋顶边缘 + 底座光环」体现；</li>
 *   <li>分级颜色柔和可辨，未评级用中性灰；</li>
 *   <li>深色模式预留结构：把这里的数值换成暗色系即可，不改调用方。</li>
 * </ul>
 */
export const PALETTE = {
  /** 背景 / 雾：淡蓝灰，营造通透感 */
  background: 0xeaeef0,
  fog: 0xeaeef0,
  fogNear: 420,
  fogFar: 1500,

  /** 地面底板 */
  ground: 0xe0e5e7,
  groundSide: 0xccd5d8,
  /** 草坪 */
  lawn: 0xcfe0d2,
  lawnAlt: 0xc4d8c8,
  /** 道路与步道 */
  road: 0xc9d2d5,
  roadLine: 0xe9edee,
  /** 树冠 / 树干 */
  treeLeaf: 0x9dbfa8,
  treeLeafAlt: 0x8fb39c,
  treeTrunk: 0xc2b8ac,

  /** 建筑主体（浅灰白） */
  wall: 0xf4f6f7,
  /** 建筑背光面 / 侧墙，稍暗以拉开体积感 */
  wallShade: 0xe6eaec,
  /** 屋顶 */
  roof: 0xdfe5e8,
  /** 底座 */
  base: 0xdbe2e4,
  /** 楼层分隔带（默认中性，随评级变色） */
  band: 0xd0d8da,
  /** 玻璃幕墙 */
  glass: 0xc3dde8,
  glassDark: 0xa9c8d8,

  /** 高亮 / 选中 */
  highlight: 0x35866b,
  /** 异常定位光圈 */
  pulse: 0xc7685f,
} as const

/** 能耗评级配色：柔和、可辨、非高饱和 */
export const GRADE_COLOR: Record<string, string> = {
  A: '#4f9d77',
  B: '#7fb98c',
  C: '#d3b25c',
  D: '#d18f5f',
  E: '#c26b62',
  '未评级': '#a9b4b8',
}

/** EUI 分档配色（kWh/m²·年） */
export const EUI_COLOR = [
  { max: 60, color: '#4f9d77' },
  { max: 100, color: '#7fb98c' },
  { max: 150, color: '#d3b25c' },
  { max: 200, color: '#d18f5f' },
  { max: Infinity, color: '#c26b62' },
]

/** 异常率分档配色 */
export const ANOMALY_COLOR = [
  { max: 0.02, color: '#4f9d77' },
  { max: 0.05, color: '#7fb98c' },
  { max: 0.10, color: '#d3b25c' },
  { max: Infinity, color: '#c26b62' },
]

/** 未评级 / 缺数据时的中性灰 */
export const NEUTRAL = '#a9b4b8'

/** 取十六进制颜色为 THREE 可用数值 */
export function hexToNum(hex: string): number {
  return parseInt(hex.replace('#', ''), 16)
}
