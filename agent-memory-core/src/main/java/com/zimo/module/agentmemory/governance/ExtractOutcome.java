package com.zimo.module.agentmemory.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 事实抽取结果（规则档 / LLM 档统一出参）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：抽取出参，对应
 * {@code POST /api/ai/memory/extract}。</p>
 *
 * <p><b>为什么要把「抽到几条」与「写进去几条」分开</b>：抽取与落库是两步，中间可能被
 * 白名单拒绝、被敏感策略挡下、或因去重跳过。只报 {@code candidates} 会让「抽到 10 条
 * 但一条都没落库」看起来像成功。因此三个数字（候选 / 落库 / 跳过）必须同时可见。</p>
 *
 * <p><b>LLM 档状态必须如实暴露</b>：{@link FactExtractor.Result#llmConfiguredButInactive()}
 * 描述的是「配置要求开、实际没生效」这一类静默失效，接口层必须把它透出来，而不是只在
 * 日志里留一行。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param traceId       抽取所依据的链路标识（按 trace 抽取时非空）
 * @param sessionId     抽取所依据的会话标识（按会话抽取时非空）
 * @param scanned       参与抽取的 L0 消息条数
 * @param candidates    抽出的候选条数
 * @param deduped       因与本次已抽候选重复而丢弃的条数
 * @param persisted     实际落库条数（{@code persist=false} 时恒为 0）
 * @param persistFailed 落库被拒绝条数（白名单 / 敏感策略）
 * @param llmEnabled    配置是否要求启用 LLM 档
 * @param llmApplied    LLM 档本次是否真的生效
 * @param persist       本次是否为落库模式
 * @param items         候选明细（含类别、内容、重要性）
 */
public record ExtractOutcome(
        String traceId,
        String sessionId,
        int scanned,
        int candidates,
        int deduped,
        int persisted,
        int persistFailed,
        boolean llmEnabled,
        boolean llmApplied,
        boolean persist,
        List<FactCandidate> items) {

    /** 归一化：候选明细不可变，{@code null} 视为空列表。 */
    public ExtractOutcome {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * LLM 档是否「配置要求开启但实际未生效」。
     *
     * <p>{@code @JsonProperty} 必需：Jackson 只序列化 record 组件，派生访问器默认不入 JSON。
     * 本字段的存在意义就是「把静默失效喊出来」，掉了注解它自己反而静默了。</p>
     *
     * @return 配置开启但未生效时返回 {@code true}
     */
    @JsonProperty("llmConfiguredButInactive")
    public boolean llmConfiguredButInactive() {
        return llmEnabled && !llmApplied;
    }

    /**
     * 是否抽到候选但一条都没落库。
     *
     * <p>典型成因是候选内容全部命中敏感策略。这个组合最容易被误读成「抽取成功」，
     * 因此单独给出判断方法，供接口层附加提示。</p>
     *
     * @return 抽取模式下候选非空且落库为 0 时返回 {@code true}
     */
    @JsonProperty("extractedNothingPersisted")
    public boolean extractedNothingPersisted() {
        return persist && candidates > 0 && persisted == 0;
    }
}
