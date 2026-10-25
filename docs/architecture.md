# 架构设计说明

> 本文是 `mall-ai-assistant` 的施工图：模块职责、数据模型、接口契约、参数与降级策略。
> README 讲「这个项目是什么」，本文讲「这个项目怎么造」。

- 状态标记：**已定** = 设计确定；**待验证** = 需要实现时确认（多为对齐 hmall 现有接口）
- 最后更新：2026.04

---

## 1. 系统边界

```
┌─────────────────────────────┐
│  前端 / Postman / 测试脚本    │
└──────────────┬──────────────┘
               │ HTTPS + SSE
┌──────────────▼──────────────────────────────────────┐
│  ai-service（本仓库新增，独立进程，端口 9090）          │
│  ─ 意图理解、RAG 检索、工具编排、流式输出、可观测性       │
│  ─ 拥有独立数据库 mall_ai                            │
└──────────────┬──────────────────────────────────────┘
               │ OpenFeign（只读）
┌──────────────▼──────────────────────────────────────┐
│  hmall 微服务（二次开发，不改动交易链路）                │
│  ─ item-service：商品查询 / 库存                      │
│  ─ trade-service：订单查询                            │
│  ─ 拥有业务库 hm-mall                                │
└─────────────────────────────────────────────────────┘
```

**三条边界原则（已定）**

1. **AI 层不直连业务库**。所有业务数据必须走 hmall 的既有接口，保证权限、审计、缓存策略不被绕过。
2. **业务库与 AI 库物理分离**。`mall_ai` 独立 schema，AI 层可以随时重建索引而不影响交易。
3. **AI 故障不影响交易**。`ai-service` 挂掉时，hmall 的商品与订单接口照常工作。

---

## 2. 模块划分（ai-service 内部）

```
ai-service/src/main/java/com/mall/ai/
├── config/
│   ├── ChatClientConfig        # ChatClient 构建、Advisor、工具注册入口
│   ├── VectorStoreConfig       # RedisVectorStore 与索引参数
│   ├── FeignConfig             # 超时、重试、拦截器（透传 traceId）
│   └── AsyncConfig             # 流式与异步任务线程池
├── controller/
│   ├── ChatController          # POST /api/chat/stream（SSE）
│   └── KnowledgeController     # 知识库上传与重建
├── service/
│   ├── ChatService             # 编排：检索 → 提示词 → 模型 → 工具循环 → 推送
│   ├── RagService              # 切分、向量化、检索、阈值过滤
│   └── ToolCallService         # 工具执行、结果裁剪、日志落库
├── tool/
│   ├── ProductTool             # @Tool 查商品 / 查库存
│   └── OrderTool               # @Tool 查订单
├── reader/
│   ├── DocumentReaderFactory   # 按 MIME 分派 Tika / Text 解析
│   └── CustomTextSplitter      # 带长度上限的切分器
├── client/
│   ├── ItemClient              # OpenFeign → item-service
│   └── TradeClient             # OpenFeign → trade-service
├── repository/                 # MyBatis-Plus Mapper
├── entity/ · dto/ · vo/
└── common/
    ├── Result                  # 统一响应体
    ├── BizException
    └── TraceContext            # traceId（MDC）
```

**每个类的单句职责**

| 类 | 职责 | 不做什么 |
|---|---|---|
| `ChatService` | 一次问答的完整编排 | 不写 SQL、不拼向量 |
| `RagService` | 只负责「文本 → 可检索的向量」这一段 | 不调模型生成回答 |
| `ToolCallService` | 工具执行 + 结果裁剪 + 日志 | 不决定「该不该调工具」（模型决定） |
| `ProductTool` | 把 `item-service` 的查询包装成模型可调用的工具 | 不含提示词逻辑 |

---

## 3. 数据模型

### 3.1 数据库

