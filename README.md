# RAG 智能问答系统

基于 **Spring Boot 3 + LangChain4j + PostgreSQL 18.3 + PGVector + Elasticsearch + Redis** 实现的 RAG（检索增强生成）智能问答系统，支持文档上传、文本分块、向量化存储、**混合检索**（语义检索 + 全文检索）、智能问答及 SSE 流式输出。

系统在基础 RAG 之上引入了：
- **混合检索（Hybrid Retrieval）**：PGVector 向量检索 + Elasticsearch 全文检索，通过 RRF 融合提升召回率
- **轻量级 ReAct Agent** 架构：支持意图路由与回答幻觉校验

---

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2 + JDK 21 |
| RAG 核心 | LangChain4j 0.36.0 |
| 大模型 | 阿里云 DashScope（OpenAI 兼容模式）：`qwen3.6-plus` + `text-embedding-v4` |
| 向量存储 | PostgreSQL 18.3 + PGVector（`vector(1024)`） |
| 全文检索 | Elasticsearch 7.17.28 + IK 中文分词器（`ik_smart`） |
| 混合检索 | RRF（Reciprocal Rank Fusion）融合向量 + ES 结果 |
| 业务持久化 | Spring Data JPA + 自定义 Hibernate `UserType` 映射 `vector` 类型 |
| 缓存 | Redis |
| PDF 解析 | Apache PDFBox |

---

## 五个核心设计亮点

1. **JPA 自定义 `UserType` 映射 PostgreSQL `vector(1024)`**
   - 不依赖 `PgVectorEmbeddingStore` 的现成黑盒，手写原生 SQL + JPA 实现向量存储与检索，完全掌握 PGVector 底层原理。

2. **混合检索（Hybrid Retrieval）：向量语义 + ES 全文，RRF 融合**
   - **PGVector 向量检索**：基于 Cosine Distance 的语义相似度检索，擅长概念理解、同义词召回。
   - **Elasticsearch 全文检索**：基于 IK 分词 + BM25 的关键词检索，擅长专有名词、型号、缩写、数字的精确匹配。
   - **RRF 融合**：两路并行执行，按 Reciprocal Rank Fusion 公式融合排序，取 Top 3 送入 LLM。
   - **降级策略**：ES 宕机时自动降级为纯向量检索，PG 宕机时自动降级为纯 ES 检索，双路都不可用返回空列表。

3. **Redis 热缓存 + PostgreSQL 冷持久化的双层对话历史**
   - Redis 缓存最近 10 轮对话上下文，用于快速组装 Prompt；PostgreSQL 持久化全量历史记录，支持查询与审计。

4. **HNSW 向量索引 + Cosine Distance 语义检索**
   - 使用 PGVector 的 `<=>` 算子执行 Cosine Distance 检索，并建立 HNSW 近似最近邻索引，保证高维向量查询性能。

5. **轻量级 ReAct Agent 架构**
   - **路由 Agent**：自动识别用户意图（RAG 检索 / 通用闲聊 / 工具调用），实现智能路由。
   - **Reflection Agent**：对 RAG 生成的答案做幻觉校验，若检测到编造内容则拦截并返回安全提示。

---

## 环境要求

- **JDK 21+**
- **PostgreSQL 16+**（本地已验证 18.3）
- **PGVector 扩展**（版本 0.8.1+）
- **Elasticsearch 7.17.x**（已安装 IK 中文分词器）
- **Redis**（默认端口 6379）
- **Maven 3.6+**

---

## 快速启动

### 1. 克隆/下载项目

```bash
cd /Users/hehuaidong/IdeaProjects/rag
```

### 2. 启动 Elasticsearch

确保本地 ES 已安装 IK 分词器并运行在 `localhost:9200`：

```bash
# 验证 ES 状态
curl http://localhost:9200/_cluster/health

# 验证 IK 分词器已安装
curl -X POST "http://localhost:9200/_analyze" \
  -H "Content-Type: application/json" \
  -d '{"analyzer":"ik_smart","text":"微服务架构"}'
```

### 3. 初始化数据库

确保 PostgreSQL 和 PGVector 已安装，然后执行：

```bash
/Applications/Postgres.app/Contents/Versions/18/bin/psql -U hehuaidong -d postgres -c "CREATE DATABASE ai_java;"
/Applications/Postgres.app/Contents/Versions/18/bin/psql -U hehuaidong -d ai_java -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

> 若你的 `psql` 路径不同，请替换为实际路径。

### 3. 修改配置

打开 `src/main/resources/application.yml`，确认以下配置：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ai_java
    username: hehuaidong        # 替换为你的本地 Mac 用户名
    password:                   # 空密码则留空
```

> 大模型 API Key 已预填，如需替换可修改 `langchain4j.open-ai.*.api-key`。

