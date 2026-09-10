package com.zimo.module.agentmemory.storage.spi;

import com.zimo.module.agentmemory.analytics.impl.ArrowOlapAnalyticsRepository;
import com.zimo.module.agentmemory.storage.OlapAnalyticsRepository;
import com.zimo.module.ds.storage.StorageContext;
import com.zimo.module.ds.storage.StorageProvider;

/**
 * Arrow + Calcite OLAP 存储后端（内置默认实现，engine = {@code arrow}，纯 Java 无 JNI）。
 *
 * <p>通用存储 SPI（{@link StorageProvider}）在本模块的 OLAP 落地：纯文件型引擎，
 * 只消费 {@link StorageContext#dataFile()}，不依赖数据源。其他 OLAP 引擎（DuckDB 等）
 * 可参照本类实现后通过 {@code agent-memory.olap-engine=duckdb} 切换。</p>
 */
public class ArrowOlapStorageProvider implements StorageProvider<OlapAnalyticsRepository> {

    @Override
    public String engine() {
        return "arrow";
    }

    @Override
    public OlapAnalyticsRepository create(StorageContext context) {
        return new ArrowOlapAnalyticsRepository(context.dataFile());
    }
}
