package com.zimo.module.agentmemory.governance;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆治理接口（PRD §6.2 新增端点，M2）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。接口统一前缀 {@code /api/ai/memory}，
 * 与既有的 {@code AiMemoryController} 共用一个前缀、互不重叠的二级路径 ——
 * 拆成两个类而不是塞进一个，是为了让「CRUD 兼容层」与「治理能力」各自保持在
 * {@code docs/rules/CODE_SIZE_RULES.md} 的 Controller 行数上限内，且改动互不干扰。</p>
 *
 * <p><b>权限规则</b>：与 {@code /api/ai/memory} 既有端点一致，本接口不做令牌鉴权，
 * 依赖部署侧网关或回环限制（跨机访问的 MCP 端点另有 {@code MemoryMcpAccessGuard} 令牌校验）。
 * 其中 {@code /merge}、{@code /evict} 会真实删除数据，部署到非受信网络前必须先加访问控制。</p>
 *
 * <p><b>返回体规范</b>：直接返回治理结果记录（{@link RecallResult}、{@link BatchOutcome} 等），
 * 由应用级 {@code GlobalExceptionHandler} 统一包成 {@code {code,msg,data}}；参数错误
 * （租户缺失、批量超限、抽取无来源）经 {@link IllegalArgumentException} 映射为
 * <b>信封 {@code code=400}</b>，业务性拒绝（白名单、敏感内容、非法写入目标）走 {@code code=200}
 * 且逐条给出 {@code reason} —— 批量场景下一条失败不该让整批请求失败。</p>
 *
 * <p><b>注意错误码的位置</b>：本仓库的 HTTP 状态码恒为 {@code 200}，错误一律读信封里的
 * {@code code}（真机实测：批量 101 条返回 {@code HTTP 200 + code 400}）。只看 HTTP 状态码
 * 会把所有参数错误当成成功。</p>
 *
 * <p><b>删除类端点默认预演</b>：{@code /merge}、{@code /evict} 的 {@code dryRun} 缺省为
 * {@code true}，必须显式传 {@code false} 才会落库。默认值选错方向的代价是不对称的 ——
 * 误预演只是白跑一次，误执行的代价是数据没了。</p>
 *
 * @author zimo
 * @since 2026-09-15
 */
@RestController
@RequestMapping("/api/ai/memory")
public class MemoryGovernanceController {

    /**
     * 请求未声明租户时的回落值。
     *
     * <p>与模块既有端点（{@code AiMemoryController} 的 {@code defaultValue="default"}、
     * MCP 工具集的 {@code str(args,"tenantId","default")}）保持一致：同一模块内
     * 「缺省租户」只能有一个语义，否则同名参数在不同端点会落到不同的桶里。</p>
     */
    private static final String DEFAULT_TENANT = "default";

    private final MemoryManager memoryManager;
    private final com.zimo.module.agentmemory.engine.MemorySpanRecorder spanRecorder;

    /**
     * @param memoryManager 记忆管理门面（六项治理能力的唯一入口）
     * @param spanRecorder  记忆 span 与审计记录器（{@code /span/{traceId}} 的数据源）；
     *                      允许为空 —— 未装配感知引擎时该端点返回空 spans，审计表照常可查
     */
    public MemoryGovernanceController(MemoryManager memoryManager,
            com.zimo.module.agentmemory.engine.MemorySpanRecorder spanRecorder) {
        this.memoryManager = memoryManager;
        this.spanRecorder = spanRecorder;
    }

    /**
     * 批量写入记忆（{@code POST /api/ai/memory/batch}）。
     *
     * <p>请求体字段见 {@link BatchWriteRequest}；{@code items} 上限
     * {@link MemoryManager#MAX_BATCH_ITEMS} 条，<b>超限即整批 400</b>，不静默截断。</p>
     *
     * <p><b>部分失败不回滚</b>：返回 {@code total / succeeded / rejected / results[]}，
     * 逐条给出成功记忆 id 或拒绝原因；调用方必须看 {@code rejected}，不能只看 HTTP 状态码。</p>
     *
     * @param request 批量写入入参；{@code items} 为空表示空批量，返回 0 成功 0 拒绝
     * @return 批量结果（含逐条回执与 {@code partial} 标记）
     */
    @PostMapping("/batch")
    public BatchOutcome batch(@RequestBody BatchWriteRequest request) {
        return memoryManager.batchWrite(tenantOf(request.tenantId()), request.items());
    }

