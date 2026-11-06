<template>
  <section class="amm-page">
    <div class="page-head">
      <div class="head-title">
        <h1>会话记忆</h1>
              </div>
      <el-button type="primary" plain :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="sessionId" placeholder="会话 ID，如 s-20260819-001" clearable class="w-260" @keyup.enter="load">
          <template #prepend>会话</template>
        </el-input>
        <el-select v-model="sessionId" placeholder="最近活跃会话" filterable clearable class="w-260" @change="load">
          <el-option v-for="s in recentSessions" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <div class="flex-1"></div>
        <el-button type="success" plain :icon="Plus" @click="openAdd">新增变量</el-button>
      </div>

      <el-table :data="list" size="small" stripe v-loading="loading">
        <el-table-column label="变量名（key）" width="240" show-overflow-tooltip>
          <template #default="{ row }"><span class="amm-mono-strong">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column prop="content" label="值（value）" min-width="280" show-overflow-tooltip />
        <el-table-column label="时间" width="180">
          <template #default="{ row }"><span class="amm-mono">{{ row.timestamp }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-popconfirm title="确认删除该变量？" @confirm="remove(row)">
              <template #reference>
                <el-button link type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !list.length" :description="sessionId ? '该会话暂无业务变量' : '输入或选择会话 ID 查看该会话的业务变量'" />
    </el-card>

    <el-dialog v-model="addVisible" title="新增会话变量" width="460px">
      <el-form :model="addForm" label-width="80px">
        <el-form-item label="会话 ID">
          <el-input v-model="addForm.sessionId" placeholder="会话标识" />
        </el-form-item>
        <el-form-item label="变量名">
          <el-input v-model="addForm.key" placeholder="如 current_doc" />
        </el-form-item>
        <el-form-item label="值">
          <el-input v-model="addForm.value" placeholder="变量值" />
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
import { Refresh, Search, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getSessionVars, saveSessionVar, deleteSessionVar, getMessages, recordsOf } from '../api/memory'

const sessionId = ref('')
const recentSessions = ref([])
const list = ref([])
const loading = ref(false)
const saving = ref(false)
const addVisible = ref(false)
const addForm = ref({ sessionId: '', key: '', value: '' })

async function load() {
  if (!sessionId.value) {
    list.value = []
    return
  }
  loading.value = true
  try {
    list.value = recordsOf(await getSessionVars(sessionId.value))
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

/** 首屏自动带出「有 L1 变量」的最近活跃会话。
 *  OLAP /analytics/messages 按 ts DESC，前 50 条里取 distinct session_id 作候选；
 *  但最新消息可能只是 user/global 写入留下的 L0 轨迹（如 session_id=user-admin），
 *  该会话并没有会话变量。故并行探测各候选的 L1 条数，默认落在第一个非空会话上。 */
async function initRecentSessions() {
  try {
    const data = await getMessages({ page: 0, size: 50 })
    const ids = [...new Set((data?.rows || []).map((r) => r.session_id).filter(Boolean))].slice(0, 8)
    recentSessions.value = ids
    if (!sessionId.value && ids.length) {
      const probes = await Promise.all(ids.map(async (id) => {
        try {
          return recordsOf(await getSessionVars(id, { limit: 1 })).length > 0 ? id : ''
        } catch {
          return ''
        }
      }))
      sessionId.value = probes.find(Boolean) || ids[0]
    }
  } catch {
    recentSessions.value = []
  }
  await load()
}

onMounted(initRecentSessions)

function openAdd() {
  addForm.value = { sessionId: sessionId.value, key: '', value: '' }
  addVisible.value = true
}

async function submitAdd() {
  if (!addForm.value.sessionId || !addForm.value.key) {
    ElMessage.warning('会话 ID 与变量名必填')
    return
  }
  saving.value = true
  try {
    await saveSessionVar(addForm.value.sessionId, {
      key: addForm.value.key,
      value: addForm.value.value
    })
    ElMessage.success('已保存')
    addVisible.value = false
    sessionId.value = addForm.value.sessionId
    load()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await deleteSessionVar(sessionId.value, row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.head-title h1 { font-size: 20px; font-weight: 600; letter-spacing: -0.2px; margin: 0 0 4px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 16px; align-items: center; flex-wrap: wrap; }
.w-260 { width: 260px; }
.flex-1 { flex: 1; }
</style>
