package com.example.rag.service.rag;

import com.example.rag.common.VectorUtils;
import com.example.rag.dto.SliceSearchResult;
import com.example.rag.repository.DocumentSliceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量检索服务：基于 PGVector 的 <=> Cosine Distance 执行语义检索
 */
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final DocumentSliceRepository documentSliceRepository;

    @Value("${rag.retrieval.top-k:5}")
    private int topK;

    @Value("${rag.retrieval.max-distance:0.25}")
    private double maxDistance;

    /**
     * 检索与问题向量最相似的文档切片
     */
    public List<SliceSearchResult> retrieve(String question, float[] questionVector) {
        String vectorStr = VectorUtils.toPgVectorString(questionVector);
        List<Object[]> results = documentSliceRepository.findTopKByEmbeddingNativeWithThreshold(vectorStr, topK, maxDistance);

        List<SliceSearchResult> list = new ArrayList<>();
        for (Object[] row : results) {
            SliceSearchResult r = new SliceSearchResult();
            r.setId(((Number) row[0]).longValue());
            r.setDocumentId(((Number) row[1]).longValue());
            r.setChunkIndex(((Number) row[2]).intValue());
            r.setContent((String) row[3]);
            r.setDistance(((Number) row[4]).doubleValue());
            list.add(r);
        }
        return list;
    }

    /**
     * 不带阈值过滤的检索（用于向量检索接口展示）
     */
    public List<SliceSearchResult> retrieveTopK(float[] questionVector, int limit) {
        String vectorStr = VectorUtils.toPgVectorString(questionVector);
        List<Object[]> results = documentSliceRepository.findTopKByEmbeddingNative(vectorStr, limit);

        List<SliceSearchResult> list = new ArrayList<>();
        for (Object[] row : results) {
            SliceSearchResult r = new SliceSearchResult();
            r.setId(((Number) row[0]).longValue());
            r.setDocumentId(((Number) row[1]).longValue());
            r.setChunkIndex(((Number) row[2]).intValue());
            r.setContent((String) row[3]);
            r.setDistance(((Number) row[4]).doubleValue());
            list.add(r);
        }
        return list;
    }
}
