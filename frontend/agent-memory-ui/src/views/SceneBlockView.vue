<template>
  <section class="amm-page scene-page">
    <div class="page-head">
      <div>
        <h1><span class="code">L2</span> 场景块</h1>
        <p class="sub">L2SceneBlock · 会话级场景聚合</p>
      </div>
      <el-tag type="warning" effect="plain" size="small">结构预留</el-tag>
    </div>

    <el-alert
      class="gap-alert"
      type="warning"
      :closable="false"
      show-icon
      title="这一层目前没有数据，也没有生产者与消费者"
      description="页面如实呈现架构现状，不用假数据填充。表与模型已存在，但写入、召回、REST 端点均未接通。"
    />

    <el-card shadow="never">
      <template #header><b>实现现状</b></template>
      <div class="facts">
        <div v-for="f in facts" :key="f.k" class="fact">
          <span class="fact-k">{{ f.k }}</span>
          <span class="fact-v" :class="{ off: f.off }">{{ f.v }}</span>
        </div>
      </div>
    </el-card>

    <el-row :gutter="14" class="dual">
      <el-col :xs="24" :sm="12">
        <el-card shadow="never" class="full-h">
          <template #header><b>设计意图</b></template>
          <p class="txt">{{ intent }}</p>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12">
        <el-card shadow="never" class="full-h">
          <template #header><b>启用前置条件</b></template>
          <ul class="todo">
            <li v-for="t in todos" :key="t">{{ t }}</li>
          </ul>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="empty-card">
      <el-empty :image-size="72" description="l2_scene_block 暂无场景块">
        <div class="empty-hint">
          场景聚类落地前，会话级上下文仍由 L0 原始日志与 L1 原子记忆承担。
        </div>
        <div class="empty-actions">
          <el-button size="small" plain @click="emit('navigate', 'trace')">看 L0 原始日志</el-button>
          <el-button size="small" plain @click="emit('navigate', 'user')">看 L1 原子记忆</el-button>
          <el-button size="small" plain @click="emit('navigate', 'layers')">回到数据分层</el-button>
        </div>
      </el-empty>
    </el-card>
  </section>
</template>

<script setup>
import '../styles/tokens.css'

const emit = defineEmits(['navigate'])

const facts = [
  { k: '模型', v: 'L2SceneBlock（com.zimo.agent.memory.model）' },
  { k: '表', v: 'l2_scene_block（OLTP H2，已建表）' },
  { k: '生产者', v: '无 —— OltpMemoryRepository.saveSceneBlock 零调用', off: true },
  { k: '消费者', v: '无 —— 没有 read_scenario 工具或召回链路', off: true },
  { k: 'REST 端点', v: '无', off: true },
  { k: '前端', v: '本页（空态说明），不展示伪数据' }
]

const intent =
  '把同一会话内若干条 L0 原始日志 / L1 原子记忆聚合成可复用的「场景块」，让后续对话不必重放全部原始消息即可恢复上下文。' +
  '在分层里位于 L1 之上、L3 之下：比单条事实更聚合，但仍是会话级临时上下文，不进入用户长期画像。'

const todos = [
  '定义场景块的聚合规则与生命周期（会话结束即归档还是保留）',
  '在 OltpMemoryRepository.saveSceneBlock 上补真实写入方',
  '提供场景召回工具 / API，并接入提示词组装链路',
  '补 REST 端点与分页列表，前端才能展示真实数据',
  '补测试：写入 → 召回 → 过期 / 清理，避免只建表不落地'
]
</script>

<style scoped>
.scene-page { padding: 4px 2px 24px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
.page-head h1 { display: flex; align-items: center; gap: 10px; }
.code {
  font-family: var(--amm-font-mono); font-size: 15px; font-weight: 600;
  color: var(--amm-primary); background: var(--amm-primary-soft);
  padding: 2px 8px; border-radius: var(--amm-radius-sm);
}
.sub { margin: 6px 0 0; font-size: 12px; color: var(--amm-ink-subtle); }
.gap-alert { margin-bottom: 14px; }
.facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 24px; }
.fact { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.fact-k { font-size: 11px; color: var(--amm-ink-subtle); letter-spacing: 0.3px; }
.fact-v { font-size: 13px; color: var(--amm-ink); font-family: var(--amm-font-mono); word-break: break-all; }
.fact-v.off { color: #9A6600; }
.dual { margin-top: 14px; }
.full-h { height: 100%; }
.txt { margin: 0; font-size: 13px; color: var(--amm-ink-muted); line-height: 1.75; }
.todo { margin: 0; padding-left: 18px; font-size: 12px; color: var(--amm-ink-muted); line-height: 1.9; }
.empty-card { margin-top: 14px; }
.empty-hint { font-size: 12px; color: var(--amm-ink-subtle); margin-bottom: 12px; }
.empty-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
@media (max-width: 900px) { .facts { grid-template-columns: 1fr; } }
</style>
