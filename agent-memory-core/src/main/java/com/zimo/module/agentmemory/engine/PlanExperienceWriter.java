package com.zimo.module.agentmemory.engine;

import com.zimo.module.agentmemory.governance.BatchOutcome;
import com.zimo.module.agentmemory.governance.FactExtractor;
import com.zimo.module.agentmemory.governance.MemoryManager;
import com.zimo.module.agentmemory.governance.WriteItem;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 计划模式的经验回写（M4-2b，PRD §4.2）。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。</p>
 *
 * <h2>职责</h2>
 * <p>计划<b>执行完成且未被用户驳回</b>时，把这次规划中沉淀出的「做事方法」抽象成一条
 * {@code category=procedural} 的程序性记忆，供后续同类任务在计划模式下预召回。</p>
 *
 * <h2>为什么必须做「驳回守卫」</h2>
 * <p>驳回是用户对方案的<b>否定</b>。把被否定的方案回写成长期经验，等于让模型下次继续
 * 按被拒绝的方式规划，且用户难以察觉原因 —— 与「改不掉用户偏好」（PRD R3）是同一类失败。
 * 因此驳回信号优先于任何「执行成功」判据：驳回即不回写，无需再看其它条件。</p>
 *
 * <h2>为什么只写「步数 + 步骤摘要」而不是计划全文</h2>
 * <p>计划全文包含大量一次性细节（本月对账的具体日期、某个报表名）。原样沉淀会把
 * <b>一次性任务细节污染成长期程序性记忆</b>，后续任何对账任务都会召回这段无关细节，
 * 挤占 topK 名额。抽象成「分几步、哪步需人工确认」才是可跨任务复用的方法论（PRD §4.2 第 2 条）。</p>
 *
 * <h2>失败不反噬</h2>
 * <p>回写是增强能力：写库失败、去重失败都只打 WARN，不影响对话本身。
 * 但绝不静默吞掉 —— 本项目已因静默失败踩过坑。</p>
 *
 * @author WorkBuddy
 * @since 2026-09-17
 */
public class PlanExperienceWriter {

    private static final Logger log = LoggerFactory.getLogger(PlanExperienceWriter.class);

    /** 来源标记：与人工写入 / 会话事实抽取区分开，便于审计与灰度回滚。 */
    public static final String SOURCE_PLAN_WRITEBACK = "plan_writeback";

    /** 摘要里最多保留多少个步骤（超过则折叠，避免记忆条目过长）。 */
    private static final int MAX_STEPS = 8;
    /** 单条程序性记忆的内容上限（字符）。 */
    private static final int MAX_CONTENT = 300;

    private final MemoryManager memoryManager;

    public PlanExperienceWriter(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * 尝试回写一条计划经验。
     *
     * @param scope    隔离维度（租户 / 用户 / 会话）
     * @param rejected 用户是否驳回了本次计划的确认；{@code true} 时<b>直接不回写</b>
     * @param planSteps 本次计划识别出的步骤描述（可为空；为空时退化为只记「做过一次规划」）
     * @param traceId  链路 ID，仅用于日志定位
     * @return 是否真的写入了记忆（驳回、无内容、写库失败均返回 {@code false}）
     */
    public boolean writeback(MemoryScope scope, boolean rejected,
                             List<String> planSteps, String traceId) {
        if (scope == null || !scope.usable()) {
            return false;
        }
        if (rejected) {
            // 驳回守卫：这是 M4-2b 的负向判据落点（PRD 场景 2b）
            log.info("[plan-writeback] 计划被用户驳回，不回写程序性记忆 traceId={} session={}",
                    traceId, scope.sessionId());
            return false;
        }
        String content = compose(planSteps);
        if (content == null) {
            return false;
        }
        try {
            BatchOutcome outcome = memoryManager.batchWrite(scope.tenantId(), List.of(
                    new WriteItem(WriteItem.TARGET_USER, scope.userId(), scope.sessionId(),
                            null, FactExtractor.CATEGORY_PROCEDURAL, content, 0.6)));
            boolean ok = outcome != null && outcome.succeeded() > 0;
            if (ok) {
                log.info("[plan-writeback] 已回写程序性记忆（来源 {}）traceId={} steps={}",
                        SOURCE_PLAN_WRITEBACK, traceId, planSteps == null ? 0 : planSteps.size());
            } else {
                log.warn("[plan-writeback] 回写未成功（succeeded=0）traceId={}", traceId);
            }
            return ok;
        } catch (RuntimeException e) {
            // 不吞异常，但也不让它反噬对话
            log.warn("[plan-writeback] 回写失败 traceId={}：{}", traceId, e.toString());
            return false;
        }
    }

    /**
     * 组装程序性记忆内容：{@code 规划方法：共 N 步 —— 步骤1 / 步骤2}。
     *
     * <p>去重与截断都在这里做，保证落库内容稳定可比对（评测时同一步计划应产出同一字符串）。</p>
     *
     * @return 记忆内容；无有效步骤时返回 {@code null}（不写空条目）
     */
    private static String compose(List<String> planSteps) {
        Set<String> steps = new LinkedHashSet<>();
        if (planSteps != null) {
            for (String step : planSteps) {
                if (step == null || step.trim().isEmpty()) {
                    continue;
                }
                steps.add(step.trim().replace('\n', ' '));
                if (steps.size() >= MAX_STEPS) {
                    break;
                }
            }
        }
        if (steps.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("规划方法：共 ").append(steps.size()).append(" 步 —— ");
        boolean first = true;
        for (String step : steps) {
            if (!first) {
                sb.append(" / ");
            }
            sb.append(step);
            first = false;
        }
        String content = sb.toString();
        return content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
    }
}
