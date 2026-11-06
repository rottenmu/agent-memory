package com.zimo.agent.memory.mcp;

import cn.hutool.core.util.StrUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 记忆 MCP 端点准入校验器。
 *
 * <p>该端点固定被 {@code plugin.auth} 拦截器排除在白名单外（见
 * {@code AuthProperties.Interceptor#excludePaths}），因此**必须自己完成鉴权**，
 * 否则任何能访问该端口者都可以无凭据读写删全部记忆 —— 而记忆会被注入 LLM 上下文，
 * 写入任意记忆等价于向后续所有会话注入提示词（prompt injection 面）。</p>
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li><b>已配置令牌</b>（{@code agent-memory.mcp-token} 非空）：一律校验
 *       {@code Authorization}，支持 {@code Bearer <token>} 与裸 {@code <token>} 两种写法。
 *       这是对外暴露时的部署方式。</li>
 *   <li><b>未配置令牌</b>：退化为「仅回环来源可访问」（{@code 127.0.0.0/8}、{@code ::1}、
 *       以及 IPv4-mapped 的 {@code ::ffff:127.x}）。这样本地 MCP 客户端（如 WorkBuddy 的
 *       {@code http://localhost:9900/...}）零配置即可用，而局域网/公网来源一律拒绝，
 *       不会出现「忘记配置就等于没有鉴权」的裸奔状态。</li>
 * </ul>
 *
 * <p>取的是 {@code getRemoteAddr()} 而非 {@code X-Forwarded-For}：后者可被请求方伪造，
 * 用它做准入判断等于把门禁交给来访者自己填。若部署在反向代理后且代理不在本机，
 * 应显式配置 {@code agent-memory.mcp-token}。</p>
 *
 * @author WorkBuddy
 * @since 2026-09-15
 */
public class MemoryMcpAccessGuard {

    private static final String BEARER_PREFIX = "Bearer ";

    private final String token;

    public MemoryMcpAccessGuard(String token) {
        this.token = token == null ? "" : token.trim();
    }

    /** 是否已显式配置令牌（未配置时退化为「仅回环可访问」）。 */
    public boolean tokenConfigured() {
        return !token.isEmpty();
    }

    /**
     * 判断本次请求是否放行。
     *
     * @param remoteAddr    请求来源地址（{@code HttpServletRequest#getRemoteAddr()}）
     * @param authorization {@code Authorization} 请求头原文，可空
     * @return 放行返回 {@code true}
     */
    public boolean isAllowed(String remoteAddr, String authorization) {
        if (tokenConfigured()) {
            return matches(extractCredential(authorization));
        }
        return isLoopback(remoteAddr);
    }

    /** 拒绝原因：区分「未配置令牌」与「令牌不匹配」，让调用方可自查而非只看到 401。 */
    public String denyReason() {
        return tokenConfigured()
                ? "未授权：Authorization 缺失或令牌不匹配，应发送 Bearer <agent-memory.mcp-token>"
                : "未授权：本端点未配置 agent-memory.mcp-token，仅允许回环来源访问";
    }

    /** 从 {@code Authorization} 头取出凭据，兼容 {@code Bearer xxx} 与裸 token 两种写法。 */
    static String extractCredential(String authorization) {
        if (StrUtil.isBlank(authorization)) {
            return "";
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return value.substring(BEARER_PREFIX.length()).trim();
        }
        return value;
    }

    /** 定长比较，避免按字符逐位比较带来的时序差异。 */
    private boolean matches(String credential) {
        if (credential.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /** 回环地址判定。 */
    static boolean isLoopback(String remoteAddr) {
        if (StrUtil.isBlank(remoteAddr)) {
            return false;
        }
        String addr = remoteAddr.trim();
        return "::1".equals(addr)
                || "0:0:0:0:0:0:0:1".equals(addr)
                || addr.startsWith("127.")
                || addr.startsWith("::ffff:127.");
    }
}
