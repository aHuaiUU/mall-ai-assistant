# 实测踩坑记录

> 全部是真实踩过、有数据或日志佐证的坑。按「现象 → 根因 → 处理」组织，供后来者（包括三个月后的自己）少走弯路。
> 面试向的复盘不在这里，本文只解决工程问题。

## 环境与构建

### 1. 全局 `JAVA_HOME` 指向 JDK 8，编译直接失败

**现象**：`mvn compile` 报 `UnsupportedClassVersionError` 或 `release 21 not supported`。

**根因**：机器默认 `JAVA_HOME` 是 JDK 8（hmall 那边还要 JDK 11），三个 JDK 不能靠全局变量切换。

**处理**：每个会话显式设 `$env:JAVA_HOME='D:\JDK\jdk-21.0.12.1'`，不要改全局。

### 2. `.env` 的相对路径依赖「工作目录」，换个目录启动就读不到

**现象**：启动报 `Failed to bind properties under 'spring.data.redis.port'`，`Value: "${REDIS_PORT}"`——看起来像配置写错。

**根因**：`spring.config.import: optional:file:./ai-service/.env[.properties]` 按**当前工作目录**解析。约定必须从仓库根启动；从 `ai-service/` 启动时路径变成 `ai-service/ai-service/.env`，而 `optional:` 让失败完全静默。

**处理**：固定从仓库根启动；或写成双路径
`optional:file:.env[.properties],optional:file:./ai-service/.env[.properties]`。**用 `optional:` 就要有另一处把「实际加载到了什么」打出来。**

### 3. `Set-Content -Encoding UTF8` 写出 BOM，第一个键失效

**现象**：所有查库请求 500，异常链最底层是 `UnknownHostException: ${MYSQL_HOST}`。

**根因**：PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写 UTF-8 BOM；Spring 的 properties 加载器不剥 BOM，第一个键名变成 `\ufeffMYSQL_HOST`。**只有第一个键坏**，后面的都正常——所以会误判成数据库问题。

**处理**：不要用 `Set-Content`/`Out-File` 改 `.env`。自查：`[IO.File]::ReadAllBytes('.env')[0..2]` 应为 `4D 59 53`（"MYS"），不是 `EF BB BF`。

### 4. 用 `target` 里的旧 jar 启动，跑的是几天前的代码

**现象**：代码改了但行为没变（比如 traceId 响应头不出现）。

**根因**：`mvn test` 只编译不打包，`target/*.jar` 还是旧的。

**处理**：改完 `mvn package` 再 `java -jar`，或直接用 `mvn spring-boot:run`（用 `target/classes`，不存在陈旧问题）。**排查第一步永远是确认跑的是哪份代码。**

### 5. Windows 下 jar 被运行中的实例锁住，`mvn package` 报 `Unable to rename`

**现象**：`repackage failed: Unable to rename ...jar to ...jar.original`。

**根因**：Windows 文件锁——JVM 正在执行这个 jar。

**处理**：先停实例再打包；开发期用 `spring-boot:run`。停不掉时（进程枚举受限）换个端口跑新构建，别硬碰。

### 6. Nacos 启动时它依赖的数据库还没就绪

**现象**：服务启动卡在配置加载，或 Nacos 控制台看不到实例、看不到配置。

**根因**：Nacos 自身依赖 MySQL，DB 未就绪时它的配置接口是「能连但没数据」的状态。

**处理**：排查顺序固定为 **Nacos 自身日志 → 它依赖的库是否可连 → 最后才看业务服务**。别一上来就怀疑业务代码。

## hmall 契约（文档与实现不一致）

### 7. 统一响应体不是 `Result`，而且业务接口根本没包装

**事实**：类名是 `R`（`com.hmall.common.domain.R`），成功码是 **200** 不是 0；更关键的是 item/trade 的 Controller **直接返回裸 DTO/PageDTO**。

**后果**：Feign 方法签名若写成 `Result<PageDTO<ItemDTO>>`，能编译、能调用、**解析出 null 且不报错**。

### 8. 商品搜索是 `GET /search/list`，不是 `/items/search`

参数：`key/category/brand/minPrice/maxPrice/pageNo/pageSize`，返回 `PageDTO<ItemDTO>`（`total`/`pages`/`list`）。`ItemController` 里没有任何搜索方法。

### 9. 没有独立的「查库存」接口，也不需要

库存就在 `GET /items/{id}` 返回的 `ItemDTO.stock` 里。设计稿里的 `/items/{id}/stock` 不存在，**不需要给 hmall 加接口**。

