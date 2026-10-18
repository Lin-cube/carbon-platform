import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api'
import { setToken, clearToken, getToken } from '@/api/request'
import type { UserInfo } from '@/types/api'

export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(getToken())
  const info = ref<UserInfo | null>(null)

  const isLoggedIn = computed(() => !!token.value)
  const nickname = computed(() => info.value?.nickname || info.value?.username || '未登录')
  const roles = computed(() => info.value?.roles || [])
  const permissions = computed(() => info.value?.permissions || [])

  const hasPerm = (perm: string) => permissions.value.includes(perm)
  const hasRole = (role: string) => roles.value.includes(role)

  async function login(username: string, password: string) {
    const res = await authApi.login({ username, password })
    token.value = res.token
    setToken(res.token)
    await fetchInfo()
    return res
  }

  async function fetchInfo() {
    info.value = await authApi.me()
    return info.value
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      /* 忽略登出接口异常，本地清理即可 */
    }
    token.value = null
    info.value = null
    clearToken()
  }

  return { token, info, isLoggedIn, nickname, roles, permissions, hasPerm, hasRole, login, fetchInfo, logout }
})
