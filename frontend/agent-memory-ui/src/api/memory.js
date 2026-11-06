import request from './request'

/* ================= 列表响应读取 ================= */

/**
 * 读取列表类接口的记录数组。
 *
 * 后端 `/api/ai/memory/*` 的列表接口自 2026-09-15（M1）起由「裸数组」改为分页信封
 * `{ records, total, truncated, limit, offset }`。这里同时兼容两种形态：既能与
 * 尚未升级的后端共存，也避免旧写法「判非数组即回空」把接口变更静默吞成空列表
 * （页面不报错、只是没数据，最难查）。
 */
export function recordsOf(res) {
  if (Array.isArray(res)) return res
  return Array.isArray(res?.records) ? res.records : []
}

/* ================= 记忆管理 OLTP（/api/ai/memory） ================= */

/** 读取会话变量列表（支持分页）。 */
export function getSessionVars(sessionId, { limit, offset } = {}) {
  return request.get('/ai/memory/session/' + encodeURIComponent(sessionId), {
    params: { limit, offset }
  })
}

/** 写入会话变量。 */
export function saveSessionVar(sessionId, data) {
  return request.post('/ai/memory/session/' + encodeURIComponent(sessionId), data)
}

/** 删除会话变量。 */
export function deleteSessionVar(sessionId, key) {
  return request.delete('/ai/memory/session/' + encodeURIComponent(sessionId), { params: { key } })
}

/** 查询用户长期记忆（可按类别过滤、分页）。 */
export function getUserMemory(userId, { category, limit, offset } = {}) {
  return request.get('/ai/memory/user/' + encodeURIComponent(userId), {
    params: { category: category || undefined, limit, offset }
  })
}

/** 写入用户长期记忆。 */
export function saveUserMemory(userId, data) {
  return request.post('/ai/memory/user/' + encodeURIComponent(userId), data)
}

/** 删除用户记忆（id 为空=清空该用户全部）。 */
export function deleteUserMemory(userId, id) {
  return request.delete('/ai/memory/user/' + encodeURIComponent(userId), {
    params: { id: id || undefined }
  })
}

/** 查询全局持久记忆（支持分页）。 */
export function getGlobalMemory({ limit, offset } = {}) {
  return request.get('/ai/memory/global', { params: { limit, offset } })
}

/** 写入全局持久记忆。 */
export function saveGlobalMemory(data) {
  return request.post('/ai/memory/global', data)
}

/** 删除全局记忆（key 为空=清空全部）。 */
export function deleteGlobalMemory(key) {
  return request.delete('/ai/memory/global', { params: { key: key || undefined } })
}

/** 记忆策略（白名单/敏感过滤/规则）。 */
export function getMemoryPolicy() {
  return request.get('/ai/memory/policy')
}

/* ================= 记忆分析 OLAP（/api/agent-memory/analytics） ================= */

/** 会话聚合统计。 */
export function getSessionStats() {
  return request.get('/agent-memory/analytics/session-stats')
}

/** 用户行为时序（近 N 天）。 */
export function getUserActivity(userId, days = 7) {
  return request.get('/agent-memory/analytics/user-activity', { params: { userId, days } })
}

/** 记忆蒸馏质量评估（L0 事件构成）。 */
export function getDistillationStats() {
  return request.get('/agent-memory/analytics/distillation-stats')
}

/** 按 traceId 查询 L0 事件链。 */
export function getTraceEvents(traceId) {
  return request.get('/agent-memory/analytics/trace', { params: { traceId } })
}

/** 自定义 OLAP SQL（Calcite 查询 olap_l0_log）。 */
export function queryOlap(sql) {
  return request.get('/agent-memory/analytics/query', { params: { sql } })
}

/* ================= L3 画像（走已合并的 OLTP 列表） ================= */

/**
 * L3 画像列表 —— 复用 OLTP 用户记忆接口，**不新增后端端点**。
 * 后端 pageUserMemory 合并 L1 + L3 两表返回（记录带 layer / version / importance），
 * 调用方按 category=persona|preference 或 layer=L3 过滤出画像。
 */
export function getUserPersonas(userId, { limit = 200, offset } = {}) {
  return getUserMemory(userId, { limit, offset })
}

/* ⚠️ 以下 3 个接口在当前后端**没有对应端点**（调用返回 404），保留仅为历史兼容，
 * 页面请勿直接使用。L3 数据一律走上面的 getUserPersonas / getUserMemory。
 * 若将来补 /api/agent-memory/analytics/personas 端点，再启用并补测试。 */

/** @deprecated 后端无端点，404。 */
export function listPersonas() {
  return request.get('/agent-memory/analytics/personas')
}

/** @deprecated 后端无端点，404。 */
export function extractPersona(userId) {
  return request.post(`/agent-memory/analytics/personas/${userId}/extract`)
}

/** @deprecated 后端无端点，404。 */
export function deletePersona(userId, personaType) {
  return request.delete(`/agent-memory/analytics/personas/${userId}/${personaType}`)
}

/** 会话消息预览（L0 消息 + 来源 md 文档信息）。 */
export function getSessionMessages(sessionId, agentId = 'default') {
  return request.get('/agent-memory/analytics/session-messages', {
    params: { sessionId, agentId }
  })
}

/* ================= 记忆分层架构 ================= */
export function getArchStats() {
  return request.get('/agent-memory/arch/stats')
}
export function listArchConfigs(params) {
  return request.get('/agent-memory/arch/configs', { params })
}
export function getArchConfig(id) {
  return request.get('/agent-memory/arch/configs/' + id)
}
export function createArchConfig(body) {
  return request.post('/agent-memory/arch/configs', body)
}
export function updateArchConfig(id, body) {
  return request.put('/agent-memory/arch/configs/' + id, body)
}
export function extractArchConfig(id) {
  return request.post('/agent-memory/arch/configs/' + id + '/extract')
}
export function deleteArchConfig(id) {
  return request.delete('/agent-memory/arch/configs/' + id)
}

/** 会话消息分页（「消息记录」下钻与「会话消息」Tab 复用）。 */
export function getMessages(params) {
  return request.get('/agent-memory/analytics/messages', { params })
}
