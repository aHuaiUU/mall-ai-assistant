package com.mall.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识切片（表 {@code kb_chunk}）。
 *
 * <p>MySQL 存原文与来源，向量库存向量，两边用 {@code vectorId} 关联。
 * 这样做的原因：向量库只擅长相似度检索，**按文档删除、按来源过滤、原文回显**都得回 MySQL。
 *
 * <p>{@code vectorId} 必须是入库时生成并回写的，<b>不要用自增 id 反推</b>——
 * 一旦重试入库，自增 id 会变，而向量库里已经写进去的 key 不会跟着变。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    /** 文档内序号，从 0 开始，配合 docId 唯一 */
    private Integer chunkIndex;

    private String content;

    /** 预估 token 数，用于控制拼接进提示词的上下文长度 */
    private Integer tokenCount;

    /** 向量库主键，形如 embedding:kb:{uuid} */
    private String vectorId;

    private LocalDateTime createTime;
}
