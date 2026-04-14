package com.example.rag.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 向量嵌入服务：调用 text-embedding-v4 生成 1024 维向量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * 将文本向量化
     */
    public float[] embed(String text) {
        long start = System.currentTimeMillis();
        Embedding embedding = embeddingModel.embed(text).content();
        log.info("[耗时-Embedding接口] textLength={}, cost={}ms", text != null ? text.length() : 0, System.currentTimeMillis() - start);
        return embedding.vector();
    }
}
