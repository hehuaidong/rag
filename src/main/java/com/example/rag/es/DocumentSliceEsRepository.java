package com.example.rag.es;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentSliceEsRepository extends ElasticsearchRepository<DocumentSliceEs, Long> {
}
