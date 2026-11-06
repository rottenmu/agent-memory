package com.zimo.agent.memory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * 敏感脱敏过滤器测试。
 *
 * <p>重点锁定两类历史缺陷：</p>
 * <ol>
 *   <li><b>掩码引用不存在的捕获组导致整个脱敏链路抛异常</b> —— 含身份证形态数字的文本
 *       必须能正常脱敏，而不是抛 {@code IndexOutOfBoundsException}。</li>
 *   <li><b>脱敏后仍可还原原文</b> —— 银行卡、内网 IP 等曾被「保留全部数字再补星号」，
 *       等于没脱敏；此处断言脱敏结果不再包含原始敏感串。</li>
 * </ol>
 */
class AiMemorySensitiveFilterTest {

    private final AiMemorySensitiveFilter filter = new AiMemorySensitiveFilter();

    /* ---------------- 掩码引用越界（D7 核心回归） ---------------- */

    @Test
    void sanitizeIdCardDoesNotThrowAndMasksBody() {
        String text = "用户身份证 110101199003077758 已登记";
        assertThatCode(() -> filter.sanitize(text)).doesNotThrowAnyException();
        String masked = filter.sanitize(text);
        assertThat(masked).isEqualTo("用户身份证 110101********7758 已登记");
        assertThat(masked).doesNotContain("1990030777");
    }

    @Test
    void sanitizeLegacyFifteenDigitIdCard() {
        assertThat(filter.sanitize("旧证 110101900307775 归档"))
                .isEqualTo("旧证 110101******775 归档");
    }

    @Test
    void idCardRuleMustRunBeforeBankCardRule() {
        // 18 位身份证同时满足 16~19 位连续数字的银行卡形态；顺序颠倒会让地区码前缀丢失
        String masked = filter.sanitize("110101199003077758");
        assertThat(masked).startsWith("110101");
        assertThat(masked).endsWith("7758");
        assertThat(masked).isEqualTo("110101********7758");
    }

    /* ---------------- 脱敏结果不可还原 ---------------- */

    @Test
    void bankCardKeepsOnlyLastFourDigits() {
        String masked = filter.sanitize("卡号 6222021234567890123");
        assertThat(masked).isEqualTo("卡号 ****0123");
        assertThat(masked).doesNotContain("622202");
        assertThat(masked).doesNotContain("123456789");
    }

    @Test
    void phoneKeepsHeadAndTailOnly() {
        assertThat(filter.sanitize("联系 13812345678")).isEqualTo("联系 138****5678");
    }

    @Test
    void privateIpv4HostPartIsFullyMasked() {
        assertThat(filter.sanitize("192.168.1.100")).isEqualTo("192.168.*.*");
        assertThat(filter.sanitize("10.0.0.5")).isEqualTo("10.*.*.*");
        assertThat(filter.sanitize("172.16.3.9")).isEqualTo("172.16.*.*");
        assertThat(filter.sanitize("172.31.255.254")).isEqualTo("172.31.*.*");
    }

    @Test
    void emailApiKeyAndBearerTokenAreMasked() {
        assertThat(filter.sanitize("邮箱 alice@example.com")).isEqualTo("邮箱 ***@***");
        assertThat(filter.sanitize("key=sk-abcdefgh12345")).isEqualTo("key=***[key]***");
        assertThat(filter.sanitize("Authorization: Bearer abcdefghijklmnop1234"))
                .isEqualTo("Authorization: Bearer ***");
    }

    /* ---------------- 不误伤 ---------------- */

    @Test
    void plainTextAndPublicIpAreUntouched() {
        String plain = "今天讨论了三层架构与索引优化，下午继续。";
        assertThat(filter.sanitize(plain)).isEqualTo(plain);
        // 公网地址不属于内网段，不应被遮蔽
        assertThat(filter.sanitize("8.8.8.8")).isEqualTo("8.8.8.8");
        assertThat(filter.sanitize("172.32.1.1")).isEqualTo("172.32.1.1");
    }

    @Test
    void blankAndNullInputPassThrough() {
        assertThat(filter.sanitize(null)).isNull();
        assertThat(filter.sanitize("   ")).isEqualTo("   ");
    }

    /* ---------------- 配套能力 ---------------- */

    @Test
    void containsSensitiveDetectsAndDeclaresRules() {
        assertThat(filter.containsSensitive("13812345678")).isTrue();
        // 旧实现的正则只认 16 位形态，18 位身份证完全检不出（返回值恒为 false）
        assertThat(filter.containsSensitive("110101199003077758")).isTrue();
        assertThat(filter.containsSensitive("110101900307775")).isTrue();
        assertThat(filter.containsSensitive("普通文本")).isFalse();
        assertThat(filter.ruleNames()).contains("phone", "id_card", "bank_card", "private_ip_192");
    }

    @Test
    void mixedTextWithAllSensitiveKindsSanitizesWithoutFailure() {
        String mixed = "身份证 110101199003077758，卡号 6222021234567890123，手机 13812345678，"
                + "内网 10.20.30.40，邮箱 bob@corp.cn，key sk-abcdefgh12345，Bearer abcdefghijklmnop1234";
        assertThatCode(() -> filter.sanitize(mixed)).doesNotThrowAnyException();
        String masked = filter.sanitize(mixed);
        assertThat(masked).doesNotContain("110101199003077758")
                .doesNotContain("6222021234567890123")
                .doesNotContain("13812345678")
                .doesNotContain("10.20.30.40")
                .doesNotContain("bob@corp.cn")
                .doesNotContain("sk-abcdefgh12345")
                .doesNotContain("abcdefghijklmnop1234");
    }
}
