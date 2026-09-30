package com.zimo.module.agentmemory.storage;

import com.zimo.module.agentmemory.engine.MemorySpanRecord;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.model.L2SceneBlock;
import com.zimo.module.agentmemory.model.L3Persona;
import com.zimo.module.agentmemory.model.UserMemoryRow;
import java.util.List;

/**
 * 测试用的 OLTP 仓储空实现：所有查询返回空集合，删除返回 0 行。
 *
 * <p><b>为什么要有这个类</b>：{@link OltpMemoryRepository} 是四层记忆的完整契约，而多个测试
 * 只需要一个「不关心存储行为」的桩（它们验证的是链路、事件或路由）。若各测试各自手写匿名实现，
 * 接口每新增一个方法就要同步改五处，漏改一处即编译失败，且极易在复制粘贴中写错语义。</p>
 *
 * <p>用法：继承本类并只覆写测试真正关心的方法。</p>
 */
public abstract class FakeOltpMemoryRepository implements OltpMemoryRepository {

    /* ---------- L0 原始日志 ---------- */

    @Override
    public void saveRawLog(L0RawLog log) {
    }

    @Override
    public List<L0RawLog> listRawLogsByTrace(String traceId) {
        return List.of();
    }

    @Override
    public List<L0RawLog> listRawLogsBySession(String sessionId) {
        return List.of();
    }

    @Override
    public List<L0RawLog> listRawLogsPage(int offset, int limit) {
        return List.of();
    }

    @Override
    public List<L0RawLog> listRawLogsSince(long afterId, int limit) {
        return List.of();
    }

    /* ---------- L1 原子记忆 ---------- */

    @Override
    public void saveAtomicMemory(L1AtomicMemory memory) {
    }

    @Override
    public int deleteAtomicMemory(String tenantId, String id) {
        return 0;
    }

    @Override
    public List<L1AtomicMemory> recallAtomicByType(
            String tenantId, String owner, String memoryType, int limit, int offset) {
        return List.of();
    }

    @Override
    public long countAtomicByType(String tenantId, String owner, String memoryType) {
        return 0L;
    }

    @Override
    public List<L1AtomicMemory> recallAtomicByUser(
            String tenantId, String userId, int limit, int offset) {
        return List.of();
    }

    @Override
    public long countAtomicByUser(String tenantId, String userId) {
        return 0L;
    }

    @Override
    public List<L1AtomicMemory> recallSessionVars(
            String tenantId, String sessionId, int limit, int offset) {
        return List.of();
    }

    @Override
    public long countSessionVars(String tenantId, String sessionId) {
        return 0L;
    }

    @Override
    public List<L1AtomicMemory> listAtomicByTrace(String traceId) {
        return List.of();
    }

    @Override
    public int deleteAtomicByType(String tenantId, String owner, String memoryType) {
        return 0;
    }

    @Override
    public int deleteAtomicByUser(String tenantId, String userId) {
        return 0;
    }

    @Override
    public int deleteAtomicBySession(String tenantId, String sessionId) {
        return 0;
    }

    /* ---------- L2 场景块 ---------- */

    @Override
    public void saveSceneBlock(L2SceneBlock block) {
    }

    @Override
    public L2SceneBlock recallSceneBySession(String sessionId) {
        return null;
    }

    @Override
    public List<L2SceneBlock> listScenesBySession(String sessionId, int limit) {
        return List.of();
    }

    /* ---------- L3 画像 ---------- */

    @Override
    public void savePersona(L3Persona persona) {
    }

    @Override
    public L3Persona getPersona(String tenantId, String userId, String personaType) {
        return null;
    }

    @Override
    public int deletePersona(String tenantId, String userId, String personaType) {
        return 0;
    }

    @Override
    public int deletePersonaById(String tenantId, String id) {
        return 0;
    }

    @Override
    public int deletePersonasByUser(String tenantId, String userId) {
        return 0;
    }

    /* ---------- 用户长期记忆合并分页 ---------- */

    @Override
    public List<UserMemoryRow> pageUserMemory(
            String tenantId, String userId, String category, int limit, int offset) {
        return List.of();
    }

    @Override
    public long countUserMemory(String tenantId, String userId, String category) {
        return 0L;
    }

    @Override
    public List<UserMemoryRow> listUserMemoryByIds(
            String tenantId, String userId, List<String> ids) {
        return List.of();
    }

    /* ---------- L1 治理支撑（合并 / 遗忘淘汰） ---------- */

    @Override
    public List<L1AtomicMemory> recallEvictionCandidates(
            String tenantId, long touchedBefore, int limit, int offset) {
        return List.of();
    }

    @Override
    public int updateAtomicGovernance(
            String tenantId, String id, double importance, int accessCount, Long lastAccessAt) {
        return 0;
    }

    @Override
    public int deleteAtomicByIds(String tenantId, List<String> ids) {
        return 0;
    }

    /* ---------- 钻取与统计 ---------- */

    @Override
    public List<L0RawLog> drillDownToRawLog(String traceId) {
        return List.of();
    }

    @Override
    public long countL0Total() {
        return 0L;
    }

    @Override
    public long countL0Today() {
        return 0L;
    }

    @Override
    public int countAtomicByTenant(String tenantId) {
        return 0;
    }

    @Override
    public int countPersonaByTenant(String tenantId) {
        return 0;
    }

    /* ---------- 记忆审计（memory_span，只增不改） ---------- */

    @Override
    public void saveMemorySpan(MemorySpanRecord record) {
        // 默认丢弃：仅需要审计留痕的测试才覆写本方法
    }

    @Override
    public List<MemorySpanRecord> listMemorySpansByTrace(String traceId) {
        return List.of();
    }
}
