<template>
  <div>
    <!-- 筛选区：高频 / 高级分组 -->
    <div class="page-card mb-12">
      <div class="page-title">异常诊断</div>

      <div class="filter-group">
        <div class="filter-group-label">筛选条件</div>
        <div class="filter-bar">
          <span class="text-dim">时间范围</span>
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始" end-placeholder="结束" style="width: 240px"
                          @change="onRangeChange" />

          <span class="text-dim">园区</span>
          <el-select v-model="siteId" placeholder="全部园区" clearable style="width: 140px"
                     @change="onSiteChange">
            <el-option v-for="s in sites" :key="s.siteId" :label="`${s.siteId}（${s.buildingCount}栋）`"
                        :value="s.siteId" />
          </el-select>

          <span class="text-dim">建筑</span>
          <el-select v-model="buildingId" placeholder="全部建筑" filterable clearable style="width: 250px">
            <el-option v-for="b in buildings" :key="b.buildingId"
                        :label="`${b.buildingId}（${b.buildingTypeCn || '未分类'}）`" :value="b.buildingId" />
          </el-select>
        </div>
      </div>

      <div class="filter-group">
        <div class="filter-group-label">高级筛选</div>
        <div class="filter-bar">
          <span class="text-dim">异常类型</span>
          <el-select v-model="anomalyType" placeholder="全部类型" clearable style="width: 140px">
            <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>

          <span class="text-dim">严重度</span>
          <el-select v-model="severity" placeholder="全部" clearable style="width: 110px">
            <el-option label="高" value="高" />
            <el-option label="中" value="中" />
            <el-option label="低" value="低" />
          </el-select>

          <span class="text-dim">最短持续(h)</span>
          <el-input-number v-model="minDuration" :min="0" :max="720" :step="1" controls-position="right"
                           style="width: 120px" placeholder="不限" />

          <span class="text-dim">处理状态</span>
          <el-select v-model="handleStatus" placeholder="全部" clearable style="width: 120px">
            <el-option v-for="st in HANDLE_STATUSES" :key="st" :label="st" :value="st" />
          </el-select>

          <div class="filter-actions">
            <el-button type="primary" :icon="Search" :loading="loading" @click="search">查询</el-button>
            <el-button :icon="Refresh" @click="onReset">重置</el-button>
          </div>
        </div>
        <!-- 实测：当前 2016 数据集里 severity=高 的事件数为 0，保留选项以便后续数据接入 -->
        <div class="text-dim" style="font-size: 12px; margin-top: 8px">
          提示：当前数据集（2016）中「高」严重度事件为 0，选它可能查不到结果
        </div>
      </div>
    </div>

    <!-- 告警概览（真实接口：/analytics/anomaly，按时间·园区·建筑口径） -->
    <div class="kpi-row">
      <div class="kpi-card">
        <div class="kpi-label">异常事件数</div>
        <div class="kpi-value">{{ fmtInt(summary?.eventCount) }}<span class="kpi-unit">次</span></div>
        <div class="kpi-sub">连续异常已聚合</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">异常小时数</div>
        <div class="kpi-value">{{ fmtInt(summary?.anomalyHours) }}<span class="kpi-unit">h</span></div>
        <div class="kpi-sub">仅 A 级计量建筑</div>
      </div>
      <div class="kpi-card risk">
        <div class="kpi-label">能耗异常率</div>
        <div class="kpi-value">
          {{ summary?.anomalyRate != null ? summary.anomalyRate.toFixed(2) : '--' }}<span class="kpi-unit">%</span>
        </div>
        <div class="kpi-sub">异常小时 ÷ 评估小时</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">平均持续时长</div>
        <div class="kpi-value">{{ summary?.avgDurationHours != null ? Number(summary.avgDurationHours).toFixed(1) : '--' }}<span class="kpi-unit">h</span></div>
        <div class="kpi-sub">单次事件均值</div>
      </div>
      <div class="kpi-card risk">
        <div class="kpi-label">最长持续时长</div>
        <div class="kpi-value">{{ summary?.maxDurationHours ?? '--' }}<span class="kpi-unit">h</span></div>
        <div class="kpi-sub">需重点核查</div>
      </div>
    </div>
    <div class="summary-note text-dim">摘要按时间 · 园区 · 建筑口径统计，不随异常类型 / 严重度 / 时长筛选变化。</div>

    <!-- 事件列表（后端分页） -->
    <div class="page-card">
      <div class="chart-title">
        异常事件清单 <span class="src-badge src-labeled">算法标注</span>
        <span class="text-dim" style="font-weight: 400">
          · 点击任意行或「诊断」按钮查看归因证据链
        </span>
      </div>

      <el-alert v-if="loadError" type="warning" :closable="false" show-icon class="mb-12"
                title="事件列表加载失败">
        <template #default>
          <el-button link type="primary" size="small" @click="loadEvents">重试</el-button>
        </template>
      </el-alert>

      <el-table :data="events" size="small" v-loading="loading" max-height="560"
                highlight-current-row @row-click="onRowClick">
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="buildingId" label="建筑编号" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.handleStatus && row.handleStatus !== '未处理'"
                  class="handle-dot" :class="'hs-' + statusKey(row.handleStatus)"
                  :title="`处理状态：${row.handleStatus}${row.handleBy ? ' · ' + row.handleBy : ''}`"></span>
            {{ row.buildingId }}
          </template>
        </el-table-column>
        <el-table-column prop="siteId" label="园区" width="100" />
        <el-table-column prop="buildingTypeCn" label="建筑类型" width="126" />
        <el-table-column prop="startTs" label="开始时间" width="150" sortable />
        <el-table-column prop="durationHours" label="持续(h)" width="96" align="right" sortable />
        <el-table-column prop="anomalyType" label="异常类型" width="112" />
        <el-table-column prop="severity" label="严重度" width="94">
          <template #default="{ row }">
            <span class="severity-badge" :class="'sev-' + severityKey(row.severity)">
              {{ row.severity || '未分级' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="peakValue" label="峰值" width="104" align="right" sortable>
          <template #default="{ row }">{{ fmtNum(row.peakValue) }}</template>
        </el-table-column>
        <el-table-column prop="baselineValue" label="基线" width="104" align="right" sortable>
          <template #default="{ row }">{{ fmtNum(row.baselineValue) }}</template>
        </el-table-column>
        <el-table-column prop="deviationPct" label="偏离%" width="100" align="right" sortable>
          <template #default="{ row }">
            <span :class="{ 'dev-high': (row.deviationPct ?? 0) > 100 }">
              {{ row.deviationPct ?? '--' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="152" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small"
                       @click.stop="openDiagnose(row)">诊断</el-button>
            <el-button link type="warning" size="small"
                       @click.stop="openSuggest(row)">优化建议</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 服务端分页：total 来自接口返回 -->
      <el-pagination class="mt-12" layout="total, sizes, prev, pager, next, jumper"
                     :total="total" :page-size="pageSize" :current-page="pageNum"
                     :page-sizes="[10, 20, 50, 100]"
                     @current-change="onPageChange" @size-change="onPageSizeChange" background />

      <el-empty v-if="!loading && !events.length" description="没有符合条件的异常事件，可放宽严重度或时长条件" />
    </div>

    <!-- 诊断抽屉：建筑信息 → 指标对比 → 时序证据 → 归因说明 → 优化建议 -->
    <el-drawer v-model="diagnoseVisible" size="62%" :title="`异常诊断 · ${current?.buildingId || ''}`">
      <div v-loading="diagLoading">
        <template v-if="diagnose">
          <div class="filter-bar">
            <el-tag :type="diagnose.hasAnomaly ? 'danger' : 'success'" effect="dark">
              {{ diagnose.hasAnomaly ? '存在能耗异常' : '未检出明显异常' }}
            </el-tag>
            <span class="text-dim">统计区间 {{ diagnose.timeRange }}</span>
          </div>

          <!-- ① 建筑信息 -->
          <div class="chart-title mt-12">建筑信息</div>
          <el-descriptions :column="3" border size="small" class="mb-12">
            <el-descriptions-item label="建筑编号">{{ current?.buildingId || '--' }}</el-descriptions-item>
            <el-descriptions-item label="所属园区">{{ current?.siteId || '--' }}</el-descriptions-item>
            <el-descriptions-item label="建筑类型">{{ current?.buildingTypeCn || '--' }}</el-descriptions-item>
            <el-descriptions-item label="异常类型">{{ current?.anomalyType || '--' }}</el-descriptions-item>
            <el-descriptions-item label="严重度">{{ current?.severity || '未分级' }}</el-descriptions-item>
            <el-descriptions-item label="异常时段">{{ current?.startTs || '--' }} 起，持续 {{ current?.durationHours ?? '--' }}h</el-descriptions-item>
          </el-descriptions>

          <!-- ② 指标对比：异常时段 vs 正常时段 -->
          <div class="chart-title">指标对比（异常时段 / 正常时段）</div>
          <el-table :data="diagnose.anomalyVsNormal || []" size="small" border class="mb-12">
            <el-table-column label="时段" width="110">
              <template #default="{ row }">
                <el-tag :type="String(row.group).includes('异常') ? 'danger' : 'success'" size="small"
                        effect="plain">
                  {{ row.group }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="小时数" width="96" align="right">
              <template #default="{ row }">{{ fmtInt(num(row, 'hours')) }}</template>
            </el-table-column>
            <el-table-column label="均电耗" min-width="110" align="right">
              <template #default="{ row }">{{ fmtNum(num(row, 'avgKwh')) }} kWh</template>
            </el-table-column>
            <el-table-column label="室外温度" min-width="108" align="right">
              <template #default="{ row }">{{ num(row, 'avgTemp') ?? '--' }} ℃</template>
            </el-table-column>
            <el-table-column label="人员密度" min-width="112" align="right">
              <template #default="{ row }">{{ num(row, 'avgOccupancy') ?? '--' }} 人/100㎡</template>
            </el-table-column>
            <el-table-column label="COP" width="88" align="right">
              <template #default="{ row }">{{ num(row, 'avgCop') ?? '--' }}</template>
            </el-table-column>
            <el-table-column label="温差" width="92" align="right">
              <template #default="{ row }">{{ num(row, 'avgDeltaT') ?? '--' }} ℃</template>
            </el-table-column>
          </el-table>
          <div class="text-dim mb-12" style="font-size: 12px">
            说明：室外温度、人员密度、供回水温度与 COP 中，供回水温度与人员密度来自仿真/推算链路，
            判定时需结合现场实际（AI 输出不直接作为最终决策依据）。
          </div>

          <!-- ③ 时序证据（如有） -->
          <div class="chart-title">时序证据</div>
          <div class="text-dim mb-12" style="font-size: 12px">
            当前诊断接口未返回逐时 / 逐日时序证据，仅提供异常 / 正常时段聚合对比（见上表）。
          </div>

          <!-- ④ 归因说明（证据链） -->
          <div class="chart-title">归因说明（证据链）</div>
          <el-empty v-if="!(diagnose.attributions || []).length" description="未发现明显异常归因" />
          <el-card v-for="(a, i) in diagnose.attributions || []" :key="i" shadow="never"
                   class="attr-card mb-12">
            <div class="attr-head">
              <span class="attr-cause">{{ i + 1 }}. {{ a.cause }}</span>
              <el-tag :type="confidenceTag(a.confidence)" size="small" effect="dark">
                置信度 {{ a.confidence }}
              </el-tag>
              <el-tag v-if="a.needsManualReview" type="warning" size="small" effect="plain">
                需人工复核
              </el-tag>
            </div>
            <div class="attr-row">
              <span class="attr-key">证据</span>
              <span class="attr-val">{{ a.evidence }}</span>
            </div>
            <div class="attr-row">
              <span class="attr-key">建议</span>
              <span class="attr-val">{{ a.suggestion }}</span>
            </div>
          </el-card>

          <!-- ⑤ 优化建议 -->
          <div class="chart-title">优化建议</div>
          <el-empty v-if="!(suggest?.suggestions || []).length" description="暂无优化建议" />
          <el-card v-for="(s, i) in suggest?.suggestions || []" :key="i" shadow="never"
                   class="attr-card mb-12">
            <div class="attr-head">
              <span class="attr-cause">{{ i + 1 }}. {{ s.title }}</span>
              <el-tag :type="priorityTag(s.priority)" size="small" effect="dark">
                优先级 {{ s.priority }}
              </el-tag>
              <el-tag v-if="s.needsManualReview" type="warning" size="small" effect="plain">
                需人工复核
              </el-tag>
            </div>
            <div class="attr-row"><span class="attr-key">依据</span><span class="attr-val">{{ s.evidence }}</span></div>
            <div class="attr-row"><span class="attr-key">措施</span><span class="attr-val">{{ s.actions }}</span></div>
            <div class="attr-row"><span class="attr-key">原理</span><span class="attr-val text-dim">{{ s.basis }}</span></div>
          </el-card>

          <!-- 能效画像 -->
          <div class="chart-title mt-12">能效画像</div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item v-for="f in profileRows(diagnose.efficiencyProfile)" :key="f.key"
                                  :label="f.label">
              {{ f.text }}
            </el-descriptions-item>
          </el-descriptions>

          <!-- AI 证据链解读 -->
          <AiInsight
            ref="diagnosisInsightRef"
            class="mt-12"
            scope="diagnosis"
            title="AI 证据链解读"
            :building-id="current?.buildingId || ''"
            :site-id="current?.siteId || ''"
            :auto="false"
          />
        </template>
        <el-empty v-else-if="!diagLoading" description="暂无诊断结果" />
      </div>

          <!-- 处置闭环：低频操作收进诊断抽屉，不占列表列 -->
          <div v-if="handleDetail" class="handle-section">
            <div class="chart-title mt-12">
              处置记录
              <el-tag size="small" effect="plain" :type="statusTagType(handleDetail.handleStatus)">
                {{ handleDetail.handleStatus || '未处理' }}
              </el-tag>
              <span v-if="handleDetail.handleBy" class="text-dim"
                    style="font-size: 12px; font-weight: 400">
                · {{ handleDetail.handleBy }} {{ handleDetail.handleAt || '' }}
              </span>
            </div>
              <div class="chart-title">处理结论</div>
              <el-form label-width="88px" class="mb-12">
                <el-form-item label="处理状态">
                  <el-select v-model="handleForm.status" style="width: 180px">
                    <el-option v-for="st in HANDLE_STATUSES" :key="st" :label="st" :value="st" />
                  </el-select>
                </el-form-item>
                <el-form-item label="处理备注">
                  <el-input v-model="handleForm.note" type="textarea" :rows="4" maxlength="512"
                            show-word-limit
                            placeholder="例如：已核查为冷凝器结垢，安排 11 月清洗；清洗前临时降低负荷" />
                </el-form-item>
              </el-form>
              <el-alert v-if="!canHandle" type="info" :closable="false" show-icon
                        title="当前账号为只读权限，无法回写处理结论" />
              <div v-else class="text-dim" style="font-size: 12px; margin-bottom: 12px">
                提交后将以「{{ userStore.nickname || '当前用户' }}」的名义记录处理人与处理时间。
                AI 建议仅供参考，处置结论由你确认。
              </div>
              <el-button v-if="canHandle" type="primary" :loading="handleSaving"
                         style="width: 100%" @click="submitHandle">
                提交处理结论
              </el-button>

              <div class="chart-title mt-12">处理历史</div>
              <el-empty v-if="!handleDetail.history?.length" description="暂无处理记录" :image-size="60" />
              <el-timeline v-else>
                <el-timeline-item v-for="h in handleDetail.history" :key="h.id"
                                 :timestamp="`${h.createdAt} · ${h.operator || '系统'}`"
                                 placement="top">
                  <div style="font-size: 13px">
                    <b>{{ h.fromStatus || '—' }} → {{ h.toStatus }}</b>
                  </div>
                  <div v-if="h.note" class="text-dim" style="font-size: 12.5px">{{ h.note }}</div>
                </el-timeline-item>
              </el-timeline>
          </div>

            <!-- AI 诊断记录（持久化 + 复用 + 重新分析） -->
            <div class="handle-section">
              <div class="chart-title mt-12">
                AI 诊断记录
                <el-tag v-if="diagRecord" size="small" style="margin-left: 8px"
                        :type="diagRecord.status === 'VALID' ? 'success' : 'info'">
                  {{ diagRecord.status === 'VALID' ? (diagRecord.reused ? '复用缓存' : '已保存') : '已过期' }}
                </el-tag>
                <span v-if="diagRecord?.createdAt" class="text-dim" style="font-size: 12px; margin-left: 8px">
                  {{ String(diagRecord.createdAt).slice(0, 19) }}
                </span>
              </div>

              <el-empty v-if="!diagRecord" description="暂无保存的诊断，点下方按钮生成" :image-size="56" />
              <div v-else class="diag-box">
                <div class="diag-text">{{ diagRecord.summary }}</div>
                <div v-if="diagRecord.invalidReason" class="diag-warn">
                  {{ diagRecord.invalidReason }}，结论已过期，请重新分析
                </div>
              </div>

              <div class="mt-12" style="display: flex; gap: 8px; flex-wrap: wrap">
                <el-button type="primary" :loading="diagSaving" @click="generateDiagnosis(false)">
                  {{ diagRecord ? '重新获取' : '生成诊断' }}
                </el-button>
                <el-button :loading="diagSaving" :disabled="!diagRecord" @click="generateDiagnosis(true)">
                  重新分析
                </el-button>
                <el-button text @click="showDiagHistory = !showDiagHistory">
                  历史诊断（{{ diagHistory.length }}）
                </el-button>
              </div>

              <el-collapse-transition>
                <div v-show="showDiagHistory" style="margin-top: 10px">
                  <el-timeline>
                    <el-timeline-item v-for="h in diagHistory" :key="h.id"
                                      :timestamp="`${String(h.createdAt).slice(0, 19)} · ${h.mode || ''}`"
                                      placement="top">
                      <div style="font-size: 12.5px">
                        <el-tag size="small" :type="h.status === 'VALID' ? 'success' : 'info'">
                          {{ h.status === 'VALID' ? '有效' : '已过期' }}
                        </el-tag>
                        <span class="text-dim"> {{ String(h.summary || '').slice(0, 60) }}…</span>
                      </div>
                    </el-timeline-item>
                  </el-timeline>
                </div>
              </el-collapse-transition>

              <!-- 处置后复检 -->
              <div class="chart-title mt-12">处置后复检</div>
              <el-button :loading="verifying" @click="runVerify">执行复检</el-button>
              <div v-if="verifyResult" class="verify-box mt-12">
                <el-tag :type="verifyResult.conclusion === 'IMPROVED' ? 'success'
                             : verifyResult.conclusion === 'STILL_ANOMALY' ? 'danger' : 'warning'">
                  {{ verifyResult.conclusionText }}
                </el-tag>
                <div class="verify-reason">{{ verifyResult.reason }}</div>
                <el-descriptions :column="2" size="small" border style="margin-top: 8px">
                  <el-descriptions-item label="处理前均耗">{{ verifyResult.preAvgKwh }} kWh</el-descriptions-item>
                  <el-descriptions-item label="处理后均耗">{{ verifyResult.postAvgKwh }} kWh</el-descriptions-item>
                  <el-descriptions-item label="处理前偏差">{{ verifyResult.preDeviationPct ?? '--' }}%</el-descriptions-item>
                  <el-descriptions-item label="处理后偏差">{{ verifyResult.postDeviationPct ?? '--' }}%</el-descriptions-item>
                  <el-descriptions-item label="变化">{{ verifyResult.changePct ?? '--' }}%</el-descriptions-item>
                  <el-descriptions-item label="样本">{{ verifyResult.basis?.preHours }}h / {{ verifyResult.basis?.postHours }}h</el-descriptions-item>
                </el-descriptions>
                <div class="verify-caveat" v-for="(c, idx) in (verifyResult.basis?.caveats || [])" :key="idx">
                  · {{ c }}
                </div>
              </div>
            </div>

    </el-drawer>

    <!-- 优化建议抽屉（快捷入口，聚焦建议） -->
    <el-drawer v-model="suggestVisible" size="58%" :title="`优化建议 · ${current?.buildingId || ''}`">
      <div v-loading="sugLoading">
        <template v-if="suggest">
          <el-alert v-if="suggest.disclaimer" type="warning" :closable="false" show-icon
                    class="mb-12" title="使用须知" :description="suggest.disclaimer" />

          <el-empty v-if="!(suggest.suggestions || []).length"
                    description="该建筑在查询区间内无有效数据，暂无法给出优化建议" />

          <el-card v-for="(s, i) in suggest.suggestions || []" :key="i" shadow="never"
                   class="attr-card mb-12">
            <div class="attr-head">
              <span class="attr-cause">{{ i + 1 }}. {{ s.title }}</span>
              <el-tag :type="priorityTag(s.priority)" size="small" effect="dark">
                优先级 {{ s.priority }}
              </el-tag>
              <el-tag v-if="s.needsManualReview" type="warning" size="small" effect="plain">
                需人工复核
              </el-tag>
            </div>
            <div class="attr-row"><span class="attr-key">依据</span><span class="attr-val">{{ s.evidence }}</span></div>
            <div class="attr-row"><span class="attr-key">措施</span><span class="attr-val">{{ s.actions }}</span></div>
            <div class="attr-row"><span class="attr-key">原理</span><span class="attr-val text-dim">{{ s.basis }}</span></div>
          </el-card>

          <div class="chart-title mt-12">能效画像</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item v-for="f in profileRows(suggest.efficiencyProfile)" :key="f.key"
                                  :label="f.label">
              {{ f.text }}
            </el-descriptions-item>
          </el-descriptions>
        </template>
        <el-empty v-else-if="!sugLoading" description="暂无优化建议" />
      </div>
    </el-drawer>

  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { analyticsApi, anomalyHandleApi, diagnosisApi, verifyApi, energyApi } from '@/api'
import AiInsight from '@/components/AiInsight.vue'
import { useDataVersionRefresh } from '@/composables/useDataVersionRefresh'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { fmtNum, fmtInt } from '@/utils/echarts'
import type {
  AnomalyEvent, AnomalyAnalysisVO, BuildingRow, DiagnoseResult, DimensionOption, SiteRow, SuggestResult,
  AnomalyHandleDetail
} from '@/types/api'

const app = useAppStore()
const route = useRoute()

const loading = ref(false)
const loadError = ref(false)
const sites = ref<SiteRow[]>([])
const buildings = ref<BuildingRow[]>([])
const typeOptions = ref<DimensionOption[]>([])
const events = ref<AnomalyEvent[]>([])
/** 告警概览（/analytics/anomaly，按时间·园区·建筑口径） */
const summary = ref<AnomalyAnalysisVO | null>(null)

const range = ref<[string, string]>([app.startDate, app.endDate])
const siteId = ref('')
const buildingId = ref('')
const anomalyType = ref('')
const severity = ref('')
const minDuration = ref<number | undefined>(undefined)

/** 处置状态筛选 */
const handleStatus = ref('')

// ── 异常处置 ──
const HANDLE_STATUSES = ['未处理', '处理中', '已处理', '已忽略', '待复检'] as const
const userStore = useUserStore()
/** 只有 admin / operator 能回写（后端 @PreAuthorize 兜底，前端只负责隐藏入口） */
const canHandle = computed(() => userStore.hasRole('admin') || userStore.hasRole('operator'))
const handleLoading = ref(false)
const handleSaving = ref(false)
const handleDetail = ref<AnomalyHandleDetail | null>(null)
const handleForm = ref<{ status: string; note: string }>({ status: '处理中', note: '' })
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const diagnoseVisible = ref(false)
const suggestVisible = ref(false)
const diagLoading = ref(false)
const diagnosisInsightRef = ref<InstanceType<typeof AiInsight> | null>(null)
const sugLoading = ref(false)
const diagnose = ref<DiagnoseResult | null>(null)
const suggest = ref<SuggestResult | null>(null)
const current = ref<AnomalyEvent | null>(null)

// ---------------------------------------------------------------- 展示辅助
/** 严重度 → 语义键（用于风险标签配色） */
function severityKey(v: string | null) {
  return v === '高' ? 'high' : v === '中' ? 'mid' : 'low'
}

function confidenceTag(v: string | null | undefined) {
  switch (v) {
    case '高': return 'success' as const
    case '中': return 'warning' as const
    default: return 'info' as const
  }
}

function priorityTag(v: string | null | undefined) {
  switch (v) {
    case '高': return 'danger' as const
    case '中': return 'warning' as const
    default: return 'success' as const
  }
}

/** anomalyVsNormal / efficiencyProfile 在后端是 Map 结构，取数时统一做空值保护 */
function num(row: Record<string, unknown> | undefined, key: string): number | null {
  const v = row?.[key]
  return typeof v === 'number' ? v : null
}

const profileMeta: { key: string; label: string; unit?: string; int?: boolean }[] = [
  { key: 'buildingTypeCn', label: '建筑类型' },
  { key: 'dataGrade', label: '数据分级' },
  { key: 'energyGrade', label: '能耗评级' },
  { key: 'areaM2', label: '建筑面积', unit: '㎡' },
  { key: 'electricityKwh', label: '区间用电量', unit: 'kWh' },
  { key: 'hvacKwh', label: '空调能耗', unit: 'kWh' },
  { key: 'carbonKg', label: '碳排放', unit: 'kg' },
  { key: 'euiYear', label: 'EUI', unit: 'kWh/㎡·a' },
  { key: 'copAvg', label: '平均 COP' },
  { key: 'copMin', label: '最低 COP' },
  { key: 'supplyTempAvg', label: '平均供水温度', unit: '℃' },
  { key: 'returnTempAvg', label: '平均回水温度', unit: '℃' },
  { key: 'deltaTAvg', label: '平均温差', unit: '℃' },
  { key: 'daytimeAvgKwh', label: '日间均电耗', unit: 'kWh/h' },
  { key: 'nightAvgKwh', label: '夜间均电耗', unit: 'kWh/h' },
  { key: 'anomalyHours', label: '异常小时', unit: 'h', int: true },
  { key: 'qualityHours', label: '数据质量缺陷小时', unit: 'h', int: true },
  { key: 'evaluatedHours', label: '评估小时数', unit: 'h', int: true }
]

function profileRows(profile?: Record<string, unknown>) {
  if (!profile) return []
  return profileMeta
    .filter((m) => profile[m.key] !== undefined && profile[m.key] !== null)
    .map((m) => {
      const raw = profile[m.key]
      const text = typeof raw === 'number'
        ? (m.int ? fmtInt(raw) : fmtNum(raw, 2)) + (m.unit ? ` ${m.unit}` : '')
        : `${raw}${m.unit ? ` ${m.unit}` : ''}`
      return { key: m.key, label: m.label, text }
    })
}

// ---------------------------------------------------------------- 数据加载
async function loadSummary() {
  try {
    summary.value = await analyticsApi.anomaly({
      startDate: app.startDate,
      endDate: app.endDate,
      siteId: siteId.value || undefined,
      buildingId: buildingId.value || undefined
    })
  } catch {
    summary.value = null
  }
}

async function loadEvents() {
  loading.value = true
  loadError.value = false
  try {
    const res = await analyticsApi.scanAnomalies({
      startDate: app.startDate,
      endDate: app.endDate,
      siteId: siteId.value || undefined,
      buildingId: buildingId.value || undefined,
      anomalyType: anomalyType.value || undefined,
      severity: severity.value || undefined,
      minDuration: minDuration.value ?? undefined,
      handleStatus: handleStatus.value || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    events.value = (res.events || []) as AnomalyEvent[]
    total.value = res.total || 0
  } catch {
    events.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function search() {
  pageNum.value = 1
  loadEvents()
  loadSummary()
}

function onPageChange(p: number) {
  pageNum.value = p
  loadEvents()
}

function onPageSizeChange(s: number) {
  pageSize.value = s
  pageNum.value = 1
  loadEvents()
}

function onSiteChange() {
  buildingId.value = ''
  loadBuildings()
}

function onRangeChange(v: [string, string] | null) {
  if (v && v.length === 2) app.setRange(v[0], v[1])
}

function onReset() {
  app.setRange('2016-01-01', '2016-12-31')
  range.value = [app.startDate, app.endDate]
  siteId.value = ''
  buildingId.value = ''
  anomalyType.value = ''
  severity.value = ''
  minDuration.value = undefined
  pageNum.value = 1
  pageSize.value = 20
  loadBuildings()
  loadEvents()
  loadSummary()
}

async function loadBuildings() {
  try {
    buildings.value = await energyApi.buildings({ siteId: siteId.value || undefined, limit: 500 })
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------------------------------------------------------------- 诊断 / 建议
/** 处置状态 → el-tag 颜色 */
/** 处置状态 → 小圆点类名后缀 */
function statusKey(st?: string | null) {
  switch (st) {
    case '已处理': return 'done'
    case '已忽略': return 'ignored'
    case '处理中': return 'processing'
    case '待复检': return 'verify'
    default: return 'pending'
  }
}


function statusTagType(st?: string | null) {
  switch (st) {
    case '已处理': return 'success'
    case '已忽略': return 'info'
    case '处理中': return 'warning'
    case '待复检': return 'primary'
    default: return 'danger'
  }
}

/** 打开处置抽屉：每次重新拉详情，拿到最新 handleVersion 用于乐观锁 */
async function openHandle(row: AnomalyEvent) {
  handleLoading.value = true
  handleDetail.value = null
  try {
    const d = await anomalyHandleApi.detail(row.id)
    handleDetail.value = d
    const cur = d.handleStatus || '未处理'
    handleForm.value = {
      status: cur === '未处理' || cur === '处理中' ? '已处理' : cur,
      note: d.handleNote || ''
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '加载异常详情失败')
  } finally {
    handleLoading.value = false
  }
}

async function submitHandle() {
  if (!handleDetail.value) return
  handleSaving.value = true
  try {
    const d = await anomalyHandleApi.handle(handleDetail.value.id, {
      status: handleForm.value.status,
      note: handleForm.value.note,
      version: handleDetail.value.handleVersion ?? 0
    })
    handleDetail.value = d
    ElMessage.success(`已标记为「${handleForm.value.status}」`)
    await loadEvents()
  } catch (e: any) {
    // 409 = 别人先改了，提示刷新；其它错误直接展示后端文案
    ElMessage.error(e?.message || '提交失败')
  } finally {
    handleSaving.value = false
  }
}


// ── AI 诊断记录（持久化） ──
const diagRecord = ref<Record<string, any> | null>(null)
const diagHistory = ref<Record<string, any>[]>([])
const diagSaving = ref(false)
const showDiagHistory = ref(false)

// ── 处置后复检 ──
const verifying = ref(false)
const verifyResult = ref<Record<string, any> | null>(null)

/** 打开抽屉时只读缓存：有有效记录就直接展示，不主动调大模型 */
async function loadDiagnosis() {
  if (!current.value) return
  diagRecord.value = null
  diagHistory.value = []
  verifyResult.value = null
  try {
    const list = await diagnosisApi.history({
      buildingId: current.value.buildingId,
      anomalyId: current.value.id,
      limit: 10
    })
    diagHistory.value = list || []
    const valid = (list || []).find((x) => x.status === 'VALID')
    diagRecord.value = valid || (list || [])[0] || null
  } catch {
    /* 无历史不影响抽屉 */
  }
}

async function generateDiagnosis(force: boolean) {
  if (!current.value) return
  diagSaving.value = true
  try {
    const rec = await diagnosisApi.generate({
      buildingId: current.value.buildingId,
      anomalyId: current.value.id,
      startDate: range.value?.[0] || app.startDate,
      endDate: range.value?.[1] || app.endDate,
      force
    })
    diagRecord.value = rec
    ElMessage.success(force ? '已重新分析并保存' : (rec.reused ? '已复用有效诊断' : '诊断已生成并保存'))
    const list = await diagnosisApi.history({
      buildingId: current.value.buildingId,
      anomalyId: current.value.id,
      limit: 10
    })
    diagHistory.value = list || []
  } catch {
    /* 拦截器已提示 */
  } finally {
    diagSaving.value = false
  }
}

async function runVerify() {
  if (!current.value) return
  verifying.value = true
  try {
    verifyResult.value = await verifyApi.verify(current.value.id)
    ElMessage.success(`复检完成：${verifyResult.value?.conclusionText || ''}`)
    loadDiagnosis()
  } catch {
    /* 拦截器已提示 */
  } finally {
    verifying.value = false
  }
}


async function openDiagnose(row: AnomalyEvent) {
  // 诊断抽屉同时承载处置入口：静默加载该异常的处置详情
    void loadDiagnosis()
  void openHandle(row)
  current.value = row
  diagnose.value = null
  suggest.value = null
  diagnoseVisible.value = true
  diagLoading.value = true
  try {
    const [diag, sug] = await Promise.all([
      analyticsApi.diagnose({
        buildingId: row.buildingId,
        startDate: app.startDate,
        endDate: app.endDate
      }),
      analyticsApi.suggest({
        buildingId: row.buildingId,
        startDate: app.startDate,
        endDate: app.endDate
      }).catch(() => null)
    ])
    diagnose.value = diag
    suggest.value = sug
    // 诊断数据就绪后再生成 AI 证据链解读（此时条件完整）
    void diagnosisInsightRef.value?.load()
  } catch {
    diagnose.value = null
  } finally {
    diagLoading.value = false
  }
}

async function openSuggest(row: AnomalyEvent) {
  current.value = row
  suggest.value = null
  suggestVisible.value = true
  sugLoading.value = true
  try {
    suggest.value = await analyticsApi.suggest({
      buildingId: row.buildingId,
      startDate: app.startDate,
      endDate: app.endDate
    })
  } catch {
    suggest.value = null
  } finally {
    sugLoading.value = false
  }
}

function onRowClick(row: AnomalyEvent) {
  openDiagnose(row)
}

// 导入新数据后重载建筑列表 / 事件 / 汇总
useDataVersionRefresh(async () => {
  await loadBuildings()
  await loadEvents()
  await loadSummary()
})

onMounted(async () => {
  try {
    const [s, dim] = await Promise.all([
      energyApi.sites(),
      energyApi.dimensions('anomaly_type')
    ])
    sites.value = s
    typeOptions.value = dim.options || []
  } catch {
    /* 忽略 */
  }

    // 3D 园区页通过 /anomaly?buildingId=xxx 跳转过来，需要预选该建筑
    const q = route.query.buildingId
    if (typeof q === 'string' && q) {
      buildingId.value = q
      ElMessage.info(`已按 3D 园区传入的建筑预选：${q}`)
    }
    // 其它页面（能耗查询、看板）会一起带上时间范围，这里沿用，保持口径一致
    const qs = route.query.startDate
    const qe = route.query.endDate
    if (typeof qs === 'string' && typeof qe === 'string' && qs && qe) {
      range.value = [qs, qe]
    }

  await loadBuildings()
  await loadEvents()
  await loadSummary()
})
</script>

<style scoped>
/* ── 筛选分组 ── */
.filter-group { margin-bottom: 12px; }
.filter-group-label {
  font-size: 11px; font-weight: 600; letter-spacing: 0.05em;
  color: var(--carbon-text-dim, #5d6e66); margin-bottom: 8px;
}
.filter-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; }

/* ── 告警概览 KPI 卡 ── */
.kpi-card {
  background: var(--carbon-panel, #fff);
  border: 1px solid var(--carbon-border, #e3e9e4);
  border-radius: 14px;
  padding: 14px 16px;
  box-shadow: var(--shadow-sm);
  position: relative;
  overflow: hidden;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}
.kpi-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.kpi-card::after {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 2px;
  background: linear-gradient(90deg, var(--carbon-primary, #35866b), rgba(53, 134, 107, 0));
  opacity: 0.35;
}
/* 风险类卡片：顶部点缀改风险红，数值用风险色 */
.kpi-card.risk::after {
  background: linear-gradient(90deg, #c26b62, rgba(194, 107, 98, 0));
}
.kpi-card.risk .kpi-value { color: #c26b62; }
.kpi-label { font-size: 12px; color: var(--carbon-text-dim, #5d6e66); margin-bottom: 6px; display: flex; align-items: center; gap: 4px; }
.kpi-value { font-size: 22px; font-weight: 600; color: var(--carbon-text, #253d35); line-height: 1.2; font-variant-numeric: tabular-nums; }
.kpi-unit { font-size: 12px; font-weight: 400; color: var(--carbon-text-mute, #7b8b84); margin-left: 4px; }
.kpi-sub { font-size: 11px; color: var(--carbon-text-mute, #7b8b84); margin-top: 5px; }
.summary-note { font-size: 12px; margin: 4px 2px 14px; }

/* ── 严重度风险标签 ── */
.severity-badge {
  display: inline-block; padding: 1px 9px; border-radius: 6px; font-size: 11px; font-weight: 500;
}
.severity-badge.sev-high { background: #f9ece9; color: #9b564e; }
.severity-badge.sev-mid  { background: #f6eed8; color: #8a6d2f; }
.severity-badge.sev-low  { background: #e9f1f4; color: #497286; }
.dev-high { color: #c26b62; font-weight: 600; }

.attr-card :deep(.el-card__body) { padding: 12px 14px; }
.attr-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.attr-cause { font-weight: 600; font-size: 13px; color: var(--carbon-text, #253d35); }
.attr-row { display: flex; gap: 8px; font-size: 12.5px; line-height: 1.7; margin-top: 4px; }
.attr-key { flex: 0 0 34px; color: var(--carbon-text-dim, #5d6e66); font-weight: 600; }
.attr-val { flex: 1; color: #374151; word-break: break-word; }

:deep(.el-pagination) { justify-content: flex-end; }
:deep(.el-table .cell) { font-variant-numeric: tabular-nums; }

/* 处置状态小圆点：只在非「未处理」时出现，避免列表被状态列占满 */
.handle-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: 1px;
  background: #c9d3cd;
}
.handle-dot.hs-done { background: #35866B; }
.handle-dot.hs-processing { background: #D18F5F; }
.handle-dot.hs-ignored { background: #9aa8a0; }
.handle-dot.hs-verify { background: #5B8FA8; }

/* 诊断记录与复检 */
.diag-box { padding: 10px 12px; border-radius: 10px; background: #f6faf8; border: 1px solid #e3efe8; }
.diag-text { font-size: 13px; line-height: 1.75; color: #253d35; white-space: pre-wrap; }
.diag-warn { margin-top: 8px; font-size: 12px; color: #c26b62; }
.verify-box { padding: 10px 12px; border-radius: 10px; background: #fbfdfc; border: 1px solid #e6efea; }
.verify-reason { margin-top: 6px; font-size: 12.5px; color: #4a5f57; }
.verify-caveat { margin-top: 6px; font-size: 12px; color: #8a9a92; line-height: 1.6; }
</style>
