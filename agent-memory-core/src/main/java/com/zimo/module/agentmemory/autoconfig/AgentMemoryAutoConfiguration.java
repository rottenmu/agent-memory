package com.zimo.module.agentmemory.autoconfig;

import com.zaxxer.hikari.HikariDataSource;
import com.zimo.module.agentmemory.analytics.MemoryAnalyticsService;
import com.zimo.module.agentmemory.mcp.MemoryMcpEndpoint;
import com.zimo.module.agentmemory.mcp.MemoryMcpToolkit;
import com.zimo.module.agentmemory.memory.AiMemoryController;
import com.zimo.module.agentmemory.memory.AiMemoryService;
import com.zimo.module.agentmemory.memoryarch.MemoryArchController;
import com.zimo.module.agentmemory.memoryarch.MemoryArchService;
import com.zimo.module.agentmemory.memoryarch.MemoryArchRepository;
import com.zimo.module.agentmemory.memoryfile.MemoryFileController;
import com.zimo.module.agentmemory.memoryfile.MemoryFileService;
import com.zimo.module.agentmemory.memory.MemorySecurityConfig;
import com.zimo.module.agentmemory.security.AiMemorySensitiveFilter;
import com.zimo.module.agentmemory.storage.MemoryStorageFacade;
import com.zimo.module.agentmemory.storage.OlapAnalyticsRepository;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import com.zimo.module.agentmemory.storage.spi.ArrowOlapStorageProvider;
import com.zimo.module.agentmemory.storage.spi.H2OltpStorageProvider;
import com.zimo.module.agentmemory.sync.AsyncLogSyncTask;
import com.zimo.module.ds.storage.StorageContext;
import com.zimo.module.ds.storage.StorageProvider;
import com.zimo.module.ds.storage.StorageRouter;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 四层记忆系统自动装配。
 *
 * <p>装配内容：H2 MVStore OLTP 数据源与仓储、Arrow+Calcite OLAP 仓储、
 * 分析服务、异步 ETL 调度（{@code @Scheduled}，默认每分钟同步一次）。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentMemoryProperties.class)
