package com.zimo.module.agentmemory.memoryarch;

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
 * 记忆分层架构 REST 接口（`/api/agent-memory/arch`）。
 */
@RestController
@RequestMapping("/api/agent-memory/arch")
public class MemoryArchController {

    private final MemoryArchService service;

    public MemoryArchController(MemoryArchService service) {
        this.service = service;
    }

    /** 5 维统计。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    /** 分页列表。 */
    @GetMapping("/configs")
    public Map<String, Object> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(type, q, page, size);
    }

    @GetMapping("/configs/{id}")
    public MemoryArchConfig get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/configs")
    public MemoryArchConfig create(@RequestBody Map<String, String> body) {
        return service.create(
                body.get("type"),
                body.get("name"),
                body.get("summary"),
                body.get("background"),
                body.get("content"),
                body.get("source"));
    }

    @PutMapping("/configs/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        boolean ok = service.update(id, body.get("summary"), body.get("background"),
                body.get("content"), body.get("source"));
        return Map.of("updated", ok);
    }

    @PostMapping("/configs/{id}/extract")
    public Map<String, Object> extract(@PathVariable String id) {
        boolean ok = service.extract(id);
        return Map.of("extracted", ok);
    }

    @DeleteMapping("/configs/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean ok = service.delete(id);
        return Map.of("deleted", ok);
    }

    /** 批量删除（可选）。 */
    @DeleteMapping("/configs")
    public Map<String, Object> deleteBatch(@RequestParam List<String> ids) {
        int n = 0;
        for (String id : ids) {
            if (service.delete(id)) {
                n++;
            }
        }
        return Map.of("deleted", n);
    }
}
