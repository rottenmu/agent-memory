# agent-memory-ui

agent-memory 四层金字塔记忆系统的**独立前端应用**(Vue 3 + Element Plus + Vite)。

> 工程位置：`agent_runner/modules/agent-memory/frontend/agent-memory-ui/`（2026-11-06 从 `frontend/` 归位到所属模块下）。
> 模块定位：`agent-memory` 仍是 `agent_runner` 的 Maven reactor 模块,同时 `agent-memory-application` 可独立启动。

基于 `frontend/modules/agentmemory`(web-shell 插件)的 6 个视图组件平移,可脱离主壳单独运行,直连 agent-memory 后端。

## 功能

- 记忆总览(Dashboard):用户/会话/全局记忆速览、KPI、行为时序、L0 事件构成、会话排行
- 用户长期记忆:画像/偏好/历史/自定义 四类管理(新增/删除/清空/溯源)
- 会话记忆:会话变量 CRUD
- 全局持久记忆:CRUD + 清空
- 溯源分析(Trajectory):按 traceId 查看 L0 事件链,9 类来源筛选
- 记忆策略:敏感过滤规则与类别白名单展示
- 画像配置(SOUL/USER 档案):统计、列表、新建/编辑/提取/删除

## 技术栈

Vue 3 · Element Plus · Vue Router(hash) · Axios · Vite 5

## 快速开始

```bash
npm install
npm run dev      # 开发 → http://localhost:6060
npm run build    # 生产构建 → dist/
```

dev 阶段 Vite 将 `/api` 代理到后端 `http://localhost:9900`,需先启动 agent-memory 独立后端(或 agent-application)。
后端启动方式见模块根目录 `README.md` 第五节。

后端改用别的端口时,前后端用同名环境变量对齐:

```bash
# 后端(modules/agent-memory/agent-memory-application/)
AGENT_MEMORY_PORT=9901 ./run.sh
# 前端(modules/agent-memory/frontend/agent-memory-ui/)
AGENT_MEMORY_PORT=9901 npm run dev
```

## 目录结构

```
agent-memory-ui/
├── vite.config.js        # dev server + /api → :9900 代理
├── index.html
└── src/
    ├── main.js           # Vue + Element Plus + 图标全局注册
    ├── App.vue           # 路由壳
    ├── router.js         # 7 条路由(hash 模式)
    ├── api/              # request.js(axios 封装)+ memory.js(接口)
    ├── styles/tokens.css # 设计令牌
    └── views/            # 6 个视图组件
```

## 后端接口依赖

| 分组 | 路径前缀 |
|---|---|
| 记忆 CRUD (OLTP) | `/api/ai/memory/*` |
| 分析 (OLAP) | `/api/agent-memory/analytics/*` |
| 分层架构 | `/api/agent-memory/arch/*` |
