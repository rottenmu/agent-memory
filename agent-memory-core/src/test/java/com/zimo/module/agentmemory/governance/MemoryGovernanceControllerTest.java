package com.zimo.module.agentmemory.governance;

import static com.zimo.module.agentmemory.governance.MemoryManagerFixture.QUERY;
import static com.zimo.module.agentmemory.governance.MemoryManagerFixture.TENANT;
import static com.zimo.module.agentmemory.governance.MemoryManagerFixture.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.UserMemoryRow;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 记忆治理端点契约测试（PRD §6.2 的七个新增端点）。
 *
 * <p>走 MockMvc 而不是直接调 Controller 方法，是为了覆盖真正容易出错的一层：
 * <b>路径与方法映射</b>（写错路径的接口单测全绿但线上 404）、<b>JSON 与 record 的
 * 双向转换</b>（record 反序列化要求构造器参数名可解析，组件名与 JSON 字段必须对得上）。
 * 这些在直接调方法的测试里全都被绕过。</p>
 *
 * <p><b>为什么这里不测 4xx</b>：参数异常由应用级 {@code GlobalExceptionHandler}
 * 转成 400，而那个类在 {@code agent-application} 模块，本模块测试类路径拿不到。
 * 若在测试里本地镜像一个 advice，测试会与真实行为脱钩（改了真 handler 测试照样绿）——
 * 所以这里只断言「抛出了参数异常且原因可读」，真实 HTTP 状态码由 M2-7 真机请求验证。</p>
 *
 * <p><b>夹具用真实 H2 仓储</b>：端点层要验证的是「请求进去、数据真的落库」，
 * 换成 Fake 就把待验证的链路替换成假的了（与 {@link MemoryManagerFixture} 同一立场）。</p>
 */
class MemoryGovernanceControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MemoryManagerFixture fixture;
    private MockMvc mockMvc;

    /**
     * 建夹具与端点。
     *
     * @param db 内存库名；**每个用例必须不同**，否则共享的 {@code DB_CLOSE_DELAY=-1}
     *           内存库会让用例相互污染
     */
    private void prepare(String db) {
        fixture = MemoryManagerFixture.create(db);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MemoryGovernanceController(fixture.manager(), fixture.spanRecorder()))
                .build();
    }

    /* ---------------- 批量写入 ---------------- */

    @Test
    @DisplayName("批量写入：逐条回执，非法 target 与空内容各自被拒且不拖垮整批")
    void batchWriteReturnsPerItemReceipts() throws Exception {
        prepare("gov_batch");

        String body = """
                {"tenantId":"t1","items":[
                  {"target":"user","userId":"u1","category":"custom","content":"以后都用 Excel 导出报表"},
                  {"target":"user","userId":"u1","category":"custom","content":"用户偏好中文回复"},
                  {"target":"gloabl","userId":"u1","category":"custom","content":"拼错的写入目标"},
                  {"target":"user","userId":"u1","category":"custom","content":"   "}
                ]}
                """;

        mockMvc.perform(post("/api/ai/memory/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.succeeded").value(2))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].memoryId").isNotEmpty())
                .andExpect(jsonPath("$.results[2].ok").value(false))
                .andExpect(jsonPath("$.results[2].target").value("gloabl"))
                .andExpect(jsonPath("$.results[2].reason").value(containsString("不支持的写入目标")))
                .andExpect(jsonPath("$.results[3].reason").value(containsString("content")));

        // 被拒的两条不能进库：只断言回执会让「报错了但其实也写进去了」蒙混过关
        assertThat(fixture.repository().pageUserMemory(TENANT, USER, null, 10, 0)).hasSize(2);
    }

    @Test
    @DisplayName("批量写入超过 100 条时抛参数异常，不静默截断")
    void batchWriteRejectsOverLimit() {
        prepare("gov_batch_limit");
        List<WriteItem> items = IntStream.range(0, 101)
                .mapToObj(i -> WriteItem.user(USER, "custom", "记忆-" + i, null))
                .toList();

        assertThatThrownBy(() -> new MemoryGovernanceController(fixture.manager(), fixture.spanRecorder())
                .batch(new BatchWriteRequest(TENANT, items)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 100 条");
    }

    /* ---------------- 批量读取 ---------------- */

    @Test
    @DisplayName("批量读取：命中与未命中分别返回，missRatio 可见")
    void batchQuerySeparatesMissingIds() throws Exception {
        prepare("gov_batch_query");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "记忆一");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "记忆二");
        List<String> ids = fixture.repository().pageUserMemory(TENANT, USER, null, 10, 0)
                .stream().map(UserMemoryRow::memoryId).toList();
        String body = "{\"tenantId\":\"t1\",\"userId\":\"u1\",\"ids\":[\""
                + ids.get(0) + "\",\"" + ids.get(1) + "\",\"ghost\"]}";

        mockMvc.perform(post("/api/ai/memory/batch/query")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.found.length()").value(2))
                .andExpect(jsonPath("$.missing.length()").value(1))
                .andExpect(jsonPath("$.missing[0]").value("ghost"))
                .andExpect(jsonPath("$.allFound").value(false));
    }

    /* ---------------- 召回 ---------------- */

    @Test
    @DisplayName("召回：分数降序、命中分量为真、truncated 等边界信号出现在 JSON 里")
    void recallReturnsScoredItems() throws Exception {
        prepare("gov_recall");
        fixture.seedStandard14();

        MvcResult result = mockMvc.perform(post("/api/ai/memory/recall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"userId\":\"u1\",\"query\":\""
                                + QUERY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").value(3))
                .andExpect(jsonPath("$.algorithm").value(MemoryScorer.ALGORITHM_S1_HYBRID))
                // record 的派生访问器必须真的进 JSON：前端靠 truncated 判断候选池是否漏了数据，
                // 这个字段一旦没序列化，PRD「所有跨度上限必须可见」就在 HTTP 层静默失效
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].content").value(containsString("Excel")))
                .andReturn();

        JsonNode items = JSON.readTree(result.getResponse().getContentAsString()).get("items");
        double first = items.get(0).get("score").asDouble();
        double second = items.get(1).get("score").asDouble();
        double third = items.get(2).get("score").asDouble();
        assertThat(first).isGreaterThanOrEqualTo(second);
        assertThat(second).isGreaterThanOrEqualTo(third);
        assertThat(items.get(0).get("match").asDouble()).as("查询词命中，match 必须 > 0").isPositive();
    }

    /* ---------------- 抽取 ---------------- */

    @Test
    @DisplayName("抽取：默认只回显候选不落库，persist 显式开启后才写库")
    void extractDefaultsToNoPersist() throws Exception {
        prepare("gov_extract");
        fixture.repository().saveRawLog(L0RawLog.forInsert(
                "trace-x", "s-1", USER, System.currentTimeMillis(), "user",
                "以后都用 Excel 导出报表，另外我叫李雷", null, null));

        mockMvc.perform(post("/api/ai/memory/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"userId\":\"u1\",\"traceId\":\"trace-x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persist").value(false))
                .andExpect(jsonPath("$.candidates").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.persisted").value(0))
                .andExpect(jsonPath("$.llmApplied").value(false));

        assertThat(fixture.repository().countAtomicByTenant(TENANT)
                + fixture.repository().countPersonaByTenant(TENANT))
                .as("默认预演模式下不能有任何落库").isZero();

        mockMvc.perform(post("/api/ai/memory/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"userId\":\"u1\",\"traceId\":\"trace-x\","
                                + "\"persist\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persist").value(true))
                .andExpect(jsonPath("$.persisted").value(greaterThan(0)));

        assertThat(fixture.repository().countAtomicByTenant(TENANT)
                + fixture.repository().countPersonaByTenant(TENANT)).isPositive();
    }

    @Test
    @DisplayName("抽取：traceId 与 sessionId 都为空时抛参数异常，而不是返回「抽到 0 条」")
    void extractRejectsWhenNoSource() {
        prepare("gov_extract_nosource");

        assertThatThrownBy(() -> new MemoryGovernanceController(fixture.manager(), fixture.spanRecorder())
                .extract(new ExtractRequest(TENANT, USER, null, null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId");
    }

    /* ---------------- 合并 ---------------- */

    @Test
    @DisplayName("合并：不传 dryRun 时默认预演，显式 false 才真的删")
    void mergeDefaultsToDryRun() throws Exception {
        prepare("gov_merge");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "以后都用 Excel 导出报表");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "以后都用 Excel 导出报表明细");

        mockMvc.perform(post("/api/ai/memory/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"userId\":\"u1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.deletedCount").value(0))
                .andExpect(jsonPath("$.plan.groups.length()").value(1))
                .andExpect(jsonPath("$.plan.groups[0].mergedIds.length()").value(1));
        assertThat(fixture.repository().countAtomicByTenant(TENANT)).isEqualTo(2);

        mockMvc.perform(post("/api/ai/memory/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"userId\":\"u1\",\"dryRun\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.deletedCount").value(1));

        assertThat(fixture.repository().countAtomicByTenant(TENANT)).isEqualTo(1);
    }

    /* ---------------- 淘汰 ---------------- */

    @Test
    @DisplayName("淘汰：默认预演且安全阀可见，阈值来自请求")
    void evictDefaultsToDryRun() throws Exception {
        prepare("gov_evict");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "早已过时的临时结论");
        fixture.backdate("早已过时的临时结论", 200);

        mockMvc.perform(post("/api/ai/memory/evict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"threshold\":0.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.deletedCount").value(0))
                .andExpect(jsonPath("$.plan.threshold").value(0.5))
                .andExpect(jsonPath("$.plan.evicted.length()").value(1))
                .andExpect(jsonPath("$.plan.evicted[0].content").value("早已过时的临时结论"))
                // 1/1 远超 10% 安全阀：预演时就必须能看出来，否则真跑一次就是全删
                .andExpect(jsonPath("$.suspicious").value(true));

        assertThat(fixture.repository().countAtomicByTenant(TENANT)).isEqualTo(1);
    }

    /* ---------------- 指标 ---------------- */

    @Test
    @DisplayName("指标：按租户统计，未传 tenantId 时落默认租户")
    void metricsAreTenantScoped() throws Exception {
        prepare("gov_metrics");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "租户一记忆");
        fixture.service().saveUserMemory("t2", USER, "custom", "租户二记忆");

        mockMvc.perform(get("/api/ai/memory/metrics").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userMemoryTotal").value(1))
                .andExpect(jsonPath("$.l1Count").value(1))
                .andExpect(jsonPath("$.hasRecallData").value(false))
                .andExpect(jsonPath("$.since").isNotEmpty());

        mockMvc.perform(get("/api/ai/memory/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userMemoryTotal").value(0))
                .andExpect(jsonPath("$.extractRuns").value(0));
    }
}
