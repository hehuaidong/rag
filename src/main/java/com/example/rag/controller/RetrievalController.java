package com.example.rag.controller;

import com.example.rag.common.Result;
import com.example.rag.dto.SliceSearchResult;
import com.example.rag.service.rag.EmbeddingService;
import com.example.rag.service.rag.HybridRetrievalService;
import com.example.rag.service.rag.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 向量检索接口
 */
@RestController
@RequestMapping("/api/retrieval")
@RequiredArgsConstructor
public class RetrievalController {

    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final HybridRetrievalService hybridRetrievalService;

    @PostMapping("/search")
    public Result<List<SliceSearchResult>> search(@RequestBody SearchRequest request) {
        float[] vector = embeddingService.embed(request.getQuery());
        List<SliceSearchResult> results = retrievalService.retrieveTopK(vector, request.getTopK());
        return Result.success(results);
    }

    /**
     * 混合检索测试接口（不走 LLM，直接返回融合结果）
     */
    @PostMapping("/hybrid")
    public Result<List<SliceSearchResult>> hybridSearch(@RequestBody SearchRequest request) {
        float[] vector = embeddingService.embed(request.getQuery());
        List<SliceSearchResult> results = hybridRetrievalService.retrieve(request.getQuery(), vector);
        return Result.success(results);
    }

    public static class SearchRequest {
        private String query;
        private int topK = 5;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }
}
