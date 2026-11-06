<template>
  <section class="amm-page amm-dsh">
    <!-- dsh 式左侧导航 -->
    <aside class="dsh-sidebar">
      <div class="dsh-brand">
        <span class="dsh-brand-mark">记</span>
        <span class="dsh-brand-text">Agent Memory</span>
      </div>
      <nav class="dsh-nav">
        <section v-for="section in navSections" :key="section.label" class="dsh-nav-section">
          <button
            v-if="section.tab"
            class="dsh-nav-section-head"
            :class="{ active: mainTab === section.tab }"
            @click="goTab(section.tab)"
          >
            <el-icon :size="16"><component :is="section.icon" /></el-icon>
            <span>{{ section.label }}</span>
          </button>
          <div v-else class="dsh-nav-group-label dsh-nav-section-title">{{ section.label }}</div>
          <div v-for="(group, gi) in section.groups" :key="group.label || gi" class="dsh-nav-group">
            <div v-if="group.label" class="dsh-nav-group-label dsh-nav-sub-label">{{ group.label }}</div>
            <button
              v-for="item in group.items"
              :key="item.key"
              class="dsh-nav-item"
              :class="{ 'dsh-nav-sub-item': !!section.tab, active: mainTab === item.key }"
              @click="goTab(item.key)"
            >
              <el-icon :size="16"><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </button>
          </div>
        </section>
      </nav>
      <div class="dsh-sidebar-foot">v1.0 · L0~L3 四层记忆</div>
    </aside>

    <!-- 主内容区 -->
    <main class="dsh-main">
      <div v-show="mainTab === 'overview'" class="dsh-pane">
      <div class="page-head">
        <div class="head-title">
          <h1>消息记录</h1>
              </div>
      <el-button type="primary" plain :icon="Refresh" @click="refreshAll">刷新</el-button>
    </div>
    <el-tabs v-model="activeTab" class="amm-tabs">
      <el-tab-pane label="会话消息" name="messages">
        <div class="arch-stats">
          <div
            class="stat-card stat-soul stat-card-link"
            role="button"
            tabindex="0"
            aria-label="查看 SOUL 配置列表"
            @click="goArch('SOUL')"
            @keydown.enter.prevent="goArch('SOUL')"
          >
            <div class="stat-icon"><el-icon><UserFilled /></el-icon></div>
            <div class="stat-num">{{ formatNum(archStats.soulConfigCount) }}</div>
            <div class="stat-label">SOUL 配置 <span class="stat-link-hint">查看列表 →</span></div>
          </div>
          <div
            class="stat-card stat-user stat-card-link"
            role="button"
            tabindex="0"
            aria-label="查看 USER 档案列表"
            @click="goArch('USER')"
            @keydown.enter.prevent="goArch('USER')"
          >
            <div class="stat-icon"><el-icon><User /></el-icon></div>
            <div class="stat-num">{{ formatNum(archStats.userProfileCount) }}</div>
            <div class="stat-label">USER 档案 <span class="stat-link-hint">查看列表 →</span></div>
          </div>
          <div
            class="stat-card stat-today stat-today-link"
            role="button"
            tabindex="0"
            @click="goToday()"
            @keydown.enter.prevent="goToday()"
          >
            <div class="stat-icon"><el-icon><ChatDotRound /></el-icon></div>
            <div class="stat-num">{{ formatNum(archStats.todayDialogues) }}</div>
            <div class="stat-label" title="统计口径：今日 L0 消息条数，不是会话数或对话轮数">今日消息 <span class="stat-link-hint">查看对话 →</span></div>
          </div>
          <div class="stat-card stat-total">
            <div class="stat-icon"><el-icon><DataAnalysis /></el-icon></div>
            <div class="stat-num">{{ formatNum(archStats.totalDialogues) }}</div>
            <div class="stat-label">累计对话</div>
          </div>
          <div
            class="stat-card stat-extract stat-card-link"
            role="button"
            tabindex="0"
            aria-label="查看已提取列表"
            @click="goArch('EXTRACTED')"
            @keydown.enter.prevent="goArch('EXTRACTED')"
          >
            <div class="stat-icon"><el-icon><MagicStick /></el-icon></div>
            <div class="stat-num">{{ archStats.extracted || 0 }}</div>
            <div class="stat-label">已提取 <span class="stat-link-hint">查看列表 →</span></div>
          </div>
        </div>
        <!-- 列表（含工具栏 / 分页 / 新建编辑提取删除）已抽到公共组件，独立页复用同一份 -->
        <ArchConfigTable ref="archTableRef" @changed="loadArchStats" />
      </el-tab-pane>
    </el-tabs>

      </div>
      <div v-show="mainTab === 'arch'" class="dsh-pane">
        <ArchConfigs :active="mainTab === 'arch'" @back="backFromArch" />
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
      <div v-show="mainTab === 'layers'" class="dsh-pane">
        <MemoryLayers @navigate="goTab" />
      </div>
      <div v-show="mainTab === 'scene'" class="dsh-pane">
        <SceneBlockView @navigate="goTab" />
      </div>
      <div v-show="mainTab === 'persona'" class="dsh-pane">
        <PersonaView />
      </div>
      <div v-show="mainTab === 'today'" class="dsh-pane">
        <TodaySessions @back="backFromToday" />
      </div>
    </main>

  </section>
