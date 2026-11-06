<template>
  <section class="amm-page">
    <div class="page-head">
      <div class="head-title">
        <h1>记忆策略</h1>
              </div>
      <el-button type="primary" plain :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-row :gutter="14">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>敏感内容过滤</b></template>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="敏感过滤开关">
              <el-tag size="small" :type="policy.sensitiveFiltering ? 'success' : 'warning'">
                {{ policy.sensitiveFiltering ? '已开启' : '已关闭' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="白名单策略">
              <el-tag size="small" :type="policy.whitelistEnabled ? 'primary' : 'info'">
                {{ policy.whitelistEnabled ? '启用（未白名单类别拒绝写入）' : '未启用（放行全部）' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <div class="section-title">脱敏规则（{{ (policy.sensitiveRules || []).length }} 类）</div>
          <div class="chips">
            <el-tag v-for="r in policy.sensitiveRules || []" :key="r" size="small" class="chip">{{ r }}</el-tag>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>记忆类别白名单</b></template>
          <template v-if="(policy.whitelistCategories || []).length">
            <div class="chips">
              <el-tag v-for="w in policy.whitelistCategories" :key="w" size="small" class="chip" type="success">
                {{ w }}
              </el-tag>
            </div>
          </template>
          <el-empty v-else description="白名单未启用，放行全部类别（persona / preference / history / custom / global）" :image-size="56" />


        </el-card>
      </el-col>
    </el-row>
  </section>
</template>

<script setup>
import '../styles/tokens.css'
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getMemoryPolicy } from '../api/memory'

const policy = ref({})

async function load() {
  try {
    policy.value = await getMemoryPolicy()
  } catch {
    policy.value = {}
  }
}

onMounted(load)
</script>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.head-title h1 { font-size: 20px; font-weight: 600; letter-spacing: -0.2px; margin: 0 0 4px; }
.section-title { font-size: 13px; font-weight: 500; margin: 16px 0 10px; color: var(--amm-ink-muted); }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
