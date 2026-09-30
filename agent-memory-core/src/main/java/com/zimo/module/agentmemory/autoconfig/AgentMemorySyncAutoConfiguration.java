package com.zimo.module.agentmemory.autoconfig;

import com.zimo.module.agentmemory.governance.AccessCounter;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import com.zimo.module.agentmemory.sync.AsyncLogSyncTask;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 记忆 ETL 调度自动装配（与主装配分离，避免与 aiHarnessAgentFactory 成环）。
 *
 * <p>仅在 AsyncLogSyncTask bean 存在时生效；调度在后台线程执行，
 * 非阻塞主对话链路。</p>
 *
 * <p>另承载「召回访问计数落库」调度：计数在请求线程只做进程内累加，由这里按固定间隔
 * 批量落库（PRD §3.2「计数更新不阻塞查询」）。这里刻意<b>不</b>注入 {@code MemoryManager}，
 * 只注入它落库真正需要的两个 bean（累加器 + 仓储）—— 本类被拆出来正是为了规避
 * 装配成环，注入一个更大的依赖图会把刚拆掉的风险重新引回来。</p>
 */
@AutoConfiguration
@EnableScheduling
public class AgentMemorySyncAutoConfiguration {

    private final AsyncLogSyncTask asyncLogSyncTask;
    private final AccessCounter accessCounter;
    private final OltpMemoryRepository oltpMemoryRepository;

    /**
     * @param asyncLogSyncTask     ETL 同步任务
     * @param accessCounter        召回访问计数累加器
     * @param oltpMemoryRepository OLTP 仓储（计数落库目标）
     */
    public AgentMemorySyncAutoConfiguration(
            AsyncLogSyncTask asyncLogSyncTask,
            AccessCounter accessCounter,
            OltpMemoryRepository oltpMemoryRepository) {
        this.asyncLogSyncTask = asyncLogSyncTask;
        this.accessCounter = accessCounter;
        this.oltpMemoryRepository = oltpMemoryRepository;
    }

    /** ETL 定时调度：按配置 cron 增量同步 L0 日志到 OLAP。 */
    @Scheduled(cron = "${agent-memory.sync-cron:0 */1 * * * ?}")
    public void scheduledLogSync() {
        asyncLogSyncTask.run();
    }

    /**
     * 召回访问计数落库：默认每 30 秒一次。
     *
     * <p>为什么是 30 秒：{@code access_count} / {@code last_access_at} 只服务于衰减参考
     * 与「哪些记忆真的被用上」的判断，30 秒的粒度足够；间隔越长，进程崩溃时丢的计数越多。
     * 用 {@code fixedDelay} 而不是 cron：前一次落库耗时不该与下一次重叠，避免同一条目
     * 被两个调度线程同时更新。</p>
     */
    @Scheduled(fixedDelayString = "${agent-memory.access-flush-ms:30000}")
    public void scheduledAccessCountFlush() {
        accessCounter.flush(oltpMemoryRepository);
    }
}
