package com.zimo.module.agentmemory.governance;

import com.zimo.module.agentmemory.memory.AiMemoryService;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.UserMemoryRow;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 记忆管理门面：把 CRUD、召回与六项治理能力收成一个入口。
 *
 * <p>所属模块：module-agent-memory 记忆治理。业务能力：在 {@link AiMemoryService}（三档
 * CRUD）与 {@link OltpMemoryRepository}（存储）之上编排<b>召回、抽取、合并、淘汰、统计</b>
 * 五类动作，并对外提供批量读写，对应 PRD §3.1 的「记忆管理器」。</p>
 *
 * <p><b>门面而非重写</b>：本类不推翻既有的 session / user / global 三档语义，写入一律
 * 委派给 {@link AiMemoryService} —— 白名单、敏感脱敏、重要性评估这些写入期规则只能有
 * 一个实现处，多一条写入路径就多一处脱敏漏点。</p>
 *
 * <p><b>为什么不在这里做日志埋点</b>：埋点属 M3（记忆感知引擎）的职责，且必须拿到
 * traceId 与父 span 才有意义。本类刻意只做业务编排，保持可在单测里同步断言 ——
 * 一旦掺入链路依赖，召回的可回归性就没了（PRD §十一 标准 14 要求分数逐位可复现）。</p>
 *
 * <p><b>事务边界</b>：本类<b>不做</b>事务编排。原因：召回是纯读；抽取/合并/淘汰为逐条
 * 处理且允许部分成功（PRD §6.2 明确「部分失败不回滚」）；批量写入同理。给这些动作套上
 * 事务只会把「已成功的条目」也一起撤销，放大数据损失面。</p>
 *
 * <p><b>异常规范</b>：参数错误（租户/用户为空、批量超限、抽取既无 traceId 也无 sessionId）
 * 抛 {@link IllegalArgumentException}，由全局异常处理器转成 4xx；业务性拒绝（白名单、
 * 敏感内容、非法写入目标）<b>不抛异常</b>，以逐条回执的 {@code reason} 返回 ——
 * 批量场景下一条失败不该让整批请求失败。</p>
 *
 * <p><b>线程安全</b>：无本地可变状态；访问计数累加交给 {@link AccessCounter}（内部无锁）。
 * 本类可安全地被并发召回与定时治理同时调用。</p>
 *
 * @author zimo
 * @since 2026-09-15
 */
public class MemoryManager {

    /** 单次批量写入的条数上限（PRD §6.2）。 */
    public static final int MAX_BATCH_ITEMS = 100;

    /** 单次批量读取的 id 条数上限；上限的作用是防 SQL 参数过多，不是业务约束。 */
    public static final int MAX_BATCH_QUERY_IDS = 200;

    /**
     * 召回候选池扫描上限。
     *
     * <p>取值理由：S1 打分是纯内存计算，单条成本极低，500 条可在毫秒级完成；同时它远大于
     * 单用户正常记忆量（PRD §9.4 的告警线是 1 万条，那是抽取失控的信号）。取上限而不是
     * 全量拉取，是为了不让「某个用户抽取失控」拖垮所有人的召回延迟。</p>
     */
    public static final int RECALL_CANDIDATE_LIMIT = 500;

    /** 调用方可覆盖的 topK 上限，防止一次召回把整个候选池塞进 Prompt。 */
    public static final int MAX_TOP_K = 100;

    /**
     * 召回排序口径（PRD §3.2）：{@code score DESC → last_access_at DESC → ts DESC → id ASC}。
     *
     * <p>末位用 id 兜底是硬要求：没有它，同分条目跨查询顺序不确定，§十一 标准 14
     * 的「连续 3 次分数逐位相同」会偶发失败，而这恰恰是 S1 方案能被信任的前提。</p>
     */
    private static final Comparator<RecallResult.Item> RECALL_ORDER = (left, right) -> {
        int byScore = Double.compare(right.score(), left.score());
        if (byScore != 0) {
            return byScore;
        }
        int byAccess = Long.compare(touchedAt(right), touchedAt(left));
        if (byAccess != 0) {
            return byAccess;
        }
        int byTs = Long.compare(right.ts(), left.ts());
        if (byTs != 0) {
            return byTs;
        }
        return Objects.toString(left.memoryId(), "").compareTo(Objects.toString(right.memoryId(), ""));
    };

