package com.zimo.agent.memory.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.agent.memory.analytics.impl.ArrowOlapAnalyticsRepository;
import com.zimo.agent.memory.chat.AiConversationMemory;
import com.zimo.agent.memory.governance.ImportanceScorer;
import com.zimo.agent.memory.memory.AiMemoryService;
import com.zimo.agent.memory.memory.MemorySecurityConfig;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import com.zimo.agent.memory.storage.impl.H2OltpMemoryRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TrajectorySourcePipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void productionL0WritersPreserveSourcesThroughOlapReload() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:trajectory_" + UUID.randomUUID().toString().replace("-", "")
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        H2OltpMemoryRepository oltp = new H2OltpMemoryRepository(dataSource);
        AiConversationMemory conversations = new AiConversationMemory(512, null, oltp);
        conversations.appendTurn("session-1", "问题内容", "回答内容", 50);

        AiMemorySensitiveFilter filter = new AiMemorySensitiveFilter();
        AiMemoryService memoryService = new AiMemoryService(
                oltp, filter, MemorySecurityConfig.DEFAULT, new ImportanceScorer(filter));
        memoryService.saveUserMemory("tenant-1", "user-1", "custom", "联系电话 13812345678");

        Path arrowFile = tempDir.resolve("trajectory.arrow");
        ArrowOlapAnalyticsRepository olap = new ArrowOlapAnalyticsRepository(arrowFile);
        new AsyncLogSyncTask(oltp, olap).run();

        List<Map<String, Object>> rows = olap.query(
                "SELECT role, source, content FROM olap_l0_log");
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(row -> row.get("role") + ":" + row.get("source")))
                .containsExactlyInAnyOrder(
                        "user:user_message", "assistant:assistant_message", "system:memory_write");
        Map<String, Object> memoryWrite = rows.stream()
                .filter(row -> "memory_write".equals(row.get("source")))
                .findFirst()
                .orElseThrow();
        assertThat(memoryWrite.get("content").toString())
                .contains("138****5678")
                .doesNotContain("13812345678");

        ArrowOlapAnalyticsRepository reloaded = new ArrowOlapAnalyticsRepository(arrowFile);
        List<Map<String, Object>> reloadedRows = reloaded.query(
                "SELECT role, source FROM olap_l0_log");
        assertThat(reloadedRows).hasSize(3);
        assertThat(reloadedRows.stream().map(row -> row.get("role") + ":" + row.get("source")))
                .containsExactlyInAnyOrder(
                        "user:user_message", "assistant:assistant_message", "system:memory_write");
    }
}