public class AgentMemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryAutoConfiguration.class);

    /** H2 OLTP 数据源（MVStore 文件库，module-agent-memory 专用豁免）。 */
    @Bean
    public DataSource agentMemoryDataSource(AgentMemoryProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.h2Url());
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        dataSource.setPoolName("agent-memory-h2");
        return dataSource;
    }

    /**
     * 存储装配上下文（通用 SPI，位于 agent-datasource 的 module-datasource-storage）。
     *
     * <p>同时承载 JDBC 类后端（{@code jdbcUrl} + {@code dataSource}）与文件类后端
     * （{@code dataFilePath}），由各 Provider 按需取用。</p>
     */
    @Bean
    public StorageContext memoryStorageContext(
            AgentMemoryProperties properties, DataSource agentMemoryDataSource) {
        return new StorageContext(
                AgentMemoryProperties.DEFAULT_OLTP_ENGINE,
                properties.h2Url(),
                properties.olapArrowDataFile(),
                agentMemoryDataSource);
    }

    /** 内置 OLTP 存储后端：H2 MVStore（engine=h2）。 */
    @Bean
    public StorageProvider<OltpMemoryRepository> h2OltpStorageProvider() {
        return new H2OltpStorageProvider();
    }

    /** 内置 OLAP 存储后端：Arrow + Calcite（engine=arrow，纯 Java 无 JNI）。 */
    @Bean
    public StorageProvider<OlapAnalyticsRepository> arrowOlapStorageProvider() {
        return new ArrowOlapStorageProvider();
    }

    /**
     * OLTP 记忆仓储：按 {@code agent-memory.oltp-engine} 路由到对应后端，承担运行时记忆 CRUD 与钻取召回。
     *
     * <p>路由直接委托通用 {@link StorageRouter}（原先多包的一层 {@code MemoryStorageFactory} 已移除）：
     * 配置为空取默认引擎，配置未注册则告警并回退默认，且传给 Provider 的是**实际生效**的引擎名。</p>
     */
    @Bean
    public OltpMemoryRepository oltpMemoryRepository(
            List<StorageProvider<OltpMemoryRepository>> oltpProviders,
            StorageContext memoryStorageContext,
            AgentMemoryProperties properties) {
        return StorageRouter.route(
                oltpProviders,
                properties.oltpEngine(),
                AgentMemoryProperties.DEFAULT_OLTP_ENGINE,
                memoryStorageContext,
                "OLTP");
    }

    /** OLAP 分析仓储：按 {@code agent-memory.olap-engine} 路由，仅后台分析使用。 */
    @Bean
    public OlapAnalyticsRepository olapAnalyticsRepository(
            List<StorageProvider<OlapAnalyticsRepository>> olapProviders,
            StorageContext memoryStorageContext,
            AgentMemoryProperties properties) {
        return StorageRouter.route(
                olapProviders,
                properties.olapEngine(),
                AgentMemoryProperties.DEFAULT_OLAP_ENGINE,
                memoryStorageContext,
                "OLAP");
    }

    /** 记忆分析服务。 */
    @Bean
    public MemoryAnalyticsService memoryAnalyticsService(
            OlapAnalyticsRepository olapAnalyticsRepository) {
        return new MemoryAnalyticsService(olapAnalyticsRepository);
    }

    /** 存储门面：业务层只依赖门面，不感知底层实现。 */
    @Bean
    public MemoryStorageFacade memoryStorageFacade(
            OltpMemoryRepository oltpMemoryRepository,
            OlapAnalyticsRepository olapAnalyticsRepository) {
        return new MemoryStorageFacade() {
            @Override
            public OltpMemoryRepository oltp() {
                return oltpMemoryRepository;
            }

            @Override
            public OlapAnalyticsRepository olap() {
                return olapAnalyticsRepository;
            }
        };
    }

    /** 记忆安全配置（白名单类别 + 敏感过滤开关）。 */
    @Bean
    public MemorySecurityConfig memorySecurityConfig(AgentMemoryProperties properties) {
        List<String> whitelist = properties.whitelistCategories() == null
                ? List.of()
                : Arrays.stream(properties.whitelistCategories().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new MemorySecurityConfig(whitelist, properties.sensitiveFiltering());
    }

    /** 记忆敏感内容过滤器。 */
    @Bean
    public AiMemorySensitiveFilter aiMemorySensitiveFilter() {
        return new AiMemorySensitiveFilter();
    }

    /** 智能体记忆服务（四层金字塔适配）。 */
    @Bean
    public AiMemoryService aiMemoryService(
            OltpMemoryRepository oltpMemoryRepository,
            AiMemorySensitiveFilter aiMemorySensitiveFilter,
            MemorySecurityConfig memorySecurityConfig) {
        return new AiMemoryService(oltpMemoryRepository, aiMemorySensitiveFilter, memorySecurityConfig);
    }

    /** 记忆管理接口（/api/ai/memory 兼容路径）。 */
    @Bean
    public AiMemoryController aiMemoryController(
            AiMemoryService aiMemoryService,
            MemorySecurityConfig memorySecurityConfig,
            AiMemorySensitiveFilter aiMemorySensitiveFilter) {
        return new AiMemoryController(aiMemoryService, memorySecurityConfig, aiMemorySensitiveFilter);
    }

    /** 记忆分层架构仓储（H2 JdbcTemplate 复用，模块自洽）。 */
    @Bean
    public MemoryArchRepository memoryArchRepository(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new MemoryArchRepository(jdbc);
    }

    /** 记忆分层架构服务。 */
    @Bean
    public MemoryArchService memoryArchService(
            MemoryArchRepository memoryArchRepository,
            OltpMemoryRepository oltpMemoryRepository) {
        MemoryArchService service = new MemoryArchService(memoryArchRepository, oltpMemoryRepository);
        seedArchIfEmpty(memoryArchRepository);
        return service;
    }

    /** 记忆分层架构控制器。 */
    @Bean
    public MemoryArchController memoryArchController(MemoryArchService memoryArchService) {
        return new MemoryArchController(memoryArchService);
    }

    /** 种子数据：表为空时写入示例（5 卡片统计非零 + 列表有数据）。 */
    private void seedArchIfEmpty(MemoryArchRepository repo) {
        if (repo.countAll() > 0) {
            return;
        }
        cn.hutool.core.util.IdUtil.fastSimpleUUID();
        long ts = System.currentTimeMillis();
        String[][] userRows = {
            {"user-001", "关注代码质量与工时数据的技术管理者", "可能从事软件开发或项目管理，涉及前后端"},
            {"user-002", "测试工程师，排查流程画布保存前端传参问题", "测试人员，负责流程画布相关功能的 bug 排查"},
            {"user-003", "运维或数字化工厂建设相关人员", "从事 IT 运维或数字化工厂相关领域"},
            {"user-004", "事运维、集成、可视化、物联网平台相关技术", "售前技术员或平台架构师"},
            {"user-005", "产品原型/PRD 设计者，长期围绕产品全链路", "从事产品设计、原型制作或需求文档编写"},
            {"user-006", "产品原型设计或需求方，关注制造业执行 MES", "制造业信息化或生产管理相关工作"},
            {"user-007", "后端开发，数据库表字段溯源与 Feign 接口调试", "后端开发，负责设备管理或物联网相关"},
            {"user-008", "数据展示与验证系统开发者，注重真实与性能", "从事工业物联网、质量追溯或异常检测"},
            {"user-009", "后端或全栈工程师，关注企业级 MES 制造执行", "具有多年 Java/Python 经验的开发"},
            {"user-010", "产品原型设计需求管理场景操作者", "产品原型或界面设计相关"},
            {"user-011", "测试工程师，关注标准 3.0 系统功能测试", "从事软件测试，涉及服务端监控、JV..."},
        };
        for (String[] r : userRows) {
            repo.insert(new com.zimo.module.agentmemory.memoryarch.MemoryArchConfig(
                    cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 16),
                    "USER", r[0], r[1], r[2], r[1] + "。" + r[2],
                    "自动", 1, ts));
        }
        // SOUL 配置：2 条
        repo.insert(new com.zimo.module.agentmemory.memoryarch.MemoryArchConfig(
                cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 16),
                "SOUL", "SOUL-代码助手",
                "严谨的代码工程师：技术精湛、逻辑缜密、追求代码质量",
                "面向开发者的 AI 工程师身份配置：偏好响应精确、可运行、有测试",
                "严谨的代码工程师：技术精湛、逻辑缜密、追求代码质量。面向开发者的 AI 工程师身份配置：偏好响应精确、可运行、有测试。",
                "手动", 1, ts));
        repo.insert(new com.zimo.module.agentmemory.memoryarch.MemoryArchConfig(
                cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 16),
                "SOUL", "SOUL-行政助理",
                "细心高效的行政助理：偏好周报邮件与采购审批",
                "面向行政助理的 AI 身份配置：业务背景采购、偏好周报、待办跟踪",
                "细心高效的行政助理：细心高效、响应规范。面向行政助理的 AI 身份配置：业务背景采购、偏好周报、待办跟踪。",
                "手动", 1, ts));
    }

    /** 文件兼容模式服务（{baseDir}/{agentId}/MEMORY.md，OpenClaw 互通；框架 RocksDB 存储存在时记忆文件落 RocksDB）。 */
    @Bean
    public MemoryFileService memoryFileService(
            AgentMemoryProperties properties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    com.zimo.framework.common.storage.FileStorageService fileStorageService) {
        return new MemoryFileService(java.nio.file.Paths.get(properties.fileBaseDir()), fileStorageService);
    }

    /** 文件兼容模式 REST 接口（/api/agent-memory/file/**，4 个）。 */
    @Bean
    public MemoryFileController memoryFileController(MemoryFileService memoryFileService) {
        return new MemoryFileController(memoryFileService);
    }

    /** 记忆 MCP 工具集（WorkBuddy 深度接管：memory_write/read/delete/search）。 */
    @Bean
    public MemoryMcpToolkit memoryMcpToolkit(AiMemoryService aiMemoryService) {
        return new MemoryMcpToolkit(aiMemoryService);
    }

    /** 记忆 MCP 接入端点（/api/agent-memory/mcp，HTTP JSON-RPC，WorkBuddy mcp.json 注册）。 */
    @Bean
    public MemoryMcpEndpoint memoryMcpEndpoint(MemoryMcpToolkit memoryMcpToolkit) {
        return new MemoryMcpEndpoint(memoryMcpToolkit);
    }

    /** OLAP 分析接口（/api/agent-memory/analytics）。 */
    @Bean
    public AgentMemoryAnalyticsController agentMemoryAnalyticsController(
            MemoryAnalyticsService memoryAnalyticsService,
            com.zimo.module.agentmemory.memoryfile.MemoryFileService memoryFileService) {
        return new AgentMemoryAnalyticsController(memoryAnalyticsService, memoryFileService);
    }

    /** 异步日志同步 ETL 任务（后台线程，非阻塞主链路）。 */
    @Bean
    public AsyncLogSyncTask asyncLogSyncTask(
            OltpMemoryRepository oltpMemoryRepository,
            OlapAnalyticsRepository olapAnalyticsRepository) {
        return new AsyncLogSyncTask(oltpMemoryRepository, olapAnalyticsRepository);
    }
}