`ai-service` 使用独立 schema `mall_ai`（**已定**）。字符集 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`。

**① 知识库文档表**

```sql
CREATE TABLE kb_document (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  title         VARCHAR(200) NOT NULL                COMMENT '文档标题',
  source_type   VARCHAR(20)  NOT NULL                COMMENT 'CATEGORY 商品参数 / POLICY 售后政策 / FAQ 常见问题 / MANUAL 操作手册',
  source_url    VARCHAR(500)                         COMMENT '来源地址或文件路径',
  content_hash  CHAR(64)     NOT NULL                COMMENT '正文 SHA-256，用于幂等去重',
  chunk_count   INT          NOT NULL DEFAULT 0      COMMENT '切片数量',
  status        TINYINT      NOT NULL DEFAULT 0      COMMENT '0 待处理 / 1 已入库 / 2 处理失败',
  error_msg     VARCHAR(500)                         COMMENT '失败原因',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_hash (content_hash),
  KEY idx_type_status (source_type, status)
) COMMENT='知识库文档';
```

> `content_hash` 唯一索引的作用：同一份文档重复上传不会重复入库，这是入库幂等的唯一可靠手段（文件名会变、内容不会）。

**② 知识切片表**

```sql
CREATE TABLE kb_chunk (
  id          BIGINT      PRIMARY KEY AUTO_INCREMENT,
  doc_id      BIGINT      NOT NULL              COMMENT '所属文档',
  chunk_index INT         NOT NULL              COMMENT '文档内序号，从 0 开始',
  content     TEXT        NOT NULL              COMMENT '切片正文',
  token_count INT                               COMMENT '预估 token 数',
  vector_id   VARCHAR(80) NOT NULL              COMMENT '向量库主键',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_doc_index (doc_id, chunk_index),
  KEY idx_vector (vector_id)
) COMMENT='知识切片（向量库的元数据索引）';
```

> 为什么既存 MySQL 又存向量库：向量库只负责「相似度检索」，不负责「按来源筛选、按文档删除、原文回显」。元数据留在 MySQL，两者用 `vector_id` 关联。

**③ 会话表**

```sql
CREATE TABLE chat_session (
  id          BIGINT      PRIMARY KEY AUTO_INCREMENT,
  session_id  VARCHAR(64) NOT NULL              COMMENT '对外会话标识（UUID）',
  user_id     BIGINT                            COMMENT '关联 hmall 用户，匿名会话为空',
  title       VARCHAR(100)                      COMMENT '会话标题，取首条提问前 30 字',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT     NOT NULL DEFAULT 0,
  UNIQUE KEY uk_session (session_id),
  KEY idx_user_time (user_id, update_time)
) COMMENT='会话';
```

**④ 会话消息表**

```sql
CREATE TABLE chat_message (
  id          BIGINT      PRIMARY KEY AUTO_INCREMENT,
  session_id  VARCHAR(64) NOT NULL,
  role        VARCHAR(16) NOT NULL              COMMENT 'user / assistant / tool / system',
  content     MEDIUMTEXT,
  token_count INT,
  tool_name   VARCHAR(64)                       COMMENT 'role=tool 时记录工具名',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_session_time (session_id, id)
) COMMENT='会话消息';
```

**⑤ 工具调用日志表**

```sql
CREATE TABLE tool_call_log (
  id            BIGINT      PRIMARY KEY AUTO_INCREMENT,
  trace_id      VARCHAR(64) NOT NULL            COMMENT '贯穿一次请求的链路 ID',
  session_id    VARCHAR(64),
  tool_name     VARCHAR(64) NOT NULL,
  arguments     TEXT                            COMMENT '入参 JSON',
  result_digest VARCHAR(500)                    COMMENT '结果摘要（截断），不存全量',
  success       TINYINT     NOT NULL DEFAULT 1,
  error_msg     VARCHAR(500),
  cost_ms       INT,
  create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_trace (trace_id),
  KEY idx_tool_time (tool_name, create_time)
) COMMENT='工具调用日志（可观测性）';
```

> 这张表是「可观测性」的落点：工具调用成功率、平均耗时、失败原因，全从这张表算。面试被问「你怎么知道 AI 应用效果好」，答案在这里。

**⑥ 复用表**

商品、订单、用户等表由 hmall 提供，**不复制到 `mall_ai`**，避免双写不一致（**已定**）。

### 3.2 Redis 结构

> **实测环境（2026.10）**：`10.233.79.116:9379`（**端口是 9379，不是默认 6379**），
> Redis 7.4.7 standalone，已加载 RediSearch **2.10.20**，满足 Spring AI RedisVectorStore 要求。

| 用途 | Key / 索引 | 类型 | TTL | 说明 |
|---|---|---|---|---|
| 向量库 | 索引 `kb-vectorstore`，key 前缀 `kb:embedding:` | **JSON** + 向量 | 无 | Spring AI 1.0 的 RedisVectorStore 写 RedisJSON，不是 HASH |
| 会话记忆 | `ai:memory:{sessionId}` | LIST | 24h | `MessageWindowChatMemory` 持久化 |
| 问答缓存 | `ai:cache:qa:{questionHash}` | STRING | 1h | 相同问题直接命中，省 token |
| 限流 | `ai:limit:{userId}:{分钟}` | STRING（INCR） | 60s | 每用户每分钟最多 N 次 |
| 分布式锁 | `ai:lock:doc:{contentHash}` | STRING（SET NX PX） | 60s | 防止同一文档并发入库 |

**已建成的索引（2026.10 实测 `FT.INFO kb-vectorstore`）**

```
key_type=JSON   prefixes=kb:embedding:   algorithm=HNSW
data_type=FLOAT32   dim=1024   distance_metric=COSINE   num_docs=0
```

**建索引的四个坑（都是踩过的）**

1. **前缀必须和旧索引错开**。这个 Redis 上还留着上个原型建的 `lee-vectorstore`
   （`key_type=JSON`、前缀 `embedding:`、dim 1024、93 条数据）。
   因为它同样只索引 JSON 类型，所以只要新前缀是 `kb:embedding:` 就互不干扰；
   **但一旦写成 `embedding:kb:`，旧索引会把我们的 key 一起吞进去**，检索结果串味。
2. **维度不用手填，但换模型必须重建索引**。Spring AI 的
   `AbstractEmbeddingModel.dimensions()` 在建索引时会探测维度——先查内置模型维度表，
   查不到就真调一次 embedding 接口量长度（本项目实测 1024）。
   换模型后如果维度变了，必须先 `FT.DROPINDEX kb-vectorstore DD` 再启动，
   否则写入报 `Vector dimension mismatch`，而且只在下标时才暴露。
3. **`base-url` 不能带 `/v1`**。Spring AI 自己会拼 `/v1/embeddings`，
   填成 `.../compatible-mode/v1` 就会请求到 `.../v1/v1/embeddings`，启动时报
   `HTTP 404 - No response body`，而且报错堆栈只指向 `RedisVectorStore.afterPropertiesSet`，
   看起来像 Redis 的问题，其实是模型地址写错了。
4. **旧的 93 条数据要不要清**：`FT.DROPINDEX lee-vectorstore DD` 连索引带 key 一起删。
   不清也不影响功能（占约 9MB 内存），但演示时 `FT._LIST` 里多一个索引不好看。

---

## 4. 接口契约

### 4.1 对外：多轮问答（SSE）

```
POST /api/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

