package com.zimo.agent.memory.engine;

import com.zimo.agent.memory.governance.FactExtractor;
import com.zimo.agent.memory.governance.MemoryManager;
import com.zimo.agent.memory.governance.RecallResult;
import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆感知 Prompt 增强器：推理前主动召回并注入长期记忆（PRD §3.3）。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。</p>
 *
 * <h2>与「记忆工具」的区别（Push vs Pull）</h2>
 * <p>既有的 {@code memory_read} / {@code memory_search} 是 <b>Pull</b>：把工具注册进 Toolkit，
 * 由模型自主决定要不要读。问题是召回时机不可控、无法保证 Prompt 里有用户偏好，
 * 且埋点只能看到 {@code tool_call}。</p>
 * <p>本类是 <b>Push</b>：在推理<b>之前</b>主动召回并注入。时机确定、可埋点、可测量。
 * 两者不是替代关系 —— 首轮预召回保证「首轮推理就带着记忆」，轮内按需检索负责「深挖」。</p>
 *
 * <h2>为什么必须独立成类</h2>
 * <p>{@code AiAgentService} 的有效行已到 469（上限 500），净增预算只有 31 行。召回、组装、
 * 留痕、投递队列这些逻辑内联进去必然超限；因此服务层只保留一行调用，其余全在本类。</p>
 *
 * <h2>注入位置：系统提示词，不是 messages</h2>
 * <p>本类<b>只产片段</b>，放哪儿交给 {@code MemoryPromptMiddleware#onSystemPrompt} 决定。
 * 早期版本自己构造一条 {@code MsgRole.SYSTEM} 消息插进 messages，真机被 AgentScope 拒绝
 * （{@code Hooks must not inject SYSTEM messages into PreCallEvent.inputMessages}），
 * 表现为<b>每条命中记忆的对话都失败</b>；且 2.0.2 的 {@code PreCallEvent} 并没有错误信息里
 * 推荐的 {@code appendSystemContent}。放系统提示词另有一个语义理由：记忆是可能过期的背景事实，
 * 伪装成用户发言会被模型当作<b>新的用户指令</b>照办，那正是 PRD R3 想避免的失败模式。</p>
 *
 * <h2>三条不可动摇的设计约束</h2>
 * <ol>
 *   <li><b>必须声明「以用户当前陈述为准」</b>：记忆是可能过期的旧事实。直接告诉模型
 *       「用户偏好 X」而不加这条约束，会让用户改了偏好之后仍然被反复按旧偏好处理，
 *       而且用户无法理解为什么改不掉（PRD R3）。</li>
 *   <li><b>空召回不注入任何内容</b>：连标题都不加。注入「暂无记忆」这类占位文本会污染
 *       上下文并破坏 Prompt 缓存命中（新用户每次都拿到不同的前缀）。</li>
 *   <li><b>注入内容必须进 L0</b>：否则「模型当时到底看到了什么」不可追溯，
 *       记忆引发的错误行为无法复盘。</li>
 * </ol>
 *
 * <h2>失败不反噬</h2>
 * <p>记忆是<b>增强</b>能力：召回失败、留痕失败、队列满，都不允许影响对话本身。
 * 每一处旁路动作都 try/catch 且打 WARN —— 静默吞掉是禁止的（本项目已因静默失败踩过坑）。</p>
 *
 * @author zimo
 * @since 2026-09-16
 */
public class MemoryAwarePromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(MemoryAwarePromptBuilder.class);

    /** 片段标题（独立成块：可开关、可计数、可单独评测，不与知识库注入混在一起）。 */
    public static final String FRAGMENT_TITLE = "## 已知的用户长期记忆";
    /**
     * 片段首行：标题 + 冲突声明。
     *
     * <p>冲突声明是本模块与「让用户改不掉偏好」之间唯一的防线，不允许在重构中被省略。</p>
     */
    public static final String FRAGMENT_HEADER =
            FRAGMENT_TITLE + "（仅供参考，若与用户当前陈述冲突，以用户当前陈述为准）";

    /** L0 留痕的 {@code source} 取值（对齐 {@link L0RawLog} 的 Trajectory 来源枚举）。 */
    public static final String SOURCE_CONTEXT_INJECTION = "context_injection";

    private final MemoryManager memoryManager;
    private final MemorySpanRecorder recorder;
    private final MemoryWritebackQueue writebackQueue;
    /** L0 留痕用；允许为空（留痕关闭时只发 span 与审计）。 */
    private final OltpMemoryRepository repository;
    /** L3 画像片段组装器；允许为空（未装配时本类只负责 L1）。 */
    private final PersonaFragmentComposer personaComposer;
    /** 总开关：关闭后 {@link #recallFragment} 与 {@link #personaFragment} 都返回 null。 */
    private final boolean enabled;

    /**
     * 兼容旧装配的构造：不注入 L3 画像（仅 L1 预召回）。
     *
     * @param memoryManager  记忆门面（召回与抽取的唯一入口）
     * @param recorder       审计与 span 记录器
     * @param writebackQueue 异步回写队列
     * @param repository     四层仓储（写 L0 注入留痕）；可为 {@code null}
     * @param enabled        是否启用预召回
     */
    public MemoryAwarePromptBuilder(
            MemoryManager memoryManager,
            MemorySpanRecorder recorder,
            MemoryWritebackQueue writebackQueue,
            OltpMemoryRepository repository,
            boolean enabled) {
        this(memoryManager, recorder, writebackQueue, repository, enabled, null);
    }

    /**
     * @param memoryManager   记忆门面（召回与抽取的唯一入口）
     * @param recorder        审计与 span 记录器
     * @param writebackQueue  异步回写队列
     * @param repository      四层仓储（写 L0 注入留痕）；可为 {@code null}
     * @param enabled         是否启用预召回
     * @param personaComposer L3 画像组装器；为 {@code null} 时不做画像注入
     */
    public MemoryAwarePromptBuilder(
            MemoryManager memoryManager,
            MemorySpanRecorder recorder,
            MemoryWritebackQueue writebackQueue,
            OltpMemoryRepository repository,
            boolean enabled,
            PersonaFragmentComposer personaComposer) {
        this.memoryManager = memoryManager;
        this.recorder = recorder;
        this.writebackQueue = writebackQueue;
        this.repository = repository;
        this.enabled = enabled;
        this.personaComposer = personaComposer;
    }

    /**
     * 组装本轮要注入的 L3 用户画像片段（跨会话稳定画像）。
     *
     * <p><b>为什么它是独立方法而不是并进 {@link #recallFragment}</b>：两条数据来源的成功率
     * 与生命周期都不同 —— L1 走打分召回、每轮都变；L3 按类别精确查表、多轮不变。
     * 合并后任一侧失败都会连带另一侧，表现为"画像查不到，连偏好记忆也不注入了"。</p>
     *
     * @param scope   隔离维度；不可用时返回 {@code null}
     * @param traceId 链路 ID；为空时跳过留痕但仍返回片段
     * @return 画像片段；未启用、无画像或读取失败时返回 {@code null}
     */
    public String personaFragment(MemoryScope scope, String traceId) {
        if (!enabled || personaComposer == null) {
            return null;
        }
        return personaComposer.compose(scope, traceId);
    }

    /**
     * 预召回并组装出本轮要注入的记忆片段。
     *
     * <h2>调用时机</h2>
     * <p>由 {@code MemoryPromptMiddleware#onSystemPrompt} 在框架组装完系统提示词之后调用。
     * 时机必须在 {@code TraceCollector.begin()} <b>之后</b> —— 没有 traceId 就无法给召回埋点，
     * 因此 traceId 为空时直接不做。</p>
     *
     * <h2>为什么要独立成本方法（不再自己拼 messages）</h2>
     * <p>早期版本由本类构造一条 {@code MsgRole.SYSTEM} 消息插进 messages，真机被 AgentScope
     * 拒绝（{@code Hooks must not inject SYSTEM messages into PreCallEvent.inputMessages}），
     * 表现为<b>每条命中记忆的对话都失败</b>。2.0.2 的 {@code PreCallEvent} 没有错误信息里
     * 推荐的 {@code appendSystemContent}，唯一受支持的注入点是中间件的
     * {@code onSystemPrompt}。因此本类退化为「只产片段」，<b>放哪儿</b>交给中间件决定。</p>
     *
     * @param scope   隔离维度；不可用时返回 {@code null}
     * @param query   召回查询文本（通常就是用户这句话）
     * @param traceId 链路 ID；为空时不做任何事（不埋无主的点）
     * @return 记忆片段文本；无条目达到注入阈值、召回失败、或未启用时返回 {@code null}
     */
    public String recallFragment(MemoryScope scope, String query, String traceId) {
        if (!enabled || scope == null || !scope.usable() || traceId == null) {
            return null;
        }
        long start = System.currentTimeMillis();
        RecallResult result;
        try {
            result = memoryManager.recall(scope.tenantId(), scope.userId(), query,
                    recallTypes(scope), excludeTypes(scope), null);
        } catch (RuntimeException e) {
            // 召回失败不阻断对话：没有记忆的对话仍是可用的对话
            log.warn("[memory-prefetch] 预召回失败，本轮不注入记忆 traceId={}：{}", traceId, e.toString());
            return null;
        }
        long latency = System.currentTimeMillis() - start;
        recorder.recordRecall(traceId, scope.sessionId(), scope.userId(), scope.tenantId(),
                result, recallStrategy(scope), latency);

        String fragment = composeFragment(result);
        if (fragment == null) {
            // 空召回或全部低于注入阈值：连标题都不加，避免污染上下文与破坏缓存命中
            log.info("[memory-prefetch] traceId={} 召回 {} 条，但无一条达到注入阈值 {}",
                    traceId, result.items().size(), result.minScore());
            return null;
        }
        recordInjection(scope, traceId, fragment, result);
        log.info("[memory-prefetch] traceId={} 召回 {} 条，注入 {} 条（算法 {}，策略 {}，耗时 {}ms）",
                traceId, result.items().size(), result.injectedCount(), result.algorithm(),
                recallStrategy(scope), latency);
        return fragment;
    }

    /**
     * 本次召回要拉取哪些类别（M4-2a，标准 11 的正向路径）。
     *
     * <p><b>计划模式的语义</b>：{@code plan} 类型的智能体要先规划再执行，
     * 此时「这类任务通常分几步、哪一步需要人工确认」这类<b>程序性记忆</b>对规划质量的
     * 影响最大 —— 它是可复用的方法论，而不是某一轮对话的细节。所以计划模式把它拉进来。</p>
     *
     * <p><b>为什么现在只拉 procedural 而不叠加其他类别</b>：程序性记忆与用户偏好 / 画像走的是
     * 同一次召回、同一个 topK 名额。叠加会让计划模式下的偏好类记忆被程序性记忆挤出 topK
     * —— 那是「为了通过标准 11 而引入的真实退化」。当前取舍是计划模式优先服务规划质量，
     * 由 {@code excludeTypes} 保证它至少不会漏掉本该拿到的程序性记忆。
     * 若将来要改成「procedural ∪ 全类别」，需同时确认 topK 足够容纳两类内容。</p>
     *
     * @param scope 隔离维度
     * @return 计划模式返回仅含程序性记忆的白名单；其他情况返回 {@code null}（不限类别）
     */
    private static List<String> recallTypes(MemoryScope scope) {
        return scope.planMode() ? List.of(FactExtractor.CATEGORY_PROCEDURAL) : null;
    }

    /**
     * 本次召回要排除哪些类别（M4-2a，标准 11 的隔离路径）。
     *
     * <p><b>这里是真机测试暴露出的缺陷点，值得单独说明</b>：最初只做了
     * {@link #recallTypes}（非计划模式返回 {@code null} = 不限类别），
     * 结果单测直接抓到「conversation 模式下程序性记忆仍被注入」——
     * 因为 {@code null} 的语义是「什么都要」，而不是「不要 procedural」。
     * <b>「没加白名单」不等于「被排除」</b>，这两个语义在允许列表模型里无法互换。</p>
     *
     * <p>若不做这一步，标准 11 的隔离维度会完全失效，而正向用例（plan 模式召回成功）
     * 仍然全绿 —— 属于典型的「单向测试放过的缺陷」。</p>
     *
     * @param scope 隔离维度
     * @return 非计划模式返回 {@code [procedural]}（排除程序性记忆）；计划模式返回 {@code null}
     */
    private static List<String> excludeTypes(MemoryScope scope) {
        return scope.planMode() ? null : List.of(FactExtractor.CATEGORY_PROCEDURAL);
    }

    /**
     * 召回策略标识（埋点用）。
     *
     * <p>计划模式落 {@value MemorySpanAttributes#STRATEGY_PREFETCH_PLAN}，与普通预召回区分开
     * —— 标准 11 要求链路里能看出「这是计划模式特有的那次召回」，
     * 若两种策略都记成 {@code prefetch}，判据就退化成了「有过召回」，失去了隔离意义。</p>
     *
     * @param scope 隔离维度
     * @return 计划模式返回 {@code prefetch_plan}；否则返回 {@code prefetch}
     */
    private static String recallStrategy(MemoryScope scope) {
        return scope.planMode() ? MemorySpanAttributes.STRATEGY_PREFETCH_PLAN
                : MemorySpanAttributes.STRATEGY_PREFETCH;
    }

    /**
     * 回复结束后触发异步事实回写（PRD §7.2）。
     *
     * <p>调用时机：拿到回复之后、{@code TraceCollector.endFor()} <b>之前</b>。
     * 必须在收尾之前，因为同步骨架 span（{@code status=pending}）只有此刻发出才会被导出；
     * 收尾之后发出的 span 会「既不被导出、也不被释放」（PRD §4.4）。</p>
     *
     * @param scope       隔离维度
     * @param traceId     链路 ID
     * @param userMessage 本轮用户消息
     * @param reply       本轮助手回复
     */
    public void afterReply(MemoryScope scope, String traceId, String userMessage, String reply) {
        if (!enabled || scope == null || !scope.usable() || traceId == null) {
            return;
        }
        long ts = System.currentTimeMillis();
        List<L0RawLog> turn = new ArrayList<>(2);
        if (hasText(userMessage)) {
            turn.add(L0RawLog.forInsert(traceId, scope.sessionId(), scope.userId(), ts,
                    "user", userMessage, null, null));
        }
        if (hasText(reply)) {
            turn.add(L0RawLog.forInsert(traceId, scope.sessionId(), scope.userId(), ts,
                    "assistant", reply, null, null));
        }
        if (turn.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        int depth = writebackQueue.submit(new MemoryWritebackQueue.Task(
                scope.tenantId(), scope.userId(), traceId, scope.sessionId(), List.copyOf(turn)));
        if (depth < 0) {
            // 已被丢弃：不再发 pending 骨架，避免留下「永远等不到完成」的假记录
            return;
        }
        recorder.recordWritePending(traceId, scope.sessionId(), scope.userId(), scope.tenantId(),
                depth, System.currentTimeMillis() - start);
    }

    /**
     * 组装记忆片段。
     *
     * <p>格式对齐 PRD §3.3：{@code - [类别|分数] 内容}。分数保留两位小数并固定
     * {@link Locale#ROOT} —— 用默认 Locale 时某些区域会把小数点渲染成逗号，
     * 同一个片段在不同机器上长得不一样，评测时难以对比。</p>
     *
     * @param result 召回结果
     * @return 片段文本；无条目达到注入阈值时返回 {@code null}（调用方据此跳过注入）
     */
    String composeFragment(RecallResult result) {
        List<RecallResult.Item> injected = new ArrayList<>();
        for (RecallResult.Item item : result.items()) {
            if (item.meetsThreshold(result.minScore())) {
                injected.add(item);
            }
        }
        if (injected.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(FRAGMENT_HEADER).append('\n');
        for (RecallResult.Item item : injected) {
            sb.append("- [").append(text(item.category())).append('|')
                    .append(String.format(Locale.ROOT, "%.2f", item.score())).append("] ")
                    .append(text(item.content())).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 注入留痕：把「模型看到了什么」原样写进 L0，失败不阻断对话但必须留痕。 */
    private void recordInjection(MemoryScope scope, String traceId, String fragment,
                                 RecallResult result) {
        if (repository == null) {
            return;
        }
        try {
            repository.saveRawLog(L0RawLog.forInsert(traceId, scope.sessionId(), scope.userId(),
                    System.currentTimeMillis(), "system", fragment, null, metaJson(result),
                    SOURCE_CONTEXT_INJECTION));
        } catch (RuntimeException e) {
            log.warn("[memory-prefetch] 注入留痕写入失败 traceId={}：{}", traceId, e.toString());
        }
    }

    /** 留痕元数据：注入条数、召回算法、阈值。溯源时要能一眼看出「当时的判定边界」。 */
    private static String metaJson(RecallResult result) {
        return "{\"injected\":" + result.injectedCount()
                + ",\"recalled\":" + result.items().size()
                + ",\"algorithm\":\"" + result.algorithm() + "\""
                + ",\"minScore\":" + result.minScore() + "}";
    }

    private static String text(String value) {
        return value == null ? "" : value.replace('\n', ' ').trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
