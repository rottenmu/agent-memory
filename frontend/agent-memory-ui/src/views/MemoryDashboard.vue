<template>
  <section class="amm-page amm-dsh">
    <!-- dsh 式左侧导航 -->
    <aside class="dsh-sidebar">
      <div class="dsh-brand">
        <span class="dsh-brand-mark">记</span>
        <span class="dsh-brand-text">Agent Memory</span>
      </div>
      <nav class="dsh-nav">
        <button
          v-for="item in navItems"
          :key="item.key"
          class="dsh-nav-item"
          :class="{ active: mainTab === item.key }"
          @click="goTab(item.key)"
        >
          <el-icon :size="16"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </nav>
      <div class="dsh-sidebar-foot">v1.0 · 四层记忆</div>
    </aside>

    <!-- 主内容区 -->
    <main class="dsh-main">
      <div v-show="mainTab === 'overview'" class="dsh-pane">
      <div class="page-head">
        <div class="head-title">
          <h1>记忆总览</h1>
              </div>
      <el-button type="primary" plain :icon="Refresh" @click="loadAll">刷新</el-button>
    </div>
    <el-tabs v-model="activeTab" class="amm-tabs">
      <el-tab-pane label="总览" name="overview">
    <!-- 记忆数据速览：用户 / 会话 / 全局 / 溯源 / 策略 五类一屏可见 -->
    <div v-loading="overviewLoading" class="stats-grid overview-row" element-loading-background="rgba(255,255,255,0.6)">
      <div
        v-for="c in overviewCards"
        :key="c.key"
        class="ov-card stat-card"
        :class="'ov-' + c.key"
        role="button"
        tabindex="0"
        @click="goTab(c.tab)"
        @keydown.enter.prevent="goTab(c.tab)"
      >
        <div class="stat-label ov-label">{{ c.label }}</div>
        <div class="stat-value ov-value">{{ c.value }}<small class="unit">{{ c.unit }}</small></div>
        <div class="stat-trend"><i :class="c.trendCls" :style="{ width: c.trend + '%' }"></i></div>
        <div class="ov-sub">{{ c.sub }}</div>
      </div>
    </div>

    <el-row :gutter="14" class="overview-row">
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card shadow="never" class="ov-list-card">
          <template #header>
            <div class="ov-card-head">
              <b>用户记忆</b>
              <el-link type="primary" underline="never" @click="goTab('user')">查看全部 →</el-link>
            </div>
          </template>
          <div v-for="(m, i) in overview.user" :key="i" class="ov-item">
            <el-tag size="small" :type="tagType(m.category)" class="ov-tag">{{ catLabel(m.category) }}</el-tag>
            <span class="ov-text" :title="m.content">{{ m.content }}</span>
            <el-tag v-if="m.sensitiveMasked" size="small" type="danger" effect="dark" class="ov-mask">脱敏</el-tag>
          </div>
          <el-empty v-if="!overview.user.length" description="暂无用户记忆" :image-size="48" />
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card shadow="never" class="ov-list-card">
          <template #header>
            <div class="ov-card-head">
              <b>会话记忆</b>
              <el-link type="primary" underline="never" @click="goTab('session')">查看全部 →</el-link>
            </div>
          </template>
          <div v-for="(m, i) in overview.session" :key="i" class="ov-item">
            <span class="ov-key">{{ m.id }}</span>
            <span class="ov-text" :title="m.content">{{ m.content }}</span>
          </div>
          <el-empty v-if="!overview.session.length" :description="overview.sessionId ? '该会话暂无变量' : '暂无活跃会话'" :image-size="48" />
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card shadow="never" class="ov-list-card">
          <template #header>
            <div class="ov-card-head">
              <b>全局记忆</b>
              <el-link type="primary" underline="never" @click="goTab('global')">查看全部 →</el-link>
            </div>
          </template>
          <div v-for="(m, i) in overview.global" :key="i" class="ov-item">
            <span class="ov-key">{{ m.id }}</span>
            <span class="ov-text" :title="m.content">{{ m.content }}</span>
            <el-tag v-if="m.sensitiveMasked" size="small" type="danger" effect="dark" class="ov-mask">脱敏</el-tag>
          </div>
          <el-empty v-if="!overview.global.length" description="暂无全局记忆" :image-size="48" />
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card shadow="never" class="ov-list-card">
          <template #header>
            <div class="ov-card-head">
              <b>记忆策略</b>
              <el-link type="primary" underline="never" @click="goTab('policy')">查看全部 →</el-link>
            </div>
          </template>
          <div class="ov-policy">
            <div class="ov-policy-row">
              <span class="ov-label-inline">敏感过滤</span>
              <el-tag size="small" :type="overview.policy.sensitiveFiltering ? 'success' : 'info'">
                {{ overview.policy.sensitiveFiltering ? '已开启' : '已关闭' }}
              </el-tag>
            </div>
            <div class="ov-policy-row">
              <span class="ov-label-inline">白名单</span>
              <el-tag size="small" :type="overview.policy.whitelistEnabled ? 'primary' : 'info'">
                {{ overview.policy.whitelistEnabled ? '启用' : '未启用' }}
              </el-tag>
            </div>
            <div class="ov-policy-row">
              <span class="ov-label-inline">脱敏规则</span>
              <span class="ov-policy-num">{{ (overview.policy.sensitiveRules || []).length }} 类</span>
            </div>
            <div class="ov-policy-row">
              <span class="ov-label-inline">白名单类别</span>
              <span class="ov-policy-num">{{ (overview.policy.whitelistCategories || []).length }} 类</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 溯源分析：最近消息 -->
    <el-card shadow="never" class="overview-row">
      <template #header>
        <div class="ov-card-head">
          <b>溯源分析 · 最近消息</b>
          <el-link type="primary" underline="never" @click="goTab('trace')">进入溯源分析 →</el-link>
        </div>
      </template>
      <el-table :data="overview.trace" size="small" stripe>
        <el-table-column label="角色" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="roleType(row.role)" effect="plain">{{ row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="320" show-overflow-tooltip />
        <el-table-column label="会话" width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.session_id || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button v-if="row.trace_id" link type="primary" size="small" @click="goTab('trace', row.trace_id)">查看链路</el-button>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!overview.trace.length" description="暂无消息（ETL 同步后展示）" />
    </el-card>

    <!-- 指标卡 -->
    <el-row :gutter="16" class="kpi-row">
      <el-col :xs="24" :sm="12" :lg="6" v-for="kpi in kpis" :key="kpi.label">
        <div class="stat-card kpi-card">
          <div class="stat-label kpi-label">{{ kpi.label }}</div>
          <div class="stat-value kpi-value">{{ kpi.value }}<small class="unit">{{ kpi.unit }}</small></div>
          <div class="stat-trend"><i :class="kpi.trendCls" :style="{ width: kpi.trend + '%' }"></i></div>
          <div class="kpi-sub">{{ kpi.sub }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="14">
      <!-- 用户行为时序 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header><b>用户行为时序（近 7 天）</b></template>
          <div class="bar-chart">
            <div class="bar-col" v-for="(d, i) in activity" :key="i">
              <div class="bar-track">
                <div class="bar-fill" :style="{ height: barHeight(d.count, activity) }"></div>
              </div>
              <div class="bar-day">{{ d.day }}</div>
              <div class="bar-count">{{ d.count }}</div>
            </div>
          </div>
          <el-empty v-if="!activity.length" description="暂无时序数据（ETL 同步后展示）" :image-size="60" />
        </el-card>
      </el-col>
      <!-- L0 事件构成 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><b>L0 事件构成（蒸馏质量）</b></template>
          <div v-for="(item, i) in distill" :key="i" class="distill-row">
            <span class="distill-role" :class="'role-' + item.role">{{ item.role }}</span>
            <el-progress
              :percentage="percent(item.count, distill)"
              :color="roleColor(item.role)"
              :stroke-width="10"
            />
            <span class="distill-count">{{ item.count }}</span>
          </div>
          <el-empty v-if="!distill.length" description="暂无事件数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 会话排行 -->
    <el-card shadow="never" class="mt-14">
      <template #header>
        <div class="ov-card-head">
          <b>会话活跃排行</b>
          <el-input v-model="sessionSearch" placeholder="按会话 ID 过滤" clearable class="session-search" :prefix-icon="Search" />
        </div>
      </template>
      <el-table :data="filteredSessions" size="small" stripe>
        <el-table-column label="会话 ID" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><span class="amm-mono">{{ row.session_id }}</span></template>
        </el-table-column>
        <el-table-column prop="message_count" label="消息数" width="90" />
        <el-table-column label="Token 消耗" width="130">
          <template #default="{ row }"><span class="amm-mono">{{ formatTokens(row.total_tokens) }}</span></template>
        </el-table-column>
        <el-table-column label="活跃时长" width="120">
          <template #default="{ row }"><span class="amm-mono">{{ formatDuration(row.active_duration_ms) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="previewSession(row)">消息预览</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!sessionStats.length" description="暂无会话统计（ETL 同步后展示）" />
    </el-card>

      </el-tab-pane>
      <el-tab-pane label="会话消息" name="messages">
        <div class="arch-stats">
          <div class="stat-card stat-soul">
            <div class="stat-icon"><el-icon><UserFilled /></el-icon></div>
            <div class="stat-num">{{ formatNum(archStats.soulConfigCount) }}</div>
            <div class="stat-label">SOUL 配置</div>
          </div>
          <div class="stat-card stat-user">
            <div class="stat-icon"><el-icon><User /></el-icon></div>
            <div class="stat-num">{{ formatNum(archStats.userProfileCount) }}</div>
            <div class="stat-label">USER 档案</div>
          </div>
          <div class="stat-card stat-today">
            <div class="stat-icon"><el-icon><ChatDotRound /></el-icon></div>
            <div class="stat-num">{{ formatNum(archStats.todayDialogues) }}</div>
            <div class="stat-label">今日对话</div>
          </div>
          <div class="stat-card stat-total">
            <div class="stat-icon"><el-icon><DataAnalysis /></el-icon></div>
            <div class="stat-num">{{ formatNum(archStats.totalDialogues) }}</div>
            <div class="stat-label">累计对话</div>
          </div>
          <div class="stat-card stat-extract">
            <div class="stat-icon"><el-icon><MagicStick /></el-icon></div>
            <div class="stat-num">{{ archStats.extracted || 0 }}</div>
            <div class="stat-label">已提取</div>
          </div>
        </div>
        <div class="msg-toolbar">
          <el-select v-model="archQuery.type" placeholder="全部类型" clearable style="width: 130px" @change="loadArch">
            <el-option label="SOUL 配置" value="SOUL" />
            <el-option label="USER 档案" value="USER" />
          </el-select>
          <el-input v-model="archQuery.q" placeholder="搜索 名称 / 核心摘要" clearable style="width: 220px"
            :prefix-icon="Search" @keyup.enter="loadArch" @clear="loadArch" />
          <el-button type="primary" :icon="Plus" @click="openCreateArch">新建</el-button>
          <el-button :icon="Refresh" circle @click="loadArch" />
          <span class="msg-total">共 {{ archTotal }} 条画像配置</span>
        </div>
        <el-table v-loading="archLoading" :data="archRows" size="small" stripe>
          <el-table-column prop="name" label="用户" width="140" show-overflow-tooltip />
          <el-table-column prop="summary" label="核心摘要" min-width="280" show-overflow-tooltip />
          <el-table-column prop="background" label="背景" min-width="240" show-overflow-tooltip />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.type === 'SOUL' ? 'warning' : 'primary'" effect="plain">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="70" align="center" />
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.source === '自动' ? 'success' : 'info'" effect="plain">{{ row.source }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" align="center">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openEditArch(row)">编辑</el-button>
              <el-button size="small" text type="warning" @click="onExtractArch(row)">提取</el-button>
              <el-button size="small" text type="danger" @click="onDeleteArch(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无画像配置（可新建或执行「提取」）" />
          </template>
        </el-table>

        <el-dialog v-model="archDialogVisible" :title="archEditing.id ? '编辑画像配置' : '新建画像配置'" width="560px" destroy-on-close>
          <el-form ref="archFormRef" :model="archEditing" :rules="archRules" label-width="90px">
            <el-form-item label="类型" prop="type">
              <el-radio-group v-model="archEditing.type">
                <el-radio value="SOUL">SOUL（灵魂/人格）</el-radio>
                <el-radio value="USER">USER（用户档案）</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="名称" prop="name">
              <el-input v-model="archEditing.name" placeholder="用户/画像名称" />
            </el-form-item>
            <el-form-item label="核心摘要" prop="summary">
              <el-input v-model="archEditing.summary" type="textarea" :rows="3" placeholder="一句话概括该画像" />
            </el-form-item>
            <el-form-item label="背景" prop="background">
              <el-input v-model="archEditing.background" type="textarea" :rows="2" placeholder="背景/上下文说明" />
            </el-form-item>
            <el-form-item label="完整内容" prop="content">
              <el-input v-model="archEditing.content" type="textarea" :rows="5" placeholder="完整画像内容" />
            </el-form-item>
            <el-form-item label="来源" prop="source">
              <el-input v-model="archEditing.source" placeholder="手动 / 自动" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="archDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="archSaving" @click="submitArch">确定</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>

      </div>
      <div v-show="mainTab === 'user'" class="dsh-pane">
        <UserMemoryManage />
      </div>
      <div v-show="mainTab === 'session'" class="dsh-pane">
        <SessionMemoryManage />
      </div>
      <div v-show="mainTab === 'global'" class="dsh-pane">
        <GlobalMemoryManage />
      </div>
      <div v-show="mainTab === 'trace'" class="dsh-pane">
        <TraceAnalysis :trace-id="traceIdParam" />
      </div>
      <div v-show="mainTab === 'policy'" class="dsh-pane">
        <MemoryPolicy />
      </div>
    </main>

    <el-drawer v-model="previewVisible" title="会话消息预览" size="560px">
      <template v-if="preview">
        <el-descriptions :column="1" size="small" border class="preview-file">
          <el-descriptions-item label="会话 ID">{{ preview.sessionId }}</el-descriptions-item>
          <el-descriptions-item label="md 文档名称">
            <el-tag size="small" type="success">{{ preview.memoryFileName }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="文件地址" class="preview-path">
            <span class="path-text">{{ preview.memoryFilePath }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="preview-title">消息内容（{{ (preview.messages || []).length }} 条）</div>
        <el-timeline v-if="(preview.messages || []).length">
          <el-timeline-item v-for="(m, i) in preview.messages" :key="i" :type="roleType(m.role)" :timestamp="formatTs(m.ts)" placement="top">
            <el-tag size="small" :type="roleType(m.role)" effect="plain">{{ m.role }}</el-tag>
            <div class="msg-content">{{ m.content }}</div>
            <div class="msg-trace" v-if="m.trace_id">trace: {{ m.trace_id }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="该会话暂无 L0 消息（ETL 同步后展示）" />
      </template>
    </el-drawer>
  </section>
</template>

<script setup>
import '../styles/tokens.css'
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh, Search, Plus, UserFilled, User, ChatDotRound, DataAnalysis, MagicStick, Collection, Connection, Key } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import UserMemoryManage from './UserMemoryManage.vue'
import SessionMemoryManage from './SessionMemoryManage.vue'
import GlobalMemoryManage from './GlobalMemoryManage.vue'
import TraceAnalysis from './TraceAnalysis.vue'
import MemoryPolicy from './MemoryPolicy.vue'
import {
  getSessionStats,
  getUserActivity,
  getDistillationStats,
  getSessionMessages,
  getMessages,
  getArchStats,
  listArchConfigs,
  createArchConfig,
  updateArchConfig,
  extractArchConfig,
  deleteArchConfig,
  getUserMemory,
  getSessionVars,
  getGlobalMemory,
  getMemoryPolicy,
  recordsOf
} from '../api/memory'

const route = useRoute()
const router = useRouter()

/* ============ 主 Tab（记忆总览页收纳全部子页） ============ */
const mainTab = ref('overview')
const traceIdParam = ref('')

/* dsh 式左侧导航项 */
const navItems = [
  { key: 'overview', label: '记忆总览', icon: DataAnalysis },
  { key: 'user', label: '用户记忆', icon: User },
  { key: 'session', label: '会话记忆', icon: ChatDotRound },
  { key: 'global', label: '全局记忆', icon: Collection },
  { key: 'trace', label: '溯源分析', icon: Connection },
  { key: 'policy', label: '记忆策略', icon: Key }
]

watch(
  () => route.query.tab,
  (v) => {
    if (v && v !== mainTab.value) mainTab.value = String(v)
  }
)

watch(
  () => route.query.traceId,
  (v) => {
    traceIdParam.value = v ? String(v) : ''
  }
)

const sessionStats = ref([])
const activity = ref([])
const distill = ref([])
const previewVisible = ref(false)
const preview = ref(null)
const previewLoading = ref(false)
const activeTab = ref('overview')
const msgSession = ref('')
const messageRows = ref([])
const messageTotal = ref(0)
const messagePage = ref(0)
const messageSize = ref(50)
const msgLoading = ref(false)

const kpis = computed(() => {
  const sessions = sessionStats.value.length
  const msgs = sessionStats.value.reduce((a, b) => a + (b.message_count || 0), 0)
  const tokens = sessionStats.value.reduce((a, b) => a + (b.total_tokens || 0), 0)
  const events = distill.value.reduce((a, b) => a + (b.event_count || 0), 0)
  const max = Math.max(1, sessions, msgs, events)
  return [
    { label: '会话数', value: sessions, unit: ' 个', sub: 'OLAP 会话聚合', trend: Math.round((sessions / max) * 100), trendCls: '' },
    { label: '消息总量', value: msgs, unit: ' 条', sub: '累计对话消息', trend: Math.round((msgs / max) * 100), trendCls: '' },
    { label: 'Token 消耗', value: formatTokens(tokens), unit: '', sub: '累计推理 token', trend: 72, trendCls: 'trend-amber' },
    { label: 'L0 事件量', value: events, unit: ' 条', sub: '原始日志事件流', trend: Math.round((events / max) * 100), trendCls: 'trend-green' }
  ]
})

/* ================= 记忆数据速览（五类一屏） ================= */
const overview = reactive({ user: [], session: [], global: [], policy: {}, trace: [], sessionId: '' })
const overviewLoading = ref(false)

const overviewCards = computed(() => {
  const values = [overview.user.length, overview.session.length, overview.global.length, overview.trace.length]
  const max = Math.max(1, ...values)
  return [
    { key: 'user', label: '用户记忆', value: overview.user.length, unit: ' 条', sub: '最近记录（admin）', tab: 'user', trend: Math.round((overview.user.length / max) * 100), trendCls: '' },
    {
      key: 'session',
      label: '会话记忆',
      value: overview.session.length,
      unit: ' 条',
      sub: overview.sessionId ? '会话 ' + overview.sessionId : '暂无活跃会话',
      tab: 'session',
      trend: Math.round((overview.session.length / max) * 100),
      trendCls: ''
    },
    { key: 'global', label: '全局记忆', value: overview.global.length, unit: ' 条', sub: '最近记录', tab: 'global', trend: Math.round((overview.global.length / max) * 100), trendCls: 'trend-green' },
    { key: 'trace', label: '溯源消息', value: overview.trace.length, unit: ' 条', sub: '最近 L0 消息', tab: 'trace', trend: Math.round((overview.trace.length / max) * 100), trendCls: '' },
    {
      key: 'policy',
      label: '记忆策略',
      value: overview.policy.sensitiveFiltering ? '开启' : '关闭',
      unit: '',
      sub: '脱敏规则 ' + (overview.policy.sensitiveRules || []).length + ' 类',
      tab: 'policy',
      trend: overview.policy.sensitiveFiltering ? 100 : 0,
      trendCls: 'trend-amber'
    }
  ]
})

/* 会话排行搜索过滤 */
const sessionSearch = ref('')
const filteredSessions = computed(() => {
  const q = sessionSearch.value.trim().toLowerCase()
  if (!q) return sessionStats.value
  return sessionStats.value.filter((s) => String(s.session_id || '').toLowerCase().includes(q))
})

function goTab(tab, traceId) {
  const query = { ...route.query, tab }
  if (traceId) query.traceId = traceId
  router.push({ path: '/agent-memory', query })
}

function catLabel(v) {
  return { persona: '画像', preference: '偏好', history: '历史', custom: '自定义' }[v] || v || '—'
}

function tagType(v) {
  return { persona: 'primary', preference: 'success', history: 'warning', custom: 'info' }[v] || 'info'
}

async function loadOverview() {
  overviewLoading.value = true
  try {
    const [u, g, p, msgs] = await Promise.all([
      getUserMemory('admin', { limit: 3 }).catch(() => []),
      getGlobalMemory({ limit: 3 }).catch(() => []),
      getMemoryPolicy().catch(() => ({})),
      getMessages({ page: 0, size: 5 }).catch(() => ({ rows: [], total: 0 }))
    ])
    overview.user = recordsOf(u)
    overview.global = recordsOf(g)
    overview.policy = p || {}
    overview.trace = (msgs && msgs.rows) || []
    // 会话记忆：自动取活跃会话排行第一名，无需输入
    const top = sessionStats.value[0]
    if (top && top.session_id) {
      overview.sessionId = top.session_id
      overview.session = recordsOf(await getSessionVars(top.session_id, { limit: 3 }).catch(() => []))
    } else {
      overview.sessionId = ''
      overview.session = []
    }
  } catch (e) {
    /* 单板块失败不影响其他 */
  } finally {
    overviewLoading.value = false
  }
}

function barHeight(count, list) {
  const max = Math.max(1, ...list.map((d) => d.count || 0))
  return Math.max(4, Math.round(((count || 0) / max) * 120)) + 'px'
}

function percent(count, list) {
  const total = list.reduce((a, b) => a + (b.count || b.event_count || 0), 0)
  return total ? Math.round(((count || 0) / total) * 100) : 0
}

function roleColor(role) {
  return { user: '#2b5bff', assistant: '#2a9d6f', tool: '#e67c3c', system: '#909399' }[role] || '#909399'
}

function formatTokens(t) {
  if (t == null) return '0'
  if (t >= 1000000) return (t / 1000000).toFixed(1) + 'M'
  if (t >= 1000) return (t / 1000).toFixed(1) + 'k'
  return String(t)
}

function formatDuration(ms) {
  if (ms == null || ms <= 0) return '—'
  const min = Math.floor(ms / 60000)
  if (min < 60) return min + ' 分钟'
  return Math.floor(min / 60) + ' 小时 ' + (min % 60) + ' 分'
}

async function previewSession(row) {
  previewVisible.value = true
  previewLoading.value = true
  try {
    preview.value = await getSessionMessages(row.session_id)
  } catch {
    preview.value = { sessionId: row.session_id, memoryFileName: 'MEMORY.md', memoryFilePath: '—', messages: [] }
  } finally {
    previewLoading.value = false
  }
}

function roleType(role) {
  return { user: 'primary', assistant: 'success', tool: 'warning', system: 'info' }[role] || 'info'
}

function formatTs(ts) {
  if (!ts) return ''
  const d = new Date(Number(ts))
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const archStats = ref({})
const archRows = ref([])
const archTotal = ref(0)
const archLoading = ref(false)
const archSaving = ref(false)
const archDialogVisible = ref(false)
const archFormRef = ref(null)
const archQuery = reactive({ type: '', q: '' })
const archEditing = reactive({ id: '', type: 'USER', name: '', summary: '', background: '', content: '', source: '手动' })
const archRules = {
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  summary: [{ required: true, message: '请输入核心摘要', trigger: 'blur' }],
  content: [{ required: true, message: '请输入完整内容', trigger: 'blur' }]
}

function formatNum(n) {
  if (n == null) return 0
  if (n >= 10000) return (n / 10000).toFixed(1).replace(/\.0$/, '') + 'w'
  return Number(n).toLocaleString()
}

async function loadArchStats() {
  try {
    archStats.value = await getArchStats()
  } catch {
    /* 统计卡容错 */
  }
}

async function loadArch() {
  archLoading.value = true
  try {
    const data = await listArchConfigs({
      type: archQuery.type || undefined,
      q: archQuery.q || undefined,
      page: 0,
      size: 100
    })
    archRows.value = data.rows || []
    archTotal.value = data.total || 0
  } catch (e) {
    ElMessage.error('加载画像配置失败：' + (e?.message || '未知错误'))
  } finally {
    archLoading.value = false
  }
}

function resetArch() {
  archEditing.id = ''
  archEditing.type = archQuery.type === 'SOUL' ? 'SOUL' : 'USER'
  archEditing.name = ''
  archEditing.summary = ''
  archEditing.background = ''
  archEditing.content = ''
  archEditing.source = '手动'
}

function openCreateArch() {
  resetArch()
  archDialogVisible.value = true
  archFormRef.value?.clearValidate()
}

function openEditArch(row) {
  resetArch()
  Object.assign(archEditing, row)
  archDialogVisible.value = true
  archFormRef.value?.clearValidate()
}

async function submitArch() {
  const valid = await archFormRef.value?.validate().catch(() => false)
  if (!valid) return
  archSaving.value = true
  try {
    if (archEditing.id) {
      await updateArchConfig(archEditing.id, {
        summary: archEditing.summary,
        background: archEditing.background,
        content: archEditing.content,
        source: archEditing.source
      })
      ElMessage.success('已更新画像配置')
    } else {
      await createArchConfig({ ...archEditing })
      ElMessage.success('已创建画像配置')
    }
    archDialogVisible.value = false
    await Promise.all([loadArch(), loadArchStats()])
  } catch (e) {
    ElMessage.error('保存失败：' + (e?.message || '未知错误'))
  } finally {
    archSaving.value = false
  }
}

async function onExtractArch(row) {
  try {
    await extractArchConfig(row.id)
    ElMessage.success(`已标记提取「${row.name}」`)
    await Promise.all([loadArch(), loadArchStats()])
  } catch (e) {
    ElMessage.error('提取失败：' + (e?.message || '未知错误'))
  }
}

async function onDeleteArch(row) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.name}」（${row.type}）？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteArchConfig(row.id)
    ElMessage.success('已删除')
    await Promise.all([loadArch(), loadArchStats()])
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || '未知错误'))
  }
}

async function loadMessages() {
  msgLoading.value = true
  try {
    const data = await getMessages({
      sessionId: msgSession.value || undefined,
      page: messagePage.value,
      size: messageSize.value
    })
    messageRows.value = data.rows || []
    messageTotal.value = data.total || 0
  } finally {
    msgLoading.value = false
  }
}

async function loadAll() {
  try {
    const [s, a, d] = await Promise.all([
      getSessionStats(),
      getUserActivity(undefined, 7),
      getDistillationStats()
    ])
    sessionStats.value = Array.isArray(s) ? s : []
    activity.value = (Array.isArray(a) ? a : []).map((x) => ({
      day: formatDay(x.day),
      count: x.message_count || 0
    }))
    distill.value = (Array.isArray(d) ? d : []).map((x) => ({ role: x.role, count: x.event_count || 0 }))
  } catch (e) {
    // 分析接口未就绪时保持空态
  }
  await loadOverview()
}

function formatDay(ts) {
  if (!ts) return ''
  const d = new Date(Number(ts))
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

onMounted(() => {
  if (route.query.tab) mainTab.value = String(route.query.tab)
  if (route.query.traceId) traceIdParam.value = String(route.query.traceId)
  loadAll()
  loadArch()
  loadArchStats()
})
</script>

<style scoped>
/* ============ dsh 布局：左侧导航 + 主内容区 ============ */
.amm-dsh {
  display: flex;
  align-items: stretch;
  min-height: calc(100vh - 40px);
  gap: 20px;
}
.dsh-sidebar {
  width: 200px;
  flex-shrink: 0;
  background: var(--amm-canvas-soft);
  border: 1px solid var(--amm-hairline);
  border-radius: var(--amm-radius-lg);
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: sticky;
  top: 0;
  align-self: flex-start;
}
.dsh-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px 10px;
  border-bottom: 1px solid var(--amm-hairline);
}
.dsh-brand-mark {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: var(--amm-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.dsh-brand-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--amm-ink);
  font-family: var(--amm-font-mono);
  letter-spacing: 0;
}
.dsh-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}
.dsh-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border: none;
  background: transparent;
  border-radius: var(--amm-radius-md);
  font-size: 13px;
  color: var(--amm-ink-muted);
  cursor: pointer;
  text-align: left;
  transition: background 150ms ease, color 150ms ease;
  width: 100%;
}
.dsh-nav-item:hover {
  background: rgb(65 118 230 / 0.06);
  color: var(--amm-ink);
}
.dsh-nav-item.active {
  background: var(--amm-primary-soft);
  color: var(--amm-primary-active);
  font-weight: 500;
}
.dsh-sidebar-foot {
  padding: 8px 6px 2px;
  border-top: 1px solid var(--amm-hairline);
  font-size: 11px;
  color: var(--amm-ink-faint);
  font-family: var(--amm-font-mono);
}
.dsh-main {
  flex: 1;
  min-width: 0;
}
.dsh-pane {
  animation: dsh-fade 200ms ease;
}
@keyframes dsh-fade {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: none; }
}
@media (max-width: 768px) {
  .amm-dsh { flex-direction: column; }
  .dsh-sidebar { width: 100%; position: static; flex-direction: row; flex-wrap: wrap; align-items: center; }
  .dsh-brand { border-bottom: none; border-right: 1px solid var(--amm-hairline); padding: 4px 10px 4px 6px; }
  .dsh-nav { flex-direction: row; flex-wrap: wrap; gap: 4px; }
  .dsh-nav-item { width: auto; padding: 7px 10px; }
  .dsh-sidebar-foot { display: none; }
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.head-title h1 { font-size: 20px; font-weight: 600; letter-spacing: -0.2px; margin: 0 0 4px; }
/* ============ 记忆数据速览（原型 stat-card：label + mono 数字 + 迷你趋势条） ============ */
.overview-row { margin-bottom: 20px; }
/* 速览统计卡响应式网格：1 → 2 → 3 → 5 列（≥1440 大屏 5 列避免挤压） */
.stats-grid { display: grid; grid-template-columns: 1fr; gap: 14px; }
@media (min-width: 481px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } }
@media (min-width: 769px) { .stats-grid { grid-template-columns: repeat(3, 1fr); } }
@media (min-width: 1440px) { .stats-grid { grid-template-columns: repeat(5, 1fr); } }
.stat-card {
  background: var(--amm-surface-1);
  border: 1px solid var(--amm-hairline);
  border-radius: var(--amm-radius-lg);
  padding: 20px;
  box-shadow: var(--amm-shadow-xs);
  transition: border-color 150ms ease;
}
.stat-card:hover { border-color: var(--amm-hairline-strong); }
.stat-label { font-size: 12px; color: var(--amm-ink-subtle); display: flex; align-items: center; gap: 6px; letter-spacing: 0.2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.stat-value {
  font-size: 24px; font-weight: 600; letter-spacing: -0.4px; margin-top: 4px;
  color: var(--amm-ink); font-family: var(--amm-font-mono);
  display: flex; align-items: baseline; gap: 4px; white-space: nowrap; word-break: keep-all;
}
.stat-value .unit { font-size: 13px; font-weight: 400; color: var(--amm-ink-subtle); margin-left: 0; font-family: var(--amm-font-sans); white-space: nowrap; }
.stat-trend { margin-top: 8px; height: 4px; border-radius: 2px; background: var(--amm-canvas-soft-2); overflow: hidden; }
.stat-trend i { display: block; height: 100%; border-radius: 2px; background: var(--amm-primary); transition: width 300ms ease; }
.trend-green { background: var(--amm-success); }
.trend-amber { background: var(--amm-warning); }
.trend-gray { background: var(--amm-ink-faint); }
/* 速览卡：可点击 + 薰衣草蓝左缘（唯一彩色点缀） */
.ov-card {
  cursor: pointer;
  border-left: 3px solid var(--amm-primary);
  transition: border-color 150ms ease, transform 150ms ease;
}
.ov-card:hover { transform: translateY(-1px); }
.ov-user, .ov-session, .ov-global, .ov-trace, .ov-policy { border-left-color: var(--amm-primary); }
.ov-value { font-weight: 600; }
.ov-value small { font-size: 13px; font-weight: 400; color: var(--amm-ink-subtle); margin-left: 2px; font-family: var(--amm-font-sans); }
.ov-sub { font-size: 12px; color: var(--amm-ink-subtle); margin-top: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ov-list-card :deep(.el-card__header) { padding: 12px 16px; }
.ov-list-card :deep(.el-card__body) { padding: 12px 16px; }
.ov-card-head { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.ov-item { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--amm-hairline); }
.ov-item:last-child { border-bottom: none; }
.ov-tag { flex-shrink: 0; }
.ov-key { flex-shrink: 0; font-size: 12px; color: var(--amm-ink-muted); font-family: var(--amm-font-mono); max-width: 130px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ov-text { flex: 1; font-size: 13px; color: var(--amm-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ov-mask { flex-shrink: 0; }
.ov-policy { padding: 4px 0; }
.ov-policy-row { display: flex; justify-content: space-between; align-items: center; padding: 7px 0; border-bottom: 1px solid var(--amm-hairline); font-size: 13px; }
.ov-policy-row:last-child { border-bottom: none; }
.ov-label-inline { color: var(--amm-ink-muted); }
.ov-policy-num { color: var(--amm-ink); font-weight: 500; font-family: var(--amm-font-mono); font-size: 12px; }
.text-muted { color: var(--amm-ink-faint); }
/* ============ KPI 统计卡（复用 stat-card 原型风格） ============ */
.kpi-row { margin-bottom: 20px; }
.kpi-sub { font-size: 12px; color: var(--amm-ink-subtle); margin-top: 6px; }
.session-search { width: 220px; }
.bar-chart { display: flex; align-items: flex-end; gap: 12px; height: 150px; padding-top: 6px; }
.bar-col { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; }
.bar-track { height: 120px; width: 26px; background: var(--amm-canvas-soft-2); border-radius: 4px; display: flex; align-items: flex-end; }
.bar-fill { width: 100%; background: var(--amm-primary); border-radius: 4px; }
.bar-day { font-size: 11px; color: var(--amm-ink-subtle); }
.bar-count { font-size: 11px; color: var(--amm-ink-muted); font-family: var(--amm-font-mono); }
.distill-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.distill-role { width: 78px; font-size: 12px; color: var(--amm-ink-muted); }
.distill-count { width: 40px; text-align: right; font-size: 12px; color: var(--amm-ink-subtle); font-family: var(--amm-font-mono); }
.mt-14 { margin-top: 14px; }
.preview-file { margin-bottom: 16px; }
.preview-path .path-text { font-family: var(--amm-font-mono); font-size: 12px; color: var(--amm-ink-muted); word-break: break-all; }
.preview-title { font-size: 13px; font-weight: 600; margin-bottom: 12px; }
.msg-content { margin-top: 6px; font-size: 13px; background: var(--amm-canvas-soft); border-radius: 6px; padding: 8px 10px; line-height: 1.6; white-space: pre-wrap; }
.msg-trace { margin-top: 4px; font-size: 11.5px; color: var(--amm-ink-faint); font-family: var(--amm-font-mono); }
.amm-tabs { margin-top: 6px; }
.msg-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.msg-total { font-size: 12.5px; color: var(--amm-ink-subtle); }
.msg-pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.file-link {
  color: var(--el-color-primary);
  text-decoration: none;
}
.text-muted {
  color: #a8abb2;
}
/* 画像配置统计卡（合并自记忆架构页） */
.arch-stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
.stat-card {
  background: var(--bg-surface, #fff);
  border: 1px solid var(--border-subtle, #e6e8ee);
  border-radius: var(--radius-md, 12px);
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md, 0 4px 12px rgba(0, 0, 0, 0.1));
}
.stat-icon {
  font-size: 22px;
  display: flex;
  align-items: center;
}
.stat-soul .stat-icon   { color: #7c4dff; }
.stat-user .stat-icon   { color: #ff7c43; }
.stat-today .stat-icon  { color: #2db985; }
.stat-total .stat-icon  { color: #3b82f6; }
.stat-extract .stat-icon{ color: #ec4f7c; }
.stat-num {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary, #212529);
  line-height: 1.2;
}
.stat-label {
  font-size: 12px;
  color: var(--text-muted, #868e96);
}
@media (max-width: 768px) {
  .arch-stats {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 480px) {
  .arch-stats {
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
  .stat-num {
    font-size: 18px;
  }
}
</style>
