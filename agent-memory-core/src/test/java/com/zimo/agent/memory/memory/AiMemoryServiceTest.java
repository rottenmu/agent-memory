package com.zimo.agent.memory.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.agent.memory.governance.ImportanceScorer;
import com.zimo.agent.memory.model.MemoryPage;
import com.zimo.agent.memory.model.UserMemoryRow;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import com.zimo.agent.memory.storage.impl.H2OltpMemoryRepository;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 记忆服务的隔离、分页与删除语义测试。
 *
 * <p>对应 PRD 成功标准 2–7 的单元级验证（真机验证步骤见 PRD §十二）。这些缺陷的共同特征是
 * **静默错误**：不报错、返回值看起来合理，但数据读不到或删不掉，因此必须有断言把语义钉死。</p>
 */
class AiMemoryServiceTest {

    /** 每个测试用独立库名，避免共享内存库（DB_CLOSE_DELAY=-1）导致相互污染。 */
    private static AiMemoryService service(String db) {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", "sa", "");
        AiMemorySensitiveFilter sensitiveFilter = new AiMemorySensitiveFilter();
        return new AiMemoryService(new H2OltpMemoryRepository(ds),
                sensitiveFilter, MemorySecurityConfig.DEFAULT,
                new ImportanceScorer(sensitiveFilter));
    }

    private static String text(Map<String, Object> record) {
        return String.valueOf(record.get("content"));
    }

    /** 写入结果回带的命中信号名；显式指定重要性时为空列表。 */
    private static List<String> signals(Map<String, Object> saved) {
        return ((List<?>) saved.get("importanceSignals")).stream().map(String::valueOf).toList();
    }

    /* ---------------- D9：类别缺省 ---------------- */

    @Test
    @DisplayName("D9：不传 category 也能读到该用户全部记忆（画像 + 原子记忆）")
    void listsAllUserMemoryWhenCategoryAbsent() {
        AiMemoryService svc = service("svc_d9");
        svc.saveUserMemory("t1", "u1", "custom", "客户要求月报");
        svc.saveUserMemory("t1", "u1", "history", "上周完成交付");
        svc.saveUserMemory("t1", "u1", "persona", "偏好中文");

        MemoryPage all = svc.listUserMemory("t1", "u1", null, null, null);

        assertThat(all.total()).isEqualTo(3);
        assertThat(all.records()).extracting(AiMemoryServiceTest::text)
                .containsExactlyInAnyOrder("客户要求月报", "上周完成交付", "偏好中文");
        assertThat(all.truncated()).isFalse();
    }

