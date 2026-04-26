# RAG 智能问答系统 - 完整测试用例

> 按模块分类，建议按顺序执行。每个用例包含：测试目的、curl 命令、预期结果。

---

## 前置准备

1. 确保应用已启动：`mvn spring-boot:run` 或 `java -jar target/rag-1.0.0.jar`
2. 确保 PostgreSQL、Redis 已启动
3. 基础 URL：`http://localhost:8080`

---

## 模块一：系统基础接口

### TC-1.1 健康检查
**目的：** 验证四大依赖（PostgreSQL、PGVector、Redis、DashScope）全部连通

```bash
curl -s http://localhost:8080/api/health | jq .
```

**预期结果：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "status": "UP",
    "components": {
      "postgresql": "UP",
      "pgvector": "UP",
      "redis": "UP",
      "dashscope": "UP"
    }
  }
}
```

---

## 模块二：文档管理

### 前置：创建测试文件
```bash
cat > /tmp/test_doc.txt << 'EOF'
Spring Boot 是一个用于简化 Spring 应用初始搭建以及开发过程的框架。
它提供了自动配置、起步依赖和嵌入式服务器等特性，非常适合微服务开发。
Spring Boot 让开发者可以快速创建独立运行的、生产级别的基于 Spring 的应用。
EOF
```

### TC-2.1 上传 TXT 文档
**目的：** 验证文档上传、文本提取、分块、向量化全流程

```bash
curl -s -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/tmp/test_doc.txt" | jq .
```

**预期结果：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "fileName": "test_doc.txt",
    "fileType": "txt",
    "totalChunks": 1
  }
}
```

### TC-2.2 文档列表分页查询
**目的：** 验证文档元数据可查询

```bash
curl -s "http://localhost:8080/api/documents?page=0&size=10" | jq '.data.content[0]'
```

**预期结果：** 返回包含 `id=1`、`fileName="test_doc.txt"` 的文档对象

### TC-2.3 上传空文件（异常）
**目的：** 验证空文件上传有友好提示

```bash
touch /tmp/empty.txt
curl -s -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/tmp/empty.txt" | jq .
```

**预期结果：**
```json
{
  "code": 500,
  "message": "文档内容为空，无法处理"
}
```

### TC-2.4 上传不支持格式（异常）
**目的：** 验证格式校验

```bash
curl -s -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/dev/null;filename=test.docx" | jq .
```

**预期结果：**
```json
{
  "code": 500,
  "message": "不支持的文件类型：docx，仅支持：txt,md,pdf"
}
```

### TC-2.5 向量检索接口
**目的：** 验证 PGVector `<=>` Cosine Distance 检索正常

```bash
curl -s -X POST http://localhost:8080/api/retrieval/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Spring Boot 有什么特点",
    "topK": 3
  }' | jq '.data[0]'
```

**预期结果：**
```json
{
  "id": 1,
  "documentId": 1,
  "chunkIndex": 0,
  "content": "Spring Boot 是一个用于简化...",
  "distance": 0.21
}
```
> `distance` 值应在 `0~0.35` 之间，说明检索命中。

### TC-2.6 删除文档
**目的：** 验证级联删除（文档 + 切片 + 向量）

```bash
curl -s -X DELETE http://localhost:8080/api/documents/1 | jq .
```

**预期结果：** `code: 200`

### TC-2.7 删除后检索为空
**目的：** 验证删除后向量数据同步清理

```bash
curl -s -X POST http://localhost:8080/api/retrieval/search \
  -H "Content-Type: application/json" \
  -d '{"query": "Spring Boot 有什么特点", "topK": 3}' | jq '.data | length'
```

**预期结果：** `0`

---

## 模块三：RAG 智能问答（基于混合检索）

### 前置：测试知识库

当前已上传 4 份测试文档，共 9 个切片：