    private final AiMemoryService memoryService;
    private final OltpMemoryRepository repository;
    private final MemoryScorer scorer;
    private final FactExtractor factExtractor;
    private final MemoryMerger merger;
    private final MemoryEvictor evictor;
    private final MemoryMetrics metrics;
    private final AccessCounter accessCounter;

    /**
     * @param memoryService 记忆 CRUD 服务（写入规则的唯一实现处）
     * @param repository    四层 OLTP 仓储
     * @param scorer        S1 混合打分器（召回与淘汰共用同一口径）
     * @param factExtractor 事实抽取器（规则档默认开，LLM 档默认关）
     * @param merger        记忆合并器
     * @param evictor       遗忘淘汰器
     * @param metrics       运行期指标累加器
     * @param accessCounter 召回访问计数累加器
     */
    public MemoryManager(
            AiMemoryService memoryService,
            OltpMemoryRepository repository,
            MemoryScorer scorer,
            FactExtractor factExtractor,
            MemoryMerger merger,
            MemoryEvictor evictor,
            MemoryMetrics metrics,
            AccessCounter accessCounter) {
        this.memoryService = memoryService;
        this.repository = repository;
        this.scorer = scorer;
        this.factExtractor = factExtractor;
        this.merger = merger;
        this.evictor = evictor;
        this.metrics = metrics;
        this.accessCounter = accessCounter;
    }

    /* ---------------- 召回 ---------------- */

    /**
     * 按查询文本召回用户长期记忆（S1 混合打分 + Top-K）。
     *
     * <p>业务执行流程：<br>
     * 1. 从该租户该用户的「用户长期记忆」拉取候选（L3 画像 ∪ L1 原子记忆，SQL 层已完成
     *    租户与用户下推）；<br>
     * 2. 按 {@code types} 做类别过滤（本步在内存完成，因此 {@code scanned ≥ candidates}）；<br>
     * 3. 逐条打分并按 {@link #RECALL_ORDER} 稳定排序；<br>
     * 4. 截取 topK，把命中条目的访问计数交给累加器（<b>不</b>同步写库）；<br>
     * 5. 记录指标并返回。</p>
     *
     * <p><b>返回的是全部 topK 条目，不做阈值过滤</b>：注入阈值只决定「哪些进 Prompt」，
     * 由 {@link RecallResult#injectedCount()} 标出边界，过滤动作在 M3 注入层执行。
     * 在召回层就丢掉低分条目会让阈值问题无法调试。</p>
     *
     * @param tenantId 租户标识（隔离维度，必填）
     * @param userId   用户标识（必填）
     * @param query    查询文本；为空表示只按新鲜度与重要性排序（不报错，
     *                 便于前端「看看这个用户都记了什么」的用法）
     * @param types    类别白名单；为空或 {@code null} 表示不限类别
     * @param topK     返回条数上限；{@code null} 表示用配置默认值，超过 {@link #MAX_TOP_K} 时被夹到上限
     * @return 召回结果（含分数分解、候选数、注入数）
     * @throws IllegalArgumentException 租户或用户标识为空时
     */
    public RecallResult recall(
            String tenantId, String userId, String query, List<String> types, Integer topK) {
        return recall(tenantId, userId, query, types, null, topK);
    }

