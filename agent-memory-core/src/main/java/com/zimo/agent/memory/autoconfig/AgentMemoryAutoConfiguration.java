package com.zimo.agent.memory.autoconfig;

import com.zaxxer.hikari.HikariDataSource;
import com.zimo.agent.memory.engine.*;
import com.zimo.agent.memory.memoryarch.MemoryArchConfig;
import com.zimo.agent.memory.AgentMemoryPluginRegister;
import com.zimo.agent.memory.analytics.MemoryAnalyticsService;
import com.zimo.agent.memory.governance.AccessCounter;
import com.zimo.agent.memory.governance.FactExtractor;
import com.zimo.agent.memory.governance.ImportanceScorer;
import com.zimo.agent.memory.governance.MemoryEvictor;
import com.zimo.agent.memory.governance.MemoryGovernanceController;
import com.zimo.agent.memory.governance.MemoryManager;
import com.zimo.agent.memory.governance.MemoryMerger;
import com.zimo.agent.memory.governance.MemoryMetrics;
import com.zimo.agent.memory.governance.MemoryScorer;
import com.zimo.agent.memory.mcp.MemoryMcpAccessGuard;
import com.zimo.agent.memory.mcp.MemoryMcpEndpoint;
import com.zimo.agent.memory.mcp.MemoryMcpToolkit;
import com.zimo.agent.memory.memory.AiMemoryController;
import com.zimo.agent.memory.memory.AiMemoryService;
import com.zimo.agent.memory.memoryarch.MemoryArchController;
import com.zimo.agent.memory.memoryarch.MemoryArchService;
import com.zimo.agent.memory.memoryarch.MemoryArchRepository;
import com.zimo.agent.memory.memoryfile.MemoryFileController;
import com.zimo.agent.memory.memoryfile.MemoryFileService;
import com.zimo.agent.memory.memory.MemorySecurityConfig;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import com.zimo.agent.memory.storage.MemoryStorageFacade;
import com.zimo.agent.memory.storage.OlapAnalyticsRepository;
import com.zimo.agent.memory.storage.OltpMemoryRepository;
import com.zimo.agent.memory.storage.spi.ArrowOlapStorageProvider;
import com.zimo.agent.memory.storage.spi.H2OltpStorageProvider;
import com.zimo.agent.memory.sync.AsyncLogSyncTask;
import com.zimo.module.ds.storage.StorageContext;
import com.zimo.module.ds.storage.StorageProvider;
import com.zimo.module.ds.storage.StorageRouter;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    /**
     * 重要性评估器（规则档，零模型调用）。
     *
     * <p>与写入路径共用同一个 {@link AiMemorySensitiveFilter} 实例，保证「脱敏」与
     * 「敏感降权」的口径一致：两处若各自持有一份规则集，迟早会出现「已脱敏但没降权」的错位。</p>
     */
    @Bean
    public ImportanceScorer importanceScorer(AiMemorySensitiveFilter aiMemorySensitiveFilter) {
        return new ImportanceScorer(aiMemorySensitiveFilter);
    }

    /**
     * 事实抽取器（规则档默认开，LLM 档默认关）。
     *
     * <p>配置把 LLM 档打开、而实现尚未接线时**必须吵一声**：这类「开关拨了但没生效」是最难
     * 察觉的失效形态 —— 日志里一切正常，只是抽取质量没有提升（决策 D2）。</p>
     */
    @Bean
    public FactExtractor factExtractor(
            ImportanceScorer importanceScorer, AgentMemoryProperties properties) {
        boolean llmEnabled = Boolean.TRUE.equals(properties.extract().llmEnabled());
        if (llmEnabled) {
            log.warn("agent-memory.extract.llm-enabled=true，但 LLM 档抽取尚未接线："
                    + "本次抽取仍按规则档执行，该开关当前不产生任何效果");
        }
        log.info("事实抽取器就绪：规则档启用，LLM 档 {}", llmEnabled ? "已配置开启但未生效" : "关闭");
        return new FactExtractor(importanceScorer, llmEnabled);
    }

    /** 智能体记忆服务（四层金字塔适配）。 */
    @Bean
    public AiMemoryService aiMemoryService(
            OltpMemoryRepository oltpMemoryRepository,
            AiMemorySensitiveFilter aiMemorySensitiveFilter,
            MemorySecurityConfig memorySecurityConfig,
            ImportanceScorer importanceScorer) {
        return new AiMemoryService(oltpMemoryRepository, aiMemorySensitiveFilter,
                memorySecurityConfig, importanceScorer);
    }

    /**
     * S1 混合打分器（召回与遗忘淘汰共用同一口径）。
     *
     * <p>参数非法时 Spring 会带着 {@link IllegalStateException} 直接终止启动 —— 这是刻意的：
     * 权重和不等于 1 时「服务能起来但所有分数都失去意义」，比启动失败更难排查（PRD §3.2）。</p>
     */
    @Bean
    public MemoryScorer memoryScorer(AgentMemoryProperties properties) {
        // 解包动作留在 autoconfig：core 层（governance.MemoryScorer）只认原始数值，
        // 不认识「配置对象」这一形态 —— 依赖方向必须是 autoconfig → core（架构铁律）。
        AgentMemoryProperties.Recall recall = properties.recall();
        MemoryScorer scorer = new MemoryScorer(
                recall.matchWeight(),
                recall.decayWeight(),
                recall.importanceWeight(),
                recall.halfLifeDays(),
                recall.topK(),
                recall.minScore());
        log.info("S1 记忆打分器就绪：权重[{}] 半衰期={}天 topK={} 注入阈值={}",
                properties.recall().weights(),
                scorer.halfLifeDays(),
                scorer.topK(),
                scorer.minScore());
        return scorer;
    }

    /**
     * 记忆合并器（近似条目归并）。
     *
     * <p>复用召回打分器的 {@code match} 做近似判定：让「什么算像」在召回与合并两处
     * 只有一个定义（PRD §3.1）。</p>
     */
    @Bean
    public MemoryMerger memoryMerger(MemoryScorer memoryScorer) {
        MemoryMerger merger = MemoryMerger.of(memoryScorer);
        log.info("记忆合并器就绪：近似阈值={}", MemoryMerger.DEFAULT_SIMILARITY_THRESHOLD);
        return merger;
    }

    /**
     * 遗忘淘汰器（与召回同一打分口径）。
     *
     * <p>复用同一个 {@link MemoryScorer} 实例而非按需新建 —— 两处若各自持有一份参数，
     * 「低分不注入」与「低分淘汰」迟早会变成两套互不相容的标准（PRD §7.3）。</p>
     */
    @Bean
    public MemoryEvictor memoryEvictor(MemoryScorer memoryScorer) {
        MemoryEvictor evictor = MemoryEvictor.of(memoryScorer);
        log.info("遗忘淘汰器就绪：最小闲置={}天 默认阈值={} 安全阀={}",
                MemoryEvictor.DEFAULT_MIN_IDLE_DAYS,
                MemoryEvictor.DEFAULT_THRESHOLD,
                MemoryEvictor.SAFETY_RATIO);
        return evictor;
    }

    /**
     * 记忆运行期指标累加器（进程内计数）。
     *
     * <p>重启即归零，因此 {@link MemoryMetrics.Snapshot#since()} 表示本进程启动时刻 ——
     * 这是调用方必须知道的语义，否则会把重启后的低值误读成业务量下滑。</p>
     */
    @Bean
    public MemoryMetrics memoryMetrics() {
        return new MemoryMetrics();
    }

    /**
     * 召回访问计数累加器。
     *
     * <p>召回只在进程内累加，由 {@link AgentMemorySyncAutoConfiguration} 的固定间隔调度
     * 批量落库（默认 30 秒）—— 让「计数更新不阻塞查询」成立（PRD §3.2）。</p>
     */
    @Bean
    public AccessCounter accessCounter() {
        return new AccessCounter();
    }

    /**
     * 记忆管理门面：把 CRUD、召回与六项治理能力收成一个入口（PRD §3.1）。
     *
     * <p>注入的 {@code MemoryScorer} / {@code MemoryMerger} / {@code MemoryEvictor}
     * 三个 bean 刻意共用同一个 {@code MemoryScorer} 实例：召回、合并近似判定、
     * 淘汰阈值必须是同一套口径，否则会退化成两套互不相容的标准。</p>
     */
    @Bean
    public MemoryManager memoryManager(
            AiMemoryService aiMemoryService,
            OltpMemoryRepository oltpMemoryRepository,
            MemoryScorer memoryScorer,
            FactExtractor factExtractor,
            MemoryMerger memoryMerger,
            MemoryEvictor memoryEvictor,
            MemoryMetrics memoryMetrics,
            AccessCounter accessCounter) {
        return new MemoryManager(aiMemoryService, oltpMemoryRepository, memoryScorer,
                factExtractor, memoryMerger, memoryEvictor, memoryMetrics, accessCounter);
    }

    /** 记忆管理接口（/api/ai/memory 兼容路径）。 */
    @Bean
    public AiMemoryController aiMemoryController(
            AiMemoryService aiMemoryService,
            MemorySecurityConfig memorySecurityConfig,
            AiMemorySensitiveFilter aiMemorySensitiveFilter) {
        return new AiMemoryController(aiMemoryService, memorySecurityConfig, aiMemorySensitiveFilter);
    }

    /**
     * 记忆治理接口（{@code /api/ai/memory} 下的 batch / recall / extract / merge / evict / metrics / span）。
     *
     * <p>与 {@link AiMemoryController} 共用前缀、路径互不重叠；治理端点只做协议转换，
     * 全部编排在 {@link MemoryManager} 内，便于单测同步断言。</p>
     */
    @Bean
    public MemoryGovernanceController memoryGovernanceController(
            MemoryManager memoryManager,
            MemorySpanRecorder memorySpanRecorder) {
        return new MemoryGovernanceController(memoryManager, memorySpanRecorder);
    }

    /**
     * 记忆 span 与审计记录器：本模块<b>唯一</b>发 {@code memory.*} span、写 {@code memory_span} 的地方。
     *
     * <p>埋点出口（{@link MemoryTraceSink}）由上层 {@code framework-ai} 提供 —— 本模块不能反向依赖它
     * （会与「framework-ai 依赖 agent-memory-core」构成循环）。因此这里用 {@code ObjectProvider} 取，
     * 取不到时只写审计表。</p>
     *
     * <p><b>取不到必须吵一声</b>：链路上没有 {@code memory.*} 属性这种失效，面板上表现为
     * 「记忆功能没启用」，而审计表里数据一切正常 —— 静默降级会让排查方向完全跑偏。</p>
     */
    @Bean
    public MemorySpanRecorder memorySpanRecorder(
            OltpMemoryRepository oltpMemoryRepository,
            org.springframework.beans.factory.ObjectProvider<
                    MemoryTraceSink> traceSinkProvider) {
        MemoryTraceSink sink = traceSinkProvider.getIfAvailable();
        if (sink == null) {
            log.warn("未发现 MemoryTraceSink 实现：记忆动作只写审计表、不发链路 span，"
                    + "链路里将看不到 memory.* 属性（正常部署下由 AiAgentAutoConfiguration 提供该 Bean）");
        } else {
            log.info("记忆埋点通道就绪：记忆动作将同时发链路 span 并写 memory_span 审计表");
        }
        return new MemorySpanRecorder(oltpMemoryRepository, sink);
    }

    /**
     * 事实抽取异步回写队列（PRD §7.2）。
     *
     * <p>回复返回后才做抽取落库，不占用用户可感知的时延；结果写审计表而<b>不</b>发 span，
     * 因为此时链路已收尾（详见 {@code MemorySpanRecorder} 与 {@code MemoryWritebackQueue} 的类注释）。</p>
     */
    @Bean(destroyMethod = "close")
    public MemoryWritebackQueue memoryWritebackQueue(
            MemoryManager memoryManager,
            MemorySpanRecorder memorySpanRecorder) {
        return new MemoryWritebackQueue(memoryManager,
                memorySpanRecorder);
    }

    /**
     * 记忆感知 Prompt 增强器（Push 召回：推理前主动注入，PRD §3.3）。
     *
     * <p>注入失败/召回失败/队列满都不阻断对话，但每一处都打 WARN —— 记忆是增强能力，
     * 不是对话的前置条件。</p>
     */
    @Bean
    public MemoryAwarePromptBuilder memoryAwarePromptBuilder(
            MemoryManager memoryManager,
            MemorySpanRecorder memorySpanRecorder,
            MemoryWritebackQueue memoryWritebackQueue,
            OltpMemoryRepository oltpMemoryRepository,
            PersonaFragmentComposer personaFragmentComposer,
            AgentMemoryProperties properties) {
        boolean enabled = properties.engine().enabledOrDefault();
        if (enabled) {
            log.info("记忆感知引擎已启用：推理前预召回并注入片段，回复后异步回写事实");
        } else {
            log.warn("记忆感知引擎已被配置关闭（agent-memory.engine.enabled=false）："
                    + "不预召回、不注入、不写 L0 留痕、不入回写队列");
        }
        return new MemoryAwarePromptBuilder(memoryManager,
                memorySpanRecorder, memoryWritebackQueue, oltpMemoryRepository, enabled,
                personaFragmentComposer);
    }

    /**
     * L3 用户画像片段组装器（跨会话稳定画像注入）。
     *
     * <p>与 L1 预召回并列装配：L1 走打分召回（每轮变动），L3 按类别精确查画像表（多轮不变）。
     * 两者共用同一个注入点，但各自独立降级 —— 画像读不到不影响召回，反之亦然。</p>
     */
    @Bean
    public PersonaFragmentComposer personaFragmentComposer(
            OltpMemoryRepository oltpMemoryRepository, AgentMemoryProperties properties) {
        AgentMemoryProperties.Persona persona = properties.persona();
        PersonaInjection injection =
                new PersonaInjection(
                        persona.enabledOrDefault(), persona.typesOrDefault(),
                        persona.maxCharsOrDefault());
        boolean engineEnabled = properties.engine().enabledOrDefault();
        if (engineEnabled && injection.enabled()) {
            log.info("L3 画像注入已启用：类别={} 上限={}字符", injection.types(), injection.maxChars());
        } else {
            log.info("L3 画像注入未启用（engine={} persona={}）", engineEnabled, injection.enabled());
        }
        return new PersonaFragmentComposer(
                oltpMemoryRepository, injection, engineEnabled);
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
            repo.insert(new MemoryArchConfig(
                    cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 16),
                    "USER", r[0], r[1], r[2], r[1] + "。" + r[2],
                    "自动", 1, ts));
        }
        // SOUL 配置：2 条
        repo.insert(new MemoryArchConfig(
                cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 16),
                "SOUL", "SOUL-代码助手",
                "严谨的代码工程师：技术精湛、逻辑缜密、追求代码质量",
                "面向开发者的 AI 工程师身份配置：偏好响应精确、可运行、有测试",
                "严谨的代码工程师：技术精湛、逻辑缜密、追求代码质量。面向开发者的 AI 工程师身份配置：偏好响应精确、可运行、有测试。",
                "手动", 1, ts));
        repo.insert(new MemoryArchConfig(
                cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 16),
                "SOUL", "SOUL-行政助理",
                "细心高效的行政助理：偏好周报邮件与采购审批",
                "面向行政助理的 AI 身份配置：业务背景采购、偏好周报、待办跟踪",
                "细心高效的行政助理：细心高效、响应规范。面向行政助理的 AI 身份配置：业务背景采购、偏好周报、待办跟踪。",
                "手动", 1, ts));
    }

    /**
     * 文件兼容模式服务（{baseDir}/{agentId}/MEMORY.md，OpenClaw 互通；框架 RocksDB 存储存在时记忆文件落 RocksDB）。
     *
     * <p>根目录的取值优先级：{@code ./data/agent-memory/file-base-dir.override}
     * （设置面板「内存管理」保存时写入）> {@code agent-memory.file-base-dir}（application.yml）
     * > 内置默认 {@code ./data/agent-memory-files}。</p>
     */
    @Bean
    public MemoryFileService memoryFileService(
            AgentMemoryProperties properties,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    com.zimo.framework.common.storage.FileStorageService fileStorageService) {
        String configured = properties.fileBaseDir();
        java.nio.file.Path override = MemoryFileService.overrideFile();
        if (java.nio.file.Files.exists(override)) {
            try {
                String fromOverride = java.nio.file.Files.readString(override).trim();
                if (!fromOverride.isEmpty()) {
                    configured = fromOverride;
                }
            } catch (java.io.IOException ignored) {
                // 覆盖文件读不出来就退回 application.yml，不让启动挂在一行配置上
            }
        }
        return new MemoryFileService(java.nio.file.Paths.get(configured), fileStorageService);
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

    /** 记忆 MCP 端点准入校验器（令牌取 {@code agent-memory.mcp-token}，为空则仅放行回环来源）。 */
    @Bean
    public MemoryMcpAccessGuard memoryMcpAccessGuard(AgentMemoryProperties properties) {
        MemoryMcpAccessGuard guard = new MemoryMcpAccessGuard(properties.mcpToken());
        if (guard.tokenConfigured()) {
            log.info("记忆 MCP 端点已启用令牌鉴权（/api/agent-memory/mcp）");
        } else {
            log.warn("记忆 MCP 端点未配置 agent-memory.mcp-token，仅放行回环来源访问；"
                    + "如需跨机访问请注入 AGENT_MEMORY_TOKEN");
        }
        return guard;
    }

    /** 记忆 MCP 接入端点（/api/agent-memory/mcp，HTTP JSON-RPC，WorkBuddy mcp.json 注册）。 */
    @Bean
    public MemoryMcpEndpoint memoryMcpEndpoint(MemoryMcpToolkit memoryMcpToolkit,
            MemoryMcpAccessGuard memoryMcpAccessGuard) {
        return new MemoryMcpEndpoint(memoryMcpToolkit, memoryMcpAccessGuard);
    }

    /** OLAP 分析接口（/api/agent-memory/analytics）。 */
    @Bean
    public AgentMemoryAnalyticsController agentMemoryAnalyticsController(
            MemoryAnalyticsService memoryAnalyticsService,
            MemoryFileService memoryFileService) {
        return new AgentMemoryAnalyticsController(memoryAnalyticsService, memoryFileService);
    }

    /** 异步日志同步 ETL 任务（后台线程，非阻塞主链路）。 */
    @Bean
    public AsyncLogSyncTask asyncLogSyncTask(
            OltpMemoryRepository oltpMemoryRepository,
            OlapAnalyticsRepository olapAnalyticsRepository) {
        return new AsyncLogSyncTask(oltpMemoryRepository, olapAnalyticsRepository);
    }

    /**
     * 智能体记忆插件身份：进入 {@code GET /api/plugins}，并对齐前端 agentmemory 模块。
     *
     * <p>此处用 {@code @ConditionalOnMissingBean} 与本次其余 5 个模块保持一致（本类其余 Bean
     * 是模块内部固定组件，不对外开放覆盖点，故未加该注解）。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentMemoryPluginRegister agentMemoryPluginRegister() {
        return new AgentMemoryPluginRegister();
    }
}
