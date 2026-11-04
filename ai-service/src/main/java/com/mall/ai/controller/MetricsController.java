package com.mall.ai.controller;

import com.mall.ai.common.Result;
import com.mall.ai.mapper.ToolCallLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标查询。
 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final ToolCallLogMapper toolCallLogMapper;

    /** @param hours 回看小时数，默认 24 */
    @GetMapping("/tools")
    public Result<Map<String, Object>> tools(@RequestParam(value = "hours", defaultValue = "24") int hours) {
        LocalDateTime from = LocalDateTime.now().minusHours(Math.max(1, hours));
        List<Map<String, Object>> rows = toolCallLogMapper.statByTool(from);

        // 补一列成功率，避免前端自己算（也避免除零）
        for (Map<String, Object> row : rows) {
            // 总调用数
            long total = ((Number) row.get("total")).longValue();
            // 成功数
            long ok = row.get("successCount") == null ? 0 : ((Number) row.get("successCount")).longValue();
            // 成功率 = 成功数 / 总数
            row.put("successRate", total == 0 ? 0.0 : Math.round(ok * 10000.0 / total) / 100.0);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from.toString());
        data.put("hours", hours);
        data.put("tools", rows);
        return Result.ok(data);
    }
}