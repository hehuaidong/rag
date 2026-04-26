package com.example.rag.service.rag;

import com.example.rag.dto.SliceSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 混合检索服务：向量检索 + ES 全文检索，RRF 融合
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final RetrievalService retrievalService;
    private final EsRetrievalService esRetrievalService;

    @Value("${rag.retrieval.hybrid.vector-top-k:15}")
    private int vectorTopK;

    @Value("${rag.retrieval.hybrid.es-top-k:15}")
    private int esTopK;

    @Value("${rag.retrieval.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${rag.retrieval.hybrid.timeout-ms:3000}")
    private long timeoutMs;

    @Value("${rag.retrieval.top-k:3}")
    private int finalTopK;

    /**
     * 混合检索：并行执行向量检索和 ES 检索，RRF 融合返回 TopK
     */
    public List<SliceSearchResult> retrieve(String question, float[] questionVector) {
        long start = System.currentTimeMillis();

        // 并行执行两路检索
        CompletableFuture<List<SliceSearchResult>> vectorFuture = CompletableFuture.supplyAsync(() ->
            retrievalService.retrieveTopK(questionVector, vectorTopK)
        );

        CompletableFuture<List<SliceSearchResult>> esFuture = CompletableFuture.supplyAsync(() ->
            esRetrievalService.search(question, esTopK)
        );

        List<SliceSearchResult> vectorResults = null;
        List<SliceSearchResult> esResults = null;
        boolean vectorFailed = false;
        boolean esFailed = false;

        try {
            vectorResults = vectorFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            vectorFailed = true;
            log.warn("[混合检索-降级] 向量检索失败: {}", e.getMessage());
        }

        try {
            esResults = esFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            esFailed = true;
            log.warn("[混合检索-降级] ES检索失败: {}", e.getMessage());
        }

        List<SliceSearchResult> finalResults;

        // 降级策略
        if (vectorFailed && esFailed) {
            log.warn("[混合检索-降级] 两路检索均失败，返回空列表");
            finalResults = Collections.emptyList();
        } else if (vectorFailed) {
            log.warn("[混合检索-降级] 仅使用ES检索结果");
            finalResults = esResults.stream().limit(finalTopK).collect(Collectors.toList());
        } else if (esFailed) {
            log.warn("[混合检索-降级] 仅使用向量检索结果");
            finalResults = vectorResults.stream().limit(finalTopK).collect(Collectors.toList());
        } else {
            // RRF 融合
            finalResults = rrfFusion(vectorResults, esResults);
        }

        log.info("[耗时-混合检索] questionLength={}, cost={}ms, vectorHits={}, esHits={}, finalHits={}",
            question != null ? question.length() : 0,
            System.currentTimeMillis() - start,
            vectorResults != null ? vectorResults.size() : 0,
            esResults != null ? esResults.size() : 0,
            finalResults.size());
        return finalResults;
    }

    /**
     * RRF 融合：score = Σ(1 / (k + rank))
     */
    private List<SliceSearchResult> rrfFusion(List<SliceSearchResult> vectorResults, List<SliceSearchResult> esResults) {
        Map<Long, Double> scoreMap = new HashMap<>();
        Map<Long, SliceSearchResult> resultMap = new HashMap<>();

        // 向量检索 rank（从 1 开始）
        for (int i = 0; i < vectorResults.size(); i++) {
            SliceSearchResult r = vectorResults.get(i);
            double score = 1.0 / (rrfK + i + 1);
            scoreMap.merge(r.getId(), score, Double::sum);
            resultMap.putIfAbsent(r.getId(), r);
        }

        // ES 检索 rank（从 1 开始）
        for (int i = 0; i < esResults.size(); i++) {
            SliceSearchResult r = esResults.get(i);
            double score = 1.0 / (rrfK + i + 1);
            scoreMap.merge(r.getId(), score, Double::sum);
            resultMap.putIfAbsent(r.getId(), r);
        }

        // 按 RRF 分数降序排序，取 TopK
        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(finalTopK)
            .map(e -> resultMap.get(e.getKey()))
            .collect(Collectors.toList());
    }
}
