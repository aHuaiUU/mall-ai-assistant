package com.mall.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.ai.entity.ToolCallLog;
import org.apache.ibatis.annotations.Mapper;

/** tool_call_log 的 Mapper。第 7 步用它算工具成功率与平均耗时。 */
@Mapper
public interface ToolCallLogMapper extends BaseMapper<ToolCallLog> {
}
