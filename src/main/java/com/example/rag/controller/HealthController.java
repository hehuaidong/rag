package com.example.rag.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.rag.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查接口
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ElasticsearchClient elasticsearchClient;
    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping
    public Result<Map<String, Object>> health() {
        Map<String, String> status = new HashMap<>();

        // PostgreSQL
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(3)) {
                status.put("postgresql", "UP");
            } else {
                status.put("postgresql", "DOWN");
            }
        } catch (Exception e) {
            status.put("postgresql", "DOWN: " + e.getMessage());
        }

        // PGVector
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
            if (count != null && count > 0) {
                status.put("pgvector", "UP");
            } else {
                status.put("pgvector", "DOWN: extension not found");
            }
        } catch (Exception e) {
            status.put("pgvector", "DOWN: " + e.getMessage());
        }

        // Redis
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                status.put("redis", "UP");
            } else {
                status.put("redis", "DOWN");
            }
        } catch (Exception e) {
            status.put("redis", "DOWN: " + e.getMessage());
        }

        // Elasticsearch
        try {
            elasticsearchClient.ping();
            status.put("elasticsearch", "UP");
        } catch (Exception e) {
            status.put("elasticsearch", "DOWN: " + e.getMessage());
        }

        // DashScope API（简单探测，不做真实请求，避免消耗 token）
        try {
            // 仅检查网络连通性，向 base url 发 HEAD 请求
            restTemplate.headForHeaders("https://dashscope.aliyuncs.com/compatible-mode/v1/models");
            status.put("dashscope", "UP");
        } catch (Exception e) {
            // HEAD 可能不被支持，只要网络可达就不算 DOWN
            if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                status.put("dashscope", "DOWN: " + e.getMessage());
            } else {
                status.put("dashscope", "UP");
            }
        }

        boolean allUp = status.values().stream().allMatch(v -> v.equals("UP"));
        Map<String, Object> result = new HashMap<>();
        result.put("status", allUp ? "UP" : "DOWN");
        result.put("components", status);
        return Result.success(result);
    }
}
