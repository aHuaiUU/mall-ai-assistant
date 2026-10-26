package com.mall.ai.controller;

import com.mall.ai.common.BizException;
import com.mall.ai.common.Result;
import com.mall.ai.dto.UploadVO;
import com.mall.ai.entity.KbDocument;
import com.mall.ai.mapper.KbDocumentMapper;
import com.mall.ai.service.IngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理接口，对应 docs/architecture.md 4.2。
 *
 * {@code rebuild}（按 docId 重建索引）暂不提供：重建 = purge + 用新切分参数重新入库，
 * 而重新入库需要原始文件。等第 8 步调切分参数时再补，届时把原始文件也存一份。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final IngestService ingestService;
    private final KbDocumentMapper documentMapper;

    /**
     * 上传并入库。
     *
     * @param title      可选。为空则用文件名（去掉扩展名）——表单优先、文件名兜底
     * @param sourceType 可选。CATEGORY / POLICY / FAQ / MANUAL，为空则按文件名推断
     */
    @PostMapping("/upload")
    public Result<UploadVO> upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "title", required = false) String title,
                                   @RequestParam(value = "sourceType", required = false) String sourceType) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }
        UploadVO vo = ingestService.ingest(file, title, sourceType);
        return Result.ok(vo);
    }

    @GetMapping("/{docId}")
    public Result<KbDocument> get(@PathVariable("docId") Long docId) {
        KbDocument document = documentMapper.selectById(docId);
        if (document == null) {
            throw new BizException(404, "文档不存在：" + docId);
        }
        return Result.ok(document);
    }

    /** 逻辑删除文档，同时按 vector_id 清理向量与切片。 */
    @DeleteMapping("/{docId}")
    public Result<Void> delete(@PathVariable("docId") Long docId) {
        ingestService.purge(docId);
        return Result.ok(null);
    }
}