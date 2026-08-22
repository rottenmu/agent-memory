package com.zimo.module.agentmemory.vector;

/**
 * 纯 Java 内存向量相似度工具：不依赖任何外部向量库（无 JNI）。
 *
 * <p>用于 L1 原子记忆召回、历史会话相似度检索的降级实现；维度对齐后
 * 计算余弦相似度 / 内积，返回 Top-K 索引与分数。</p>
 */
public final class PureJavaVectorUtil {

    private PureJavaVectorUtil() {
    }

    /** 余弦相似度（两个向量维度必须一致；任一零向量返回 0）。 */
    public static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** 内积相似度。 */
    public static double dot(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
        }
        return dot;
    }

    /**
     * 在候选向量中检索与查询向量最相似的 Top-K。
     *
     * @param query    查询向量
     * @param corpus   候选向量列表（与 query 同维度）
     * @param topK     返回条数
     * @return 分数数组（与 corpus 同序，未参与排序）
     */
    public static double[] rankScores(float[] query, java.util.List<float[]> corpus, int topK) {
        if (corpus == null || corpus.isEmpty() || query == null) {
            return new double[0];
        }
        double[] scores = new double[corpus.size()];
        for (int index = 0; index < corpus.size(); index++) {
            scores[index] = cosine(query, corpus.get(index));
        }
        return scores;
    }

    /** 简单确定性字符串特征向量（维度固定 {@code dimensions}，供无嵌入模型时使用）。 */
    public static float[] featureVector(String text, int dimensions) {
        float[] vector = new float[dimensions];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        // 字符哈希散列到向量桶（word 级更佳，此处为轻量降级实现）
        for (char ch : text.toCharArray()) {
            int bucket = (ch * 31) % dimensions;
            vector[Math.abs(bucket)] += 1.0f;
        }
        // L2 归一化
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            double scale = Math.sqrt(norm);
            for (int index = 0; index < vector.length; index++) {
                vector[index] = (float) (vector[index] / scale);
            }
        }
        return vector;
    }
}
