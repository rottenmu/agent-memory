package com.zimo.agent.memory;

import com.zimo.framework.common.PluginRegister;

/**
 * 智能体记忆插件身份声明。
 *
 * <p>只声明插件元数据，不含任何业务逻辑与依赖注入；由 {@code AgentMemoryAutoConfiguration}
 * 注册为 Bean，再由 {@code PluginRegistry} 汇总进 {@code GET /api/plugins}。</p>
 *
 * <p>{@code apiPrefix} 取 {@code /api/agent-memory}（MCP 端点、file、arch、analytics 都在此前缀下）。
 * 另一组兼容端点 {@code /api/ai/memory} 按最长前缀会归属到 {@code ai} 插件而非本插件 ——
 * 这是可接受的语义：那组是 {@code /api/ai} 体系下的兼容路径。</p>
 *
 * @author WorkBuddy
 * @since 2026-11-06
 */
public class AgentMemoryPluginRegister implements PluginRegister {

    @Override
    public String getPluginId() {
        return "agentmemory";
    }

    @Override
    public String getPluginName() {
        return "智能体记忆";
    }

    @Override
    public String getApiPrefix() {
        return "/api/agent-memory";
    }

    @Override
    public String getFrontendRoute() {
        return "/agent-memory";
    }

    @Override
    public String getFrontendModule() {
        return "agentmemory";
    }

    @Override
    public String getAgentName() {
        return "agentmemory-agent";
    }

    @Override
    public int getOrder() {
        return 13;
    }
}
