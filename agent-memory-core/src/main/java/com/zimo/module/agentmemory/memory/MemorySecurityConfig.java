package com.zimo.module.agentmemory.memory;

import java.util.List;

/**
 * 记忆安全策略配置：白名单类别与敏感过滤开关。
 *
 * @param whitelistCategories 类别白名单；空列表表示放行全部（persona/preference/history/custom/global 等）
 * @param sensitiveFiltering  是否启用敏感内容脱敏
 */
public record MemorySecurityConfig(
        List<String> whitelistCategories,
        boolean sensitiveFiltering) {

    public static final MemorySecurityConfig DEFAULT =
            new MemorySecurityConfig(List.of(), true);

    public MemorySecurityConfig {
        whitelistCategories = whitelistCategories == null ? List.of() : List.copyOf(whitelistCategories);
    }

    /** 白名单是否启用了限制。 */
    public boolean whitelistEnabled() {
        return !whitelistCategories.isEmpty();
    }
}
