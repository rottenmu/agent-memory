package com.zimo.module.agentmemory.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zimo.module.agentmemory.governance.ImportanceScorer;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.security.AiMemorySensitiveFilter;
import com.zimo.module.agentmemory.storage.FakeOltpMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiMemoryServiceTrajectoryTest {

    private static final class CapturingRepository extends FakeOltpMemoryRepository {
        final List<L0RawLog> rawLogs = new ArrayList<>();
        final List<L1AtomicMemory> atomicMemories = new ArrayList<>();
        boolean failRawLogWrites;

        @Override
        public void saveRawLog(L0RawLog log) {
            if (failRawLogWrites) {
                throw new IllegalStateException("L0 unavailable");
            }
            rawLogs.add(log);
        }

        @Override
        public void saveAtomicMemory(L1AtomicMemory memory) {
            atomicMemories.add(memory);
        }
    }

    private AiMemoryService service(CapturingRepository repository) {
        AiMemorySensitiveFilter filter = new AiMemorySensitiveFilter();
        return new AiMemoryService(repository, filter, MemorySecurityConfig.DEFAULT,
                new ImportanceScorer(filter));
    }

    @Test
    void tagsSessionAndUserMemoryWritesAndKeepsSensitiveContentMasked() {
        CapturingRepository repository = new CapturingRepository();
        AiMemoryService service = service(repository);

        service.saveSessionVar("tenant-1", "session-1", "region", "华东");
        service.saveUserMemory("tenant-1", "user-1", "custom", "联系电话 13812345678");

        assertThat(repository.rawLogs).hasSize(2)
                .extracting(L0RawLog::source)
                .containsExactly("memory_write", "memory_write");
        assertThat(repository.rawLogs.get(1).content())
                .contains("138****5678")
                .doesNotContain("13812345678");
    }

    @Test
    void l0FailureDoesNotPreventUserMemoryPersistence() {
        CapturingRepository repository = new CapturingRepository();
        repository.failRawLogWrites = true;
        AiMemoryService service = service(repository);

        assertThatCode(() -> service.saveUserMemory(
                "tenant-1", "user-1", "custom", "用户偏好中文"))
                .doesNotThrowAnyException();

        assertThat(repository.atomicMemories).hasSize(1);
        assertThat(repository.rawLogs).isEmpty();
    }
}
