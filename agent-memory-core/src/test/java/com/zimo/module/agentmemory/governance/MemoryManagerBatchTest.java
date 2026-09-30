package com.zimo.module.agentmemory.governance;

import static com.zimo.module.agentmemory.governance.MemoryManagerFixture.TENANT;
import static com.zimo.module.agentmemory.governance.MemoryManagerFixture.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zimo.module.agentmemory.model.UserMemoryRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 批量读写测试（PRD §6.2：{@code /batch} 与 {@code /batch/query}）。
 *
 * <p>批量接口的正确性重点不在「能写进去」，而在<b>失败可见</b>：哪一条被拒、为什么被拒、
 * 有没有被静默截断。这些断言比成功路径更重要 —— 脚本回填场景下，静默丢弃会直接造成
 * 数据缺口且事后无法定位。</p>
 */
class MemoryManagerBatchTest {

    @Test
    @DisplayName("逐条回执：成功与拒绝混在一批里，各自带原因")
    void reportsPerItemResults() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_batch_mixed");
        List<WriteItem> items = List.of(
                WriteItem.user(USER, "custom", "第一条", null),
                WriteItem.global("rule-1", "全局规则一"),
                WriteItem.session("s-1", "var", "会话变量"),
                new WriteItem("gloabl", USER, null, null, "custom", "拼错的目标", null),
                WriteItem.user(" ", "custom", "缺用户", null),
                WriteItem.user(USER, "custom", "  ", null));

        BatchOutcome outcome = fixture.manager().batchWrite(TENANT, items);

        assertThat(outcome.total()).isEqualTo(6);
        assertThat(outcome.succeeded()).isEqualTo(3);
        assertThat(outcome.rejected()).isEqualTo(3);
        assertThat(outcome.partial()).isTrue();
        assertThat(outcome.results()).hasSize(6);
        assertThat(outcome.results().get(0).memoryId()).isNotBlank();
        assertThat(outcome.results().get(3).reason()).contains("不支持的写入目标");
        assertThat(outcome.results().get(4).reason()).contains("userId");
        assertThat(outcome.results().get(5).reason()).contains("content");
        assertThat(outcome.results()).allMatch(result -> !result.ok() || result.reason() == null);
    }

    @Test
    @DisplayName("拒绝的条目不影响已成功的条目（部分失败不回滚）")
    void keepsSucceededItemsOnPartialFailure() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_batch_partial");
        List<WriteItem> items = List.of(
                WriteItem.user(USER, "custom", "应该写进去", null),
                WriteItem.user(" ", "custom", "这条必须被拒", null));

        BatchOutcome outcome = fixture.manager().batchWrite(TENANT, items);

        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(fixture.repository().pageUserMemory(TENANT, USER, null, 10, 0))
                .extracting(UserMemoryRow::content)
                .containsExactly("应该写进去");
        assertThat(fixture.repository().pageUserMemory(TENANT, " ", null, 10, 0)).isEmpty();
    }

    @Test
    @DisplayName("非法的合法值边界：target 大小写与空白被归一化后仍可写入")
    void normalizesTargetCase() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_batch_target_case");
        List<WriteItem> items = List.of(
                new WriteItem(" GLOBAL ", null, null, "key-1", null, "全局记忆", null),
                new WriteItem(null, USER, null, null, "custom", "默认按用户记忆", null));

        BatchOutcome outcome = fixture.manager().batchWrite(TENANT, items);

        assertThat(outcome.succeeded()).isEqualTo(2);
        assertThat(outcome.results()).extracting(BatchOutcome.ItemResult::target)
                .containsExactly(WriteItem.TARGET_GLOBAL, WriteItem.TARGET_USER);
    }

    @Test
    @DisplayName("超过条数上限即抛参数异常（不静默截断，避免悄悄丢数据）")
    void rejectsOverLimit() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_batch_limit");
        List<WriteItem> tooMany = new ArrayList<>();
        for (int i = 0; i <= MemoryManager.MAX_BATCH_ITEMS; i++) {
            tooMany.add(WriteItem.user(USER, "custom", "内容" + i, null));
        }

        assertThatThrownBy(() -> fixture.manager().batchWrite(TENANT, tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 " + MemoryManager.MAX_BATCH_ITEMS);
    }

    @Test
    @DisplayName("空批量返回 0/0 而不是报错")
    void acceptsEmptyBatch() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_batch_empty");

        BatchOutcome outcome = fixture.manager().batchWrite(TENANT, List.of());

        assertThat(outcome.total()).isZero();
        assertThat(outcome.succeeded()).isZero();
        assertThat(outcome.partial()).isFalse();
    }

    @Test
    @DisplayName("批量读取返回命中行与未命中 id，重复 id 去重后计数")
    void returnsFoundAndMissing() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_batch_query");
        Map<String, Object> first =
                fixture.service().saveUserMemory(TENANT, USER, "custom", "记忆一");
        Map<String, Object> second =
                fixture.service().saveUserMemory(TENANT, USER, "preference", "记忆二");
        String id1 = String.valueOf(first.get("id"));
        String id2 = String.valueOf(second.get("id"));

        BatchQueryOutcome outcome = fixture.manager()
                .batchQuery(TENANT, USER, List.of(id1, id2, "not-exist", id1));

        assertThat(outcome.requested()).isEqualTo(3);
        assertThat(outcome.found()).extracting(UserMemoryRow::memoryId)
                .containsExactlyInAnyOrder(id1, id2);
        assertThat(outcome.missing()).containsExactly("not-exist");
        assertThat(outcome.allFound()).isFalse();
        assertThat(fixture.manager().batchQuery(TENANT, USER, List.of(id1)).allFound()).isTrue();
    }

    @Test
    @DisplayName("批量读取按租户隔离：其他租户的 id 一律视为未命中")
    void queryIsTenantScoped() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_batch_query_tenant");
        Map<String, Object> saved =
                fixture.service().saveUserMemory("t2", USER, "custom", "租户二记忆");
        String id = String.valueOf(saved.get("id"));

        BatchQueryOutcome outcome = fixture.manager().batchQuery(TENANT, USER, List.of(id));

        assertThat(outcome.found()).isEmpty();
        assertThat(outcome.missing()).containsExactly(id);
        assertThat(outcome.missRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("批量读取的超限与空集合边界")
    void batchQueryBoundaries() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_batch_query_edge");
        MemoryManager manager = fixture.manager();

        assertThat(manager.batchQuery(TENANT, USER, List.of()).requested()).isZero();
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= MemoryManager.MAX_BATCH_QUERY_IDS; i++) {
            tooMany.add("id-" + i);
        }
        assertThatThrownBy(() -> manager.batchQuery(TENANT, USER, tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 " + MemoryManager.MAX_BATCH_QUERY_IDS);
    }
}
