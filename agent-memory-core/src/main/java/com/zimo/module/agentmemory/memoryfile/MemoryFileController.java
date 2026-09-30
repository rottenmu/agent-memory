package com.zimo.module.agentmemory.memoryfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件兼容模式 REST 接口（4 个）：按 agentId 读写 OpenClaw 兼容的 MEMORY.md。
 *
 * <p>路径前缀 {@code /api/agent-memory/file}，与 {@code /api/ai/memory}（H2 四层）
 * 并存：本接口面向 OpenClaw 生态文件互通场景。</p>
 *
 * <p>另有存储配置接口 {@code GET/PUT /config}：查看与热切换记忆文件根目录
 * （{@code {baseDir}/{agentId}/MEMORY.md}），PUT 会写入
 * {@code ./data/agent-memory/file-base-dir.override} 使重启后仍生效。
 * 该接口与设置面板的「内存管理」节对应。</p>
 */
@RestController
@RequestMapping("/api/agent-memory/file")
public class MemoryFileController {

    private final MemoryFileService fileService;

    public MemoryFileController(MemoryFileService fileService) {
        this.fileService = fileService;
    }

    /** 存储配置：模式（file/rocksdb）+ 当前根目录 + 布局说明 + 是否已持久化覆盖。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mode", fileService.usesRocksDb() ? "rocksdb" : "file");
        Path baseDir = fileService.baseDir();
        cfg.put("baseDir", baseDir == null ? "" : baseDir.toAbsolutePath().normalize().toString());
        cfg.put("layout", "{baseDir}/{agentId}/MEMORY.md");
        cfg.put("persisted", Files.exists(MemoryFileService.overrideFile()));
        return cfg;
    }

    /**
     * 修改存储根目录：立即生效（后续读写走新目录），并持久化覆盖文件让重启保留。
     * 校验失败（空白 / 目录不可创建 / RocksDB 后端）以 {@code ok:false, error:…} 返回。
     */
    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> body) {
        if (fileService.usesRocksDb()) {
            return Map.of("ok", false, "error", "当前为 RocksDB 存储后端，记忆文件不落本地磁盘，无路径可配置");
        }
        String raw = body == null ? "" : String.valueOf(body.getOrDefault("baseDir", "")).trim();
        if (raw.isEmpty()) {
            return Map.of("ok", false, "error", "存储地址不能为空");
        }
        try {
            fileService.updateBaseDir(Paths.get(raw));
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
        try {
            persistOverride(fileService.baseDir());
        } catch (IOException e) {
            Map<String, Object> cfg = config();
            cfg.put("ok", false);
            cfg.put("error", "目录已切换，但持久化失败（重启后会回到 application.yml 配置）：" + e.getMessage());
            return cfg;
        }
        Map<String, Object> cfg = config();
        cfg.put("ok", true);
        return cfg;
    }

    /** 覆盖文件只存一行路径；启动时 AgentMemoryAutoConfiguration 优先读它。 */
    private static void persistOverride(Path baseDir) throws IOException {
        Path override = MemoryFileService.overrideFile();
        if (override.getParent() != null) {
            Files.createDirectories(override.getParent());
        }
        Files.writeString(override, baseDir.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
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