    /**
     * 批量读取用户长期记忆（{@code POST /api/ai/memory/batch/query}）。
     *
     * <p>请求体字段见 {@link BatchQueryRequest}；id 查询下推到 SQL，不做「扫全量再过滤」。</p>
     *
     * @param request 批量读取入参；{@code ids} 去重后上限
     *                {@link MemoryManager#MAX_BATCH_QUERY_IDS} 个
     * @return 批量读取结果（{@code found} 命中行 + {@code missing} 未命中 id + {@code missRatio}）
     */
    @PostMapping("/batch/query")
    public BatchQueryOutcome batchQuery(@RequestBody BatchQueryRequest request) {
        return memoryManager.batchQuery(
                tenantOf(request.tenantId()), request.userId(), request.ids());
    }

    /**
     * 按查询文本召回用户长期记忆（{@code POST /api/ai/memory/recall}）。
     *
     * <p>请求体字段见 {@link RecallRequest}；分数口径为 S1 混合打分
     * （{@code match / decay / importance} 三分量，见 {@link RecallResult.Item}）。</p>
     *
     * <p><b>返回全部 topK 条目，不做阈值过滤</b>：注入阈值只由
     * {@link RecallResult#injectedCount()} 标出边界，是否进 Prompt 由调用方决定；
     * 同时必须看 {@link RecallResult#truncated()} —— 候选池被扫描上限截断时，
     * 可能存在「分数更高但没进候选池」的记忆。</p>
     *
     * @param request 召回入参；{@code userId} 必填，{@code query} 允许为空
     * @return 召回结果（含逐条分数分解、候选数、注入数、截断标记）
     */
    @PostMapping("/recall")
    public RecallResult recall(@RequestBody RecallRequest request) {
        return memoryManager.recall(tenantOf(request.tenantId()), request.userId(),
                request.query(), request.types(), request.excludeTypes(), request.topK());
    }

    /**
     * 手动触发事实抽取（{@code POST /api/ai/memory/extract}）。
     *
     * <p>请求体字段见 {@link ExtractRequest}；{@code traceId} 与 {@code sessionId}
     * 至少一个非空，两者都为空时 400 而不是返回「抽到 0 条」——「没抽到」与
     * 「参数没传对」对调用方是两件完全不同的事。</p>
     *
     * <p><b>{@code persist} 缺省为 {@code false}</b>：默认只回显候选不写库。
     * 回填场景请显式传 {@code true}，并用返回的
     * {@link ExtractOutcome#persisted()} / {@link ExtractOutcome#persistFailed()}
     * 核对实际落库条数（候选落 L3 画像或 L1 原子记忆，看 {@code items[].category}）。</p>
     *
     * @param request 抽取入参；{@code persist=true} 时 {@code userId} 必填
     * @return 抽取结果（扫描数、候选数、去重数、落库统计、候选明细）
     */
    @PostMapping("/extract")
    public ExtractOutcome extract(@RequestBody ExtractRequest request) {
        return memoryManager.extract(tenantOf(request.tenantId()), request.userId(),
                request.traceId(), request.sessionId(), flagOf(request.persist(), false));
    }

    /**
     * 手动触发记忆合并去重（{@code POST /api/ai/memory/merge}）。
     *
     * <p>请求体字段见 {@link MergeRequest}；{@code dryRun} 缺省为 {@code true}。</p>
     *
     * @param request 合并入参；{@code userId} 必填
     * @return 合并结果（{@code plan.groups} 为分组明细 + 实际删除 / 更新条数）
     */
    @PostMapping("/merge")
    public MemoryMerger.Outcome merge(@RequestBody MergeRequest request) {
        return memoryManager.merge(
                tenantOf(request.tenantId()), request.userId(), flagOf(request.dryRun(), true));
    }

