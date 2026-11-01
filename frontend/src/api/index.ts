import { request, download } from './request'
import type {
  LoginRequest, LoginResponse, UserInfo, SystemOverview,
  DimensionVO, OverviewVO, BreakdownVO, EnergyQueryRequest, TimeSeriesVO,
  PeriodSummaryVO, CopAnalysisVO, AnomalyAnalysisVO, DiagnoseResult,
  SuggestResult, ChatResponse, BuildingRow, SiteRow, AiReport,
  AnomalyHandleDetail, AnomalyHandleLog, SavingSimulationVO
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
    handleStatus?: string
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

/**
 * 节能情景模拟接口。
 *
 * 结果写入独立的 energy_saving_simulation 表，不覆盖任何真实统计指标。
 */
export const savingApi = {
  simulate: (data: {
    buildingId: string; startDate: string; endDate: string; scenario: string
    reduceRatio?: number; hourStart?: number; hourEnd?: number
    copImproveRatio?: number; unitPrice?: number; save?: boolean
  }) => request<SavingSimulationVO>({ url: '/analytics/saving/simulate', method: 'post', data }),

  get: (id: number) => request<SavingSimulationVO>({ url: `/analytics/saving/${id}`, method: 'get' }),

  list: (params: { buildingId?: string; limit?: number }) =>
    request<SavingSimulationVO[]>({ url: '/analytics/saving/list', method: 'get', params }),

  explain: (id: number) =>
    request<{ narrative: string; mode: string }>(
      { url: `/analytics/saving/${id}/explain`, method: 'post' })
}

/**
 * 异常处置接口。
 *
 * 写操作（handle）后端用 @PreAuthorize 限制为 admin/operator，
 * viewer 调用会拿到 403，前端也会隐藏入口。
 */
export const anomalyHandleApi = {
  detail: (id: number) =>
    request<AnomalyHandleDetail>({ url: `/anomalies/${id}`, method: 'get' }),

  history: (id: number) =>
    request<AnomalyHandleLog[]>({ url: `/anomalies/${id}/history`, method: 'get' }),

  handle: (id: number, data: { status: string; note?: string; version?: number }) =>
    request<AnomalyHandleDetail>({ url: `/anomalies/${id}/handle`, method: 'post', data }),

  handledCases: (params: { buildingId?: string; anomalyType?: string; limit?: number }) =>
    request<{ count: number; cases: AnomalyHandleLog[] }>(
      { url: '/anomalies/handled', method: 'get', params }),

  stats: (params: { startDate: string; endDate: string; buildingId?: string }) =>
    request<Record<string, number>>({ url: '/anomalies/stats', method: 'get', params })
}

// ---------------------------------------------------------------- AI 助手
/** AI 诊断记录（持久化 + 复用 + 重新分析） */
export const diagnosisApi = {
  generate: (params: { buildingId: string; anomalyId?: number; startDate: string; endDate: string; force?: boolean }) =>
    request<DiagnosisRecord>({ url: '/diagnosis', method: 'post', params }),
  history: (params: { buildingId?: string; anomalyId?: number; limit?: number }) =>
    request<DiagnosisRecord[]>({ url: '/diagnosis', method: 'get', params }),
  detail: (id: number) => request<DiagnosisRecord>({ url: `/diagnosis/${id}`, method: 'get' })
}

/** 处置后复检 */
export const verifyApi = {
  verify: (anomalyId: number) =>
    request<VerifyResult>({ url: `/anomalies/${anomalyId}/verify`, method: 'post' }),
  history: (anomalyId: number, limit = 10) =>
    request<VerifyResult[]>({ url: `/anomalies/${anomalyId}/verifications`, method: 'get', params: { limit } })
}

export interface DiagnosisRecord {
  id: number
  buildingId: string
  anomalyId?: number
  startDate: string
  endDate: string
  summary: string
  evidence?: Record<string, unknown>
  causes?: Array<Record<string, unknown>>
  actions?: string[]
  topEvents?: Array<Record<string, unknown>>
  model?: string
  mode?: string
  status: string
  invalidReason?: string
  createdAt: string
  reused?: boolean
}

export interface VerifyResult {
  id: number
  anomalyId: number
  conclusion: 'IMPROVED' | 'STILL_ANOMALY' | 'INSUFFICIENT'
  conclusionText: string
  reason: string
  preAvgKwh: number
  preBaseline: number
  preDeviationPct: number | null
  postAvgKwh: number
  postBaseline: number
  postDeviationPct: number | null
  changePct: number | null
  basis?: {
    preWindow?: string
    postWindow?: string
    eventWindow?: string
    preHours?: number
    postHours?: number
    preAvgTemp?: number
    postAvgTemp?: number
    caveats?: string[]
  }
}

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
