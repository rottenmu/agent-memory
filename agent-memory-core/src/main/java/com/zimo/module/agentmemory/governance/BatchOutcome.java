package com.zimo.module.agentmemory.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 批量写入结果（逐条回执）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：批量写入出参，对应
 * {@code POST /api/ai/memory/batch}。</p>
 *
 * <p><b>为什么必须逐条回执，而不是整体成功/失败</b>：批量写入的一条可能因「类别不在白名单」
 * 被拒、另一条因「内容全是敏感信息」被拒，而其余都成功。若只返回一个总体状态，调用方
 * （尤其是脚本回填场景）既无法知道哪条没落库，也拿不到拒绝原因，只能整体重试 —— 结果是
 * 已经成功的条目被重复写入。</p>
 *
 * <p><b>刻意不做事务回滚</b>：部分失败不回滚是 PRD §6.2 的明确要求。批量接口主要服务于
 * 数据回填与压测造数，此时「能写进去的先写进去」比「全有或全无」更有用；且 L1/L3 分属
 * 不同表，回滚会把已成功的画像一并撤销，反而放大数据损失面。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param total     请求条数
 * @param succeeded 成功写入条数
 * @param rejected  被拒绝条数（含参数非法、白名单拒绝、内容为空）
 * @param results   逐条结果，顺序与请求一致，便于调用方按下标对齐
 */
public record BatchOutcome(int total, int succeeded, int rejected, List<ItemResult> results) {

    /** 归一化：结果列表不可变，{@code null} 视为空列表。 */
    public BatchOutcome {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * 是否部分成功。
     *
     * <p>供调用方判断是否需要人工介入：全成功或全失败都是确定的，部分成功才需要逐条看原因。</p>
     *
     * <p>{@code @JsonProperty} 必需：Jackson 只序列化 record 组件，派生访问器默认不入 JSON。</p>
     *
     * @return 成功与拒绝均大于 0 时返回 {@code true}
     */
    @JsonProperty("partial")
    public boolean partial() {
        return succeeded > 0 && rejected > 0;
    }

    /**
     * 单条写入回执。
     *
     * @param index    请求下标（从 0 开始）
     * @param ok       是否成功落库
     * @param target   写入目标：{@code user} / {@code global} / {@code session}
     * @param memoryId 成功时的记忆主键；失败时为 {@code null}
     * @param reason   失败原因（成功时为 {@code null}）
     */
    public record ItemResult(
            int index, boolean ok, String target, String memoryId, String reason) {

        /**
         * 构造成功回执。
         *
         * @param index    请求下标
         * @param target   写入目标
         * @param memoryId 记忆主键
         * @return 成功回执
         */
        public static ItemResult ok(int index, String target, String memoryId) {
            return new ItemResult(index, true, target, memoryId, null);
        }

        /**
         * 构造失败回执。
         *
         * @param index  请求下标
         * @param target 写入目标
         * @param reason 失败原因（不可为空）
         * @return 失败回执
         */
        public static ItemResult rejected(int index, String target, String reason) {
            return new ItemResult(index, false, target, null,
                    reason == null || reason.isBlank() ? "未提供原因" : reason);
        }
    }
}
