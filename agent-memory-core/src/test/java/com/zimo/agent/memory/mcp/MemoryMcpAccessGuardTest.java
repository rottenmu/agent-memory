package com.zimo.agent.memory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 记忆 MCP 端点准入校验测试。
 *
 * <p>锁定两条安全语义：</p>
 * <ol>
 *   <li>配置了令牌时，缺失/错误凭据一律拒绝（含大小写不敏感的 {@code Bearer} 前缀）。</li>
 *   <li>未配置令牌时**不是完全放行**：仅回环来源可通过，局域网/公网来源仍拒绝 ——
 *       避免「忘记配令牌 = 裸奔」。</li>
 * </ol>
 */
class MemoryMcpAccessGuardTest {

    private static final String TOKEN = "s3cret-token";

    @Test
    void rejectsWhenTokenConfiguredButCredentialMissingOrWrong() {
        MemoryMcpAccessGuard guard = new MemoryMcpAccessGuard(TOKEN);
        assertThat(guard.tokenConfigured()).isTrue();
        // 即使来自回环，配了令牌也必须校验
        assertThat(guard.isAllowed("127.0.0.1", null)).isFalse();
        assertThat(guard.isAllowed("127.0.0.1", "")).isFalse();
        assertThat(guard.isAllowed("127.0.0.1", "Bearer ")).isFalse();
        assertThat(guard.isAllowed("127.0.0.1", "Bearer wrong")).isFalse();
        assertThat(guard.isAllowed("127.0.0.1", "wrong")).isFalse();
        // 前缀大小写不敏感，且裸 token 同样接受
        assertThat(guard.isAllowed("127.0.0.1", "Bearer " + TOKEN)).isTrue();
        assertThat(guard.isAllowed("10.1.2.3", "bearer " + TOKEN)).isTrue();
        assertThat(guard.isAllowed("10.1.2.3", TOKEN)).isTrue();
    }

    @Test
    void withoutTokenOnlyLoopbackIsAllowed() {
        MemoryMcpAccessGuard guard = new MemoryMcpAccessGuard("   ");
        assertThat(guard.tokenConfigured()).isFalse();
        assertThat(guard.isAllowed("127.0.0.1", null)).isTrue();
        assertThat(guard.isAllowed("127.0.0.5", null)).isTrue();
        assertThat(guard.isAllowed("::1", null)).isTrue();
        assertThat(guard.isAllowed("0:0:0:0:0:0:0:1", null)).isTrue();
        assertThat(guard.isAllowed("::ffff:127.0.0.1", null)).isTrue();
        // 非回环来源：拒绝
        assertThat(guard.isAllowed("192.168.1.20", null)).isFalse();
        assertThat(guard.isAllowed("10.0.0.9", null)).isFalse();
        assertThat(guard.isAllowed("0:0:0:0:0:0:0:2", null)).isFalse();
        assertThat(guard.isAllowed(null, null)).isFalse();
    }

    @Test
    void spoofedForwardingHeaderCannotBuyLoopbackTrust() {
        // 守卫只接受 getRemoteAddr() 的返回值。若调用方误把 X-Forwarded-For 之类
        // 客户端可控的值喂进来，非回环地址仍必须被拒绝。
        MemoryMcpAccessGuard guard = new MemoryMcpAccessGuard(null);
        assertThat(guard.isAllowed("8.8.8.8", null)).isFalse();
        assertThat(guard.isAllowed("::ffff:8.8.8.8", null)).isFalse();
        assertThat(guard.isAllowed("10.1.2.3", null)).isFalse();
    }

    @Test
    void credentialExtractionHandlesBothForms() {
        assertThat(MemoryMcpAccessGuard.extractCredential("Bearer abc")).isEqualTo("abc");
        assertThat(MemoryMcpAccessGuard.extractCredential("bearer abc")).isEqualTo("abc");
        assertThat(MemoryMcpAccessGuard.extractCredential("  abc  ")).isEqualTo("abc");
        assertThat(MemoryMcpAccessGuard.extractCredential(null)).isEmpty();
        assertThat(MemoryMcpAccessGuard.extractCredential("   ")).isEmpty();
    }

    @Test
    void denyReasonTellsWhatIsMissing() {
        assertThat(new MemoryMcpAccessGuard(TOKEN).denyReason()).contains("Authorization");
        assertThat(new MemoryMcpAccessGuard("").denyReason()).contains("mcp-token");
    }
}