### 4. 编译运行

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="/Users/hehuaidong/anzhuangbao/apache-maven-3.6.3/bin:$PATH"
mvn spring-boot:run
```

应用启动后访问：http://localhost:8080/api/health

---

## 核心接口说明

### 1. 健康检查

```bash
curl http://localhost:8080/api/health
```

预期返回 PostgreSQL、PGVector、Redis、Elasticsearch、DashScope 全部 `UP`。

### 2. 上传文档

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/your/test_doc.txt"
```

支持格式：`txt`、`md`、`pdf`

### 3. 混合检索（推荐）

```bash
curl -X POST http://localhost:8080/api/retrieval/hybrid \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Spring Boot 有什么特点",
    "topK": 3
  }'
```

并行执行向量语义检索 + ES 全文检索，RRF 融合后返回 Top 3。支持单路降级：ES 宕机时自动回退为纯向量检索。

### 4. 纯向量检索（测试用）

```bash
curl -X POST http://localhost:8080/api/retrieval/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Spring Boot 有什么特点",
    "topK": 3
  }'
```

返回相似度最高的文档切片及 `distance` 分数。

### 5. 智能问答（非流式）

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "demo-user",
    "question": "根据资料，Spring Boot 提供了哪些特性？"
  }'
```

问答链路内部已接入混合检索（向量 + ES，RRF 融合 Top 3），无需额外参数。

### 6. 智能问答（SSE 流式）

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "userId": "demo-user",
    "question": "根据资料，Spring Boot 提供了哪些特性？"
  }'
```

### 7. 查询对话历史

```bash
curl "http://localhost:8080/api/chat/history?userId=demo-user&page=0&size=10"
```

### 8. 清空对话历史

```bash
curl -X DELETE "http://localhost:8080/api/chat/history?userId=demo-user"
```

---

## Agent 能力演示

### 工具调用示例

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "agent-test",
    "question": "现在几点了？"
  }'
```

路由 Agent 识别为 `TOOL_GET_CURRENT_TIME`，调用 `LocalDateTime.now()` 后生成自然语言回答。

### 闲聊路由示例

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "agent-test",
    "question": "你好"
  }'
```

路由 Agent 识别为 `CHAT`，直接调用大模型回答，不走 RAG 检索。

---

## 项目结构

```
rag/
├── pom.xml
├── src/main/resources/application.yml
├── src/main/java/com/example/rag/
│   ├── RagApplication.java
│   ├── config/
│   │   ├── LangChain4jConfig.java      # 大模型 Bean 配置
│   │   ├── PgVectorType.java            # 核心：自定义 vector 类型映射
│   │   ├── DatabaseInitConfig.java      # HNSW 索引自动初始化
│   │   └── RedisConfig.java
│   ├── controller/
│   │   ├── DocumentController.java
│   │   ├── ChatController.java
│   │   ├── RetrievalController.java     # 向量检索 + 混合检索测试接口
│   │   └── HealthController.java
│   ├── service/
│   │   ├── document/                    # 文档管理（含 ES 双写）
│   │   ├── rag/                         # RAG 核心（Embedding、混合检索、Prompt）
│   │   └── chat/                        # 对话历史 + 编排服务
│   ├── agent/                           # ReAct 路由 + Reflection 校验
│   ├── es/                              # ES 索引实体 + Repository
│   ├── entity/                          # JPA Entity
│   ├── repository/                      # JPA Repository
│   ├── dto/                             # 请求/响应 DTO
│   ├── common/                          # 统一响应体、工具类
│   └── exception/                       # 全局异常处理
```

---

## 注意事项

- **混合检索已默认接入**：`/api/chat` 问答链路内部自动使用 `HybridRetrievalService`，无需额外参数。向量检索接口 `/api/retrieval/search` 保留用于测试和降级。
- **ES 与 PG 数据一致性**：文档上传时同步双写 ES，删除时同步删 ES。若出现不一致，可手动重建 ES 索引（删除后重新上传文档）。
- **IK 分词器**：ES 使用 `ik_smart`（粗粒度）分词，对专有名词、型号、缩写友好。如需更高召回，可改为 `ik_max_word`（细粒度）。
- **降级策略**：ES 不可用时自动降级为纯向量检索，PG 不可用时自动降级为纯 ES 检索，双路都不可用返回空列表，LLM 回答"根据现有资料无法回答"。
- `max-distance` 默认配置为 `0.35`，对应 cosine similarity ≥ 0.65。若实际检索结果偏少，可适当调大该值。
- 路由 Agent 基于单条问题做意图识别，对于强依赖上下文的指代消解追问，可能误路由为 `CHAT` 或 `RAG`，这是已知优化点。
