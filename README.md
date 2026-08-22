# agent-memory — 智能体四层记忆模块

> 为智能体（Agent）提供进程内长期记忆能力：L0~L3 四层记忆金字塔 + OLTP/OLAP 双存储 + 分钟级 ETL 分析副本，支持 MCP 工具、OpenClaw 文件兼容模式与记忆架构（SOUL/USER）配置。
> 技术栈：Java 17 / Spring Boot 3.4.5 / H2 MVStore / Apache Arrow + Calcite / AgentScope Java v2。

## 一、介绍

### 1.1 四层记忆金字塔（L0 ~ L3）

| 层 | 模型 | 职责 | 说明 |
|---|---|---|---|
| **L0** | `L0RawLog` | 原始对话/记忆日志 | 追加式留痕（合规审计），自增 id + traceId/sessionId 索引，永删除 |
| **L1** | `L1AtomicMemory` | 原子记忆 | 单条事实（key=value），含类型/会话归属/embedding |
| **L2** | `L2SceneBlock` | 场景块 | 会话级场景聚合（当前为结构预留） |
| **L3** | `L3Persona` | 用户画像 | 用户长期画像，version 乐观更新 |

### 1.2 核心特性

- **双存储架构**：OLTP（H2 MVStore，进程内，5 表）负责实时读写；OLAP（Arrow IPC 文件 + Calcite 内存表 `olap_l0_log`）负责分析查询（会话统计/消息预览/溯源/自定义 SQL）
- **ETL 同步**：`AsyncLogSyncTask` 以自增 id 为游标增量分页（500/批）同步 L0 → OLAP 宽表，cron 默认每分钟；失败不影响主链路，`cursor<0` 全量重建
- **记忆文件兼容模式**：`MemoryFileStore` 将记忆持久化为 `MEMORY.md`（OpenClaw 互通），存储引擎可切 RocksDB / 本地磁盘
- **MCP 工具**：`memory_write` / `memory_read` / `memory_delete` / `memory_search`（白名单免 token 接入）
- **Agent 内置工具**：`memory_write` / `memory_read` 注入 AgentScope Toolkit，推理中直接读写记忆
- **对话记忆事件流**：`AiConversationMemory` 全量 L0 事件化（role=user/assistant）+ 跨重启回放（`restore`）+ 会话 fork + 会话标题 + 压缩剪枝（>5120 丢弃、>2048 截断）
- **记忆架构配置**：`MemoryArch` 管理 SOUL（人设）/ USER（用户档案）配置，含 5 维统计卡
- **安全**：敏感信息落库前脱敏（如 `138****5678`，原始值不落盘）+ 内容白名单过滤
- **存储 SPI 可插拔**：OLTP（`h2`/`mysql`/`rocksdb`）、OLAP（`arrow`/`duckdb`）、文件引擎（`rocksdb`/`local`/`minio`）按配置切换

### 1.3 存储架构

```text
写：AiMemoryService（脱敏→白名单）→ H2 OLTP（l0/l1/l2/l3）──┐
                                                          │ ETL ≤1min
读：总览/溯源/消息预览 ← Arrow OLAP 宽表（Calcite） ←───────┘
```

## 二、使用说明

### 2.1 Maven 依赖

```xml
<dependency>
    <groupId>com.zimo</groupId>
    <artifactId>agent-memory-autoconfig</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- 或直接依赖 core -->
<dependency>
    <groupId>com.zimo</groupId>
    <artifactId>agent-memory-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

> 注意：Maven parent 为 agent_runner 根 pom（`com.zimo:agent_runner`）。独立构建本仓库需先安装父 pom 或改用独立 parent；本仓库为 agent_runner 平台代码拆分。

### 2.2 配置（application.yml）

```yaml
agent-memory:
  oltp-engine: h2          # h2 / mysql / rocksdb
  olap-engine: arrow       # arrow / duckdb
  h2:
    path: data/agent-memory
  sync:
    cron: "0 * * * * ?"    # ETL 每分钟
