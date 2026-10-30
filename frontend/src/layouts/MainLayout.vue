<template>
  <el-container class="layout">
    <!-- 侧边导航 -->
    <el-aside :width="app.sidebarCollapsed ? '64px' : '208px'" class="aside">
      <div class="logo">
        <el-icon :size="20" color="#4FA37F"><Lightning /></el-icon>
        <span v-show="!app.sidebarCollapsed" class="logo-text">碳能智控运维平台</span>
      </div>

      <el-menu :default-active="route.path" router :collapse="app.sidebarCollapsed"
               background-color="#0F2038" text-color="#9FB0BF" active-text-color="#8AD3AC"
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
          <el-dropdown @command="onCommand">
            <span class="user">
              <el-avatar :size="26" class="user-avatar">
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

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const app = useAppStore()

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
  if (!userStore.info) {
    try { await userStore.fetchInfo() } catch { /* 忽略 */ }
  }
})
</script>

<style scoped>
.layout { height: 100vh; }

/* ── 侧栏：深海军蓝结构色 #0F2038 ── */
.aside {
  background: var(--carbon-navy, #0F2038);
  transition: width 0.22s ease;
  overflow-x: hidden;
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 18px;
  color: #EAF0F4;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  white-space: nowrap;
}

.logo-text { font-size: 14px; font-weight: 600; letter-spacing: 0.02em; }

.menu { border-right: none; }

/* 菜单项：低饱和薄荷绿激活态 + 左侧竖线 + 轻微过渡 */
.menu :deep(.el-menu-item) {
  height: 46px;
  line-height: 46px;
  margin: 2px 8px;
  border-radius: 8px;
  position: relative;
  transition: background-color 0.18s ease, color 0.18s ease;
}

.menu :deep(.el-menu-item:hover) {
  background-color: rgba(255, 255, 255, 0.06);
  color: #EAF0F4;
}

/* 当前激活项：薄荷绿文字 + 淡绿底 + 左侧竖线 */
.menu :deep(.el-menu-item.is-active) {
  background-color: rgba(74, 160, 120, 0.16);
  color: #8AD3AC;
  font-weight: 600;
}
.menu :deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 2px;
  background: #4FA37F;
}

/* 折叠态：图标居中，隐藏侧竖线避免错位 */
.menu :deep(.el-menu--collapse .el-menu-item.is-active::before) {
  display: none;
}

/* ── 顶栏：白色 + 极淡玻璃 ── */
.header {
  height: 56px;
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid var(--carbon-border, #E3E9E4);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
}

.header-left { display: flex; align-items: center; gap: 14px; }
.collapse-btn {
  cursor: pointer;
  font-size: 18px;
  color: var(--carbon-text-dim, #5D6E66);
  transition: color 0.16s ease;
}
.collapse-btn:hover { color: var(--carbon-primary, #35866B); }
.crumb { font-size: 15px; font-weight: 600; color: var(--carbon-text, #253D35); }

.header-right { display: flex; align-items: center; gap: 16px; }

.user {
  display: flex; align-items: center; gap: 8px;
  cursor: pointer; outline: none;
  padding: 3px 6px;
  border-radius: 8px;
  transition: background-color 0.16s ease;
}
.user:hover { background: var(--el-fill-color-light, #F7F9F8); }
.user-avatar { background: var(--carbon-primary, #35866B); font-weight: 600; }
.uname { font-size: 13px; color: var(--carbon-text-regular, #46584F); }

.main { background: var(--carbon-bg, #F7F9F6); padding: 16px; overflow-y: auto; }
.mr6 { margin-right: 6px; }
</style>
