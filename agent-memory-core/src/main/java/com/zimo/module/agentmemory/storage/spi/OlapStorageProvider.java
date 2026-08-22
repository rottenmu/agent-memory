package com.zimo.module.agentmemory.storage.spi;

import com.zimo.module.agentmemory.storage.OlapAnalyticsRepository;

/**
 * OLAP 分析存储后端 SPI：将"离线分析仓储"实现插拔化。
 *
 * <p>接入新的 OLAP 引擎（DuckDB 等）只需：</p>
 * <ol>
 *   <li>实现本接口并声明 {@link #engine()}（如 {@code "duckdb"}）；</li>
 *   <li>将实现注册为 Spring Bean（或加入 Provider 集合）；</li>
 *   <li>配置 {@code agent-memory.olap-engine=duckdb} 切换。</li>
 * </ol>
 * <p>当前内置 {@code arrow}（Arrow + Calcite 纯 Java 无 JNI）实现；
 * 分析服务与 ETL 只依赖 {@link OlapAnalyticsRepository} 接口。</p>
 */
public interface OlapStorageProvider {

    /** 引擎名（配置 {@code agent-memory.olap-engine} 匹配值，如 arrow / duckdb）。 */
    String engine();

    /**
     * 创建 OLAP 分析仓储实例。
     *
     * @param context 装配上下文（含数据文件路径、配置）
     * @return 仓储实例
     */
    OlapAnalyticsRepository create(StorageContext context);
}
