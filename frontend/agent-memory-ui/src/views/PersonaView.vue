<template>
  <section class="amm-page persona-page">
    <div class="page-head">
      <div>
        <h1><span class="code">L3</span> 用户画像</h1>
        <p class="sub">L3Persona · version 乐观更新 · 注入系统提示词</p>
      </div>
      <div class="head-actions">
        <el-input v-model="userId" placeholder="用户 ID" clearable style="width: 180px" @keyup.enter="load" />
        <el-button type="primary" plain :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="stats">
      <div class="stat"><span>画像条数</span><b>{{ rows.length }}</b></div>
      <div class="stat"><span>persona</span><b>{{ countOf('persona') }}</b></div>
      <div class="stat"><span>preference</span><b>{{ countOf('preference') }}</b></div>
      <div class="stat"><span>平均重要度</span><b>{{ avgImportance }}</b></div>
    </div>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <b>画像列表</b>
          <span class="amm-mono">GET /api/ai/memory/user/{userId} · L1 + L3 两表合并返回</span>
        </div>
      </template>
      <el-table v-loading="loading" :data="rows" size="small" stripe empty-text="该用户暂无画像（category=persona / preference 时写入 L3）">
        <el-table-column label="类别" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.category === 'persona' ? 'primary' : 'success'">{{ catLabel(row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="画像内容" min-width="240" show-overflow-tooltip />
        <el-table-column label="层" width="70" align="center">
          <template #default="{ row }"><span class="amm-mono">{{ layerLabel(row) }}</span></template>
        </el-table-column>
        <el-table-column label="版本" width="70" align="center">
          <template #default="{ row }"><span class="amm-mono">{{ row.version ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="重要度" width="90" align="center">
          <template #default="{ row }"><span class="amm-mono">{{ fmt(row.importance) }}</span></template>
        </el-table-column>
        <el-table-column label="访问" width="80" align="center">
          <template #default="{ row }"><span class="amm-mono">{{ row.accessCount ?? 0 }}</span></template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }"><span class="amm-mono">{{ row.timestamp || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="脱敏" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.sensitiveMasked" size="small" type="danger">已脱敏</el-tag>
            <span v-else class="amm-mono">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-row :gutter="14" class="notes-row">
      <el-col :xs="24" :sm="12">
        <el-card shadow="never" class="full-h">
          <template #header><b>写入与读取</b></template>
          <ul class="notes">
            <li>写入：<span class="amm-mono">saveUserMemory</span> 在 category 为 persona / preference 时落到 <b>L3Persona</b>，其余类别落到 L1。</li>
            <li>列表：<span class="amm-mono">pageUserMemory</span> 合并 L1 + L3 两表，本页按 category / layer 过滤出画像，不新增后端端点。</li>
            <li>读取：<span class="amm-mono">PersonaFragmentComposer</span> 逐类取画像、单行化去重、超 maxChars 截断，再由 <span class="amm-mono">MemoryPromptMiddleware</span> 注入。</li>
          </ul>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12">
        <el-card shadow="never" class="full-h">
          <template #header><b>注入策略</b></template>
          <ul class="notes">
            <li>默认注入类型：<span class="amm-mono">persona / preference / habit</span>，单条上限 2000 字符。</li>
            <li><b>刻意不注入 history</b>：那是任务流水（L2 范畴），放进稳定画像会让模型每轮拖着已完成任务的残影。</li>
            <li>拼接顺序：系统提示词 + 画像 + L1 片段。稳定内容必须在前，否则 L1 一变，画像一起掉出可缓存前缀。</li>
          </ul>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      class="tip"
      type="info"
      :closable="false"
      show-icon
      title="关于 /analytics/personas 接口"
      description="api/memory.js 中 listPersonas / extractPersona / deletePersona 三个方法在当前后端没有对应端点（调用会 404），本页不使用它们；画像数据走上面这条已合并的 user 列表接口。"
    />
  </section>
</template>

<script setup>
import '../styles/tokens.css'
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getUserPersonas, recordsOf } from '../api/memory'

const userId = ref('admin')
const rows = ref([])
const loading = ref(false)

function catLabel(v) {
  return { persona: '画像', preference: '偏好', habit: '习惯', history: '历史', custom: '自定义' }[v] || v || '—'
}

function countOf(cat) {
  return rows.value.filter((r) => String(r.category || '').toLowerCase() === cat).length
}

/** UNION 返回的 layer 是小写 'l3' / 'l1'（见 UserMemoryRow.LAYER_L3），统一成大写展示。 */
function layerLabel(r) {
  const v = String(r.layer || '').trim()
  return v ? v.toUpperCase() : 'L3'
}

function fmt(v) {
  if (v == null || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : String(v)
}

const avgImportance = ref('—')

/** 画像类别：与 PersonaInjection.DEFAULT_TYPES 一致（persona / preference / habit）。 */
const PERSONA_CATEGORIES = ['persona', 'preference', 'habit']

/**
 * 列表接口是「l3_persona UNION ALL l1_atomic_memory」，layer 字段为小写
 * 'l3' / 'l1'（见 H2UserMemoryPageDao.L3_SELECT）。原实现用大写 'L3' 比较，
 * 该条件恒为 false，只能靠 category 兜底 —— 遇到 habit 类别的画像就会漏行。
 */
function isPersonaRow(r) {
  const category = String(r.category || '').toLowerCase()
  const layer = String(r.layer || '').toLowerCase()
  return PERSONA_CATEGORIES.includes(category) || layer === 'l3'
}

/** 列表接口合并 L1 + L3，按 category / layer 过滤出画像层数据。 */
async function load() {
  if (!userId.value.trim()) return
  loading.value = true
  try {
    const all = recordsOf(await getUserPersonas(userId.value.trim()))
    rows.value = all.filter(isPersonaRow)
    const scores = rows.value.map((r) => Number(r.importance)).filter((n) => Number.isFinite(n))
    avgImportance.value = scores.length
      ? (scores.reduce((a, b) => a + b, 0) / scores.length).toFixed(2)
      : '—'
  } catch {
    rows.value = []
    avgImportance.value = '—'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.persona-page { padding: 4px 2px 24px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.page-head h1 { display: flex; align-items: center; gap: 10px; }
.code {
  font-family: var(--amm-font-mono); font-size: 15px; font-weight: 600;
  color: var(--amm-primary); background: var(--amm-primary-soft);
  padding: 2px 8px; border-radius: var(--amm-radius-sm);
}
.sub { margin: 6px 0 0; font-size: 12px; color: var(--amm-ink-subtle); }
.head-actions { display: flex; gap: 8px; align-items: center; }
.stats { display: flex; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.stat {
  flex: 1 1 130px; padding: 12px 14px; background: var(--amm-canvas);
  border: 1px solid var(--amm-hairline); border-radius: var(--amm-radius-lg);
  display: flex; flex-direction: column; gap: 4px;
}
.stat span { font-size: 11px; color: var(--amm-ink-subtle); }
.stat b { font-size: 20px; font-weight: 600; font-family: var(--amm-font-mono); }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.notes-row { margin-top: 14px; }
.full-h { height: 100%; }
.notes { margin: 0; padding-left: 18px; font-size: 12px; color: var(--amm-ink-muted); line-height: 1.9; }
.notes b { color: var(--amm-ink); }
.tip { margin-top: 14px; }
</style>
