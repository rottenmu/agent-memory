package com.zimo.module.agentmemory.autoconfig;

import com.zimo.module.agentmemory.sync.AsyncLogSyncTask;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 记忆 ETL 调度自动装配（与主装配分离，避免与 aiHarnessAgentFactory 成环）。
 *
 * <p>仅在 AsyncLogSyncTask bean 存在时生效；调度在后台线程执行，
 * 非阻塞主对话链路。</p>
 */
@AutoConfiguration
@EnableScheduling
public class AgentMemorySyncAutoConfiguration {

    private final AsyncLogSyncTask asyncLogSyncTask;

    public AgentMemorySyncAutoConfiguration(AsyncLogSyncTask asyncLogSyncTask) {
        this.asyncLogSyncTask = asyncLogSyncTask;
    }

    /** ETL 定时调度：按配置 cron 增量同步 L0 日志到 OLAP。 */
    @Scheduled(cron = "${agent-memory.sync-cron:0 */1 * * * ?}")
    public void scheduledLogSync() {
        asyncLogSyncTask.run();
    }
}
