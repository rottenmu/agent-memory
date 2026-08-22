package com.zimo.module.agentmemory.memoryfile;

import java.util.List;
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
 * 文件兼容模式 REST 接口（4 个）：按 agentId 读写 OpenClaw 兼容的 MEMORY.md。
 *
 * <p>路径前缀 {@code /api/agent-memory/file}，与 {@code /api/ai/memory}（H2 四层）
 * 并存：本接口面向 OpenClaw 生态文件互通场景。</p>
 */
@RestController
@RequestMapping("/api/agent-memory/file")
public class MemoryFileController {

    private final MemoryFileService fileService;

    public MemoryFileController(MemoryFileService fileService) {
        this.fileService = fileService;
    }

    /** 读取智能体全部记忆（可指定主题过滤）。 */
    @GetMapping("/{agentId}")
    public List<MemoryFileEntry> read(
            @PathVariable String agentId,
            @RequestParam(required = false) String topic) {
        return topic == null || topic.isBlank()
                ? fileService.read(agentId)
                : fileService.read(agentId, topic);
    }

    /** 写入记忆（topic + content；主题存在则追加条目）。 */
    @PostMapping("/{agentId}")
    public Map<String, Object> write(
            @PathVariable String agentId,
            @RequestBody Map<String, String> body) {
        String topic = body.get("topic");
        String content = body.get("content");
        fileService.write(agentId, topic, content);
        return Map.of("written", true, "agentId", agentId, "topic", topic);
    }

    /** 删除记忆（content 空=删除主题，非空=删除匹配条目）。 */
    @DeleteMapping("/{agentId}")
    public Map<String, Object> delete(
            @PathVariable String agentId,
            @RequestParam String topic,
            @RequestParam(required = false) String content) {
        boolean deleted = fileService.delete(agentId, topic, content);
        return Map.of("deleted", deleted, "agentId", agentId, "topic", topic);
    }

    /** 检索记忆（主题或条目包含关键词）。 */
    @GetMapping("/{agentId}/search")
    public List<MemoryFileEntry> search(
            @PathVariable String agentId,
            @RequestParam String q) {
        return fileService.search(agentId, q);
    }
}
