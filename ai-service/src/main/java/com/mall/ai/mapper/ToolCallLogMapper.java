package com.mall.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.ai.entity.ToolCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** tool_call_log 的 Mapper。第 7 步用它算工具成功率与平均耗时。 */
@Mapper
public interface ToolCallLogMapper extends BaseMapper<ToolCallLog> {
    /**
     * 按工具聚合近 N 小时的调用情况。
     *
     * <p>口径约定（写进注释，避免以后各算各的）：
     * 「成功」= success=1，包含「查到了」和「查了但没结果」两种；
     * 「失败」只指下游异常/超时。空结果不是失败——那是正常业务结果。
     */
    List<Map<String, Object>> statByTool(@Param("from") LocalDateTime from);
}
