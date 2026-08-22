package com.zimo.module.agentmemory.storage.spi;

import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import com.zimo.module.agentmemory.storage.impl.H2OltpMemoryRepository;

/**
 * H2 MVStore OLTP 存储后端（内置默认实现，engine = {@code h2}）。
 *
 * <p>其他关系型数据库（MySQL 等）可参照本类实现 {@link OltpStorageProvider}
 * 并提供 SQL 方言实现后接入。</p>
 */
public class H2OltpStorageProvider implements OltpStorageProvider {

    @Override
    public String engine() {
        return "h2";
    }

    @Override
    public OltpMemoryRepository create(StorageContext context) {
        if (context.dataSource() == null) {
            throw new IllegalStateException("H2 OLTP 引擎需要 DataSource（agent-memory.h2-url 配置）");
        }
        return new H2OltpMemoryRepository(context.dataSource());
    }
}
