package com.zimo.module.agentmemory.engine;

import com.zimo.module.agentmemory.governance.RecallResult;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆审计记录器：把记忆动作变成「链路 span + 审计行」。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。本类是记忆模块里<b>唯一</b>写
 * {@code memory_span} 审计表与发 {@code memory.*} span 的地方 —— 多一条写入路径就多一处
 * 「属性名写错、漏传字段」的漏点。</p>
 *
 * <p><b>同步 / 异步的分工（PRD §4.4、§7.2）</b>：</p>
 * <table border="1">
 *   <caption>阶段职责</caption>
 *   <tr><th>阶段</th><th>发 span</th><th>写审计</th><th>理由</th></tr>
 *   <tr><td>同步（链路未收尾）</td><td>是</td><td>是（{@code phase=sync}）</td>
 *       <td>链路里一定看得见「这次动作发生过」，不丢事实</td></tr>
 *   <tr><td>异步（链路已收尾）</td><td><b>绝不</b></td><td>是（{@code phase=async}）</td>
 *       <td>此时 {@code GenAiTraceObserver} 已把容器 {@code remove} 掉，
 *           再发 span 会「不导出 + 不释放」，且因 {@code catch (Exception ignored)} 而静默</td></tr>
 * </table>
 *
 * <p><b>失败不反噬</b>：审计写入与 span 发送都在 try/catch 内，且失败时<b>打 WARN</b> ——
 * 观测通道整体静默是本项目已经踩过的坑（注册成功但一条都导不出来），所以这里不允许
 * 出现「吞掉异常且不留痕」的写法。</p>
 *
 * <p><b>明确否决</b>的方案：让 trace 延迟 N 秒再导出。N 无法覆盖所有异步任务，
 * 只会把「丢 span」变成「偶发丢 span」，更难排查。</p>
 *
 * @author zimo
 * @since 2026-09-16
 */
public class MemorySpanRecorder {

    private static final Logger log = LoggerFactory.getLogger(MemorySpanRecorder.class);

    /**
     * 内部相位标记：仅当「链路已收尾之后仍然发 span」这条<b>禁止路径</b>被触发时才置位。
     *
     * <p><b>为什么不能用业务属性 {@link MemorySpanAttributes#ASYNC} 当相位判据</b>：
     * 该属性的语义是「本次写入以异步方式完成」，而 {@code recordWritePending} 发出的
     * <b>同步骨架</b>恰好描述的就是一次异步写入 —— 它本身发在同步阶段，属正常路径。
     * 早期版本直接用它当相位判据，结果每轮正常对话都会把违规计数 +1，并使
     * {@code /span} 响应里的 {@code chainPollutedByAsync} 恒为 {@code true}：
     * 判据被自己污染，标准 9 永远无法通过。因此相位标记必须与业务属性分开。</p>
     *
     * <p>本模块<b>不存在</b>设置该标记的代码路径（{@code recordAsyncWriteback} 只写审计表、
     * 不发 span）。它在此的作用是：把「异步不许发 span」这条硬约束钉在判据出现的位置
     * —— 一旦将来有人在收尾后发 span，只需带上该标记，计数与 WARN 立即生效。</p>
     */
    private static final String INTERNAL_PHASE = "memory.internal.phase";
    /** {@link #INTERNAL_PHASE} 的取值：链路收尾之后。 */
    private static final String PHASE_AFTER_TRACE_END = "after-trace-end";

    /** 留存最近多少条链路的 span 副本（面板按 traceId 反查时用，超出即淘汰最久未访问的）。 */
    private static final int MAX_RETAINED_TRACES = 200;
    /** 单条链路最多留存多少个 span（防一条异常长链路吃满内存）。 */
    private static final int MAX_RETAINED_SPANS = 50;

    /**
     * {@code memory.type} 的回落值：命中条目类别缺失、或空召回时使用。
     *
     * <p>与 {@code AiMemoryService} 对「类别为空按 custom 处理」的口径保持一致
     * —— 两处若各定一套，会出现「写入时记为 custom、埋点上显示成别的」的矛盾。</p>
     */
    private static final String CATEGORY_FALLBACK = "custom";

    private final OltpMemoryRepository repository;
    private final MemoryTraceSink sink;

