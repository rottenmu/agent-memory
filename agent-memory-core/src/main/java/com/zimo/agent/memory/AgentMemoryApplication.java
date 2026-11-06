package com.zimo.agent.memory;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * agent-memory 独立应用入口。
 *
 * <p>仅装配四层记忆能力（{@code agent-memory-autoconfig} 的自动装配）：REST /api/ai/memory、
 * MCP /api/agent-memory/mcp、分析 /api/agent-memory/analytics、ETL 定时同步。
 * 不加载 sys/auth/ai/feishu 等业务模块，无需 MySQL（记忆存储使用 H2 豁免）。</p>
 *
 * <p>注意：本启动类位于 {@code com.zimo.agentmemory.app}，刻意避开
 * {@code com.zimo.module.agentmemory} 包——core 中 AiMemoryController/MemoryArchService 等
 * 组件通过 autoconfig 的 @Bean 注册，若被组件扫描会与 @Bean 重复装配冲突。</p>
 *
 * <p>使用 Maven 插件启动时，需通过 {@code spring-boot.run.jvmArguments} 向 fork JVM 传入
 * {@code --add-opens=java.base/java.nio=ALL-UNNAMED}；打包后启动命令为
 * {@code java --add-opens=java.base/java.nio=ALL-UNNAMED -jar agent-memory-core-1.0.0-exec.jar
 * --spring.profiles.active=agentmemory}。Arrow IPC 导出需要开放 {@code java.nio}。</p>
 *
 * <p>2026-11-06：原独立模块 {@code agent-memory-application} 已整合进本 core 模块。
 * 配置放在 {@code application-agentmemory.yml}（profile 专用），避免被依赖本 core 的
 * 主应用从 classpath 自动加载；可执行 jar 由 spring-boot-maven-plugin 以
 * {@code classifier=exec} 产出，主构件仍是普通库 jar 供 {@code framework-ai} 等依赖方编译。</p>
 */
@SpringBootApplication
public class AgentMemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentMemoryApplication.class, args);
    }

    /**
     * 独立运行需显式提供 JdbcTemplate（主应用由 MySQL 自动配置提供，本应用仅有 H2 数据源）。
     * 绑定 agent-memory 的 H2 数据源，供 MemoryArchRepository 等使用。
     */
    @Bean
    public JdbcTemplate agentMemoryJdbcTemplate(
            @Qualifier("agentMemoryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