framework:
  storage:
    engine: rocksdb        # 记忆文件引擎：rocksdb / local / minio
    base-path: data/rocksdb
```

### 2.3 REST API

| 分组 | 端点 | 说明 |
|---|---|---|
| 四层记忆 | `GET/POST/DELETE /api/ai/memory/{session\|user\|global}/...`、`GET /api/ai/memory/policy` | 运行时记忆 CRUD + 脱敏策略 |
| 文件兼容 | `GET/POST/DELETE /api/agent-memory/file/{agentId}`、`/search?q=` | MEMORY.md 互通 |
| 分析 | `/api/agent-memory/analytics/session-stats` / `user-activity` / `distillation-stats` / `trace` / `query` / `session-messages` / `messages` | 会话统计/时序/溯源/自定义 SQL/消息预览/消息分页 |
| 记忆架构 | `/api/agent-memory/arch/stats`、`/configs`、`/configs/{id}/extract` | SOUL/USER 档案管理 |
| MCP | `POST /api/agent-memory/mcp`（jsonrpc） | WorkBuddy/外部 MCP 客户端接入 |

### 2.4 调用示例

```bash
# 写入用户记忆（自动脱敏 + 白名单）
curl -X POST http://localhost:9900/api/ai/memory/user/admin \
  -H "Authorization: <token>" -H "Content-Type: application/json" \
  -d '{"category":"preference","content":"偏好周报邮件"}'

# MCP：跨目标检索
curl -X POST http://localhost:9900/api/agent-memory/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"memory_search","arguments":{"query":"周报","userId":"admin"}}}'
```

### 2.5 数据文件位置

| 存储 | 路径 | 说明 |
|---|---|---|
| H2 OLTP | `data/agent-memory.mv.db` | l0/l1/l2/l3 + memory_arch_config 五表 |
| OLAP | `data/olap/l0_log.arrow` + `.cursor` | 分析宽表 + 同步游标 |
| RocksDB 文件 | `data/rocksdb/`（键 `agent-memory/{agentId}/MEMORY.md`） | 记忆文件兼容模式 |
| 本地引擎 | `data/local-storage/` | 切换 `framework.storage.engine=local` 时 |

## 三、模块结构

```text
agent-memory/
├── pom.xml
├── agent-memory-core/          # 领域模型/存储/ETL/记忆服务
│   ├── model/                  # L0RawLog / L1AtomicMemory / L2SceneBlock / L3Persona
│   ├── storage/                # OLTP/OLAP 接口 + SPI（H2/Arrow 实现）
│   ├── sync/                   # AsyncLogSyncTask（ETL）
│   ├── chat/                   # AiConversationMemory（对话事件流）
│   ├── memory/                 # AiMemoryService / AiMemoryAgentTool / AiMemoryController
│   ├── memoryarch/             # 记忆架构配置（SOUL/USER）
│   ├── memoryfile/             # MemoryFileStore（文件兼容模式）
│   ├── mcp/                    # MemoryMcpEndpoint / MemoryMcpToolkit
│   └── security/               # 脱敏过滤器 / 白名单
└── agent-memory-autoconfig/    # 自动装配 + REST 控制器 + Properties
```

## 四、构建与验证

```bash
# 模块构建（在 agent_runner 仓库内）
./mvnw install -pl modules/agent-memory -am -DskipTests

# 运行单测（含事件流/回放/ETL/存储路由）
./mvnw verify -pl modules/agent-memory
```

## 五、已知边界

- L0 为追加日志不删除（合规留痕）；OLAP 分析副本最多落后 1 分钟
- L2 场景块聚类当前仅建表未落地；embedding 使用 `PureJavaVectorUtil`（无外部模型依赖）
- H2 嵌入式存储为进程内（agent_runner 平台经批准的唯一豁免；其他模块请使用 MySQL）
