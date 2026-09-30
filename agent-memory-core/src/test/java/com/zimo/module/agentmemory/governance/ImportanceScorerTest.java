package com.zimo.module.agentmemory.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.module.agentmemory.security.AiMemorySensitiveFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ImportanceScorer} 单测。
 *
 * <p>对应 PRD §3.1「重要性评估」与 §十一 验收标准：规则档必须**可解释、可回归**，
 * 且「无信号回落 0.5」。断言集中在三件事上：分值的**方向**（该高的高、该低的低）、
 * 分值的**区间**（自动打分不得触达免淘汰阈值）、以及分值能被信号**完全解释**。</p>
 */
class ImportanceScorerTest {

    /** 无敏感规则命中的普通文本，避免无关规则干扰分值是常态。 */
    private static ImportanceScorer scorer() {
        return new ImportanceScorer(new AiMemorySensitiveFilter());
    }

    @Nested
    @DisplayName("中性回落")
    class NeutralFallback {

        @Test
        @DisplayName("未知类别 + 无内容信号 → 恰好是中性基线 0.5")
        void fallsBackToNeutral() {
            ImportanceScore score = scorer().score("周四下午把会议室换到 A 座", "unknown_category");

            assertThat(score.value()).isEqualTo(ImportanceScorer.NEUTRAL);
            assertThat(score.signals()).isEmpty();
        }

        @Test
        @DisplayName("history / custom 是中性容器：不给类别先验，分值仍由内容决定")
        void historyAndCustomCarryNoCategoryPrior() {
            String text = "周四下午把会议室换到 A 座";

            assertThat(scorer().score(text, "history").value()).isEqualTo(ImportanceScorer.NEUTRAL);
            assertThat(scorer().score(text, "custom").value()).isEqualTo(ImportanceScorer.NEUTRAL);
        }

        @Test
        @DisplayName("类别大小写与空白不影响判定")
        void normalizesCategory() {
            assertThat(scorer().score("周四下午把会议室换到 A 座", "  PERSONA  ").value())
                    .isEqualTo(scorer().score("周四下午把会议室换到 A 座", "persona").value());
        }
    }

    @Nested
    @DisplayName("类别先验")
    class CategoryPrior {

        @ParameterizedTest(name = "{0} 应高于中性基线")
        @ValueSource(strings = {"persona", "preference", "global", "habit", "task", "fact"})
        void positiveCategoriesRaiseScore(String category) {
            ImportanceScore score = scorer().score("周四下午把会议室换到 A 座", category);

            assertThat(score.value()).isGreaterThan(ImportanceScorer.NEUTRAL);
            assertThat(score.has(ImportanceScorer.SIGNAL_CATEGORY_PRIOR)).isTrue();
        }

        @Test
        @DisplayName("persona 的类别先验强于 fact —— 长期身份比零散事实更值得保留")
        void personaOutweighsFact() {
            String text = "周四下午把会议室换到 A 座";

            assertThat(scorer().score(text, "persona").value())
                    .isGreaterThan(scorer().score(text, "fact").value());
        }

        @Test
        @DisplayName("session_var 是会话内临时值，先验为负")
        void sessionVarIsPenalized() {
            assertThat(scorer().score("周四下午把会议室换到 A 座", "session_var").value())
                    .isLessThan(ImportanceScorer.NEUTRAL);
        }
    }

    @Nested
    @DisplayName("内容信号")
    class ContentSignals {

        @Test
        @DisplayName("显式长期指令（「以后都用」）显著加分")
        void explicitLongTermDirectiveRaisesScore() {
            ImportanceScore score = scorer().score("以后都用 Excel 导出报表", "custom");

            assertThat(score.has(ImportanceScorer.SIGNAL_EXPLICIT_LONG_TERM)).isTrue();
            assertThat(score.value()).isGreaterThan(ImportanceScorer.NEUTRAL);
        }

        @Test
        @DisplayName("身份陈述加分")
        void identityStatementRaisesScore() {
            ImportanceScore score = scorer().score("我叫李雷，我的部门是数据平台部", "persona");

            assertThat(score.has(ImportanceScorer.SIGNAL_IDENTITY)).isTrue();
        }

        @Test
        @DisplayName("明确偏好指令加分")
        void preferenceDirectiveRaisesScore() {
            ImportanceScore score = scorer().score("我喜欢先看结论再看细节", "preference");

            assertThat(score.has(ImportanceScorer.SIGNAL_PREFERENCE)).isTrue();
        }