**请求体**

```json
{
  "sessionId": "9f1c...e2",     // 首次可为空，服务端生成并随 done 事件返回
  "userId": 123,                 // 可为空（匿名）
  "message": "500以内适合跑步的鞋，能退换吗",
  "debug": false                 // true 时返回检索片段与工具调用明细，仅开发环境生效
}
```

**SSE 事件协议（已定）**

| event | data 示例 | 何时发送 |
|---|---|---|
| `delta` | `{"content":"推荐"}` | 模型每产生一个增量文本块 |
| `tool` | `{"name":"searchProduct","status":"running"}` | 工具开始执行 / 结束 |
| `done` | `{"sessionId":"9f1c","answerId":"a01","tokens":412,"firstTokenMs":380,"costMs":2100}` | 回答完整结束 |
| `error` | `{"code":"MODEL_TIMEOUT","message":"模型响应超时，请稍后重试"}` | 任一环节失败 |

**规则**

1. **必须有 `done` 或 `error` 作为终止帧**，前端据此关闭连接；两者互斥。
2. `id` 字段填自增序号，用于断线重连时定位（配合 `Last-Event-ID`）。
3. `tool` 事件的 `status` 取值：`running` / `success` / `failed`。
4. 每个事件之间保持心跳注释行 `: keep-alive`，间隔 15s，防止网关断连。

