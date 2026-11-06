package com.mall.ai.service;

import com.mall.ai.dto.KbHitVO;
import com.mall.ai.entity.KbChunk;
import com.mall.ai.entity.KbDocument;
import com.mall.ai.mapper.KbChunkMapper;
import com.mall.ai.mapper.KbDocumentMapper;
import com.mall.ai.reader.ChunkQualityFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索：问题 → 向量库 → 命中片段（含原文与来源）。
 *
 * <p>用 vectorId 反查 MySQL 取原文和标题，而不是依赖向量库返回的 metadata：
 * 索引只声明了 content 与 embedding，metadata 没进索引，取回是否可用取决于实现细节。
 *
 * <p>检索为空是正常结果，不是异常。上层必须据此拒绝回答，不能拿空上下文去问模型。
 */
@Slf4j
@Service
public class RagService {

    private final VectorStore vectorStore;
    private final KbChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;
    private final ChunkQualityFilter qualityFilter;

    @Value("${ai.rag.top-k:5}")
    private int topK;

    @Value("${ai.rag.similarity-threshold:0.4}")
    private double similarityThreshold;

    /** 多取几倍候选，过滤掉噪声片段后再截断到 topK */
    @Value("${ai.rag.fetch-multiplier:2}")
    private int fetchMultiplier;

    @Value("${ai.rag.filter-fragments:true}")
    private boolean filterFragments;

    public RagService(VectorStore vectorStore,
                      KbChunkMapper chunkMapper,
                      KbDocumentMapper documentMapper,
                      @Value("${ai.rag.min-chunk-chars:50}") int minChunkChars,
                      @Value("${ai.rag.yes-no-token-threshold:3}") int yesNoTokenThreshold) {
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.qualityFilter = new ChunkQualityFilter(minChunkChars, yesNoTokenThreshold);
    }

    public List<KbHitVO> retrieve(String question) {
        return retrieve(question, topK, similarityThreshold);
    }

    /**
     * @param k         最终返回条数上限
     * @param threshold 相似度阈值；0 表示不过滤
     */
    public List<KbHitVO> retrieve(String question, int k, double threshold) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        // 多取候选：噪声片段被过滤后，还要凑得满 k 条
        int fetch = filterFragments ? Math.max(k, k * fetchMultiplier) : k;
        List<KbHitVO> candidates = search(question, fetch, threshold);

        List<KbHitVO> hits = candidates;
        if (filterFragments) {
            List<KbHitVO> clean = candidates.stream()
                    .filter(hit -> !qualityFilter.isFragment(hit.getContent()))
                    .toList();
            if (clean.isEmpty()) {
                // 兜底：过滤后一条不剩，说明这段资料大量是表格流水，宁可返回原始结果也不要空手而归
                log.warn("过滤后无剩余片段，退回未过滤结果：{} 条", candidates.size());
            } else {
                hits = clean;
            }
        }

        List<KbHitVO> result = hits.size() > k ? hits.subList(0, k) : hits;
        log.info("检索：候选={} 过滤后={} 返回={} (k={} threshold={})",
                candidates.size(), hits.size(), result.size(), k, threshold);
        return new ArrayList<>(result);
    }

    /** 纯向量库查询 + 反查原文，不做质量过滤。 */
    private List<KbHitVO> search(String question, int fetch, double threshold) {
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(Math.max(1, fetch))
                .similarityThreshold(threshold)
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        if (documents == null || documents.isEmpty()) {
            log.info("检索无命中：fetch={} threshold={}", fetch, threshold);
            return List.of();
        }

        // 保持向量库给出的顺序：命中越靠前，提示词里越靠前
        List<String> vectorIds = documents.stream().map(Document::getId).toList();
        Map<String, KbChunk> chunkByVectorId =
                chunkMapper.selectByVectorIds(vectorIds).stream()
                .collect(Collectors.toMap(KbChunk::getVectorId, c -> c, (a, b) -> a));

        Map<String, Double> scoreByVectorId = documents.stream()
                .filter(d -> d.getScore() != null)
                .collect(Collectors.toMap(Document::getId, Document::getScore, (a, b) -> a));

        Set<Long> docIds = chunkByVectorId.values().stream()
                .map(KbChunk::getDocId)
                .collect(Collectors.toSet());
        Map<Long, KbDocument> docById = new HashMap<>();
        if (!docIds.isEmpty()) {
            for (KbDocument document : documentMapper.selectBatchIds(docIds)) {
                docById.put(document.getId(), document);
            }
        }

        List<KbHitVO> hits = new ArrayList<>(vectorIds.size());
        for (String vectorId : vectorIds) {
            KbChunk chunk = chunkByVectorId.get(vectorId);
            if (chunk == null) {
                // 向量库有、MySQL 没有：孤儿向量。记录下来，否则表现为「回答没有出处」
                log.warn("孤儿向量：vectorId={} 在 kb_chunk 中不存在", vectorId);
                continue;
            }
            // 从向量库的 Document 上取下相似度，挂到命中结果里
            KbHitVO hit = toHit(chunk, docById.get(chunk.getDocId()));
            hit.setScore(scoreByVectorId.get(vectorId));
            hits.add(hit);
        }
        return hits;
    }

    /**
     * 阈值扫描：同一问题在不同阈值下的命中数。
     *
     * <p>阈值不能拍脑袋。先看这条曲线，找「召回开始掉、但仍能命中目标段落」的那一档。
     * 每次调用会重新算一次 query 向量，属于调试用途，不要放进业务链路。
     *
     * @return 阈值 → 命中数，按阈值升序
     */
    public Map<Double, Integer> sweep(String question, double[] thresholds, int k) {
        Map<Double, Integer> result = new LinkedHashMap<>();
        for (double threshold : thresholds) {
            result.put(threshold, retrieve(question, k, threshold).size());
        }
        return result;
    }

    private KbHitVO toHit(KbChunk chunk, KbDocument document) {
        KbHitVO hit = new KbHitVO();
        hit.setVectorId(chunk.getVectorId());
        hit.setDocId(chunk.getDocId());
        hit.setChunkIndex(chunk.getChunkIndex());
        hit.setContent(chunk.getContent());
        hit.setSnippet(chunk.getContent() == null ? "" : truncate(chunk.getContent(), 120));
        if (document != null) {
            hit.setTitle(document.getTitle());
            hit.setSourceType(document.getSourceType());
        }
        return hit;
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
