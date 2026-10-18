import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 全局查询条件：让各页面共享"当前选中的园区/时间范围" */
export const useAppStore = defineStore('app', () => {
  const siteId = ref<string>('')
  const buildingType = ref<string>('')
  const startDate = ref<string>('2016-01-01')
  const endDate = ref<string>('2016-12-31')
  const sidebarCollapsed = ref(false)

  function setRange(start: string, end: string) {
    startDate.value = start
    endDate.value = end
  }

  return { siteId, buildingType, startDate, endDate, sidebarCollapsed, setRange }
})
