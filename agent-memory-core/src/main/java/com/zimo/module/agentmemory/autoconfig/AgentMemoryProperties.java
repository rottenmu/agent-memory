package com.zimo.module.agentmemory.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * module-agent-memory 配置属性。
 *
 * @param h2Url             H2 OLTP 数据库 JDBC URL（MVStore 文件模式）
 * @param olapArrowDataFile OLAP Arrow IPC 数据文件路径
 * @param syncCron          ETL 定时同步 cron 表达式
 * @param whitelistCategories 记忆类别白名单（逗号分隔）；为空表示放行全部
 * @param sensitiveFiltering 记忆敏感内容过滤开关
 * @param oltpEngine        OLTP 存储引擎（存储后端 SPI 路由）：h2（默认）/ mysql / rocksdb 等
 * @param olapEngine        OLAP 分析引擎（存储后端 SPI 路由）：arrow（默认）/ duckdb 等
 * @param fileBaseDir       文件兼容模式记忆根目录（{baseDir}/{agentId}/MEMORY.md）
 */
@ConfigurationProperties(prefix = "agent-memory")
public record AgentMemoryProperties(
        String h2Url,
        String olapArrowDataFile,
        String syncCron,
        String whitelistCategories,
        boolean sensitiveFiltering,
        String oltpEngine,
        String olapEngine,
        String fileBaseDir) {

    /** 默认 H2 文件库（MVStore，数据落盘 data/agent-memory）。 */
    public static final String DEFAULT_H2_URL = "jdbc:h2:file:./data/agent-memory;MODE=LEGACY";
    /** 默认 OLAP Arrow IPC 文件。 */
    public static final String DEFAULT_OLAP_FILE = "./data/olap/l0_log.arrow";
    /** 默认同步间隔：每分钟。 */
    public static final String DEFAULT_SYNC_CRON = "0 */1 * * * ?";

    public AgentMemoryProperties {
        if (h2Url == null || h2Url.isBlank()) {
            h2Url = DEFAULT_H2_URL;
        }
        if (olapArrowDataFile == null || olapArrowDataFile.isBlank()) {
            olapArrowDataFile = DEFAULT_OLAP_FILE;
        }
        if (syncCron == null || syncCron.isBlank()) {
            syncCron = DEFAULT_SYNC_CRON;
        }
        if (oltpEngine == null || oltpEngine.isBlank()) {
            oltpEngine = "h2";
        }
        if (olapEngine == null || olapEngine.isBlank()) {
            olapEngine = "arrow";
        }
        if (fileBaseDir == null || fileBaseDir.isBlank()) {
            fileBaseDir = "./data/agent-memory-files";
        }
    }
}
