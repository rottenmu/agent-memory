package com.zimo.agent.memory.engine;

import com.zimo.agent.memory.governance.AccessCounter;
import com.zimo.agent.memory.governance.FactExtractor;
import com.zimo.agent.memory.governance.ImportanceScorer;
import com.zimo.agent.memory.governance.MemoryEvictor;
import com.zimo.agent.memory.governance.MemoryManager;
import com.zimo.agent.memory.governance.MemoryMerger;
import com.zimo.agent.memory.governance.MemoryMetrics;
import com.zimo.agent.memory.governance.MemoryScorer;
import com.zimo.agent.memory.memory.AiMemoryService;
import com.zimo.agent.memory.memory.MemorySecurityConfig;
import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.model.L1AtomicMemory;
import com.zimo.agent.memory.model.UserMemoryRow;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import com.zimo.agent.memory.storage.FakeOltpMemoryRepository;
import com.zimo.agent.memory.storage.OltpMemoryRepository;
import com.zimo.agent.memory.storage.impl.H2OltpMemoryRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 记忆感知引擎测试夹具（M3）。
 *
 * <p>用<b>真实 H2 仓储</b>而不是纯 mock：本层的待验证行为恰好横跨「召回 → 组装 → 落 L0 →
 * 写审计表 → 发 span」五段，任何一段被替换成假实现，测试都会全绿而线上仍可能错。
 * 因此只有「故意制造失败」的用例才用最小桩（见各测试内的匿名子类）。</p>
 *
 * <p>为什么独立成类：预召回、留痕、异步回写三组测试共用同一套造数与构造逻辑，
 * 复制三份会让「阈值 / 打分权重口径」出现三个可能漂移的版本。</p>
 */
final class MemoryEngineFixture {

    /** 测试用隔离维度。 */
    static final String TENANT = "t1";
    static final String USER = "u1";
    static final String SESSION = "s1";

    /** 召回查询文本。 */
    static final String QUERY = "报表导出";

    /** 与查询强相关（预期高分并注入）。 */
    static final String HIT_CONTENT = "以后都用 Excel 导出报表";

    /** 与查询无关（预期低分，被阈值挡掉）。 */
    static final String NOISE_CONTENT = "公司食堂中午排队很久";

    /** 内存库名自增序号（保证每个夹具独占一个库）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private final OltpMemoryRepository repository;
    private final AiMemoryService service;
    private final AiMemorySensitiveFilter sensitiveFilter = new AiMemorySensitiveFilter();
    private final ImportanceScorer importanceScorer;
    private final MemoryMetrics metrics = new MemoryMetrics();
    private final AccessCounter counter = new AccessCounter();
    private final CapturingSink sink = new CapturingSink();
    private MemorySpanRecorder recorder;

    private MemoryEngineFixture(OltpMemoryRepository repository) {
        this.repository = repository;
        this.importanceScorer = new ImportanceScorer(sensitiveFilter);
        this.service = new AiMemoryService(
                repository, sensitiveFilter, MemorySecurityConfig.DEFAULT, importanceScorer);
    }

    /**
     * 建一套带<b>唯一库名</b>的夹具（推荐入口）。
     *
     * <p>库名自动生成而不是让每个测试手填：H2 的 {@code DB_CLOSE_DELAY=-1} 使内存库在
     * 连接关闭后依然存活，手填的名字一旦重复，两个测试就会共享同一份数据并互相污染
     * —— 表现为「单跑绿、全量跑红」这类最难定位的失败。</p>
     *
     * @return 夹具
     */
    static MemoryEngineFixture fresh() {
        return create("m3-" + SEQ.incrementAndGet());
    }

    /**
     * 建一套独立夹具。
     *
     * @param db 内存库名；<b>每个测试必须不同</b>，否则共享的 {@code DB_CLOSE_DELAY=-1}
     *           内存库会让用例相互污染
     * @return 夹具
     */
    static MemoryEngineFixture create(String db) {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", "sa", "");
        return new MemoryEngineFixture(new H2OltpMemoryRepository(ds));
    }