### 10. 价格单位是「分」

`price=28900` 表示 289.00 元。不除以 100，模型会跟用户说「这个行李箱 28900 元」。

### 11. 只传 `maxPrice` 会返回 0 条（hmall 的 bug）

**根因**：`SearchController` 里
`.between(query.getMaxPrice() != null, Item::getPrice, minPrice, maxPrice)`
——条件只看 `maxPrice`，只传上限时生成 `price BETWEEN NULL AND x`，SQL 恒为 NULL。

实测：`key=跑步鞋` 1178 条 → 加 `maxPrice` **0 条** → 加 `minPrice=0` 596 条。

**处理**：AI 层兜底——传价格上限时**强制同时传 `minPrice=0`**。不改 hmall：那是交易链路核心代码，改它要回归评审，AI 层兜底零风险。

### 12. 端口以 `application.yaml` 为准，不是 `bootstrap.yaml`

`bootstrap.yaml` 里写 8082、`application.yaml` 里写 8081 时，实际监听 **8081**（启动日志可证）。不要凭记忆判断配置优先级。

### 13. `Long` 字段被序列化成字符串

hmall 返回 `"total":"88475"`、`"id":"317578"`（防 JS 精度丢失）。Jackson 反序列化到 `Long` 没问题，但**手写 JSON 解析或写日志再解析时要注意**。

### 14. `trade-service` 启动失败：Feign 配置类被误导入成 springfox 的类

**现象**：启动报 `required a bean of type 'springfox.documentation.spi.service.contexts.Defaults'`。

**根因**：`TradeApplication` 里
`@EnableFeignClients(defaultConfiguration = DefaultConfiguration.class)`
的 import 被 IDE 自动补全成了 `springfox...DefaultConfiguration`。

**处理**：改成项目自己的 `com.hmall.api.config.DefaultFeignConfig`（它带 UserContext 透传拦截器，trade 调 item 时需要）。这是本项目对 hmall 的**唯一代码改动**。

## RAG 与向量库

### 15. `base-url` 不能带 `/v1`，否则报 404 且堆栈指向 Redis

**现象**：启动报 `HTTP 404 - No response body`，堆栈指向 `RedisVectorStore.afterPropertiesSet`——看起来像 Redis 连不上。

**根因**：Spring AI 自己会拼 `/v1/embeddings`；填 `.../compatible-mode/v1` 就变成 `/v1/v1/embeddings`。

**处理**：`OPENAI_BASE_URL=https://host/compatible-mode`（止于 `compatible-mode`）。

### 16. Spring AI 1.0 的 RedisVectorStore 写 **JSON**，不是 HASH

`FT.INFO kb-vectorstore` → `key_type=JSON`，依赖 RedisJSON 模块。所以「前缀隔离」是**必须**而不是可选：同库里旧索引（JSON + `embedding:` 前缀）会把新 key 一起索引，检索串味。

### 17. 向量维度是自动探测的，换模型必须删索引重建

`AbstractEmbeddingModel.dimensions()` 先查内置维度表，查不到就真调一次 embedding 接口量长度。本项目 1024 维。换了模型维度变了却不重建索引，插入时报 `Vector dimension mismatch`——**而且只在下标数据时才暴露**。

### 18. KNN「先截断后过滤」：政策片段被商品语料挤掉

**现象**：政策类问题的 top5 全是商品片段（0.69），政策片段一条没有。

**关键数据**：把候选池开到 40 条后，政策片段其实**分数更高**（0.76 / 0.81），却进不了 top5。因为 RediSearch 的 KNN 是「先取 K 个最近邻、再按 `similarityThreshold` 过滤」，而商品语料 341 块 vs 政策 21 块，密度差 16 倍。

**处理**：`ai.rag.fetch-multiplier` 2 → 8（候选池 10 → 40），A 类通过率 5/6 → 6/6。

**结论**：`similarity-threshold` 只决定「已进候选的要不要」，`topK`/候选池才决定「谁能进候选」。

### 19. 阈值 0.4 等于不过滤

Spring AI 的换算：`score = 1 - 余弦距离/2`。所以 `score=0.5` 对应「余弦相似度 0」，`score=0.4` 对应 **-0.2**。实测无关问题也能召回 0.66~0.67 的片段（dense embedding 的底噪），有效区间只有 0.66~0.90，最终取 **0.68**（再高会把能答的问题打成空召回）。

## 缓存与会话

### 20. 缓存「只写不读」：`cacheable` 在检查之后才赋值