    @Test
    @DisplayName("category 过滤只返回指定类别")
    void filtersByCategory() {
        AiMemoryService svc = service("svc_category");
        svc.saveUserMemory("t1", "u1", "custom", "客户要求月报");
        svc.saveUserMemory("t1", "u1", "history", "上周完成交付");

        MemoryPage page = svc.listUserMemory("t1", "u1", "history", null, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(text(page.records().get(0))).isEqualTo("上周完成交付");
    }

    @Test
    @DisplayName("用户长期记忆不混入全局记忆与会话变量")
    void excludesGlobalAndSessionVar() {
        AiMemoryService svc = service("svc_scope");
        svc.saveUserMemory("t1", "u1", "custom", "用户记忆");
        svc.saveGlobalMemory("t1", "rule", "全局规则");
        svc.saveSessionVar("t1", "s1", "k", "v");

        MemoryPage page = svc.listUserMemory("t1", "u1", null, null, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(text(page.records().get(0))).isEqualTo("用户记忆");
    }

    /* ---------------- 隔离性（标准 6 / 7） ---------------- */

    @Test
    @DisplayName("租户隔离：同名 userId 在两个租户互不可见（含画像缓存）")
    void isolatesTenantsForSameUserId() {
        AiMemoryService svc = service("svc_tenant");
        svc.saveUserMemory("t1", "u1", "custom", "租户一的记忆");
        svc.saveUserMemory("t2", "u1", "custom", "租户二的记忆");
        svc.saveUserMemory("t1", "u1", "persona", "租户一的画像");
        svc.saveUserMemory("t2", "u1", "persona", "租户二的画像");

        MemoryPage tenant1 = svc.listUserMemory("t1", "u1", null, null, null);
        MemoryPage tenant2 = svc.listUserMemory("t2", "u1", null, null, null);

        assertThat(tenant1.total()).isEqualTo(2);
        assertThat(tenant2.total()).isEqualTo(2);
        assertThat(tenant1.records()).extracting(AiMemoryServiceTest::text)
                .containsExactlyInAnyOrder("租户一的记忆", "租户一的画像");
        assertThat(tenant2.records()).extracting(AiMemoryServiceTest::text)
                .containsExactlyInAnyOrder("租户二的记忆", "租户二的画像");
    }

    @Test
    @DisplayName("用户隔离：同租户的两个用户互不可见")
    void isolatesUsersWithinTenant() {
        AiMemoryService svc = service("svc_user");
        svc.saveUserMemory("t1", "u1", "custom", "用户一的记忆");
        svc.saveUserMemory("t1", "u2", "custom", "用户二的记忆");

        MemoryPage user1 = svc.listUserMemory("t1", "u1", null, null, null);

        assertThat(user1.total()).isEqualTo(1);
        assertThat(text(user1.records().get(0))).isEqualTo("用户一的记忆");
    }

    /* ---------------- D10：分页与截断可见（标准 5） ---------------- */

    @Test
    @DisplayName("D10：600 条记忆可完整分页，两页无重叠且末页与全量一致")
    void paginatesSixHundredRowsWithoutSilentTruncation() {
        AiMemoryService svc = service("svc_page");
        for (int i = 0; i < 600; i++) {
            svc.saveUserMemory("t1", "u1", "custom", "记忆-" + i);
        }

        MemoryPage all = svc.listUserMemory("t1", "u1", null, 600, 0);
        MemoryPage firstPage = svc.listUserMemory("t1", "u1", null, 10, 0);
        MemoryPage lastPage = svc.listUserMemory("t1", "u1", null, 10, 590);

        assertThat(all.total()).isEqualTo(600);
        assertThat(all.records()).hasSize(600);
        assertThat(firstPage.records()).hasSize(10);
        assertThat(lastPage.records()).hasSize(10);

        List<Object> firstIds = firstPage.records().stream().map(r -> r.get("id")).toList();
        List<Object> lastIds = lastPage.records().stream().map(r -> r.get("id")).toList();
        assertThat(firstIds).doesNotContainAnyElementsOf(lastIds);

        List<Object> expectedTail = all.records().subList(590, 600).stream().map(r -> r.get("id")).toList();
        assertThat(lastIds).containsExactlyElementsOf(expectedTail);
    }

    @Test
    @DisplayName("truncated 表示「仍有未返回数据」")
    void marksTruncatedWhenMoreRowsRemain() {
        AiMemoryService svc = service("svc_truncated");
        for (int i = 0; i < 5; i++) {
            svc.saveUserMemory("t1", "u1", "custom", "m" + i);
        }

        assertThat(svc.listUserMemory("t1", "u1", null, 2, 0).truncated()).isTrue();
        assertThat(svc.listUserMemory("t1", "u1", null, 10, 0).truncated()).isFalse();
        assertThat(svc.listUserMemory("t1", "u1", null, 2, 4).truncated()).isFalse();
    }

    /* ---------------- D11：时间口径 ---------------- */

    @Test
    @DisplayName("D11：记录的 ts 取自记忆自身时间，而非查询时刻")
    void timestampComesFromMemoryNotQueryTime() throws InterruptedException {
        AiMemoryService svc = service("svc_ts");
        svc.saveUserMemory("t1", "u1", "custom", "旧记忆");
        // 拉开写入与查询的时钟，否则同一毫秒内无法区分两种口径
        Thread.sleep(5);
        long queryAt = System.currentTimeMillis();

        Map<String, Object> record = svc.listUserMemory("t1", "u1", null, null, null)
                .records().get(0);

        assertThat(((Number) record.get("ts")).longValue()).isLessThan(queryAt);
        assertThat(record.get("timestamp")).isNotNull();
    }

    /* ---------------- D12 / D13：删除语义（标准 3 / 4） ---------------- */

    @Test
    @DisplayName("D13：删不存在的 id 返回 0，不再谎报成功")
    void reportsRealDeleteCount() {
        AiMemoryService svc = service("svc_delete_missing");

        assertThat(svc.deleteUserMemory("t1", "u1", "not-exist-id")).isZero();
        assertThat(svc.deleteSessionVar("t1", "s1", "not-exist-key")).isZero();
        assertThat(svc.deleteGlobalMemory("t1", "not-exist-key")).isZero();
    }

    @Test
    @DisplayName("D12：按画像 id 删除能真正删掉 L3 记录")
    void deletesPersonaById() {
        AiMemoryService svc = service("svc_delete_persona");
        Map<String, Object> saved = svc.saveUserMemory("t1", "u1", "persona", "偏好中文");
        String personaId = String.valueOf(saved.get("id"));

        int deleted = svc.deleteUserMemory("t1", "u1", personaId);

        assertThat(deleted).isEqualTo(1);
        assertThat(svc.listUserMemory("t1", "u1", "persona", null, null).total()).isZero();
    }

    @Test
    @DisplayName("D13：不带 id 时真正清空该用户全部长期记忆，且不影响他人")
    void clearsAllUserMemory() {
        AiMemoryService svc = service("svc_clear");
        svc.saveUserMemory("t1", "u1", "persona", "画像");
        svc.saveUserMemory("t1", "u1", "custom", "自定义");
        svc.saveUserMemory("t1", "u2", "custom", "另一个用户");

        int deleted = svc.deleteUserMemory("t1", "u1", null);

        assertThat(deleted).isEqualTo(2);
        assertThat(svc.listUserMemory("t1", "u1", null, null, null).total()).isZero();
        assertThat(svc.listUserMemory("t1", "u2", null, null, null).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("D13：会话变量 key 为空时真正清空该会话（MCP 清空分支）")
    void clearsSessionVars() {
        AiMemoryService svc = service("svc_session_clear");
        svc.saveSessionVar("t1", "s1", "a", "1");
        svc.saveSessionVar("t1", "s1", "b", "2");
        svc.saveSessionVar("t1", "s2", "a", "其他会话");

        int deleted = svc.deleteSessionVar("t1", "s1", null);

        assertThat(deleted).isEqualTo(2);
        assertThat(svc.getSessionVars("t1", "s1", null, null).total()).isZero();
        assertThat(svc.getSessionVars("t1", "s2", null, null).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("会话变量按 key 删除只命中该 key")
    void deletesSessionVarByKey() {
        AiMemoryService svc = service("svc_session_key");
        svc.saveSessionVar("t1", "s1", "aa", "1");
        svc.saveSessionVar("t1", "s1", "ab", "2");

        int deleted = svc.deleteSessionVar("t1", "s1", "a");

        // key=a 不匹配 aa/ab（前缀必须带等号），因此一条都不该删
        assertThat(deleted).isZero();
        assertThat(svc.getSessionVars("t1", "s1", null, null).total()).isEqualTo(2);
    }

    /* ---------------- 重要性写入（M2） ---------------- */

    @Test
    @DisplayName("未指定 importance 时自动评估，并在返回体回带来源与信号")
    void assessesImportanceWhenNotSpecified() {
        AiMemoryService svc = service("svc_imp_auto");

        Map<String, Object> saved = svc.saveUserMemory(
                "t1", "u1", "preference", "以后都用 Excel 导出报表", null);

        assertThat((Double) saved.get("importance")).isBetween(0.05, 0.85);
        assertThat(saved.get("importanceSource")).isEqualTo("scored");
        assertThat(signals(saved)).contains("explicit_long_term", "category_prior");
    }

    @Test
    @DisplayName("自动打分不会触达免淘汰阈值 —— 保护状态只能来自显式声明")
    void autoScoreNeverReachesProtectedThreshold() {
        AiMemoryService svc = service("svc_imp_not_protected");

        Map<String, Object> saved = svc.saveUserMemory(
                "t1", "u1", "persona", "记住我的名字，我叫李雷，以后都用 Excel 导出", null);

        assertThat((Double) saved.get("importance"))
                .isLessThan(ImportanceScorer.PROTECTED_THRESHOLD);
    }

    @Test
    @DisplayName("显式 importance 允许到 1.0，且不回显未参与打分的自动信号")
    void explicitImportanceReachesOneAndSuppressesSignals() {
        AiMemoryService svc = service("svc_imp_explicit");

        Map<String, Object> saved = svc.saveUserMemory(
                "t1", "u1", "persona", "以后都用 Excel 导出报表", 1.0);

        assertThat(saved.get("importance")).isEqualTo(1.0);
        assertThat(saved.get("importanceSource")).isEqualTo("explicit");
        assertThat(signals(saved)).isEmpty();
    }

    @Test
    @DisplayName("显式 importance 越界被收敛到 0~1")
    void explicitImportanceIsClamped() {
        AiMemoryService svc = service("svc_imp_clamp");

        assertThat(svc.saveUserMemory("t1", "u1", "custom", "内容一", 1.8).get("importance"))
                .isEqualTo(1.0);
        assertThat(svc.saveUserMemory("t1", "u1", "custom", "内容二", -0.5).get("importance"))
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("落库的重要性可被列表查询读回（写入与读取口径一致）")
    void persistedImportanceIsReadable() {
        AiMemoryService svc = service("svc_imp_roundtrip");
        svc.saveUserMemory("t1", "u1", "persona", "以后都用 Excel 导出报表", 0.95);

        MemoryPage page = svc.listUserMemory("t1", "u1", "persona", null, null);

        assertThat(page.total()).isEqualTo(1);
        Map<String, Object> row = page.records().get(0);
        assertThat(row.get("importance")).isEqualTo(0.95);
        // 断言取模型常量而非字面量：layer 是跨层联合查询的产出标识，写死字符串会随实现漂移
        assertThat(row.get("layer")).isEqualTo(UserMemoryRow.LAYER_L3);
    }

    @Test
    @DisplayName("重要性在脱敏前评估 —— 手机号原文能命中敏感降权，写入仍被脱敏")
    void importanceIsAssessedBeforeMasking() {
        AiMemoryService svc = service("svc_imp_mask_order");

        Map<String, Object> saved = svc.saveUserMemory(
                "t1", "u1", "history", "我的手机号是 13812348000", null);

        assertThat(saved.get("sensitiveMasked")).isEqualTo(true);
        assertThat(String.valueOf(saved.get("content"))).doesNotContain("13812348000");
        assertThat(signals(saved)).contains("sensitive");
    }
}
