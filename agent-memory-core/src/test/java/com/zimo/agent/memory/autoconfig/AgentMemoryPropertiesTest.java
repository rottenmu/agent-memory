package com.zimo.agent.memory.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 召回参数与抽取开关的配置校验（对应 PRD §十一 标准 15）。
 *
 * <p>核心主张：**配置错必须在启动期暴露**。服务带着「和为 3 的权重」跑起来，只会让每条记忆的
 * 分数都失去意义，且用户很难察觉 —— 这属于 PRD 明确要消灭的"静默错误"。</p>
 */
class AgentMemoryPropertiesTest {

    /** 只关注 recall / extract / engine / persona 四个字段，其余留空走默认。 */
    private static AgentMemoryProperties withRecall(AgentMemoryProperties.Recall recall) {
        return new AgentMemoryProperties(
                null, null, null, null, true, null, null, null, null, recall, null, null, null);
    }

    /* ---------------- 默认值 ---------------- */

    @Test
    @DisplayName("标准15：未配置 recall 时全部走默认值（0.5/0.3/0.2、30天、topK=5、阈值0.25）")
    void appliesRecallDefaults() {
        AgentMemoryProperties.Recall recall = withRecall(null).recall();

        assertThat(recall.weights()).isEqualTo(List.of(0.5, 0.3, 0.2));
        assertThat(recall.matchWeight()).isEqualTo(0.5);
        assertThat(recall.decayWeight()).isEqualTo(0.3);
        assertThat(recall.importanceWeight()).isEqualTo(0.2);
        assertThat(recall.halfLifeDays()).isEqualTo(30);
        assertThat(recall.topK()).isEqualTo(5);
        assertThat(recall.minScore()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("标准15：LLM 档抽取默认关（决策 D2），不会因未配置而误开")
    void llmExtractionIsOffByDefault() {
        assertThat(withRecall(null).extract().llmEnabled()).isFalse();
        assertThat(new AgentMemoryProperties.Extract(null).llmEnabled()).isFalse();
        assertThat(new AgentMemoryProperties.Extract(Boolean.TRUE).llmEnabled()).isTrue();
    }

    @Test
    @DisplayName("顶层其余字段也全为空时仍能构造（不因新增字段破坏既有配置）")
    void keepsLegacyDefaultsIntact() {
        AgentMemoryProperties properties = withRecall(null);

        assertThat(properties.h2Url()).isEqualTo(AgentMemoryProperties.DEFAULT_H2_URL);
        assertThat(properties.olapArrowDataFile()).isEqualTo(AgentMemoryProperties.DEFAULT_OLAP_FILE);
        assertThat(properties.syncCron()).isEqualTo(AgentMemoryProperties.DEFAULT_SYNC_CRON);
        assertThat(properties.oltpEngine()).isEqualTo(AgentMemoryProperties.DEFAULT_OLTP_ENGINE);
        assertThat(properties.olapEngine()).isEqualTo(AgentMemoryProperties.DEFAULT_OLAP_ENGINE);
    }

    /* ---------------- 标准 15：权重和 ≠ 1 必须启动失败 ---------------- */

    @Test
    @DisplayName("标准15：weights = 0.5/0.5/0.5（和 ≠ 1）→ 抛异常且文案明确")
    void rejectsWeightsNotSummingToOne() {
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(List.of(0.5, 0.5, 0.5), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("权重之和必须等于 1")
                .hasMessageContaining("1.5");
    }

    @Test
    @DisplayName("标准15：权重个数不是 3 个 → 拒绝")
    void rejectsWrongWeightCount() {
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(List.of(0.5, 0.5), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 个数值");
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(List.of(), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 个数值");
    }

    @Test
    @DisplayName("标准15：权重越界或为 null → 拒绝（不做静默夹取）")
    void rejectsOutOfRangeWeights() {
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(List.of(-0.1, 0.6, 0.5), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0~1");
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(List.of(1.2, -0.1, -0.1), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0~1");
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(java.util.Arrays.asList(0.5, null, 0.5), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0~1");
    }

    @Test
    @DisplayName("标准15：非正半衰期 / 非正 topK / 越界阈值 → 拒绝，而不是悄悄换默认值")
    void rejectsOtherInvalidRecallParameters() {
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(null, 0, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("half-life-days");
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(null, 30, 0, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-k");
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(null, 30, 8, 1.5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-score");
        assertThatThrownBy(() -> new AgentMemoryProperties.Recall(null, 30, 8, -0.1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-score");
    }

    @Test
    @DisplayName("合法非默认值被原样保留（校验只拦错的，不干扰正常的）")
    void keepsValidOverrides() {
        AgentMemoryProperties.Recall recall =
                new AgentMemoryProperties.Recall(List.of(0.4, 0.4, 0.2), 7, 3, 0.6);

        assertThat(recall.matchWeight()).isEqualTo(0.4);
        assertThat(recall.decayWeight()).isEqualTo(0.4);
        assertThat(recall.importanceWeight()).isEqualTo(0.2);
        assertThat(recall.halfLifeDays()).isEqualTo(7);
        assertThat(recall.topK()).isEqualTo(3);
        assertThat(recall.minScore()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("权重列表对外不可变，避免被调用方改坏共享默认值")
    void weightsAreImmutable() {
        AgentMemoryProperties.Recall recall = new AgentMemoryProperties.Recall(null, null, null, null);

        assertThat(recall.weights()).isEqualTo(AgentMemoryProperties.Recall.DEFAULT_WEIGHTS);
        assertThatThrownBy(() -> recall.weights().add(0.5))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
