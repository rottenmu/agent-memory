package com.zimo.agent.memory.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.agent.memory.governance.ImportanceScorer;
import com.zimo.agent.memory.mcp.MemoryMcpToolkit;
import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import com.zimo.agent.memory.storage.impl.H2OltpMemoryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 外部宿主对话导入 L0 的语义测试。
 *
 * <p>重点钉死三件容易被「看起来成功」骗过去的事：写后回查（record 吞异常）、
 * 跨调用幂等（同 traceId + 同内容不重复落库）、脱敏在落库前发生。</p>
 */
class DialogueTurnIngestServiceTest {

    private static final String RID = "req-ingest-1";

    private static H2OltpMemoryRepository repo(String db) {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", "sa", "");
        return new H2OltpMemoryRepository(ds);
    }

    private static AiMemoryService service(H2OltpMemoryRepository repo) {
        AiMemorySensitiveFilter filter = new AiMemorySensitiveFilter();
        return new AiMemoryService(repo, filter, MemorySecurityConfig.DEFAULT,
                new ImportanceScorer(filter));
    }

    private static Map<String, Object> turn(String msgId, String role, String content) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("msgId", msgId);
        one.put("requestId", RID);
        one.put("role", role);
        one.put("content", content);
        return one;
    }

    @Test
    @DisplayName("导入一轮 user/assistant 到 L0：role、source、content 都保持原样")
    void writesUserAndAssistantTurnsToL0() {
        H2OltpMemoryRepository repo = repo("ingest_write");
        AiMemoryService svc = service(repo);
        List<Map<String, Object>> turns = List.of(
                turn("m1", "user", "删除 module-security 有什么影响"),
                turn("m2", "assistant", "不会编译失败，但会静默丢掉安全能力"));

        Map<String, Object> result = svc.ingestTurns("default", "s1", "u1", turns);

        assertThat(result).containsEntry("inserted", 2).containsEntry("skipped", 0)
                .containsEntry("failed", 0);
        List<L0RawLog> rows = repo.listRawLogsByTrace(RID);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(L0RawLog::role).containsExactly("user", "assistant");
        assertThat(rows).extracting(L0RawLog::source)
                .containsExactly("user_message", "assistant_message");
        assertThat(rows.get(0).content()).isEqualTo("删除 module-security 有什么影响");
        assertThat(rows.get(0).metaJson()).contains("\"src\":\"workbuddy\"").contains("\"h\":");
    }

    @Test
    @DisplayName("重复导入同一批：第二次全部 skipped，L0 行数不增长")
    void isIdempotentAcrossCalls() {
        H2OltpMemoryRepository repo = repo("ingest_idem");
        AiMemoryService svc = service(repo);
        List<Map<String, Object>> turns = List.of(
                turn("m1", "user", "同一个问题"),
                turn("m2", "assistant", "同一个回答"));
        svc.ingestTurns("default", "s1", "u1", turns);

        Map<String, Object> second = svc.ingestTurns("default", "s1", "u1", turns);

        assertThat(second).containsEntry("inserted", 0).containsEntry("skipped", 2)
                .containsEntry("failed", 0);
        assertThat(repo.listRawLogsByTrace(RID)).hasSize(2);
    }

    @Test
    @DisplayName("导入前脱敏：手机号与邮箱原文不进 L0")
    void masksSensitiveContentBeforeWrite() {
        H2OltpMemoryRepository repo = repo("ingest_mask");
        AiMemoryService svc = service(repo);

        Map<String, Object> result = svc.ingestTurns("default", "s1", "u1",
                List.of(turn("m1", "user", "联系 13812345678 或 a@b.com")));

        assertThat(result).containsEntry("inserted", 1);
        String stored = repo.listRawLogsByTrace(RID).get(0).content();
        assertThat(stored).doesNotContain("13812345678", "a@b.com")
                .contains("138****5678", "***@***");
    }

    @Test
    @DisplayName("非法入参显式计数：空 sessionId 报 error，非法 role / 空内容计入 failed")
    void rejectsInvalidInput() {
        H2OltpMemoryRepository repo = repo("ingest_invalid");
        AiMemoryService svc = service(repo);

        assertThat(svc.ingestTurns("default", "s1", "u1", null))
                .containsEntry("inserted", 0);
        assertThat(svc.ingestTurns("default", "", "u1", List.of(turn("m1", "user", "x"))))
                .containsEntry("error", "sessionId 不能为空");
        Map<String, Object> result = svc.ingestTurns("default", "s1", "u1", List.of(
                turn("m1", "tool", "不该导入"),
                turn("m2", "user", "")));

        assertThat(result).containsEntry("inserted", 0).containsEntry("failed", 2);
        assertThat(repo.listRawLogsByTrace(RID)).isEmpty();
    }

    @Test
    @DisplayName("MCP 暴露第 5 个工具，且 turns 非数组时返回 error 而非异常")
    void toolkitExposesIngestTool() {
        H2OltpMemoryRepository repo = repo("ingest_toolkit");
        MemoryMcpToolkit toolkit = new MemoryMcpToolkit(service(repo));

        assertThat(toolkit.listTools()).hasSize(5)
                .extracting(t -> String.valueOf(t.get("name")))
                .contains("memory_write", "memory_read", "memory_delete",
                        "memory_search", "memory_ingest_turns");
        Map<String, Object> ok = toolkit.call("memory_ingest_turns", Map.of(
                "sessionId", "s1", "userId", "u1",
                "turns", List.of(turn("m1", "user", "通过 MCP 写入"))));
        assertThat(ok).containsEntry("inserted", 1);
        assertThat(toolkit.call("memory_ingest_turns", Map.of("sessionId", "s1", "turns", "bad")))
                .containsEntry("error", "turns 必须是数组");
    }
}
