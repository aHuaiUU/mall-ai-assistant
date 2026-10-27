package com.mall.ai.dto;

import lombok.Data;

/**
 * 一条检索命中。
 *
 * <p>字段来自两处：{@code vectorId} 与相似度来自向量库，其余靠 {@code vectorId} 反查
 * {@code kb_chunk}（原文）和 {@code kb_document}（标题）。
 *
 * <p>为什么不直接用向量库返回的 metadata：索引只声明了 content 和 embedding 两个字段，
 * metadata 没进索引，取回是否可用取决于实现细节。用 vectorId 反查 MySQL 是确定的。
 */
@Data
public class KbHitVO {

    private String vectorId;

    private Long docId;

    /** 文档标题，用于回答里标注来源 */
    private String title;

    private String sourceType;

    private Integer chunkIndex;

    /** 切片原文 */
    private String content;

    /** 用于日志和调试的截断预览 */
    private String snippet;
}