    /**
     * 召回用户记忆，可同时指定类别白名单与类别黑名单（M4-2a）。
     *
     * <p><b>为什么需要黑名单而非「白名单传全量」</b>：标准 11 要求「非计划模式不得召回程序性
     * 记忆」。用白名单表达这件事必须穷举「除 procedural 外的所有类别」—— 而类别是<b>开放集</b>
     * （{@code habit}/{@code fact}/{@code task} 等已在打分器里出现），新增一个类别就得回来
     * 补白名单，漏补即静默失效。黑名单只描述「这几类不要」，新增类别默认可用，
     * 失效方向是安全的。</p>
     *
     * <p>白名单与黑名单<b>同时生效</b>（先白名单后黑名单），而不是二选一：
     * 前者表达「我只要这几类」，后者表达「我不要这几类」，混用时同时尊重两个意图更不易出错。</p>
     *
     * @param tenantId     租户标识（隔离维度，必填）
     * @param userId       用户标识（必填）
     * @param query        查询文本；为空表示只按新鲜度与重要性排序
     * @param types        类别白名单；为空或 {@code null} 表示不限类别
     * @param excludeTypes 类别黑名单；为空或 {@code null} 表示不排除任何类别
     * @param topK         返回条数上限；{@code null} 表示用配置默认值
     * @return 召回结果（含分数分解、候选数、注入数）
     * @throws IllegalArgumentException 租户或用户标识为空时
     */
    public RecallResult recall(
            String tenantId, String userId, String query, List<String> types,
            List<String> excludeTypes, Integer topK) {
        requireText(tenantId, "租户标识不能为空");
        requireText(userId, "用户标识不能为空");
        int limit = resolveTopK(topK);
        List<UserMemoryRow> scanned =
                repository.pageUserMemory(tenantId, userId, null, RECALL_CANDIDATE_LIMIT, 0);
        long now = System.currentTimeMillis();
        List<RecallResult.Item> scored = scoreAll(query, scanned, types, excludeTypes, now);
        scored.sort(RECALL_ORDER);
        List<RecallResult.Item> top = trimTo(scored, limit);
        recordAccess(tenantId, top, now);
        metrics.recordRecall(scored.size(), top.size(), scoreSum(top));
        return new RecallResult(MemoryScorer.ALGORITHM_S1_HYBRID, query, limit, scorer.minScore(),
                RECALL_CANDIDATE_LIMIT, scanned.size(), scored.size(), top);
    }

    /** 逐条打分；类型过滤在此完成（仓储未提供「类型集合」下推，故不是 SQL 层过滤）。 */
    private List<RecallResult.Item> scoreAll(
            String query, List<UserMemoryRow> rows, List<String> types, long now) {
        return scoreAll(query, rows, types, null, now);
    }

    /**
     * 逐条打分 + 类别白/黑名单过滤。
     *
     * <p>过滤顺序是<b>先白后黑</b>：白名单未命中直接跳过，命中后再看黑名单。
     * 若反过来（先黑后白），一条被黑名单排除的条目仍会在白名单统计里被算作「候选」，
     * 让 {@code candidates} 这个诊断字段失去解释力。</p>
     */
    private List<RecallResult.Item> scoreAll(
            String query, List<UserMemoryRow> rows, List<String> types,
            List<String> excludeTypes, long now) {
        Set<String> wanted = normalizeTypes(types);
        Set<String> excluded = normalizeTypes(excludeTypes);
        List<RecallResult.Item> items = new ArrayList<>();
        for (UserMemoryRow row : rows) {
            String category = normalizeCategory(row.category());
            if (!wanted.isEmpty() && !wanted.contains(category)) {
                continue;
            }
            if (excluded.contains(category)) {
                continue;
            }
            long touched = row.lastAccessAt() == null ? row.ts() : row.lastAccessAt();
            MemoryScore score = scorer.score(query, row.content(), row.importance(), touched, now);
            items.add(new RecallResult.Item(row.memoryId(), row.layer(), row.category(),
                    row.content(), row.ts(), score.score(), score.match(), score.decay(),
                    score.importance(), row.accessCount(), row.lastAccessAt()));
        }
        return items;
    }

    /** 命中条目的访问计数交给累加器；仅 L1 参与（仓储未提供 L3 的治理字段更新能力）。 */
    private void recordAccess(String tenantId, List<RecallResult.Item> items, long now) {
        for (RecallResult.Item item : items) {
            if (!UserMemoryRow.LAYER_L1.equals(item.layer())) {
                continue;
            }
            accessCounter.touch(tenantId, item.memoryId(), item.importance(),
                    item.accessCount(), now);
        }
    }

    /* ---------------- 抽取 ---------------- */

