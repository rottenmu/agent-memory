package com.zimo.module.agentmemory.memoryfile;

import com.zimo.framework.common.storage.FileStorageService;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件兼容模式服务门面：按 agentId 隔离记忆文件，提供写入/读取/删除/检索四类操作。
 *
 * <p>记忆文件路径：{@code {baseDir}/{agentId}/MEMORY.md}，与 QClaw / OpenClaw /
 * Claude Code 工作区记忆文件格式互通（可直接被 OpenClaw CLI 读取）。</p>
 */
public class MemoryFileService {

    private final Path baseDir;
    /** RocksDB 存储后端（非空时记忆文件存 RocksDB；否则存本地文件系统）。 */
    private final FileStorageService storage;

    public MemoryFileService(Path baseDir) {
        this(baseDir, null);
    }

    /** @param storage RocksDB 存储服务（框架 RocksdbFileStorageService），可为 null */
    public MemoryFileService(Path baseDir, FileStorageService storage) {
        this.baseDir = baseDir;
        this.storage = storage;
    }

    /** 指定智能体的记忆文件存储（RocksDB 键：agent-memory/{agentId}/MEMORY.md）。 */
    public MemoryFileStore storeFor(String agentId) {
        String safeId = sanitizeAgentId(agentId);
        if (storage != null) {
            return new MemoryFileStore(storage, "agent-memory/" + safeId + "/MEMORY.md");
        }
        return new MemoryFileStore(baseDir.resolve(safeId).resolve("MEMORY.md"));
    }

    /** 读取全部记忆。 */
    public List<MemoryFileEntry> read(String agentId) {
        return storeFor(agentId).readAll();
    }

    /** 按主题读取。 */
    public List<MemoryFileEntry> read(String agentId, String topic) {
        return storeFor(agentId).readByTopic(topic);
    }

    /** 写入（主题存在则追加条目，否则新建主题）。 */
    public void write(String agentId, String topic, String content) {
        storeFor(agentId).write(topic, content);
    }

    /** 删除（content 空=删除主题，非空=删除匹配条目）。 */
    public boolean delete(String agentId, String topic, String content) {
        return storeFor(agentId).delete(topic, content);
    }

    /** 检索（主题或条目包含关键词）。 */
    public List<MemoryFileEntry> search(String agentId, String query) {
        return storeFor(agentId).search(query);
    }

    /** 防止路径穿越：agentId 仅允许字母/数字/连字符/下划线。 */
    private static String sanitizeAgentId(String agentId) {
        String safe = agentId == null ? "default" : agentId.trim();
        if (safe.isEmpty() || safe.contains("..") || safe.contains("/") || safe.contains("\\")) {
            return "default";
        }
        return safe.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
