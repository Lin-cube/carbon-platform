import { request, download } from './request'
import type {
  LoginRequest, LoginResponse, UserInfo, SystemOverview,
  DimensionVO, OverviewVO, BreakdownVO, EnergyQueryRequest, TimeSeriesVO,
  PeriodSummaryVO, CopAnalysisVO, AnomalyAnalysisVO, DiagnoseResult,
  SuggestResult, ChatResponse, BuildingRow, SiteRow, AiReport
} from '@/types/api'

// ---------------------------------------------------------------- 认证
export const authApi = {
  login: (data: LoginRequest) => request<LoginResponse>({ url: '/auth/login', method: 'post', data }),
  me: () => request<UserInfo>({ url: '/auth/me', method: 'get' }),
  logout: () => request<void>({ url: '/auth/logout', method: 'post' })
}

// ---------------------------------------------------------------- 系统
export const systemApi = {
  health: () => request<Record<string, unknown>>({ url: '/system/health', method: 'get' }),
  overview: () => request<SystemOverview>({ url: '/system/overview', method: 'get' })
}

// ---------------------------------------------------------------- 能耗查询（MCP 1-4）
export const energyApi = {
  dimensions: (dimension = 'all') =>
    request<DimensionVO>({ url: '/energy/dimensions', method: 'get', params: { dimension } }),
  overview: (data: EnergyQueryRequest) =>
    request<OverviewVO>({ url: '/energy/overview', method: 'post', data }),
  breakdown: (data: EnergyQueryRequest, groupBy = 'site') =>
    request<BreakdownVO>({ url: '/energy/breakdown', method: 'post', data, params: { groupBy } }),
  query: (data: EnergyQueryRequest) =>
    request<TimeSeriesVO>({ url: '/energy/query', method: 'post', data }),
  buildings: (params: { siteId?: string; buildingType?: string; keyword?: string; limit?: number }) =>
    request<BuildingRow[]>({ url: '/energy/buildings', method: 'get', params }),
  buildingDetail: (buildingId: string) =>
    request<Record<string, unknown>>({ url: `/energy/buildings/${buildingId}`, method: 'get' }),
  sites: () => request<SiteRow[]>({ url: '/energy/sites', method: 'get' })
}

// ---------------------------------------------------------------- 统计分析（MCP 5-7）
export const analyticsApi = {
  periodSummary: (params: {
    periodType?: string; startDate: string; endDate: string
    siteId?: string; buildingId?: string; buildingType?: string
  }) => request<PeriodSummaryVO>({ url: '/analytics/period-summary', method: 'get', params }),

  cop: (params: { buildingId: string; startDate: string; endDate: string }) =>
    request<CopAnalysisVO>({ url: '/analytics/cop', method: 'get', params }),

  anomaly: (params: { startDate: string; endDate: string; siteId?: string; buildingId?: string }) =>
    request<AnomalyAnalysisVO>({ url: '/analytics/anomaly', method: 'get', params }),

  scanAnomalies: (params: {
    startDate: string; endDate: string; buildingId?: string; siteId?: string
    anomalyType?: string; severity?: string; minDuration?: number
    pageNum?: number; pageSize?: number
  }) => request<{ total: number; pageNum: number; pageSize: number; events: unknown[] }>(
    { url: '/analytics/anomalies/scan', method: 'get', params }),

  diagnose: (params: { buildingId: string; startDate: string; endDate: string }) =>
    request<DiagnoseResult>({ url: '/analytics/diagnose', method: 'get', params }),

  suggest: (params: { buildingId: string; startDate: string; endDate: string }) =>
    request<SuggestResult>({ url: '/analytics/suggest', method: 'get', params }),

  exportReport: (params: {
    startDate: string; endDate: string; siteId?: string
    buildingType?: string; periodType?: string
  }, filename: string) => download('/analytics/report/export', params, filename)
}

// ---------------------------------------------------------------- AI 助手
export const aiApi = {
  chat: (data: {
    question: string; sessionId?: string
    buildingId?: string; startDate?: string; endDate?: string
    context?: Record<string, unknown>
  }) => request<ChatResponse>({ url: '/ai/chat', method: 'post', data }),

  report: (data: {
    reportType?: string; startDate?: string; endDate?: string
    siteId?: string; buildingIds?: string[]; withNarrative?: boolean
  }) => request<AiReport>({ url: '/ai/report', method: 'post', data }),

  tools: () => request<{ mode: string; count: number; tools: unknown[] }>(
    { url: '/ai/tools', method: 'get' }),
  health: () => request<Record<string, unknown>>({ url: '/ai/health', method: 'get' }),
  mode: () => request<string>({ url: '/ai/mode', method: 'get' })
}