    /**
     * 从 L0 原始对话中抽取事实候选，可选直接落库。
     *
     * <p>业务执行流程：按 {@code traceId}（优先）或 {@code sessionId} 取 L0 消息 →
     * 规则档抽取 → 可选逐条落库（复用用户长期记忆的写入路径，因此同样经过白名单与脱敏）
     * → 记录指标。</p>
     *
     * <p><b>为什么两个来源都必填一个而不是二选一都为空时返回空结果</b>：既没有 traceId
     * 也没有 sessionId 属于调用方参数错误。若返回「抽到 0 条」，调用方会以为这段对话没有
     * 值得记住的内容，而真实原因是自己少传了参数 —— 静默错误必须避免。</p>
     *
     * @param tenantId  租户标识（必填）
     * @param userId    用户标识；{@code persist=true} 时必填
     * @param traceId   链路标识；与 {@code sessionId} 至少一个非空
     * @param sessionId 会话标识；与 {@code traceId} 至少一个非空
     * @param persist   是否落库；{@code false} 表示只回显候选（调试与效果评估用）
     * @return 抽取结果（含候选明细与落库统计）
     * @throws IllegalArgumentException 租户为空、两个来源均为空、或落库模式下用户为空时
     */
    public ExtractOutcome extract(
            String tenantId, String userId, String traceId, String sessionId, boolean persist) {
        requireText(tenantId, "租户标识不能为空");
        if (persist) {
            requireText(userId, "落库模式下用户标识不能为空");
        }
        return extractInternal(tenantId, userId, traceId, sessionId,
                loadRawLogs(traceId, sessionId), persist);
    }

    /**
     * 从<b>内存中的对话消息</b>抽取事实候选（M3 异步回写入口）。
     *
     * <p><b>为什么需要这个变体而不是复用 {@link #extract}</b>：会话侧写 L0 时使用的是
     * {@code convo-} 前缀的自生成 traceId（见 {@code AiConversationMemory.recordTurnEvent}），
     * 与链路 traceId 不是同一个值。异步回写按链路 traceId 回查 L0 会一条都查不到 ——
     * 表现为「回写静默空转」，且因为不报错，会被误判成「这段对话没有值得记住的内容」。
     * 因此回写侧把本轮消息直接带进任务，走本方法抽取。</p>
     *
     * <p><b>落库仍然走同一个入口</b>：候选的持久化依旧委派给 {@link AiMemoryService}，
     * 白名单、敏感脱敏、重要性评估这些写入期规则不会因为换了入口而被绕过。</p>
     *
     * @param tenantId  租户标识（必填）
     * @param userId    用户标识；{@code persist=true} 时必填
     * @param traceId   链路标识（写入抽取结果用，可为空）
     * @param sessionId 会话标识（写入抽取结果用，可为空）
     * @param messages  本轮对话消息（内存构造，不落 L0）；为空返回空结果
     * @param persist   是否落库
     * @return 抽取结果（含候选明细与落库统计）
     * @throws IllegalArgumentException 租户为空、或落库模式下用户为空时
     */
    public ExtractOutcome extractFromMessages(
            String tenantId, String userId, String traceId, String sessionId,
            List<L0RawLog> messages, boolean persist) {
        requireText(tenantId, "租户标识不能为空");
        if (persist) {
            requireText(userId, "落库模式下用户标识不能为空");
        }
        return extractInternal(tenantId, userId, traceId, sessionId,
                messages == null ? List.of() : messages, persist);
    }

    /** 抽取公共流程：两个公开入口只在「消息从哪来」上不同，其余步骤必须完全一致。 */
    private ExtractOutcome extractInternal(String tenantId, String userId, String traceId,
            String sessionId, List<L0RawLog> logs, boolean persist) {
        FactExtractor.Result result = factExtractor.extract(logs);
        int persisted = 0;
        int failed = 0;
        if (persist) {
            for (FactCandidate candidate : result.candidates()) {
                if (persistCandidate(tenantId, userId, candidate)) {
                    persisted++;
                } else {
                    failed++;
                }
            }
        }
        metrics.recordExtract(result.candidates().size(), result.deduped());
        return new ExtractOutcome(traceId, sessionId, result.scanned(), result.candidates().size(),
                result.deduped(), persisted, failed, result.llmEnabled(), result.llmApplied(),
                persist, result.candidates());
    }

    /** 取抽取输入：traceId 优先（范围更精确），否则按会话。 */
    private List<L0RawLog> loadRawLogs(String traceId, String sessionId) {
        if (hasText(traceId)) {
            return repository.listRawLogsByTrace(traceId);
        }
        if (hasText(sessionId)) {
            return repository.listRawLogsBySession(sessionId);
        }
        throw new IllegalArgumentException("抽取需要 traceId 或 sessionId 至少一个非空");
    }

    /** 落库一条候选：走用户长期记忆写入路径，被拒时返回 {@code false}。 */
    private boolean persistCandidate(String tenantId, String userId, FactCandidate candidate) {
        Map<String, Object> saved = memoryService.saveUserMemory(
                tenantId, userId, candidate.category(), candidate.content(), candidate.importance());
        return !Boolean.FALSE.equals(saved.get("allowed"));
    }