> 这一条协议直接修正了原型项目的缺陷：原实现把 `.name(msgType.type)` 注释掉了，前端拿不到事件名，只能靠数据内容猜增量帧与结束帧。

### 4.2 对外：知识库管理

```
POST /api/knowledge/upload      multipart/form-data   # 上传文档并入库
POST /api/knowledge/rebuild     application/json      # 指定 docId 重建索引
GET  /api/knowledge/{docId}                           # 查询入库状态
DELETE /api/knowledge/{docId}                         # 逻辑删除并清理向量
```

**上传响应**

```json
{ "code": 0, "data": { "docId": 10086, "chunkCount": 42, "status": "INDEXED" } }
```

统一响应体（非 SSE 接口）：

```json
{ "code": 0, "msg": "ok", "data": {} }
```

`code` 约定：`0` 成功；`4xx` 客户端错误；`50001` 文档解析失败；`50002` 向量化失败；`50003` 模型不可用。

### 4.3 对内：AI 层调 hmall（**已核对源码 + 已实测**）

> 2026.10 按 `F:\File\JavaWord\hmall` 源码逐行核对，并用真实 item-service 打通验证。
> 下面这份是**实际契约**，与初版设计稿差异很大，以本节为准。

```java
// 实测通过：GET http://127.0.0.1:8081/search/list?key=RIMOWA → total=37
@FeignClient(name = "hmall-item-service", url = "${hmall.item-service.url}")
public interface ItemClient {

    PageDTO<ItemDTO> search(String key, String category, String brand,
                            Integer minPrice, Integer maxPrice,
                            Integer pageNo, Integer pageSize);   // 全部 @RequestParam

    ItemDTO detail(@PathVariable("id") Long id);                // 含库存
}
```

**六处与初版设计稿不一致的地方（都会导致「调通但结果是错的」）**

| 初版设计稿 | 实际情况 |
|---|---|
| 类名 `Result<T>`，成功码 `0` | hmall 是 `R<T>`，成功码 **200**；且 item/trade 的 Controller **不返回 `R` 包装**，直接返回裸 DTO |
| 路径 `/items/search` | 不存在。搜索是 `GET /search/list`，参数 `key/category/brand/minPrice/maxPrice/pageNo/pageSize` |
| 单独接口 `/items/{id}/stock` | 不存在。库存就在 `GET /items/{id}` 的 `ItemDTO.stock` 里，**不需要改 hmall** |
| 返回 `List<ItemVO>` | `PageDTO<ItemDTO>`：`total` / `pages` / `list`，且 `total`、`pages`、`id` 被序列化成**字符串** |
| 端口 8081 / 8085 | item-service 实测 8081（`application.yaml` 覆盖了 `bootstrap.yaml` 里的 8082） |
| 需带 `user-info` 头 | 不需要。`UserInforInterceptor` 是「有就 set、没有也放行」，item/trade 接口不读 `UserContext` |

**三个必须写进代码的坑**

1. **价格单位是「分」**。库里 88,475 条商品的 `price` 全是分，`28900` = 289 元。工具层不除以 100，模型会把「28900 元」报给用户。
2. **只传 `maxPrice` 会返回 0 条**（hmall 的 bug）。`SearchController` 里是
   `.between(query.getMaxPrice() != null, Item::getPrice, minPrice, maxPrice)`，
   只传上限时生成 `price BETWEEN NULL AND 50000`，SQL 恒为 NULL。实测：
   `key=跑步鞋` 1178 条 → 加 `maxPrice` 0 条 → 加 `minPrice=0&maxPrice=50000` 596 条。
   **AI 层传价格上限时必须同时传 `minPrice=0`。**
3. **订单接口不校验归属**。`GET /orders/{id}` 是内部接口，谁拿到 orderId 都能查。
   AI 层必须在工具里按当前 `userId` 过滤，否则等于给模型开了一个任意订单查询接口。

**契约约束（已定）**

- AI 层**只调用 GET 接口**，不调用任何写接口（`PUT /items/stock/deduct`、`POST /orders` 一律禁止）；
- `pageSize` 必须由服务端兜底限制（上限 20），**不信任模型传进来的值**；
- 调 hmall 的超时：连接 1s、读取 3s，超时即视为工具失败。

---

