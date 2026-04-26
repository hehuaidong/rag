package com.example.rag.service.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.rag.dto.SliceSearchResult;
import com.example.rag.es.DocumentSliceEs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EsRetrievalService {

    private final ElasticsearchClient elasticsearchClient;

    public List<SliceSearchResult> search(String query, int topK) {
        long start = System.currentTimeMillis();
        List<SliceSearchResult> results = new ArrayList<>();

        try {
            SearchResponse<DocumentSliceEs> response = elasticsearchClient.search(s -> s
                    .index("document_slice")
                    .query(q -> q.match(m -> m.field("content").query(query)))
                    .size(topK),
                DocumentSliceEs.class);

            for (Hit<DocumentSliceEs> hit : response.hits().hits()) {
                DocumentSliceEs doc = hit.source();
                if (doc != null) {
                    SliceSearchResult r = new SliceSearchResult();
                    r.setId(doc.getId());
                    r.setDocumentId(doc.getDocumentId());
                    r.setChunkIndex(doc.getChunkIndex());
                    r.setContent(doc.getContent());
                    r.setDistance(null);
                    results.add(r);
                }
            }
        } catch (IOException e) {
            log.error("ES 检索异常", e);
            throw new RuntimeException("ES 检索失败: " + e.getMessage(), e);
        }

        log.info("[耗时-ES检索] queryLength={}, cost={}ms, hits={}",
            query != null ? query.length() : 0,
            System.currentTimeMillis() - start,
            results.size());
        return results;
    }
}
