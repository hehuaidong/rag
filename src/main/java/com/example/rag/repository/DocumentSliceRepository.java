package com.example.rag.repository;

import com.example.rag.dto.SliceSearchResult;
import com.example.rag.entity.DocumentSlice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentSliceRepository extends JpaRepository<DocumentSlice, Long> {

    void deleteByDocumentId(Long documentId);

    /**
     * 基于 PGVector 的 Cosine Distance (<=>) 执行语义检索，返回 TopK 切片及距离分数。
     * 手写原生 SQL 向量检索，不依赖 PgVectorEmbeddingStore 黑盒。
     */
    @Query(value = """
            SELECT id, document_id, chunk_index, content,
                   embedding <=> CAST(:embedding AS vector(1024)) AS distance
            FROM document_slice
            ORDER BY embedding <=> CAST(:embedding AS vector(1024))
            LIMIT :topK
            """, nativeQuery = true)
    List<Object[]> findTopKByEmbeddingNative(
            @Param("embedding") String embedding,
            @Param("topK") int topK);

    /**
     * 带最大距离过滤的语义检索（distance <= maxDistance）
     */
    @Query(value = """
            SELECT id, document_id, chunk_index, content,
                   embedding <=> CAST(:embedding AS vector(1024)) AS distance
            FROM document_slice
            WHERE embedding <=> CAST(:embedding AS vector(1024)) <= :maxDistance
            ORDER BY embedding <=> CAST(:embedding AS vector(1024))
            LIMIT :topK
            """, nativeQuery = true)
    List<Object[]> findTopKByEmbeddingNativeWithThreshold(
            @Param("embedding") String embedding,
            @Param("topK") int topK,
            @Param("maxDistance") double maxDistance);
}
