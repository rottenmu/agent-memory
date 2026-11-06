<template>
  <section class="amm-page">
    <div class="page-head">
      <div class="head-title">
        <h1>用户长期记忆</h1>
              </div>
      <el-button type="primary" plain :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <!-- 工具栏 -->
      <div class="toolbar">
        <el-input v-model="userId" placeholder="用户 ID，如 admin" clearable class="w-220" @keyup.enter="load">
          <template #prepend>用户</template>
        </el-input>
        <el-select v-model="category" placeholder="全部类别" clearable class="w-140">
          <el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <div class="flex-1"></div>
        <el-button type="success" plain :icon="Plus" @click="openAdd">新增记忆</el-button>
        <el-popconfirm title="确认清空该用户全部记忆？此操作不可恢复" @confirm="clearAll">
          <template #reference>
            <el-button type="danger" plain>清空全部</el-button>
          </template>
        </el-popconfirm>
      </div>

      <!-- 列表 -->
      <el-table :data="list" size="small" stripe v-loading="loading">
        <el-table-column label="类别" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.category)">{{ catLabel(row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="记忆内容" min-width="260" show-overflow-tooltip />
        <el-table-column label="脱敏" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.sensitiveMasked" size="small" type="danger">已脱敏</el-tag>
            <span v-else class="plain-text">—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }"><span class="amm-mono">{{ row.timestamp }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goTrace(row)">溯源</el-button>
            <el-popconfirm title="确认删除该条记忆？" @confirm="remove(row)">
              <template #reference>
                <el-button link type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !list.length" description="暂无用户记忆，输入用户 ID 查询或点击新增" />
    </el-card>

    <!-- 新增弹窗 -->
    <el-dialog v-model="addVisible" title="新增用户记忆" width="480px">
      <el-form :model="addForm" label-width="80px">
        <el-form-item label="用户 ID">
          <el-input v-model="addForm.userId" placeholder="用户标识" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="addForm.category" class="w-full">
            <el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="addForm.content" type="textarea" :rows="4" placeholder="记忆内容（敏感信息将自动脱敏）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdd">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import '../styles/tokens.css'
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Search, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getUserMemory, saveUserMemory, deleteUserMemory, recordsOf } from '../api/memory'

const router = useRouter()
const userId = ref('admin')
const category = ref('')
const list = ref([])
const loading = ref(false)
const saving = ref(false)
const addVisible = ref(false)
const addForm = ref({ userId: '', category: 'preference', content: '' })

const categories = [
  { value: 'persona', label: '画像' },
  { value: 'preference', label: '偏好' },
  { value: 'history', label: '历史' },
  { value: 'custom', label: '自定义' }
]

function catLabel(v) {
  return categories.find((c) => c.value === v)?.label || v || '—'
}

function tagType(v) {
  return { persona: 'primary', preference: 'success', history: 'warning', custom: 'info' }[v] || 'info'
}

async function load() {
  if (!userId.value) return
  loading.value = true
  try {
    list.value = recordsOf(await getUserMemory(userId.value, { category: category.value || undefined }))
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

function openAdd() {
  addForm.value = { userId: userId.value, category: 'preference', content: '' }
  addVisible.value = true
}

async function submitAdd() {
  if (!addForm.value.userId || !addForm.value.content) {
    ElMessage.warning('用户 ID 与内容必填')
    return
  }
  saving.value = true
  try {
    await saveUserMemory(addForm.value.userId, {
      category: addForm.value.category,
      content: addForm.value.content
    })
    ElMessage.success('已保存')
    addVisible.value = false
    load()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await deleteUserMemory(userId.value, row.id)
  ElMessage.success('已删除')
  load()
}

async function clearAll() {
  await deleteUserMemory(userId.value)
  ElMessage.success('已清空')
  load()
}

function goTrace(row) {
  const traceId = row.traceId
  if (!traceId) {
    ElMessage.info('该记录无 traceId，可在溯源分析 Tab 手动输入')
    return
  }
  // 切到记忆管理 Tab 页的"溯源分析"并带入 traceId
  router.push({ path: '/agent-memory', query: { tab: 'trace', traceId } })
}

onMounted(load)
</script>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.head-title h1 { font-size: 20px; font-weight: 600; letter-spacing: -0.2px; margin: 0 0 4px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 16px; align-items: center; flex-wrap: wrap; }
.w-220 { width: 220px; }
.w-140 { width: 140px; }
.w-full { width: 100%; }
.flex-1 { flex: 1; }
.plain-text { color: var(--amm-ink-faint); }
</style>