## 5. 核心流程

### 5.1 知识入库

```
上传文件
  → SHA-256 计算 → 查 kb_document 是否已存在（幂等）
  → 分布式锁 ai:lock:doc:{hash}（防并发重复入库）
  → 按 MIME 分派解析器（PDF/Word → Tika；txt/md → TextReader）
  → CustomTextSplitter 切分（带长度上限）
  → 分批 20 条调用 embedding，写入 Redis 向量库
  → 落 kb_chunk（含 vector_id）
  → 更新 kb_document.status = 1
```

**失败处理**：任一批次失败不整体回滚，记录 `status=2` 与 `error_msg`，允许按 `docId` 重试；重试前先按 `doc_id` 清理旧切片与向量，避免重复。

### 5.2 问答（含工具循环）

```
1. 参数校验 + 限流（ai:limit）
2. 取会话记忆（ai:memory:{sessionId}）
3. 向量检索：topK + 相似度阈值过滤
   ├─ 命中 → 拼进提示词，标记「引用片段」
   └─ 未命中 → 不拼空上下文，标记「无引用」
4. 构造 ChatClient 请求：系统提示词 + 历史 + 检索片段 + 工具清单
5. 模型返回：
   ├─ 直接回答 → 流式推送
   └─ 工具调用请求 → 执行工具 → 结果回灌 → 回到 5
     （最大 5 轮，总耗时上限 60s，超限强制收尾）
6. 推送 done 事件；异步落库 chat_message 与 tool_call_log
```

**工具循环的边界（已定）**

| 限制 | 值 | 目的 |
|---|---|---|
| 单次请求最大工具轮次 | 5 | 防止模型陷入循环 |
| 单次请求总超时 | 60s | 保护网关与用户体验 |
| 单工具单次条数上限 | 20 | 防止整表塞进上下文 |
| 工具结果进上下文的长度上限 | 2000 字符 | 控制 token |

### 5.3 提示词结构（**已定**）

```
[角色]     你是一名电商客服助手，只回答与本店商品、订单、售后相关的问题。
[知识约束] 资料片段中用 <doc> 标注的内容是唯一可信来源；片段为空时不要编造。
[工具规则] 需要实时数据（库存、订单状态）时必须调用工具，不要凭记忆回答。
           商品查询必须带关键词或价格上限；一次最多返回 20 条。
[输出格式] 回答控制在 200 字以内；列出商品时用「名称｜价格｜库存」三列。
[拒答边界] 问题超出电商范围时回复「这个问题我暂时无法回答，可以帮你查商品或订单」。
[引用要求] 使用了资料片段时，在结尾用「（来源：文档标题）」标注。
```

---

## 6. 参数与配置

| 参数 | 建议值 | 说明 |
|---|---|---|
| `rag.chunk.size` | 400–600 字 | 小于 300 会切碎语义，大于 800 会稀释相似度 |
| `rag.chunk.overlap` | 80–120 字 | 约 chunk 的 20% |
| `rag.similarity.top-k` | 5 | 配合阈值使用，过大引入噪声 |
| `rag.similarity.threshold` | 0.65–0.75 | **必须在自建评测集上调**，不能拍脑袋 |
| `rag.embedding.batch-size` | 20 | 单次请求过大易超时 |
| `chat.timeout.model` | 30s | 流式与非流式不同，流式按首字节计时 |
| `chat.timeout.tool` | 3s | Feign 读超时 |
| `chat.max-tool-rounds` | 5 | 见 5.2 |
| `memory.window.size` | 10 轮 | 超出后摘要压缩 |
| `cache.qa.ttl` | 1h | 知识库更新后需主动失效 |

**阈值怎么定（不是拍脑袋）**：准备 30 条「问题 + 标准答案 + 应命中文档」的评测集，把阈值从 0.5 扫到 0.9，记录每一档的**召回率**与**误召回数**，取召回率下降拐点前的一档。

---

## 7. 异常与降级

