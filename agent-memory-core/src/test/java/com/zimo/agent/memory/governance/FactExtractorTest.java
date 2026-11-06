package com.zimo.agent.memory.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link FactExtractor} 单测。
 *
 * <p>对应 PRD §3.1「抽取双档」：规则档必须默认可用、零成本、可回归。断言集中在三件事上 ——
 * **只信用户侧**（模型输出不得被记成用户事实）、命中哪类事实就归哪类类别、
 * 以及「没抽到」与「抽到了但不该写」必须是两种可区分的结果。</p>
 */
class FactExtractorTest {

    private static FactExtractor extractor() {
        AiMemorySensitiveFilter filter = new AiMemorySensitiveFilter();
        return new FactExtractor(new ImportanceScorer(filter), false);
    }

    /** 组装一条 L0 用户消息。 */
    private static L0RawLog user(long ts, String content) {
        return L0RawLog.forInsert("trace-" + ts, "s1", "u1", ts, "user", content, null, null);
    }

    /** 组装一条 L0 助手消息。 */
    private static L0RawLog assistant(long ts, String content) {
        return L0RawLog.forInsert("trace-" + ts, "s1", "u1", ts, "assistant", content, null, null);
    }

    @Nested
    @DisplayName("角色过滤：只信用户侧")
    class RoleFiltering {

        @Test
        @DisplayName("assistant 的消息一律不抽 —— 避免把模型输出记成用户事实")
        void ignoresAssistantMessages() {
            FactExtractor.Result result = extractor().extract(List.of(
                    assistant(1L, "以后都用 Excel 导出报表"),
                    assistant(2L, "我叫李雷，我的部门是数据平台部"),
                    assistant(3L, "结论是采用方案 B")));

            assertThat(result.candidates()).isEmpty();
            assertThat(result.scanned()).isZero();
        }

        @Test
        @DisplayName("tool / system 角色同样不抽")
        void ignoresNonUserRoles() {
            FactExtractor.Result result = extractor().extract(List.of(
                    L0RawLog.forInsert("t", "s1", "u1", 1L, "tool", "以后都用 Excel", null, null),
                    L0RawLog.forInsert("t", "s1", "u1", 2L, "system", "我叫李雷", null, null)));

            assertThat(result.candidates()).isEmpty();
        }

