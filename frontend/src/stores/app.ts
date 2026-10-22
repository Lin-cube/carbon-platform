import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 全局查询条件：让各页面共享"当前选中的园区/时间范围" */
export const useAppStore = defineStore('app', () => {
  const siteId = ref<string>('')
  const buildingType = ref<string>('')
  const startDate = ref<string>('2016-01-01')
  const endDate = ref<string>('2016-12-31')
  const sidebarCollapsed = ref(false)

  /**
   * 数据版本号：导入新数据成功后自增。
   * 各页面的 AI 解读组件 watch 这个值，从而在导入后自动重新解读，
   * 而不是停留在旧数据的结论上。
   */
  const dataVersion = ref(0)

  /** 导入等数据变更操作后调用，通知所有依赖组件刷新 */
  function bumpDataVersion() {
    dataVersion.value += 1
  }

  function setRange(start: string, end: string) {
    startDate.value = start
    endDate.value = end
  }

  return { siteId, buildingType, startDate, endDate, sidebarCollapsed,
           dataVersion, bumpDataVersion, setRange }
})