**现象**：Redis 里有 `ai:cache:qa:*`，同一问题连问两次第二次仍 `cached:false`。

**根因**：`if (session.cacheable) { get(...) }` 写在前，`session.cacheable = cacheAllowed && policyOnly` 写在后——读的时候字段还是 false。

**更深一层**：读缓存的判定依赖 `hits` 的 `sourceType`，而 `hits` 要检索才有 → **读缓存却要求先知道检索结果，逻辑死锁**。

**处理**：读/写边界分开。读只看「无历史 + 未跳过检索」（key 只依赖 question + kbVersion）；写才额外要求「命中全是 POLICY/FAQ」（商品类带价格库存，缓存就是答错价）。

### 21. 缓存 key 变成了 `[66, -35, 77, 30, ...]`

`Arrays.toString(sha256(...))` 是 **byte[] 的调试表示**：方括号、逗号、负号，长度约 200 字符。改成 `DigestUtils.sha256Hex(...)`（64 字符 hex，可读可比对）。

### 22. 缓存命中时 `usedContext` 恒为 false，引用命中率被低估

`buildPayload()` 里 `usedContext = !hits.isEmpty()`，而缓存回放路径没检索、`hits` 为空。修法：缓存分支用 `cached.usedContext()` 覆盖 payload 与指标 tag。

### 23. 提示词里写「占位号码」会变成权威幻觉

为了脱敏在提示词里写了 `400-000-0000` 当备用客服电话，结果模型在 4 条回答里把它当真实号码返回给用户。

**处理**：不给号码、只给规则——「只能用片段里出现的号码；没有就说资料中未提供联系方式」。**脱敏不要用看起来像真的假数据。**

## 流式与并发

### 24. `Flux` 是冷流，不 `subscribe` 一个字节都不发

`stream()` 返回冷流；`toStream().collect()` / `blockLast()` 会把流式退化成「等全文生成再一次性返回」，首字延迟从数百毫秒劣化到全文耗时。

### 25. `SseEmitter` 不是线程安全的，心跳与 delta 会交错写坏帧

心跳线程和模型回调线程并发 `send` 时，SSE 帧边界会被破坏（前端 JSON 解析失败，且偶发难复现）。**处理**：所有 `send` 走同一把锁；`done`/`error` 用 `AtomicBoolean` 守卫，保证终止帧有且只有一个；`onCompletion`/`onTimeout`/`onError` 都要 `dispose` 上游，否则用户走了模型还在烧 token。

### 26. `CompletableFuture.runAsync` 里读 MDC 永远为空

工具日志的 `trace_id` 全是 `untracked`，根因就在这里：公共 ForkJoinPool 的工作线程没有 MDC。**处理**：显式传 traceId，并把落库线程换成命名的专属小线程池（有界队列）。

## 评测

### 27. 评测脚本本身有 5 个测量缺陷，导致 12 个假失败

原始脚本报 33%，逐条对后端协议后发现：用 SSE 帧字段判断工具调用（后端不发）、把商品 ID 断言在回答文本上、来源断言依赖不存在的字段、拒答只认「无法」、关键词无同义变体。修完重跑是 60%。

**教训**：评测结果不可信时，**先检查评测工具，再改系统**。假失败当真去修，会把正确实现改坏。

### 28. `eval-set.tsv` 被 GBK 保存后行数减半、列数爆炸

UTF-8 中文的第二字节被当成新字符起始，把后面的换行一起吞了：31 行变 29 行、每行 9 列变 30 列。

**处理**：`tools/check_eval_set.py` 校验（UTF-8 可解码、无 BOM、无 `U+FFFD`、表头一致、每行 9 列、30 条、各类 6 条）。**编辑规则**：VS Code + UTF-8，不用记事本另存 ANSI、不用 Excel 打开 TSV。

### 29. 环境故障会让整轮评测作废（三次白跑）

| 现象 | 真因 |
|---|---|
| 30 条全 `RETRIEVE_FAILED` | DNS 瞬断（`UnresolvedAddressException`），health 却是 UP |
| D 类全「未调用工具」 | item-service 被外部结束，工具抛连接拒绝 |
| 打包失败 `Unable to rename` | 运行中的实例占着 jar（文件锁） |

**处理**：`tools/run_eval.py` 跑之前做两步自检：真打一次 `/dev/probe/kb`（验证 embedding + 向量库这条链），再打一次 `/dev/probe/search`（验证 hmall 商品服务）。health 通不代表检索通。
