package com.zimo.module.agentmemory.storage.spi;

import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import com.zimo.module.agentmemory.storage.impl.H2OltpMemoryRepository;
import com.zimo.module.ds.storage.StorageContext;
import com.zimo.module.ds.storage.StorageProvider;

/**
 * H2 MVStore OLTP 存储后端（内置默认实现，engine = {@code h2}）。
 *
 * <p>通用存储 SPI（{@link StorageProvider}）在本模块的 OLTP 落地：把 H2 数据源包装成
 * {@link OltpMemoryRepository}。其他关系型数据库（MySQL 等）可参照本类提供 SQL 方言实现后接入。</p>
 */
public class H2OltpStorageProvider implements StorageProvider<OltpMemoryRepository> {

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
