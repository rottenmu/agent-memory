package com.zimo.module.agentmemory.storage.spi;

import java.nio.file.Path;
import javax.sql.DataSource;

/**
 * 存储装配上下文：承载底层实现创建所需的依赖与配置，屏蔽各存储后端的装配差异。
 *
 * <p>存储后端 Provider（{@link OltpStorageProvider} / {@link OlapStorageProvider}）
 * 只依赖本上下文，不感知 Spring 容器与配置类细节，便于 SPI 独立实现与测试。</p>
 *
 * @param engine          本后端引擎名（如 h2 / arrow / duckdb / mysql）
 * @param h2Url           OLTP JDBC URL（H2 等 JDBC 引擎使用）
 * @param olapArrowDataFile OLAP 数据文件路径（Arrow 引擎使用）
 * @param dataSource      OLTP 数据源（JDBC 引擎使用；非 JDBC 引擎可为 {@code null}）
 */
public record StorageContext(
        String engine,
        String h2Url,
        String olapArrowDataFile,
        DataSource dataSource) {

    /** OLAP 数据文件路径（Arrow 引擎使用）。 */
    public Path olapDataFile() {
        return Path.of(olapArrowDataFile);
    }
}
