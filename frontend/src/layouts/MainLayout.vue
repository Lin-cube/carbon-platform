<template>
  <el-container class="layout">
    <!-- 侧边导航 -->
    <el-aside :width="app.sidebarCollapsed ? '64px' : '208px'" class="aside">
      <div class="logo">
        <el-icon :size="20" color="#22d3ee"><Lightning /></el-icon>
        <span v-show="!app.sidebarCollapsed" class="logo-text">碳能智控运维平台</span>
      </div>

      <el-menu :default-active="route.path" router :collapse="app.sidebarCollapsed"
               background-color="#0f1b2f" text-color="#9fb3d1" active-text-color="#22d3ee"
               class="menu">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <template #title>{{ m.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="app.sidebarCollapsed = !app.sidebarCollapsed">
            <Fold v-if="!app.sidebarCollapsed" />
            <Expand v-else />
          </el-icon>
          <span class="crumb">{{ route.meta.title }}</span>
        </div>

        <div class="header-right">
          <el-tooltip :content="aiStatusText" placement="bottom">
            <span class="status-dot" :class="aiStatus">
              <span class="dot"></span>AI 服务
            </span>
          </el-tooltip>

          <el-tag size="small" type="success" effect="plain">
            数据 {{ fmtInt(overview?.energyRows) }} 行
          </el-tag>

          <el-dropdown @command="onCommand">
            <span class="user">
              <el-avatar :size="26" style="background: #2f7dff">
                {{ userStore.nickname.charAt(0) }}
              </el-avatar>
              <span class="uname">{{ userStore.nickname }}</span>
              <el-tag v-for="r in userStore.roles" :key="r" size="small" effect="plain">
                {{ roleLabel(r) }}
              </el-tag>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">账号信息</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <keep-alive :max="6">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>

    <!-- 账号信息 -->
    <el-dialog v-model="profileVisible" title="账号信息" width="420px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户名">{{ userStore.info?.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ userStore.info?.nickname }}</el-descriptions-item>
        <el-descriptions-item label="部门">{{ userStore.info?.dept || '--' }}</el-descriptions-item>
        <el-descriptions-item label="角色">
          <el-tag v-for="r in userStore.roles" :key="r" size="small" class="mr6">
            {{ roleLabel(r) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="权限">
          <el-tag v-for="p in userStore.permissions" :key="p" size="small"
                  type="info" effect="plain" class="mr6">{{ p }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { systemApi, aiApi } from '@/api'
import { fmtInt } from '@/utils/echarts'
import type { SystemOverview } from '@/types/api'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const app = useAppStore()

const overview = ref<SystemOverview | null>(null)
const aiStatus = ref<'up' | 'down' | 'unknown'>('unknown')
const profileVisible = ref(false)

const menus = [
  { path: '/dashboard', title: '监控看板', icon: 'Odometer' },
  { path: '/campus3d', title: '3D 园区', icon: 'OfficeBuilding' },
  { path: '/energy', title: '能耗查询', icon: 'Search' },
  { path: '/analytics', title: '统计分析', icon: 'TrendCharts' },
  { path: '/anomaly', title: '异常诊断', icon: 'Warning' },
  { path: '/assistant', title: '小E 助手', icon: 'ChatDotRound' },
  { path: '/import', title: '数据导入', icon: 'Upload' }
]

const aiStatusText = computed(() => {
  if (aiStatus.value === 'up') return 'AI 服务正常（当前为本地联调模式或已接入 RAGFlow）'
  if (aiStatus.value === 'down') return 'AI 服务不可达，AI 问答可能不可用'
  return 'AI 服务状态检测中'
})

function roleLabel(r: string) {
  return r === 'admin' ? '管理员' : r === 'operator' ? '运维人员' : r === 'viewer' ? '只读' : r
}

async function onCommand(cmd: string) {
  if (cmd === 'profile') {
    profileVisible.value = true
    return
  }
  if (cmd === 'logout') {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
  }
}

onMounted(async () => {
  try {
    overview.value = await systemApi.overview()
  } catch { /* 忽略 */ }
  try {
    const h = await aiApi.health()
    aiStatus.value = h.status === 'UP' ? 'up' : 'down'
  } catch {
    aiStatus.value = 'down'
  }
  if (!userStore.info) {
    try { await userStore.fetchInfo() } catch { /* 忽略 */ }
  }
})
</script>

<style scoped>
.layout { height: 100vh; }

.aside {
  background: #0f1b2f;
  transition: width 0.22s;
  overflow-x: hidden;
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 18px;
  color: #e6edf7;
  border-bottom: 1px solid #1e2b45;
  white-space: nowrap;
}

.logo-text { font-size: 14px; font-weight: 600; }

.menu { border-right: none; }

.header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
}

.header-left { display: flex; align-items: center; gap: 14px; }
.collapse-btn { cursor: pointer; font-size: 18px; color: #6b7280; }
.crumb { font-size: 15px; font-weight: 600; color: #1f2937; }

.header-right { display: flex; align-items: center; gap: 14px; }

.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #6b7280;
}
.status-dot .dot {
  width: 8px; height: 8px; border-radius: 50%; background: #9ca3af;
}
.status-dot.up .dot { background: #22c55e; box-shadow: 0 0 6px #22c55e; }
.status-dot.down .dot { background: #ef4444; }

.user { display: flex; align-items: center; gap: 8px; cursor: pointer; outline: none; }
.uname { font-size: 13px; color: #374151; }

.main { background: #f4f6fa; padding: 16px; overflow-y: auto; }
.mr6 { margin-right: 6px; }
</style>
