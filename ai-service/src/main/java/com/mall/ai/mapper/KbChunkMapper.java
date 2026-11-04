package com.mall.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.ai.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * kb_chunk 的 Mapper。
 *
 * 检索链路对不上时，先看这张表：向量库返回的 {@code vectorId} 能不能在
 * {@code kb_chunk} 里查到原文。查不到就是入库时没回写 vectorId。
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 按向量库返回的一组 vectorId 批量取原文，顺序由调用方自己排。
     *
     * 为什么不逐条 {@code selectById}：一次检索命中 5 条，逐条查就是 5 次往返。
     * 这里用一条 {@code IN} 查回来，成本差 5 倍。
     */
    List<KbChunk> selectByVectorIds(@Param("vectorIds") List<String> vectorIds);
}