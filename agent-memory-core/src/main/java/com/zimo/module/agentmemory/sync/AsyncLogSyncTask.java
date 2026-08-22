package com.zimo.module.agentmemory.sync;

import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.storage.OlapAnalyticsRepository;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步日志同步 ETL 任务：定时将 H2 OLTP 的 L0 原始日志增量同步到 OLAP 副库。
 *
 * <p>硬性约束：本任务在后台异步线程执行（由 autoconfig 的 {@code @Scheduled}
 * 调度），非阻塞主对话链路；运行时记忆查询绝不访问 OLAP。</p>
 *
 * <p>同步策略（游标增量）：</p>
 * <ul>
 *   <li>游标 = 已同步的 H2 L0 最大自增 id，持久化于 OLAP 数据文件旁（{@code .cursor}）；</li>
 *   <li>每次执行仅拉取 {@code id > cursor} 的日志并追加到 OLAP 数据集；</li>
 *   <li>同步完成后推进游标并落盘；重启后从游标继续，避免重复同步。</li>
 * </ul>
 */
public class AsyncLogSyncTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AsyncLogSyncTask.class);

    /** 单次分页拉取条数。 */
    private static final int PAGE_SIZE = 500;

    private final OltpMemoryRepository oltp;
    private final OlapAnalyticsRepository olap;

    public AsyncLogSyncTask(OltpMemoryRepository oltp, OlapAnalyticsRepository olap) {
        this.oltp = oltp;
        this.olap = olap;
    }

    /**
     * 执行一次增量同步：拉取游标之后的新 L0 日志，追加到 OLAP 数据集并推进游标。
     * 异常不抛出（任务自愈，不影响主链路）。
     */
    @Override
    public void run() {
        try {
            long start = System.currentTimeMillis();
            long cursor = olap.syncCursor();
            if (cursor < 0) {
                // 首轮/游标未知：全量重建（去重后整体替换），保证与既有 .arrow 数据不重复
                fullRebuild();
                return;
            }
            List<Object[]> newRows = new ArrayList<>();
            long maxId = cursor;
            while (true) {
                List<L0RawLog> page = oltp.listRawLogsSince(cursor, PAGE_SIZE);
                if (page.isEmpty()) {
                    break;
                }
                for (L0RawLog logEntry : page) {
                    newRows.add(toWideRow(logEntry));
                    if (logEntry.id() > maxId) {
                        maxId = logEntry.id();
                    }
                }
                cursor = maxId;
                if (page.size() < PAGE_SIZE) {
                    break;
                }
            }
            if (newRows.isEmpty()) {
                log.info("AsyncLogSyncTask 无新增（游标 {}），跳过", maxId);
                return;
            }
            olap.appendRows(newRows);
            olap.updateSyncCursor(maxId);
            log.info("AsyncLogSyncTask 完成：增量 {} 行，游标推进至 {}，耗时 {} ms",
                    newRows.size(), maxId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("AsyncLogSyncTask 执行失败（不影响主链路）：{}", e.getMessage());
        }
    }

    /** 全量重建：分页拉取全部 L0 日志，按 traceId+ts+role 去重后整体替换 OLAP 数据集。 */
    private void fullRebuild() {
        long start = System.currentTimeMillis();
        java.util.List<Object[]> allRows = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        long maxId = 0L;
        int offset = 0;
        while (true) {
            List<L0RawLog> page = oltp.listRawLogsPage(offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (L0RawLog logEntry : page) {
                String dedupKey = logEntry.traceId() + "#" + logEntry.ts() + "#" + logEntry.role();
                if (seen.add(dedupKey)) {
                    allRows.add(toWideRow(logEntry));
                }
                if (logEntry.id() > maxId) {
                    maxId = logEntry.id();
                }
            }
            offset += page.size();
        }
        olap.replaceRows(allRows);
        olap.updateSyncCursor(maxId);
        log.info("AsyncLogSyncTask 全量重建完成：{} 行，游标推进至 {}，耗时 {} ms",
                allRows.size(), maxId, System.currentTimeMillis() - start);
    }

    private static Object[] toWideRow(L0RawLog logEntry) {
        return new Object[]{
                logEntry.traceId(),
                logEntry.sessionId(),
                logEntry.userId(),
                logEntry.ts(),
                logEntry.role(),
                logEntry.content(),
                logEntry.tokens() == null ? null : logEntry.tokens().longValue()
        };
    }
}
