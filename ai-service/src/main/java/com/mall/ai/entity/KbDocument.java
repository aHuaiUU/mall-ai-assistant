package com.mall.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档（表 {@code kb_document}）。
 *
 * 注意：
 *
 *   - {@code contentHash} 是唯一索引，重复上传同一份内容会撞 {@code uk_hash}，
 *       入库前必须先按它查一次，别指望数据库报错来兜底——那样拿不到可读的错误信息。
 *   - {@code status}：0 待处理 / 1 已入库 / 2 处理失败，失败时写 {@code errorMsg}。
 *   - {@code chunkCount} 是冗余计数，入库成功后回写，用来和 {@code kb_chunk} 对数。
 *
 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** CATEGORY 商品参数 / POLICY 售后政策 / FAQ 常见问题 / MANUAL 操作手册 */
    private String sourceType;

    private String sourceUrl;

    /** 正文 SHA-256，六十四位十六进制 */
    private String contentHash;

    private Integer chunkCount;

    /** 0 待处理 / 1 已入库 / 2 处理失败 */
    private Integer status;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}