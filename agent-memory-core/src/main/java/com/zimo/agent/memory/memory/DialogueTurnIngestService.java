package com.zimo.agent.memory.memory;

import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import com.zimo.agent.memory.storage.OltpMemoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外部宿主（WorkBuddy）对话原文导入 L0 的服务。
 *
 * <p>宿主把每一轮 user/assistant 文本全量写在 jsonl 里，本服务把它转成 append-only 的
 * L0 事件：traceId 用 conversationRequestId（一次交互），role/user/assistant 保持原样，
 * source 用 {@code user_message} / {@code assistant_message}。</p>
 *
 * <p><b>幂等不改表</b>：{@code l0_raw_log} 没有外部消息 ID 列，而表结构存在 Java 自举与
 * SQL 脚本两处副本（有 parity 测试）。因此把 {@code sha256(msgId|role|脱敏后内容)} 的
 * 前 16 位放进 {@code meta_json.h}，写入前按 traceId 拉该轮已有行比对。这样既保住
 * 「traceId = 一次交互」的溯源语义，又不需要 DDL 变更。</p>
 *
 * <p><b>保留宿主时间戳</b>：{@link TrajectoryRecorder#record} 不接收 ts、内部固定取
 * {@code System.currentTimeMillis()}，直接复用会让补录的历史消息全部挤到导入时刻。
 * 因此这里直接 {@code oltp.saveRawLog(L0RawLog.forInsert(..., ts, ...))}，并把异常降级
 * 语义（只记 WARN、不向上抛）原样保留。</p>
 *
 * <p><b>必须写后回查</b>：写入被降级为 catch-and-log 后，「调用没抛错」不能证明落库成功。
 * 每条写完重新查一次该 traceId 的哈希集合，查不到计入 failed，让导入器不推进 state。</p>
 */
public class DialogueTurnIngestService {

    private static final Logger log = LoggerFactory.getLogger(DialogueTurnIngestService.class);
    /** 导入来源标记，写进 meta_json.src，便于与后端自身链路产生的 L0 区分。 */
    private static final String SOURCE_TAG = "workbuddy";
    /** 从 meta_json 中提取幂等哈希，避免为了一个字段引入 JSON 解析依赖。 */
    private static final Pattern HASH_FIELD = Pattern.compile("\"h\"\\s*:\\s*\"([0-9a-f]{16})\"");

    private final OltpMemoryRepository oltp;
    private final AiMemorySensitiveFilter sensitiveFilter;
    private final boolean sensitiveFiltering;

    public DialogueTurnIngestService(OltpMemoryRepository oltp,
                                     AiMemorySensitiveFilter sensitiveFilter,
                                     boolean sensitiveFiltering) {
        this.oltp = oltp;
        this.sensitiveFilter = sensitiveFilter;
        this.sensitiveFiltering = sensitiveFiltering;
    }

    /**
     * 批量导入对话消息。
     *
     * @param tenantId  租户标识（L0 无租户列，写进 meta_json.tenant 备用）
     * @param sessionId 会话标识，必填
     * @param userId    用户标识，可空
     * @param turns     消息列表，每项含 msgId / requestId / ts / role / content
     * @return {@code inserted / skipped / failed} 三个真实计数；参数非法时带 {@code error}
     */
    public Map<String, Object> ingestTurns(String tenantId, String sessionId, String userId,
                                           List<Map<String, Object>> turns) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sessionId == null || sessionId.isBlank()) {
            out.put("error", "sessionId 不能为空");
            return counts(out, 0, 0, 0);
        }
        if (turns == null || turns.isEmpty()) {
            return counts(out, 0, 0, 0);
        }
        if (oltp == null) {
            out.put("error", "OLTP 仓储不可用，未写入任何对话");
            return counts(out, 0, 0, turns.size());
        }
        IngestCounters counters = new IngestCounters();
        Map<String, Set<String>> cache = new HashMap<>();
        for (Map<String, Object> turn : turns) {
            ingestOne(tenantId, sessionId, userId, turn, cache, counters);
        }
        return counts(out, counters.inserted, counters.skipped, counters.failed);
    }

    /** 导入单条：校验 → 脱敏 → 判重 → 写入 → 回查。循环体保持短小。 */
    private void ingestOne(String tenantId, String sessionId, String userId,
                           Map<String, Object> turn, Map<String, Set<String>> cache,
                           IngestCounters counters) {
        String traceId = arg(turn, "requestId");
        String role = arg(turn, "role");
        String content = sanitize(arg(turn, "content"));
        boolean roleOk = "user".equals(role) || "assistant".equals(role);
        if (traceId.isEmpty() || content.isEmpty() || !roleOk) {
            counters.failed++;
            return;
        }
        String hash = hash(arg(turn, "msgId"), role, content);
        Set<String> hashes = cache.computeIfAbsent(traceId, this::hashesOf);
        if (hashes.contains(hash)) {
            counters.skipped++;
            return;
        }
        String source = "user".equals(role) ? TrajectoryRecorder.SOURCE_USER_MESSAGE
                : TrajectoryRecorder.SOURCE_ASSISTANT_MESSAGE;
        writeRawLog(sessionId, userId, traceId, role, source,
                content, meta(tenantId, arg(turn, "msgId"), hash), tsOf(turn));
        if (hashesOf(traceId).contains(hash)) {
            hashes.add(hash);
            counters.inserted++;
        } else {
            counters.failed++;
        }
    }

    /** 写 L0：异常降级成 WARN，成败一律交给下一次回查判定，避免静默假成功。 */
    private void writeRawLog(String sessionId, String userId, String traceId, String role,
                             String source, String content, String metaJson, long ts) {
        try {
            oltp.saveRawLog(L0RawLog.forInsert(traceId, sessionId,
                    userId == null ? "" : userId, ts, role, content, null, metaJson, source));
        } catch (RuntimeException e) {
            log.warn("[对话导入] L0 写入失败 traceId={}: {}", traceId, e.toString());
        }
    }

    /** 宿主 ts 缺失或非法时退回当前时间，不能让一条脏数据把整批时间线拖坏。 */
    private static long tsOf(Map<String, Object> turn) {
        String raw = arg(turn, "ts");
        try {
            return raw.isEmpty() ? System.currentTimeMillis() : Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    private String sanitize(String value) {
        return sensitiveFiltering ? sensitiveFilter.sanitize(value) : value;
    }

    /** 取该 traceId 下已落库的幂等哈希集合；写后回查也走这里，不复用缓存以免自证成功。 */
    private Set<String> hashesOf(String traceId) {
        Set<String> hashes = new HashSet<>();
        for (L0RawLog row : oltp.listRawLogsByTrace(traceId)) {
            Matcher matcher = HASH_FIELD.matcher(row.metaJson() == null ? "" : row.metaJson());
            if (matcher.find()) {
                hashes.add(matcher.group(1));
            }
        }
        return hashes;
    }

    private static String arg(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String hash(String msgId, String role, String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((msgId + "|" + role + "|" + content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用，无法生成幂等键", e);
        }
    }

    private static String meta(String tenantId, String msgId, String hash) {
        return "{\"src\":\"" + SOURCE_TAG + "\",\"tenant\":\"" + escape(tenantId)
                + "\",\"msgId\":\"" + escape(msgId) + "\",\"h\":\"" + hash + "\"}";
    }

    /** 只转义 JSON 字符串里会破坏结构的字符；msgId/tenantId 都是短标识。 */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static Map<String, Object> counts(Map<String, Object> out,
                                              int inserted, int skipped, int failed) {
        out.put("inserted", inserted);
        out.put("skipped", skipped);
        out.put("failed", failed);
        return out;
    }

    /** 三个计数在三处分支里递增，用可变小对象承载，避免返回数组后靠下标猜语义。 */
    private static final class IngestCounters {
        private int inserted;
        private int skipped;
        private int failed;
    }
}
