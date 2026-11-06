package com.zimo.agent.memory.security;

import java.util.List;
import cn.hutool.core.util.StrUtil;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆敏感内容过滤器：写入长期记忆前对 PII 与密钥做脱敏。
 *
 * <p>覆盖手机号、身份证号、银行卡号、邮箱、API 密钥、Bearer Token、内网 IP 七类。
 * 脱敏保留识别所需的最少字符，如手机号 {@code 138****8000}、银行卡 {@code ****1234}、
 * 内网地址 {@code 192.168.*.*}。</p>
 *
 * <p><b>规则顺序本身有语义，不要随意调整</b>：</p>
 * <ul>
 *   <li>{@code id_card}（18 位）必须排在 {@code bank_card} 之前 —— 18 位身份证同时满足
 *       「16~19 位连续数字」的银行卡形态。先按身份证脱敏会在串内插入 {@code *}，
 *       银行卡规则便不再命中；顺序颠倒会让身份证丢掉地区码前缀，脱敏结果不可读。</li>
 *   <li>{@code phone} 依赖首尾数字边界断言，放在 {@code bank_card} 之前可避免长数字串被
 *       误切成「手机号 + 余数」两段。</li>
 * </ul>
 *
 * <p><b>掩码只允许引用真实存在的捕获组</b>：类加载时逐条校验掩码中的 {@code $n}，
 * 越界即抛 {@link IllegalStateException}。历史上 {@code id_card} 的掩码误写为
 * {@code $1********$2}，而该正则只有一个捕获组，导致任何含身份证形态数字的文本在
 * {@code replaceAll} 时抛 {@code IndexOutOfBoundsException}、整条脱敏链路失败。
 * 这类错误必须在启动时暴露，而不是等用户写入记忆时才崩。</p>
 *
 * @author WorkBuddy
 * @since 2026-08-08
 */
public class AiMemorySensitiveFilter {

    private record Rule(String name, Pattern pattern, String mask) {
    }

    /** 掩码中的捕获组引用，形如 {@code $1}、{@code $12}。 */
    private static final Pattern MASK_GROUP_REF = Pattern.compile("\\$(\\d+)");

    private static final List<Rule> RULES = List.of(
            // 手机号：138****8000（保留前 3 位与后 4 位）
            new Rule("phone", Pattern.compile("(?<![0-9])(1[3-9][0-9])\\d{4}(\\d{4})(?![0-9])"), "$1****$2"),
            // 身份证 18 位：110101********7758（保留地区码 6 位与末 4 位）
            new Rule("id_card", Pattern.compile("(?<![0-9])([1-9][0-9]{5})(?:[0-9]{4})"
                    + "(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])([0-9]{3}[0-9Xx])(?![0-9])"), "$1********$2"),
            // 身份证 15 位（历史格式）：110101******775
            new Rule("id_card_15", Pattern.compile("(?<![0-9])([1-9][0-9]{5})(?:[0-9]{2})"
                    + "(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])([0-9]{3})(?![0-9])"), "$1******$2"),
            // 银行卡 16~19 位：****1234（仅保留末 4 位）
            new Rule("bank_card", Pattern.compile("(?<![0-9])(?:[0-9]{12,15})([0-9]{4})(?![0-9])"), "****$1"),
            new Rule("email", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "***@***"),
            new Rule("api_key", Pattern.compile("(?i)(?:sk-[A-Za-z0-9_-]{8,})"), "***[key]***"),
            new Rule("bearer_token", Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"), "$1***"),
            // 内网 IP：保留网段前缀以便业务侧识别「这是内网地址」，主机位整体遮蔽
            new Rule("private_ip_10", Pattern.compile("(?<![0-9])10\\.(?:[0-9]{1,3}\\.){2}[0-9]{1,3}(?![0-9])"),
                    "10.*.*.*"),
            new Rule("private_ip_192", Pattern.compile("(?<![0-9])192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}(?![0-9])"),
                    "192.168.*.*"),
            new Rule("private_ip_172", Pattern.compile("(?<![0-9])172\\.(1[6-9]|2[0-9]|3[01])\\."
                    + "[0-9]{1,3}\\.[0-9]{1,3}(?![0-9])"), "172.$1.*.*"));

    static {
        for (Rule rule : RULES) {
            int groupCount = rule.pattern().matcher("").groupCount();
            Matcher ref = MASK_GROUP_REF.matcher(rule.mask());
            while (ref.find()) {
                int index = Integer.parseInt(ref.group(1));
                if (index > groupCount) {
                    throw new IllegalStateException("敏感规则「" + rule.name() + "」的掩码引用了不存在的捕获组 $"
                            + index + "（该正则仅有 " + groupCount + " 个捕获组）");
                }
            }
        }
    }

    /**
     * 对文本执行敏感信息脱敏。
     *
     * @param text 原始文本；为 {@code null} 时返回 {@code null}
     * @return 脱敏后的文本
     */
    public String sanitize(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.mask());
        }
        return result;
    }

    /**
     * 判断文本是否包含敏感信息。
     *
     * @param text 待检测文本；为 {@code null} 时返回 {@code false}
     * @return 包含任何敏感模式时返回 {@code true}
     */
    public boolean containsSensitive(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回支持的敏感类别名称列表（用于管理端展示）。
     *
     * @return 规则名称列表
     */
    public List<String> ruleNames() {
        return RULES.stream().map(Rule::name).toList();
    }
}
