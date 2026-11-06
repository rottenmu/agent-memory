package com.zimo.agent.memory.governance;

import com.zimo.agent.memory.engine.MemorySpanRecorder;
import com.zimo.agent.memory.memory.AiMemoryService;
import com.zimo.agent.memory.memory.MemorySecurityConfig;
import com.zimo.agent.memory.model.L1AtomicMemory;
import com.zimo.agent.memory.model.UserMemoryRow;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import com.zimo.agent.memory.storage.OltpMemoryRepository;
import com.zimo.agent.memory.storage.impl.H2OltpMemoryRepository;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 记忆管理门面测试夹具：一套真实 H2 内存库 + 可切换打分参数的门面。
 *
 * <p>用真实 H2 仓储而不是 {@code FakeOltpMemoryRepository}：门面的价值恰在于把
 * 「打分、排序、SQL 下推、计数落库」串起来，换成 Fake 就会把待验证的链路替换成假的，
 * 测试全绿而线上仍可能错。Fake 只服务于「不关心记忆内容」的其他模块测试。</p>
 *
 * <p>为什么抽成独立类：召回、治理、批量三组测试都需要同一份造数与构造逻辑，
 * 复制三份会让「标准 14 的造数口径」出现三个可能漂移的版本。</p>
 */
final class MemoryManagerFixture {

    /** 测试用租户 / 用户。 */
    static final String TENANT = "t1";
    static final String USER = "u1";

    /** 标准 14 的查询文本。 */
    static final String QUERY = "报表导出";

    /** 含查询关键词且刚写入（新鲜）。 */
    static final String CONTENT_A = "以后都用 Excel 导出报表";

    /** 含查询关键词但 150 天未访问（陈旧）。 */
    static final String CONTENT_B = "报表导出走旧模板";

    /** 与查询完全无关。 */
    static final String CONTENT_C = "公司食堂中午排队很久";

    private static final long DAY_MS = 24L * 60 * 60 * 1000L;

    private final AiMemoryService service;
    private final OltpMemoryRepository repository;
    private final ImportanceScorer importanceScorer;
    private final MemoryMetrics metrics = new MemoryMetrics();
    private final AccessCounter counter = new AccessCounter();
    /** 记忆 span / 审计记录器：感知引擎与 {@code /span/{traceId}} 端点共用同一实例。 */
    private final MemorySpanRecorder spanRecorder;

    private MemoryManagerFixture(OltpMemoryRepository repository) {
        AiMemorySensitiveFilter filter = new AiMemorySensitiveFilter();
        this.repository = repository;
        this.importanceScorer = new ImportanceScorer(filter);
        this.service = new AiMemoryService(
                repository, filter, MemorySecurityConfig.DEFAULT, importanceScorer);
        // sink 传 null：单测不发链路 span（发出去也没人接），只验证审计表与留存副本
        this.spanRecorder = new MemorySpanRecorder(repository, null);
    }

    /**
     * 建一套独立夹具。
     *
     * @param db 内存库名；**每个测试必须不同**，否则共享的 {@code DB_CLOSE_DELAY=-1}
     *           内存库会让用例相互污染
     * @return 夹具
     */
    static MemoryManagerFixture create(String db) {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", "sa", "");
        return new MemoryManagerFixture(new H2OltpMemoryRepository(ds));
    }

    AiMemoryService service() {
        return service;
    }

    OltpMemoryRepository repository() {
        return repository;
    }

    /** 按指定半衰期构造门面（标准 14 的「改 half-life-days 后 B 降 A 不变」靠它）。 */
    MemoryManager manager(double halfLifeDays) {
        return manager(new MemoryScorer(0.5, 0.3, 0.2, halfLifeDays, 8, 0.25));
    }

    /** 按默认半衰期（30 天）构造门面。 */
    MemoryManager manager() {
        return manager(30);
    }

    MemoryManager manager(MemoryScorer scorer) {
        return new MemoryManager(service, repository, scorer,
                new FactExtractor(importanceScorer, false),
                MemoryMerger.of(scorer), MemoryEvictor.of(scorer), metrics, counter);
    }

    MemoryMetrics metrics() {
        return metrics;
    }

    /** 记忆 span / 审计记录器（感知引擎与 {@code /span} 端点共用的同一实例）。 */
    MemorySpanRecorder spanRecorder() {
        return spanRecorder;
    }

    /**
     * 只读「运行期累加器」的快照。
     *
     * <p>存储侧四个参数传 0：这里关心的是「治理动作有没有被记账」，不是分层规模。
     * 走 {@link MemoryMetrics#snapshot} 而不是给计数器加零散访问器 —— 后者会让
     * 指标类为了测试便利而膨胀出五个只被测试用到的公开方法。</p>
     *
     * @return 快照（存储侧数字均为 0）
     */
    MemoryMetrics.Snapshot runtime() {
        return metrics.snapshot(0, 0, 0, 0, 0);
    }

    /** 造标准 14 用的 A / B / C 三条记忆，并把 B 的最近访问时间推到 150 天前。 */
    void seedStandard14() {
        service.saveUserMemory(TENANT, USER, "custom", CONTENT_A);
        service.saveUserMemory(TENANT, USER, "custom", CONTENT_B);
        service.saveUserMemory(TENANT, USER, "custom", CONTENT_C);
        backdate(CONTENT_B, 150);
    }

    /** 把指定内容的条目最近访问时间改到指定天数之前（绕过服务层，直接改治理字段）。 */
    void backdate(String content, int days) {
        long old = System.currentTimeMillis() - (long) days * DAY_MS;
        for (UserMemoryRow row : repository.pageUserMemory(TENANT, USER, null, 1000, 0)) {
            if (content.equals(row.content())) {
                repository.updateAtomicGovernance(
                        TENANT, row.memoryId(), row.importance(), row.accessCount(), old);
            }
        }
    }

    /** 直接插一条 L1 原子记忆（绕过服务层，用于快速造量）。 */
    void insertAtomic(String id, String content, String category) {
        long ts = System.currentTimeMillis();
        repository.saveAtomicMemory(L1AtomicMemory.create(
                id, "trace-" + id, "user-" + USER, USER, category, content, ts, TENANT));
    }
}
