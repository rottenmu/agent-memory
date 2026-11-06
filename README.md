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

> 定位：`agent-memory` 是 `agent_runner` 的 Maven reactor 模块（parent 为 `com.zimo:modules:1.0.0`），
> **不脱离仓库独立构建**；2026-11-06 起只有 `agent-memory-core` 一个子模块，启动类与独立运行配置
> 都在 core 内，可单独 `java -jar` 运行。完整运行方式见第五节「独立运行」。

### 2.2 配置（主应用 application.yml 中的 agent-memory.* 属性）

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
| 记忆治理 | `POST /api/ai/memory/batch`、`/batch/query`、`/recall`、`/extract`、`/merge`、`/evict`、`GET /api/ai/memory/metrics` | 批量读写 / S1 混合打分召回 / 事实抽取 / 合并 / 遗忘淘汰 / 指标 |
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

# 召回：S1 混合打分（match / decay / importance 三分量可解释）
# truncated=true 表示候选池被扫描上限截断，此时可能存在「分数更高却没进候选池」的记忆
curl -X POST http://localhost:9900/api/ai/memory/recall \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"default","userId":"admin","query":"周报","topK":5}'

# 合并 / 淘汰：dryRun 缺省为 true（只出计划不落库），必须显式传 false 才会真的删
curl -X POST http://localhost:9900/api/ai/memory/merge \
  -H "Content-Type: application/json" -d '{"tenantId":"default","userId":"admin"}'
```

**响应包体**：治理端点与全站一致，最终返回 `{code,msg,data}` 信封，业务结果在 `data` 内；
`/merge`、`/evict` 的 `data.dryRun` 与 `data.plan` 用于判断「有没有真的动数据」。

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
├── pom.xml                     # reactor 聚合模块（parent: com.zimo:modules:1.0.0，仅 1 个子模块）
├── agent-memory-core/          # 库 + 可独立运行应用（2026-11-06 合并）
│   ├── src/main/java/com/zimo/module/agentmemory/
│   │   ├── model/ storage/ sync/ chat/ engine/ memory/      # 领域 / 存储 / ETL / 召回
│   │   ├── mcp/ security/ analytics/ vector/ governance/    # MCP / 脱敏 / 分析 / 治理
│   │   └── autoconfig/         # AgentMemoryAutoConfiguration / Properties
│   ├── src/main/java/com/zimo/agentmemory/app/
│   │   └── AgentMemoryApplication.java   # 启动类（独立包，避开组件扫描冲突）
│   ├── src/main/resources/
│   │   ├── application-agentmemory.yml   # profile 专用独立运行配置（启动需激活 profile）
│   │   ├── META-INF/spring/...AutoConfiguration.imports
│   │   └── mcp/ skill/                   # MCP 工具定义 / 技能说明
│   ├── run.sh                  # 启动 exec jar（unset SERVER__PORT + profile + --add-opens）
│   ├── data/                   # 运行时生成：*.mv.db / olap/ / agent-memory-files/
│   └── pom.xml                 # spring-boot-maven-plugin classifier=exec（主构件仍是库 jar）
├── agent-memory-application/   # 已退役，不参与构建（见其 RETIRED.md；保留旧 jar 备份）
└── frontend/
    └── agent-memory-ui/        # 独立前端 Vue3 + Vite（6060，/api 代理到后端）
```

## 四、构建与验证

```bash
# 模块构建（在 agent_runner 仓库内）
./mvnw install -pl modules/agent-memory -am -DskipTests

# 运行单测（含事件流/回放/ETL/存储路由）
./mvnw verify -pl modules/agent-memory
```

## 五、独立运行（保留在 agent_runner 内，但可单独启动）

`agent-memory` 既是 reactor 模块，也是一个能独立启动的应用。**2026-11-06 起原
`agent-memory-application` 子模块已整合进 `agent-memory-core`**：启动类、配置、启动脚本、数据目录
都在 core 内，聚合 pom 只剩这一个子模块。前端工程位于 **`frontend/agent-memory-ui/`**。

