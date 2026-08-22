package com.zimo.module.agentmemory.storage;

import com.zimo.module.agentmemory.storage.spi.OlapStorageProvider;
import com.zimo.module.agentmemory.storage.spi.OltpStorageProvider;
import com.zimo.module.agentmemory.storage.spi.StorageContext;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 存储工厂：按配置（{@code agent-memory.oltp-engine} / {@code agent-memory.olap-engine}）
 * 从已注册的存储后端 SPI 中路由并创建对应仓储。
 *
 * <p>新增数据库实现步骤：实现对应 Provider 接口 → 注册为 Bean → 改配置切换，
 * 业务代码与既有实现零改动。配置引擎无匹配 Provider 时回退默认引擎并告警。</p>
 */
public class MemoryStorageFactory {

    private static final Logger log = LoggerFactory.getLogger(MemoryStorageFactory.class);

    /** OLTP 默认引擎（内置 H2）。 */
    public static final String DEFAULT_OLTP_ENGINE = "h2";
    /** OLAP 默认引擎（内置 Arrow）。 */
    public static final String DEFAULT_OLAP_ENGINE = "arrow";

    private final Map<String, OltpStorageProvider> oltpProviders;
    private final Map<String, OlapStorageProvider> olapProviders;
    private final StorageContext context;

    public MemoryStorageFactory(
            List<OltpStorageProvider> oltpProviders,
            List<OlapStorageProvider> olapProviders,
            StorageContext context) {
        this.oltpProviders = toMap(oltpProviders, OltpStorageProvider::engine);
        this.olapProviders = toMap(olapProviders, OlapStorageProvider::engine);
        this.context = context;
    }

    /** 按配置创建 OLTP 记忆仓储（未知引擎回退默认 h2）。 */
    public OltpMemoryRepository createOltp(String configuredEngine) {
        String engine = configuredEngine == null || configuredEngine.isBlank()
                ? DEFAULT_OLTP_ENGINE : configuredEngine;
        OltpStorageProvider provider = oltpProviders.get(engine);
        if (provider == null) {
            log.warn("未找到 OLTP 引擎 [{}]，回退默认 [{}]，已注册引擎：{}",
                    engine, DEFAULT_OLTP_ENGINE, oltpProviders.keySet());
            provider = oltpProviders.get(DEFAULT_OLTP_ENGINE);
        }
        if (provider == null) {
            throw new IllegalStateException("无可用 OLTP 存储 Provider");
        }
        return provider.create(withEngine(engine));
    }

    /** 按配置创建 OLAP 分析仓储（未知引擎回退默认 arrow）。 */
    public OlapAnalyticsRepository createOlap(String configuredEngine) {
        String engine = configuredEngine == null || configuredEngine.isBlank()
                ? DEFAULT_OLAP_ENGINE : configuredEngine;
        OlapStorageProvider provider = olapProviders.get(engine);
        if (provider == null) {
            log.warn("未找到 OLAP 引擎 [{}]，回退默认 [{}]，已注册引擎：{}",
                    engine, DEFAULT_OLAP_ENGINE, olapProviders.keySet());
            provider = olapProviders.get(DEFAULT_OLAP_ENGINE);
        }
        if (provider == null) {
            throw new IllegalStateException("无可用 OLAP 存储 Provider");
        }
        return provider.create(withEngine(engine));
    }

    private StorageContext withEngine(String engine) {
        return new StorageContext(
                engine,
                context.h2Url(),
                context.olapArrowDataFile(),
                context.dataSource());
    }

    private static <T> Map<String, T> toMap(List<T> providers, Function<T, String> keyExtractor) {
        return providers == null ? Map.of()
                : providers.stream().collect(Collectors.toMap(keyExtractor, Function.identity(),
                        (left, right) -> left, java.util.LinkedHashMap::new));
    }
}