    OltpMemoryRepository repository() {
        return repository;
    }

    AiMemoryService service() {
        return service;
    }

    /**
     * 埋点出口：捕捉本模块发出的每一次 span。
     *
     * <p>与 {@link #recorder()} 绑定的同一实例 —— 分别新建会得到两个 sink，
     * 断言「异步阶段没发 span」时看的就不是同一个计数。</p>
     */
    CapturingSink sink() {
        return sink;
    }

    /** 审计 + span 记录器（懒建，与 {@link #sink()} 绑定）。 */
    MemorySpanRecorder recorder() {
        if (recorder == null) {
            recorder = new MemorySpanRecorder(repository, sink);
        }
        return recorder;
    }

    MemoryManager manager() {
        return manager(defaultScorer());
    }

    /** 按指定注入阈值构造门面（阈值是「是否注入」的判据，必须可独立调参）。 */
    MemoryManager manager(double minScore) {
        return manager(new MemoryScorer(0.5, 0.3, 0.2, 30, 8, minScore));
    }

    MemoryManager manager(MemoryScorer scorer) {
        return managerOn(repository, scorer);
    }

    /**
     * 用指定仓储构造门面。
     *
     * <p>供「故障注入」类用例使用：把失败点放在<b>存储层</b>（拉候选池失败 / 落库失败），
     * 这样 {@code MemoryManager}、打分、抽取等上层代码路径全都是真实的 —— 比给门面打桩
     * 更能证明「失败不反噬」这条约束真的成立。</p>
     *
     * <p><b>注意 service 也必须换</b>：落库走的是 {@code AiMemoryService}，而 service 持有
     * 自己的仓储引用。只替换 {@code MemoryManager} 的仓储会出现「仓储是桩、落库仍走真库」
     * 的静默错配 —— 故障注入看起来没生效，实际是根本没走到那条路径。</p>
     *
     * @param repo   仓储（可以是任何实现，含故障桩）
     * @param scorer 打分器
     * @return 门面
     */
    MemoryManager managerOn(OltpMemoryRepository repo, MemoryScorer scorer) {
        AiMemoryService scopedService = serviceOn(repo);
        return new MemoryManager(scopedService, repo, scorer,
                new FactExtractor(importanceScorer, false),
                MemoryMerger.of(scorer), MemoryEvictor.of(scorer), metrics, counter);
    }

    /** 用指定仓储构造记忆服务（与门面必须指向同一份存储）。 */
    AiMemoryService serviceOn(OltpMemoryRepository repo) {
        return new AiMemoryService(repo, sensitiveFilter, MemorySecurityConfig.DEFAULT,
                importanceScorer);
    }

    /** 用默认打分器 + 指定仓储构造门面。 */
    MemoryManager managerOn(OltpMemoryRepository repo) {
        return managerOn(repo, defaultScorer());
    }

    /** 生产默认口径的打分器（半衰期 30 天、topK 8、注入阈值 0.25）。 */
    MemoryScorer defaultScorer() {
        return new MemoryScorer(0.5, 0.3, 0.2, 30, 8, 0.25);
    }

    /**
     * 仓储桩：候选池拉取必抛异常。
     *
     * <p>只覆写 {@code pageUserMemory}，其余行为为空实现 —— 桩替换的范围越小，
     * 越能证明失败路径之外的行为没有被顺手改掉。</p>
     */
    OltpMemoryRepository repositoryWithFailingPage() {
        return new FakeOltpMemoryRepository() {
            @Override
            public List<UserMemoryRow> pageUserMemory(
                    String tenantId, String userId, String category, int limit, int offset) {
                throw new IllegalStateException("候选池拉取故意失败");
            }
        };
    }

    /** 仓储桩：L0 写入必抛异常（验证留痕失败不影响注入本身）。 */
    OltpMemoryRepository repositoryWithFailingRawLog() {
        return new FakeOltpMemoryRepository() {
            @Override
            public void saveRawLog(L0RawLog log) {
                throw new IllegalStateException("L0 写入故意失败");
            }
        };
    }

