<template>
  <section class="amm-page amm-today">
    <div class="page-head">
      <div class="head-title">
        <h1>今日对话</h1>
        <div class="amm-sub">
          {{ dateLabel }} · 卡片数字是<b>今日 L0 消息条数</b>（不是会话数）；本页按一轮问答展开为
          <b>{{ turnGroupCount }}</b> 条对话记录，这些消息来自 <b>{{ sessionCount }}</b> 个宿主会话
        </div>
      </div>
      <div class="head-actions">
        <div class="amm-mode">
          <el-button size="small" :type="viewMode === 'turn' ? 'primary' : 'default'" @click="viewMode = 'turn'">按对话轮</el-button>
          <el-button size="small" :type="viewMode === 'session' ? 'primary' : 'default'" @click="viewMode = 'session'">按会话</el-button>
        </div>
        <el-button :icon="ArrowLeft" @click="$emit('back')">返回会话消息</el-button>
        <el-button type="primary" plain :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="l0Today !== null && l0Today !== totalMessages" type="info" :closable="false" class="amm-alert">
      数据同步中：L0 今日已入库 <b>{{ l0Today }}</b> 条，OLAP 宽表当前可见 <b>{{ totalMessages }}</b> 条。
      ETL 每分钟同步一次，稍后刷新即可对齐（查不到 ≠ 没写入）。
    </el-alert>

    <el-alert v-if="viewMode === 'turn' && systemTraceCount" type="info" :closable="false" class="amm-alert">
      另有 <b>{{ systemTraceCount }}</b> 条系统写入轨迹（memory_write）不属于问答轮，未计入对话列表；
      切到「按会话」可查看全部 <b>{{ totalMessages }}</b> 条消息。
    </el-alert>

    <div class="amm-summary">
      <div class="stat-card">
        <div class="stat-label">{{ viewMode === 'turn' ? '今日对话轮' : '今日会话' }}</div>
        <div class="stat-num">{{ groups.length }}<small> {{ viewMode === 'turn' ? '轮' : '个' }}</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日消息（OLAP 已同步）</div>
        <div class="stat-num">{{ totalMessages }}<small> 条</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">宿主会话数</div>
        <div class="stat-num">{{ sessionCount }}<small> 个</small></div>
      </div>
      <div class="stat-card">
        <div class="stat-label">最近活跃</div>
        <div class="stat-num stat-time">{{ groups.length ? formatTime(groups[0].lastTs) : '—' }}</div>
      </div>
    </div>

    <el-card shadow="never">
      <template #header>
        <div class="ov-card-head">
          <b>{{ viewMode === 'turn' ? '今日对话列表（每轮一问一答及其过程说明）' : '今日会话列表' }}</b>
          <span class="amm-hint">点击「查看内容」阅读本{{ viewMode === 'turn' ? '轮' : '会话' }}全部消息</span>
        </div>
      </template>

      <el-table v-if="viewMode === 'turn'" v-loading="loading" :data="groups" size="small" stripe>
        <el-table-column label="开始时间" width="105">
          <template #default="{ row }">{{ formatTime(row.firstTs) }}</template>
        </el-table-column>
        <el-table-column label="用户提问" min-width="300" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ 'amm-faint': !row.hasUser }">{{ row.question }}</span></template>
        </el-table-column>
        <el-table-column prop="count" label="消息数" width="90" align="center" />
        <el-table-column label="会话" width="120">
          <template #default="{ row }"><span class="amm-mono">{{ row.sessionId.slice(0, 8) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="open(row)">查看内容</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="今日暂无对话消息（新消息经 ETL 同步后展示）" :image-size="60" />
        </template>
      </el-table>

      <el-table v-else v-loading="loading" :data="groups" size="small" stripe>
        <el-table-column label="会话 ID" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span class="amm-mono">{{ row.sessionId }}</span></template>
        </el-table-column>
        <el-table-column prop="count" label="消息数" width="90" align="center" />
        <el-table-column label="首条" width="105">
          <template #default="{ row }">{{ formatTime(row.firstTs) }}</template>
        </el-table-column>
        <el-table-column label="最后" width="105">
          <template #default="{ row }">{{ formatTime(row.lastTs) }}</template>
        </el-table-column>
        <el-table-column label="最新内容" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" :type="roleType(row.role)" effect="plain" class="amm-role">{{ row.role }}</el-tag>
            <span>{{ row.summary }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="open(row)">查看内容</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="今日暂无会话消息（新消息经 ETL 同步后展示）" :image-size="60" />
        </template>
      </el-table>
    </el-card>

    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="640px">
      <template v-if="current">
        <el-descriptions :column="2" size="small" border class="amm-desc">
          <el-descriptions-item label="消息数">{{ current.count }} 条</el-descriptions-item>
          <el-descriptions-item label="时间范围">{{ formatTime(current.firstTs) }} ~ {{ formatTime(current.lastTs) }}</el-descriptions-item>
          <el-descriptions-item label="会话 ID" :span="2"><span class="amm-mono">{{ current.sessionId }}</span></el-descriptions-item>
        </el-descriptions>
        <div class="amm-preview-title">消息内容（按时间正序，共 {{ current.messages.length }} 条）</div>
        <el-timeline>
          <el-timeline-item
            v-for="(m, i) in current.messages"
            :key="i"
            :type="roleType(m.role)"
            :timestamp="formatTs(m.ts)"
            placement="top"
          >
            <el-tag size="small" :type="roleType(m.role)" effect="plain">{{ m.role }}</el-tag>
            <span v-if="m.source" class="amm-source">{{ m.source }}</span>
            <div class="msg-content">{{ m.content }}</div>
            <div v-if="m.trace_id" class="msg-trace">trace: {{ m.trace_id }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>
  </section>
</template>

<script setup>
import '../styles/tokens.css'
import { ref, computed, onMounted } from 'vue'
import { Refresh, ArrowLeft } from '@element-plus/icons-vue'
import { getMessages, getArchStats } from '../api/memory'

defineEmits(['back'])

const PAGE_SIZE = 500
const MAX_PAGES = 20 // 上限保护：避免 OLAP 异常时无限翻页
const loading = ref(false)
const rowsCache = ref([])
const viewMode = ref('turn') // turn = 一轮问答（trace_id）；session = 宿主会话文件
const systemTraceCount = ref(0)
const totalMessages = ref(0)
const l0Today = ref(null)
const drawerVisible = ref(false)
const current = ref(null)

const dateLabel = computed(() => {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
})

const sessionCount = computed(() => new Set(rowsCache.value.map((r) => r.session_id || '—')).size)
const turnGroupCount = computed(() => aggregate(rowsCache.value, 'turn').length)
const groups = computed(() => aggregate(rowsCache.value, viewMode.value))
const drawerTitle = computed(() => {
  if (!current.value) return '消息内容'
  return viewMode.value === 'turn'
    ? `本轮对话 · ${formatTime(current.value.firstTs)} · ${current.value.count} 条消息`
    : `今日会话内容 · ${current.value.sessionId}`
})

function startOfToday() {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

function formatTime(ts) {
  if (!ts) return '—'
  const d = new Date(Number(ts))
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function formatTs(ts) {
  if (!ts) return ''
  const d = new Date(Number(ts))
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function roleType(role) {
  return { user: 'primary', assistant: 'success', tool: 'warning', system: 'info' }[role] || 'info'
}

function clean(text, limit) {
  return String(text || '').replace(/\s+/g, ' ').slice(0, limit)
}

/**
 * /analytics/messages 固定按 ts DESC 返回，因此遇到第一条早于今日 00:00 的记录即可停止翻页，
 * 无需拉全表。与后端 startOfToday() 一致，均取浏览器/服务本地时区的自然日。
 */
async function fetchTodayRows(from) {
  const rows = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const data = await getMessages({ page, size: PAGE_SIZE })
    const batch = (data && data.rows) || []
    if (!batch.length) break
    let reachedYesterday = false
    for (const r of batch) {
      const ts = Number(r.ts)
      if (!ts) continue // ts 缺失的行不参与判定，避免误触发早停
      if (ts < from) {
        reachedYesterday = true
        break
      }
      rows.push({ ...r, ts })
    }
    if (reachedYesterday || batch.length < PAGE_SIZE) break
  }
  return rows
}

/**
 * 两种粒度共用一套分组结构：
 *  - turn：key = trace_id（导入器写入时等于 conversationRequestId），一轮问答 = 一条记录。
 *    纯 system 的 memory_write 轨迹（trace-xxx）不属于问答，单独计数，不进列表。
 *  - session：key = session_id，保留最初的按宿主会话聚合视图。
 */
function aggregate(rows, mode) {
  const buckets = new Map()
  for (const r of rows) {
    const key = mode === 'turn'
      ? (r.trace_id || `orphan-${r.session_id || '—'}`)
      : (r.session_id || '—')
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key).push(r)
  }
  let systemOnly = 0
  const list = []
  for (const [key, msgs] of buckets) {
    const isDialogue = msgs.some((m) => m.role === 'user' || m.role === 'assistant')
    if (mode === 'turn' && !isDialogue) {
      systemOnly += msgs.length
      continue
    }
    msgs.sort((a, b) => a.ts - b.ts)
    const first = msgs[0]
    const last = msgs[msgs.length - 1]
    const userMsg = msgs.find((m) => m.role === 'user')
    list.push({
      key,
      sessionId: first.session_id || '—',
      count: msgs.length,
      firstTs: first.ts,
      lastTs: last.ts,
      role: last.role || '',
      summary: clean(last.content, 140),
      question: clean(userMsg ? userMsg.content : first.content, 160),
      hasUser: !!userMsg,
      messages: msgs
    })
  }
  if (mode === 'turn' && rows.length) systemTraceCount.value = systemOnly
  return list.sort((a, b) => b.lastTs - a.lastTs)
}

async function load() {
  loading.value = true
  try {
    const rows = await fetchTodayRows(startOfToday())
    rowsCache.value = rows
    totalMessages.value = rows.length
  } finally {
    loading.value = false
  }
  // L0 口径用于提示 ETL 延迟；失败不影响主列表
  try {
    const stats = await getArchStats()
    l0Today.value = Number(stats?.todayDialogues ?? 0)
  } catch {
    l0Today.value = null
  }
}

function open(row) {
  current.value = row
  drawerVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.amm-today .page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
.head-title h1 { font-size: 20px; font-weight: 600; margin: 0 0 4px; }
.amm-sub { font-size: 12px; color: var(--amm-ink-subtle); }
.amm-sub b { color: var(--amm-ink); }
.head-actions { display: flex; gap: 8px; align-items: center; }
.amm-mode { display: flex; }
.amm-mode .el-button + .el-button { margin-left: 0; }
.amm-alert { margin-bottom: 14px; }
.amm-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 14px; }
.amm-summary .stat-card {
  background: var(--amm-surface-1);
  border: 1px solid var(--amm-hairline);
  border-radius: var(--amm-radius-lg);
  padding: 14px 18px;
}
.amm-summary .stat-label { font-size: 12px; color: var(--amm-ink-subtle); }
.amm-summary .stat-num {
  margin-top: 6px; font-size: 22px; font-weight: 600;
  font-family: var(--amm-font-mono); color: var(--amm-ink);
}
.amm-summary .stat-num small { font-size: 12px; font-weight: 400; color: var(--amm-ink-subtle); }
.amm-summary .stat-time { font-size: 18px; }
.ov-card-head { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.amm-hint { font-size: 12px; color: var(--amm-ink-faint); }
.amm-mono { font-family: var(--amm-font-mono); font-size: 12px; }
.amm-faint { color: var(--amm-ink-faint); }
.amm-role { margin-right: 6px; }
.amm-desc { margin-bottom: 16px; }
.amm-preview-title { font-size: 13px; font-weight: 600; margin-bottom: 14px; }
.amm-source { margin-left: 8px; font-size: 11px; color: var(--amm-ink-faint); font-family: var(--amm-font-mono); }
.msg-content {
  margin-top: 6px; font-size: 13px; line-height: 1.6;
  background: var(--amm-canvas-soft); border-radius: 6px;
  padding: 8px 10px; white-space: pre-wrap; word-break: break-word;
}
.msg-trace { margin-top: 4px; font-size: 11px; color: var(--amm-ink-faint); font-family: var(--amm-font-mono); }
@media (max-width: 768px) {
  .amm-summary { grid-template-columns: repeat(2, 1fr); }
  .amm-today .page-head { flex-direction: column; align-items: flex-start; gap: 10px; }
}
</style>
