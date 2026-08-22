package com.zimo.module.agentmemory.memoryfile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MEMORY.md 序列化与文件存储测试（OpenClaw 兼容格式 + 双级锁 + 原子写）。
 */
class MemoryFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void parseAndRenderRoundTrip() {
        String content = """
                # 智能体记忆（Agent: demo）

                ## 用户偏好
                - 偏好周报邮件
                - 联系电话 138****5678

                ## 业务规则
                - 周报每周五发送
                """;
        List<MemoryFileEntry> entries = MemoryFileStore.parse(content);
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).topic()).isEqualTo("用户偏好");
        assertThat(entries.get(1).content()).isEqualTo("联系电话 138****5678");

        String rendered = MemoryFileStore.render("智能体记忆", entries);
        assertThat(MemoryFileStore.parse(rendered)).hasSize(3);
    }

    @Test
    void writeAppendsToExistingTopic() {
        MemoryFileStore store = new MemoryFileStore(tempDir.resolve("MEMORY.md"));
        store.write("用户偏好", "偏好周报邮件");
        store.write("用户偏好", "联系电话 138****5678");
        store.write("业务规则", "周报每周五发送");

        assertThat(store.readAll()).hasSize(3);
        assertThat(store.readByTopic("用户偏好")).hasSize(2);
        assertThat(Files.exists(store.file())).isTrue();
    }

    @Test
    void deleteTopicAndEntry() {
        MemoryFileStore store = new MemoryFileStore(tempDir.resolve("MEMORY.md"));
        store.write("主题A", "条目1");
        store.write("主题A", "条目2");
        store.write("主题B", "条目3");

        assertThat(store.delete("主题A", "条目1")).isTrue();
        assertThat(store.readByTopic("主题A")).extracting(MemoryFileEntry::content)
                .containsExactly("条目2");

        assertThat(store.delete("主题A", null)).isTrue();
        assertThat(store.readByTopic("主题A")).isEmpty();

        assertThat(store.delete("主题B", "不存在")).isFalse();
    }

    @Test
    void searchMatchesTopicAndContent() {
        MemoryFileStore store = new MemoryFileStore(tempDir.resolve("MEMORY.md"));
        store.write("用户偏好", "偏好周报");
        store.write("业务规则", "日报每晚十点");

        assertThat(store.search("周报")).hasSize(1);
        assertThat(store.search("日报")).hasSize(1);
        assertThat(store.search("偏好")).hasSize(1);
        assertThat(store.search("不存在")).isEmpty();
    }

    @Test
    void concurrentWritesAreSafe() throws Exception {
        MemoryFileStore store = new MemoryFileStore(tempDir.resolve("MEMORY.md"));
        // 多线程并发追加同一主题（双级锁保护）
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