    /**
     * 手动触发遗忘淘汰（{@code POST /api/ai/memory/evict}）。
     *
     * <p>请求体字段见 {@link EvictRequest}；{@code dryRun} 缺省为 {@code true}。</p>
     *
     * <p>返回值里的 {@link MemoryEvictor.Outcome#suspicious()} 是安全阀：为 {@code true}
     * 说明本次淘汰比例超过 {@link MemoryEvictor#SAFETY_RATIO}，大概率是阈值配错，
     * <b>不要</b>在非预演模式下忽略它。</p>
     *
     * @param request 淘汰入参；{@code tenantId} 为空时按 {@code default} 处理
     * @return 淘汰结果（计划明细 + 分数达标保留数 + 受保护数 + 实际删除条数）
     */
    @PostMapping("/evict")
    public MemoryEvictor.Outcome evict(@RequestBody EvictRequest request) {
        return memoryManager.evict(
                tenantOf(request.tenantId()), flagOf(request.dryRun(), true), request.threshold());
    }

    /**
     * 记忆运行指标（{@code GET /api/ai/memory/metrics}）。
     *
     * <p>存储侧数字（L0 总量、分层分布）来自带租户条件的精确 {@code COUNT(*)}；
     * 运行侧数字（召回 / 抽取 / 合并 / 淘汰）来自本进程累加，因此
     * {@link MemoryMetrics.Snapshot#since()} 表示本进程启动时刻 ——
     * 重启后低值不代表业务量下滑。</p>
     *
     * @param tenantId 租户标识；默认为 {@code default}；指标按租户统计，
     *                 不做跨租户聚合（否则该接口会变成规模探针）
     * @return 指标快照（含 {@code hasRecallData} / {@code recallScoreBelowAlarmLine} 判断位）
     */
    @GetMapping("/metrics")
    public MemoryMetrics.Snapshot metrics(
            @RequestParam(defaultValue = DEFAULT_TENANT) String tenantId) {
        return memoryManager.metrics(tenantOf(tenantId));
    }

    /**
     * 某条链路的记忆读写记录（{@code GET /api/ai/memory/span/{traceId}}，PRD §8.3）。
     *
     * <p>合并两个数据源：{@code spans}（本模块发往链路的 span 副本，含
     * {@code memory.recall.algorithm} 这类只在属性里的信息）+ {@code audit}
     * （{@code memory_span} 审计表，持久化事实，异步回写的结果只在这里）。</p>
     *
     * <p>三个判断位可直接断言，不必自己遍历：{@code recallSpanPresent}（链路里有无召回 span）、
     * {@code asyncArrived}（同步骨架与异步结果是否都到了）、
     * {@code chainPollutedByAsync}（是否出现过「链路已收尾还发 span」的违规路径，正常恒为 false）。</p>
     *
     * <p><b>spans 是进程内留存的</b>（只保留最近若干条链路），重启后该数组为空而 {@code audit}
     * 仍在；要判断「有没有发生过」请以 {@code audit} 为准。</p>
     *
     * @param traceId 链路 ID
     * @return 合并视图；traceId 从未出现过时返回空 spans + 空 audit，而不是 404 ——
     *         「这条链路没有记忆动作」与「traceId 写错了」对调用方都需要看到结构
     */
    @GetMapping("/span/{traceId}")
    public com.zimo.module.agentmemory.engine.MemorySpanTrace span(
            @org.springframework.web.bind.annotation.PathVariable String traceId) {
        return com.zimo.module.agentmemory.engine.MemorySpanTrace.of(
                traceId,
                spanRecorder == null ? java.util.List.of() : spanRecorder.spansOf(traceId),
                memoryManager.memorySpansOf(traceId),
                spanRecorder == null ? 0 : spanRecorder.emittedAsyncCount());
    }

    /* ---------------- 工具 ---------------- */
    /** 租户归一化：空白按默认租户处理，与模块既有端点同口径（见 {@link #DEFAULT_TENANT}）。 */
    private static String tenantOf(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId.trim();
    }

    /** 布尔入参归一化：{@code null} 取指定缺省值，避免自动拆箱空指针。 */
    private static boolean flagOf(Boolean flag, boolean defaultValue) {
        return flag == null ? defaultValue : flag;
    }
}