    /* ---------------- 合并与淘汰 ---------------- */

    /**
     * 触发记忆合并去重（同用户 + 同类别 + 内容近似归并为一条）。
     *
     * @param tenantId 租户标识（必填）
     * @param userId   用户标识（必填）
     * @param dryRun   为 {@code true} 时只产出计划不落库
     * @return 合并结果（扫描数、删除数、更新数、分组明细）
     * @throws IllegalArgumentException 租户或用户标识为空时
     */
    public MemoryMerger.Outcome merge(String tenantId, String userId, boolean dryRun) {
        requireText(tenantId, "租户标识不能为空");
        requireText(userId, "用户标识不能为空");
        MemoryMerger.Outcome outcome = merger.apply(repository, tenantId, userId, dryRun);
        if (!dryRun) {
            metrics.recordMerge(outcome.deletedCount());
        }
        return outcome;
    }

    /**
     * 触发遗忘淘汰（分数低于阈值且最近访问超期的条目）。
     *
     * <p>分数口径与 {@link #recall} 完全一致，否则会出现「低分不注入却长期留存」
     * 与「低分被淘汰」两套互不相容的标准（PRD §7.3）。</p>
     *
     * @param tenantId  租户标识（必填）
     * @param dryRun    为 {@code true} 时只产出计划不落库
     * @param threshold 分数阈值；{@code null} 表示用 {@link MemoryEvictor#DEFAULT_THRESHOLD}
     * @return 淘汰结果（含 {@code suspicious()} 安全阀判断）
     * @throws IllegalArgumentException 租户标识为空时
     */
    public MemoryEvictor.Outcome evict(String tenantId, boolean dryRun, Double threshold) {
        requireText(tenantId, "租户标识不能为空");
        MemoryEvictor.Outcome outcome = evictor.apply(repository, tenantId, dryRun, threshold);
        if (!dryRun) {
            metrics.recordEvict(outcome.deletedCount());
        }
        return outcome;
    }

    /* ---------------- 统计 ---------------- */

    /**
     * 记忆指标快照。
     *
     * <p>存储侧数字来自精确 {@code COUNT(*)}（带租户条件）；运行侧数字来自本进程累加，
     * 因此 {@code since} 表示「本进程启动时刻」而不是历史起点 —— 调用方必须知道这一点，
     * 否则会把重启后的低值误读成业务量下滑。</p>
     *
     * @param tenantId 租户标识（必填；指标接口不做跨租户聚合，避免成为规模探针）
     * @return 指标快照
     * @throws IllegalArgumentException 租户标识为空时
     */
    public MemoryMetrics.Snapshot metrics(String tenantId) {
        requireText(tenantId, "租户标识不能为空");
        int l3Count = repository.countPersonaByTenant(tenantId);
        int l1Count = repository.countAtomicByTenant(tenantId);
        return metrics.snapshot(
                repository.countL0Total(), repository.countL0Today(), l1Count + l3Count,
                l3Count, l1Count);
    }

    /**
     * 把召回累加的访问计数批量落库（供定时任务调用）。
     *
     * @return 真实更新行数
     */
    public int flushAccessCounts() {
        return accessCounter.flush(repository);
    }

    /** 当前待落库的访问计数条目数（供指标与诊断观测）。 */
    public int pendingAccessCounts() {
        return accessCounter.pendingSize();
    }

    /**
     * 某条链路的记忆审计记录（{@code /span/{traceId}} 的落库侧数据源）。
     *
     * <p>放在门面上而非让控制器直接摸仓储：审计记录是「记忆动作发生过」的持久化事实，
     * 与 CRUD、召回、治理同属记忆能力的一部分，出口越少越不容易出现「某一处绕过了脱敏
     * 或租户校验」的漏点。</p>
     *
     * @param traceId 链路 ID；为空返回空列表
     * @return 审计记录（时间升序，同毫秒按自增 id 兜底）
     */
    public List<com.zimo.module.agentmemory.engine.MemorySpanRecord> memorySpansOf(String traceId) {
        return repository.listMemorySpansByTrace(traceId);
    }

    /* ---------------- 批量读写 ---------------- */

