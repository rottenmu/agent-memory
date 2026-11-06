package com.zimo.agent.memory.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.model.L3Persona;
import com.zimo.agent.memory.storage.FakeOltpMemoryRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * L3 用户画像片段组装验证。
 *
 * <p>本类钉住的是<b>注入契约</b>，不是画像怎么产生：</p>
 * <ol>
 *   <li>按策略顺序读类别，渲染成带冲突声明的一段文本；</li>
 *   <li>同一内容只出现一次（persona 与 preference 可能存了同一句话）；</li>
 *   <li>超上限显式截断并带标记，不静默丢内容；</li>
 *   <li>查库失败降级为「本轮无画像」，不抛、不连带 L1；</li>
 *   <li>未启用时<b>连查库都不做</b>（用调用计数验证，避免"开关看着关了其实还在查"）；</li>
 *   <li>注入内容必须落 L0，否则「模型当时看到了什么」不可追溯。</li>
 * </ol>
 */
class PersonaFragmentComposerTest {

    private static final String TENANT = "t1";
    private static final String USER = "u1";
    private static final String SESSION = "s1";
    private static final String TRACE = "trace-l3-1";

    private static MemoryScope scope() {
        return MemoryScope.of(TENANT, USER, SESSION);
    }

    @Nested
    @DisplayName("渲染")
    class Render {

        @Test
        @DisplayName("三类画像按策略顺序渲染，且带「以用户当前陈述为准」的冲突声明")
        void rendersTypesInPolicyOrder() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "我是数据平台的李雷，负责报表");
            repo.put("preference", "以后都用 Excel 导出报表");
            repo.put("habit", "先看结论再看细节");

            String fragment = composer(repo, PersonaInjection.DEFAULT).compose(scope(), TRACE);

