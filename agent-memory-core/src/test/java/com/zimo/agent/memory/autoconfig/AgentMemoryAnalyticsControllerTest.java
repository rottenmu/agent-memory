package com.zimo.agent.memory.autoconfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zimo.agent.memory.analytics.MemoryAnalyticsService;
import com.zimo.agent.memory.analytics.impl.ArrowOlapAnalyticsRepository;
import com.zimo.agent.memory.memoryfile.MemoryFileService;
import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.storage.impl.H2OltpMemoryRepository;
import com.zimo.agent.memory.sync.AsyncLogSyncTask;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentMemoryAnalyticsControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesSourceInMessagesSessionMessagesAndTrace() throws Exception {
        String databaseName = "controller_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1", "sa", "");
        H2OltpMemoryRepository oltp = new H2OltpMemoryRepository(dataSource);
        oltp.saveRawLog(L0RawLog.forInsert(
                "trace-1", "session-1", "user-1", 1000L,
                "user", "用户请求", 2, null, "user_message"));
        oltp.saveRawLog(L0RawLog.forInsert(
                "trace-2", "session-1", "user-1", 2000L,
                "tool", "查询结果", 3, null, "tool_result"));

        ArrowOlapAnalyticsRepository olap = new ArrowOlapAnalyticsRepository(
                tempDir.resolve("l0-log.arrow"));
        new AsyncLogSyncTask(oltp, olap).run();
        AgentMemoryAnalyticsController controller = new AgentMemoryAnalyticsController(
                new MemoryAnalyticsService(olap), new MemoryFileService(tempDir));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/agent-memory/analytics/messages").param("sessionId", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].source").value("tool_result"))
                .andExpect(jsonPath("$.rows[1].source").value("user_message"));
        mvc.perform(get("/api/agent-memory/analytics/session-messages")
                        .param("sessionId", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].source").value("user_message"))
                .andExpect(jsonPath("$.messages[1].source").value("tool_result"));
        mvc.perform(get("/api/agent-memory/analytics/trace").param("traceId", "trace-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("tool_result"));
    }
}
