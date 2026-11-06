<template>
  <section class="amm-page">
    <div class="page-head">
      <div class="head-title">
        <h1>全局持久记忆</h1>
              </div>
      <el-button type="primary" plain :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="success" plain :icon="Plus" @click="openAdd">新增全局记忆</el-button>
        <el-popconfirm title="确认清空全部全局记忆？此操作不可恢复" @confirm="clearAll">
          <template #reference>
            <el-button type="danger" plain>清空全部</el-button>
          </template>
        </el-popconfirm>
      </div>

      <el-table :data="list" size="small" stripe v-loading="loading">
        <el-table-column label="记忆键（key）" width="240" show-overflow-tooltip>
          <template #default="{ row }"><span class="amm-mono-strong">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
        <el-table-column label="脱敏" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.sensitiveMasked" size="small" type="danger">已脱敏</el-tag>
            <span v-else class="plain-text">—</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }"><span class="amm-mono">{{ row.timestamp }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-popconfirm title="确认删除该条全局记忆？" @confirm="remove(row)">
              <template #reference>
                <el-button link type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !list.length" description="暂无全局记忆" />
    </el-card>

    <el-dialog v-model="addVisible" title="新增全局记忆" width="460px">
      <el-form :model="addForm" label-width="80px">
        <el-form-item label="记忆键">
          <el-input v-model="addForm.key" placeholder="如 week-rule" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="addForm.content" type="textarea" :rows="3" placeholder="全局记忆内容" />
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
import { Refresh, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getGlobalMemory, saveGlobalMemory, deleteGlobalMemory, recordsOf } from '../api/memory'

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const addVisible = ref(false)
const addForm = ref({ key: '', content: '' })

async function load() {
  loading.value = true
  try {
    list.value = recordsOf(await getGlobalMemory())
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

function openAdd() {
  addForm.value = { key: '', content: '' }
  addVisible.value = true
}

async function submitAdd() {
  if (!addForm.value.key || !addForm.value.content) {
    ElMessage.warning('记忆键与内容必填')
    return
  }
  saving.value = true
  try {
    await saveGlobalMemory(addForm.value)
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
  await deleteGlobalMemory(row.id)
  ElMessage.success('已删除')
  load()
}

async function clearAll() {
  await deleteGlobalMemory()
  ElMessage.success('已清空')
  load()
}

onMounted(load)
</script>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.head-title h1 { font-size: 20px; font-weight: 600; letter-spacing: -0.2px; margin: 0 0 4px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 16px; align-items: center; flex-wrap: wrap; }
.plain-text { color: var(--amm-ink-faint); }
</style>
