package com.mall.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.ai.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * kb_document 的 Mapper。
 *
 * <p>第 2 步会用到的查询：按 {@code content_hash} 查是否存在（幂等去重）、
 * 按 {@code doc_id} 删旧切片（重建索引前清理）。
 * 单表条件查询直接用 {@code LambdaQueryWrapper}，不用自己写 XML。
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