| 文档 | ID | 切片数 | 内容主题 |
|------|-----|--------|----------|
| `01-microservice.md` | 5 | 2 | 微服务架构设计（概念、拆分原则、通信方式） |
| `02-product-spec.md` | 6 | 2 | T58-PRO-2024 智能门禁终端规格（型号、硬件、认证编号） |
| `03-devops-guide.md` | 7 | 3 | DevOps 实践（CI/CD、Docker、K8s、SRE） |
| `04-financial-compliance.md` | 8 | 2 | 财务合规管理（报销标准、采购审批、合同流程） |

---

### 分类一：语义检索场景（向量检索优势）

> 这类问题依赖语义理解，向量检索能捕捉概念关联。

#### TC-3.1 微服务概念理解
**目的：** 验证口语化/概念型问题能基于微服务文档回答

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-ms",
    "question": "微服务架构有哪些核心特征？"
  }' | jq '.data'
```

**预期结果：**
- `answer` 中包含"单一职责"、"独立部署"、"去中心化治理"等文档关键词
- `referencedSliceIds` 包含文档 5 的切片 ID

#### TC-3.2 DevOps 概念解释
**目的：** 验证概念定义型问题的召回

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-devops",
    "question": "什么是持续集成和持续部署？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中包含 CI/CD 的定义、核心工具（Jenkins、GitLab CI）和部署策略（蓝绿、金丝雀）

#### TC-3.3 财务制度理解
**目的：** 验证对制度/流程类问题的语义召回

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-finance",
    "question": "公司报销差旅费的标准是什么？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中包含不同职级的酒店标准（高管≤2000、中层≤800、普通员工≤400）和餐补标准

#### TC-3.4 容器化概念对比
**目的：** 验证语义相似性召回（Docker vs Kubernetes）

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-k8s",
    "question": "Docker 和 Kubernetes 有什么区别？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中区分 Docker（容器打包工具）和 K8s（容器编排平台），提及 Pod、Deployment、Service 等概念

---

### 分类二：关键词检索场景（ES 全文检索优势）

> 这类问题包含专有名词、型号、缩写、编号，ES 的 IK 分词+BM25 能精确命中。

#### TC-3.5 产品型号精确查询
**目的：** 验证 ES 对产品型号的精确匹配能力

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-product",
    "question": "T58-PRO-2024 的处理器是什么型号？"
  }' | jq '.data'
```

**预期结果：**
- `answer` 中包含"海思 Hi3559A"
- `referencedSliceIds` 包含文档 6 的切片 ID

#### TC-3.6 SN 码格式查询
**目的：** 验证 ES 对特定格式字符串的精确召回

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-product",
    "question": "设备序列号是什么格式？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中包含"SN:T58P2403XXXXXXXX"的格式说明

#### TC-3.7 缩写词精确查询
**目的：** 验证 ES 对技术缩写（K8s、PVC、SLO）的精确匹配

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-k8s",
    "question": "Kubernetes 中的 PVC 是什么意思？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中包含"PVC：PersistentVolumeClaim（持久卷声明）"

#### TC-3.8 金额数字精确查询
**目的：** 验证 ES 对金额/数字的精确匹配优于向量模糊语义

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-finance",
    "question": "100万元以上的合同需要谁来审批？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中包含"董事会审批"

#### TC-3.9 认证编号精确查询
**目的：** 验证 ES 对长串编号（类似身份证号）的精确召回

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-product",
    "question": "产品的 CCC 认证编号是多少？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中包含"2024010903528888"

#### TC-3.10 多缩写同时查询
**目的：** 验证多个缩写词同时出现在问题中时，ES 仍能精确召回

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-sre",
    "question": "SLO、SLI、SLA 分别是什么意思？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中分别解释 SLO（服务等级目标）、SLI（服务等级指标）、SLA（服务等级协议）

---

### 分类三：混合检索增强场景

> 这类问题同时涉及语义理解和关键词，混合检索通过 RRF 融合比单路召回更全面。

#### TC-3.11 短文本/缩写 + 语义结合
**目的：** 验证 RRF 融合对"缩写+上下文"问题的提升

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-hybrid",
    "question": "K8s 中如何实现滚动更新？"
  }' | jq '.data'
