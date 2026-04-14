package com.example.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数据库初始化配置：应用启动后自动创建 HNSW 向量索引。
 * 这是满足性能需求（≤3s 响应、首包 ≤500ms）的关键保障。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DatabaseInitConfig {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initIndexes() {
        try {
            jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_document_slice_embedding
                ON document_slice
                USING hnsw (embedding vector_cosine_ops);
                """);
            log.info("HNSW 向量索引 idx_document_slice_embedding 检查/创建成功");
        } catch (Exception e) {
            log.error("HNSW 向量索引创建失败：", e);
        }
    }
}
