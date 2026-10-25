package com.mall.ai.dto;

import lombok.Data;

/** 上传入库结果，对应 docs/architecture.md 4.2 的上传响应体。 */
@Data
public class UploadVO {

    /** 文档 id；重复上传同一份内容时返回已有文档的 id */
    private Long docId;

    /** 切片数量 */
    private Integer chunkCount;

    /** 0 待处理 / 1 已入库 / 2 处理失败 */
    private Integer status;

    /** true 表示内容已存在，本次没有重复入库（幂等命中） */
    private boolean existed;

    private Long costMs;
}
