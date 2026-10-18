import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/api/request'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '监控看板', icon: 'Odometer' }
      },
      {
        path: 'campus3d',
        name: 'Campus3D',
        component: () => import('@/views/Campus3D.vue'),
        meta: { title: '3D 园区', icon: 'OfficeBuilding' }
      },
      {
        path: 'energy',
        name: 'EnergyQuery',
        component: () => import('@/views/EnergyQuery.vue'),
        meta: { title: '能耗查询', icon: 'Search' }
      },
      {
        path: 'analytics',
        name: 'Analytics',
        component: () => import('@/views/Analytics.vue'),
        meta: { title: '统计分析', icon: 'TrendCharts' }
      },
      {
        path: 'anomaly',
        name: 'Anomaly',
        component: () => import('@/views/Anomaly.vue'),
        meta: { title: '异常诊断', icon: 'Warning' }
      },
      {
        path: 'assistant',
        name: 'Assistant',
        component: () => import('@/views/Assistant.vue'),
        meta: { title: '小E 助手', icon: 'ChatDotRound' }
      },
      {
        path: 'import',
        name: 'DataImport',
        component: () => import('@/views/DataImport.vue'),
        meta: { title: '数据导入', icon: 'Upload' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  const title = (to.meta?.title as string) || ''
  document.title = title ? `${title} · 碳能智控运维平台` : '碳能智控运维平台'

  if (to.meta?.public) {
    next()
    return
  }
  if (!getToken()) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  next()
})

export default router