</template>

<script setup>
import '../styles/tokens.css'
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh, UserFilled, User, ChatDotRound, DataAnalysis, MagicStick, Collection, Connection, Key } from '@element-plus/icons-vue'
import ArchConfigTable from '../components/ArchConfigTable.vue'
import UserMemoryManage from './UserMemoryManage.vue'
import SessionMemoryManage from './SessionMemoryManage.vue'
import GlobalMemoryManage from './GlobalMemoryManage.vue'
import TraceAnalysis from './TraceAnalysis.vue'
import MemoryPolicy from './MemoryPolicy.vue'
import MemoryLayers from './MemoryLayers.vue'
import SceneBlockView from './SceneBlockView.vue'
import PersonaView from './PersonaView.vue'
import TodaySessions from './TodaySessions.vue'
import ArchConfigs from './ArchConfigs.vue'
import { getArchStats } from '../api/memory'

const route = useRoute()
const router = useRouter()

/* ============ 主 Tab（消息记录页收纳全部子页） ============ */
const mainTab = ref('overview')
const traceIdParam = ref('')

/* dsh 式左侧导航：一级菜单「消息记录」收纳 L0~L3 全部页面；「辅助」独立成组。
   一级标题本身可点击回到消息记录页（tab=overview），其下按分层缩进展示子项。 */
const navSections = [
  {
    label: '消息记录', tab: 'overview', icon: ChatDotRound,
    groups: [
      {
        label: '', items: [
          { key: 'layers', label: '数据分层', icon: MagicStick }
        ]
      },
      {
        label: 'L0 · 原始日志', items: [
          { key: 'today', label: '今日会话', icon: ChatDotRound },
          { key: 'trace', label: '溯源分析', icon: Connection }
        ]
      },
      {
        label: 'L1 · 原子记忆', items: [
          { key: 'user', label: '用户记忆', icon: User },
          { key: 'session', label: '会话记忆', icon: ChatDotRound },
          { key: 'global', label: '全局记忆', icon: Collection }
        ]
      },
      {
        label: 'L2 · 场景块', items: [
          { key: 'scene', label: '场景块（预留）', icon: Collection }
        ]
      },
      {
        label: 'L3 · 用户画像', items: [
          { key: 'persona', label: '用户画像', icon: UserFilled }
        ]
      }
    ]
  },
  {
    label: '辅助', tab: '',
    groups: [
      {
        label: '', items: [
          { key: 'policy', label: '记忆策略', icon: Key }
        ]
      }
    ]
  }
]

// 独立 URL 直达分层页（侧栏点击仍走 /agent-memory?tab=xxx）
const pathTabs = {
  '/agent-memory/layers': 'layers',
  '/agent-memory/scene': 'scene',
  '/agent-memory/persona': 'persona',
  '/agent-memory/today-sessions': 'today',
  '/agent-memory/arch-configs': 'arch'
}

watch(
  () => route.path,
  (p) => { if (pathTabs[p]) mainTab.value = pathTabs[p] }
)

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

const activeTab = ref('messages')


function goTab(tab, traceId) {
  const query = { ...route.query, tab }
  if (traceId) query.traceId = traceId
  router.push({ path: '/agent-memory', query })
}

/** 「今日对话」卡片：URL 直达今日会话列表，可收藏/刷新。 */
function goToday() {
  router.push({ path: '/agent-memory/today-sessions' })
}