        @Test
        @DisplayName("疑问句降权 —— 一次性问答不是长期事实")
        void trailingQuestionIsPenalized() {
            ImportanceScore score = scorer().score("这个接口的限流是多少？", "custom");

            assertThat(score.has(ImportanceScorer.SIGNAL_QUESTION)).isTrue();
            assertThat(score.value()).isLessThan(ImportanceScorer.NEUTRAL);
        }

        @Test
        @DisplayName("寒暄客套降权")
        void chitchatIsPenalized() {
            ImportanceScore score = scorer().score("感谢！！！", "custom");

            assertThat(score.has(ImportanceScorer.SIGNAL_CHITCHAT)).isTrue();
            assertThat(score.value()).isLessThan(ImportanceScorer.NEUTRAL);
        }

        @Test
        @DisplayName("内容过短降权")
        void tooShortIsPenalized() {
            ImportanceScore score = scorer().score("好", "custom");

            assertThat(score.has(ImportanceScorer.SIGNAL_TOO_SHORT)).isTrue();
            assertThat(score.value()).isLessThan(ImportanceScorer.NEUTRAL);
        }

        @Test
        @DisplayName("敏感内容降权，且回填片段为空 —— 命中值不得出现在响应或日志里")
        void sensitiveContentIsPenalizedWithoutEchoingValue() {
            ImportanceScore score = scorer().score("我的手机号是 13812348000", "persona");

            assertThat(score.has(ImportanceScorer.SIGNAL_SENSITIVE)).isTrue();
            ImportanceScore.Signal signal = score.signals().stream()
                    .filter(s -> s.name().equals(ImportanceScorer.SIGNAL_SENSITIVE))
                    .findFirst().orElseThrow();
            assertThat(signal.matched()).isEmpty();
        }

        @Test
        @DisplayName("「应答 + 指令」混合句式不得被寒暄规则误扣分（寒暄用整串锚定）")
        void acknowledgementPlusDirectiveIsNotTreatedAsChitchat() {
            ImportanceScore mixed = scorer().score("好的，以后都用 Excel", "custom");

            assertThat(mixed.has(ImportanceScorer.SIGNAL_CHITCHAT)).isFalse();
            assertThat(mixed.has(ImportanceScorer.SIGNAL_EXPLICIT_LONG_TERM)).isTrue();
            assertThat(mixed.value()).isGreaterThan(ImportanceScorer.NEUTRAL);
        }

        @Test
        @DisplayName("多个同向信号可叠加")
        void signalsAccumulate() {
            ImportanceScore identityOnly = scorer().score("我叫李雷", "custom");
            ImportanceScore both = scorer().score("我叫李雷，以后都用 Excel", "custom");

            assertThat(both.value()).isGreaterThan(identityOnly.value());
            assertThat(both.has(ImportanceScorer.SIGNAL_IDENTITY)).isTrue();
            assertThat(both.has(ImportanceScorer.SIGNAL_EXPLICIT_LONG_TERM)).isTrue();
        }
    }

    @Nested
    @DisplayName("分值可解释性")
    class Explainability {

        @ParameterizedTest(name = "[{index}] {0} / {1}")
        @CsvSource({
                "以后都用 Excel 导出报表, custom",
                "我叫李雷，我的部门是数据平台部, persona",
                "这个接口的限流是多少？, custom",
                "感谢！！！, custom",
                "我的手机号是 13812348000, history",
                "周四下午把会议室换到 A 座, unknown"
        })
        @DisplayName("value 恒等于中性基线 + 所有信号 delta 之和（未收敛时）")
        void valueIsFullyExplainedBySignals(String content, String category) {
            ImportanceScore score = scorer().score(content, category);
            double raw = score.signals().stream()
                    .mapToDouble(ImportanceScore.Signal::delta)
                    .sum() + ImportanceScorer.NEUTRAL;

            assertThat(score.value()).isEqualTo(ImportanceScorer.clampScored(raw));
            // 未触及上下界时，必须逐位等于原始加权值，不能有任何「隐藏调整」
            if (raw > ImportanceScorer.MIN_VALUE && raw < ImportanceScorer.MAX_VALUE) {
                assertThat(score.value()).isEqualTo(raw);
            }
        }

