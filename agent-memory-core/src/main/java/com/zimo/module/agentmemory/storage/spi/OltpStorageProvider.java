package com.zimo.module.agentmemory.storage.spi;

import com.zimo.module.agentmemory.storage.OltpMemoryRepository;

/**
 * OLTP 存储后端 SPI：将"运行时记忆仓储"实现插拔化。
 *
 * <p>接入新的 OLTP / 关系型数据库（MySQL、RocksDB 等）只需：</p>
 * <ol>
 *   <li>实现本接口并声明 {@link #engine()}（如 {@code "mysql"}）；</li>
 *   <li>将实现注册为 Spring Bean（或加入 Provider 集合）；</li>
 *   <li>配置 {@code agent-memory.oltp-engine=mysql} 切换。</li>
 * </ol>
 * <p>业务代码（AiMemoryService / 工具 / 门面）只依赖 {@link OltpMemoryRepository}，
 * 与底层数据库实现完全解耦。</p>
 */
public interface OltpStorageProvider {

    /** 引擎名（配置 {@code agent-memory.oltp-engine} 匹配值，如 h2 / mysql / rocksdb）。 */
    String engine();

    /**
     * 创建 OLTP 记忆仓储实例。
     *
     * @param context 装配上下文（含数据源、配置）
     * @return 仓储实例
     */
    OltpMemoryRepository create(StorageContext context);
}