/** 三张统计卡 → 画像配置列表页，筛选条件放 URL query（可收藏/刷新/回退）。 */
function goArch(kind) {
  const query = kind === 'EXTRACTED' ? { extracted: '1' }
    : kind === 'SOUL' ? { type: 'SOUL' }
      : { type: 'USER' }
  router.push({ path: '/agent-memory/arch-configs', query })
}

/** 返回时回到「消息记录 → 会话消息」Tab，保持点击前的上下文。 */
function backFromToday() {
  mainTab.value = 'overview'
  activeTab.value = 'messages'
  router.push({ path: '/agent-memory', query: { tab: 'overview' } })
}

/** 画像配置页返回：pathTabs 不含 '/agent-memory'，必须显式切回 overview。 */
const backFromArch = backFromToday



const archTableRef = ref(null)
const archStats = ref({})

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

/* 画像配置的工具栏 / 表格 / 分页 / 新建编辑提取删除已下沉到 ArchConfigTable.vue，
   本页只保留统计卡与刷新入口；独立画像配置页复用同一个组件。 */

/** 刷新按钮：重拉统计卡 + 列表公共组件（两者各发一个 /arch/* 请求）。 */
async function refreshAll() {
  await Promise.all([archTableRef.value?.reload(), loadArchStats()])
}

onMounted(() => {
  if (pathTabs[route.path]) mainTab.value = pathTabs[route.path]
  if (route.query.tab) mainTab.value = String(route.query.tab)
  if (route.query.traceId) traceIdParam.value = String(route.query.traceId)
  // 列表由 ArchConfigTable 的 onMounted 自行加载，这里只补统计卡，避免首屏重复请求
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
.dsh-sidebar { max-height: calc(100vh - 24px); overflow-y: auto; }
/* 一级菜单（消息记录 / 辅助）与其下的分层子组 */
.dsh-nav-section {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 10px;
}
.dsh-nav-section-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 10px;
  width: 100%;
  border: none;
  background: transparent;
  border-radius: var(--amm-radius-md);
  font-size: 13px;
  font-weight: 600;
  color: var(--amm-ink);
  text-align: left;
  cursor: pointer;
  transition: background 150ms ease, color 150ms ease;
}
.dsh-nav-section-head:hover { background: rgb(65 118 230 / 0.06); }
.dsh-nav-section-head.active { background: var(--amm-primary-soft); color: var(--amm-primary-active); }
.dsh-nav-section-title { color: var(--amm-ink-muted); font-weight: 600; padding: 7px 10px 3px; }
.dsh-nav-sub-label { padding-left: 20px; }
.dsh-nav-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 6px;
}
.dsh-nav-group-label {
  padding: 7px 12px 3px;
  font-size: 10px;
  letter-spacing: 0.6px;
  font-family: var(--amm-font-mono);
  color: var(--amm-ink-faint);
  white-space: nowrap;
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
.dsh-nav-sub-item { padding-left: 22px; font-size: 12.5px; }
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
  .dsh-nav-section { flex-direction: row; flex-wrap: wrap; align-items: center; gap: 4px; margin-bottom: 0; }
  .dsh-nav-section-head { width: auto; padding: 7px 10px; }
  .dsh-nav-group { flex-direction: row; flex-wrap: wrap; gap: 4px; margin-bottom: 0; }
  .dsh-nav-sub-label { padding-left: 0; }
  .dsh-nav-item { width: auto; padding: 7px 10px; }
  .dsh-nav-sub-item { padding-left: 10px; }
  .dsh-sidebar-foot { display: none; }
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.head-title h1 { font-size: 20px; font-weight: 600; letter-spacing: -0.2px; margin: 0 0 4px; }
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
.amm-tabs { margin-top: 6px; }
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
/* 可点击统计卡：SOUL / USER / 今日消息 / 已提取，均跳转到对应列表页 */
.stat-today-link, .stat-card-link { cursor: pointer; }
.stat-today-link:hover, .stat-card-link:hover { transform: translateY(-2px); box-shadow: var(--shadow-md, 0 4px 12px rgba(0, 0, 0, 0.1)); }
.stat-today-link:focus-visible, .stat-card-link:focus-visible { outline: 2px solid var(--amm-primary); outline-offset: 2px; }
.stat-link-hint { font-size: 11px; color: var(--amm-primary); margin-left: 4px; white-space: nowrap; }
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
