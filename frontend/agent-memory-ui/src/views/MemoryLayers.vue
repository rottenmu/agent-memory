<template>
  <section class="amm-page layer-page">
    <div class="page-head">
      <div>
        <h1>数据分层</h1>
        <p class="sub">L0 ~ L3 四层记忆金字塔 · OLTP / OLAP 双存储 · 分钟级 ETL</p>
      </div>
    </div>

    <!-- 四层金字塔：自下而上逐层抽象，宽度递减表示收敛 -->
    <div class="pyramid" aria-label="四层记忆金字塔">
      <div
        v-for="(l, i) in layers"
        :key="l.code"
        class="layer-row"
        :class="{ reserved: l.reserved }"
        :style="{ width: 100 - i * 8 + '%' }"
      >
        <div class="layer-badge">{{ l.code }}</div>
        <div class="layer-body">
          <div class="layer-title">
            <b>{{ l.name }}</b>
            <span class="amm-mono">{{ l.model }}</span>
            <el-tag size="small" :type="l.reserved ? 'warning' : 'success'" effect="plain">
              {{ l.status }}
            </el-tag>
          </div>
          <p class="layer-desc">{{ l.desc }}</p>
          <div class="layer-meta">
            <span>存储 <b>{{ l.storage }}</b></span>
            <span>写入 <b>{{ l.writer }}</b></span>
            <span>读取 <b>{{ l.reader }}</b></span>
          </div>
        </div>
        <el-button size="small" plain @click="emit('navigate', l.tab)">{{ l.action }} →</el-button>
      </div>
    </div>

    <!-- 存储与流转 -->
    <el-card shadow="never" class="flow-card">
      <template #header><b>写入 → OLTP → ETL → OLAP → 读取</b></template>
      <div class="flow">
        <template v-for="(n, i) in flow" :key="n.k">
          <div class="flow-node">
            <span class="flow-k">{{ n.k }}</span>
            <b class="flow-t">{{ n.t }}</b>
            <span class="flow-d">{{ n.d }}</span>
          </div>
          <span v-if="i < flow.length - 1" class="flow-arrow" aria-hidden="true">→</span>
        </template>
      </div>
    </el-card>

    <!-- 边界与现状 -->
    <el-card shadow="never" class="notes-card">
      <template #header><b>架构边界（照实现，不照命名）</b></template>
      <ul class="notes">
        <li v-for="n in notes" :key="n">
          <span class="dot" aria-hidden="true"></span>{{ n }}
        </li>
      </ul>
    </el-card>
  </section>
</template>

<script setup>
import '../styles/tokens.css'

const emit = defineEmits(['navigate'])

const layers = [
  {
    code: 'L0', name: '原始日志', model: 'L0RawLog', tab: 'trace', action: '查看溯源',
    status: '已落地', reserved: false,
    desc: '原始对话与记忆写入的全量留痕，追加式（append-only），自增 id + traceId / sessionId 索引，合规审计用。',
    storage: 'OLTP l0_raw_log → OLAP olap_l0_log',
    writer: 'TrajectoryRecorder / AiMemoryService',
    reader: 'ETL / 溯源 / 会话消息 / 蒸馏质量'
  },
  {
    code: 'L1', name: '原子记忆', model: 'L1AtomicMemory', tab: 'user', action: '查看原子记忆',
    status: '已落地', reserved: false,
    desc: '单条事实 key=value，含类别、会话归属与 embedding；每轮按 minScore 阈值召回注入系统提示词。',
    storage: 'OLTP l1_atomic_memory',
    writer: 'AiMemoryService（session / user / global）',
    reader: 'MemoryAwarePromptBuilder.recallFragment'
  },
  {
    code: 'L2', name: '场景块', model: 'L2SceneBlock', tab: 'scene', action: '查看说明',
    status: '结构预留', reserved: true,
    desc: '会话级场景聚合。当前仅有表与模型：saveSceneBlock 零调用，无 read_scenario 工具，也无 REST 端点。',
    storage: 'OLTP l2_scene_block（空）',
    writer: '—（暂无生产者）',
    reader: '—（暂无消费者）'
  },
  {
    code: 'L3', name: '用户画像', model: 'L3Persona', tab: 'persona', action: '查看画像',
    status: '已落地', reserved: false,
    desc: '用户长期画像，version 乐观更新；persona / preference / habit 注入系统提示词，history 刻意不注入。',
    storage: 'OLTP l3_persona',
    writer: 'AiMemoryService（category=persona / preference）',
    reader: 'PersonaFragmentComposer → MemoryPromptMiddleware'
  }
]

