import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types/api'

/**
 * Axios 实例。
 *
 * 设计要点：
 *  1. 请求拦截注入 JWT；响应拦截统一解包 ApiResult，业务代码直接拿 data；
 *  2. 401 自动清理登录态并跳登录页，避免各页面重复处理；
 *  3. 错误提示集中在此，页面只需处理成功分支。
 */
const service: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

const TOKEN_KEY = 'carbon_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

let redirecting = false

// 说明：响应拦截器会【解包】统一信封，因此成功分支实际返回的是 ApiResult 而非 AxiosResponse。
// 这里的类型标注必须与运行时行为一致，否则所有调用方都会拿到错误的类型
// （vue-tsc 会在 pnpm build 时报 AxiosResponse<T> 不兼容 —— 实测踩过）。
service.interceptors.response.use(
  (response: AxiosResponse<ApiResult>): any => {
    const res = response.data
    // 文件流（报表导出）不走统一解包
    if (response.config.responseType === 'blob') {
      return response
    }
    if (res && typeof res.code === 'number' && res.code !== 200) {
      if (res.code === 401) {
        if (!redirecting) {
          redirecting = true
          clearToken()
          ElMessage.error('登录已过期，请重新登录')
          setTimeout(() => {
            window.location.href = '/login'
            redirecting = false
          }, 800)
        }
      } else {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      if (!redirecting) {
        redirecting = true
        clearToken()
        ElMessage.error('登录已过期，请重新登录')
        setTimeout(() => {
          window.location.href = '/login'
          redirecting = false
        }, 800)
      }
    } else if (status === 403) {
      ElMessage.error('无权限访问该功能')
    } else if (error?.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请缩小查询范围后重试')
    } else {
      ElMessage.error(error?.response?.data?.message || error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

/** 泛型请求：直接返回业务数据（拦截器已解包信封） */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const res = (await service.request(config)) as unknown as ApiResult<T>
  return res.data
}

/** 带完整信封的请求（需要读取 code/message 时用） */
export async function requestRaw<T>(config: AxiosRequestConfig): Promise<ApiResult<T>> {
  return (await service.request(config)) as unknown as ApiResult<T>
}

/** 文件下载 */
export async function download(url: string, params: Record<string, unknown>, filename: string) {
  const resp = (await service.get(url, {
    params,
    responseType: 'blob'
  })) as unknown as AxiosResponse<Blob>
  const blob = new Blob([resp.data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}

export default service
