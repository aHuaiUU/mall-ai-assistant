package com.mall.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.ai.common.BizException;
import com.mall.ai.dto.UploadVO;
import com.mall.ai.entity.KbChunk;
import com.mall.ai.entity.KbDocument;
import com.mall.ai.mapper.KbChunkMapper;
import com.mall.ai.mapper.KbDocumentMapper;
import com.mall.ai.reader.DocumentReaderFactory;
import com.mall.ai.reader.SplitContext;
import com.mall.ai.reader.TextNormalizer;
import com.mall.ai.reader.TextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 知识入库编排：解析 → 归一化 → 幂等检查 → 加锁 → 切分 → 落 MySQL → 写向量 → 回写状态。
 *
 * 为什么写操作不加 {@code @Transactional}：这段流程里包含外部调用（embedding 接口、
 * Redis 向量写入），一次调用可能几秒到几十秒。包在一个数据库事务里，事务会被长时间持有，
 * 连接池很快被打满，而且外部调用失败时数据库事务回滚并不会撤销已经写进向量库的数据——
 * 事务在这里给不了你想要的一致性。所以改用「显式补偿」：记录已写入的 id，失败时按顺序清理。
 *
 * 写入顺序：先 MySQL 后向量，理由见 {@link #persistChunks}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private static final String LOCK_KEY_PREFIX = "ai:lock:doc:";

    private final DocumentReaderFactory readerFactory;
    private final TextNormalizer textNormalizer;
    private final TextSplitter textSplitter;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final VectorStore vectorStore;
    private final StringRedisTemplate redisTemplate;

    //切分大小
    @Value("${ai.ingest.chunk-size:500}")
    private int chunkSize;
    //重叠字节
    @Value("${ai.ingest.overlap:80}")
    private int overlap;

    @Value("${ai.ingest.embed-batch-size:20}")
    private int embedBatchSize;

    @Value("${ai.ingest.lock-ttl-seconds:60}")
    private long lockTtlSeconds;

    /**
     * 上传并入库
     *
     * @param title      表单传入优先；为空则取文件名（去扩展名）
     * @param sourceType 表单传入优先；为空则按文件名推断
     */
    public UploadVO ingest(MultipartFile file, String title, String sourceType) {
        long start = System.currentTimeMillis();
        //获取文件名
        String filename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        //标题为空则以文件明标题，反之标题命名并清洗开头结尾空白
        String finalTitle = (title == null || title.isBlank()) ? stripExtension(filename) : title.strip();
        //判断文件类型 政策 问答 手册
        String finalType = (sourceType == null || sourceType.isBlank()) ? inferSourceType(filename) : sourceType.strip();

        // 1) read解析 + 归一化
        String rawText = readerFactory.read(file.getResource(), filename).stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        // 1.1 得到清洗后内容
        String normalized = textNormalizer.normalize(rawText);
        if (normalized.isBlank()) {
            // 扫描件 PDF 会走到这里：Tika 抽不出文字。必须显式失败，
            // 否则会入库一堆空切片，检索时表现为「知识库什么都搜不到」但没有任何报错。
            throw new BizException(50001, "文档没有可解析的文字（可能是扫描件，需要先 OCR）：" + filename);
        }

        // 2) 幂等：正文 hash 唯一
        String contentHash = sha256(normalized);
        // 2.1查询是否已存在
        KbDocument existing = documentMapper.selectOne(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getContentHash, contentHash));
        if (existing != null) {
            //2.2 不存在直接构建VO返回
            log.info("幂等命中：docId={} hash={} status={}", existing.getId(), contentHash, existing.getStatus());
            return buildResult(existing.getId(), existing.getChunkCount(), existing.getStatus(), true, start);
        }

        // 3) 分布式锁：防同一份文档并发入库
        //  同一份文件只入库一次（幂等）
        String lockKey = LOCK_KEY_PREFIX + contentHash;
        // 抢锁，防两个人同时传同一份
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, UUID.randomUUID().toString(), Duration.ofSeconds(lockTtlSeconds));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BizException(429, "同一份文档正在入库，请稍后再试");
        }

        KbDocument document = new KbDocument();
        try {
            document.setTitle(finalTitle);
            document.setSourceType(finalType);
            document.setContentHash(contentHash);
            document.setChunkCount(0);
            document.setStatus(0);
            documentMapper.insert(document);

            // 4) 切分
            // 是否有二级标题
            boolean hasHeadings = normalized.lines().anyMatch(l -> l.startsWith("## "));
            SplitContext ctx = new SplitContext(finalType, finalTitle, chunkSize, overlap, hasHeadings);
            // 传入切分条件与内容
            List<String> pieces = textSplitter.split(normalized, ctx);
            if (pieces.isEmpty()) {
                throw new BizException(50001, "切分结果为空：" + filename);
            }
            log.info("切分完成：docId={} 块数={} 平均块长={} 有结构={}",
                    document.getId(), pieces.size(),
                    pieces.stream().mapToInt(String::length).average().orElse(0), hasHeadings);

            // 5) 先落 MySQL（含 vector_id），再写向量
            List<String> vectorIds = persistChunks(document, pieces, finalTitle, finalType);

            // 6) 分批写向量：一次 add 太多会被上游 embedding 接口限流
            writeVectors(pieces, vectorIds, document.getId(), finalTitle, finalType);

            // 7) 状态回写
            KbDocument done = new KbDocument();
            done.setId(document.getId());
            done.setStatus(1);
            done.setChunkCount(pieces.size());
            documentMapper.updateById(done);

            log.info("入库完成：docId={} 块数={} 耗时={}ms",
                    document.getId(), pieces.size(), System.currentTimeMillis() - start);
            return buildResult(document.getId(), pieces.size(), 1, false, start);

        } catch (Exception e) {
            log.error("入库失败：docId={} 原因={}", document.getId(), e.getMessage(), e);
            compensate(document.getId(), e);
            throw (e instanceof BizException be) ? be
                    : new BizException(50002, "入库失败：" + e.getMessage(), e);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 先写 MySQL 分片行（含 vector_id），返回 id 列表。
     *
     * 顺序为什么是这个：两种顺序都会留残留，但危险程度不同。
     * 先写向量再写 MySQL，MySQL 失败会留下孤儿向量——它会出现在检索结果里，
     * 却反查不到原文和 docId，表现为「回答没有出处」或者空指针，而且不去对账根本发现不了。
     * 反过来先写 MySQL，向量失败只留下「有行没向量」，检索上等价于没入库，
     * 无害，且 status=2 明确标记，重试即可。
     */
    private List<String> persistChunks(KbDocument document, List<String> pieces,
                                       String title, String sourceType) {
        List<String> vectorIds = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            // vector_id 自己生成，不用自增 id 反推：重试时自增 id 会变，向量库里的 key 不会跟着变
            String vectorId = UUID.randomUUID().toString();

            KbChunk chunk = new KbChunk();
            chunk.setDocId(document.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(pieces.get(i));
            chunk.setTokenCount(estimateTokens(pieces.get(i)));
            chunk.setVectorId(vectorId);
            chunkMapper.insert(chunk);

            vectorIds.add(vectorId);
        }
        return vectorIds;
    }

    /** 分批写向量库；任何一批失败都向上抛，由 ingest 做补偿。 */
    private void writeVectors(List<String> pieces, List<String> vectorIds,
                              Long docId, String title, String sourceType) {
        int total = pieces.size();
        for (int from = 0; from < total; from += embedBatchSize) {
            int to = Math.min(from + embedBatchSize, total);
            List<Document> batch = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("docId", docId);
                metadata.put("chunkIndex", i);
                metadata.put("title", title);
                metadata.put("sourceType", sourceType);
                batch.add(Document.builder()
                        .id(vectorIds.get(i))   // Redis key = 前缀 + 这个 id
                        .text(pieces.get(i))
                        .metadata(metadata)
                        .build());
            }
            vectorStore.add(batch);
            log.debug("向量写入 {}-{}/{}", from, to, total);
        }
    }

    /**
     * 失败补偿。先删向量，再删 MySQL 行，顺序不能反：
     * 反了会在「删完行、删向量失败」时留下孤儿向量——最危险的残留。
     */
    private void compensate(Long docId, Exception cause) {
        try {
            List<KbChunk> chunks = chunkMapper.selectList(
                    new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, docId));
            if (!chunks.isEmpty()) {
                // 逐条删向量而不是批量：某一条失败不影响其他条，且能记录是哪一条
                for (KbChunk chunk : chunks) {
                    try {
                        vectorStore.delete(List.of(chunk.getVectorId()));
                    } catch (Exception ignored) {
                        log.warn("补偿时删除向量失败 vectorId={}", chunk.getVectorId());
                    }
                }
                chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, docId));
            }
            KbDocument failed = new KbDocument();
            failed.setId(docId);
            failed.setStatus(2);
            failed.setErrorMsg(truncate(cause.getMessage(), 480));
            failed.setChunkCount(0);
            documentMapper.updateById(failed);
        } catch (Exception e) {
            // 补偿本身失败只能记日志，不能再抛——否则会把原始异常盖掉
            log.error("补偿失败 docId={}，需要人工清理", docId, e);
        }
    }

    /**
     * 删除文档：按 docId 查 vector_id → 删向量 → 删 MySQL 行 → 逻辑删除文档。
     *
     * 索引里没有声明 metadata 字段，所以不能用 filterExpression 按 docId 过滤删除，
     * 只能这样「先查 id 再按 id 删」。
     */
    public void purge(Long docId) {
        List<KbChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, docId));
        if (!chunks.isEmpty()) {
            vectorStore.delete(chunks.stream().map(KbChunk::getVectorId).toList());
            chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, docId));
        }
        documentMapper.deleteById(docId);
    }

    /** 按字符估 token（中文 1 字≈1 token，偏保守）。只用于控制上下文长度，不需要精确。 */
    private int estimateTokens(String text) {
        return text == null ? 0 : text.length();
    }

    //通过
    private String inferSourceType(String filename) {
        //大写字符转小写
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.contains("政策") || lower.contains("policy") || lower.contains("售后")) {
            return "POLICY";
        }
        if (lower.contains("faq") || lower.contains("常见") || lower.contains("问答")) {
            return "FAQ";
        }
        if (lower.contains("手册") || lower.contains("manual")) {
            return "MANUAL";
        }
        return "CATEGORY";
    }

    //去除扩展名获取文件名
    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // 构建UploadVO内容
    private UploadVO buildResult(Long docId, Integer chunkCount, Integer status, boolean existed, long start) {
        UploadVO vo = new UploadVO();
        vo.setDocId(docId);
        vo.setChunkCount(chunkCount == null ? 0 : chunkCount);
        vo.setStatus(status);
        vo.setExisted(existed);
        vo.setCostMs(System.currentTimeMillis() - start);
        return vo;
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}