        @Test
        @DisplayName("只统计真正参与抽取的 user 消息为 scanned")
        void countsOnlyScannedMessages() {
            FactExtractor.Result result = extractor().extract(List.of(
                    user(1L, "以后都用 Excel 导出报表"),
                    assistant(2L, "好的"),
                    user(3L, "我叫李雷")));

            assertThat(result.scanned()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("模式识别与类别归属")
    class PatternRecognition {

        @Test
        @DisplayName("身份信息 → persona")
        void identityGoesToPersona() {
            FactExtractor.Result result = extractor().extract(List.of(user(1L, "我叫李雷")));

            assertThat(result.candidates()).hasSize(1);
            FactCandidate candidate = result.candidates().get(0);
            assertThat(candidate.category()).isEqualTo(FactExtractor.CATEGORY_PERSONA);
            assertThat(candidate.pattern()).isEqualTo(FactExtractor.PATTERN_IDENTITY);
        }

        @Test
        @DisplayName("长期偏好指令 → preference")
        void preferenceGoesToPreference() {
            FactExtractor.Result result = extractor().extract(List.of(user(1L, "以后都用 Excel 导出报表")));

            assertThat(result.candidates()).hasSize(1);
            assertThat(result.candidates().get(0).category()).isEqualTo(FactExtractor.CATEGORY_PREFERENCE);
            assertThat(result.candidates().get(0).pattern()).isEqualTo(FactExtractor.PATTERN_PREFERENCE);
        }

        @Test
        @DisplayName("任务结论 → history")
        void conclusionGoesToHistory() {
            FactExtractor.Result result = extractor().extract(List.of(user(1L, "结论是采用方案 B 上线")));

            assertThat(result.candidates()).hasSize(1);
            assertThat(result.candidates().get(0).category()).isEqualTo(FactExtractor.CATEGORY_HISTORY);
        }

        @Test
        @DisplayName("「请记住 X」→ custom，且优先级低于身份与偏好")
        void explicitRememberGoesToCustom() {
            FactExtractor.Result result = extractor().extract(List.of(user(1L, "请记住这个账号的结算周期")));

            assertThat(result.candidates()).hasSize(1);
            assertThat(result.candidates().get(0).category()).isEqualTo(FactExtractor.CATEGORY_CUSTOM);
            assertThat(result.candidates().get(0).pattern()).isEqualTo(FactExtractor.PATTERN_REMEMBER);
        }

        @Test
        @DisplayName("一条消息含身份与偏好 → 拆成两条候选，各自归类")
        void splitsOneMessageIntoMultipleCandidates() {
            FactExtractor.Result result = extractor().extract(List.of(user(1L, "我叫李雷，以后都用 Excel 导出")));

            assertThat(result.candidates()).hasSize(2);
            assertThat(result.candidates()).extracting(FactCandidate::category)
                    .containsExactly(FactExtractor.CATEGORY_PERSONA, FactExtractor.CATEGORY_PREFERENCE);
        }

        @Test
        @DisplayName("候选内容取自命中的小句，而不是整条消息")
        void candidateContentIsTheMatchedClause() {
            FactExtractor.Result result = extractor().extract(List.of(
                    user(1L, "今天天气不错，我叫李雷，顺便问一下版本号")));

            assertThat(result.candidates()).hasSize(1);
            assertThat(result.candidates().get(0).content()).isEqualTo("我叫李雷");
        }

        @Test
        @DisplayName("没有命中任何模式时返回空结果，不产噪音")
        void returnsEmptyWhenNothingMatches() {
            FactExtractor.Result result = extractor().extract(List.of(
                    user(1L, "这个接口的限流是多少？"),
                    user(2L, "好的谢谢")));

            assertThat(result.candidates()).isEmpty();
            assertThat(result.scanned()).isEqualTo(2);
        }

        @Test
        @DisplayName("溯源信息随候选带出")
        void carriesTraceability() {
            FactExtractor.Result result = extractor().extract(List.of(user(1700000000000L, "我叫李雷")));

            assertThat(result.candidates().get(0).sourceTraceId()).isEqualTo("trace-1700000000000");
            assertThat(result.candidates().get(0).sourceTs()).isEqualTo(1700000000000L);
        }
    }

    @Nested
    @DisplayName("去重与上限")
    class DeduplicationAndLimits {

        @Test
        @DisplayName("同批次内内容与类别相同的候选只保留一条")
        void deduplicatesWithinBatch() {
            FactExtractor.Result result = extractor().extract(List.of(
                    user(1L, "我叫李雷"),
                    user(2L, "我叫李雷")));

            assertThat(result.candidates()).hasSize(1);
            assertThat(result.deduped()).isEqualTo(1);
        }

        @Test
        @DisplayName("内容相同但类别不同的候选不算重复")
        void keepsSameContentUnderDifferentCategories() {
            FactExtractor.Result result = extractor().extract(List.of(
                    user(1L, "我叫李雷"),
                    user(2L, "请记住 我叫李雷")));

            assertThat(result.candidates()).hasSize(2);
            assertThat(result.deduped()).isZero();
        }

        @Test
        @DisplayName("单条消息的候选数有上限，超出部分计入 rejected")
        void capsCandidatesPerMessage() {
            String longMessage = "我叫李雷，我叫韩梅梅，我叫张三，我叫李四，我叫王五，我叫赵六，我叫孙七";

            FactExtractor.Result result = extractor().extract(List.of(user(1L, longMessage)));

            assertThat(result.candidates()).hasSizeLessThanOrEqualTo(5);
            assertThat(result.rejected()).isGreaterThan(0);
        }

        @Test
        @DisplayName("过短小句被丢弃")
        void rejectsTooShortClauses() {
            FactExtractor.Result result = extractor().extract(List.of(user(1L, "我叫")));

            assertThat(result.candidates()).isEmpty();
        }
    }

    @Nested
    @DisplayName("重要性与可回归性")
    class ImportanceAndDeterminism {

        @Test
        @DisplayName("候选重要性来自 ImportanceScorer，不是固定 0.5")
        void importanceComesFromScorer() {
            FactExtractor.Result result = extractor().extract(List.of(user(1L, "我叫李雷，我的部门是数据平台部")));

            FactCandidate candidate = result.candidates().get(0);
            assertThat(candidate.importance()).isNotEqualTo(ImportanceScorer.NEUTRAL);
            assertThat(candidate.importance()).isBetween(0.05, 0.85);
        }

        @Test
        @DisplayName("同样输入必得同样输出")
        void isDeterministic() {
            FactExtractor extractor = extractor();
            List<L0RawLog> logs = List.of(user(1L, "我叫李雷，以后都用 Excel 导出"));

            FactExtractor.Result first = extractor.extract(logs);
            FactExtractor.Result second = extractor.extract(logs);

            assertThat(second.candidates()).isEqualTo(first.candidates());
        }
    }

    @Nested
    @DisplayName("LLM 档开关语义")
    class LlmSwitch {

        @Test
        @DisplayName("默认档：llmEnabled=false，llmApplied=false，不报错")
        void defaultIsRuleOnly() {
            FactExtractor.Result result = extractor().extract(List.of(user(1L, "我叫李雷")));

            assertThat(result.llmEnabled()).isFalse();
            assertThat(result.llmApplied()).isFalse();
            assertThat(result.llmConfiguredButInactive()).isFalse();
        }

        @Test
        @DisplayName("配置开启但未接线时可被识别 —— 不允许静默失效")
        void reportsConfiguredButInactive() {
            AiMemorySensitiveFilter filter = new AiMemorySensitiveFilter();
            FactExtractor llmOn = new FactExtractor(new ImportanceScorer(filter), true);

            FactExtractor.Result result = llmOn.extract(List.of(user(1L, "我叫李雷")));

            assertThat(result.llmEnabled()).isTrue();
            assertThat(result.llmApplied()).isFalse();
            assertThat(result.llmConfiguredButInactive()).isTrue();
            // 未接线不等于不干活：规则档仍须正常产出候选
            assertThat(result.candidates()).hasSize(1);
        }

        @Test
        @DisplayName("未注入打分器时仍可抽取，重要性回落中性基线")
        void worksWithoutScorer() {
            FactExtractor.Result result = new FactExtractor(null, false)
                    .extract(List.of(user(1L, "我叫李雷")));

            assertThat(result.candidates()).hasSize(1);
            assertThat(result.candidates().get(0).importance()).isEqualTo(ImportanceScorer.NEUTRAL);
        }
    }

    @Nested
    @DisplayName("健壮性")
    class Robustness {

        @Test
        @DisplayName("null 输入与空内容不抛异常")
        void toleratesNullAndEmpty() {
            FactExtractor extractor = extractor();

            assertThat(extractor.extract(null).candidates()).isEmpty();
            assertThat(extractor.extract(List.of()).candidates()).isEmpty();
            assertThat(extractor.extract(List.of(
                    L0RawLog.forInsert("t", "s1", "u1", 1L, "user", "   ", null, null))).candidates())
                    .isEmpty();
        }
    }
}
