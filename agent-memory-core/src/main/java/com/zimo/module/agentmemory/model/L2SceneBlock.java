package com.zimo.module.agentmemory.model;

import java.util.ArrayList;
import java.util.List;

/**
 * L2 场景块：按会话/场景聚合的上下文块，含摘要与关联的 L1 原子记忆。
 *
 * <p>会话启动时优先加载该场景块，作为对话上下文的骨架；摘要由
 * {@code summary} 承载（替代原进程内会话摘要），{@code l1Ids} 关联本场景
 * 抽取的原子记忆。</p>
 *
 * @param id        场景块 ID
 * @param sessionId 会话标识
 * @param sceneName 场景名（如「采购审批」「周报生成」）
 * @param summary   场景摘要（含事实与约束）
 * @param startTs   场景开始时间戳（毫秒）
 * @param endTs     场景结束时间戳（毫秒）
 * @param l1Ids     关联的 L1 原子记忆 ID 列表
 * @param ts        更新时间戳（毫秒）
 */
public record L2SceneBlock(
        String id,
        String sessionId,
        String sceneName,
        String summary,
        long startTs,
        long endTs,
        List<String> l1Ids,
        long ts) {

    /** 从 JSON 数组字符串还原 L1 ID 列表。 */
    public static List<String> l1IdsFromJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return new ArrayList<>();
        }
        String body = json.trim();
        body = body.substring(1, body.length() - 1);
        List<String> ids = new ArrayList<>();
        for (String part : body.split(",")) {
            String id = part.trim().replace("\"", "");
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