const flow = [
  { k: '写', t: 'AiMemoryService', d: '脱敏 → 白名单 → 落库' },
  { k: 'OLTP', t: 'H2 MVStore', d: 'l0 / l1 / l2 / l3 实时读写' },
  { k: 'ETL', t: 'AsyncLogSyncTask', d: '自增 id 游标 · 500/批 · 每分钟' },
  { k: 'OLAP', t: 'Arrow IPC + Calcite', d: 'olap_l0_log 内存宽表' },
  { k: '读', t: '分析视图', d: '溯源 / 会话统计 / 消息 / SQL' }
]

const notes = [
  'L0 为追加日志，全仓无删除与 prune 路径，永删除属设计约束而非遗漏。',
  'OLAP 分析副本最多落后 1 分钟；ETL 失败不影响主链路，cursor < 0 时全量重建。',
  'L2 场景块只是结构预留，前端如实标注，不用假数据填充。',
  'L1 / L2 / L3 在 REST API 上并不隔离：用户记忆列表由 L1 + L3 两表合并返回，消费方需自行按 layer / category 确认层次。',
  '画像先算、L1 后算，拼接顺序为「系统提示词 + 画像 + L1 片段」——稳定内容必须在前，否则前缀缓存失效。'
]
</script>

<style scoped>
.layer-page { padding: 4px 2px 24px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 18px; }
.sub { margin: 6px 0 0; font-size: 12px; color: var(--amm-ink-subtle); }

.pyramid { display: flex; flex-direction: column; align-items: center; gap: 8px; margin-bottom: 18px; }
.layer-row {
  display: flex; align-items: flex-start; gap: 14px;
  padding: 14px 16px; border: 1px solid var(--amm-hairline);
  border-radius: var(--amm-radius-lg); background: var(--amm-canvas);
  box-shadow: var(--amm-shadow-xs); min-width: 0;
}
.layer-row.reserved { background: var(--amm-canvas-soft); border-style: dashed; }
.layer-badge {
  flex-shrink: 0; width: 38px; height: 38px; border-radius: var(--amm-radius-md);
  display: flex; align-items: center; justify-content: center;
  background: var(--amm-primary-soft); color: var(--amm-primary-active);
  font-family: var(--amm-font-mono); font-size: 14px; font-weight: 600;
}
.layer-row.reserved .layer-badge { background: var(--amm-warning-soft); color: #9A6600; }
.layer-body { flex: 1; min-width: 0; }
.layer-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.layer-title b { font-size: 15px; font-weight: 600; letter-spacing: -0.1px; }
.layer-desc { margin: 6px 0 8px; font-size: 13px; color: var(--amm-ink-muted); line-height: 1.6; }
.layer-meta { display: flex; gap: 18px; flex-wrap: wrap; font-size: 11px; color: var(--amm-ink-subtle); }
.layer-meta b { font-weight: 500; color: var(--amm-ink-muted); font-family: var(--amm-font-mono); }
.layer-row > :last-child { flex-shrink: 0; align-self: center; }

.flow-card, .notes-card { margin-bottom: 14px; }
.flow { display: flex; align-items: stretch; gap: 6px; flex-wrap: wrap; }
.flow-node {
  flex: 1 1 150px; min-width: 0; padding: 10px 12px;
  background: var(--amm-canvas-soft); border: 1px solid var(--amm-hairline);
  border-radius: var(--amm-radius-md); display: flex; flex-direction: column; gap: 4px;
}
.flow-k { font-size: 10px; letter-spacing: 0.6px; text-transform: uppercase; color: var(--amm-primary); font-weight: 600; }
.flow-t { font-size: 13px; font-family: var(--amm-font-mono); }
.flow-d { font-size: 11px; color: var(--amm-ink-subtle); line-height: 1.5; }
.flow-arrow { align-self: center; color: var(--amm-ink-faint); font-size: 15px; }

.notes { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 9px; }
.notes li { display: flex; gap: 9px; font-size: 12px; color: var(--amm-ink-muted); line-height: 1.6; }
.dot { flex-shrink: 0; width: 5px; height: 5px; margin-top: 7px; border-radius: 50%; background: var(--amm-primary); }

@media (max-width: 900px) {
  .layer-row { width: 100% !important; flex-wrap: wrap; }
  .layer-row > :last-child { align-self: flex-start; }
  .layer-meta { flex-direction: column; gap: 4px; }
}
</style>