    /**
     * 批量写入（部分失败不回滚，逐条给出结果）。
     *
     * <p>业务执行流程：按 {@code target} 分发到 user / global / session 三档写入；
     * 每条的参数校验与业务拒绝都只影响该条。非法 {@code target} 直接拒绝该条 ——
     * 静默按用户记忆写入会把数据放进错误的桶里。</p>
     *
     * @param tenantId 租户标识（必填）
     * @param items    写入项；为空表示空批量，返回 0 成功 0 拒绝
     * @return 批量结果（含逐条回执）
     * @throws IllegalArgumentException 租户为空或条数超过 {@link #MAX_BATCH_ITEMS} 时
     */
    public BatchOutcome batchWrite(String tenantId, List<WriteItem> items) {
        requireText(tenantId, "租户标识不能为空");
        List<WriteItem> requested = items == null ? List.of() : items;
        if (requested.size() > MAX_BATCH_ITEMS) {
            throw new IllegalArgumentException(
                    "单次批量写入最多 " + MAX_BATCH_ITEMS + " 条，当前 " + requested.size() + " 条");
        }
        List<BatchOutcome.ItemResult> results = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            results.add(writeOne(tenantId, index, requested.get(index)));
        }
        int succeeded = countSucceeded(results);
        return new BatchOutcome(requested.size(), succeeded, requested.size() - succeeded, results);
    }

    /** 写入单条：先校验目标与必填项，再分发。 */
    private BatchOutcome.ItemResult writeOne(String tenantId, int index, WriteItem item) {
        if (item == null) {
            return BatchOutcome.ItemResult.rejected(index, WriteItem.TARGET_USER, "写入项为空");
        }
        if (!item.targetSupported()) {
            return BatchOutcome.ItemResult.rejected(index, item.effectiveTarget(),
                    "不支持的写入目标: " + item.target() + "，可选 user / global / session");
        }
        if (!hasText(item.content())) {
            return BatchOutcome.ItemResult.rejected(index, item.effectiveTarget(), "content 不能为空");
        }
        return switch (item.effectiveTarget()) {
            case WriteItem.TARGET_GLOBAL -> writeGlobal(tenantId, index, item);
            case WriteItem.TARGET_SESSION -> writeSession(tenantId, index, item);
            default -> writeUser(tenantId, index, item);
        };
    }

    /** 写用户长期记忆（category 为空时由服务层按 custom 处理）。 */
    private BatchOutcome.ItemResult writeUser(String tenantId, int index, WriteItem item) {
        if (!hasText(item.userId())) {
            return BatchOutcome.ItemResult.rejected(index, WriteItem.TARGET_USER, "userId 不能为空");
        }
        Map<String, Object> saved = memoryService.saveUserMemory(
                tenantId, item.userId(), item.category(), item.content(), item.importance());
        return toResult(index, WriteItem.TARGET_USER, saved);
    }

    /** 写全局记忆（缺省键时用时间戳兜底，与 MCP 工具口径一致）。 */
    private BatchOutcome.ItemResult writeGlobal(String tenantId, int index, WriteItem item) {
        String key = hasText(item.key()) ? item.key() : "mem-" + System.currentTimeMillis();
        Map<String, Object> saved =
                memoryService.saveGlobalMemory(tenantId, key, item.content());
        return toResult(index, WriteItem.TARGET_GLOBAL, saved);
    }

    /** 写会话变量（缺省键时用时间戳兜底，与 MCP 工具口径一致）。 */
    private BatchOutcome.ItemResult writeSession(String tenantId, int index, WriteItem item) {
        if (!hasText(item.sessionId())) {
            return BatchOutcome.ItemResult.rejected(index, WriteItem.TARGET_SESSION, "sessionId 不能为空");
        }
        String key = hasText(item.key()) ? item.key() : "var-" + System.currentTimeMillis();
        Map<String, Object> saved =
                memoryService.saveSessionVar(tenantId, item.sessionId(), key, item.content());
        return toResult(index, WriteItem.TARGET_SESSION, saved);
    }

    /** 把服务层返回体转成逐条回执：{@code allowed=false} 视为业务拒绝而非异常。 */
    private BatchOutcome.ItemResult toResult(int index, String target, Map<String, Object> saved) {
        if (Boolean.FALSE.equals(saved.get("allowed"))) {
            return BatchOutcome.ItemResult.rejected(index, target, stringOf(saved.get("reason")));
        }
        return BatchOutcome.ItemResult.ok(index, target, stringOf(saved.get("id")));
    }

    /**
     * 按 id 集合批量读取用户长期记忆。
     *
     * <p>id 查询下推到 SQL（不做「扫全量再过滤」：扫描有上限，超限即静默漏查）。
     * 返回体把未命中的 id 单独列出，供删除前校验与导出后对账使用。</p>
     *
     * @param tenantId 租户标识（必填）
     * @param userId   用户标识（必填；限定在单个用户范围内，避免越权读他人记忆）
     * @param ids      记忆主键集合；重复项自动去重，空集合返回空结果
     * @return 批量读取结果（命中行 + 未命中 id）
     * @throws IllegalArgumentException 租户/用户为空或去重后条数超过 {@link #MAX_BATCH_QUERY_IDS} 时
     */
    public BatchQueryOutcome batchQuery(String tenantId, String userId, List<String> ids) {
        requireText(tenantId, "租户标识不能为空");
        requireText(userId, "用户标识不能为空");
        List<String> distinct = distinctIds(ids);
        if (distinct.size() > MAX_BATCH_QUERY_IDS) {
            throw new IllegalArgumentException("单次批量读取最多 " + MAX_BATCH_QUERY_IDS
                    + " 个 id，当前去重后 " + distinct.size() + " 个");
        }
        if (distinct.isEmpty()) {
            return new BatchQueryOutcome(0, List.of(), List.of());
        }
        List<UserMemoryRow> found = repository.listUserMemoryByIds(tenantId, userId, distinct);
        Set<String> foundIds = new LinkedHashSet<>();
        for (UserMemoryRow row : found) {
            foundIds.add(row.memoryId());
        }
        List<String> missing = distinct.stream().filter(id -> !foundIds.contains(id)).toList();
        return new BatchQueryOutcome(distinct.size(), found, missing);
    }

    /* ---------------- 工具 ---------------- */

    /** 生效的 topK：空值取配置默认，超出上限夹到上限（不报错，避免边缘取值直接失败）。 */
    private int resolveTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return scorer.topK();
        }
        return Math.min(topK, MAX_TOP_K);
    }

    /** 截取 topK 条（返回不可变副本，避免调用方改动影响已记录的指标）。 */
    private static List<RecallResult.Item> trimTo(List<RecallResult.Item> items, int limit) {
        if (items.size() <= limit) {
            return List.copyOf(items);
        }
        return List.copyOf(items.subList(0, limit));
    }

    /** 返回条目分数之和，用于平均召回分指标。 */
    private static double scoreSum(List<RecallResult.Item> items) {
        double sum = 0.0;
        for (RecallResult.Item item : items) {
            sum += item.score();
        }
        return sum;
    }

    /** 最近访问时间：缺失时回落条目自身时间戳，与打分口径一致。 */
    private static long touchedAt(RecallResult.Item item) {
        return item.lastAccessAt() == null ? item.ts() : item.lastAccessAt();
    }

    /** 类型白名单归一化：去空白、去重、忽略空项。 */
    private static Set<String> normalizeTypes(List<String> types) {
        Set<String> wanted = new LinkedHashSet<>();
        if (types == null) {
            return wanted;
        }
        for (String type : types) {
            if (hasText(type)) {
                wanted.add(normalizeCategory(type));
            }
        }
        return wanted;
    }

    /** 类别归一化：缺失按 {@code custom} 处理，与写入路径同口径。 */
    private static String normalizeCategory(String category) {
        return hasText(category) ? category.trim() : "custom";
    }

    /** id 去重并丢弃空项（保序，便于与请求顺序对照）。 */
    private static List<String> distinctIds(List<String> ids) {
        Set<String> distinct = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (hasText(id)) {
                    distinct.add(id.trim());
                }
            }
        }
        return List.copyOf(distinct);
    }

    /** 统计成功条数。 */
    private static int countSucceeded(List<BatchOutcome.ItemResult> results) {
        int succeeded = 0;
        for (BatchOutcome.ItemResult result : results) {
            if (result.ok()) {
                succeeded++;
            }
        }
        return succeeded;
    }

    /** 转字符串：{@code null} 保持 {@code null}，避免把 null 写成 "null"。 */
    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 必填文本校验，不通过即抛参数异常（由全局异常处理器转 4xx）。 */
    private static void requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    /** 文本非空判断。 */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
