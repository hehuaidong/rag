# RAG 智能问答系统

基于 **Spring Boot 3 + LangChain4j + PostgreSQL 18.3 + PGVector + Redis** 实现的 RAG（检索增强生成）智能问答系统，支持文档上传、文本分块、向量化存储、语义检索、智能问答及 SSE 流式输出。

系统在基础 RAG 之上引入了轻量级 **ReAct Agent** 架构，支持意图路由与回答幻觉校验。

---

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2 + JDK 21 |
| RAG 核心 | LangChain4j 0.36.0 |
| 大模型 | 阿里云 DashScope（OpenAI 兼容模式）：`qwen3.6-plus` + `text-embedding-v4` |
| 向量存储 | PostgreSQL 18.3 + PGVector（`vector(1024)`） |
| 业务持久化 | Spring Data JPA + 自定义 Hibernate `UserType` 映射 `vector` 类型 |
| 缓存 | Redis |
| PDF 解析 | Apache PDFBox |

---

## 四个核心设计亮点

1. **JPA 自定义 `UserType` 映射 PostgreSQL `vector(1024)`**
   - 不依赖 `PgVectorEmbeddingStore` 的现成黑盒，手写原生 SQL + JPA 实现向量存储与检索，完全掌握 PGVector 底层原理。

2. **Redis 热缓存 + PostgreSQL 冷持久化的双层对话历史**
   - Redis 缓存最近 10 轮对话上下文，用于快速组装 Prompt；PostgreSQL 持久化全量历史记录，支持查询与审计。

3. **HNSW 向量索引 + Cosine Distance 语义检索**
   - 使用 PGVector 的 `<=>` 算子执行 Cosine Distance 检索，并建立 HNSW 近似最近邻索引，保证高维向量查询性能。

4. **轻量级 ReAct Agent 架构**
   - **路由 Agent**：自动识别用户意图（RAG 检索 / 通用闲聊 / 工具调用），实现智能路由。
   - **Reflection Agent**：对 RAG 生成的答案做幻觉校验，若检测到编造内容则拦截并返回安全提示。

---

## 环境要求

- **JDK 21+**
- **PostgreSQL 16+**（本地已验证 18.3）
- **PGVector 扩展**（版本 0.8.1+）
- **Redis**（默认端口 6379）
- **Maven 3.6+**

---

## 快速启动

### 1. 克隆/下载项目

```bash
cd /Users/hehuaidong/IdeaProjects/rag
```

### 2. 初始化数据库

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

预期返回 PostgreSQL、PGVector、Redis、DashScope 全部 `UP`。

### 2. 上传文档

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/your/test_doc.txt"
```

支持格式：`txt`、`md`、`pdf`

### 3. 向量检索

```bash
curl -X POST http://localhost:8080/api/retrieval/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Spring Boot 有什么特点",
    "topK": 3
  }'
```

返回相似度最高的文档切片及 `distance` 分数。

### 4. 智能问答（非流式）

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "demo-user",
    "question": "根据资料，Spring Boot 提供了哪些特性？"
  }'
```

### 5. 智能问答（SSE 流式）

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "userId": "demo-user",
    "question": "根据资料，Spring Boot 提供了哪些特性？"
  }'
```

### 6. 查询对话历史

```bash
curl "http://localhost:8080/api/chat/history?userId=demo-user&page=0&size=10"
```

### 7. 清空对话历史

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
│   │   ├── RetrievalController.java
│   │   └── HealthController.java
│   ├── service/
│   │   ├── document/                    # 文档管理
│   │   ├── rag/                         # RAG 核心（Embedding、检索、Prompt）
│   │   └── chat/                        # 对话历史 + 编排服务
│   ├── agent/                           # ReAct 路由 + Reflection 校验
│   ├── entity/                          # JPA Entity
│   ├── repository/                      # JPA Repository
│   ├── dto/                             # 请求/响应 DTO
│   ├── common/                          # 统一响应体、工具类
│   └── exception/                       # 全局异常处理
```

---

## 注意事项

- `max-distance` 默认配置为 `0.35`，对应 cosine similarity ≥ 0.65。若实际检索结果偏少，可适当调大该值。
- 路由 Agent 基于单条问题做意图识别，对于强依赖上下文的指代消解追问，可能误路由为 `CHAT` 或 `RAG`，这是已知优化点。
- SSE 流式当前为简化实现（整句生成后分段推送），生产环境可替换为 `StreamingChatLanguageModel` 实现真正的逐 token 流式。
