import { createRouter, createWebHashHistory } from 'vue-router'

// 路由与前端插件 modules/agentmemory/routes.js 对齐（hash 模式，独立应用免服务端配置）
export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/agent-memory' },
    {
      path: '/agent-memory',
      name: 'AgentMemoryRoot',
      component: () => import('./views/MemoryDashboard.vue')
    },
    {
      path: '/agent-memory/dashboard',
      name: 'AgentMemoryDashboard',
      component: () => import('./views/MemoryDashboard.vue')
    },
    // 四层数据分层入口：与 dashboard 共用主壳，由 MemoryDashboard 按 path/query 切 pane
    {
      path: '/agent-memory/layers',
      name: 'AgentMemoryLayers',
      component: () => import('./views/MemoryDashboard.vue')
    },
    {
      path: '/agent-memory/scene',
      name: 'AgentMemoryScene',
      component: () => import('./views/MemoryDashboard.vue')
    },
    {
      path: '/agent-memory/persona',
      name: 'AgentMemoryPersona',
      component: () => import('./views/MemoryDashboard.vue')
    },
    // 「今日对话」卡片直达：共用主壳，由 MemoryDashboard 按 path 切 pane
    {
      path: '/agent-memory/today-sessions',
      name: 'AgentMemoryTodaySessions',
      component: () => import('./views/MemoryDashboard.vue')
    },
    // 「会话消息」统计卡直达：SOUL / USER / 已提取 三种筛选共用同一列表页
    {
      path: '/agent-memory/arch-configs',
      name: 'AgentMemoryArchConfigs',
      component: () => import('./views/MemoryDashboard.vue')
    },
    {
      path: '/agent-memory/user',
      name: 'AgentMemoryUser',
      component: () => import('./views/UserMemoryManage.vue')
    },
    {
      path: '/agent-memory/session',
      name: 'AgentMemorySession',
      component: () => import('./views/SessionMemoryManage.vue')
    },
    {
      path: '/agent-memory/global',
      name: 'AgentMemoryGlobal',
      component: () => import('./views/GlobalMemoryManage.vue')
    },
    {
      path: '/agent-memory/trace',
      name: 'AgentMemoryTrace',
      component: () => import('./views/TraceAnalysis.vue')
    },
    {
      path: '/agent-memory/policy',
      name: 'AgentMemoryPolicy',
      component: () => import('./views/MemoryPolicy.vue')
    }
  ]
})
