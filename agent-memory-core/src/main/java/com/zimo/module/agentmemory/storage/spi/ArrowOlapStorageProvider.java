package com.zimo.module.agentmemory.storage.spi;

import com.zimo.module.agentmemory.analytics.impl.ArrowOlapAnalyticsRepository;
import com.zimo.module.agentmemory.storage.OlapAnalyticsRepository;

/**
 * Arrow + Calcite OLAP 存储后端（内置默认实现，engine = {@code arrow}，纯 Java 无 JNI）。
 *
 * <p>其他 OLAP 引擎（DuckDB 等）可参照本类实现 {@link OlapStorageProvider}
 * 后通过 {@code agent-memory.olap-engine=duckdb} 切换。</p>
 */
public class ArrowOlapStorageProvider implements OlapStorageProvider {

    @Override
    public String engine() {
        return "arrow";
    }

    @Override
    public OlapAnalyticsRepository create(StorageContext context) {
        return new ArrowOlapAnalyticsRepository(context.olapDataFile());
    }
}
