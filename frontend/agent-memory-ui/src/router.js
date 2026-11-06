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
