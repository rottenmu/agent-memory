<template>
  <section class="amm-page">
    <div class="page-head">
      <div class="head-title">
        <h1>溯源分析 · Trajectory</h1>
              </div>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="traceId" placeholder="输入 traceId，如 trace-1751b03ab801" clearable class="w-320" @keyup.enter="load">
          <template #prepend>traceId</template>
        </el-input>
        <el-button type="primary" :icon="Search" @click="load" :loading="loading">检索事件链</el-button>
      </div>

      <!-- 来源筛选（Trajectory：按来源查看模型看到的一切） -->
      <div v-if="list.length" class="traj-filter">
        <button
          v-for="s in sourceOptions"
          :key="s.value"
          class="traj-chip"
          :class="{ active: sourceFilter === s.value }"
          @click="sourceFilter = s.value"
        >{{ s.label }}</button>
      </div>

      <el-timeline v-if="filtered.length" class="mt-14">
        <el-timeline-item
          v-for="(e, i) in filtered"
          :key="i"
          :type="typeOf(e.role)"
          :timestamp="formatTs(e.ts)"
          placement="top"
        >
          <div class="tl-head">
            <el-tag size="small" :type="typeOf(e.role)" effect="plain">{{ e.role }}</el-tag>
            <span class="tl-source" :class="'src-' + srcKey(e.source)">{{ srcLabel(e.source) }}</span>
            <span class="tl-token" v-if="e.tokens != null">{{ e.tokens }} tokens</span>
          </div>
          <div class="tl-content" :class="{ expanded: expanded === e.ts }" @click="expanded = expanded === e.ts ? null : e.ts">
            {{ e.content }}
          </div>
        </el-timeline-item>
      </el-timeline>

      <el-empty v-else-if="!loading" description="输入 traceId 查看该次交互的 L0 原始事件链" />
    </el-card>
  </section>
</template>

<script setup>
import '../styles/tokens.css'
import { ref, watch, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { getTraceEvents } from '../api/memory'

const props = defineProps({
  /** 父级（Tab 容器）传入的 traceId，变化时自动检索。 */
  traceId: { type: String, default: '' }
})
const route = useRoute()
const traceId = ref('')
const list = ref([])
const loading = ref(false)
const sourceFilter = ref('')
const expanded = ref(null)

/** Trajectory 来源分类（与后端 L0RawLog.source 一致）。 */
const sourceOptions = [
  { value: '', label: '全部' },
  { value: 'user_message', label: '用户消息' },
  { value: 'assistant_message', label: '助手回复' },
  { value: 'system_prompt', label: '系统提示词' },
  { value: 'chain_of_thought', label: '思维链' },
  { value: 'tool_call', label: '工具调用' },
  { value: 'tool_result', label: '工具结果' },
  { value: 'sub_agent', label: '子 Agent' },
  { value: 'context_injection', label: '上下文注入' },
  { value: 'system_event', label: '系统事件' }
]

const SOURCE_LABELS = {
  user_message: '用户消息',
  assistant_message: '助手回复',
  system_prompt: '系统提示词',
  chain_of_thought: '思维链',
  tool_call: '工具调用',
  tool_result: '工具结果',
  sub_agent: '子 Agent',
  context_injection: '上下文注入',
  system_event: '系统事件'
}

function srcLabel(source) {
  return SOURCE_LABELS[source] || '消息'
}

function srcKey(source) {
  return SOURCE_LABELS[source] ? source : 'unknown'
}

/** 按来源过滤后的事件链。 */
const filtered = computed(() => {
  if (!sourceFilter.value) return list.value
  return list.value.filter((e) => e.source === sourceFilter.value)
})

function typeOf(role) {
  return { user: 'primary', assistant: 'success', tool: 'warning', system: 'info' }[role] || 'info'
}

function formatTs(ts) {
  if (!ts) return ''
  const d = new Date(Number(ts))
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

async function load() {
  if (!traceId.value) return
  loading.value = true
  sourceFilter.value = ''
  expanded.value = null
  try {
    list.value = await getTraceEvents(traceId.value)
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.traceId) {
    traceId.value = props.traceId
    load()
  } else if (route.query.traceId) {
    traceId.value = route.query.traceId
    load()
  }
})

watch(
  () => props.traceId,
  (val) => {
    if (val) {
      traceId.value = val
      load()
    }
  }
)
</script>

<style scoped>
.page-head { margin-bottom: 20px; }
.head-title h1 { font-size: 20px; font-weight: 600; letter-spacing: -0.2px; margin: 0 0 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.w-320 { width: 320px; max-width: 100%; }
.mt-14 { margin-top: 14px; }
/* 来源筛选 chips（DESIGN：pill 风格） */
.traj-filter { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
.traj-chip {
  border: 1px solid var(--amm-hairline); background: #fff;
  border-radius: 9999px; padding: 5px 12px;
  font: 400 12px/1.20 var(--amm-font-sans); color: var(--amm-ink-muted);
  cursor: pointer; transition: all 150ms ease;
}
.traj-chip:hover { border-color: var(--amm-hairline-strong); color: var(--amm-ink); }
.traj-chip.active {
  background: var(--amm-primary-soft); border-color: var(--amm-primary);
  color: var(--amm-primary); font-weight: 500;
}
/* 来源徽标（mono，语义色） */
.tl-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tl-source {
  font-family: var(--amm-font-mono); font-size: 11px; letter-spacing: 0;
  border-radius: 9999px; padding: 2px 8px; background: var(--amm-canvas-soft-2); color: var(--amm-ink-muted);
}
.src-user_message { background: var(--amm-primary-soft); color: var(--amm-primary); }
.src-assistant_message { background: var(--amm-success-soft); color: var(--amm-success); }
.src-system_prompt { background: var(--amm-warning-soft); color: var(--amm-warning); }
.src-tool_call { background: var(--amm-warning-soft); color: var(--amm-warning); }
.src-tool_result { background: var(--amm-success-soft); color: var(--amm-success); }
.src-sub_agent { background: var(--amm-primary-soft); color: var(--amm-primary); }
.src-context_injection { background: var(--amm-info-soft); color: var(--amm-info); }
.tl-token { font-size: 12px; color: var(--amm-ink-subtle); font-family: var(--amm-font-mono); }
.tl-content {
  margin-top: 6px; font-size: 13px; color: var(--amm-ink);
  background: var(--amm-canvas-soft); border-radius: 6px; padding: 8px 10px; line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
  cursor: pointer; transition: background 150ms ease;
}
.tl-content:hover { background: var(--amm-canvas-soft-2); }
.tl-content.expanded { -webkit-line-clamp: unset; overflow: visible; }
</style>
