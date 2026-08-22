package com.zimo.module.agentmemory.memoryarch;

import cn.hutool.core.util.IdUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import org.springframework.stereotype.Service;

/**
 * 记忆分层架构服务门面：CRUD + 统计。
 */
@Service
public class MemoryArchService {

    private final MemoryArchRepository repo;
    private final OltpMemoryRepository oltp;

    public MemoryArchService(MemoryArchRepository repo, OltpMemoryRepository oltp) {
        this.repo = repo;
        this.oltp = oltp;
    }

    /** 5 维统计（对应页面顶部卡片）。 */
    public Map<String, Object> stats() {
        long today = oltp.countL0Today();
        long total = oltp.countL0Total();
        java.util.Map<String, Long> dialogues = java.util.Map.of("today", today, "total", total);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("soulConfigCount", repo.countByType("SOUL"));
        m.put("userProfileCount", repo.countByType("USER"));
        m.put("todayDialogues", dialogues.get("today"));
        m.put("totalDialogues", dialogues.get("total"));
        m.put("extracted", repo.countExtracted());
        return m;
    }

    /** 分页列表（type 可空，q 关键词）。 */
    public Map<String, Object> list(String type, String q, int page, int size) {
        if (size <= 0) {
            size = 20;
        }
        List<MemoryArchConfig> rows = repo.list(type, q, page, size);
        int total = repo.countList(type, q);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("total", total);
        m.put("page", page);
        m.put("size", size);
        return m;
    }

    public MemoryArchConfig get(String id) {
        return repo.findById(id);
    }

    /** 新建（type SOUL/USER）。 */
    public MemoryArchConfig create(String type, String name, String summary,
                                   String background, String content, String source) {
        long ts = System.currentTimeMillis();
        MemoryArchConfig c = new MemoryArchConfig(
                IdUtil.fastSimpleUUID().substring(0, 16),
                type,
                name == null || name.isBlank() ? "未命名" : name,
                summary,
                background,
                content,
                source == null || source.isBlank() ? "手动" : source,
                1,
                ts);
        repo.insert(c);
        return c;
    }

    /** 更新（content/summary/background/source）。 */
    public boolean update(String id, String summary, String background, String content, String source) {
        MemoryArchConfig exist = repo.findById(id);
        if (exist == null) {
            return false;
        }
        long ts = System.currentTimeMillis();
        MemoryArchConfig c = new MemoryArchConfig(
                exist.id(), exist.type(), exist.name(),
                summary, background, content,
                source == null || source.isBlank() ? exist.source() : source,
                exist.version(), ts);
        return repo.update(c) > 0;
    }

    /** 提取：标记为自动，extracted 计数 +1。 */
    public boolean extract(String id) {
        return repo.updateSource(id, "自动") > 0;
    }

    public boolean delete(String id) {
        return repo.delete(id) > 0;
    }
}