        @Test
        @DisplayName("同样输入必得同样输出（纯函数，无时钟、无状态）")
        void isDeterministic() {
            ImportanceScorer scorer = scorer();
            ImportanceScore first = scorer.score("以后都用 Excel 导出报表", "preference");
            ImportanceScore second = scorer.score("以后都用 Excel 导出报表", "preference");

            assertThat(second.value()).isEqualTo(first.value());
            assertThat(second.signalNames()).isEqualTo(first.signalNames());
        }
    }

    @Nested
    @DisplayName("区间与免淘汰保护")
    class RangeAndProtection {

        @ParameterizedTest
        @ValueSource(doubles = {-1.0, 0.0, 0.4999, 0.5, 0.85, 1.0, 2.0})
        @DisplayName("自动打分恒收敛到 [MIN_VALUE, MAX_VALUE]")
        void scoredValueIsClamped(double raw) {
            assertThat(ImportanceScorer.clampScored(raw))
                    .isBetween(ImportanceScorer.MIN_VALUE, ImportanceScorer.MAX_VALUE);
        }

        @Test
        @DisplayName("自动打分无法构造出免淘汰的保护状态 —— 保护只能来自显式声明")
        void scoredValueNeverReachesProtectedThreshold() {
            // 用「同类叠加」的最强组合逼近上限：long_term + identity + preference 三类信号全中
            ImportanceScore best = scorer().score(
                    "记住我的名字，我叫李雷，以后都用 Excel 导出", "persona");

            assertThat(best.value()).isLessThan(ImportanceScorer.PROTECTED_THRESHOLD);
            assertThat(ImportanceScorer.protectedFromEviction(best.value())).isFalse();
        }

        @Test
        @DisplayName("显式重要性允许到 1.0，且 1.0 视为免淘汰")
        void explicitValueMayReachOne() {
            assertThat(ImportanceScorer.clampExplicit(1.0)).isEqualTo(1.0);
            assertThat(ImportanceScorer.protectedFromEviction(ImportanceScorer.clampExplicit(1.0))).isTrue();
        }

        @Test
        @DisplayName("显式值越界被收敛，NaN 回落中性基线而非 0")
        void explicitValueIsClampedAndNaNFallsBackToNeutral() {
            assertThat(ImportanceScorer.clampExplicit(-0.5)).isZero();
            assertThat(ImportanceScorer.clampExplicit(1.5)).isEqualTo(1.0);
            assertThat(ImportanceScorer.clampExplicit(Double.NaN)).isEqualTo(ImportanceScorer.NEUTRAL);
            assertThat(ImportanceScorer.clampScored(Double.NaN)).isEqualTo(ImportanceScorer.NEUTRAL);
        }

        @Test
        @DisplayName("免淘汰判定：恰好等于阈值即受保护，NaN 不受保护")
        void protectionThresholdIsInclusive() {
            assertThat(ImportanceScorer.protectedFromEviction(ImportanceScorer.PROTECTED_THRESHOLD)).isTrue();
            assertThat(ImportanceScorer.protectedFromEviction(ImportanceScorer.PROTECTED_THRESHOLD - 0.001))
                    .isFalse();
            assertThat(ImportanceScorer.protectedFromEviction(Double.NaN)).isFalse();
        }

        @Test
        @DisplayName("类级不变式：自动打分上界严格小于免淘汰阈值")
        void invariantsHold() {
            assertThat(ImportanceScorer.MAX_VALUE).isLessThan(ImportanceScorer.PROTECTED_THRESHOLD);
            assertThat(ImportanceScorer.MIN_VALUE).isBetween(0.0, ImportanceScorer.NEUTRAL);
        }
    }

    @Nested
    @DisplayName("健壮性")
    class Robustness {

        @Test
        @DisplayName("null 内容与 null 类别不抛异常")
        void toleratesNullInputs() {
            // 空内容只命中「内容过短」一项，于是稳定落在 中性基线 - 0.10，而不是某个未定义值
            assertThat(scorer().score(null, null).value())
                    .isEqualTo(ImportanceScorer.NEUTRAL - 0.10);
            assertThat(scorer().score("以后都用 Excel", null).value()).isGreaterThan(ImportanceScorer.NEUTRAL);
        }

        @Test
        @DisplayName("敏感过滤器为 null 时仍可打分，只是没有敏感降权信号")
        void worksWithoutSensitiveFilter() {
            ImportanceScore score = new ImportanceScorer(null).score("我的手机号是 13812348000", "custom");

            assertThat(score.has(ImportanceScorer.SIGNAL_SENSITIVE)).isFalse();
            assertThat(score.value()).isEqualTo(ImportanceScorer.NEUTRAL);
        }
    }
}
