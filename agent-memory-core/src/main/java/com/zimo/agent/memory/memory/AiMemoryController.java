package com.zimo.agent.memory.memory;

import com.zimo.agent.memory.model.MemoryPage;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体记忆管理接口。
 *
 * <ul>
 *   <li>会话记忆：单次会话上下文与业务变量</li>
 *   <li>用户长期记忆：员工画像、历史习惯、业务历史记录</li>
 *   <li>全局持久记忆：跨会话、跨智能体共享</li>
 *   <li>记忆策略：白名单类别与敏感过滤规则查询</li>
 * </ul>
 *
 * <p><b>列表接口返回 {@link MemoryPage}</b>（{@code records / total / truncated / limit / offset}），
 * 不再是裸数组：调用方需要 {@code total} 才能判断「是否还有下一页」，需要 {@code truncated}
 * 才能知道结果是否被服务端上限截断（否则数据被截了却无从察觉）。</p>
 *
 * <p><b>删除接口返回真实影响行数</b>（{@code deletedCount}），{@code deleted} 由行数推导，
 * 不再无条件为 {@code true}。</p>
 *
 * @author WorkBuddy
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/api/ai/memory")
public class AiMemoryController {

    private final AiMemoryService memoryService;
    private final MemorySecurityConfig securityConfig;
    private final AiMemorySensitiveFilter sensitiveFilter;

    public AiMemoryController(
            AiMemoryService memoryService,
            MemorySecurityConfig securityConfig,
            AiMemorySensitiveFilter sensitiveFilter) {
        this.memoryService = memoryService;
        this.securityConfig = securityConfig;
        this.sensitiveFilter = sensitiveFilter;
    }

    /* ---------------- 短期会话记忆 ---------------- */

    @GetMapping("/session/{sessionId}")
    public MemoryPage getSessionVars(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return memoryService.getSessionVars(tenantId, sessionId, limit, offset);
    }

    @PostMapping("/session/{sessionId}")
    public Map<String, Object> saveSessionVar(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestBody Map<String, String> body) {
        return memoryService.saveSessionVar(tenantId, sessionId, body.get("key"), body.get("value"));
    }

    /** 删除会话变量；{@code key} 为空表示清空该会话全部变量。 */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> deleteSessionVar(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String key) {
        int deleted = memoryService.deleteSessionVar(tenantId, sessionId, key);
        return deleteResult(deleted);
    }

    /* ---------------- 用户长期记忆 ---------------- */

    @GetMapping("/user/{userId}")
    public MemoryPage getUserMemory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return memoryService.listUserMemory(tenantId, userId, category, limit, offset);
    }

    /**
     * 写用户长期记忆。
     *
     * <p>请求体：{@code {category, content, importance?}}。{@code importance} 省略时由服务端
     * 自动评估；显式传入且 ≥ 0.9 视为「显式标记为重要」，该条记忆免于遗忘淘汰。</p>
     */
    @PostMapping("/user/{userId}")
    public Map<String, Object> saveUserMemory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestBody Map<String, Object> body) {
        return memoryService.saveUserMemory(tenantId, userId,
                textOf(body, "category"), textOf(body, "content"), importanceOf(body));
    }

    /** 删除指定记忆；{@code id} 为空表示清空该用户全部长期记忆。 */
    @DeleteMapping("/user/{userId}")
    public Map<String, Object> deleteUserMemory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String id) {
        int deleted = memoryService.deleteUserMemory(tenantId, userId, id);
        return deleteResult(deleted);
    }

    /* ---------------- 全局持久记忆 ---------------- */

    @GetMapping("/global")
    public MemoryPage getGlobalMemory(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return memoryService.listGlobalMemory(tenantId, limit, offset);
    }

    /**
     * 写全局持久记忆。
     *
     * <p>请求体：{@code {key, content, importance?}}；{@code importance} 语义同用户长期记忆。</p>
     */
    @PostMapping("/global")
    public Map<String, Object> saveGlobalMemory(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestBody Map<String, Object> body) {
        return memoryService.saveGlobalMemory(tenantId,
                textOf(body, "key"), textOf(body, "content"), importanceOf(body));
    }

    /** 删除全局记忆；{@code key} 为空表示清空该租户全部全局记忆。 */
    @DeleteMapping("/global")
    public Map<String, Object> deleteGlobalMemory(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String key) {
        int deleted = memoryService.deleteGlobalMemory(tenantId, key);
        return deleteResult(deleted);
    }

    /* ---------------- 记忆策略 ---------------- */

    @GetMapping("/policy")
    public Map<String, Object> policy() {
        return Map.of(
                "whitelistCategories", securityConfig.whitelistCategories(),
                "whitelistEnabled", securityConfig.whitelistEnabled(),
                "sensitiveFiltering", securityConfig.sensitiveFiltering(),
                "sensitiveRules", sensitiveFilter.ruleNames());
    }

    /* ---------------- 工具 ---------------- */

    /** 统一的删除结果：{@code deleted} 由真实影响行数推导，避免「删不到也报成功」。 */
    private Map<String, Object> deleteResult(int deleted) {
        return Map.of("deleted", deleted > 0, "deletedCount", deleted);
    }

    /** 取字符串字段；JSON 里写成数字也照读，不因类型不符就丢掉字段。 */
    private static String textOf(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 取可选的重要性入参。
     *
     * <p>格式非法时**抛异常而不是忽略**：静默忽略会让调用方以为「已按我指定的重要性保存」，
     * 而实际存进去的是自动评估值 —— 这正是本项目一直在消除的无声失败。</p>
     *
     * @param body 请求体
     * @return 重要性；字段缺失或空白时返回 {@code null}（表示交给服务端自动评估）
     */
    private static Double importanceOf(Map<String, Object> body) {
        Object value = body.get("importance");
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("importance 必须是 0~1 的数值，当前为: " + value);
        }
    }
}
