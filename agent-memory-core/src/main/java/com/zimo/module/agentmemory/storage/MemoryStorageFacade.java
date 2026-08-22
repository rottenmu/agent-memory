package com.zimo.module.agentmemory.storage;

/**
 * 记忆存储门面：统一业务层与底层数据库实现的边界。
 *
 * <p>业务（记忆抽取、聚类召回）只依赖本接口，不感知底层是 H2 MVStore
 * 还是 RocksDB 还是未来其他实现；同时按读写负载分离原则区分两个子仓储：</p>
 * <ul>
 *   <li>{@link OltpMemoryRepository}：运行时记忆 CRUD 与钻取召回（仅 OLTP 主库）</li>
 *   <li>{@link OlapAnalyticsRepository}：后台离线分析（仅 OLAP 副库，不承担运行时查询）</li>
 * </ul>
 */
public interface MemoryStorageFacade {

    /** 运行时记忆仓储（H2 OLTP，承担 Agent 推理召回）。 */
    OltpMemoryRepository oltp();

    /** 离线分析仓储（Arrow OLAP，仅后台异步分析使用）。 */
    OlapAnalyticsRepository olap();
}