    /**
     * 最近发出的 span 副本（{@code traceId → span 列表}，按访问序淘汰）。
     *
     * <p><b>为什么需要留存</b>：{@code GenAiTraceObserver} 在 {@code onEnd} 里用
     * {@code remove(traceId)} 取走 span 列表并交给导出器，之后进程内<b>再也查不到</b>
     * 这条链路的 span。而面板按 traceId 反查「这次对话用了哪些记忆」是核心用法，
     * 因此本类保留一份副本。</p>
     *
     * <p><b>只留最近 {@value #MAX_RETAINED_TRACES} 条</b>：无界留存等于内存泄漏，
     * 而审计表（{@code memory_span}）已经持久化了「事实」，这里只是为了让 span 的
     * 结构化属性可回看。</p>
     */
    private final Map<String, List<MemorySpanView>> retainedTraces =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<MemorySpanView>> eldest) {
                    return size() > MAX_RETAINED_TRACES;
                }
            });
    private final AtomicInteger emitted = new AtomicInteger();
    /**
     * 「链路收尾后发 span」的累计次数：按设计<b>必须恒为 0</b>。
     *
     * <p>注意语义边界：它统计的是<b>相位违规</b>，不是「异步写入的条数」。
     * 异步写入的条数看审计表（{@code phase=async}），两者不可混用
     * —— 曾经因为混用，正常对话也被记成违规（详见 {@link #INTERNAL_PHASE}）。</p>
     */
    private final AtomicInteger emittedAsync = new AtomicInteger();

    /**
     * @param repository 审计表存储（必须有，否则审计能力整体不可用）
     * @param sink       埋点出口；为 {@code null} 时只写审计表（不影响主流程）
     */
    public MemorySpanRecorder(OltpMemoryRepository repository, MemoryTraceSink sink) {
        this.repository = repository;
        this.sink = sink == null ? MemoryTraceSink.NOOP : sink;
    }

    /**
     * 记录一次召回（预召回或工具检索）。
     *
     * <p>召回走 {@code retrieval} 规范词：它自动映射为 {@code RETRIEVER} span，
     * 前端可观测页零改动即可显示，且沿用既有别名表。</p>
     *
     * @param traceId   链路 ID
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param tenantId  租户 ID
     * @param result    召回结果（分数、候选数、截断标记均取自它）
     * @param strategy  {@link MemorySpanAttributes#STRATEGY_PREFETCH} 或 {@code tool}
     * @param latencyMs 耗时毫秒
     */
    public void recordRecall(String traceId, String sessionId, String userId, String tenantId,
                             RecallResult result, String strategy, long latencyMs) {
        if (result == null) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(MemorySpanAttributes.OPERATION, MemorySpanAttributes.OP_RECALL);
        attributes.put(MemorySpanAttributes.RECALL_COUNT, result.items().size());
        attributes.put(MemorySpanAttributes.RECALL_CANDIDATES, result.candidates());
        attributes.put(MemorySpanAttributes.RECALL_SCORE_TOP, topScore(result));
        attributes.put(MemorySpanAttributes.RECALL_SCORE_AVG, avgScore(result));
        attributes.put(MemorySpanAttributes.RECALL_HIT, !result.items().isEmpty());
        attributes.put(MemorySpanAttributes.RECALL_TRUNCATED, result.truncated());
        attributes.put(MemorySpanAttributes.RECALL_STRATEGY, strategy);
        attributes.put(MemorySpanAttributes.RECALL_ALGORITHM, result.algorithm());
        attributes.put(MemorySpanAttributes.ACCESS_COUNT, maxAccessCount(result));
        attributes.put(MemorySpanAttributes.TENANT_ID, tenantId);
        attributes.put(MemorySpanAttributes.LAYER, topLayer(result));
        attributes.put(MemorySpanAttributes.TYPE, deriveType(result));
        attributes.put(MemorySpanAttributes.ASYNC, Boolean.FALSE);

        emit(traceId, "retrieval", "memory." + MemorySpanAttributes.OP_RECALL,
                recallInput(result), recallOutput(result), latencyMs,
                MemorySpanAttributes.STATUS_OK, attributes);

        audit(new MemorySpanRecord(0L, traceId, sessionId, userId, tenantId,
                MemorySpanAttributes.PHASE_SYNC, MemorySpanAttributes.OP_RECALL,
                topLayer(result), joinIds(result), null, topScore(result),
                result.items().size(), result.truncated(), latencyMs,
                MemorySpanAttributes.STATUS_OK, System.currentTimeMillis()));
    }

    /**
     * 记录一次写入的<b>同步骨架</b>（{@code status=pending}）。
     *
     * <p>骨架的意义：异步回写发生在链路收尾之后，光靠审计表看不出「这次回写曾经属于哪条链路」。
     * 同步阶段先把 pending 记在链路里，异步结果再落到审计表，两边靠 {@code traceId} 对齐。</p>
     *
     * <p>骨架上的 {@code memory.async=true} 是<b>业务属性</b>（这次写入以异步方式完成），
     * 与相位无关 —— 本 span 发在同步阶段，属正常路径。</p>
     *
     * @param queueDepth 入队后的队列深度（用于观察积压趋势）
     */
    public void recordWritePending(String traceId, String sessionId, String userId, String tenantId,
                                   int queueDepth, long latencyMs) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(MemorySpanAttributes.OPERATION, MemorySpanAttributes.OP_WRITE);
        attributes.put(MemorySpanAttributes.ASYNC, Boolean.TRUE);
        attributes.put(MemorySpanAttributes.TENANT_ID, tenantId);
        attributes.put(MemorySpanAttributes.LAYER, MemorySpanAttributes.LAYER_L0);

        emit(traceId, "memory", "memory." + MemorySpanAttributes.OP_WRITE, null,
                "{\"phase\":\"queued\",\"queueDepth\":" + queueDepth + "}", latencyMs,
                MemorySpanAttributes.STATUS_PENDING, attributes);

        audit(new MemorySpanRecord(0L, traceId, sessionId, userId, tenantId,
                MemorySpanAttributes.PHASE_SYNC, MemorySpanAttributes.OP_WRITE,
                MemorySpanAttributes.LAYER_L0, null, null, null, null, null, latencyMs,
                MemorySpanAttributes.STATUS_PENDING, System.currentTimeMillis()));
    }

    /**
     * 记录异步回写的结果。
     *
     * <p><b>只写审计表，不发 span</b>：此刻链路已收尾，向 {@code TraceCollector} 追加 span
     * 会既不被导出、也不被释放（详见类注释）。</p>
     *
     * @param tenantId        租户 ID
     * @param userId          用户 ID
     * @param traceId         链路 ID（与同步骨架对齐）
     * @param sessionId       会话 ID
     * @param newCount        新落库事实数（写入审计行的 {@code recallCount} 列；该列的语义随
     *                        {@code operation} 变化：召回时为召回条数，写入/抽取时为新落条数）
     * @param memoryIds       落库得到的记忆 ID
     * @param latencyMs       异步阶段耗时
     * @param status          终态（ok / failed / dropped）
     */
    public void recordAsyncWriteback(String tenantId, String userId, String traceId, String sessionId,
                                     int newCount, List<String> memoryIds,
                                     long latencyMs, String status) {
        audit(new MemorySpanRecord(0L, traceId, sessionId, userId, tenantId,
                MemorySpanAttributes.PHASE_ASYNC, MemorySpanAttributes.OP_WRITE,
                MemorySpanAttributes.LAYER_L1, joinIds(memoryIds), null, null, newCount, null,
                latencyMs, status, System.currentTimeMillis()));
    }

    /**
     * 记录无活跃链路的动作（定时淘汰 / 合并 / 显式删除）。
     *
     * <p>这类动作由定时任务或管理接口触发，<b>没有 traceId</b> 可挂：硬塞一个伪造 ID
     * 会让「按 traceId 反查」这条路径失效。因此只写审计表并以 {@code operation} 区分。</p>
     */
    public void recordDetached(String operation, String tenantId, String layer, List<String> ids,
                               long latencyMs, String status) {
        audit(new MemorySpanRecord(0L, null, null, null, tenantId,
                MemorySpanAttributes.PHASE_ASYNC, operation, layer, joinIds(ids), null, null,
                ids == null ? 0 : ids.size(), null, latencyMs, status,
                System.currentTimeMillis()));
    }

    /**
     * 记录一次<b>被丢弃的回写</b>（队列积压超限，PRD §9.2）。
     *
     * <p><b>为什么不复用 {@link #recordDetached}</b>：丢弃发生在<b>已知链路</b>上，而
     * {@code recordDetached} 按「无活跃链路」语义把 {@code traceId} 写成 {@code null}，
     * 于是「哪个用户、哪一轮对话的记忆被丢了」再也查不出来 —— 而这恰恰是排查背压时
     * 唯一有用的信息。丢弃必须可归因，否则「指标正常」与「数据在丢」可以同时成立。</p>
     *
     * <p>操作记为 {@code write}、阶段记为 {@code async}，与正常的异步回写行同口径；
     * 用 {@code status=dropped} 区分终态，因此不会污染「异步写入完成数」的统计。</p>
     *
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param traceId   链路 ID（丢弃也保留，丢弃必须可归因）
     * @param sessionId 会话 ID
     * @param latencyMs 从提交到被拒绝的耗时
     */
    public void recordDroppedWriteback(String tenantId, String userId, String traceId,
                                       String sessionId, long latencyMs) {
        audit(new MemorySpanRecord(0L, traceId, sessionId, userId, tenantId,
                MemorySpanAttributes.PHASE_ASYNC, MemorySpanAttributes.OP_WRITE,
                MemorySpanAttributes.LAYER_L0, null, null, null, null, null, latencyMs,
                MemorySpanAttributes.STATUS_DROPPED, System.currentTimeMillis()));
    }

    /* ---------------- 内部 ---------------- */

    /** 发 span：任何异常都不允许反噬主流程，但必须留痕。 */
    private void emit(String traceId, String stepType, String name, String inputJson,
                      String outputJson, long latencyMs, String status,
                      Map<String, Object> attributes) {
        if (traceId == null) {
            return;
        }
        emitted.incrementAndGet();
        if (PHASE_AFTER_TRACE_END.equals(attributes.get(INTERNAL_PHASE))) {
            // 链路收尾后发 span 是明确禁止的路径（span 不导出 + 不释放）。本模块没有该路径，
            // 一旦有改动走到这里必须可见 —— 因此计数 + WARN，而不是静默丢弃。
            emittedAsync.incrementAndGet();
            log.warn("[memory-span] 链路收尾后尝试发送 span（违反 §4.4 硬约束）traceId={} name={}",
                    traceId, name);
        }
        retain(traceId, new MemorySpanView(name, stepType, status, latencyMs,
                Map.copyOf(attributes)));
        try {
            sink.step(traceId, stepType, name, inputJson, outputJson, latencyMs, status,
                    attributes);
        } catch (Exception e) {
            log.warn("[memory-span] 埋点发送失败（不影响业务）traceId={} name={}：{}",
                    traceId, name, e.toString());
        }
    }

    /** 留存 span 副本（超出单链路上限时丢弃后续并告警一次）。 */
    private void retain(String traceId, MemorySpanView view) {
        List<MemorySpanView> spans = retainedTraces.computeIfAbsent(
                traceId, key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (spans) {
            if (spans.size() < MAX_RETAINED_SPANS) {
                spans.add(view);
            }
        }
    }

    /**
     * 该链路里由本模块发出的 span 副本（时间序）。
     *
     * <p>返回的是<b>本模块发出的记忆 span</b>，不是整条链路的全部 span ——
     * 完整链路 span 由导出器与观测模块负责。</p>
     *
     * @param traceId 链路 ID
     * @return span 列表；无记录返回空列表
     */
    public List<MemorySpanView> spansOf(String traceId) {
        if (traceId == null) {
            return List.of();
        }
        List<MemorySpanView> spans = retainedTraces.get(traceId);
        if (spans == null) {
            return List.of();
        }
        synchronized (spans) {
            return List.copyOf(spans);
        }
    }

    /** 累计发出的 span 数（进程内）。 */
    public int emittedCount() {
        return emitted.get();
    }

    /** 「链路收尾后发 span」的累计次数；恒为 0 才是正确状态（标准 9 断言此值）。 */
    public int emittedAsyncCount() {
        return emittedAsync.get();
    }

    /**
     * 本模块发往链路的 span 副本。
     *
     * <p>字段与 {@code GenAiTraceObserver} 落到 span 上的内容一致，使面板无需再查链路存储
     * 就能回答「这次对话召回了几条、分数多少、用的是哪个算法」。</p>
     *
     * @param name       步骤名（如 {@code memory.recall}）
     * @param stepType   规范词（{@code retrieval} / {@code memory}）
     * @param status     ok / failed / pending
     * @param latencyMs  耗时毫秒
     * @param attributes 结构化属性（{@code memory.*} 等）
     */
    public record MemorySpanView(String name, String stepType, String status, long latencyMs,
                                 Map<String, Object> attributes) {
    }

    /** 写审计行：失败必须可见，否则「指标一直为空」会被误读成「没有记忆操作」。 */
    private void audit(MemorySpanRecord record) {
        try {
            repository.saveMemorySpan(record);
        } catch (Exception e) {
            log.warn("[memory-span] 审计写入失败 traceId={} operation={}：{}",
                    record.traceId(), record.operation(), e.toString());
        }
    }

    /** 最高分（空列表为 0）。 */
    private static double topScore(RecallResult result) {
        double top = 0;
        for (RecallResult.Item item : result.items()) {
            top = Math.max(top, item.score());
        }
        return top;
    }

    /** 平均分（空列表为 0）。 */
    private static double avgScore(RecallResult result) {
        if (result.items().isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (RecallResult.Item item : result.items()) {
            sum += item.score();
        }
        return sum / result.items().size();
    }

    /** 命中条目的最大累计访问数。 */
    private static int maxAccessCount(RecallResult result) {
        int max = 0;
        for (RecallResult.Item item : result.items()) {
            max = Math.max(max, item.accessCount());
        }
        return max;
    }

    /** 首条的层（分数最高者）：表达「这次召回主要命中哪一层」。 */
    private static String topLayer(RecallResult result) {
        return result.items().isEmpty() ? MemorySpanAttributes.LAYER_L3
                : result.items().get(0).layer();
    }

    /**
     * 从召回结果派生 {@code memory.type}（M4-1，标准 11 的判据来源）。
     *
     * <p><b>取值口径</b>：取命中条目的类别。命中多条且类别不唯一时，取<b>分数最高那条</b>的类别
     * （与 {@link #topLayer} 同口径 —— 顶层就是排序后第一条），保证同一份结果
     * 「层的口径」与「类型的口径」指向同一条记忆，不会出现 A 条的 layer 配 B 条的 type。</p>
     *
     * <p><b>为什么空召回回落 {@code custom} 而不是 null</b>：埋点属性缺失会让面板该列空白，
     * 与「这条链路压根没走召回」无法区分。回落成显式取值后，
     * 「没召回出东西」也有一条自解释的记录（配合已有的
     * {@code memory.recall.count=0} / {@code hit=false} 更可解释）。</p>
     *
     * <p>本方法<b>不做</b>「本次请求的 types 白名单」回填：白名单是调用方的意图，
     * 本条属性表达的是<b>实际召回结果的类型</b>。两者可能不同（请求 procedural 但库里没有，
     * 召回为空），混用会让标准 11 的判据被意图污染、恒真。</p>
     *
     * @param result 召回结果
     * @return 命中首条的类别；空召回或类别缺失时回落 {@code custom}
     */
    private static String deriveType(RecallResult result) {
        if (result.items().isEmpty()) {
            return CATEGORY_FALLBACK;
        }
        String category = result.items().get(0).category();
        return category == null || category.isBlank() ? CATEGORY_FALLBACK : category;
    }

    /** 拼接记忆 ID（超过上限截断并附省略号，保留「共有多少个」的信息）。 */
    private static String joinIds(RecallResult result) {        List<String> ids = result.items().stream().map(RecallResult.Item::memoryId).toList();
        return joinIds(ids);
    }

    private static String joinIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        if (ids.size() <= MemorySpanRecord.MAX_AUDIT_IDS) {
            return String.join(",", ids);
        }
        return String.join(",", ids.subList(0, MemorySpanRecord.MAX_AUDIT_IDS))
                + ",...(" + ids.size() + ")";
    }

    /** 召回入参摘要（不含完整记忆内容，避免把用户数据复制进 span 输入）。 */
    private static String recallInput(RecallResult result) {
        return "{\"query\":\"" + safe(result.query()) + "\",\"topK\":" + result.topK()
                + ",\"minScore\":" + result.minScore() + "}";
    }

    /** 召回出参摘要（只给条数与截断信息，内容明细在审计表里可查）。 */
    private static String recallOutput(RecallResult result) {
        return "{\"items\":" + result.items().size() + ",\"candidates\":" + result.candidates()
                + ",\"injected\":" + result.injectedCount() + ",\"truncated\":"
                + result.truncated() + "}";
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
