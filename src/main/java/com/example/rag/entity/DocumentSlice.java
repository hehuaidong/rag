package com.example.rag.entity;

import com.example.rag.config.PgVectorType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

/**
 * 文档切片实体，核心向量表。
 * embedding 字段类型为 float[]，通过 PgVectorType 映射到 PostgreSQL vector(1024)。
 */
@Data
@Entity
@Table(name = "document_slice")
public class DocumentSlice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Type(PgVectorType.class)
    @Column(name = "embedding", nullable = false, columnDefinition = "vector(1024)")
    private float[] embedding;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
