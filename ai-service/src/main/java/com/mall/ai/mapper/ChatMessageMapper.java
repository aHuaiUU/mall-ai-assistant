package com.mall.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.ai.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}