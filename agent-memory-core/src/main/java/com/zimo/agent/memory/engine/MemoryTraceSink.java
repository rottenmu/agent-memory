package com.zimo.agent.memory.engine;

import java.util.Map;

/**
 * 记忆埋点出口（SPI）。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。</p>
 *
 * <p><b>为什么用 SPI 而不是直接调 {@code TraceCollector}</b>：{@code TraceCollector} 位于
 * {@code framework-ai}，而 {@code framework-ai} <b>已经依赖</b> {@code agent-memory-core}
 * （它要用 {@code AiConversationMemory} 与 {@code OltpMemoryRepository}）。若记忆模块反过来
 * 直接依赖 {@code framework-ai}，Maven reactor 会判定循环引用而无法构建。</p>
 *
 * <p>于是把「怎么埋点」抽象成本接口，实现由上层注入：{@code framework-ai} 侧提供
 * {@code TraceCollector::stepFor} 的适配。方法签名刻意与
 * {@code TraceCollector.stepFor(String, String, String, String, String, long, String, Map)}
 * <b>逐参对齐</b>，使适配退化为一个方法引用，不引入任何转换代码。</p>
 *
 * <p><b>可空约定</b>：感知引擎允许 sink 为 {@code null}（未装配埋点时），此时只写审计表、
 * 不发 span。判断「埋点是否生效」的正确方式是看链路里有没有 {@code memory.*} 属性，
 * 而不是看这个 Bean 是否存在。</p>
 */
@FunctionalInterface
public interface MemoryTraceSink {

    /** 未装配埋点时的空实现（不抛异常、不做事）。 */
    MemoryTraceSink NOOP =
            (traceId, stepType, name, inputJson, outputJson, latencyMs, status, attributes) -> {
            };

    /**
     * 记录一个链路步骤。
     *
     * @param traceId    链路 ID；为 {@code null} 时实现方应直接返回（不伪造 ID）
     * @param stepType   步骤类型（{@code retrieval} 或 {@code memory}）
     * @param name       步骤名
     * @param inputJson  输入（自由文本，导出侧不解析）
     * @param outputJson 输出
     * @param latencyMs  耗时毫秒
     * @param status     状态（ok / failed / pending）
     * @param attributes 结构化属性（属性名见 {@link MemorySpanAttributes}）
     */
    void step(String traceId, String stepType, String name, String inputJson, String outputJson,
              long latencyMs, String status, Map<String, Object> attributes);
}
