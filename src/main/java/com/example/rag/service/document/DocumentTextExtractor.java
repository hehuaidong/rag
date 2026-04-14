package com.example.rag.service.document;

/**
 * 文档文本提取器接口
 */
public interface DocumentTextExtractor {

    /**
     * 是否支持该文件类型
     */
    boolean supports(String fileType);

    /**
     * 从字节数组中提取纯文本
     */
    String extract(byte[] content);
}