```

**预期结果：**
- `answer` 中包含滚动更新的概念和 K8s 中的实现方式
- `referencedSliceIds` 包含文档 7 的切片 ID
- 向量能理解"如何实现"的语义，ES 能精确命中"K8s"

#### TC-3.12 跨文档概念关联
**目的：** 验证混合检索对跨文档概念关联的召回能力

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-hybrid",
    "question": "微服务拆分和 DevOps 持续部署有什么关系？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中提及微服务独立部署与 CI/CD 流水线的关联，可能引用文档 5 和文档 7

#### TC-3.13 口语化 + 专有名词
**目的：** 验证口语化表达中的专有名词能被 ES 增强召回

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-hybrid",
    "question": "咱们公司的 T58 门禁支持 NFC 刷卡吗？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中包含"支持 NFC 刷卡"、"ISO 14443 Type A/B"等信息

多轮+混合
```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-hybrid",
    "question": "还支持什么？"
  }' | jq '.data.answer'
```

**预期结果：** 回答中包含"支持 4G Cat.4"等信息
---

### 分类四：未命中与异常场景

#### TC-3.14 未命中资料（无关问题）
**目的：** 验证无关问题时检索为空，模型基于 Prompt 约束给出安全回答

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-empty",
    "question": "今天北京天气怎么样？"
  }' | jq '.data'
```

**预期结果：**
- `answer` 中包含"根据现有资料无法回答"或类似表述
- `referencedSliceIds` 为空列表 `[]`

#### TC-3.15 userId 为空（异常）
**目的：** 验证参数校验

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"userId":"","question":"测试"}' | jq .
```

**预期结果：**
```json
{
  "code": 500,
  "message": "userId 不能为空"
}
```

#### TC-3.16 流式问答（命中资料）
**目的：** 验证 SSE 流式输出接口正常，且基于混合检索结果回答

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "userId": "test-stream",
    "question": "微服务架构的通信方式有哪些？"
  }'
```

**预期结果：** 终端逐段输出文字，内容涉及同步 HTTP、gRPC、消息队列等，最后以 `data:[DONE]` 结束

#### TC-3.17 真实流式问答（逐 token 推送）
**目的：** 验证基于 `StreamingChatLanguageModel` 的真实逐 token 流式输出

```bash
curl -N -X POST http://localhost:8080/api/chat/stream/real \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "userId": "test-real-stream",
    "question": "Docker 和 Kubernetes 有什么区别？"
  }'
```

**预期结果：**
- 终端能够观察到文字**逐 token/逐 chunk 实时输出**
- 最后以 `data:[DONE]` 结束

---

## 模块四：对话管理与上下文

### TC-4.1 多轮对话上下文
**目的：** 验证 Redis 缓存的历史对话能被拼入 Prompt

**第一轮：**
```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "context-test",
    "question": "根据资料，Spring Boot 是什么？"
  }' | jq '.data.answer'
```

**第二轮（追问，含指代）：**
```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "context-test",
    "question": "它适合什么场景？"
  }' | jq '.data.answer'
```

**预期结果：** 第二轮回答能直接理解"它"指代 Spring Boot，提到"微服务开发"等内容

### TC-4.2 查询对话历史
**目的：** 验证 PostgreSQL 持久化记录可查询

```bash
curl -s "http://localhost:8080/api/chat/history?userId=demo-user&page=0&size=5" | jq '.data.content | length'
```

**预期结果：** 返回大于 0 的数字（说明历史已落库）

### TC-4.3 清空对话历史
**目的：** 验证 Redis 缓存和 PostgreSQL 记录双清空

```bash
curl -s -X DELETE "http://localhost:8080/api/chat/history?userId=demo-user" | jq .
curl -s "http://localhost:8080/api/chat/history?userId=demo-user&page=0&size=5" | jq '.data.empty'
```

**预期结果：**
- 第一条返回 `code: 200`
- 第二条返回 `true`

---

## 模块五：Agent 增强（ReAct + Reflection）