| 故障 | 处理策略 | 用户可见表现 |
|---|---|---|
| 模型 API 超时 / 429 | 指数退避重试 1 次 → 切备用模型 → 仍失败则终止 | `error: MODEL_TIMEOUT`「稍后重试」 |
| 工具调用失败 | 把失败原因作为工具结果回灌给模型，让它换策略或如实说明 | 「库存查询失败，请稍后再试」 |
| 检索为空 | **不拼空上下文**，提示模型「无相关资料，基于通用知识回答并说明未引用店铺资料」 | 回答 + 「未引用店铺资料」标注 |
| 向量库不可用 | 降级为纯模型回答，显式标注「知识库暂不可用」 | 回答 + 降级提示 |
| 会话记忆超长 | 窗口截断 + 摘要压缩 | 无感 |
| hmall 服务不可用 | 工具返回「服务暂时不可用」，模型据此回答 | 「暂时查不到，请稍后」 |
| 并发入库 | Redis 分布式锁，拿不到锁直接返回「处理中」 | 重复上传不产生脏数据 |

---

## 8. 可观测性

**必须埋的三个指标**

1. **首字延迟（TTFT）**：从收到请求到第一个 `delta` 事件，反映用户体感；
2. **工具调用成功率**：`tool_call_log` 中 `success=1` 的占比，按工具名分组；
3. **引用命中率**：本次回答是否使用了检索片段，用于评估 RAG 是否真的在工作。

**日志规范**

- 全链路 `traceId`（MDC 透传，Feign 拦截器带上）；
- 每个请求一条汇总日志：`sessionId / traceId / tokens / firstTokenMs / costMs / toolCalls / cited`；
- **不打印完整提示词与用户原文**（涉及隐私），只打印长度与 hash。

---

## 9. 依赖与版本

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | 21 | ai-service |
| Spring Boot | 3.5.x | ai-service |
| Spring AI | 1.0.0 | BOM 导入 |
| MyBatis-Plus | 3.5.10+ | 适配 Boot 3 |
| MySQL | 8.0.33+ | 两个 schema：`hm-mall` / `mall_ai` |
| Redis | 7.x（带 RediSearch 模块） | Redis Stack，向量库依赖 |
| Spring Cloud Alibaba | 2023.0.x | 与 Boot 3.5 对齐 |
| hmall | Boot 2.7 + JDK 11 | **不升级**，保持原状 |

> **版本冲突的处理原则**：hmall 不动，`ai-service` 独立进程，两者只通过 HTTP 通信。这样 Boot 2.7 与 Boot 3.5 可以共存，不需要 `jakarta` 迁移。

---

## 10. 开发顺序

| 顺序 | 模块 | 完成标准 |
|---|---|---|
| 1 | 建库建表 + Feign 客户端 | 能通过 `ItemClient` 查到一条真实商品 |
| 2 | 知识入库链路 | 上传一份 PDF，`kb_chunk` 有数据，向量库能检索到 |
| 3 | 单轮问答（无工具） | Postman 能拿到基于知识库的回答 |
| 4 | 工具调用 | 问「有没有跑步鞋」能触发 `searchProduct` 并返回真实商品 |
| 5 | SSE 流式 + 事件协议 | 前端能看到逐字输出，且有 `done` 帧 |
| 6 | 会话记忆 + 缓存 | 第二轮追问「那第二款呢」能接上上文 |
| 7 | 日志与指标 | `tool_call_log` 有数据，能算出成功率与平均耗时 |
| 8 | 评测集与阈值调优 | 30 条用例，记录不同阈值下的召回率 |

---

## 附：与两个原型项目的差异

| 维度 | SpringAI 原型 | 本项目 |
|---|---|---|
| 切分 | 按空行切，无长度上限 | 强制 `chunkSize` 上限 + overlap |
| 检索 | 默认 topK，无阈值 | 显式 topK + 阈值，且有评测集支撑 |
| 空检索 | 只打日志，继续拼空上下文 | 返回明确状态，提示模型不要编造 |
| 工具返回 | `void` + 吞异常 | 返回「成功+数据」或「失败+原因」 |
| 流式 | `Flux.toStream().collect()` 阻塞 | `doOnNext` 逐块推送 |
| SSE 事件 | 事件名被注释，前端靠猜 | 显式 `delta` / `tool` / `done` / `error` |
| 多实例 | 静态 Map，单机 | 无状态服务 + Redis 持久化记忆 |
| 配置 | 明文写死 | 环境变量 + `.gitignore` |
