package com.zimo.module.agentmemory.memoryfile;

import com.zimo.framework.common.storage.FileStorageService;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MEMORY.md 文件记忆存储：与 QClaw / OpenClaw / Claude Code 生态的文件记忆格式互通。
 *
 * <p>文件格式（Markdown 约定）：</p>
 * <pre>
 * # 智能体记忆（Agent: {agentId}）
 *
 * ## 主题A
 * - 条目1
 * - 条目2
 *
 * ## 主题B
 * - 条目3
 * </pre>
 *
 * <p>并发安全（双级锁）：</p>
 * <ul>
 *   <li>进程内：{@link ReentrantReadWriteLock}（读写分离，读多写少）</li>
 *   <li>跨进程：{@link FileLock}（写操作独占目标文件，多实例安全）</li>
 *   <li>原子写：先写 {@code .tmp} 再 rename 覆盖，避免半写文件</li>
 * </ul>
 */
public class MemoryFileStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileStore.class);

    private final Path memoryFile;
    /** RocksDB 存储后端（非空时优先使用；否则退回本地文件系统）。 */
    private final FileStorageService storage;
    /** RocksDB 存储键（如 agent-memory/{agentId}/MEMORY.md）。 */
    private final String storageKey;
    /** 进程内读写锁（读多写少，保护 read-modify-write）。 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 本地文件系统后端。 */
    public MemoryFileStore(Path memoryFile) {
        this(memoryFile, null, null);
    }

    /** RocksDB 存储后端（RocksDB 实例自身并发安全，无需文件锁）。 */
    public MemoryFileStore(FileStorageService storage, String storageKey) {
        this(null, storage, storageKey);
    }

    private MemoryFileStore(Path memoryFile, FileStorageService storage, String storageKey) {
        this.memoryFile = memoryFile;
        this.storage = storage;
        this.storageKey = storageKey;
    }

    /** 记忆文件路径（文件系统后端）；RocksDB 后端返回 null。 */
    public Path file() {
        return memoryFile;
    }

    /** RocksDB 存储键（文件系统后端返回 null）。 */
    public String storageKey() {
        return storageKey;
    }

    /** 存储定位描述：RocksDB 后端显示数据目录+键；文件系统后端显示文件绝对路径。 */
    public String location() {
        if (storage != null) {
            return storage.describe(storageKey);
        }
        return memoryFile == null ? "" : memoryFile.toAbsolutePath().normalize().toString();
    }

    /* ================= 读取（读锁） ================= */

    /** 读取全部记忆（按主题分组，保持文件顺序）。 */
    public List<MemoryFileEntry> readAll() {
        lock.readLock().lock();
        try {
            return parse(readFile());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按主题读取。 */
    public List<MemoryFileEntry> readByTopic(String topic) {
        lock.readLock().lock();
        try {
            List<MemoryFileEntry> all = parse(readFile());
            if (topic == null || topic.isBlank()) {
                return all;
            }
            return all.stream().filter(e -> topic.equals(e.topic())).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 内容检索（主题或条目包含关键词，大小写不敏感）。 */
    public List<MemoryFileEntry> search(String query) {
        lock.readLock().lock();
        try {
            if (query == null || query.isBlank()) {
                return List.of();
            }
            String q = query.toLowerCase(Locale.ROOT);
            return parse(readFile()).stream()
                    .filter(e -> e.topic().toLowerCase(Locale.ROOT).contains(q)
                            || e.content().toLowerCase(Locale.ROOT).contains(q))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /* ================= 写入（写锁 + 文件锁 + 原子写） ================= */

    /**
     * 写入记忆：主题已存在则追加条目；不存在则新建主题。内容空则忽略。
     */
    public void write(String topic, String content) {
        if (topic == null || topic.isBlank() || content == null || content.isBlank()) {
            return;
        }
        lock.writeLock().lock();
        try {
            withFileLock(() -> {
                Map<String, List<String>> sections = toSections(parse(readFile()));
                sections.computeIfAbsent(topic.trim(), ignored -> new ArrayList<>()).add(content.trim());
                writeAtomically(render("智能体记忆", sections));
            });
        } catch (IOException e) {
            log.warn("MEMORY.md 写入加锁失败：{}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除记忆：content 非空则删除该主题下匹配条目；content 为空则删除整个主题。
     *
     * @return 是否发生删除
     */
    public boolean delete(String topic, String content) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        lock.writeLock().lock();
        try {
            final boolean[] changed = {false};
            withFileLock(() -> {
                Map<String, List<String>> sections = toSections(parse(readFile()));
                List<String> items = sections.get(topic);
                if (items == null) {
                    return;
                }
                if (content == null || content.isBlank()) {
                    sections.remove(topic);
                    changed[0] = true;
                } else {
                    boolean removed = items.removeIf(item -> item.equals(content.trim()));
                    if (removed) {
                        if (items.isEmpty()) {
                            sections.remove(topic);
                        }
                        changed[0] = true;
                    }
                }
                if (changed[0]) {
                    writeAtomically(render("智能体记忆", sections));
                }
            });
            return changed[0];
        } catch (IOException e) {
            log.warn("MEMORY.md 删除加锁失败：{}", e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /* ================= 序列化 ================= */

    /** 解析 MEMORY.md 文本 → 有序条目列表（保持主题出现顺序）。 */
    public static List<MemoryFileEntry> parse(String content) {
        List<MemoryFileEntry> entries = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return entries;
        }
        String currentTopic = "";
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                currentTopic = trimmed.substring(3).trim();
            } else if (trimmed.startsWith("- ") && !currentTopic.isEmpty()) {
                entries.add(new MemoryFileEntry(currentTopic, trimmed.substring(2).trim()));
            }
        }
        return entries;
    }

    /** 渲染条目列表 → MEMORY.md 文本（OpenClaw/Claude Code 兼容格式）。 */
    public static String render(String title, List<MemoryFileEntry> entries) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (MemoryFileEntry entry : entries) {
            sections.computeIfAbsent(entry.topic(), ignored -> new ArrayList<>()).add(entry.content());
        }
        return render(title, sections);
    }

    private static String render(String title, Map<String, List<String>> sections) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sections.forEach((topic, items) -> {
            sb.append("## ").append(topic).append("\n");
            for (String item : items) {
                sb.append("- ").append(item).append("\n");
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    /* ================= 内部工具 ================= */

    private static Map<String, List<String>> toSections(List<MemoryFileEntry> entries) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (MemoryFileEntry entry : entries) {
            sections.computeIfAbsent(entry.topic(), ignored -> new ArrayList<>()).add(entry.content());
        }
        return sections;
    }

    /** 读取原始内容（RocksDB 后端 / 文件系统后端分发）。 */
    private String readFile() {
        if (storage != null) {
            byte[] raw = storage.get(storageKey);
            return raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        }
        try {
            if (!Files.exists(memoryFile)) {
                return "";
            }
            return Files.readString(memoryFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("MEMORY.md 读取失败（按空处理）：{}", e.getMessage());
            return "";
        }
    }

    /** 写入原始内容（RocksDB put 原子；文件系统用 tmp + rename 原子写）。 */
    private void writeAtomically(String content) {
        if (storage != null) {
            storage.store(storageKey, content.getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            Path parent = memoryFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = memoryFile.resolveSibling(memoryFile.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, memoryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("MEMORY.md 写入失败：{}", e.getMessage());
        }
    }

    /** 跨进程锁：RocksDB 后端无需文件锁（实例并发安全）；文件系统后端锁定独立 {@code .lock} 文件。 */
    private void withFileLock(Runnable action) throws IOException {
        if (storage != null) {
            action.run();
            return;
        }
        Path parent = memoryFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path lockFile = memoryFile.resolveSibling(memoryFile.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            action.run();
        }
    }
}