> ⚠️ 两个刻意的设计（改动前务必看懂）：
> 1. **配置叫 `application-agentmemory.yml`，不是 `application.yml`**。core 被 `framework-ai` /
>    主应用当库依赖，classpath 根下的 `application.yml` 会被所有依赖方自动加载，`server.port=9900`
>    与关闭 api-registry 等配置会污染主应用。改成 profile 专用文件后，只有显式激活才加载。
> 2. **可执行 jar 带 `exec` classifier**。`spring-boot-maven-plugin` 若直接 repackage，会把主构件
>    换成 `BOOT-INF/classes` 结构的 fat jar，依赖方编译期找不到 `com.zimo.module.agentmemory.*` 类。
>    用 `classifier=exec` 后主构件仍是普通库 jar，另产 `agent-memory-core-1.0.0-exec.jar`。

### 后端（默认 9900）

```bash
# 1) 在 agent_runner 仓库根打包
#    Windows：Git Bash 用 ./mvnw，cmd/PowerShell 用 .\mvnw.cmd
./mvnw -pl modules/agent-memory/agent-memory-core -am package -DskipTests
# 产出：agent-memory-core/target/agent-memory-core-1.0.0.jar       （库，供依赖方）
#       agent-memory-core/target/agent-memory-core-1.0.0-exec.jar  （可独立运行）

# 2) Git Bash 下启动（Windows 也可）
cd modules/agent-memory/agent-memory-core
./run.sh
# 脚本已封装：unset SERVER__PORT、--spring.profiles.active=agentmemory、
#             --add-opens=java.base/java.nio=ALL-UNNAMED、--server.port

# 3) 冒烟：MCP 工具列表
curl --noproxy '*' -X POST http://127.0.0.1:9900/api/agent-memory/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# 期望：HTTP 200，响应含 "result" 与 tools 数组
```

- 若**不用 `run.sh`** 直接 `java -jar`，必须自己带
  `--spring.profiles.active=agentmemory`，否则独立运行配置不生效；同时清掉 `SERVER__PORT`
  （本机该变量会覆盖成别的端口）并加 `--add-opens=java.base/java.nio=ALL-UNNAMED`，否则 Arrow 初始化失败。
- `run.sh` 会把工作目录固定到 `agent-memory-core/`，因此 H2 / Arrow / 记忆文件稳定落在
  该目录的 `data/`（`agent-memory.mv.db`、`olap/l0_log.arrow`、`agent-memory-files/`）。
- 端口被占用或需与主应用并行时，后端与前端用**同一个**环境变量错开：
  `AGENT_MEMORY_PORT=9901 ./run.sh` + `AGENT_MEMORY_PORT=9901 npm run dev`。
- ⚠️ 独立应用与主应用**不要同时写同一个 H2 文件**。当前两者 CWD 不同，数据文件天然分开
  （独立应用用 `agent-memory-core/data/`，主应用用仓库根 `data/`）；若把
  `AGENT_MEMORY_H2_URL` 指到同一路径，必须只让其中一个进程写入。

### 前端（6060）

```bash
cd modules/agent-memory/frontend/agent-memory-ui
npm install
npm run dev        # http://localhost:6060
```

Vite 把 `/api` 代理到 `http://localhost:9900`（默认）；后端改端口时用 `AGENT_MEMORY_PORT` 对齐。
前端与主壳插件 `frontend/modules/agentmemory` 是两套并存实现，页面同名但互不引用，按上一轮决策**均保留**。

## 六、已知边界

- L0 为追加日志不删除（合规留痕）；OLAP 分析副本最多落后 1 分钟
- L2 场景块聚类当前仅建表未落地；embedding 使用 `PureJavaVectorUtil`（无外部模型依赖）
- H2 嵌入式存储为进程内（agent_runner 平台经批准的唯一豁免；其他模块请使用 MySQL）
