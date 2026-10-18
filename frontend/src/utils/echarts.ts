import * as echarts from 'echarts/core'
import {
  BarChart, LineChart, PieChart, GaugeChart, ScatterChart
} from 'echarts/charts'
import {
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  DataZoomComponent, MarkLineComponent, MarkPointComponent,
  VisualMapComponent, ToolboxComponent, GraphicComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

echarts.use([
  BarChart, LineChart, PieChart, GaugeChart, ScatterChart,
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  DataZoomComponent, MarkLineComponent, MarkPointComponent,
  VisualMapComponent, ToolboxComponent, GraphicComponent,
  CanvasRenderer
])

export default echarts
export type { EChartsOption }

/** 数值格式化：万/亿 单位，保留 1 位小数 */
export function fmtNum(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '--'
  const abs = Math.abs(v)
  if (abs >= 1e8) return (v / 1e8).toFixed(digits) + ' 亿'
  if (abs >= 1e4) return (v / 1e4).toFixed(digits) + ' 万'
  return v.toLocaleString('zh-CN', { maximumFractionDigits: digits })
}

export function fmtInt(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '--'
  return Math.round(v).toLocaleString('zh-CN')
}

/** 能耗评级颜色 */
export function gradeColor(grade: string | null | undefined): string {
  switch (grade) {
    case 'A': return '#22c55e'
    case 'B': return '#84cc16'
    case 'C': return '#eab308'
    case 'D': return '#f97316'
    case 'E': return '#ef4444'
    default: return '#9ca3af'
  }
}

/** 数据分级颜色 */
export function dataGradeColor(g: string | null | undefined): string {
  return g === 'A' ? '#22c55e' : '#f59e0b'
}
