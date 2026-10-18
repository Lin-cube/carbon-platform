<template>
  <div ref="el" class="echart" :style="{ height: height }"></div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, nextTick } from 'vue'
import echarts, { type EChartsOption } from '@/utils/echarts'

const props = withDefaults(
  defineProps<{ option: EChartsOption; height?: string; loading?: boolean }>(),
  { height: '300px', loading: false }
)

const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let ro: ResizeObserver | null = null

function render() {
  if (!chart || !props.option) return
  chart.setOption(props.option, true)
}

onMounted(async () => {
  await nextTick()
  if (!el.value) return
  chart = echarts.init(el.value)
  render()
  // 容器尺寸变化时自适应（侧边栏折叠、窗口缩放）
  ro = new ResizeObserver(() => chart?.resize())
  ro.observe(el.value)
})

watch(() => props.option, render, { deep: true })
watch(() => props.loading, (v) => (v ? chart?.showLoading('default', {
  text: '加载中', color: '#2f7dff', textColor: '#6b7280', maskColor: 'rgba(255,255,255,0.7)'
}) : chart?.hideLoading()))

onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.echart { width: 100%; }
</style>