            assertThat(fragment).startsWith(PersonaFragmentComposer.PERSONA_HEADER);
            assertThat(fragment).contains("以用户当前陈述为准");
            assertThat(fragment.indexOf("我是数据平台的李雷"))
                    .isLessThan(fragment.indexOf("以后都用 Excel 导出报表"));
            assertThat(fragment.indexOf("以后都用 Excel 导出报表"))
                    .isLessThan(fragment.indexOf("先看结论再看细节"));
        }

        @Test
        @DisplayName("同一内容出现在两个类别时只渲染一次")
        void deduplicatesSameContent() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "负责数据平台");
            repo.put("preference", "负责数据平台");

            String fragment = composer(repo, PersonaInjection.DEFAULT).compose(scope(), TRACE);

            assertThat(fragment.split("负责数据平台", -1)).hasSize(2);
        }

        @Test
        @DisplayName("超上限时截断并附加显式标记")
        void truncatesWithMark() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", repeat("画像", 200));

            String fragment = composer(repo, new PersonaInjection(true, null, 50))
                    .compose(scope(), TRACE);

            assertThat(fragment).endsWith(PersonaFragmentComposer.TRUNCATED_MARK);
            assertThat(fragment.length())
                    .isEqualTo(50 + PersonaFragmentComposer.TRUNCATED_MARK.length());
        }

        @Test
        @DisplayName("内容为空的画像不参与渲染")
        void skipsBlankContent() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "   ");
            repo.put("preference", null);

            assertThat(composer(repo, PersonaInjection.DEFAULT).compose(scope(), TRACE)).isNull();
        }

        private static String repeat(String unit, int times) {
            return unit.repeat(times);
        }
    }

    @Nested
    @DisplayName("降级与开关")
    class Degrade {

        @Test
        @DisplayName("没有任何画像时返回 null（不注入空标题）")
        void noPersonaReturnsNull() {
            assertThat(composer(new PersonaRepository(), PersonaInjection.DEFAULT)
                    .compose(scope(), TRACE)).isNull();
        }

        @Test
        @DisplayName("查库抛异常时降级为 null，不向调用方抛")
        void repositoryFailureDegrades() {
            PersonaRepository repo = new PersonaRepository();
            repo.failure = new IllegalStateException("画像库炸了");

            assertThat(composer(repo, PersonaInjection.DEFAULT).compose(scope(), TRACE)).isNull();
            assertThat(repo.queries).isEqualTo(1);
        }

        @Test
        @DisplayName("persona.enabled=false 时连查库都不做")
        void disabledPolicySkipsQueries() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "我是李雷");

            String fragment = composer(repo, new PersonaInjection(false, null, 0))
                    .compose(scope(), TRACE);

            assertThat(fragment).isNull();
            assertThat(repo.queries).isZero();
        }

        @Test
        @DisplayName("引擎总开关关闭时同样不查库（不做半开状态）")
        void engineDisabledSkipsQueries() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "我是李雷");
            PersonaFragmentComposer composer = new PersonaFragmentComposer(
                    repo, PersonaInjection.DEFAULT, false);

            assertThat(composer.compose(scope(), TRACE)).isNull();
            assertThat(repo.queries).isZero();
        }

        @Test
        @DisplayName("隔离维度不可用（缺 userId）时不查库")
        void unusableScopeSkipsQueries() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "我是李雷");

            assertThat(composer(repo, PersonaInjection.DEFAULT)
                    .compose(MemoryScope.of(TENANT, "  ", SESSION), TRACE)).isNull();
            assertThat(repo.queries).isZero();
        }
    }

    @Nested
    @DisplayName("L0 留痕")
    class Trace {

        @Test
        @DisplayName("注入成功时写一条 L0，来源为上下文注入且元数据标明 L3")
        void recordsInjectionToL0() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "我是李雷");

            composer(repo, PersonaInjection.DEFAULT).compose(scope(), TRACE);

            assertThat(repo.rawLogs).hasSize(1);
            L0RawLog log = repo.rawLogs.get(0);
            assertThat(log.traceId()).isEqualTo(TRACE);
            assertThat(log.source()).isEqualTo(PersonaFragmentComposer.SOURCE_CONTEXT_INJECTION);
            assertThat(log.metaJson()).contains("\"layer\":\"L3\"");
            assertThat(log.content()).contains("我是李雷");
        }

        @Test
        @DisplayName("traceId 为空时仍然返回片段，只是不留痕")
        void nullTraceIdSkipsRecordOnly() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "我是李雷");

            String fragment = composer(repo, PersonaInjection.DEFAULT).compose(scope(), null);

            assertThat(fragment).contains("我是李雷");
            assertThat(repo.rawLogs).isEmpty();
        }

        @Test
        @DisplayName("留痕写失败不影响片段返回")
        void recordFailureDoesNotBreakInjection() {
            PersonaRepository repo = new PersonaRepository();
            repo.put("persona", "我是李雷");
            repo.rawLogFailure = new IllegalStateException("L0 写不进去");

            assertThat(composer(repo, PersonaInjection.DEFAULT).compose(scope(), TRACE))
                    .contains("我是李雷");
        }
    }

    private static PersonaFragmentComposer composer(PersonaRepository repo, PersonaInjection policy) {
        return new PersonaFragmentComposer(repo, policy, true);
    }

    /**
     * 画像仓储替身：只实现 L3 读取与 L0 留痕两条真实路径，其余继承空实现。
     *
     * <p><b>不用 Mockito</b>：本模块测试一直用显式替身，读用例时不必先推断 stub 的行为。</p>
     */
    private static final class PersonaRepository extends FakeOltpMemoryRepository {

        private final Map<String, L3Persona> personas = new HashMap<>();
        private final List<L0RawLog> rawLogs = new ArrayList<>();
        private int queries;
        private RuntimeException failure;
        private RuntimeException rawLogFailure;

        void put(String type, String content) {
            personas.put(type, L3Persona.create(type + "-id", USER, type, content, 1,
                    System.currentTimeMillis(), TENANT));
        }

        @Override
        public L3Persona getPersona(String tenantId, String userId, String personaType) {
            queries++;
            if (failure != null) {
                throw failure;
            }
            return personas.get(personaType);
        }

        @Override
        public void saveRawLog(L0RawLog log) {
            if (rawLogFailure != null) {
                throw rawLogFailure;
            }
            rawLogs.add(log);
        }
    }
}