### TC-5.1 ReAct 路由 - 工具调用
**目的：** 验证"现在几点了"被路由到工具调用

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "agent-test",
    "question": "现在几点了？"
  }' | jq '.data.answer'
```

**预期结果：** 返回当前时间（如"现在是 2026年4月14日 12点30分..."）

### TC-5.2 ReAct 路由 - 闲聊
**目的：** 验证"你好"被路由到直接 Chat，不走 RAG

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "agent-test",
    "question": "你好"
  }' | jq '.data.answer'
```

**预期结果：** 返回寒暄问候语

### TC-5.3 ReAct 路由 - RAG（默认）
**目的：** 验证资料相关问题走 RAG

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "agent-test",
    "question": "根据资料，Spring Boot 提供了哪些特性？"
  }' | jq '.data.referencedSliceIds'
```

**预期结果：** `referencedSliceIds` 不为空

### TC-5.4 Reflection 幻觉校验
**目的：** 验证 Reflection Agent 能拦截偏离资料的编造回答

> 该测试较难稳定复现，因为依赖大模型生成内容。若需强制触发，可在代码中临时修改 `ReflectionAgentService` 的 Prompt 为更严格的判定标准。

**正常测试方式：**
观察 `app.log` 中是否出现 `Reflection 校验结果：isValid=true/false` 的日志。

```bash
grep "Reflection 校验结果" app.log | tail -5
```

**预期结果：** 能看到校验日志输出

---

## 模块六：边界与异常测试

### TC-6.1 无文档库时问答
**目的：** 验证空知识库场景下的系统表现

> 先确保所有文档已删除（执行 TC-2.6）

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "empty-test",
    "question": "Spring Boot 有什么特点？"
  }' | jq '.data.answer'
```

**预期结果：** 回答包含"根据现有资料无法回答"或类似安全提示

### TC-6.2 向量化 API 超时/失败
**目的：** 验证外部 API 异常有统一异常处理

> 临时修改 `application.yml` 中的 API Key 为错误值，重启后测试：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "error-test",
    "question": "测试"
  }' | jq .
```

**预期结果：**
```json
{
  "code": 500,
  "message": "系统繁忙，请稍后再试"
}
```

> 测试完成后记得改回正确 API Key。

---

## 演示脚本

建议按以下顺序执行，边说边演示：

```bash
# 1. 健康检查（10秒）
curl -s http://localhost:8080/api/health | jq .

# 2. 上传文档（20秒）
curl -s -X POST http://localhost:8080/api/documents/upload -F "file=@/tmp/test_doc.txt" | jq .

# 3. 向量检索（20秒）
curl -s -X POST http://localhost:8080/api/retrieval/search \
  -H "Content-Type: application/json" \
  -d '{"query":"Spring Boot 有什么特点","topK":3}' | jq '.data[0]'

# 4. SSE 流式问答（1分钟）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"userId":"demo-user","question":"根据资料，Spring Boot 适合什么场景？"}'

# 5. SSE 真实流式问答（1分钟）
curl -N -X POST http://localhost:8080/api/chat/stream/real \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"userId":"stream-real-test","question":"根据资料，Spring Boot 提供了哪些特性？"}'

# 6. Agent 路由 - 工具调用（20秒）
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"userId":"agent-test","question":"现在几点了？"}' | jq '.data.answer'

# 6. 查询历史（10秒）
curl -s "http://localhost:8080/api/chat/history?userId=demo-user&page=0&size=5" | jq '.data.content | length'
```

---

## 测试后清理

如需清理测试数据，执行：

```bash
# 清空所有对话历史
curl -s -X DELETE "http://localhost:8080/api/chat/history?userId=demo-user"
curl -s -X DELETE "http://localhost:8080/api/chat/history?userId=agent-test"
curl -s -X DELETE "http://localhost:8080/api/chat/history?userId=context-test"

# 删除测试文档（将 id 替换为实际值）
curl -s -X DELETE http://localhost:8080/api/documents/1
```
