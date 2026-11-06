<template>
  <section class="amm-page arch-configs-page">
    <div class="page-head">
      <div>
        <h1><span class="scope-code">{{ scope.code }}</span> {{ scope.title }}</h1>
        <p class="sub">{{ scope.desc }} · 数据源 /api/agent-memory/arch/configs</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Back" @click="emit('back')">返回消息记录</el-button>
      </div>
    </div>

    <div class="scope-bar">
      <span class="scope-label">范围</span>
      <el-radio-group :model-value="scope.key" @change="goScope">
        <el-radio-button value="ALL">全部</el-radio-button>
        <el-radio-button value="SOUL">SOUL 配置</el-radio-button>
        <el-radio-button value="USER">USER 档案</el-radio-button>
        <el-radio-button value="EXTRACTED">已提取</el-radio-button>
      </el-radio-group>
      <span class="scope-url amm-mono">{{ scope.url }}</span>
    </div>

    <el-alert
      class="tip"
      type="info"
      :closable="false"
      show-icon
      title="分页口径"
      description="列表一次取回全部配置（后端 size 上限 200），在前端按类型/关键词/已提取筛选后分页；后端暂不支持 source 过滤，故未改任何接口。"
    />

    <ArchConfigTable
      v-if="active"
      :type="scope.type"
      :extracted-only="scope.extractedOnly"
    />
  </section>
</template>

<script setup>
import '../styles/tokens.css'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Back } from '@element-plus/icons-vue'
import ArchConfigTable from '../components/ArchConfigTable.vue'

const props = defineProps({
  active: { type: Boolean, default: false }
})
const emit = defineEmits(['back'])
const route = useRoute()
const router = useRouter()

/** URL 是唯一筛选来源：卡片跳转、范围切换、刷新与收藏都落在同一份 query 上。 */
const scope = computed(() => {
  const q = route.query
  const type = Array.isArray(q.type) ? String(q.type[0] || '') : String(q.type || '')
  const extracted = q.extracted === '1' || (Array.isArray(q.extracted) && q.extracted.includes('1'))
  if (extracted) {
    return {
      key: 'EXTRACTED', code: '已提取', title: '已提取的 USER 档案',
      desc: '筛选条件：type = USER 且 source = 自动（与统计卡 extracted 口径一致）',
      type: 'USER', extractedOnly: true, url: '/agent-memory/arch-configs?extracted=1'
    }
  }
  if (type === 'SOUL') {
    return {
      key: 'SOUL', code: 'SOUL', title: 'SOUL 配置',
      desc: '筛选条件：type = SOUL（AI 人格 / 身份配置）',
      type: 'SOUL', extractedOnly: false, url: '/agent-memory/arch-configs?type=SOUL'
    }
  }
  if (type === 'USER') {
    return {
      key: 'USER', code: 'USER', title: 'USER 档案',
      desc: '筛选条件：type = USER（用户档案配置）',
      type: 'USER', extractedOnly: false, url: '/agent-memory/arch-configs?type=USER'
    }
  }
  return {
    key: 'ALL', code: '全部', title: '画像配置',
    desc: '不附加类型筛选，展示 memory_arch_config 全表',
    type: '', extractedOnly: false, url: '/agent-memory/arch-configs'
  }
})

function goScope(key) {
  const query = key === 'EXTRACTED' ? { extracted: '1' }
    : key === 'SOUL' ? { type: 'SOUL' }
      : key === 'USER' ? { type: 'USER' } : {}
  router.push({ path: '/agent-memory/arch-configs', query })
}
</script>

<style scoped>
.arch-configs-page { padding: 4px 2px 24px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.page-head h1 { display: flex; align-items: center; gap: 10px; }
.scope-code {
  font-family: var(--amm-font-mono); font-size: 14px; font-weight: 600;
  color: var(--amm-primary); background: var(--amm-primary-soft);
  padding: 2px 8px; border-radius: var(--amm-radius-sm);
}
.sub { margin: 6px 0 0; font-size: 12px; color: var(--amm-ink-subtle); }
.head-actions { display: flex; gap: 8px; align-items: center; }
.scope-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
.scope-label { font-size: 12px; color: var(--amm-ink-subtle); }
.scope-url { font-size: 11px; color: var(--amm-ink-muted); }
.tip { margin-bottom: 14px; }
</style>
