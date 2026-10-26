package com.mall.ai.reader;

import com.mall.ai.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按文件扩展名分派文档解析器。
 *
 * 两条路线，选型理由不一样：
 *
 *   - Tika（PDF / Word / PPT / Excel）：二进制格式必须靠解析库，Tika 一个依赖覆盖全部，
 *       代价是体积大。它内部会做编码探测，中文 PDF 不用额外配置。
 *   - TextReader（txt / md / csv）：纯文本，不需要 Tika。
 *       但必须显式指定 UTF-8——该类默认走 {@code Charset.defaultCharset()}，
 *       在中文 Windows 上是 GBK，读 UTF-8 的中文文件会整篇乱码，
 *       而且不会报错，只是入库的向量全是垃圾。
 *
 * 不认识的扩展名直接拒绝，不猜。猜错的代价是把二进制垃圾送进向量库，
 * 之后要清库才发现。
 */
@Slf4j
@Component
public class DocumentReaderFactory {

    /** 交给 Tika 的二进制格式 */
    private static final Set<String> BINARY_EXT = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "rtf", "odt", "ods", "odp");

    /** 纯文本，用 TextReader + 固定 UTF-8 */
    private static final Set<String> PLAIN_TEXT_EXT = Set.of("txt", "md", "markdown", "csv", "text");

    /**
     * 解析成 Spring AI 的 {@link Document}。一个文件可能产出多个 Document（Tika 对某些格式会按页切）。
     *
     * DocumentReader 屏蔽 PDF / Word / md 等格式差异，统一输出纯文本，供后续切分、向量化、入库使用。
     */
    public List<Document> read(Resource resource, String filename) {
        // 扩展名转小写
        String ext = extensionOf(filename);
        DocumentReader reader;
        // 根据扩展名进行不同处理
        if (PLAIN_TEXT_EXT.contains(ext)) {
            TextReader textReader = new TextReader(resource);
            // 关键：不设就是平台默认字符集，中文必乱码
            textReader.setCharset(StandardCharsets.UTF_8);
            reader = textReader;
        } else if (BINARY_EXT.contains(ext)) {
            reader = new TikaDocumentReader(resource);
        } else {
            throw new BizException(50001, "不支持的文件类型：" + ext + "（文件：" + filename + "）");
        }

        try {
            List<Document> documents = reader.get();
            log.info("解析完成 file={} ext={} 产出文档数={}", filename, ext, documents.size());
            return documents;
        } catch (Exception e) {
            // 解析失败单独一个 code，方便前端区分「文件坏了」和「模型挂了」
            throw new BizException(50001, "文档解析失败：" + e.getMessage(), e);
        }
    }

    /** 取小写扩展名；没有扩展名返回空串。 */
    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}