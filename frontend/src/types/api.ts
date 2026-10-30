/** 统一响应信封（与后端 com.carbon.platform.common.result.R 对应） */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  success?: boolean
  timestamp?: number
  costMs?: number | null
}

export interface LoginRequest { username: string; password: string }

export interface LoginResponse {
  token: string
  userId: number
  username: string
  nickname: string
  dept?: string
  roles: string[]
  expireSeconds: number
}

export interface UserInfo {
  userId: number
  username: string
  nickname: string
  dept?: string
  roles: string[]
  permissions: string[]
}

export interface SystemOverview {
  buildingCount: number
  siteCount: number
  energyRows: number
  anomalyEvents: number
  gradeACount: number
  gradeBCount: number
  timeRange: { start: string; end: string }
}

export interface DimensionOption { value: string; label: string; count: number }
export interface DimensionVO { dimension: string; options: DimensionOption[]; total: number }

export interface EnergyQueryRequest {
  buildingId?: string
  siteId?: string
  buildingType?: string
  dataGrade?: string
  startDate?: string
  endDate?: string
  granularity?: 'hourly' | 'daily' | 'monthly'
  metric?: 'electricity' | 'hvac' | 'water' | 'carbon' | 'cop' | 'all'
  onlyAnomaly?: boolean
  pageNum?: number
  pageSize?: number
}

export interface OverviewVO {
  buildingCount: number
  siteCount: number
  totalHours: number
  electricityKwh: number
  hvacKwh: number
  waterM3: number
  carbonKg: number
  copAvg: number | null
  anomalyHours: number
  anomalyRate: number
  timeRange: string
  /** 有冷暖分项表计的楼栋数（空调能耗只覆盖这些楼） */
  hvacBuildingCount?: number
  /** 有 COP 的楼栋数（依赖冷量表，覆盖更少） */
  copBuildingCount?: number
}

export interface BreakdownItem {
  key: string; label: string; value: number; percentage: number; count: number
}
export interface BreakdownVO {
  groupBy: string; metric: string; items: BreakdownItem[]; total: number
}

export interface SeriesPoint {
  time: string
  buildingId: string
  electricityKwh: number | null
  hvacKwh: number | null
  waterM3: number | null
  carbonKg: number | null
  cop: number | null
  airTemperatureC: number | null
  anomalyFlag: number | null
}
export interface BuildingSummary {
  buildingId: string
  buildingTypeCn: string | null
  electricityKwh: number | null
  hvacKwh: number | null
  carbonKg: number | null
  copAvg: number | null
  eui: number | null
}
export interface TimeSeriesVO {
  granularity: string
  metric: string
  buildings: string[]
  points: SeriesPoint[]
  summary: BuildingSummary[]
}

export interface PeriodBucket {
  label: string
  electricityKwh: number | null
  hvacKwh: number | null
  waterM3: number | null
  carbonKg: number | null
  copAvg: number | null
  airTempAvg: number | null
  anomalyHours: number | null
  days: number | null
  momChange: number | null
}
export interface TariffBucket {
  name: string; hours: string; electricityKwh: number | null
  percentage: number | null; avgHourlyKwh: number | null
}
export interface PeriodSummaryVO {
  periodType: string; startDate: string; endDate: string
  buckets: PeriodBucket[]
  total: number | null; average: number | null
  peak: number | null; peakLabel: string | null
  valley: number | null; valleyLabel: string | null
  peakValleyRatio: number | null
  tariffBuckets: TariffBucket[]
}

export interface CopAnalysisVO {
  buildingId: string; startDate: string; endDate: string
  copAvg: number | null; copMax: number | null; copMin: number | null; copStd: number | null
  coolingKwh: number | null; hvacElectricityKwh: number | null; systemCop: number | null
  supplyTempAvg: number | null; returnTempAvg: number | null; deltaTAvg: number | null
  copGrade: string | null; gradeReason: string | null
  tempRelations: { tempRange: string; copAvg: number | null; hours: number }[]
  distribution: { range: string; hours: number; percentage: number | null }[]
}

export interface AnomalyByType { type: string; count: number; percentage: number | null; description: string }
export interface AnomalyByBuilding {
  buildingId: string; buildingTypeCn: string | null; siteId: string
  anomalyHours: number | null; anomalyRate: number | null
  peakDeviationPct: number | null; dominantType: string | null
}
export interface AnomalyByDate { date: string; anomalyHours: number; eventCount: number }
export interface AnomalyAnalysisVO {
  startDate: string; endDate: string
  evaluatedHours: number; anomalyHours: number; anomalyRate: number | null
  qualityHours: number; qualityRate: number | null
  eventCount: number; avgDurationHours: number | null; maxDurationHours: number | null
  byType: AnomalyByType[]; topBuildings: AnomalyByBuilding[]; trend: AnomalyByDate[]
}

export interface AnomalyEvent {
  id: number; buildingId: string; siteId: string; buildingTypeCn: string | null
  startTs: string; endTs: string; durationHours: number
  anomalyType: string; severity: string | null
  peakValue: number | null; baselineValue: number | null; deviationPct: number | null
  handled: number; handleNote: string | null
}

export interface Attribution {
  cause: string; confidence: string; evidence: string
  suggestion: string; needsManualReview: boolean
}
export interface DiagnoseResult {
  buildingIds: string[]
  timeRange: string
  anomalyProfiles: Record<string, unknown>[]
  hasAnomaly: boolean
  anomalyVsNormal: Record<string, unknown>[]
  attributions: Attribution[]
  efficiencyProfile?: Record<string, unknown>
}

export interface Suggestion {
  title: string; priority: string; evidence: string
  actions: string; basis: string; needsManualReview: boolean
}
export interface SuggestResult {
  buildingId: string; timeRange: string
  efficiencyProfile?: Record<string, unknown>
  suggestions: Suggestion[]
  disclaimer?: string
}

export interface ChatResponse {
  ok: boolean
  answer: string
  citations: { source: string; page: number; snippet: string; url?: string }[]
  toolCalls: { tool: string; args: Record<string, unknown>; elapsedMs: number; success: boolean }[]
  dataEvidence: Record<string, unknown>
  confidence: number | null
  elapsedMs: number | null
  dataSource: string | null
  error: string | null
}

export interface BuildingRow {
  buildingId: string; siteId: string; buildingTypeCn: string | null
  dataGrade: string; areaM2: number | null
  electricityKwhYear: number | null; euiKwhM2Year: number | null
  energyGrade: string | null; copMean: number | null; anomalyRate: number | null
}

export interface SiteRow {
  siteId: string; buildingCount: number
  electricityKwhYear: number | null; carbonKgYear: number | null; typeCount: number
}
