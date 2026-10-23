/**
 * 3D 画质分级与设备能力探测。
 *
 * <p>本项目要在"老师办公室的普通笔记本"上也能流畅跑，所以不能一律按
 * 高分屏 + 2048 阴影贴图来渲染。这里的策略是：
 * <ol>
 *   <li>尽量识别设备能力（GPU 型号字符串、CPU 核数、内存、是否移动端）；</li>
 *   <li>识别不出来就按"中等"起步，宁可稳一点也不赌高画质；</li>
 *   <li>降级只影响渲染开销（像素比 / 阴影 / 抗锯齿 / 树木数量），
 *       <b>不影响任何业务数据与交互</b>。</li>
 * </ol>
 *
 * <p>软件渲染（SwiftShader、llvmpipe）会被判为 low：
 * 这类环境跑真实光照与阴影会非常慢，且通常是自动化/无头环境。
 */

export type QualityLevel = 'high' | 'medium' | 'low'

export interface QualityProfile {
  level: QualityLevel
  /** 设备像素比上限 */
  pixelRatio: number
  /** 是否启用阴影 */
  shadows: boolean
  /** 阴影贴图尺寸（不启用阴影时无意义） */
  shadowMapSize: number
  /** 是否抗锯齿（必须在创建 renderer 时确定） */
  antialias: boolean
  /** 树木数量缩放系数 */
  treeScale: number
  /** 判定依据，便于排查 */
  reason: string
}

const MOBILE_UA = /Android|iPhone|iPad|iPod|Mobile|HarmonyOS/i

/** 读取 WebGL 的 GPU 标识字符串 */
function readRendererString(): string {
  try {
    const canvas = document.createElement('canvas')
    const gl = (canvas.getContext('webgl2') ||
      canvas.getContext('webgl')) as WebGLRenderingContext | null
    if (!gl) return ''
    const ext = gl.getExtension('WEBGL_debug_renderer_info')
    const name = ext
      ? String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) || '')
      : String(gl.getParameter(gl.RENDERER) || '')
    // 用完立即释放，避免多占一个 WebGL 上下文
    gl.getExtension('WEBGL_lose_context')?.loseContext()
    return name.toLowerCase()
  } catch {
    return ''
  }
}

/**
 * 探测并给出画质档位。
 *
 * <p>优先级：软件渲染 → 移动端 → 低配 → 高配。
 */
export function detectQuality(): QualityProfile {
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : ''
  const mobile = MOBILE_UA.test(ua)
  const cores = (navigator as Navigator & { hardwareConcurrency?: number })
    .hardwareConcurrency ?? 4
  const mem = (navigator as Navigator & { deviceMemory?: number }).deviceMemory ?? 0
  const gpu = readRendererString()

  // ① 软件渲染：直接最低档（无头浏览器、远程桌面常见）
  if (gpu.includes('swiftshader') || gpu.includes('llvmpipe')
      || gpu.includes('software') || gpu.includes('basic render')) {
    return {
      level: 'low', pixelRatio: 1, shadows: false, shadowMapSize: 512,
      antialias: false, treeScale: 0.5, reason: `软件渲染（${gpu.slice(0, 40)}）`,
    }
  }

  // ② 移动端 / 低配：中低档
  if (mobile || cores <= 4 || (mem > 0 && mem <= 4)) {
    const level: QualityLevel = mobile ? 'low' : 'medium'
    return {
      level,
      pixelRatio: level === 'low' ? 1.5 : 2,
      shadows: level !== 'low',
      shadowMapSize: 1024,
      antialias: level !== 'low',
      treeScale: level === 'low' ? 0.55 : 0.8,
      reason: mobile
        ? '移动端设备'
        : `低配（${cores} 核${mem ? ` / ${mem}GB` : ''}）`,
    }
  }

  // ③ 常规桌面
  return {
    level: 'high', pixelRatio: 2, shadows: true, shadowMapSize: 1024,
    antialias: true, treeScale: 1, reason: `桌面设备（${cores} 核）`,
  }
}

/** 档位的中文说明，用于界面提示 */
export function qualityLabel(q: QualityProfile): string {
  const map: Record<QualityLevel, string> = {
    high: '高清', medium: '均衡', low: '流畅',
  }
  return map[q.level]
}
