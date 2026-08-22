package com.zimo.module.agentmemory.memoryfile;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.framework.common.storage.FileStorageService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MemoryFileStore RocksDB 后端测试（内存 FileStorageService 模拟 KV 存储）。
 */
class MemoryFileStoreRocksDbTest {

    /** 内存版 FileStorageService（模拟 RocksDB KV 语义）。 */
    private static final class MapStorage implements FileStorageService {
        final Map<String, byte[]> data = new HashMap<>();

        @Override public void store(String key, byte[] bytes) { data.put(key, bytes); }
        @Override public byte[] get(String key) { return data.get(key); }
        @Override public boolean delete(String key) { return data.remove(key) != null; }
        @Override public List<String> list(String prefix) {
            return data.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        }
        @Override public boolean exists(String key) { return data.containsKey(key); }
    }

    @Test
    void rocksDbBackendReadWriteDeleteSearch() {
        MapStorage storage = new MapStorage();
        MemoryFileStore store = new MemoryFileStore(storage, "agent-memory/demo/MEMORY.md");

        // 写入
        store.write("用户偏好", "偏好周报邮件");
        store.write("用户偏好", "联系电话 138****5678");
        store.write("业务规则", "周报每周五发送");
        assertThat(storage.exists("agent-memory/demo/MEMORY.md")).isTrue();
        assertThat(store.readAll()).hasSize(3);

        // 主题过滤 / 检索
        assertThat(store.readByTopic("用户偏好")).hasSize(2);
        assertThat(store.search("周报")).hasSize(2);

        // 删除条目 + 主题
        assertThat(store.delete("用户偏好", "联系电话 138****5678")).isTrue();
        assertThat(store.readByTopic("用户偏好")).extracting(MemoryFileEntry::content)
                .containsExactly("偏好周报邮件");
        assertThat(store.delete("业务规则", null)).isTrue();
        assertThat(store.search("周报")).hasSize(1);

        // 新实例（模拟重启）从存储恢复
        MemoryFileStore restarted = new MemoryFileStore(storage, "agent-memory/demo/MEMORY.md");
        assertThat(restarted.readAll()).hasSize(1);
    }

    @Test
    void concurrentWritesRocksDbBackend() throws Exception {
        MapStorage storage = new MapStorage();
        MemoryFileStore store = new MemoryFileStore(storage, "agent-memory/demo/MEMORY.md");
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            threads.add(new Thread(() -> store.write("并发", "条目" + idx)));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertThat(store.readByTopic("并发")).hasSize(10);
    }
}
