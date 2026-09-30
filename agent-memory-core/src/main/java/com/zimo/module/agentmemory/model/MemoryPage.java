package com.zimo.module.agentmemory.model;

import java.util.List;
import java.util.Map;

/**
 * 记忆列表查询的统一返回信封。
 *
 * <p>取代早期「直接返回 {@code List}」的形态：那种形态下调用方无法知道
 * 「是否还有更多数据」，也无法区分「真的只有 N 条」与「被服务端上限截断了」
 * —— 这正是缺陷 D10（先截断再分页、静默丢数据）的根源。</p>
 *
 * <p>{@code truncated} 的定义是「仍有未返回的数据」，即
 * {@code total > offset + records.size()}。它与 {@code total} 一起构成
 * 分页语义可判定的最小信息：调用方由 {@code total} 得知总量，由
 * {@code truncated} 得知是否需要继续翻页。</p>
 *
 * @param records   当前页记录
 * @param total     符合条件的总条数（来自 SQL {@code COUNT}，非当前页条数）
 * @param truncated 是否仍有未返回的数据
 * @param limit     本次请求的条数上限（未指定为 {@code null}）
 * @param offset    本次请求的起始偏移（未指定为 {@code null}）
 */
public record MemoryPage(
        List<Map<String, Object>> records,
        long total,
        boolean truncated,
        Integer limit,
        Integer offset) {

    /**
     * 由「当前页记录 + 真实总数」构造信封，自动推导 {@code truncated}。
     *
     * @param records 当前页记录
     * @param total   符合条件总条数
     * @param limit   请求条数上限
     * @param offset  请求起始偏移
     * @return 查询信封
     */
    public static MemoryPage of(
            List<Map<String, Object>> records, long total, Integer limit, Integer offset) {
        int start = offset == null || offset < 0 ? 0 : offset;
        boolean truncated = total > (long) start + records.size();
        return new MemoryPage(records, total, truncated, limit, offset);
    }

    /**
     * 不分页的全量返回（内部链路用，例如工具一次性取上限内全部记录）。
     *
     * @param records 全部记录
     * @return 查询信封
     */
    public static MemoryPage full(List<Map<String, Object>> records) {
        return new MemoryPage(records, records.size(), false, null, null);
    }
}
