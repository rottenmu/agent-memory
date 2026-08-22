package com.zimo.module.agentmemory.security;

import java.util.List;
import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/**
 * 记忆敏感内容过滤器：写入长期记忆前对 PII 与密钥做脱敏。
 *
 * <p>支持手机号、身份证号、银行卡号、邮箱、API 密钥、Bearer Token、内网 IP 的
 * 检测与脱敏。脱敏后保留首尾少量字符以便业务侧识别，如 {@code 138****8000}。</p>
 *
 * @author WorkBuddy
 * @since 2026-08-08
 */
public class AiMemorySensitiveFilter {

    private record Rule(String name, Pattern pattern, String mask) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("phone", Pattern.compile("(?<![0-9])(1[3-9][0-9])\\d{4}(\\d{4})(?![0-9])"), "$1****$2"),
            new Rule("id_card", Pattern.compile("(?<![0-9])([1-9][0-9]{5})(?:[0-9]{2})(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])(?:[0-9]{3})(?:[0-9Xx])(?![0-9])"), "$1********$2"),
            new Rule("bank_card", Pattern.compile("(?<![0-9])([1-9][0-9]{11,18})(?![0-9])"), "$1****"),
            new Rule("email", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "***@***"),
            new Rule("api_key", Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{8,})"), "***[key]***"),
            new Rule("bearer_token", Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{16,}"), "$1***"),
            new Rule("private_ip", Pattern.compile("(?<![0-9])(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.)"), "$1***"));

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
