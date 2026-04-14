package com.example.rag.common;

/**
 * 向量工具类：负责 float[] 与 PostgreSQL vector 字符串格式之间的互转。
 */
public class VectorUtils {

    /**
     * 将 float[] 转换为 PostgreSQL vector 字符串，例如 [0.1,-0.2,0.3]
     */
    public static String toPgVectorString(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将 PostgreSQL vector 字符串解析为 float[]
     */
    public static float[] fromPgVectorString(String value) {
        if (value == null || value.isBlank()) {
            return new float[0];
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return new float[0];
        }
        String[] parts = trimmed.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
