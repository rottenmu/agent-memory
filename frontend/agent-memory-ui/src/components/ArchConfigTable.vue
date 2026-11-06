<template>
  <section class="arch-table">
    <div class="msg-toolbar">
      <el-select v-model="typeFilter" placeholder="全部类型" clearable style="width: 130px" @change="resetPage">
        <el-option label="SOUL 配置" value="SOUL" />
        <el-option label="USER 档案" value="USER" />
      </el-select>
      <el-input v-model="q" placeholder="搜索 名称 / 核心摘要" clearable style="width: 220px"
        :prefix-icon="Search" @keyup.enter="resetPage" @clear="resetPage" />
      <el-button type="primary" :icon="Plus" @click="openCreate">新建</el-button>
      <el-button :icon="Refresh" circle @click="reload" />
      <span class="msg-total">
        共 {{ filtered.length }} 条{{ extractedOnly ? '（已提取 = USER 且来源为自动）' : '画像配置' }}
      </span>
    </div>

    <el-table v-loading="loading" :data="pagedRows" size="small" stripe>
      <el-table-column prop="name" label="用户" width="140" show-overflow-tooltip />
      <el-table-column prop="summary" label="核心摘要" min-width="260" show-overflow-tooltip />
      <el-table-column prop="background" label="背景" min-width="220" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.type === 'SOUL' ? 'warning' : 'primary'" effect="plain">{{ row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" align="center" />
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.source === '自动' ? 'success' : 'info'" effect="plain">{{ row.source }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" align="center">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" text type="warning" @click="onExtract(row)">提取</el-button>
          <el-button size="small" text type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前筛选下暂无画像配置（可新建或执行「提取」）" />
      </template>
    </el-table>

    <div class="arch-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="filtered.length"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="resetPage"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑画像配置' : '新建画像配置'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="form.type" :disabled="!!form.id">
            <el-radio value="SOUL">SOUL（灵魂/人格）</el-radio>
            <el-radio value="USER">USER（用户档案）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="用户/画像名称" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="核心摘要" prop="summary">
          <el-input v-model="form.summary" type="textarea" :rows="3" placeholder="一句话概括该画像" />
        </el-form-item>
        <el-form-item label="背景" prop="background">
          <el-input v-model="form.background" type="textarea" :rows="2" placeholder="背景/上下文说明" />
        </el-form-item>
        <el-form-item label="完整内容" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="5" placeholder="完整画像内容" />
        </el-form-item>
        <el-form-item label="来源" prop="source">
          <el-input v-model="form.source" placeholder="手动 / 自动" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { Refresh, Search, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listArchConfigs, createArchConfig, updateArchConfig, extractArchConfig, deleteArchConfig
} from '../api/memory'

/**
 * 画像配置列表：一次拉取（后端 size 上限 200），前端按 type / q / 已提取筛选后分页。
 * 后端 /arch/configs 已有 page/size，但没有 source 过滤参数，故「已提取」必须走客户端筛选；
 * 三种筛选统一走同一套分页，避免服务端/客户端两套口径混用。
 */
const props = defineProps({
  type: { type: String, default: '' },
  extractedOnly: { type: Boolean, default: false }
})
const emit = defineEmits(['changed'])

const allRows = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref(null)
const typeFilter = ref(props.type)
const q = ref('')
const page = ref(1)
const pageSize = ref(10)
const form = reactive({ id: '', type: 'USER', name: '', summary: '', background: '', content: '', source: '手动' })
const rules = {
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  summary: [{ required: true, message: '请输入核心摘要', trigger: 'blur' }],
  content: [{ required: true, message: '请输入完整内容', trigger: 'blur' }]
}

watch(() => [props.type, props.extractedOnly], () => {
  typeFilter.value = props.type
  q.value = ''
  page.value = 1
})

const filtered = computed(() => {
  const kw = q.value.trim().toLowerCase()
  return allRows.value.filter((r) => {
    if (typeFilter.value && r.type !== typeFilter.value) return false
    if (props.extractedOnly && !(r.type === 'USER' && r.source === '自动')) return false
    if (kw && !`${r.name || ''} ${r.summary || ''} ${r.background || ''}`.toLowerCase().includes(kw)) return false
    return true
  })
})
const pagedRows = computed(() =>
  filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))

watch(() => filtered.value.length, (n) => {
  const maxPage = Math.max(1, Math.ceil(n / pageSize.value))
  if (page.value > maxPage) page.value = maxPage
})

function resetPage() { page.value = 1 }

/** 暴露给父组件：刷新按钮与 CRUD 后重拉全量。 */
async function reload() {
  loading.value = true
  try {
    const data = await listArchConfigs({ page: 0, size: 200 })
    allRows.value = data.rows || []
  } catch (e) {
    ElMessage.error('加载画像配置失败：' + (e?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}
defineExpose({ reload })
onMounted(reload)

function resetForm() {
  form.id = ''
  form.type = typeFilter.value === 'SOUL' ? 'SOUL' : 'USER'
  form.name = ''
  form.summary = ''
  form.background = ''
  form.content = ''
  form.source = '手动'
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openEdit(row) {
  resetForm()
  Object.assign(form, row)
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) {
      await updateArchConfig(form.id, {
        summary: form.summary, background: form.background,
        content: form.content, source: form.source
      })
      ElMessage.success('已更新画像配置')
    } else {
      await createArchConfig({ ...form })
      ElMessage.success('已创建画像配置')
    }
    dialogVisible.value = false
    await reload()
    emit('changed')
  } catch (e) {
    ElMessage.error('保存失败：' + (e?.message || '未知错误'))
  } finally {
    saving.value = false
  }
}

async function onExtract(row) {
  try {
    await extractArchConfig(row.id)
    ElMessage.success(`已标记提取「${row.name}」`)
    await reload()
    emit('changed')
  } catch (e) {
    ElMessage.error('提取失败：' + (e?.message || '未知错误'))
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.name}」（${row.type}）？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteArchConfig(row.id)
    ElMessage.success('已删除')
    await reload()
    emit('changed')
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || '未知错误'))
  }
}
</script>

<style scoped>
.msg-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.msg-total { font-size: 12.5px; color: var(--amm-ink-subtle); }
.arch-pager { margin-top: 12px; display: flex; justify-content: flex-end; }
</style>