    /**
     * 仓储桩：原子记忆写入必抛异常（验证回写失败不反噬对话）。
     *
     * <p>注意挂载点是 {@code saveAtomicMemory} —— 长期记忆（含程序性记忆）落的是 L1 原子表；
     * 早期用 {@code pageUserMemory} 注入失败是错的，因为写路径根本不经过读方法，
     * 桩看起来「生效了」而实际什么都没拦到。</p>
     */
    OltpMemoryRepository repositoryWithFailingWrite() {
        return new FakeOltpMemoryRepository() {
            @Override
            public void saveAtomicMemory(L1AtomicMemory memory) {
                throw new IllegalStateException("原子记忆写入故意失败");
            }
        };
    }

    /** 造一条记忆。 */
    void seed(String content) {
        service.saveUserMemory(TENANT, USER, "custom", content);
    }

    /** 造一条指定类别的记忆（M4：程序性记忆需要显式类别）。 */
    void seedOf(String content, String category) {
        service.saveUserMemory(TENANT, USER, category, content);
    }

    /** 该链路的审计记录（时间序）。 */
    List<MemorySpanRecord> audit(String traceId) {
        return repository.listMemorySpansByTrace(traceId);
    }

    /** 该链路的 L0 原始记录（时间序）。 */
    List<L0RawLog> rawLogs(String traceId) {
        return repository.listRawLogsByTrace(traceId);
    }

    /**
     * 捕获埋点调用的 sink。
     *
     * <p>记录每次 {@code step} 的<b>全部参数</b>，而不只是「被调用过」—— 本模块的常见失效是
     * 「span 发了但属性名写错」，只断言次数根本发现不了。</p>
     */
    static final class CapturingSink implements MemoryTraceSink {

        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

        /** 置为 true 后所有 {@code step} 抛异常，用于验证「埋点失败不反噬业务」。 */
        boolean throwOnStep;

        @Override
        public void step(String traceId, String stepType, String name, String inputJson,
                         String outputJson, long latencyMs, String status,
                         Map<String, Object> attributes) {
            if (throwOnStep) {
                throw new IllegalStateException("sink 故意失败");
            }
            calls.add(new Call(traceId, stepType, name, status, latencyMs, Map.copyOf(attributes)));
        }

        int size() {
            return calls.size();
        }

        Call only() {
            if (calls.size() != 1) {
                throw new AssertionError("期望恰好 1 次埋点，实际 " + calls.size() + " 次");
            }
            return calls.get(0);
        }

        /** 按步骤名查找（同步骨架与召回在一条链路里都会发，不能只看第一个）。 */
        Call byName(String name) {
            for (Call call : calls) {
                if (call.name().equals(name)) {
                    return call;
                }
            }
            throw new AssertionError("未找到步骤名 " + name + "，实际收到 " + calls);
        }
    }

    /**
     * 捕获审计行的仓储桩。
     *
     * <p>存在的原因：审计表<b>刻意不提供「全表查」</b>（按 traceId 反查是唯一真实读路径，
     * 全表查会退化成数据导出且把一次参数漏传变成全表导出）。因此像「无链路动作不留伪造
     * traceId」这类断言，只能通过捕获写入来实现。</p>
     */
    static final class CapturingAuditRepository extends FakeOltpMemoryRepository {

        final List<MemorySpanRecord> rows = new ArrayList<>();

        /** 置为 true 后所有审计写入抛异常，用于验证「审计失败不反噬埋点」。 */
        boolean throwOnSave;

        @Override
        public void saveMemorySpan(MemorySpanRecord record) {
            if (throwOnSave) {
                throw new IllegalStateException("审计写入故意失败");
            }
            rows.add(record);
        }
    }

    /** 一次埋点调用的快照。 */
    record Call(String traceId, String stepType, String name, String status, long latencyMs,
                Map<String, Object> attributes) {

        Object attr(String key) {
            return attributes.get(key);
        }
    }
}
